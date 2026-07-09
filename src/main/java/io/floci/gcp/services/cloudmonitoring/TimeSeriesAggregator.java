package io.floci.gcp.services.cloudmonitoring;

import com.google.api.Metric;
import com.google.api.MetricDescriptor;
import com.google.api.MonitoredResource;
import com.google.monitoring.v3.Aggregation;
import com.google.monitoring.v3.Point;
import com.google.monitoring.v3.TimeInterval;
import com.google.monitoring.v3.TimeSeries;
import com.google.monitoring.v3.TypedValue;
import com.google.protobuf.Timestamp;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.services.cloudmonitoring.model.StoredTimeSeriesPoint;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class TimeSeriesAggregator {

    private static final Set<Aggregation.Aligner> SUPPORTED_ALIGNERS = EnumSet.of(
            Aggregation.Aligner.ALIGN_NONE,
            Aggregation.Aligner.ALIGN_SUM,
            Aggregation.Aligner.ALIGN_MEAN,
            Aggregation.Aligner.ALIGN_MIN,
            Aggregation.Aligner.ALIGN_MAX,
            Aggregation.Aligner.ALIGN_COUNT,
            Aggregation.Aligner.ALIGN_DELTA,
            Aggregation.Aligner.ALIGN_RATE);

    private static final Set<Aggregation.Reducer> SUPPORTED_REDUCERS = EnumSet.of(
            Aggregation.Reducer.REDUCE_NONE,
            Aggregation.Reducer.REDUCE_SUM,
            Aggregation.Reducer.REDUCE_MEAN,
            Aggregation.Reducer.REDUCE_MIN,
            Aggregation.Reducer.REDUCE_MAX,
            Aggregation.Reducer.REDUCE_COUNT);

    private TimeSeriesAggregator() {}

    static void validate(Aggregation agg) {
        Aggregation.Aligner aligner = agg.getPerSeriesAligner();
        Aggregation.Reducer reducer = agg.getCrossSeriesReducer();

        if (aligner == Aggregation.Aligner.UNRECOGNIZED || !SUPPORTED_ALIGNERS.contains(aligner)) {
            throw GcpException.invalidArgument("Unsupported per_series_aligner: " + agg.getPerSeriesAlignerValue());
        }
        if (reducer == Aggregation.Reducer.UNRECOGNIZED || !SUPPORTED_REDUCERS.contains(reducer)) {
            throw GcpException.invalidArgument("Unsupported cross_series_reducer: " + agg.getCrossSeriesReducerValue());
        }
        if (reducer != Aggregation.Reducer.REDUCE_NONE && aligner == Aggregation.Aligner.ALIGN_NONE) {
            throw GcpException.invalidArgument(
                    "If cross_series_reducer is specified, per_series_aligner must be specified and not ALIGN_NONE");
        }
        if (aligner != Aggregation.Aligner.ALIGN_NONE
                && (!agg.hasAlignmentPeriod() || agg.getAlignmentPeriod().getSeconds() < 60)) {
            throw GcpException.invalidArgument(
                    "alignment_period must be specified and at least 60 seconds when a per_series_aligner is set");
        }
    }

    static List<TimeSeries> aggregate(Map<SeriesKey, List<StoredTimeSeriesPoint>> groups,
                                      Aggregation agg, Instant reqEnd) {
        Aggregation.Aligner aligner = agg.getPerSeriesAligner();
        Aggregation.Reducer reducer = agg.getCrossSeriesReducer();
        Duration period = Duration.ofSeconds(agg.getAlignmentPeriod().getSeconds(),
                agg.getAlignmentPeriod().getNanos());

        List<AlignedSeries> aligned = new ArrayList<>();
        for (Map.Entry<SeriesKey, List<StoredTimeSeriesPoint>> entry : groups.entrySet()) {
            aligned.add(alignSeries(entry.getKey(), entry.getValue(), aligner, period, reqEnd));
        }

        List<TimeSeries> result = reducer == Aggregation.Reducer.REDUCE_NONE
                ? aligned.stream().map(s -> toTimeSeries(s, period)).toList()
                : reduce(aligned, reducer, agg.getGroupByFieldsList(), period);

        return result.stream()
                .sorted(Comparator.comparing((TimeSeries ts) -> ts.getMetric().getType())
                        .thenComparing(ts -> ts.getMetric().getLabelsMap().toString())
                        .thenComparing(ts -> ts.getResource().getLabelsMap().toString()))
                .toList();
    }

    // ── Per-series alignment ────────────────────────────────────────────────

    private record AlignedSeries(
            SeriesKey key,
            MetricDescriptor.MetricKind outKind,
            MetricDescriptor.ValueType outType,
            TreeMap<Instant, Double> buckets) {}

    private static AlignedSeries alignSeries(SeriesKey key, List<StoredTimeSeriesPoint> points,
                                             Aggregation.Aligner aligner, Duration period, Instant reqEnd) {
        MetricDescriptor.MetricKind inKind = MetricDescriptor.MetricKind.valueOf(key.metricKind());
        MetricDescriptor.ValueType inType = MetricDescriptor.ValueType.valueOf(key.valueType());
        checkAlignerCompatibility(aligner, inKind, inType, key.metricType());

        long periodMs = period.toMillis();
        long reqEndMs = reqEnd.toEpochMilli();

        Map<Long, List<StoredTimeSeriesPoint>> byBucket = new LinkedHashMap<>();
        for (StoredTimeSeriesPoint pt : points) {
            long k = Math.floorDiv(reqEndMs - Instant.parse(pt.getEndTime()).toEpochMilli(), periodMs);
            byBucket.computeIfAbsent(k, x -> new ArrayList<>()).add(pt);
        }

        TreeMap<Instant, Double> buckets = new TreeMap<>();
        for (Map.Entry<Long, List<StoredTimeSeriesPoint>> bucket : byBucket.entrySet()) {
            Instant bucketEnd = Instant.ofEpochMilli(reqEndMs - bucket.getKey() * periodMs);
            List<StoredTimeSeriesPoint> pts = bucket.getValue().stream()
                    .sorted(Comparator.comparing(p -> Instant.parse(p.getEndTime())))
                    .toList();
            buckets.put(bucketEnd, alignBucket(pts, aligner, inKind, period));
        }

        return new AlignedSeries(key, outputKind(aligner, inKind), outputType(aligner, inType), buckets);
    }

    private static void checkAlignerCompatibility(Aggregation.Aligner aligner,
                                                  MetricDescriptor.MetricKind kind,
                                                  MetricDescriptor.ValueType type,
                                                  String metricType) {
        boolean numeric = type == MetricDescriptor.ValueType.INT64 || type == MetricDescriptor.ValueType.DOUBLE;
        switch (aligner) {
            case ALIGN_DELTA, ALIGN_RATE -> {
                if (kind != MetricDescriptor.MetricKind.CUMULATIVE && kind != MetricDescriptor.MetricKind.DELTA) {
                    throw GcpException.invalidArgument(String.format(
                            "%s is only valid for CUMULATIVE and DELTA metrics; %s has kind %s",
                            aligner, metricType, kind));
                }
                if (!numeric) {
                    throw GcpException.invalidArgument(String.format(
                            "%s requires numeric values; %s has value type %s", aligner, metricType, type));
                }
            }
            case ALIGN_COUNT -> {
                checkGaugeOrDelta(aligner, kind, metricType);
                if (!numeric && type != MetricDescriptor.ValueType.BOOL) {
                    throw GcpException.invalidArgument(String.format(
                            "%s requires numeric or Boolean values; %s has value type %s", aligner, metricType, type));
                }
            }
            default -> {
                checkGaugeOrDelta(aligner, kind, metricType);
                if (!numeric) {
                    throw GcpException.invalidArgument(String.format(
                            "%s requires numeric values; %s has value type %s", aligner, metricType, type));
                }
            }
        }
    }

    private static void checkGaugeOrDelta(Aggregation.Aligner aligner,
                                          MetricDescriptor.MetricKind kind, String metricType) {
        if (kind != MetricDescriptor.MetricKind.GAUGE && kind != MetricDescriptor.MetricKind.DELTA) {
            throw GcpException.invalidArgument(String.format(
                    "%s is only valid for GAUGE and DELTA metrics; %s has kind %s", aligner, metricType, kind));
        }
    }

    private static double alignBucket(List<StoredTimeSeriesPoint> pts, Aggregation.Aligner aligner,
                                      MetricDescriptor.MetricKind inKind, Duration period) {
        List<Double> values = pts.stream().map(TimeSeriesAggregator::numericValue).toList();
        return switch (aligner) {
            case ALIGN_SUM -> values.stream().mapToDouble(Double::doubleValue).sum();
            case ALIGN_MEAN -> values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            case ALIGN_MIN -> values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            case ALIGN_MAX -> values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            case ALIGN_COUNT -> values.size();
            case ALIGN_DELTA -> delta(values, inKind);
            case ALIGN_RATE -> delta(values, inKind) / Math.max(1, period.toSeconds());
            default -> throw GcpException.invalidArgument("Unsupported per_series_aligner: " + aligner);
        };
    }

    private static double delta(List<Double> values, MetricDescriptor.MetricKind inKind) {
        if (inKind == MetricDescriptor.MetricKind.CUMULATIVE) {
            return values.get(values.size() - 1) - values.get(0);
        }
        return values.stream().mapToDouble(Double::doubleValue).sum();
    }

    private static double numericValue(StoredTimeSeriesPoint pt) {
        TypedValue value = ProtoJson.merge(pt.getValueJson(), TypedValue.newBuilder()).build();
        return switch (value.getValueCase()) {
            case INT64_VALUE -> value.getInt64Value();
            case DOUBLE_VALUE -> value.getDoubleValue();
            case BOOL_VALUE -> value.getBoolValue() ? 1 : 0;
            default -> throw GcpException.invalidArgument(
                    "Values of type " + value.getValueCase() + " cannot be aggregated");
        };
    }

    private static MetricDescriptor.MetricKind outputKind(Aggregation.Aligner aligner,
                                                          MetricDescriptor.MetricKind inKind) {
        return switch (aligner) {
            case ALIGN_DELTA -> MetricDescriptor.MetricKind.DELTA;
            case ALIGN_RATE -> MetricDescriptor.MetricKind.GAUGE;
            default -> inKind;
        };
    }

    private static MetricDescriptor.ValueType outputType(Aggregation.Aligner aligner,
                                                         MetricDescriptor.ValueType inType) {
        return switch (aligner) {
            case ALIGN_MEAN, ALIGN_RATE -> MetricDescriptor.ValueType.DOUBLE;
            case ALIGN_COUNT -> MetricDescriptor.ValueType.INT64;
            default -> inType;
        };
    }

    // ── Cross-series reduction ──────────────────────────────────────────────

    private static List<TimeSeries> reduce(List<AlignedSeries> aligned, Aggregation.Reducer reducer,
                                           List<String> groupByFields, Duration period) {
        List<GroupByField> fields = groupByFields.stream().map(GroupByField::parse).toList();

        Map<String, List<AlignedSeries>> groups = new LinkedHashMap<>();
        for (AlignedSeries series : aligned) {
            StringBuilder key = new StringBuilder(series.key().metricType())
                    .append("|").append(series.key().resourceType());
            for (GroupByField field : fields) {
                key.append("|").append(field.valueOf(series.key()));
            }
            groups.computeIfAbsent(key.toString(), k -> new ArrayList<>()).add(series);
        }

        List<TimeSeries> result = new ArrayList<>();
        for (List<AlignedSeries> members : groups.values()) {
            AlignedSeries first = members.get(0);
            TreeMap<Instant, Double> reducedBuckets = new TreeMap<>();
            TreeMap<Instant, List<Double>> byTimestamp = new TreeMap<>();
            for (AlignedSeries member : members) {
                member.buckets().forEach((ts, v) ->
                        byTimestamp.computeIfAbsent(ts, x -> new ArrayList<>()).add(v));
            }
            byTimestamp.forEach((ts, values) -> reducedBuckets.put(ts, reduceValues(values, reducer)));

            Map<String, String> metricLabels = new TreeMap<>();
            Map<String, String> resourceLabels = new TreeMap<>();
            for (GroupByField field : fields) {
                field.copyLabel(first.key(), metricLabels, resourceLabels);
            }

            MetricDescriptor.ValueType outType = switch (reducer) {
                case REDUCE_MEAN -> MetricDescriptor.ValueType.DOUBLE;
                case REDUCE_COUNT -> MetricDescriptor.ValueType.INT64;
                default -> first.outType();
            };

            SeriesKey outKey = new SeriesKey(first.key().metricType(), metricLabels,
                    first.key().resourceType(), resourceLabels,
                    first.outKind().name(), outType.name());
            result.add(toTimeSeries(new AlignedSeries(outKey, first.outKind(), outType, reducedBuckets), period));
        }
        return result;
    }

    private static double reduceValues(List<Double> values, Aggregation.Reducer reducer) {
        return switch (reducer) {
            case REDUCE_SUM -> values.stream().mapToDouble(Double::doubleValue).sum();
            case REDUCE_MEAN -> values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            case REDUCE_MIN -> values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            case REDUCE_MAX -> values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            case REDUCE_COUNT -> values.size();
            default -> throw GcpException.invalidArgument("Unsupported cross_series_reducer: " + reducer);
        };
    }

    private record GroupByField(boolean metricLabel, String labelKey) {

        static GroupByField parse(String field) {
            if (field.equals("resource.type")) {
                return new GroupByField(false, null);
            }
            for (String prefix : List.of("resource.label.", "resource.labels.")) {
                if (field.startsWith(prefix)) {
                    return new GroupByField(false, field.substring(prefix.length()));
                }
            }
            for (String prefix : List.of("metric.label.", "metric.labels.")) {
                if (field.startsWith(prefix)) {
                    return new GroupByField(true, field.substring(prefix.length()));
                }
            }
            throw GcpException.invalidArgument("Unsupported group_by field: " + field);
        }

        String valueOf(SeriesKey key) {
            if (labelKey == null) {
                return "";
            }
            Map<String, String> labels = metricLabel ? key.metricLabels() : key.resourceLabels();
            return labels.getOrDefault(labelKey, "");
        }

        void copyLabel(SeriesKey key, Map<String, String> metricLabels, Map<String, String> resourceLabels) {
            if (labelKey == null) {
                return;
            }
            Map<String, String> source = metricLabel ? key.metricLabels() : key.resourceLabels();
            String value = source.get(labelKey);
            if (value != null) {
                (metricLabel ? metricLabels : resourceLabels).put(labelKey, value);
            }
        }
    }

    // ── Output ──────────────────────────────────────────────────────────────

    private static TimeSeries toTimeSeries(AlignedSeries series, Duration period) {
        TimeSeries.Builder builder = TimeSeries.newBuilder()
                .setMetric(Metric.newBuilder()
                        .setType(series.key().metricType())
                        .putAllLabels(series.key().metricLabels())
                        .build())
                .setResource(MonitoredResource.newBuilder()
                        .setType(series.key().resourceType())
                        .putAllLabels(series.key().resourceLabels())
                        .build())
                .setMetricKind(series.outKind())
                .setValueType(series.outType());

        series.buckets().descendingMap().forEach((bucketEnd, value) -> {
            TimeInterval.Builder interval = TimeInterval.newBuilder().setEndTime(timestamp(bucketEnd));
            if (series.outKind() == MetricDescriptor.MetricKind.DELTA
                    || series.outKind() == MetricDescriptor.MetricKind.CUMULATIVE) {
                interval.setStartTime(timestamp(bucketEnd.minus(period)));
            } else {
                interval.setStartTime(timestamp(bucketEnd));
            }
            TypedValue.Builder typed = TypedValue.newBuilder();
            if (series.outType() == MetricDescriptor.ValueType.INT64) {
                typed.setInt64Value(Math.round(value));
            } else {
                typed.setDoubleValue(value);
            }
            builder.addPoints(Point.newBuilder().setInterval(interval).setValue(typed).build());
        });

        return builder.build();
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
