package io.floci.gcp.services.cloudlogging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.api.Distribution;
import com.google.api.LabelDescriptor;
import com.google.api.MetricDescriptor;
import com.google.logging.v2.ListLogMetricsResponse;
import com.google.logging.v2.LogMetric;
import com.google.protobuf.Timestamp;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.common.ProjectRegistry;
import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.cloudlogging.model.StoredLogEntry;
import io.floci.gcp.services.cloudlogging.model.StoredLogMetric;
import io.floci.gcp.services.cloudmonitoring.CloudMonitoringService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MetricsServiceV2: logs-based metric configuration, plus the write-time evaluation that turns
 * matching entries into {@code logging.googleapis.com/user/<name>} DELTA points in Cloud Monitoring.
 */
@ApplicationScoped
public class CloudLoggingMetricService {
    private static final Logger LOG = Logger.getLogger(CloudLoggingMetricService.class);

    static final String METRIC_TYPE_PREFIX = "logging.googleapis.com/user/";
    private static final Pattern PARENT = Pattern.compile("projects/[^/\\s]+");
    private static final Pattern METRIC_ID = Pattern.compile("[A-Za-z0-9_\\-.,+!*'()%][A-Za-z0-9_\\-.,+!*'()%/]{0,99}");
    private static final Pattern LABEL_KEY = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,99}");
    private static final Set<LabelDescriptor.ValueType> LABEL_TYPES = Set.of(LabelDescriptor.ValueType.STRING,
            LabelDescriptor.ValueType.BOOL, LabelDescriptor.ValueType.INT64);
    private static final int MAX_LABELS = 10;
    private static final int MAX_BUCKETS = 200;

    private final StorageBackend<String, StoredLogMetric> metrics;
    private final CloudMonitoringService monitoring;
    private final ProjectRegistry projects;
    private final EmulatorConfig config;
    private final LogMetricRecorder recorder;
    // Configuration changes and evaluation share this lock, as sinks do.
    private final Object metricLock = new Object();

    @Inject
    public CloudLoggingMetricService(StorageFactory storageFactory, CloudMonitoringService monitoring,
            ProjectRegistry projects, EmulatorConfig config) {
        this(storageFactory.createGlobal("logging", "cloudlogging-metrics.json",
                new TypeReference<Map<String, StoredLogMetric>>() {}), monitoring, projects, config, Clock.systemUTC());
    }

    CloudLoggingMetricService(StorageBackend<String, StoredLogMetric> metrics, CloudMonitoringService monitoring,
            ProjectRegistry projects, EmulatorConfig config, Clock clock) {
        this.metrics = metrics;
        this.monitoring = monitoring;
        this.projects = projects;
        this.config = config;
        this.recorder = new LogMetricRecorder(monitoring, clock);
    }

    public LogMetric createLogMetric(String parent, LogMetric requested) {
        String project = validateParent(parent);
        validateId(requested.getName());
        String name = project + "/metrics/" + requested.getName();
        synchronized (metricLock) {
            if (metrics.get(name).isPresent()) {
                throw GcpException.alreadyExists("Metric " + requested.getName() + " already exists.");
            }
            Timestamp now = timestamp();
            LogMetric created = normalize(project, requested, null).toBuilder()
                    .setCreateTime(now).setUpdateTime(now).build();
            publishDescriptor(project, created);
            metrics.put(name, new StoredLogMetric(ProtoJson.print(created)));
            return created;
        }
    }

    public LogMetric getLogMetric(String metricName) {
        String name = validateName(metricName);
        synchronized (metricLock) {
            return read(require(name));
        }
    }

    public ListLogMetricsResponse listLogMetrics(String parent, int pageSize, String pageToken) {
        String project = validateParent(parent);
        synchronized (metricLock) {
            List<String> keys = metrics.keys().stream().filter(key -> key.startsWith(project + "/metrics/"))
                    .sorted().toList();
            int bounded = pageSize > 0 ? Math.min(pageSize, keys.size()) : keys.size();
            PageToken.Page<String> page = PageToken.paginate(keys, bounded, pageToken);
            ListLogMetricsResponse.Builder response = ListLogMetricsResponse.newBuilder();
            for (String key : page.items()) {
                response.addMetrics(read(require(key)));
            }
            if (page.nextPageToken() != null) {
                response.setNextPageToken(page.nextPageToken());
            }
            return response.build();
        }
    }

    /** UpdateLogMetric replaces the metric, and creates it when it does not exist. */
    public LogMetric updateLogMetric(String metricName, LogMetric requested) {
        String name = validateName(metricName);
        String project = name.substring(0, name.indexOf("/metrics/"));
        String id = name.substring(name.indexOf("/metrics/") + "/metrics/".length());
        if (!requested.getName().isEmpty() && !requested.getName().equals(id)) {
            throw GcpException.invalidArgument("The metric name must match the metric identifier in the resource path");
        }
        LogMetric withId = requested.toBuilder().setName(id).build();
        synchronized (metricLock) {
            LogMetric existing = metrics.get(name).map(CloudLoggingMetricService::read).orElse(null);
            Timestamp now = timestamp();
            LogMetric.Builder updated = normalize(project, withId, existing).toBuilder().setUpdateTime(now);
            updated.setCreateTime(existing != null ? existing.getCreateTime() : now);
            LogMetric result = updated.build();
            publishDescriptor(project, result);
            metrics.put(name, new StoredLogMetric(ProtoJson.print(result)));
            return result;
        }
    }

    public void deleteLogMetric(String metricName) {
        String name = validateName(metricName);
        synchronized (metricLock) {
            require(name);
            metrics.delete(name);
            recorder.forget(name);
        }
    }

    /** Counts accepted entries into each enabled metric of their project. Never fails the write. */
    public void record(List<StoredLogEntry> entries) {
        if (config != null && !config.services().monitoring().enabled()) {
            return;
        }
        synchronized (metricLock) {
            Map<String, List<StoredLogEntry>> byProject = new LinkedHashMap<>();
            for (StoredLogEntry entry : entries) {
                String project = owningProject(entry.getLogName());
                if (project != null) {
                    byProject.computeIfAbsent(project, ignored -> new ArrayList<>()).add(entry);
                }
            }
            for (Map.Entry<String, List<StoredLogEntry>> group : byProject.entrySet()) {
                for (String key : metrics.keys().stream().filter(k -> k.startsWith(group.getKey() + "/metrics/"))
                        .sorted().toList()) {
                    try {
                        LogMetric metric = read(require(key));
                        if (!metric.getDisabled()) {
                            recorder.record(group.getKey(), key, metric, group.getValue());
                        }
                    } catch (RuntimeException e) {
                        LOG.warnf(e, "Log-based metric %s failed to record entries", key);
                    }
                }
            }
        }
    }

    // ── Validation ────────────────────────────────────────────────────────

    private LogMetric normalize(String project, LogMetric requested, LogMetric existing) {
        if (requested.getFilter().isBlank()) {
            throw GcpException.invalidArgument("A log-based metric requires a filter");
        }
        if (requested.getFilter().length() > 20_000) {
            throw GcpException.invalidArgument("The metric filter must not exceed 20000 characters");
        }
        if (requested.getDescription().length() > 8000) {
            throw GcpException.invalidArgument("The metric description must not exceed 8000 characters");
        }
        SinkLogEntryFilter.parse(requested.getFilter());
        if (!requested.getBucketName().isEmpty()
                && !requested.getBucketName().startsWith(project + "/locations/")) {
            throw GcpException.invalidArgument("The log bucket must be in the same project as the metric");
        }

        MetricDescriptor descriptor = descriptor(project, requested);
        if (existing != null) {
            MetricDescriptor previous = existing.getMetricDescriptor();
            if (previous.getMetricKind() != descriptor.getMetricKind()
                    || previous.getValueType() != descriptor.getValueType()) {
                throw GcpException.invalidArgument(
                        "The metric kind and value type of a log-based metric cannot be updated");
            }
            Map<String, LabelDescriptor> next = new HashMap<>();
            descriptor.getLabelsList().forEach(label -> next.put(label.getKey(), label));
            for (LabelDescriptor label : previous.getLabelsList()) {
                LabelDescriptor kept = next.get(label.getKey());
                if (kept == null || kept.getValueType() != label.getValueType()) {
                    throw GcpException.invalidArgument("Existing labels of a log-based metric cannot be removed "
                            + "or changed, except for their description: " + label.getKey());
                }
            }
        }

        for (LabelDescriptor label : descriptor.getLabelsList()) {
            String extractor = requested.getLabelExtractorsMap().get(label.getKey());
            if (extractor == null) {
                throw GcpException.invalidArgument("Label " + label.getKey() + " requires a label extractor");
            }
            LogMetricExtractor.parse(extractor);
        }
        for (String key : requested.getLabelExtractorsMap().keySet()) {
            if (descriptor.getLabelsList().stream().noneMatch(label -> label.getKey().equals(key))) {
                throw GcpException.invalidArgument("Label extractor " + key + " has no label in the metric descriptor");
            }
        }
        if (descriptor.getValueType() == MetricDescriptor.ValueType.DISTRIBUTION) {
            if (requested.getValueExtractor().isBlank() || !requested.hasBucketOptions()) {
                throw GcpException.invalidArgument(
                        "A distribution log-based metric requires a value extractor and bucket options");
            }
            LogMetricExtractor.parse(requested.getValueExtractor());
            validateBucketOptions(requested.getBucketOptions());
        } else if (!requested.getValueExtractor().isBlank()) {
            LogMetricExtractor.parse(requested.getValueExtractor());
        }

        return requested.toBuilder().clearVersion().clearCreateTime().clearUpdateTime()
                .setMetricDescriptor(descriptor).build();
    }

    private static MetricDescriptor descriptor(String project, LogMetric metric) {
        MetricDescriptor.Builder descriptor = metric.hasMetricDescriptor()
                ? metric.getMetricDescriptor().toBuilder() : MetricDescriptor.newBuilder();
        if (descriptor.getMetricKind() == MetricDescriptor.MetricKind.METRIC_KIND_UNSPECIFIED) {
            descriptor.setMetricKind(MetricDescriptor.MetricKind.DELTA);
        }
        if (descriptor.getValueType() == MetricDescriptor.ValueType.VALUE_TYPE_UNSPECIFIED) {
            descriptor.setValueType(MetricDescriptor.ValueType.INT64);
        }
        if (descriptor.getMetricKind() != MetricDescriptor.MetricKind.DELTA) {
            throw GcpException.invalidArgument("Log-based metrics must use the DELTA metric kind");
        }
        if (descriptor.getValueType() != MetricDescriptor.ValueType.INT64
                && descriptor.getValueType() != MetricDescriptor.ValueType.DISTRIBUTION) {
            throw GcpException.invalidArgument("Log-based metrics must use the INT64 or DISTRIBUTION value type");
        }
        if (descriptor.getUnit().isEmpty()) {
            descriptor.setUnit("1");
        }
        if (descriptor.getLabelsCount() > MAX_LABELS) {
            throw GcpException.invalidArgument("A log-based metric can have at most 10 labels");
        }
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < descriptor.getLabelsCount(); i++) {
            LabelDescriptor label = descriptor.getLabels(i);
            if (!LABEL_KEY.matcher(label.getKey()).matches() || !keys.add(label.getKey())) {
                throw GcpException.invalidArgument("Invalid or duplicate label key: " + label.getKey());
            }
            if (label.getKey().equals(LogMetricRecorder.LOG_LABEL)) {
                throw GcpException.invalidArgument("Label key \"log\" is reserved for the default log label");
            }
            if (label.getValueType() == LabelDescriptor.ValueType.UNRECOGNIZED) {
                throw GcpException.invalidArgument("Invalid label value type for " + label.getKey());
            }
            if (!LABEL_TYPES.contains(label.getValueType())) {
                descriptor.setLabels(i, label.toBuilder().setValueType(LabelDescriptor.ValueType.STRING));
            }
        }
        String type = METRIC_TYPE_PREFIX + metric.getName();
        return descriptor.setType(type).setName(project + "/metricDescriptors/" + type)
                .setDescription(metric.getDescription()).build();
    }

    private static void validateBucketOptions(Distribution.BucketOptions options) {
        boolean valid = switch (options.getOptionsCase()) {
            case LINEAR_BUCKETS -> options.getLinearBuckets().getNumFiniteBuckets() > 0
                    && options.getLinearBuckets().getWidth() > 0;
            case EXPONENTIAL_BUCKETS -> options.getExponentialBuckets().getNumFiniteBuckets() > 0
                    && options.getExponentialBuckets().getGrowthFactor() > 1
                    && options.getExponentialBuckets().getScale() > 0;
            case EXPLICIT_BUCKETS -> isStrictlyIncreasing(options.getExplicitBuckets().getBoundsList());
            case OPTIONS_NOT_SET -> false;
        };
        if (!valid) {
            throw GcpException.invalidArgument("Invalid bucket options for a distribution log-based metric");
        }
        long buckets = switch (options.getOptionsCase()) {
            case LINEAR_BUCKETS -> options.getLinearBuckets().getNumFiniteBuckets() + 2L;
            case EXPONENTIAL_BUCKETS -> options.getExponentialBuckets().getNumFiniteBuckets() + 2L;
            case EXPLICIT_BUCKETS -> options.getExplicitBuckets().getBoundsCount() + 1L;
            case OPTIONS_NOT_SET -> 0L;
        };
        if (buckets > MAX_BUCKETS) {
            throw GcpException.invalidArgument("A distribution log-based metric can have at most "
                    + MAX_BUCKETS + " buckets, including the underflow and overflow buckets");
        }
    }

    private static boolean isStrictlyIncreasing(List<Double> bounds) {
        if (bounds.isEmpty()) {
            return false;
        }
        for (int i = 1; i < bounds.size(); i++) {
            if (bounds.get(i) <= bounds.get(i - 1)) {
                return false;
            }
        }
        return true;
    }

    private void publishDescriptor(String project, LogMetric metric) {
        if (monitoring == null || (config != null && !config.services().monitoring().enabled())) {
            return;
        }
        MetricDescriptor.Builder descriptor = metric.getMetricDescriptor().toBuilder();
        if (descriptor.getLabelsList().stream().noneMatch(label -> label.getKey().equals(LogMetricRecorder.LOG_LABEL))) {
            descriptor.addLabels(LabelDescriptor.newBuilder().setKey(LogMetricRecorder.LOG_LABEL)
                    .setValueType(LabelDescriptor.ValueType.STRING).setDescription("Name of the log."));
        }
        monitoring.createMetricDescriptor(project, descriptor.build());
    }

    private String validateParent(String parent) {
        if (parent == null || !PARENT.matcher(parent).matches()) {
            throw GcpException.invalidArgument("Invalid log-based metric parent: " + parent);
        }
        return projects == null ? parent : projects.canonicalProjectName(parent);
    }

    private String validateName(String name) {
        if (name == null || !name.startsWith("projects/") || !name.contains("/metrics/")) {
            throw GcpException.invalidArgument("Invalid log-based metric name: " + name);
        }
        int metricsAt = name.indexOf("/metrics/");
        String project = validateParent(name.substring(0, metricsAt));
        String id = name.substring(metricsAt + "/metrics/".length());
        validateId(id);
        return project + "/metrics/" + id;
    }

    private static void validateId(String id) {
        if (id == null || !METRIC_ID.matcher(id).matches()) {
            throw GcpException.invalidArgument("Invalid log-based metric identifier: " + id);
        }
    }

    private String owningProject(String logName) {
        if (logName == null || !logName.startsWith("projects/")) {
            return null;
        }
        int logs = logName.indexOf("/logs/");
        if (logs < 0) {
            return null;
        }
        String project = logName.substring(0, logs);
        return projects == null ? project : projects.canonicalProjectName(project);
    }

    private StoredLogMetric require(String name) {
        return metrics.get(name).orElseThrow(() -> GcpException.notFound("Metric "
                + name.substring(name.indexOf("/metrics/") + "/metrics/".length()) + " does not exist."));
    }

    private static LogMetric read(StoredLogMetric stored) {
        return ProtoJson.merge(stored.json(), LogMetric.newBuilder()).build();
    }

    private static Timestamp timestamp() {
        Instant now = Instant.now();
        return Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()).build();
    }
}
