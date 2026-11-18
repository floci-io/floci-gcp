package io.floci.gcp.services.cloudlogging;

import io.floci.gcp.services.cloudlogging.model.StoredLogEntry;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The BigQuery shape of exported log entries. LogEntry field names are kept as they are; the keys
 * of labels, resource labels and JSON payloads are lowercased, unsupported characters become
 * {@code _}, leading underscores are removed and names are capped at 128 characters. Schemas are
 * plain maps in the BigQuery REST {@code TableFieldSchema} shape.
 */
final class BigQueryLogRows {
    static final String ERRORS_TABLE = "export_errors";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("uuuuMMdd").withZone(ZoneOffset.UTC);
    private static final int MAX_NAME = 128;

    private BigQueryLogRows() {}

    /** {@code syslog_20170523}, or {@code syslog} for partitioned tables; {@code -}, {@code .} and {@code /} become {@code _}. */
    static String tableId(StoredLogEntry entry, boolean partitioned) {
        String encoded = entry.getLogName().substring(entry.getLogName().indexOf("/logs/") + "/logs/".length());
        String log = URLDecoder.decode(encoded.replace("+", "%2B"), StandardCharsets.UTF_8)
                .replaceAll("[^A-Za-z0-9_]", "_");
        return partitioned ? log : log + "_" + DAY.format(Instant.parse(entry.getTimestamp()));
    }

    static String errorsTableId(StoredLogEntry entry, boolean partitioned) {
        return partitioned ? ERRORS_TABLE : ERRORS_TABLE + "_" + DAY.format(Instant.parse(entry.getTimestamp()));
    }

    static Map<String, Object> row(StoredLogEntry entry) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("logName", entry.getLogName());
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("type", entry.getResourceType() == null ? "global" : entry.getResourceType());
        if (entry.getResourceLabels() != null && !entry.getResourceLabels().isEmpty()) {
            resource.put("labels", normalizeKeys(entry.getResourceLabels()));
        }
        row.put("resource", resource);
        putIfPresent(row, "timestamp", entry.getTimestamp());
        putIfPresent(row, "receiveTimestamp", entry.getReceiveTimestamp());
        row.put("severity", entry.getSeverity() == null ? "DEFAULT" : entry.getSeverity());
        putIfPresent(row, "insertId", entry.getInsertId());
        putIfPresent(row, "trace", entry.getTrace());
        putIfPresent(row, "spanId", entry.getSpanId());
        if (entry.getLabels() != null && !entry.getLabels().isEmpty()) {
            row.put("labels", normalizeKeys(entry.getLabels()));
        }
        putIfPresent(row, "textPayload", entry.getTextPayload());
        if (entry.getJsonPayload() instanceof Map<?, ?> json && !json.isEmpty()) {
            row.put("jsonPayload", normalizeKeys(json));
        }
        return row;
    }

    /** The schema a row implies. Numbers are FLOAT, so integral and fractional values share a column. */
    static List<Map<String, Object>> schema(Map<String, Object> row) {
        List<Map<String, Object>> fields = new ArrayList<>();
        row.forEach((name, value) -> field(name, value, true).ifPresent(fields::add));
        return fields;
    }

    /**
     * Adds the fields {@code addition} has and {@code existing} lacks. Returns empty when a field
     * both define has a different type or mode, which real Logging writes to the error table.
     */
    static Optional<List<Map<String, Object>>> merge(List<Map<String, Object>> existing,
            List<Map<String, Object>> addition, StringBuilder conflict, String path) {
        List<Map<String, Object>> merged = new ArrayList<>();
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        existing.forEach(field -> byName.put((String) field.get("name"), field));
        for (Map<String, Object> field : existing) {
            merged.add(new LinkedHashMap<>(field));
        }
        for (Map<String, Object> field : addition) {
            String name = (String) field.get("name");
            Map<String, Object> current = byName.get(name);
            if (current == null) {
                merged.add(field);
                continue;
            }
            if (!current.get("type").equals(field.get("type")) || !mode(current).equals(mode(field))) {
                conflict.append("Field ").append(path).append(name).append(" has type ").append(current.get("type"))
                        .append(" (").append(mode(current)).append(") but the log entry has ")
                        .append(field.get("type")).append(" (").append(mode(field)).append(")");
                return Optional.empty();
            }
            if ("RECORD".equals(field.get("type"))) {
                Optional<List<Map<String, Object>>> nested = merge(fields(current), fields(field), conflict,
                        path + name + ".");
                if (nested.isEmpty()) {
                    return Optional.empty();
                }
                Map<String, Object> replaced = new LinkedHashMap<>(current);
                replaced.put("fields", nested.get());
                merged.set(merged.indexOf(current), replaced);
            }
        }
        return Optional.of(merged);
    }

    static List<Map<String, Object>> errorsSchema() {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (String name : List.of("logEntry", "schemaErrorDetail", "sink", "logName")) {
            fields.add(leaf(name, "STRING", "NULLABLE"));
        }
        fields.add(leaf("timestamp", "TIMESTAMP", "NULLABLE"));
        fields.add(leaf("receiveTimestamp", "TIMESTAMP", "NULLABLE"));
        for (String name : List.of("severity", "insertId", "trace", "resourceType")) {
            fields.add(leaf(name, "STRING", "NULLABLE"));
        }
        return fields;
    }

    static String normalizeName(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_").replaceFirst("^_+", "");
        return normalized.length() > MAX_NAME ? normalized.substring(0, MAX_NAME) : normalized;
    }

    private static Map<String, Object> normalizeKeys(Map<?, ?> value) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        value.forEach((key, child) -> {
            String name = normalizeName(String.valueOf(key));
            if (!name.isEmpty()) {
                normalized.put(name, child instanceof Map<?, ?> map ? normalizeKeys(map) : normalizeList(child));
            }
        });
        return normalized;
    }

    private static Object normalizeList(Object value) {
        if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>();
            for (Object item : list) {
                normalized.add(item instanceof Map<?, ?> map ? normalizeKeys(map) : item);
            }
            return normalized;
        }
        return value;
    }

    private static Optional<Map<String, Object>> field(String name, Object value, boolean topLevel) {
        if (value == null) {
            return Optional.empty();
        }
        if (topLevel && (name.equals("timestamp") || name.equals("receiveTimestamp"))) {
            return Optional.of(leaf(name, "TIMESTAMP", "NULLABLE"));
        }
        if (value instanceof Map<?, ?> map) {
            List<Map<String, Object>> children = new ArrayList<>();
            map.forEach((key, child) -> field(String.valueOf(key), child, false).ifPresent(children::add));
            if (children.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> record = leaf(name, "RECORD", "NULLABLE");
            record.put("fields", children);
            return Optional.of(record);
        }
        if (value instanceof List<?> list) {
            Optional<Object> first = list.stream().filter(item -> item != null && !(item instanceof List<?>)).findFirst()
                    .map(item -> (Object) item);
            if (first.isEmpty()) {
                return Optional.empty();
            }
            return field(name, first.get(), false).map(element -> {
                element.put("mode", "REPEATED");
                return element;
            });
        }
        return Optional.of(leaf(name, scalarType(value), "NULLABLE"));
    }

    private static String scalarType(Object value) {
        if (value instanceof Boolean) {
            return "BOOLEAN";
        }
        if (value instanceof Number) {
            return "FLOAT";
        }
        return "STRING";
    }

    private static Map<String, Object> leaf(String name, String type, String mode) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("name", name);
        field.put("type", type);
        field.put("mode", mode);
        return field;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fields(Map<String, Object> field) {
        Object fields = field.get("fields");
        return fields instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static String mode(Map<String, Object> field) {
        Object mode = field.get("mode");
        return mode == null ? "NULLABLE" : (String) mode;
    }

    private static void putIfPresent(Map<String, Object> row, String name, String value) {
        if (value != null && !value.isEmpty()) {
            row.put(name, value);
        }
    }
}
