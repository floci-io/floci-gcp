package io.floci.gcp.services.cloudmonitoring;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.monitoring.v3.AlertPolicy;
import com.google.monitoring.v3.ListAlertPoliciesResponse;
import com.google.monitoring.v3.MutationRecord;
import com.google.protobuf.BoolValue;
import com.google.protobuf.Duration;
import com.google.protobuf.FieldMask;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.FieldMaskUtil;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.common.ProjectRegistry;
import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.lifecycle.GrpcServerManager;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AlertPolicyService configuration. Policies are stored, validated and returned with their
 * server-assigned names and records; evaluating them against time series is not part of this.
 * Policies live in a global store keyed by full resource name.
 */
@ApplicationScoped
public class AlertPolicyService {
    private static final Pattern PARENT = Pattern.compile("projects/[^/\\s]+");
    private static final Pattern NAME = Pattern.compile("projects/[^/\\s]+/alertPolicies/[^/\\s]+");
    private static final String MARKDOWN = "text/markdown";
    private static final int MAX_CONDITIONS = 6;
    private static final long MIN_AUTO_CLOSE_SECONDS = 1800;
    private static final Set<String> MUTABLE = Set.of("display_name", "documentation", "user_labels",
            "conditions", "combiner", "enabled", "validity", "notification_channels", "alert_strategy", "severity");
    private final SecureRandom random = new SecureRandom();

    private final StorageBackend<String, String> policies;
    private final NotificationChannelService channels;
    private final ProjectRegistry projects;
    private final GrpcServerManager grpcServerManager;
    private final Clock clock;
    // Shared with NotificationChannelService: a channel delete checks policies under the same lock.
    private final Object lock;

    @Inject
    public AlertPolicyService(StorageFactory storageFactory, NotificationChannelService channels,
            ProjectRegistry projects, GrpcServerManager grpcServerManager) {
        this(storageFactory.createGlobal("monitoring", "monitoring-alert-policies.json",
                new TypeReference<Map<String, String>>() {}), channels, projects, grpcServerManager, Clock.systemUTC());
    }

    AlertPolicyService(StorageBackend<String, String> policies, NotificationChannelService channels,
            ProjectRegistry projects, GrpcServerManager grpcServerManager, Clock clock) {
        this.policies = policies;
        this.channels = channels;
        this.projects = projects;
        this.grpcServerManager = grpcServerManager;
        this.clock = clock;
        this.lock = channels != null ? channels.lock() : new Object();
        if (channels != null) {
            channels.onDelete(this::referencesChannel, this::removeChannel);
        }
    }

    void onStart(@Observes StartupEvent ev) {
        grpcServerManager.bind(new AlertPolicyController(this));
        grpcServerManager.bind(new NotificationChannelController(channels));
    }

    public AlertPolicy create(String parent, AlertPolicy requested) {
        String project = validateParent(parent);
        synchronized (lock) {
            String name = newId(project + "/alertPolicies/");
            MutationRecord record = record();
            AlertPolicy.Builder policy = requested.toBuilder().setName(name)
                    .setCreationRecord(record).setMutationRecord(record);
            assignConditionNames(policy, name, null);
            AlertPolicy result = normalize(policy).build();
            validate(project, result);
            policies.put(name, ProtoJson.print(result));
            return result;
        }
    }

    public AlertPolicy get(String name) {
        String key = validateName(name);
        synchronized (lock) {
            return require(key);
        }
    }

    public ListAlertPoliciesResponse list(String parent, String filter, int pageSize, String pageToken) {
        String project = validateParent(parent);
        MonitoringFilterParser.Filter parsed = MonitoringFilterParser.parse(filter,
                MonitoringFilterParser.Scope.ALERT_POLICIES);
        synchronized (lock) {
            List<AlertPolicy> matching = policies.keys().stream()
                    .filter(key -> key.startsWith(project + "/alertPolicies/")).sorted()
                    .map(this::require).filter(policy -> parsed.matches(object -> value(policy, object))).toList();
            PageToken.Page<AlertPolicy> page = PageToken.paginate(matching,
                    pageSize > 0 ? Math.min(pageSize, matching.size()) : matching.size(), pageToken);
            ListAlertPoliciesResponse.Builder response = ListAlertPoliciesResponse.newBuilder()
                    .addAllAlertPolicies(page.items()).setTotalSize(matching.size());
            if (page.nextPageToken() != null) {
                response.setNextPageToken(page.nextPageToken());
            }
            return response.build();
        }
    }

    /**
     * Without a mask the policy is replaced; conditions that keep their name keep their identity
     * and conditions without one are added. With a mask, each listed field is replaced by the
     * request's value or its default.
     */
    public AlertPolicy update(AlertPolicy requested, FieldMask updateMask) {
        String key = validateName(requested.getName());
        String project = key.substring(0, key.indexOf("/alertPolicies/"));
        synchronized (lock) {
            AlertPolicy current = require(key);
            AlertPolicy.Builder updated;
            if (updateMask.getPathsCount() == 0) {
                updated = requested.toBuilder();
            } else {
                updated = current.toBuilder();
                for (String path : updateMask.getPathsList()) {
                    String root = path.split("\\.", 2)[0];
                    if (!MUTABLE.contains(root) || !FieldMaskUtil.isValid(AlertPolicy.getDescriptor(), path)) {
                        throw GcpException.invalidArgument("Invalid update mask path for an alert policy: " + path);
                    }
                }
                FieldMaskUtil.merge(updateMask, requested, updated, new FieldMaskUtil.MergeOptions()
                        .setReplaceRepeatedFields(true).setReplaceMessageFields(true).setReplacePrimitiveFields(true));
            }
            updated.setName(key).setCreationRecord(current.getCreationRecord()).setMutationRecord(record());
            assignConditionNames(updated, key, current);
            AlertPolicy result = normalize(updated).build();
            validate(project, result);
            policies.put(key, ProtoJson.print(result));
            return result;
        }
    }

    public void delete(String name) {
        String key = validateName(name);
        synchronized (lock) {
            require(key);
            policies.delete(key);
        }
    }

    /** Every policy of every project, for the evaluator. */
    List<AlertPolicy> listAll() {
        synchronized (lock) {
            return policies.keys().stream().sorted().map(this::require).toList();
        }
    }

    // ── Channel references ───────────────────────────────────────────────

    private boolean referencesChannel(String channel) {
        synchronized (lock) {
            return policies.keys().stream().map(this::require)
                    .anyMatch(policy -> policy.getNotificationChannelsList().contains(channel));
        }
    }

    private void removeChannel(String channel) {
        synchronized (lock) {
            for (String key : policies.keys()) {
                AlertPolicy policy = require(key);
                if (policy.getNotificationChannelsList().contains(channel)) {
                    List<String> kept = policy.getNotificationChannelsList().stream()
                            .filter(name -> !name.equals(channel)).toList();
                    policies.put(key, ProtoJson.print(policy.toBuilder().clearNotificationChannels()
                            .addAllNotificationChannels(kept).setMutationRecord(record()).build()));
                }
            }
        }
    }

    // ── Validation ───────────────────────────────────────────────────────

    private void assignConditionNames(AlertPolicy.Builder policy, String name, AlertPolicy current) {
        Set<String> existing = new HashSet<>();
        if (current != null) {
            current.getConditionsList().forEach(condition -> existing.add(condition.getName()));
        }
        Set<String> used = new HashSet<>();
        for (int i = 0; i < policy.getConditionsCount(); i++) {
            AlertPolicy.Condition condition = policy.getConditions(i);
            String conditionName = condition.getName();
            if (conditionName.isEmpty() || !existing.contains(conditionName)) {
                if (!conditionName.isEmpty() && !conditionName.startsWith(name + "/conditions/")) {
                    throw GcpException.invalidArgument("Condition " + conditionName + " does not belong to " + name);
                }
                do {
                    conditionName = name + "/conditions/" + Long.toUnsignedString(random.nextLong() >>> 1);
                } while (existing.contains(conditionName) || used.contains(conditionName));
                policy.setConditions(i, condition.toBuilder().setName(conditionName));
            }
            if (!used.add(conditionName)) {
                throw GcpException.invalidArgument("Condition " + conditionName + " appears more than once");
            }
        }
    }

    private static AlertPolicy.Builder normalize(AlertPolicy.Builder policy) {
        if (!policy.hasEnabled()) {
            policy.setEnabled(BoolValue.of(true));
        }
        if (policy.hasDocumentation() && policy.getDocumentation().getMimeType().isEmpty()) {
            policy.setDocumentation(policy.getDocumentation().toBuilder().setMimeType(MARKDOWN));
        }
        return policy;
    }

    private void validate(String project, AlertPolicy policy) {
        if (policy.getConditionsCount() == 0 || policy.getConditionsCount() > MAX_CONDITIONS) {
            throw GcpException.invalidArgument("An alert policy can have from one to six conditions");
        }
        if (policy.getDisplayName().isEmpty()) {
            throw GcpException.invalidArgument("An alert policy requires a display name");
        }
        boolean single = false;
        boolean logMatch = false;
        for (AlertPolicy.Condition condition : policy.getConditionsList()) {
            switch (condition.getConditionCase()) {
                case CONDITION_THRESHOLD -> requireFilter(condition.getConditionThreshold().getFilter(), condition);
                case CONDITION_ABSENT -> {
                    requireFilter(condition.getConditionAbsent().getFilter(), condition);
                    if (condition.getConditionAbsent().getDuration().getSeconds() < 120) {
                        throw GcpException.invalidArgument("The duration of an absence condition must be at least 120 seconds");
                    }
                }
                case CONDITION_MATCHED_LOG -> {
                    logMatch = true;
                    if (condition.getConditionMatchedLog().getFilter().isBlank()) {
                        throw GcpException.invalidArgument("A log match condition requires a filter");
                    }
                }
                case CONDITION_MONITORING_QUERY_LANGUAGE, CONDITION_PROMETHEUS_QUERY_LANGUAGE, CONDITION_SQL -> single = true;
                case CONDITION_NOT_SET -> throw GcpException.invalidArgument(
                        "Every alert policy condition must set exactly one condition type");
            }
        }
        if ((single || logMatch) && policy.getConditionsCount() > 1) {
            throw GcpException.invalidArgument("A query-language or log match condition must be the only condition");
        }
        if (logMatch && policy.getCombiner() != AlertPolicy.ConditionCombinerType.OR) {
            throw GcpException.invalidArgument("A log-based alert policy must use the OR combiner");
        }
        if (policy.getConditionsCount() > 1
                && policy.getCombiner() == AlertPolicy.ConditionCombinerType.COMBINE_UNSPECIFIED) {
            throw GcpException.invalidArgument("A combiner is required when an alert policy has more than one condition");
        }
        if (policy.hasDocumentation() && !policy.getDocumentation().getMimeType().equals(MARKDOWN)) {
            throw GcpException.invalidArgument("The only valid documentation mime type is text/markdown");
        }
        if (policy.getAlertStrategy().hasAutoClose()) {
            Duration autoClose = policy.getAlertStrategy().getAutoClose();
            if (autoClose.getSeconds() < MIN_AUTO_CLOSE_SECONDS) {
                throw GcpException.invalidArgument("The minimum auto-close duration is 30 minutes");
            }
        }
        for (String channel : policy.getNotificationChannelsList()) {
            if (!channel.matches("projects/[^/\\s]+/notificationChannels/[^/\\s]+")) {
                throw GcpException.invalidArgument("Invalid notification channel name: " + channel);
            }
        }
    }

    private static void requireFilter(String filter, AlertPolicy.Condition condition) {
        if (filter == null || filter.isBlank()) {
            throw GcpException.invalidArgument("Condition " + condition.getDisplayName() + " requires a filter");
        }
    }

    private static String value(AlertPolicy policy, String object) {
        return switch (object) {
            case "display_name" -> policy.getDisplayName();
            case "enabled" -> String.valueOf(!policy.hasEnabled() || policy.getEnabled().getValue());
            case "combiner" -> policy.getCombiner().name();
            case "severity" -> policy.getSeverity().name();
            case "documentation.content" -> policy.getDocumentation().getContent();
            case "notification_channels" -> String.join(",", policy.getNotificationChannelsList());
            default -> object.startsWith("user_labels.")
                    ? policy.getUserLabelsMap().get(object.substring("user_labels.".length())) : null;
        };
    }

    private String newId(String prefix) {
        String name;
        do {
            name = prefix + Long.toUnsignedString(random.nextLong() >>> 1);
        } while (policies.get(name).isPresent());
        return name;
    }

    private AlertPolicy require(String key) {
        return policies.get(key).map(json -> ProtoJson.merge(json, AlertPolicy.newBuilder()).build())
                .orElseThrow(() -> GcpException.notFound("Alert policy not found: " + key));
    }

    private String validateParent(String parent) {
        if (parent == null || !PARENT.matcher(parent).matches()) {
            throw GcpException.invalidArgument("Invalid project name: " + parent);
        }
        return projects == null ? parent : projects.canonicalProjectName(parent);
    }

    private String validateName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw GcpException.invalidArgument("Invalid alert policy name: " + name);
        }
        return projects == null ? name : projects.canonicalResourceName(name);
    }

    private MutationRecord record() {
        Instant now = clock.instant();
        return MutationRecord.newBuilder().setMutateTime(
                Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano())).build();
    }
}
