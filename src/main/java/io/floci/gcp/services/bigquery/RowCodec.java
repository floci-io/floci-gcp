package io.floci.gcp.services.bigquery;

import io.floci.gcp.services.bigquery.model.ErrorProto;
import io.floci.gcp.services.bigquery.model.TableCell;
import io.floci.gcp.services.bigquery.model.TableFieldSchema;
import io.floci.gcp.services.bigquery.model.TableRow;
import io.floci.gcp.services.bigquery.model.TableSchema;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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

    /**
     * Coerces one value to a declared field, the way {@code insertAll} does. Query jobs writing into
     * a destination table need the same check, because the declared schema is authoritative there
     * too. Throws {@link IllegalArgumentException} with the caller-facing message.
     */
    static Object coerceValue(TableFieldSchema field, Object raw) {
        return coerce(field, raw, false);
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

    /**
     * Wire format of TIMESTAMP cells: {@code FLOAT64} (epoch seconds, the default),
     * {@code INT64} (epoch microseconds) or {@code ISO8601_STRING}, per
     * {@code formatOptions.useInt64Timestamp} / {@code formatOptions.timestampOutputFormat}.
     */
    enum TimestampFormat {
        FLOAT64, INT64, ISO8601_STRING;

        static TimestampFormat of(Boolean useInt64Timestamp, String timestampOutputFormat) {
            if (timestampOutputFormat != null) {
                switch (timestampOutputFormat.toUpperCase()) {
                    case "INT64" -> {
                        return INT64;
                    }
                    case "ISO8601_STRING" -> {
                        return ISO8601_STRING;
                    }
                    case "FLOAT64" -> {
                        return FLOAT64;
                    }
                    default -> {
                        // TIMESTAMP_OUTPUT_FORMAT_UNSPECIFIED falls back to useInt64Timestamp
                    }
                }
            }
            return Boolean.TRUE.equals(useInt64Timestamp) ? INT64 : FLOAT64;
        }
    }

    static List<TableRow> encodeRows(TableSchema schema, List<Map<String, Object>> rows) {
        return encodeRows(schema, rows, TimestampFormat.FLOAT64);
    }

    static List<TableRow> encodeRows(TableSchema schema, List<Map<String, Object>> rows, TimestampFormat format) {
        List<TableRow> encoded = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            encoded.add(encodeRow(schema, row, format));
        }
        return encoded;
    }

    static TableRow encodeRow(TableSchema schema, Map<String, Object> row, TimestampFormat format) {
        List<TableFieldSchema> fields = schema != null && schema.getFields() != null
                ? schema.getFields() : List.of();
        List<TableCell> cells = new ArrayList<>(fields.size());
        for (TableFieldSchema field : fields) {
            cells.add(new TableCell(encodeValue(field, row.get(field.getName()), format)));
        }
        return new TableRow(cells);
    }

    private static Object encodeValue(TableFieldSchema field, Object value, TimestampFormat format) {
        if (value == null) {
            return null;
        }
        if ("REPEATED".equals(field.getMode()) && value instanceof List<?> list) {
            List<Map<String, Object>> wrapped = new ArrayList<>(list.size());
            for (Object element : list) {
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("v", encodeScalar(field, element, format));
                wrapped.add(cell);
            }
            return wrapped;
        }
        return encodeScalar(field, value, format);
    }

    @SuppressWarnings("unchecked")
    private static Object encodeScalar(TableFieldSchema field, Object value, TimestampFormat format) {
        if (value == null) {
            return null;
        }
        if ("RECORD".equals(field.getType()) && value instanceof Map<?, ?> map) {
            TableSchema subSchema = new TableSchema(field.getFields() != null ? field.getFields() : List.of());
            return Map.of("f", encodeRow(subSchema, (Map<String, Object>) map, format).getF());
        }
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if ("TIMESTAMP".equals(field.getType())) {
            return encodeTimestamp(String.valueOf(value), format);
        }
        return String.valueOf(value);
    }

    /**
     * Stored TIMESTAMP values are whatever {@code insertAll} accepted (epoch seconds or an
     * ISO-8601 / civil-time string) or epoch seconds from the SQL engine; the wire always
     * carries the requested numeric or ISO form, which is what the SDKs parse.
     */
    static String encodeTimestamp(String stored, TimestampFormat format) {
        Long micros = timestampMicros(stored);
        if (micros == null) {
            return stored;
        }
        return switch (format) {
            case INT64 -> String.valueOf(micros);
            case ISO8601_STRING -> ISO_MICROS.format(Instant.EPOCH.plus(micros, ChronoUnit.MICROS));
            case FLOAT64 -> DuckTypes.microsToSeconds(micros);
        };
    }

    private static final DateTimeFormatter ISO_MICROS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private static Long timestampMicros(String stored) {
        String text = stored.trim();
        try {
            return new BigDecimal(text).movePointRight(6).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (NumberFormatException | ArithmeticException ignored) {
            // not epoch seconds; try civil forms below
        }
        String normalized = text.endsWith(" UTC") ? text.substring(0, text.length() - 4) + "Z" : text;
        String seconds = DuckTypes.timestampTextToSeconds(normalized);
        if (seconds.equals(normalized)) {
            return null;
        }
        return new BigDecimal(seconds).movePointRight(6).longValueExact();
    }

    private static ErrorProto error(String reason, String location, String message) {
        ErrorProto error = new ErrorProto();
        error.setReason(reason);
        error.setLocation(location);
        error.setMessage(message);
        return error;
    }
}
