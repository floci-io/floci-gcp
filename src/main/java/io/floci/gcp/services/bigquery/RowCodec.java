package io.floci.gcp.services.bigquery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.core.common.GcpException;
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
import java.time.format.DateTimeParseException;
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

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

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
            String name = field.getName();
            if (name == null || name.isBlank()) {
                throw GcpException.invalidArgument("Table schema field name cannot be empty").withReason("invalid");
            }
            copy.setName(name);
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
        return normalizeRow(schema, json, ignoreUnknownValues, false, out);
    }

    /**
     * {@code nativeJson} selects how JSON columns read their value. In a NEWLINE_DELIMITED_JSON
     * load the value is the JSON value itself, so a string is stored as a JSON string. In
     * {@code insertAll} a string carries JSON text. Both verified against BigQuery.
     */
    static List<ErrorProto> normalizeRow(TableSchema schema, Map<String, Object> json,
                                         boolean ignoreUnknownValues, boolean nativeJson,
                                         Map<String, Object> out) {
        return normalizeRow(schema, json, ignoreUnknownValues, nativeJson ? JsonInput.NATIVE : JsonInput.TEXT, out);
    }

    /**
     * How a JSON field's value arrives: as JSON text ({@code insertAll}) or as the JSON value itself
     * (NEWLINE_DELIMITED_JSON load). Either way every JSON cell, top-level or nested, is stored as
     * JSON text, which is what {@code tabledata.list} returns; {@link #stagingRow} parses nested
     * cells back for the query engine.
     */
    private enum JsonInput { TEXT, NATIVE }

    private static List<ErrorProto> normalizeRow(TableSchema schema, Map<String, Object> json,
                                                 boolean ignoreUnknownValues, JsonInput jsonInput,
                                                 Map<String, Object> out) {
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
                out.put(field.getName(), coerce(field, raw, ignoreUnknownValues, jsonInput));
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
        return coerce(field, raw, false, JsonInput.TEXT);
    }

    private static Object coerce(TableFieldSchema field, Object raw, boolean ignoreUnknownValues,
                                 JsonInput jsonInput) {
        if ("REPEATED".equals(field.getMode())) {
            if (!(raw instanceof List<?> list)) {
                throw new IllegalArgumentException(
                        "Repeated field " + field.getName() + " requires an array value.");
            }
            List<Object> coerced = new ArrayList<>(list.size());
            for (Object element : list) {
                coerced.add(coerceScalar(field, element, ignoreUnknownValues, jsonInput));
            }
            return coerced;
        }
        return coerceScalar(field, raw, ignoreUnknownValues, jsonInput);
    }

    @SuppressWarnings("unchecked")
    private static Object coerceScalar(TableFieldSchema field, Object raw, boolean ignoreUnknownValues,
                                       JsonInput jsonInput) {
        String type = field.getType();
        switch (type) {
            case "INTEGER" -> {
                if (raw instanceof Long || raw instanceof Integer || raw instanceof Short || raw instanceof Byte) {
                    return ((Number) raw).longValue();
                }
                if (raw instanceof Number n && n.doubleValue() == Math.floor(n.doubleValue())) {
                    try {
                        // Exact, so a value outside INT64 is rejected rather than wrapped or clamped.
                        return new BigDecimal(n.toString()).longValueExact();
                    } catch (ArithmeticException | NumberFormatException ignored) {
                        // falls through to the error below
                    }
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
                            normalizeRow(subSchema, (Map<String, Object>) map, ignoreUnknownValues,
                                    jsonInput, nested);
                    if (!nestedErrors.isEmpty()) {
                        throw new IllegalArgumentException(nestedErrors.get(0).getMessage());
                    }
                    return nested;
                }
                throw new IllegalArgumentException("Record field " + field.getName() + " requires an object value.");
            }
            case "TIMESTAMP" -> {
                String str = String.valueOf(raw);
                Long micros;
                try {
                    micros = timestampMicros(str);
                    if (micros == null) {
                        throw new IllegalArgumentException("Could not parse '" + raw + "' as a timestamp. Required format is YYYY-MM-DD HH:MM[:SS[.SSSSSS]]");
                    }
                    if (micros < -62135596800000000L || micros > 253402300799999999L) {
                        throw new IllegalArgumentException("Timestamp is out of supported range: " + raw);
                    }
                } catch (Exception e) {
                    if (e instanceof IllegalArgumentException) {
                        throw (IllegalArgumentException) e;
                    }
                    throw new IllegalArgumentException("Could not parse '" + raw + "' as a timestamp. Required format is YYYY-MM-DD HH:MM[:SS[.SSSSSS]]", e);
                }
                return ISO_MICROS.format(Instant.EPOCH.plus(micros, ChronoUnit.MICROS));
            }
            case "JSON" -> {
                if (jsonInput == JsonInput.TEXT) {
                    return storedAsText(type, raw);
                }
                try {
                    return JSON_MAPPER.writeValueAsString(raw);
                } catch (JsonProcessingException e) {
                    throw new IllegalArgumentException("Cannot convert value to JSON (bad value): " + raw, e);
                }
            }
            default -> {
                return storedAsText(type, raw);
            }
        }
    }

    private static Object storedAsText(String type, Object raw) {
        // STRING, DATE, TIME, DATETIME, NUMERIC, BYTES... stored textually
        if (raw instanceof String || raw instanceof Number || raw instanceof Boolean) {
            return String.valueOf(raw);
        }
        throw new IllegalArgumentException("Cannot convert value to " + type + " (bad value): " + raw);
    }

    /**
     * A stored row as the query engine stages it. Top-level JSON columns stay text, because they
     * are staged as VARCHAR and cast ({@link DuckTypes#stagedAsText}). JSON inside a RECORD or a
     * REPEATED field is read by {@code read_json} as is, so its stored JSON text is parsed back into
     * the JSON value; otherwise the query would see a JSON string.
     */
    static Map<String, Object> stagingRow(TableSchema schema, Map<String, Object> row) {
        return stagingRow(schema, row, true);
    }

    private static Map<String, Object> stagingRow(TableSchema schema, Map<String, Object> row, boolean topLevel) {
        List<TableFieldSchema> fields = schema != null && schema.getFields() != null
                ? schema.getFields() : List.of();
        Map<String, Object> staged = new LinkedHashMap<>(row);
        for (TableFieldSchema field : fields) {
            Object value = row.get(field.getName());
            if (value == null) {
                continue;
            }
            if ("REPEATED".equals(field.getMode()) && value instanceof List<?> list) {
                List<Object> elements = new ArrayList<>(list.size());
                for (Object element : list) {
                    elements.add(stagingScalar(field, element, false));
                }
                staged.put(field.getName(), elements);
            } else {
                staged.put(field.getName(), stagingScalar(field, value, topLevel));
            }
        }
        return staged;
    }

    @SuppressWarnings("unchecked")
    private static Object stagingScalar(TableFieldSchema field, Object value, boolean topLevel) {
        if ("RECORD".equals(field.getType()) && value instanceof Map<?, ?> map) {
            TableSchema subSchema = new TableSchema(field.getFields() != null ? field.getFields() : List.of());
            return stagingRow(subSchema, (Map<String, Object>) map, false);
        }
        if (!topLevel && "JSON".equals(field.getType()) && value instanceof String text) {
            try {
                return JSON_MAPPER.readTree(text);
            } catch (JsonProcessingException e) {
                // insertAll does not validate JSON text yet; keep such a value as it was staged before.
                return text;
            }
        }
        return value;
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

    public static Map<String, Object> formatForDuck(TableSchema schema, Map<String, Object> row) {
        if (schema == null || schema.getFields() == null || row == null) {
            return row;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (TableFieldSchema field : schema.getFields()) {
            if (row.containsKey(field.getName())) {
                out.put(field.getName(), formatValueForDuck(field, row.get(field.getName())));
            }
        }
        return out;
    }

    private static Object formatValueForDuck(TableFieldSchema field, Object value) {
        if (value == null) {
            return null;
        }
        if ("REPEATED".equals(field.getMode()) && value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object element : list) {
                out.add(formatScalarForDuck(field, element));
            }
            return out;
        }
        return formatScalarForDuck(field, value);
    }

    @SuppressWarnings("unchecked")
    private static Object formatScalarForDuck(TableFieldSchema field, Object value) {
        if (value == null) {
            return null;
        }
        if ("RECORD".equals(field.getType()) && value instanceof Map<?, ?> map) {
            TableSchema subSchema = new TableSchema(field.getFields() != null ? field.getFields() : List.of());
            return formatForDuck(subSchema, (Map<String, Object>) map);
        }
        if ("TIMESTAMP".equals(field.getType())) {
            return encodeTimestamp(String.valueOf(value), TimestampFormat.ISO8601_STRING);
        }
        return value;
    }

    private static Object encodeValue(TableFieldSchema field, Object value, TimestampFormat format) {
        if (value == null) {
            // BigQuery never returns a NULL ARRAY: query results and tabledata.list carry it as [].
            return "REPEATED".equals(field.getMode()) ? List.of() : null;
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
     * Stored TIMESTAMP values are normalized ISO-8601 strings from {@code insertAll},
     * or epoch seconds from the SQL engine; the wire always
     * carries the requested numeric or ISO form, which is what the SDKs parse.
     */
    static String encodeTimestamp(String stored, TimestampFormat format) {
        Long micros;
        try {
            micros = timestampMicros(stored);
        } catch (DateTimeParseException | ArithmeticException e) {
            return stored;
        }
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
        normalized = normalized.replaceFirst("^(-?\\d{4,})/(\\d{2})/(\\d{2})", "$1-$2-$3");
        normalized = normalized.replaceFirst("^(-?\\d{4,}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2})(Z|[+-].*)?$", "$1:00$2");
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
