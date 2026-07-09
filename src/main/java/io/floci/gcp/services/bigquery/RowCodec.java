package io.floci.gcp.services.bigquery;

import io.floci.gcp.services.bigquery.model.ErrorProto;
import io.floci.gcp.services.bigquery.model.TableCell;
import io.floci.gcp.services.bigquery.model.TableFieldSchema;
import io.floci.gcp.services.bigquery.model.TableRow;
import io.floci.gcp.services.bigquery.model.TableSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Schema-aware conversion between {@code insertAll} JSON rows, the normalized stored
 * representation, and BigQuery's {@code {f:[{v:...}]}} wire encoding. Scalar cell values
 * are always strings on the wire; REPEATED cells are arrays of {@code {v:...}} and RECORD
 * cells nest {@code {f:[...]}} (the exact contract of the SDK's {@code FieldValue.fromPb}).
 */
final class RowCodec {

    private RowCodec() {}

    /** Standard SQL → legacy type-name mapping; the SDK round-trips legacy names. */
    static String legacyType(String type) {
        if (type == null) {
            return "STRING";
        }
        return switch (type.toUpperCase()) {
            case "INT64" -> "INTEGER";
            case "FLOAT64" -> "FLOAT";
            case "BOOL" -> "BOOLEAN";
            case "STRUCT" -> "RECORD";
            default -> type.toUpperCase();
        };
    }

    static TableSchema normalizeSchema(TableSchema schema) {
        if (schema == null || schema.getFields() == null) {
            return schema;
        }
        return new TableSchema(normalizeFields(schema.getFields()));
    }

    private static List<TableFieldSchema> normalizeFields(List<TableFieldSchema> fields) {
        List<TableFieldSchema> normalized = new ArrayList<>(fields.size());
        for (TableFieldSchema field : fields) {
            TableFieldSchema copy = new TableFieldSchema();
            copy.setName(field.getName());
            copy.setType(legacyType(field.getType()));
            copy.setMode(field.getMode() != null && !field.getMode().isBlank()
                    ? field.getMode().toUpperCase() : "NULLABLE");
            copy.setDescription(field.getDescription());
            if (field.getFields() != null) {
                copy.setFields(normalizeFields(field.getFields()));
            }
            normalized.add(copy);
        }
        return normalized;
    }

    /**
     * Validates and coerces one {@code insertAll} JSON object against the schema.
     * Returns the per-row errors (empty = accepted); the normalized row is written to
     * {@code out} keyed by canonical field names.
     */
    static List<ErrorProto> normalizeRow(TableSchema schema, Map<String, Object> json,
                                         boolean ignoreUnknownValues, Map<String, Object> out) {
        List<ErrorProto> errors = new ArrayList<>();
        List<TableFieldSchema> fields = schema != null && schema.getFields() != null
                ? schema.getFields() : List.of();

        Map<String, TableFieldSchema> byLowerName = new LinkedHashMap<>();
        fields.forEach(f -> byLowerName.put(f.getName().toLowerCase(), f));

        if (!ignoreUnknownValues) {
            for (String key : json.keySet()) {
                if (!byLowerName.containsKey(key.toLowerCase())) {
                    errors.add(error("invalid", key, "no such field: " + key + "."));
                }
            }
        }

        for (TableFieldSchema field : fields) {
            Object raw = valueFor(json, field.getName());
            if (raw == null) {
                if ("REQUIRED".equals(field.getMode())) {
                    errors.add(error("invalid", field.getName(),
                            "Missing required field: " + field.getName() + "."));
                } else {
                    out.put(field.getName(), null);
                }
                continue;
            }
            try {
                out.put(field.getName(), coerce(field, raw, ignoreUnknownValues));
            } catch (IllegalArgumentException e) {
                errors.add(error("invalid", field.getName(), e.getMessage()));
            }
        }
        return errors;
    }

    private static Object valueFor(Map<String, Object> json, String fieldName) {
        if (json.containsKey(fieldName)) {
            return json.get(fieldName);
        }
        return json.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(fieldName))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    private static Object coerce(TableFieldSchema field, Object raw, boolean ignoreUnknownValues) {
        if ("REPEATED".equals(field.getMode())) {
            if (!(raw instanceof List<?> list)) {
                throw new IllegalArgumentException(
                        "Repeated field " + field.getName() + " requires an array value.");
            }
            List<Object> coerced = new ArrayList<>(list.size());
            for (Object element : list) {
                coerced.add(coerceScalar(field, element, ignoreUnknownValues));
            }
            return coerced;
        }
        return coerceScalar(field, raw, ignoreUnknownValues);
    }

    @SuppressWarnings("unchecked")
    private static Object coerceScalar(TableFieldSchema field, Object raw, boolean ignoreUnknownValues) {
        String type = field.getType();
        switch (type) {
            case "INTEGER" -> {
                if (raw instanceof Number n && n.doubleValue() == Math.floor(n.doubleValue())) {
                    return n.longValue();
                }
                if (raw instanceof String s) {
                    try {
                        return Long.parseLong(s.trim());
                    } catch (NumberFormatException ignored) {
                        // falls through to the error below
                    }
                }
                throw new IllegalArgumentException("Cannot convert value to integer (bad value): " + raw);
            }
            case "FLOAT" -> {
                if (raw instanceof Number n) {
                    return n.doubleValue();
                }
                if (raw instanceof String s) {
                    try {
                        return Double.parseDouble(s.trim());
                    } catch (NumberFormatException ignored) {
                        // falls through to the error below
                    }
                }
                throw new IllegalArgumentException("Cannot convert value to double (bad value): " + raw);
            }
            case "BOOLEAN" -> {
                if (raw instanceof Boolean b) {
                    return b;
                }
                if (raw instanceof String s && ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s))) {
                    return Boolean.parseBoolean(s);
                }
                throw new IllegalArgumentException("Cannot convert value to boolean (bad value): " + raw);
            }
            case "RECORD" -> {
                if (raw instanceof Map<?, ?> map) {
                    Map<String, Object> nested = new LinkedHashMap<>();
                    TableSchema subSchema = new TableSchema(field.getFields() != null
                            ? field.getFields() : List.of());
                    List<ErrorProto> nestedErrors =
                            normalizeRow(subSchema, (Map<String, Object>) map, ignoreUnknownValues, nested);
                    if (!nestedErrors.isEmpty()) {
                        throw new IllegalArgumentException(nestedErrors.get(0).getMessage());
                    }
                    return nested;
                }
                throw new IllegalArgumentException("Record field " + field.getName() + " requires an object value.");
            }
            default -> {
                // STRING, TIMESTAMP, DATE, TIME, DATETIME, NUMERIC, BYTES... stored textually
                if (raw instanceof String || raw instanceof Number || raw instanceof Boolean) {
                    return String.valueOf(raw);
                }
                throw new IllegalArgumentException(
                        "Cannot convert value to " + type + " (bad value): " + raw);
            }
        }
    }

    static List<TableRow> encodeRows(TableSchema schema, List<Map<String, Object>> rows) {
        List<TableRow> encoded = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            encoded.add(encodeRow(schema, row));
        }
        return encoded;
    }

    static TableRow encodeRow(TableSchema schema, Map<String, Object> row) {
        List<TableFieldSchema> fields = schema != null && schema.getFields() != null
                ? schema.getFields() : List.of();
        List<TableCell> cells = new ArrayList<>(fields.size());
        for (TableFieldSchema field : fields) {
            cells.add(new TableCell(encodeValue(field, row.get(field.getName()))));
        }
        return new TableRow(cells);
    }

    @SuppressWarnings("unchecked")
    private static Object encodeValue(TableFieldSchema field, Object value) {
        if (value == null) {
            return null;
        }
        if ("REPEATED".equals(field.getMode()) && value instanceof List<?> list) {
            List<Map<String, Object>> wrapped = new ArrayList<>(list.size());
            for (Object element : list) {
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("v", encodeScalar(field, element));
                wrapped.add(cell);
            }
            return wrapped;
        }
        return encodeScalar(field, value);
    }

    @SuppressWarnings("unchecked")
    private static Object encodeScalar(TableFieldSchema field, Object value) {
        if (value == null) {
            return null;
        }
        if ("RECORD".equals(field.getType()) && value instanceof Map<?, ?> map) {
            TableSchema subSchema = new TableSchema(field.getFields() != null ? field.getFields() : List.of());
            return Map.of("f", encodeRow(subSchema, (Map<String, Object>) map).getF());
        }
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        return String.valueOf(value);
    }

    private static ErrorProto error(String reason, String location, String message) {
        ErrorProto error = new ErrorProto();
        error.setReason(reason);
        error.setLocation(location);
        error.setMessage(message);
        return error;
    }
}
