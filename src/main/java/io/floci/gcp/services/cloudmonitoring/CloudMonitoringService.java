package io.floci.gcp.services.cloudmonitoring;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.api.LabelDescriptor;
import com.google.api.MetricDescriptor;
import com.google.api.MonitoredResourceDescriptor;
import com.google.monitoring.v3.Aggregation;
import com.google.monitoring.v3.Point;
import com.google.monitoring.v3.TimeInterval;
import com.google.monitoring.v3.TimeSeries;
import com.google.monitoring.v3.TypedValue;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.ProjectAwareStorageBackend;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.core.common.GcpResourceNames;
import io.floci.gcp.lifecycle.GrpcServerManager;
import io.floci.gcp.services.cloudmonitoring.model.StoredTimeSeriesPoint;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@ApplicationScoped
public class CloudMonitoringService {

    private static final Logger LOG = Logger.getLogger(CloudMonitoringService.class);

    private static final int MAX_TIME_SERIES_PER_REQUEST = 200;
    private static final int MAX_DESCRIPTOR_PAGE_SIZE = 10_000;
    private static final int MAX_TIME_SERIES_PAGE_SIZE = 100_000;
    private static final Duration MAX_POINT_AGE = Duration.ofHours(25);
    private static final Duration MAX_POINT_FUTURE = Duration.ofMinutes(5);

    private static final List<MonitoredResourceDescriptor> DEFAULT_RESOURCE_DESCRIPTORS = List.of(
            MonitoredResourceDescriptor.newBuilder()
                    .setType("global")
                    .setDisplayName("Global")
                    .setDescription("Use this resource type for a metric that is not associated with any specific resource type")
                    .build(),
            MonitoredResourceDescriptor.newBuilder()
                    .setType("gce_instance")
                    .setDisplayName("VM Instance")
                    .setDescription("An instance of a Google Compute Engine virtual machine")
                    .addLabels(LabelDescriptor.newBuilder().setKey("instance_id").setDescription("The VM instance ID").build())
                    .addLabels(LabelDescriptor.newBuilder().setKey("zone").setDescription("The VM zone").build())
                    .addLabels(LabelDescriptor.newBuilder().setKey("project_id").setDescription("The GCP project ID").build())
                    .build()
    );

    private final StorageBackend<String, String> descriptorStore;
    private final StorageBackend<String, StoredTimeSeriesPoint> timeSeriesStore;
    private final Clock clock;
    private final AtomicLong sequence = new AtomicLong(0);

    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final GrpcServerManager grpcServerManager;

    @Inject
    public CloudMonitoringService(StorageFactory storageFactory,
                                   ServiceRegistry serviceRegistry,
                                   EmulatorConfig config,
                                   GrpcServerManager grpcServerManager) {
        this.descriptorStore = storageFactory.create("monitoring-descriptors", "monitoring-descriptors.json",
                new TypeReference<Map<String, String>>() {});
        this.timeSeriesStore = storageFactory.create("monitoring-timeseries", "monitoring-timeseries.json",
                new TypeReference<Map<String, StoredTimeSeriesPoint>>() {});
        this.clock = Clock.systemUTC();
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.grpcServerManager = grpcServerManager;
        this.sequence.set(maxStoredSequence(timeSeriesStore));
    }

    CloudMonitoringService(StorageBackend<String, String> descriptorStore,
                           StorageBackend<String, StoredTimeSeriesPoint> timeSeriesStore) {
        this(descriptorStore, timeSeriesStore, Clock.systemUTC());
    }

    CloudMonitoringService(StorageBackend<String, String> descriptorStore,
                           StorageBackend<String, StoredTimeSeriesPoint> timeSeriesStore,
                           Clock clock) {
        this.descriptorStore = descriptorStore;
        this.timeSeriesStore = timeSeriesStore;
        this.clock = clock;
        this.serviceRegistry = null;
        this.config = null;
        this.grpcServerManager = null;
        this.sequence.set(maxStoredSequence(timeSeriesStore));
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("monitoring")
                .enabled(config.services().monitoring().enabled())
                .storageKey("monitoring")
                .protocol(ServiceProtocol.GRPC)
                .resourceClasses(CloudMonitoringController.class, CloudMonitoringHttpController.class)
                .build());
        grpcServerManager.bind(new CloudMonitoringController(this));
    }

    private static long maxStoredSequence(StorageBackend<String, StoredTimeSeriesPoint> store) {
        List<StoredTimeSeriesPoint> all = store instanceof ProjectAwareStorageBackend<StoredTimeSeriesPoint> projectAware
                ? projectAware.scanAllProjects(k -> true)
                : store.scan(k -> true);
        return all.stream().mapToLong(StoredTimeSeriesPoint::getSequence).max().orElse(0);
    }

    // ── Metric Descriptors ───────────────────────────────────────────────────

    public MetricDescriptor createMetricDescriptor(String parentProject, MetricDescriptor descriptor) {
        String type = descriptor.getType();
        if (type == null || type.isBlank()) {
            throw GcpException.invalidArgument("MetricDescriptor must have a non-empty type");
        }
        int slash = type.indexOf('/');
        if (slash <= 0 || !type.substring(0, slash).contains(".")) {
            throw GcpException.invalidArgument(
                    "Metric type must be of the form \"<domain>/<path>\", e.g. \"custom.googleapis.com/my_metric\": " + type);
        }
        boolean boolOrString = descriptor.getValueType() == MetricDescriptor.ValueType.BOOL
                || descriptor.getValueType() == MetricDescriptor.ValueType.STRING;
        if (boolOrString
                && descriptor.getMetricKind() != MetricDescriptor.MetricKind.GAUGE
                && descriptor.getMetricKind() != MetricDescriptor.MetricKind.METRIC_KIND_UNSPECIFIED) {
            throw GcpException.invalidArgument(
                    "BOOL and STRING value types are only valid with GAUGE metric kind");
        }

        // Standardize name projects/{project}/metricDescriptors/{type}
        String name = descriptor.getName();
        if (name == null || name.isBlank() || !name.contains("/metricDescriptors/")) {
            name = parentProject + "/metricDescriptors/" + type;
        }

        MetricDescriptor.Builder builder = descriptor.toBuilder().setName(name);

        // Upsert: an existing descriptor is updated, but metric labels are never removed.
        descriptorStore.get(type)
                .map(json -> ProtoJson.merge(json, MetricDescriptor.newBuilder()).build())
                .ifPresent(existing -> {
                    Map<String, LabelDescriptor> merged = new LinkedHashMap<>();
                    existing.getLabelsList().forEach(l -> merged.put(l.getKey(), l));
                    descriptor.getLabelsList().forEach(l -> merged.put(l.getKey(), l));
                    builder.clearLabels().addAllLabels(merged.values());
                });

        MetricDescriptor populated = builder.build();
        descriptorStore.put(type, ProtoJson.print(populated));
        LOG.debugf("Upserted metric descriptor type=%s name=%s", type, name);
        return populated;
    }

    public MetricDescriptor getMetricDescriptor(String name) {
        String type = parseMetricType(name);
        return descriptorStore.get(type)
                .map(json -> ProtoJson.merge(json, MetricDescriptor.newBuilder()).build())
                .orElseThrow(() -> GcpException.notFound("Metric descriptor not found: " + name));
    }

    public PageToken.Page<MetricDescriptor> listMetricDescriptors(String parentProject, String filter,
                                                                  int pageSize, String pageToken) {
        List<MetricDescriptor> all = descriptorStore.scan(k -> true).stream()
                .map(json -> ProtoJson.merge(json, MetricDescriptor.newBuilder()).build())
                .sorted(Comparator.comparing(MetricDescriptor::getType))
                .toList();

        // Apply simple filter starts_with or equals
        if (filter != null && !filter.isBlank()) {
            all = all.stream()
                    .filter(d -> matchesFilter(d, filter))
                    .toList();
        }

        int effective = (pageSize <= 0 || pageSize > MAX_DESCRIPTOR_PAGE_SIZE)
                ? MAX_DESCRIPTOR_PAGE_SIZE : pageSize;
        return PageToken.paginate(all, effective, pageToken);
    }

    public void deleteMetricDescriptor(String name) {
        String type = parseMetricType(name);
        if (!type.startsWith("custom.googleapis.com/") && !type.startsWith("external.googleapis.com/")) {
            throw GcpException.invalidArgument("Only user-created custom metrics can be deleted: " + name);
        }
        if (descriptorStore.get(type).isEmpty()) {
            throw GcpException.notFound("Metric descriptor not found: " + name);
        }
        descriptorStore.delete(type);

        // Delete all points matching this type
        List<String> keysToDelete = timeSeriesStore.scan(k -> true).stream()
                .filter(pt -> type.equals(pt.getMetricType()))
                .map(pt -> key(pt))
                .toList();
        keysToDelete.forEach(timeSeriesStore::delete);

        LOG.debugf("Deleted metric descriptor type=%s and %d associated points", type, keysToDelete.size());
    }

    // ── Monitored Resource Descriptors ───────────────────────────────────────

    public PageToken.Page<MonitoredResourceDescriptor> listMonitoredResourceDescriptors(String parentProject,
                                                                                        int pageSize, String pageToken) {
        int effective = pageSize <= 0 ? DEFAULT_RESOURCE_DESCRIPTORS.size() : pageSize;
        return PageToken.paginate(DEFAULT_RESOURCE_DESCRIPTORS, effective, pageToken);
    }

    public MonitoredResourceDescriptor getMonitoredResourceDescriptor(String name) {
        String type = GcpResourceNames.lastSegment(name);
        return DEFAULT_RESOURCE_DESCRIPTORS.stream()
                .filter(d -> d.getType().equals(type))
                .findFirst()
                .orElseThrow(() -> GcpException.notFound("Monitored resource descriptor not found: " + name));
    }

    // ── Time Series ──────────────────────────────────────────────────────────

    public void createTimeSeries(String parentProject, List<TimeSeries> timeSeriesList) {
        if (timeSeriesList.isEmpty()) {
            throw GcpException.invalidArgument("CreateTimeSeriesRequest must contain at least one time series");
        }
        if (timeSeriesList.size() > MAX_TIME_SERIES_PER_REQUEST) {
            throw GcpException.invalidArgument(String.format(
                    "A maximum of %d TimeSeries can be written per request (got %d)",
                    MAX_TIME_SERIES_PER_REQUEST, timeSeriesList.size()));
        }

        Instant now = clock.instant();
        Set<String> batchMetricTypes = timeSeriesList.stream()
                .map(ts -> ts.getMetric().getType())
                .collect(Collectors.toSet());
        Map<String, Instant> latestByIdentity = new HashMap<>();
        for (StoredTimeSeriesPoint pt : timeSeriesStore.scan(k -> {
            int idx = k.indexOf('#');
            return idx > 0 && batchMetricTypes.contains(k.substring(0, idx));
        })) {
            latestByIdentity.merge(SeriesKey.of(pt).identityString(),
                    Instant.parse(pt.getEndTime()), (a, b) -> a.isAfter(b) ? a : b);
        }

        // Pass 1: validate everything, stage writes; nothing is persisted on failure.
        Map<String, MetricDescriptor> pendingDescriptors = new LinkedHashMap<>();
        List<StoredTimeSeriesPoint> staged = new ArrayList<>();

        for (TimeSeries ts : timeSeriesList) {
            String metricType = ts.getMetric().getType();
            if (metricType == null || metricType.isBlank()) {
                throw GcpException.invalidArgument("TimeSeries metric type is required");
            }
            if (ts.getPointsCount() != 1) {
                throw GcpException.invalidArgument(String.format(
                        "Each TimeSeries must contain exactly one Point (got %d for %s)",
                        ts.getPointsCount(), metricType));
            }

            MetricDescriptor desc = descriptorStore.get(metricType)
                    .map(json -> ProtoJson.merge(json, MetricDescriptor.newBuilder()).build())
                    .orElseGet(() -> pendingDescriptors.get(metricType));

            Point pt = ts.getPoints(0);
            if (desc != null) {
                // Validate kind and value type
                if (ts.getMetricKind() != MetricDescriptor.MetricKind.METRIC_KIND_UNSPECIFIED
                        && ts.getMetricKind() != desc.getMetricKind()) {
                    throw GcpException.invalidArgument(String.format(
                            "TimeSeries metricKind %s does not match MetricDescriptor metricKind %s for %s",
                            ts.getMetricKind(), desc.getMetricKind(), metricType));
                }
                if (ts.getValueType() != MetricDescriptor.ValueType.VALUE_TYPE_UNSPECIFIED
                        && ts.getValueType() != desc.getValueType()) {
                    throw GcpException.invalidArgument(String.format(
                            "TimeSeries valueType %s does not match MetricDescriptor valueType %s for %s",
                            ts.getValueType(), desc.getValueType(), metricType));
                }
            } else {
                desc = autoCreateDescriptor(parentProject, ts, metricType, pt);
                pendingDescriptors.put(metricType, desc);
                LOG.debugf("Staged auto-created metric descriptor type=%s", metricType);
            }

            MetricDescriptor.MetricKind kind = desc.getMetricKind();
            MetricDescriptor.ValueType valType = desc.getValueType();

            // Validate interval
            if (!pt.getInterval().hasEndTime()) {
                throw GcpException.invalidArgument("Point interval end_time is required for " + metricType);
            }
            Instant end = toInstant(pt.getInterval().getEndTime());
            if (end.isBefore(now.minus(MAX_POINT_AGE)) || end.isAfter(now.plus(MAX_POINT_FUTURE))) {
                throw GcpException.invalidArgument(String.format(
                        "Point end_time (%s) must be no more than 25 hours in the past and 5 minutes in the future",
                        end));
            }
            Instant start;
            if (kind == MetricDescriptor.MetricKind.GAUGE) {
                start = pt.getInterval().hasStartTime() ? toInstant(pt.getInterval().getStartTime()) : end;
                if (!start.equals(end)) {
                    throw GcpException.invalidArgument(String.format(
                            "For GAUGE metric %s, startTime (%s) must be absent or equal to endTime (%s)",
                            metricType, start, end));
                }
            } else {
                if (!pt.getInterval().hasStartTime()) {
                    throw GcpException.invalidArgument(String.format(
                            "For %s metric %s, a non-zero interval with startTime before endTime is required",
                            kind, metricType));
                }
                start = toInstant(pt.getInterval().getStartTime());
                if (!start.isBefore(end)) {
                    throw GcpException.invalidArgument(String.format(
                            "For %s metric %s, startTime (%s) must be before endTime (%s)",
                            kind, metricType, start, end));
                }
            }

            validateValueMatchesType(pt.getValue(), valType, metricType);

            // Freshness: the new point must be more recent than any other point in its series
            String identity = new SeriesKey(metricType, ts.getMetric().getLabelsMap(),
                    ts.getResource().getType(), ts.getResource().getLabelsMap(), "", "").identityString();
            Instant latest = latestByIdentity.get(identity);
            if (latest != null && !end.isAfter(latest)) {
                throw GcpException.invalidArgument(String.format(
                        "Point end_time (%s) must be more recent than the most recent existing point (%s) for %s",
                        end, latest, metricType));
            }
            latestByIdentity.put(identity, end);

            StoredTimeSeriesPoint storedPoint = new StoredTimeSeriesPoint();
            storedPoint.setMetricType(metricType);
            storedPoint.setMetricLabels(ts.getMetric().getLabelsMap());
            storedPoint.setResourceType(ts.getResource().getType());
            storedPoint.setResourceLabels(ts.getResource().getLabelsMap());
            storedPoint.setMetricKind(kind.name());
            storedPoint.setValueType(valType.name());
            storedPoint.setStartTime(start.toString());
            storedPoint.setEndTime(end.toString());
            storedPoint.setValueJson(ProtoJson.print(pt.getValue()));
            staged.add(storedPoint);
        }

        // Pass 2: persist descriptors, then points.
        pendingDescriptors.forEach((type, desc) -> descriptorStore.put(type, ProtoJson.print(desc)));
        for (StoredTimeSeriesPoint storedPoint : staged) {
            storedPoint.setSequence(sequence.incrementAndGet());
            timeSeriesStore.put(key(storedPoint), storedPoint);
        }
        LOG.debugf("Successfully ingested %d time series", timeSeriesList.size());
    }

    private static MetricDescriptor autoCreateDescriptor(String parentProject, TimeSeries ts,
                                                         String metricType, Point pt) {
        MetricDescriptor.MetricKind kind = ts.getMetricKind() != MetricDescriptor.MetricKind.METRIC_KIND_UNSPECIFIED
                ? ts.getMetricKind() : MetricDescriptor.MetricKind.GAUGE;
        if (kind != MetricDescriptor.MetricKind.GAUGE && kind != MetricDescriptor.MetricKind.CUMULATIVE) {
            throw GcpException.invalidArgument(String.format(
                    "Auto-created metric %s must have metric kind GAUGE or CUMULATIVE (got %s)", metricType, kind));
        }
        MetricDescriptor.ValueType inferred = switch (pt.getValue().getValueCase()) {
            case BOOL_VALUE -> MetricDescriptor.ValueType.BOOL;
            case INT64_VALUE -> MetricDescriptor.ValueType.INT64;
            case DOUBLE_VALUE -> MetricDescriptor.ValueType.DOUBLE;
            case DISTRIBUTION_VALUE -> MetricDescriptor.ValueType.DISTRIBUTION;
            default -> throw GcpException.invalidArgument(String.format(
                    "Auto-created metric %s must have a BOOL, INT64, DOUBLE or DISTRIBUTION point value (got %s)",
                    metricType, pt.getValue().getValueCase()));
        };
        if (ts.getValueType() != MetricDescriptor.ValueType.VALUE_TYPE_UNSPECIFIED
                && ts.getValueType() != inferred) {
            throw GcpException.invalidArgument(String.format(
                    "TimeSeries valueType %s does not match the point value type %s for %s",
                    ts.getValueType(), inferred, metricType));
        }
        if (inferred == MetricDescriptor.ValueType.BOOL && kind != MetricDescriptor.MetricKind.GAUGE) {
            throw GcpException.invalidArgument(
                    "BOOL and STRING value types are only valid with GAUGE metric kind");
        }
        return MetricDescriptor.newBuilder()
                .setName(parentProject + "/metricDescriptors/" + metricType)
                .setType(metricType)
                .setMetricKind(kind)
                .setValueType(inferred)
                .build();
    }

    private static void validateValueMatchesType(TypedValue val, MetricDescriptor.ValueType valType,
                                                 String metricType) {
        boolean matches = switch (valType) {
            case BOOL -> val.getValueCase() == TypedValue.ValueCase.BOOL_VALUE;
            case INT64 -> val.getValueCase() == TypedValue.ValueCase.INT64_VALUE;
            case DOUBLE -> val.getValueCase() == TypedValue.ValueCase.DOUBLE_VALUE;
            case STRING -> val.getValueCase() == TypedValue.ValueCase.STRING_VALUE;
            case DISTRIBUTION -> val.getValueCase() == TypedValue.ValueCase.DISTRIBUTION_VALUE;
            default -> true;
        };
        if (!matches) {
            throw GcpException.invalidArgument(String.format(
                    "Point value does not match VALUE_TYPE %s for %s", valType, metricType));
        }
    }

    public PageToken.Page<TimeSeries> listTimeSeries(String parentProject, String filter,
                                                     TimeInterval requestInterval, Aggregation aggregation,
                                                     String view, int pageSize, String pageToken) {
        if (filter == null || filter.isBlank()) {
            throw GcpException.invalidArgument("filter is required");
        }
        TimeSeriesFilter.ParsedFilter parsedFilter = TimeSeriesFilter.parseFilter(filter);
        if (parsedFilter.metricTypeEquality() == null) {
            throw GcpException.invalidArgument(
                    "The filter must specify a single metric type using a metric.type = \"...\" clause");
        }
        if (!requestInterval.hasEndTime()) {
            throw GcpException.invalidArgument("interval.end_time is required");
        }
        Instant reqEnd = toInstant(requestInterval.getEndTime());
        Instant reqStart = requestInterval.hasStartTime() ? toInstant(requestInterval.getStartTime()) : Instant.MIN;
        if (requestInterval.hasStartTime() && !reqStart.isBefore(reqEnd)) {
            throw GcpException.invalidArgument(String.format(
                    "interval startTime (%s) must be before endTime (%s)", reqStart, reqEnd));
        }

        TimeSeriesAggregator.validate(aggregation);

        // Reads are half-open: (startTime, endTime]
        List<StoredTimeSeriesPoint> matchedPoints = timeSeriesStore.scan(k -> true).stream()
                .filter(parsedFilter.predicate())
                .filter(pt -> {
                    Instant ptEnd = Instant.parse(pt.getEndTime());
                    return ptEnd.isAfter(reqStart) && !ptEnd.isAfter(reqEnd);
                })
                .toList();

        Map<SeriesKey, List<StoredTimeSeriesPoint>> groups = matchedPoints.stream()
                .collect(Collectors.groupingBy(SeriesKey::of, LinkedHashMap::new, Collectors.toList()));

        List<TimeSeries> series = aggregation.getPerSeriesAligner() != Aggregation.Aligner.ALIGN_NONE
                ? TimeSeriesAggregator.aggregate(groups, aggregation, reqEnd)
                : buildRawSeries(groups);

        int effective = (pageSize <= 0 || pageSize > MAX_TIME_SERIES_PAGE_SIZE)
                ? MAX_TIME_SERIES_PAGE_SIZE : pageSize;

        if ("HEADERS".equalsIgnoreCase(view)) {
            List<TimeSeries> headers = series.stream()
                    .map(ts -> ts.toBuilder().clearPoints().build())
                    .toList();
            return PageToken.paginate(headers, effective, pageToken);
        }
        return paginateByPoints(series, effective, pageToken);
    }

    private static List<TimeSeries> buildRawSeries(Map<SeriesKey, List<StoredTimeSeriesPoint>> groups) {
        List<TimeSeries> result = new ArrayList<>();
        for (Map.Entry<SeriesKey, List<StoredTimeSeriesPoint>> entry : groups.entrySet()) {
            SeriesKey key = entry.getKey();
            List<Point> protoPoints = entry.getValue().stream()
                    .map(pt -> Point.newBuilder()
                            .setInterval(TimeInterval.newBuilder()
                                    .setStartTime(fromIso(pt.getStartTime()))
                                    .setEndTime(fromIso(pt.getEndTime()))
                                    .build())
                            .setValue(ProtoJson.merge(pt.getValueJson(), TypedValue.newBuilder()).build())
                            .build())
                    .sorted(Comparator.comparing((Point p) -> toInstant(p.getInterval().getEndTime())).reversed())
                    .toList();

            result.add(TimeSeries.newBuilder()
                    .setMetric(com.google.api.Metric.newBuilder()
                            .setType(key.metricType())
                            .putAllLabels(key.metricLabels())
                            .build())
                    .setResource(com.google.api.MonitoredResource.newBuilder()
                            .setType(key.resourceType())
                            .putAllLabels(key.resourceLabels())
                            .build())
                    .setMetricKind(MetricDescriptor.MetricKind.valueOf(key.metricKind()))
                    .setValueType(MetricDescriptor.ValueType.valueOf(key.valueType()))
                    .addAllPoints(protoPoints)
                    .build());
        }
        result.sort(Comparator.comparing((TimeSeries ts) -> ts.getMetric().getType())
                .thenComparing(ts -> ts.getMetric().getLabelsMap().toString())
                .thenComparing(ts -> ts.getResource().getLabelsMap().toString()));
        return result;
    }

    // Under the FULL view, page size limits the total number of Points; a series may span pages.
    private static PageToken.Page<TimeSeries> paginateByPoints(List<TimeSeries> series, int maxPoints,
                                                               String pageToken) {
        int offset = PageToken.decode(pageToken);
        int totalPoints = series.stream().mapToInt(TimeSeries::getPointsCount).sum();

        List<TimeSeries> out = new ArrayList<>();
        int position = 0;
        int taken = 0;
        for (TimeSeries ts : series) {
            if (taken >= maxPoints) {
                break;
            }
            int count = ts.getPointsCount();
            if (position + count <= offset) {
                position += count;
                continue;
            }
            int from = Math.max(0, offset - position);
            int to = Math.min(count, from + (maxPoints - taken));
            if (from < to) {
                out.add(ts.toBuilder()
                        .clearPoints()
                        .addAllPoints(ts.getPointsList().subList(from, to))
                        .build());
                taken += to - from;
            }
            position += count;
        }

        String next = offset + taken < totalPoints ? PageToken.encode(offset + taken) : null;
        return new PageToken.Page<>(out, next);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String key(StoredTimeSeriesPoint pt) {
        return pt.getMetricType() + "#" + pt.getSequence();
    }

    private static String parseMetricType(String name) {
        String marker = "/metricDescriptors/";
        int idx = name.indexOf(marker);
        if (idx >= 0) {
            return name.substring(idx + marker.length());
        }
        return name;
    }

    private static Instant toInstant(com.google.protobuf.Timestamp ts) {
        return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
    }

    private static com.google.protobuf.Timestamp fromIso(String iso) {
        try {
            Instant instant = Instant.parse(iso);
            return com.google.protobuf.Timestamp.newBuilder()
                    .setSeconds(instant.getEpochSecond())
                    .setNanos(instant.getNano())
                    .build();
        } catch (Exception e) {
            return com.google.protobuf.Timestamp.getDefaultInstance();
        }
    }

    private static boolean matchesFilter(MetricDescriptor d, String filter) {
        // Simple helper to filter custom.googleapis.com
        if (filter.contains("starts_with")) {
            int firstQuote = filter.indexOf("\"");
            int lastQuote = filter.lastIndexOf("\"");
            if (firstQuote >= 0 && lastQuote > firstQuote) {
                String prefix = filter.substring(firstQuote + 1, lastQuote);
                return d.getType().startsWith(prefix);
            }
        } else if (filter.contains("=")) {
            int firstQuote = filter.indexOf("\"");
            int lastQuote = filter.lastIndexOf("\"");
            if (firstQuote >= 0 && lastQuote > firstQuote) {
                String type = filter.substring(firstQuote + 1, lastQuote);
                return d.getType().equals(type);
            }
        }
        return true;
    }
}
