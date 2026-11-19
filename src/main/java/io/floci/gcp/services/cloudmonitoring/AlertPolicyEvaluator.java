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
import java.util.HashMap;
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
 * skipped. Auto-close counts from the newest point of an alert's series, and the alerts of a deleted,
 * disabled or skipped policy close silently once it expires.
 */
@ApplicationScoped
public class AlertPolicyEvaluator {
    private static final Logger LOG = Logger.getLogger(AlertPolicyEvaluator.class);
    private static final Duration DEFAULT_AUTO_CLOSE = Duration.ofDays(7);
    private static final Duration DEFAULT_ALIGNMENT = Duration.ofMinutes(1);
    private static final Duration MAX_LOOKBACK = Duration.ofHours(25);
    private static final Duration MISSING_DATA_ACTIVE_GRACE = Duration.ofHours(24);

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
        Set<String> evaluated = new HashSet<>();
        for (AlertPolicy policy : policies.listAll()) {
            try {
                if (evaluate(policy, now)) {
                    evaluated.add(policy.getName());
                }
            } catch (RuntimeException e) {
                LOG.warnf(e, "Alert policy %s could not be evaluated", policy.getName());
            }
        }
        closeUnevaluated(evaluated, now);
    }

    /**
     * A deleted or disabled policy is not evaluated, so its open alerts would never close. They close
     * when the auto-close duration the policy last had expires, without a notification: a deleted
     * policy has no channels left and a disabled one sends none.
     */
    private void closeUnevaluated(Set<String> evaluated, Instant now) {
        for (Map<String, Object> alert : alerts.allOpenAlerts()) {
            String policy = AlertService.policyName(alert);
            if (policy != null && evaluated.contains(policy)) {
                continue;
            }
            Duration autoClose = alert.get(AlertService.AUTO_CLOSE) instanceof Number seconds
                    ? Duration.ofSeconds(seconds.longValue()) : DEFAULT_AUTO_CLOSE;
            if (expired(lastData(alert), autoClose, now)) {
                alerts.close(alert, now.toString());
                LOG.debugf("Closed alert %s of policy %s, which is no longer evaluated", alert.get("name"), policy);
            }
        }
    }

    // ── Policy ───────────────────────────────────────────────────────────

    /** {@code lastData} is the end of the series' newest point, or null when no series has data. */
    private record Violation(Map<String, Object> resource, Map<String, Object> metric, String observed,
            Instant lastData) {}

    private record Result(boolean met, Map<String, Violation> violations, Set<String> present,
            Map<String, Instant> lastData) {}

    /** Returns false when the policy was not evaluated: it is disabled or has an unsupported condition. */
    private boolean evaluate(AlertPolicy policy, Instant now) {
        if (policy.hasEnabled() && !policy.getEnabled().getValue()) {
            return false;
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
                return false;
            }
            results.put(condition.getName(), result.get());
        }

        Map<String, Violation> firing = combine(policy, results);
        Map<String, AlertPolicy.Condition> conditions = new LinkedHashMap<>();
        policy.getConditionsList().forEach(condition -> conditions.put(condition.getName(), condition));
        String openTime = now.toString();
        Duration autoClose = policy.getAlertStrategy().hasAutoClose()
                ? Duration.ofSeconds(policy.getAlertStrategy().getAutoClose().getSeconds()) : DEFAULT_AUTO_CLOSE;

        for (Map.Entry<String, Violation> one : firing.entrySet()) {
            if (alerts.openAlert(project, one.getKey()).isPresent()) {
                continue;
            }
            Instant lastData = one.getValue().lastData() == null ? now : one.getValue().lastData();
            AlertPolicy.Condition condition = conditions.get(conditionOf(one.getKey()));
            if (expired(lastData, closeAfter(condition, autoClose), now)) {
                continue;
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("openTime", openTime);
            fields.put("policy", snapshot(policy));
            fields.put("resource", one.getValue().resource());
            fields.put("metric", one.getValue().metric());
            fields.put(AlertService.LAST_DATA, lastData.toString());
            fields.put(AlertService.AUTO_CLOSE, autoClose.getSeconds());
            Map<String, Object> alert = alerts.open(project, one.getKey(), fields);
            notifier.notify(policy, condition, AlertService.visible(alert), one.getValue().observed(), channels(policy));
        }

        for (Map<String, Object> stored : alerts.openAlerts(policy.getName())) {
            String key = (String) stored.get(AlertService.KEY);
            String conditionName = conditionOf(key);
            AlertPolicy.Condition condition = conditions.get(conditionName);
            Map<String, Object> alert = recordData(stored, results.get(conditionName), key, autoClose);
            boolean expired = expired(lastData(alert), closeAfter(condition, autoClose), now);
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
        return true;
    }

    /** Moves the alert's last data time to its series' newest point, and keeps the policy's auto-close. */
    private Map<String, Object> recordData(Map<String, Object> alert, Result result, String key, Duration autoClose) {
        Instant seen = result == null ? null : result.lastData().get(seriesOf(key));
        Instant last = lastData(alert);
        boolean newer = seen != null && seen.isAfter(last);
        boolean autoCloseChanged = !(alert.get(AlertService.AUTO_CLOSE) instanceof Number seconds)
                || seconds.longValue() != autoClose.getSeconds();
        if (!newer && !autoCloseChanged) {
            return alert;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(AlertService.LAST_DATA, (newer ? seen : last).toString());
        fields.put(AlertService.AUTO_CLOSE, autoClose.getSeconds());
        return alerts.update(alert, fields);
    }

    /**
     * Auto-close counts from the last data. With missing data evaluated as active, the docs give the
     * auto-close duration plus 24 hours.
     */
    private static Duration closeAfter(AlertPolicy.Condition condition, Duration autoClose) {
        return condition != null && missingDataActive(condition) ? autoClose.plus(MISSING_DATA_ACTIVE_GRACE) : autoClose;
    }

    private static boolean missingDataActive(AlertPolicy.Condition condition) {
        if (!condition.hasConditionThreshold()) {
            return false;
        }
        AlertPolicy.Condition.MetricThreshold threshold = condition.getConditionThreshold();
        return threshold.getEvaluationMissingData() == AlertPolicy.Condition.EvaluationMissingData.EVALUATION_MISSING_DATA_ACTIVE
                && (threshold.getDuration().getSeconds() > 0 || threshold.getDuration().getNanos() > 0);
    }

    private static boolean expired(Instant lastData, Duration closeAfter, Instant now) {
        return lastData.plus(closeAfter).isBefore(now);
    }

    private static Instant lastData(Map<String, Object> alert) {
        Object last = alert.get(AlertService.LAST_DATA);
        return Instant.parse((String) (last instanceof String ? last : alert.get("openTime")));
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
        if (result.present().contains(seriesOf(key))) {
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
        Map<String, Instant> lastData = new HashMap<>();
        for (TimeSeries one : series) {
            if (one.getPointsCount() == 0) {
                continue;
            }
            String identity = identity(one);
            present.add(identity);
            Instant newestEnd = instant(one.getPoints(0).getInterval().getEndTime());
            lastData.put(identity, newestEnd);
            Optional<Double> newest = value(one.getPoints(0).getValue());
            if (newest.isEmpty()) {
                continue;
            }
            Instant runStart = null;
            for (Point point : one.getPointsList()) {
                Optional<Double> value = value(point.getValue());
                if (value.isEmpty() || !compare(threshold.getComparison(), value.get(), threshold.getThresholdValue())) {
                    break;
                }
                runStart = instant(point.getInterval().getEndTime());
            }
            if (runStart != null && !Duration.between(runStart, newestEnd).minus(duration).isNegative()) {
                violations.put(identity, new Violation(resource(one), metric(one), format(newest.get()), newestEnd));
            }
        }
        if (threshold.getEvaluationMissingData() == AlertPolicy.Condition.EvaluationMissingData.EVALUATION_MISSING_DATA_ACTIVE
                && !duration.isZero()) {
            addMissing(project, threshold, aggregation, duration, now, violations, lastData);
        }
        Set<String> evaluated = new HashSet<>(present);
        evaluated.addAll(violations.keySet());
        boolean met = triggered(threshold.hasTrigger() ? threshold.getTrigger() : null, violations.size(), evaluated.size());
        return new Result(met, violations, present, lastData);
    }

    /**
     * EVALUATION_MISSING_DATA_ACTIVE: "If there is no data to evaluate the condition, then evaluate
     * the condition as true." A series that reported in the last day but has no point inside the
     * retest window violates the condition like an absence would.
     */
    private void addMissing(String project, AlertPolicy.Condition.MetricThreshold threshold, Aggregation aggregation,
            Duration duration, Instant now, Map<String, Violation> violations, Map<String, Instant> lastData) {
        Instant silentSince = now.minus(duration);
        for (TimeSeries one : read(project, threshold.getFilter(), aggregation, now.minus(MAX_LOOKBACK), now)) {
            if (one.getPointsCount() == 0) {
                continue;
            }
            Instant newestEnd = instant(one.getPoints(0).getInterval().getEndTime());
            if (newestEnd.isBefore(silentSince)) {
                String identity = identity(one);
                violations.putIfAbsent(identity, new Violation(resource(one), metric(one), null, newestEnd));
                lastData.putIfAbsent(identity, newestEnd);
            }
        }
    }

    private Result absence(String project, AlertPolicy policy, AlertPolicy.Condition.MetricAbsence absence, Instant now) {
        Duration duration = Duration.ofSeconds(absence.getDuration().getSeconds());
        Aggregation aggregation = absence.getAggregationsCount() > 0 ? absence.getAggregations(0) : null;
        Instant created = policy.hasCreationRecord() ? instant(policy.getCreationRecord().getMutateTime()) : now;
        if (created.plus(duration).isAfter(now)) {
            return new Result(false, Map.of(), Set.of(), Map.of());
        }
        List<TimeSeries> recent = read(project, absence.getFilter(), aggregation, now.minus(duration), now);
        Set<String> present = new HashSet<>();
        recent.stream().filter(one -> one.getPointsCount() > 0).forEach(one -> present.add(identity(one)));
        Map<String, Violation> violations = new LinkedHashMap<>();
        for (TimeSeries one : read(project, absence.getFilter(), aggregation, now.minus(MAX_LOOKBACK), now)) {
            String identity = identity(one);
            if (one.getPointsCount() > 0 && !present.contains(identity)) {
                violations.put(identity, new Violation(resource(one), metric(one), null, null));
            }
        }
        if (violations.isEmpty() && present.isEmpty()) {
            String type = MonitoringFilterParser.parse(absence.getFilter(), MonitoringFilterParser.Scope.TIME_SERIES)
                    .requiredMetricType().orElse("");
            violations.put("absent", new Violation(Map.of("type", "global"),
                    type.isEmpty() ? Map.of() : Map.of("type", type), null, null));
        }
        boolean met = triggered(absence.hasTrigger() ? absence.getTrigger() : null, violations.size(),
                Math.max(1, violations.size() + present.size()));
        return new Result(met, violations, present, Map.of());
    }

    /** ListTimeSeries pages by point count and can split a series across pages, so pieces are joined. */
    private List<TimeSeries> read(String project, String filter, Aggregation aggregation, Instant start, Instant end) {
        TimeInterval interval = TimeInterval.newBuilder().setStartTime(timestamp(start)).setEndTime(timestamp(end)).build();
        Map<String, TimeSeries.Builder> joined = new LinkedHashMap<>();
        String token = null;
        do {
            PageToken.Page<TimeSeries> page = monitoring.listTimeSeries(project, filter, interval,
                    aggregation == null ? Aggregation.getDefaultInstance() : aggregation, "FULL", 0, token);
            for (TimeSeries piece : page.items()) {
                TimeSeries.Builder series = joined.get(identity(piece));
                if (series == null) {
                    joined.put(identity(piece), piece.toBuilder());
                } else {
                    series.addAllPoints(piece.getPointsList());
                }
            }
            token = page.nextPageToken();
        } while (token != null && !token.isEmpty());
        return joined.values().stream().map(TimeSeries.Builder::build).toList();
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

    private static String seriesOf(String key) {
        return key.substring(key.indexOf('|') + 1);
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
