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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@ApplicationScoped
public class CloudMonitoringService {

    private static final Logger LOG = Logger.getLogger(CloudMonitoringService.class);

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
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.grpcServerManager = grpcServerManager;
    }

    CloudMonitoringService(StorageBackend<String, String> descriptorStore,
                           StorageBackend<String, StoredTimeSeriesPoint> timeSeriesStore) {
        this.descriptorStore = descriptorStore;
        this.timeSeriesStore = timeSeriesStore;
        this.serviceRegistry = null;
        this.config = null;
        this.grpcServerManager = null;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("monitoring")
                .enabled(config.services().monitoring().enabled())
                .storageKey("monitoring")
                .protocol(ServiceProtocol.GRPC)
                .resourceClasses(CloudMonitoringController.class)
                .build());
        grpcServerManager.bind(new CloudMonitoringController(this));
    }

    // ── Metric Descriptors ───────────────────────────────────────────────────

    public MetricDescriptor createMetricDescriptor(String parentProject, MetricDescriptor descriptor) {
        String type = descriptor.getType();
        if (type == null || type.isBlank()) {
            throw GcpException.invalidArgument("MetricDescriptor must have a non-empty type");
        }

        // Standardize name projects/{project}/metricDescriptors/{type}
        String name = descriptor.getName();
        if (name == null || name.isBlank() || !name.contains("/metricDescriptors/")) {
            name = parentProject + "/metricDescriptors/" + type;
        }

        MetricDescriptor populated = descriptor.toBuilder()
                .setName(name)
                .build();

        descriptorStore.put(type, ProtoJson.print(populated));
        LOG.debugf("Created metric descriptor type=%s name=%s", type, name);
        return populated;
    }

    public MetricDescriptor getMetricDescriptor(String name) {
        String type = parseMetricType(name);
        return descriptorStore.get(type)
                .map(json -> ProtoJson.merge(json, MetricDescriptor.newBuilder()).build())
                .orElseThrow(() -> GcpException.notFound("Metric descriptor not found: " + name));
    }

    public List<MetricDescriptor> listMetricDescriptors(String parentProject, String filter, int pageSize, String pageToken) {
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

        return all;
    }

    public void deleteMetricDescriptor(String name) {
        String type = parseMetricType(name);
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

    public List<MonitoredResourceDescriptor> listMonitoredResourceDescriptors(String parentProject) {
        return DEFAULT_RESOURCE_DESCRIPTORS;
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

        for (TimeSeries ts : timeSeriesList) {
            String metricType = ts.getMetric().getType();
            if (metricType == null || metricType.isBlank()) {
                throw GcpException.invalidArgument("TimeSeries metric type is required");
            }

            MetricDescriptor desc = descriptorStore.get(metricType)
                    .map(json -> ProtoJson.merge(json, MetricDescriptor.newBuilder()).build())
                    .orElse(null);

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
                // Auto-create metric descriptor
                desc = MetricDescriptor.newBuilder()
                        .setName(parentProject + "/metricDescriptors/" + metricType)
                        .setType(metricType)
                        .setMetricKind(ts.getMetricKind() != MetricDescriptor.MetricKind.METRIC_KIND_UNSPECIFIED
                                ? ts.getMetricKind() : MetricDescriptor.MetricKind.GAUGE)
                        .setValueType(ts.getValueType() != MetricDescriptor.ValueType.VALUE_TYPE_UNSPECIFIED
                                ? ts.getValueType() : MetricDescriptor.ValueType.DOUBLE)
                        .build();
                descriptorStore.put(metricType, ProtoJson.print(desc));
                LOG.debugf("Auto-created metric descriptor type=%s", metricType);
            }

            MetricDescriptor.MetricKind kind = desc.getMetricKind();
            MetricDescriptor.ValueType valType = desc.getValueType();

            for (Point pt : ts.getPointsList()) {
                // Validate interval
                Instant start = pt.getInterval().hasStartTime() ? toInstant(pt.getInterval().getStartTime()) : Instant.MIN;
                Instant end = pt.getInterval().hasEndTime() ? toInstant(pt.getInterval().getEndTime()) : Instant.MIN;

                if ((kind == MetricDescriptor.MetricKind.DELTA || kind == MetricDescriptor.MetricKind.CUMULATIVE)
                        && !start.isBefore(end)) {
                    throw GcpException.invalidArgument(String.format(
                            "For DELTA/CUMULATIVE metric %s, startTime (%s) must be before endTime (%s)",
                            metricType, start, end));
                }

                // Validate TypedValue case matches ValueType
                TypedValue val = pt.getValue();
                switch (valType) {
                    case BOOL -> {
                        if (val.getValueCase() != TypedValue.ValueCase.BOOL_VALUE) {
                            throw GcpException.invalidArgument("Point value does not match VALUE_TYPE BOOL");
                        }
                    }
                    case INT64 -> {
                        if (val.getValueCase() != TypedValue.ValueCase.INT64_VALUE) {
                            throw GcpException.invalidArgument("Point value does not match VALUE_TYPE INT64");
                        }
                    }
                    case DOUBLE -> {
                        if (val.getValueCase() != TypedValue.ValueCase.DOUBLE_VALUE) {
                            throw GcpException.invalidArgument("Point value does not match VALUE_TYPE DOUBLE");
                        }
                    }
                    case STRING -> {
                        if (val.getValueCase() != TypedValue.ValueCase.STRING_VALUE) {
                            throw GcpException.invalidArgument("Point value does not match VALUE_TYPE STRING");
                        }
                    }
                    case DISTRIBUTION -> {
                        if (val.getValueCase() != TypedValue.ValueCase.DISTRIBUTION_VALUE) {
                            throw GcpException.invalidArgument("Point value does not match VALUE_TYPE DISTRIBUTION");
                        }
                    }
                    default -> {}
                }

                // Store point
                StoredTimeSeriesPoint storedPoint = new StoredTimeSeriesPoint();
                storedPoint.setMetricType(metricType);
                storedPoint.setMetricLabels(ts.getMetric().getLabelsMap());
                storedPoint.setResourceType(ts.getResource().getType());
                storedPoint.setResourceLabels(ts.getResource().getLabelsMap());
                storedPoint.setMetricKind(kind.name());
                storedPoint.setValueType(valType.name());
                storedPoint.setStartTime(start.toString());
                storedPoint.setEndTime(end.toString());
                storedPoint.setValueJson(ProtoJson.print(val));
                storedPoint.setSequence(sequence.incrementAndGet());

                timeSeriesStore.put(key(storedPoint), storedPoint);
            }
        }
        LOG.debugf("Successfully ingested %d time series", timeSeriesList.size());
    }

    public List<TimeSeries> listTimeSeries(String parentProject, String filter, TimeInterval requestInterval,
                                           Aggregation aggregation, String view, int pageSize, String pageToken) {
        Predicate<StoredTimeSeriesPoint> filterPredicate = TimeSeriesFilter.parse(filter);

        Instant reqStart = requestInterval.hasStartTime() ? toInstant(requestInterval.getStartTime()) : Instant.MIN;
        Instant reqEnd = requestInterval.hasEndTime() ? toInstant(requestInterval.getEndTime()) : Instant.MAX;

        List<StoredTimeSeriesPoint> matchedPoints = timeSeriesStore.scan(k -> true).stream()
                .filter(filterPredicate)
                .filter(pt -> {
                    Instant ptEnd = Instant.parse(pt.getEndTime());
                    return !ptEnd.isBefore(reqStart) && !ptEnd.isAfter(reqEnd);
                })
                .toList();

        // Group points by SeriesKey (metric type, resource type, labels, etc.)
        Map<SeriesKey, List<StoredTimeSeriesPoint>> groups = matchedPoints.stream()
                .collect(Collectors.groupingBy(pt -> new SeriesKey(
                        pt.getMetricType(),
                        pt.getMetricLabels(),
                        pt.getResourceType(),
                        pt.getResourceLabels(),
                        pt.getMetricKind(),
                        pt.getValueType()
                )));

        List<TimeSeries> result = new ArrayList<>();
        boolean headersOnly = "HEADERS".equalsIgnoreCase(view);

        for (Map.Entry<SeriesKey, List<StoredTimeSeriesPoint>> entry : groups.entrySet()) {
            SeriesKey key = entry.getKey();
            List<StoredTimeSeriesPoint> pts = entry.getValue();

            TimeSeries.Builder builder = TimeSeries.newBuilder()
                    .setMetric(com.google.api.Metric.newBuilder()
                            .setType(key.metricType())
                            .putAllLabels(key.metricLabels())
                            .build())
                    .setResource(com.google.api.MonitoredResource.newBuilder()
                            .setType(key.resourceType())
                            .putAllLabels(key.resourceLabels())
                            .build())
                    .setMetricKind(MetricDescriptor.MetricKind.valueOf(key.metricKind()))
                    .setValueType(MetricDescriptor.ValueType.valueOf(key.valueType()));

            if (!headersOnly) {
                // Map points and sort them descending by endTime (most recent first)
                List<Point> protoPoints = pts.stream()
                        .map(pt -> Point.newBuilder()
                                .setInterval(TimeInterval.newBuilder()
                                        .setStartTime(fromIso(pt.getStartTime()))
                                        .setEndTime(fromIso(pt.getEndTime()))
                                        .build())
                                .setValue(ProtoJson.merge(pt.getValueJson(), TypedValue.newBuilder()).build())
                                .build())
                        .sorted(Comparator.comparing((Point p) -> toInstant(p.getInterval().getEndTime())).reversed())
                        .toList();
                builder.addAllPoints(protoPoints);
            }

            result.add(builder.build());
        }

        // Sort time series by metric type for stability
        result.sort(Comparator.comparing(ts -> ts.getMetric().getType()));

        return result;
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

    private record SeriesKey(
            String metricType,
            Map<String, String> metricLabels,
            String resourceType,
            Map<String, String> resourceLabels,
            String metricKind,
            String valueType
    ) {}
}
