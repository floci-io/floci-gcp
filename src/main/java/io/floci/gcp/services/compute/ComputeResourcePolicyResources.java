package io.floci.gcp.services.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.core.common.GcpException;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static io.floci.gcp.services.compute.ComputeService.integer;
import static io.floci.gcp.services.compute.ComputeService.labels;
import static io.floci.gcp.services.compute.ComputeService.object;
import static io.floci.gcp.services.compute.ComputeService.objectArray;
import static io.floci.gcp.services.compute.ComputeService.required;
import static io.floci.gcp.services.compute.ComputeService.requireObject;

/** Regional resource policies plus the shared attach/detach logic used by instances and disks. */
@ApplicationScoped
public class ComputeResourcePolicyResources implements ComputeResourceHandler {
    private static final String COLLECTION = "resourcePolicies";
    private static final String INSTANCE_SCHEDULE = "instanceSchedulePolicy";
    private static final String SNAPSHOT_SCHEDULE = "snapshotSchedulePolicy";
    private static final Set<String> UNSUPPORTED_TYPES = Set.of("groupPlacementPolicy", "workloadPolicy", "diskConsistencyGroupPolicy");
    private static final List<String> POLICY_TYPES = List.of(INSTANCE_SCHEDULE, SNAPSHOT_SCHEDULE,
            "groupPlacementPolicy", "workloadPolicy", "diskConsistencyGroupPolicy");
    private static final List<String> MUTABLE = List.of("description", INSTANCE_SCHEDULE, SNAPSHOT_SCHEDULE,
            "groupPlacementPolicy", "workloadPolicy", "diskConsistencyGroupPolicy");
    private static final Pattern HOURLY_START = Pattern.compile("([01]?\\d|2[0-3]):00");
    private static final Pattern DAILY_START = Pattern.compile("([01]?\\d|2[0-3]):00([+-](?:1[0-4]|0?\\d))?");
    private static final Pattern POLICY_PATH = Pattern.compile("regions/[^/]+/" + COLLECTION + "/[^/]+");
    private static final Set<String> DAYS = Set.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");
    private static final List<String> MONTHS = List.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");
    private static final List<String> WEEKDAYS = List.of("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT");
    private static final Set<String> ON_SOURCE_DISK_DELETE = Set.of("KEEP_AUTO_SNAPSHOTS", "APPLY_RETENTION_POLICY");

    public boolean handles(String kind) { return COLLECTION.equals(kind); }

    public void create(ComputeService.Context c, ObjectNode r) {
        c.scope("regions");
        dropUnsetTypes(r);
        validate(r);
        r.put("status", "READY");
    }

    /**
     * resourcePolicies.patch. Without updateMask every mutable field in the body replaces the stored one, as
     * Terraform sends it; with updateMask only the listed paths change, and a listed path absent from the body is cleared.
     */
    public void update(ComputeService.Context c, ObjectNode r, ObjectNode body, String verb) {
        if (!verb.equals("PATCH")) { ComputeResourceHandler.super.update(c, r, body, verb); return; }
        dropUnsetTypes(body);
        if (body.has("name") && !body.path("name").asText().equals(r.path("name").asText())) {
            throw GcpException.invalidArgument("Resource policy name cannot be changed");
        }
        String type = r.has(INSTANCE_SCHEDULE) ? INSTANCE_SCHEDULE : SNAPSHOT_SCHEDULE;
        String mask = c.option("updateMask", "");
        if (mask.isBlank()) {
            for (String field : MUTABLE) {
                if (!body.has(field)) { continue; }
                if (body.get(field).isNull()) { r.remove(field); } else { r.set(field, body.get(field)); }
            }
        } else {
            for (String path : mask.split(",")) { applyMaskPath(r, body, path.trim()); }
        }
        dropUnsetTypes(r);
        if (!r.has(type) || r.has(type.equals(INSTANCE_SCHEDULE) ? SNAPSHOT_SCHEDULE : INSTANCE_SCHEDULE)) {
            throw GcpException.invalidArgument("Resource policy type cannot be changed from " + type);
        }
        validate(r);
        r.put("status", "READY");
    }

    private static void applyMaskPath(ObjectNode r, ObjectNode body, String path) {
        String[] segments = path.split("\\.");
        if (path.isEmpty() || !MUTABLE.contains(segments[0])) {
            throw GcpException.invalidArgument("Invalid updateMask path: " + path);
        }
        ObjectNode target = r;
        JsonNode source = body;
        for (int i = 0; i < segments.length - 1; i++) {
            source = source.path(segments[i]);
            JsonNode next = target.get(segments[i]);
            if (next == null || !next.isObject()) { next = target.putObject(segments[i]); }
            target = (ObjectNode) next;
        }
        String leaf = segments[segments.length - 1];
        JsonNode value = source.get(leaf);
        if (value == null || value.isNull()) { target.remove(leaf); } else { target.set(leaf, value); }
    }

    /** Terraform serializes unset policy types as null or {}; those mean "absent". */
    private static void dropUnsetTypes(ObjectNode r) {
        for (String field : POLICY_TYPES) {
            if (r.has(field) && (r.get(field).isNull() || r.get(field).isEmpty())) { r.remove(field); }
        }
    }

    private static void validate(ObjectNode r) {
        for (String unsupported : UNSUPPORTED_TYPES) {
            if (r.has(unsupported)) { throw GcpException.unimplemented("Resource policy type " + unsupported + " is not implemented"); }
        }
        boolean instance = r.has(INSTANCE_SCHEDULE), snapshot = r.has(SNAPSHOT_SCHEDULE);
        if (instance == snapshot) {
            throw GcpException.invalidArgument("Specify exactly one of " + INSTANCE_SCHEDULE + " or " + SNAPSHOT_SCHEDULE);
        }
        if (instance) { instanceSchedule(requireObject(r.get(INSTANCE_SCHEDULE), INSTANCE_SCHEDULE)); }
        else { snapshotSchedule(requireObject(r.get(SNAPSHOT_SCHEDULE), SNAPSHOT_SCHEDULE)); }
    }

    public void delete(ComputeService.Context c, ObjectNode r) {
        String self = r.path("selfLink").asText();
        for (Map.Entry<String, ObjectNode> entry : c.state.resources.entrySet()) {
            for (JsonNode policy : entry.getValue().path(COLLECTION)) {
                if (policy.asText().equals(self)) {
                    throw GcpException.invalidArgument("The resource_policy resource '" + c.path(self)
                            + "' is already being used by '" + c.path(entry.getValue().path("selfLink").asText()) + "'")
                            .withReason("resourceInUseByAnotherResource");
                }
            }
        }
    }

    private static void instanceSchedule(ObjectNode policy) {
        boolean start = policy.has("vmStartSchedule"), stop = policy.has("vmStopSchedule");
        if (!start && !stop) { throw GcpException.invalidArgument("At least one of vmStartSchedule or vmStopSchedule is required"); }
        for (String field : List.of("vmStartSchedule", "vmStopSchedule")) {
            if (policy.has(field)) {
                String cron = required(requireObject(policy.get(field), field), "schedule");
                if (!validCron(cron)) { throw GcpException.invalidArgument("Invalid cron schedule in " + field + ": " + cron); }
            }
        }
        if (!policy.has("timeZone")) { policy.put("timeZone", "UTC"); }
        String zone = required(policy, "timeZone");
        // GCP takes tz database names. Exact, case-sensitive region IDs are accepted (UTC, Etc/UTC, America/Bogota);
        // offsets such as +05:00 or UTC+5 and the legacy short aliases such as EST are not region IDs and are rejected.
        if (!ZoneId.getAvailableZoneIds().contains(zone)) { throw GcpException.invalidArgument("Invalid timeZone " + zone + "; expected a tz database name"); }
        for (String field : List.of("startTime", "expirationTime")) {
            if (policy.has(field)) { timestamp(required(policy, field), field); }
        }
    }

    /**
     * Unix cron: five fields; {@code *}, lists, ranges, and {@code /step} on {@code *}, a range or a single start value
     * ({@code 5/10} is every 10 from 5). GCP documents only "unix-cron", so the lenient dialect is accepted.
     */
    private static boolean validCron(String cron) {
        String[] fields = cron.trim().split("\\s+");
        if (fields.length != 5) { return false; }
        return cronField(fields[0], 0, 59, null) && cronField(fields[1], 0, 23, null) && cronField(fields[2], 1, 31, null)
                && cronField(fields[3], 1, 12, MONTHS) && cronField(fields[4], 0, 7, WEEKDAYS);
    }

    private static boolean cronField(String field, int min, int max, List<String> names) {
        for (String item : field.split(",", -1)) {
            String[] step = item.split("/", -1);
            if (step.length > 2) { return false; }
            if (step.length == 2 && !(step[1].matches("\\d{1,9}") && Integer.parseInt(step[1]) >= 1)) { return false; }
            if (step[0].equals("*")) { continue; }
            String[] range = step[0].split("-", -1);
            if (range.length > 2) { return false; }
            int low = cronValue(range[0], min, max, names);
            int high = range.length == 2 ? cronValue(range[1], min, max, names) : low;
            if (low < 0 || high < 0 || low > high) { return false; }
        }
        return true;
    }

    private static int cronValue(String token, int min, int max, List<String> names) {
        if (names != null) {
            int index = names.indexOf(token.toUpperCase(Locale.ROOT));
            if (index >= 0) { return min == 0 ? index : index + min; }
        }
        if (!token.matches("\\d{1,3}")) { return -1; }
        int value = Integer.parseInt(token);
        return value >= min && value <= max ? value : -1;
    }

    private static void snapshotSchedule(ObjectNode policy) {
        ObjectNode schedule = requireObject(policy.path("schedule"), "schedule");
        List<String> cadences = new ArrayList<>();
        for (String cadence : List.of("hourlySchedule", "dailySchedule", "weeklySchedule")) {
            if (schedule.has(cadence)) { cadences.add(cadence); }
        }
        if (cadences.size() != 1) { throw GcpException.invalidArgument("Specify exactly one of hourlySchedule, dailySchedule or weeklySchedule"); }
        switch (cadences.getFirst()) {
            case "hourlySchedule" -> {
                ObjectNode hourly = requireObject(schedule.get("hourlySchedule"), "hourlySchedule");
                integer(hourly.path("hoursInCycle").asText(), 1, 23, "hoursInCycle");
                onTheHour(hourly);
            }
            case "dailySchedule" -> {
                ObjectNode daily = requireObject(schedule.get("dailySchedule"), "dailySchedule");
                if (!daily.path("daysInCycle").asText().equals("1")) {
                    throw GcpException.invalidArgument("Invalid daysInCycle; daily snapshot schedules must use 1");
                }
                dailyStart(daily);
            }
            default -> {
                List<ObjectNode> days = objectArray(requireObject(schedule.get("weeklySchedule"), "weeklySchedule"), "dayOfWeeks");
                if (days.isEmpty()) { throw GcpException.invalidArgument("dayOfWeeks is required"); }
                for (ObjectNode day : days) {
                    if (!DAYS.contains(required(day, "day"))) { throw GcpException.invalidArgument("Invalid day of week"); }
                    onTheHour(day);
                }
            }
        }
        if (policy.has("retentionPolicy")) {
            ObjectNode retention = requireObject(policy.get("retentionPolicy"), "retentionPolicy");
            if (retention.has("maxRetentionDays")) { integer(retention.path("maxRetentionDays").asText(), 1, 65536, "maxRetentionDays"); }
            if (!retention.has("onSourceDiskDelete")) { retention.put("onSourceDiskDelete", "KEEP_AUTO_SNAPSHOTS"); }
            if (!ON_SOURCE_DISK_DELETE.contains(retention.path("onSourceDiskDelete").asText())) {
                throw GcpException.invalidArgument("Invalid onSourceDiskDelete");
            }
        }
        if (policy.has("snapshotProperties")) { labels(requireObject(policy.get("snapshotProperties"), "snapshotProperties").path("labels")); }
    }

    /** Hourly and weekly startTime: "HH:00" GMT. A one-digit hour is accepted and returned zero-padded, as GCP does. */
    private static void onTheHour(ObjectNode cycle) {
        String value = required(cycle, "startTime");
        Matcher m = HOURLY_START.matcher(value);
        if (!m.matches()) { throw GcpException.invalidArgument("Invalid startTime " + value + "; expected HH:00 in GMT"); }
        cycle.put("startTime", String.format(Locale.ROOT, "%02d:00", Integer.parseInt(m.group(1))));
    }

    /** Daily startTime must resolve in UTC to 00:00, 04:00, 08:00, 12:00, 16:00 or 20:00; "13:00-5" resolves to 08:00. */
    private static void dailyStart(ObjectNode daily) {
        String value = required(daily, "startTime");
        Matcher m = DAILY_START.matcher(value);
        if (!m.matches()) { throw GcpException.invalidArgument("Invalid startTime " + value + "; expected HH:00 in UTC"); }
        int hour = Integer.parseInt(m.group(1));
        int offset = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        if (Math.floorMod(hour + offset, 24) % 4 != 0) {
            throw GcpException.invalidArgument("Invalid startTime " + value + "; it must resolve to 00:00, 04:00, 08:00, 12:00, 16:00 or 20:00 UTC");
        }
        if (m.group(2) == null) { daily.put("startTime", String.format(Locale.ROOT, "%02d:00", hour)); }
    }

    private static void timestamp(String value, String field) {
        try { OffsetDateTime.parse(value); }
        catch (DateTimeParseException e) { throw GcpException.invalidArgument("Invalid " + field + "; expected RFC 3339"); }
    }

    /** Validates {@code resourcePolicies} on an instance or disk being inserted and normalizes the entries to self links. */
    static void validateAttached(ComputeService.Context c, ObjectNode target, String collection) {
        if (!target.has(COLLECTION)) { return; }
        JsonNode node = target.get(COLLECTION);
        if (!node.isArray()) { throw GcpException.invalidArgument(COLLECTION + " must be a JSON array"); }
        ArrayNode links = object().putArray(COLLECTION);
        for (JsonNode ref : node) {
            if (!ref.isTextual()) { throw GcpException.invalidArgument(COLLECTION + " entries must be strings"); }
            String link = resolve(c, ref.asText(), collection);
            if (indexOf(links, link) >= 0) { throw GcpException.invalidArgument("Duplicate resource policy: " + ref.asText()); }
            links.add(link);
        }
        store(target, links);
    }

    /** Implements addResourcePolicies and removeResourcePolicies for instances and disks. */
    static void change(ComputeService.Context c, ObjectNode target, ObjectNode body, boolean add) {
        JsonNode requested = body.path(COLLECTION);
        if (!requested.isArray() || requested.isEmpty()) { throw GcpException.invalidArgument("resourcePolicies is required"); }
        ArrayNode links = object().putArray(COLLECTION);
        target.path(COLLECTION).forEach(links::add);
        for (JsonNode ref : requested) {
            if (!ref.isTextual()) { throw GcpException.invalidArgument(COLLECTION + " entries must be strings"); }
            String link = resolve(c, ref.asText(), c.collection());
            int index = indexOf(links, link);
            if (add) {
                if (index >= 0) { throw GcpException.invalidArgument("Resource policy is already attached: " + ref.asText()); }
                links.add(link);
            } else {
                if (index < 0) { throw GcpException.invalidArgument("Resource policy is not attached: " + ref.asText()); }
                links.remove(index);
            }
        }
        store(target, links);
    }

    private static void store(ObjectNode target, ArrayNode links) {
        if (links.size() > 1) { throw GcpException.invalidArgument("Currently a max of 1 resource policy is supported"); }
        if (links.isEmpty()) { target.remove(COLLECTION); } else { target.set(COLLECTION, links); }
    }

    private static int indexOf(ArrayNode links, String link) {
        for (int i = 0; i < links.size(); i++) {
            if (links.get(i).asText().equals(link)) { return i; }
        }
        return -1;
    }

    private static String resolve(ComputeService.Context c, String ref, String collection) {
        String path = c.path(ref);
        if (!POLICY_PATH.matcher(path).matches()) { throw GcpException.invalidArgument("Invalid resource policy reference: " + ref); }
        ObjectNode policy = c.require(path);
        String zone = c.scope().substring(c.scope().indexOf('/') + 1);
        String region = zone.substring(0, zone.lastIndexOf('-'));
        if (!policy.path("region").asText().equals(c.link("regions/" + region))) {
            throw GcpException.invalidArgument("Resource policy and " + collection + " must be in the same region");
        }
        String expected = collection.equals("instances") ? INSTANCE_SCHEDULE : SNAPSHOT_SCHEDULE;
        if (!policy.has(expected)) {
            throw GcpException.invalidArgument("Resource policy " + policy.path("name").asText() + " cannot be attached to " + collection);
        }
        return policy.path("selfLink").asText();
    }
}
