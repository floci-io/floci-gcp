package io.floci.gcp.services.cloudmonitoring;

import com.google.monitoring.v3.Aggregation;
import com.google.monitoring.v3.AlertPolicy;
import com.google.monitoring.v3.ComparisonType;
import com.google.monitoring.v3.NotificationChannel;
import com.google.monitoring.v3.Point;
import com.google.monitoring.v3.TimeInterval;
import com.google.monitoring.v3.TimeSeries;
import com.google.monitoring.v3.TypedValue;
import com.google.protobuf.Timestamp;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.PageToken;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Evaluates enabled alert policies on a fixed tick. Threshold and absence conditions are read
 * through {@link CloudMonitoringService#listTimeSeries} under the policy's project. One alert is
 * kept open per condition and time series; notifications go out when an alert opens and when it
 * closes. Log match, MQL, PromQL and SQL conditions are not evaluated, and a policy that has one is
 * skipped.
 */
@ApplicationScoped
public class AlertPolicyEvaluator {
    private static final Logger LOG = Logger.getLogger(AlertPolicyEvaluator.class);
    private static final Duration DEFAULT_AUTO_CLOSE = Duration.ofDays(7);
    private static final Duration DEFAULT_ALIGNMENT = Duration.ofMinutes(1);
    private static final Duration MAX_LOOKBACK = Duration.ofHours(25);

    private final AlertPolicyService policies;
    private final NotificationChannelService channels;
    private final CloudMonitoringService monitoring;
    private final AlertService alerts;
    private final AlertNotifier notifier;
    private final boolean enabled;
    private final long intervalSeconds;
    private final ScheduledExecutorService executor;

    @Inject
    public AlertPolicyEvaluator(AlertPolicyService policies, NotificationChannelService channels,
            CloudMonitoringService monitoring, AlertService alerts, AlertNotifier notifier, EmulatorConfig config) {
        this(policies, channels, monitoring, alerts, notifier,
                config.services().monitoring().enabled() && config.services().monitoring().alertingEnabled(),
                config.services().monitoring().evaluationIntervalSeconds());
    }

    AlertPolicyEvaluator(AlertPolicyService policies, NotificationChannelService channels,
            CloudMonitoringService monitoring, AlertService alerts, AlertNotifier notifier, boolean enabled,
            long intervalSeconds) {
        this.policies = policies;
        this.channels = channels;
        this.monitoring = monitoring;
        this.alerts = alerts;
        this.notifier = notifier;
        this.enabled = enabled;
        this.intervalSeconds = Math.max(1, intervalSeconds);
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "monitoring-alert-evaluator");
            thread.setDaemon(true);
            return thread;
        });
    }

    void onStart(@Observes StartupEvent ignored) {
        if (!enabled) {
            LOG.info("Cloud Monitoring alert evaluation disabled by configuration");
            return;
        }
        executor.scheduleAtFixedRate(this::tickSafely, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        LOG.infov("Cloud Monitoring alert evaluation started (every {0}s)", intervalSeconds);
    }

    void onStop(@Observes ShutdownEvent ignored) {
        executor.shutdownNow();
    }

    void tickSafely() {
        try {
            tick(Instant.now());
        } catch (Throwable t) {
            LOG.warnv("Cloud Monitoring alert evaluation failed: {0}", t.getMessage());
        }
    }

    synchronized void tick(Instant now) {
        for (AlertPolicy policy : policies.listAll()) {
            try {
                evaluate(policy, now);
            } catch (RuntimeException e) {
                LOG.warnf(e, "Alert policy %s could not be evaluated", policy.getName());
            }
        }
    }

    // ── Policy ───────────────────────────────────────────────────────────

    private record Violation(Map<String, Object> resource, Map<String, Object> metric, String observed) {}

    private record Result(boolean met, Map<String, Violation> violations, Set<String> present) {}

    private void evaluate(AlertPolicy policy, Instant now) {
        if (policy.hasEnabled() && !policy.getEnabled().getValue()) {
            return;
        }
        String project = policy.getName().substring(0, policy.getName().indexOf("/alertPolicies/"));
        Map<String, Result> results = new LinkedHashMap<>();
        for (AlertPolicy.Condition condition : policy.getConditionsList()) {
            Optional<Result> result = switch (condition.getConditionCase()) {
                case CONDITION_THRESHOLD -> Optional.of(threshold(project, condition.getConditionThreshold(), now));
                case CONDITION_ABSENT -> Optional.of(absence(project, policy, condition.getConditionAbsent(), now));
                default -> Optional.empty();
            };
            if (result.isEmpty()) {
                return;
            }
            results.put(condition.getName(), result.get());
        }

        Map<String, Violation> firing = combine(policy, results);
        Map<String, AlertPolicy.Condition> conditions = new LinkedHashMap<>();
        policy.getConditionsList().forEach(condition -> conditions.put(condition.getName(), condition));
        String openTime = now.toString();

        for (Map.Entry<String, Violation> one : firing.entrySet()) {
            if (alerts.openAlert(project, one.getKey()).isPresent()) {
                continue;
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("openTime", openTime);
            fields.put("policy", snapshot(policy));
            fields.put("resource", one.getValue().resource());
            fields.put("metric", one.getValue().metric());
            Map<String, Object> alert = alerts.open(project, one.getKey(), fields);
            notifier.notify(policy, conditions.get(conditionOf(one.getKey())), AlertService.visible(alert),
                    one.getValue().observed(), channels(policy));
        }

        Duration autoClose = policy.getAlertStrategy().hasAutoClose()
                ? Duration.ofSeconds(policy.getAlertStrategy().getAutoClose().getSeconds()) : DEFAULT_AUTO_CLOSE;
        for (Map<String, Object> alert : alerts.openAlerts(policy.getName())) {
            String key = (String) alert.get(AlertService.KEY);
            String conditionName = conditionOf(key);
            AlertPolicy.Condition condition = conditions.get(conditionName);
            boolean expired = Instant.parse((String) alert.get("openTime")).plus(autoClose).isBefore(now);
            if (firing.containsKey(key) && !expired) {
                continue;
            }
            if (condition != null && !expired && keepsOpenWithoutData(condition, results.get(conditionName), key)) {
                continue;
            }
            Map<String, Object> closed = alerts.close(alert, openTime);
            if (condition != null) {
                notifier.notify(policy, condition, AlertService.visible(closed), null, channels(policy));
            }
        }
    }

    private static Map<String, Violation> combine(AlertPolicy policy, Map<String, Result> results) {
        Map<String, Violation> firing = new LinkedHashMap<>();
        AlertPolicy.ConditionCombinerType combiner = policy.getCombiner();
        boolean all = results.values().stream().allMatch(Result::met);
        switch (combiner) {
            case AND -> {
                if (all) {
                    results.forEach((condition, result) -> add(firing, condition, result.violations()));
                }
            }
            case AND_WITH_MATCHING_RESOURCE -> {
                if (all) {
                    Set<String> resources = null;
                    for (Result result : results.values()) {
                        Set<String> these = new HashSet<>();
                        result.violations().values().forEach(violation -> these.add(violation.resource().toString()));
                        if (resources == null) {
                            resources = these;
                        } else {
                            resources.retainAll(these);
                        }
                    }
                    Set<String> matching = resources == null ? Set.of() : resources;
                    results.forEach((condition, result) -> {
                        Map<String, Violation> kept = new LinkedHashMap<>();
                        result.violations().forEach((series, violation) -> {
                            if (matching.contains(violation.resource().toString())) {
                                kept.put(series, violation);
                            }
                        });
                        add(firing, condition, kept);
                    });
                }
            }
            default -> results.forEach((condition, result) -> {
                if (result.met()) {
                    add(firing, condition, result.violations());
                }
            });
        }
        return firing;
    }

    private static void add(Map<String, Violation> firing, String condition, Map<String, Violation> violations) {
        violations.forEach((series, violation) -> firing.put(condition + "|" + series, violation));
    }

    /** "Open alerts stay open" for unspecified or no-op missing data, "Open alerts are closed" for inactive. */
    private static boolean keepsOpenWithoutData(AlertPolicy.Condition condition, Result result, String key) {
        if (result == null || !condition.hasConditionThreshold()) {
            return false;
        }
        String series = key.substring(key.indexOf('|') + 1);
        if (result.present().contains(series)) {
            return false;
        }
        AlertPolicy.Condition.EvaluationMissingData missing = condition.getConditionThreshold().getEvaluationMissingData();
        return missing != AlertPolicy.Condition.EvaluationMissingData.EVALUATION_MISSING_DATA_INACTIVE;
    }

    // ── Conditions ───────────────────────────────────────────────────────

    private Result threshold(String project, AlertPolicy.Condition.MetricThreshold threshold, Instant now) {
        Duration duration = Duration.ofSeconds(threshold.getDuration().getSeconds(), threshold.getDuration().getNanos());
        Aggregation aggregation = threshold.getAggregationsCount() > 0 ? threshold.getAggregations(0) : null;
        Duration alignment = aggregation != null && aggregation.hasAlignmentPeriod()
                ? Duration.ofSeconds(aggregation.getAlignmentPeriod().getSeconds()) : DEFAULT_ALIGNMENT;
        Duration lookback = min(duration.plus(alignment.multipliedBy(2)), MAX_LOOKBACK);
        List<TimeSeries> series = read(project, threshold.getFilter(), aggregation, now.minus(lookback), now);

        Map<String, Violation> violations = new LinkedHashMap<>();
        Set<String> present = new HashSet<>();
        for (TimeSeries one : series) {
            if (one.getPointsCount() == 0) {
                continue;
            }
            String identity = identity(one);
            present.add(identity);
            Optional<Double> newest = value(one.getPoints(0).getValue());
            if (newest.isEmpty()) {
                continue;
            }
            Instant newestEnd = instant(one.getPoints(0).getInterval().getEndTime());
            Instant runStart = null;
            for (Point point : one.getPointsList()) {
                Optional<Double> value = value(point.getValue());
                if (value.isEmpty() || !compare(threshold.getComparison(), value.get(), threshold.getThresholdValue())) {
                    break;
                }
                runStart = instant(point.getInterval().getEndTime());
            }
            if (runStart != null && !Duration.between(runStart, newestEnd).minus(duration).isNegative()) {
                violations.put(identity, new Violation(resource(one), metric(one), format(newest.get())));
            }
        }
        boolean met = triggered(threshold.hasTrigger() ? threshold.getTrigger() : null, violations.size(), present.size());
        return new Result(met, violations, present);
    }

    private Result absence(String project, AlertPolicy policy, AlertPolicy.Condition.MetricAbsence absence, Instant now) {
        Duration duration = Duration.ofSeconds(absence.getDuration().getSeconds());
        Aggregation aggregation = absence.getAggregationsCount() > 0 ? absence.getAggregations(0) : null;
        Instant created = policy.hasCreationRecord() ? instant(policy.getCreationRecord().getMutateTime()) : now;
        if (created.plus(duration).isAfter(now)) {
            return new Result(false, Map.of(), Set.of());
        }
        List<TimeSeries> recent = read(project, absence.getFilter(), aggregation, now.minus(duration), now);
        Set<String> present = new HashSet<>();
        recent.stream().filter(one -> one.getPointsCount() > 0).forEach(one -> present.add(identity(one)));
        Map<String, Violation> violations = new LinkedHashMap<>();
        for (TimeSeries one : read(project, absence.getFilter(), aggregation, now.minus(MAX_LOOKBACK), now)) {
            String identity = identity(one);
            if (one.getPointsCount() > 0 && !present.contains(identity)) {
                violations.put(identity, new Violation(resource(one), metric(one), null));
            }
        }
        if (violations.isEmpty() && present.isEmpty()) {
            String type = MonitoringFilterParser.parse(absence.getFilter(), MonitoringFilterParser.Scope.TIME_SERIES)
                    .requiredMetricType().orElse("");
            violations.put("absent", new Violation(Map.of("type", "global"),
                    type.isEmpty() ? Map.of() : Map.of("type", type), null));
        }
        boolean met = triggered(absence.hasTrigger() ? absence.getTrigger() : null, violations.size(),
                Math.max(1, violations.size() + present.size()));
        return new Result(met, violations, present);
    }

    private List<TimeSeries> read(String project, String filter, Aggregation aggregation, Instant start, Instant end) {
        TimeInterval interval = TimeInterval.newBuilder().setStartTime(timestamp(start)).setEndTime(timestamp(end)).build();
        List<TimeSeries> all = new ArrayList<>();
        String token = null;
        do {
            PageToken.Page<TimeSeries> page = monitoring.listTimeSeries(project, filter, interval,
                    aggregation == null ? Aggregation.getDefaultInstance() : aggregation, "FULL", 0, token);
            all.addAll(page.items());
            token = page.nextPageToken();
        } while (token != null && !token.isEmpty());
        return all;
    }

    private static boolean triggered(AlertPolicy.Condition.Trigger trigger, int violating, int total) {
        if (violating == 0) {
            return false;
        }
        if (trigger == null) {
            return true;
        }
        return switch (trigger.getTypeCase()) {
            case COUNT -> violating >= Math.max(1, trigger.getCount());
            case PERCENT -> total > 0 && violating * 100.0 / total >= trigger.getPercent();
            case TYPE_NOT_SET -> true;
        };
    }

    private static boolean compare(ComparisonType comparison, double value, double threshold) {
        return switch (comparison) {
            case COMPARISON_GT -> value > threshold;
            case COMPARISON_GE -> value >= threshold;
            case COMPARISON_LT -> value < threshold;
            case COMPARISON_LE -> value <= threshold;
            case COMPARISON_EQ -> value == threshold;
            case COMPARISON_NE -> value != threshold;
            default -> false;
        };
    }

    private static Optional<Double> value(TypedValue value) {
        return switch (value.getValueCase()) {
            case DOUBLE_VALUE -> Optional.of(value.getDoubleValue());
            case INT64_VALUE -> Optional.of((double) value.getInt64Value());
            case BOOL_VALUE -> Optional.of(value.getBoolValue() ? 1.0 : 0.0);
            case DISTRIBUTION_VALUE -> Optional.of(value.getDistributionValue().getMean());
            default -> Optional.empty();
        };
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private List<NotificationChannel> channels(AlertPolicy policy) {
        List<NotificationChannel> found = new ArrayList<>();
        for (String name : policy.getNotificationChannelsList()) {
            channels.find(name).ifPresent(found::add);
        }
        return found;
    }

    private static Map<String, Object> snapshot(AlertPolicy policy) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", policy.getName());
        snapshot.put("displayName", policy.getDisplayName());
        if (policy.getSeverity() != AlertPolicy.Severity.SEVERITY_UNSPECIFIED) {
            snapshot.put("severity", policy.getSeverity().name());
        }
        if (policy.getUserLabelsCount() > 0) {
            snapshot.put("userLabels", new TreeMap<>(policy.getUserLabelsMap()));
        }
        return snapshot;
    }

    private static String conditionOf(String key) {
        return key.substring(0, key.indexOf('|'));
    }

    private static String identity(TimeSeries series) {
        return series.getMetric().getType() + new TreeMap<>(series.getMetric().getLabelsMap())
                + series.getResource().getType() + new TreeMap<>(series.getResource().getLabelsMap());
    }

    private static Map<String, Object> resource(TimeSeries series) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("type", series.getResource().getType());
        if (series.getResource().getLabelsCount() > 0) {
            resource.put("labels", new TreeMap<>(series.getResource().getLabelsMap()));
        }
        return resource;
    }

    private static Map<String, Object> metric(TimeSeries series) {
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("type", series.getMetric().getType());
        if (series.getMetric().getLabelsCount() > 0) {
            metric.put("labels", new TreeMap<>(series.getMetric().getLabelsMap()));
        }
        return metric;
    }

    private static String format(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static Instant instant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }
}
