package io.floci.gcp.services.cloudmonitoring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.common.ProjectRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Alerts produced by the evaluator, served through the REST-only Alerts API
 * ({@code projects.alerts.list} and {@code get} in the monitoring v3 discovery document). An alert
 * is a JSON object in that document's {@code Alert} shape; {@code key} records which policy,
 * condition and series opened it, and is never returned.
 */
@ApplicationScoped
public class AlertService {
    private static final Pattern PARENT = Pattern.compile("projects/[^/\\s]+");
    private static final Pattern NAME = Pattern.compile("projects/[^/\\s]+/alerts/[^/\\s]+");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    static final String KEY = "_key";
    /** End time of the newest point seen for the alert's series; auto-close counts from it. */
    static final String LAST_DATA = "_lastData";
    /** The policy's auto-close duration in seconds, kept so alerts of a deleted policy can still close. */
    static final String AUTO_CLOSE = "_autoCloseSeconds";
    private static final String INTERNAL = "_";
    private static final int DEFAULT_PAGE = 50;
    private static final int MAX_PAGE = 1000;

    private final StorageBackend<String, String> alerts;
    private final ProjectRegistry projects;
    private final ObjectMapper json = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    private final Object lock = new Object();

    @Inject
    public AlertService(StorageFactory storageFactory, ProjectRegistry projects) {
        this(storageFactory.createGlobal("monitoring", "monitoring-alerts.json",
                new TypeReference<Map<String, String>>() {}), projects);
    }

    AlertService(StorageBackend<String, String> alerts, ProjectRegistry projects) {
        this.alerts = alerts;
        this.projects = projects;
    }

    public Map<String, Object> listAlerts(String parent, String filter, String orderBy, int pageSize, String pageToken) {
        String project = validateParent(parent);
        MonitoringFilterParser.Filter parsed = MonitoringFilterParser.parse(filter, MonitoringFilterParser.Scope.ALERTS);
        Comparator<Map<String, Object>> order = order(orderBy);
        List<Map<String, Object>> matching;
        synchronized (lock) {
            matching = alerts.keys().stream().filter(key -> key.startsWith(project + "/alerts/"))
                    .map(this::read).filter(alert -> parsed.matches(object -> value(alert, object)))
                    .sorted(order).map(AlertService::visible).toList();
        }
        int size = pageSize <= 0 ? DEFAULT_PAGE : Math.min(pageSize, MAX_PAGE);
        PageToken.Page<Map<String, Object>> page = PageToken.paginate(matching, Math.min(size, Math.max(1, matching.size())),
                pageToken);
        Map<String, Object> response = new LinkedHashMap<>();
        if (!page.items().isEmpty()) {
            response.put("alerts", page.items());
        }
        if (page.nextPageToken() != null) {
            response.put("nextPageToken", page.nextPageToken());
        }
        response.put("totalSize", matching.size());
        return response;
    }

    public Map<String, Object> getAlert(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw GcpException.invalidArgument("Invalid alert name: " + name);
        }
        String key = projects == null ? name : projects.canonicalResourceName(name);
        synchronized (lock) {
            return visible(alerts.get(key).map(this::parse)
                    .orElseThrow(() -> GcpException.notFound("Alert not found: " + key)));
        }
    }

    // ── Evaluator access ─────────────────────────────────────────────────

    /** The open alert opened by {@code key}, if any. */
    Optional<Map<String, Object>> openAlert(String project, String key) {
        synchronized (lock) {
            return alerts.keys().stream().filter(name -> name.startsWith(project + "/alerts/")).map(this::read)
                    .filter(alert -> key.equals(alert.get(KEY)) && "OPEN".equals(alert.get("state"))).findFirst();
        }
    }

    /** Every open alert of a policy. */
    List<Map<String, Object>> openAlerts(String policyName) {
        String project = policyName.substring(0, policyName.indexOf("/alertPolicies/"));
        synchronized (lock) {
            List<Map<String, Object>> open = new ArrayList<>();
            for (String name : alerts.keys()) {
                if (name.startsWith(project + "/alerts/")) {
                    Map<String, Object> alert = read(name);
                    if ("OPEN".equals(alert.get("state")) && policyName.equals(policyName(alert))) {
                        open.add(alert);
                    }
                }
            }
            return open;
        }
    }

    /** Every open alert of every project, for closing the alerts of policies that are no longer evaluated. */
    List<Map<String, Object>> allOpenAlerts() {
        synchronized (lock) {
            List<Map<String, Object>> open = new ArrayList<>();
            for (String name : alerts.keys()) {
                Map<String, Object> alert = read(name);
                if ("OPEN".equals(alert.get("state"))) {
                    open.add(alert);
                }
            }
            return open;
        }
    }

    /** Records internal bookkeeping fields on a stored alert; the returned copy is the stored one. */
    Map<String, Object> update(Map<String, Object> alert, Map<String, Object> fields) {
        synchronized (lock) {
            Map<String, Object> updated = new LinkedHashMap<>(alert);
            updated.putAll(fields);
            write(updated);
            return updated;
        }
    }

    Map<String, Object> open(String project, String key, Map<String, Object> fields) {
        synchronized (lock) {
            String name;
            do {
                name = project + "/alerts/" + Long.toUnsignedString(random.nextLong() >>> 1);
            } while (alerts.get(name).isPresent());
            Map<String, Object> alert = new LinkedHashMap<>();
            alert.put("name", name);
            alert.put("state", "OPEN");
            alert.putAll(fields);
            alert.put(KEY, key);
            write(alert);
            return alert;
        }
    }

    Map<String, Object> close(Map<String, Object> alert, String closeTime) {
        synchronized (lock) {
            Map<String, Object> closed = new LinkedHashMap<>(alert);
            closed.put("state", "CLOSED");
            closed.put("closeTime", closeTime);
            write(closed);
            return closed;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    static String policyName(Map<String, Object> alert) {
        return alert.get("policy") instanceof Map<?, ?> policy ? (String) ((Map<String, Object>) policy).get("name") : null;
    }

    static Map<String, Object> visible(Map<String, Object> alert) {
        Map<String, Object> copy = new LinkedHashMap<>(alert);
        copy.keySet().removeIf(field -> field.startsWith(INTERNAL));
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static String value(Map<String, Object> alert, String object) {
        return switch (object) {
            case "state" -> (String) alert.get("state");
            case "open_time" -> (String) alert.get("openTime");
            case "close_time" -> (String) alert.get("closeTime");
            case "policy.name" -> policyName(alert);
            case "policy.display_name" -> alert.get("policy") instanceof Map<?, ?> policy
                    ? (String) ((Map<String, Object>) policy).get("displayName") : null;
            case "resource.type" -> alert.get("resource") instanceof Map<?, ?> resource
                    ? (String) ((Map<String, Object>) resource).get("type") : null;
            case "metric.type" -> alert.get("metric") instanceof Map<?, ?> metric
                    ? (String) ((Map<String, Object>) metric).get("type") : null;
            default -> null;
        };
    }

    /** {@code open_time desc} by default; {@code open_time}, {@code close_time} and {@code desc} otherwise. */
    private static Comparator<Map<String, Object>> order(String orderBy) {
        if (orderBy == null || orderBy.isBlank()) {
            return Comparator.comparing((Map<String, Object> alert) -> String.valueOf(alert.get("openTime"))).reversed();
        }
        Comparator<Map<String, Object>> order = null;
        for (String term : orderBy.split(",")) {
            String[] parts = term.trim().split("\\s+");
            String field = switch (parts[0]) {
                case "open_time", "openTime" -> "openTime";
                case "close_time", "closeTime" -> "closeTime";
                default -> throw GcpException.invalidArgument("Unsupported order_by field: " + parts[0]);
            };
            Comparator<Map<String, Object>> one = Comparator.comparing(
                    (Map<String, Object> alert) -> alert.get(field) == null ? "" : String.valueOf(alert.get(field)));
            if (parts.length > 1 && parts[1].equalsIgnoreCase("desc")) {
                one = one.reversed();
            }
            order = order == null ? one : order.thenComparing(one);
        }
        return order;
    }

    private String validateParent(String parent) {
        if (parent == null || !PARENT.matcher(parent).matches()) {
            throw GcpException.invalidArgument("Invalid project name: " + parent);
        }
        return projects == null ? parent : projects.canonicalProjectName(parent);
    }

    private Map<String, Object> read(String name) {
        return alerts.get(name).map(this::parse).orElseThrow();
    }

    private Map<String, Object> parse(String value) {
        try {
            return json.readValue(value, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable stored alert", e);
        }
    }

    private void write(Map<String, Object> alert) {
        try {
            alerts.put((String) alert.get("name"), json.writeValueAsString(alert));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unwritable alert", e);
        }
    }
}
