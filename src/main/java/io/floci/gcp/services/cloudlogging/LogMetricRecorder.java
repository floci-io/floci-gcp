package io.floci.gcp.services.cloudlogging;

import com.google.api.Distribution;
import com.google.api.LabelDescriptor;
import com.google.api.Metric;
import com.google.api.MetricDescriptor;
import com.google.api.MonitoredResource;
import com.google.logging.v2.LogMetric;
import com.google.monitoring.v3.Point;
import com.google.monitoring.v3.TimeInterval;
import com.google.monitoring.v3.TimeSeries;
import com.google.monitoring.v3.TypedValue;
import com.google.protobuf.Timestamp;
import io.floci.gcp.services.cloudlogging.model.StoredLogEntry;
import io.floci.gcp.services.cloudmonitoring.CloudMonitoringService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Turns the entries one write delivers into one DELTA point per series. Real Logging flushes
 * in-memory counters every minute; the emulator writes when entries arrive, with each point's
 * interval starting where the series' previous point ended, so aligned reads give the same totals.
 * Callers hold the metric lock.
 */
final class LogMetricRecorder {
    static final String LOG_LABEL = "log";
    private static final int MAX_SERIES_PER_WRITE = 200;
    private static final Duration FIRST_INTERVAL = Duration.ofMinutes(1);

    private final CloudMonitoringService monitoring;
    private final Clock clock;
    private final Map<String, Instant> lastEnd = new HashMap<>();

    LogMetricRecorder(CloudMonitoringService monitoring, Clock clock) {
        this.monitoring = monitoring;
        this.clock = clock;
    }

    void record(String project, String metricName, LogMetric metric, List<StoredLogEntry> entries) {
        var matches = SinkLogEntryFilter.parse(metric.getFilter());
        MetricDescriptor descriptor = metric.getMetricDescriptor();
        boolean distribution = descriptor.getValueType() == MetricDescriptor.ValueType.DISTRIBUTION;
        Map<String, LogMetricExtractor> labelExtractors = new TreeMap<>();
        metric.getLabelExtractorsMap().forEach((key, expression) -> labelExtractors.put(key, LogMetricExtractor.parse(expression)));
        Map<String, LabelDescriptor.ValueType> labelTypes = new HashMap<>();
        descriptor.getLabelsList().forEach(label -> labelTypes.put(label.getKey(), label.getValueType()));
        LogMetricExtractor valueExtractor = distribution ? LogMetricExtractor.parse(metric.getValueExtractor()) : null;

        Map<SeriesKey, List<Double>> series = new LinkedHashMap<>();
        for (StoredLogEntry entry : entries) {
            if (!matches.test(entry)) {
                continue;
            }
            Map<String, String> labels = new TreeMap<>();
            labels.put(LOG_LABEL, logId(entry.getLogName()));
            labelExtractors.forEach((key, extractor) -> labels.put(key,
                    labelValue(extractor.extract(entry), labelTypes.getOrDefault(key, LabelDescriptor.ValueType.STRING))));
            SeriesKey key = new SeriesKey(labels, entry.getResourceType() == null ? "global" : entry.getResourceType(),
                    entry.getResourceLabels() == null ? Map.of() : new TreeMap<>(entry.getResourceLabels()));
            if (distribution) {
                Optional<Double> value = valueExtractor.extract(entry).flatMap(LogMetricRecorder::toDouble);
                if (value.isEmpty()) {
                    continue;
                }
                series.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value.get());
            } else {
                series.computeIfAbsent(key, ignored -> new ArrayList<>()).add(1.0);
            }
        }
        if (series.isEmpty()) {
            return;
        }

        Instant now = clock.instant();
        String type = descriptor.getType();
        List<TimeSeries> points = new ArrayList<>();
        for (Map.Entry<SeriesKey, List<Double>> one : series.entrySet()) {
            String identity = metricName + "|" + one.getKey();
            Instant previous = lastEnd.get(identity);
            Instant end = previous != null && !now.isAfter(previous) ? previous.plusMillis(1) : now;
            Instant start = previous != null ? previous : end.minus(FIRST_INTERVAL);
            TypedValue value = distribution
                    ? TypedValue.newBuilder().setDistributionValue(distribution(one.getValue(), metric.getBucketOptions())).build()
                    : TypedValue.newBuilder().setInt64Value(one.getValue().size()).build();
            points.add(TimeSeries.newBuilder()
                    .setMetric(Metric.newBuilder().setType(type).putAllLabels(one.getKey().labels()))
                    .setResource(MonitoredResource.newBuilder().setType(one.getKey().resourceType())
                            .putAllLabels(one.getKey().resourceLabels()))
                    .setMetricKind(MetricDescriptor.MetricKind.DELTA)
                    .setValueType(descriptor.getValueType())
                    .addPoints(Point.newBuilder()
                            .setInterval(TimeInterval.newBuilder().setStartTime(timestamp(start)).setEndTime(timestamp(end)))
                            .setValue(value))
                    .build());
            lastEnd.put(identity, end);
        }
        for (int from = 0; from < points.size(); from += MAX_SERIES_PER_WRITE) {
            monitoring.createTimeSeries(project, points.subList(from, Math.min(points.size(), from + MAX_SERIES_PER_WRITE)));
        }
    }

    void forget(String metricName) {
        lastEnd.keySet().removeIf(identity -> identity.startsWith(metricName + "|"));
    }

    static Distribution distribution(List<Double> values, Distribution.BucketOptions options) {
        double[] bounds = bounds(options);
        long[] counts = new long[bounds.length + 1];
        double sum = 0;
        for (double value : values) {
            sum += value;
            int bucket = Arrays.binarySearch(bounds, value);
            counts[bucket >= 0 ? bucket + 1 : -bucket - 1]++;
        }
        double mean = sum / values.size();
        double squaredDeviation = 0;
        for (double value : values) {
            squaredDeviation += (value - mean) * (value - mean);
        }
        Distribution.Builder result = Distribution.newBuilder().setCount(values.size()).setMean(mean)
                .setSumOfSquaredDeviation(squaredDeviation).setBucketOptions(options);
        for (long count : counts) {
            result.addBucketCounts(count);
        }
        return result.build();
    }

    /** Finite bucket lower bounds; bucket 0 is the underflow bucket and the last is the overflow bucket. */
    private static double[] bounds(Distribution.BucketOptions options) {
        return switch (options.getOptionsCase()) {
            case LINEAR_BUCKETS -> {
                var linear = options.getLinearBuckets();
                double[] bounds = new double[linear.getNumFiniteBuckets() + 1];
                for (int i = 0; i < bounds.length; i++) {
                    bounds[i] = linear.getOffset() + linear.getWidth() * i;
                }
                yield bounds;
            }
            case EXPONENTIAL_BUCKETS -> {
                var exponential = options.getExponentialBuckets();
                double[] bounds = new double[exponential.getNumFiniteBuckets() + 1];
                for (int i = 0; i < bounds.length; i++) {
                    bounds[i] = exponential.getScale() * Math.pow(exponential.getGrowthFactor(), i);
                }
                yield bounds;
            }
            case EXPLICIT_BUCKETS -> options.getExplicitBuckets().getBoundsList().stream()
                    .mapToDouble(Double::doubleValue).toArray();
            case OPTIONS_NOT_SET -> new double[0];
        };
    }

    private static String labelValue(Optional<String> extracted, LabelDescriptor.ValueType type) {
        return switch (type) {
            case BOOL -> String.valueOf(extracted.map(Boolean::parseBoolean).orElse(false));
            case INT64 -> extracted.flatMap(LogMetricRecorder::toLong).map(String::valueOf).orElse("0");
            default -> extracted.map(value -> value.length() > 1024 ? value.substring(0, 1024) : value).orElse("");
        };
    }

    private static Optional<Long> toLong(String value) {
        try {
            return Optional.of(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<Double> toDouble(String value) {
        try {
            double parsed = Double.parseDouble(value.trim());
            return Double.isFinite(parsed) ? Optional.of(parsed) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** The LOG_ID part of the log name, still URL-encoded, as real Logging reports it. */
    private static String logId(String logName) {
        int logs = logName.indexOf("/logs/");
        return logs < 0 ? logName : logName.substring(logs + "/logs/".length());
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    private record SeriesKey(Map<String, String> labels, String resourceType, Map<String, String> resourceLabels) {}
}
