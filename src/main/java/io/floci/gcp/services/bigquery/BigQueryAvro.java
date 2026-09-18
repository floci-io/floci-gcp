package io.floci.gcp.services.bigquery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.bigquery.model.TableFieldSchema;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Avro output of the Storage Read API. The schema follows BigQuery's documented mapping
 * (INT64 long, NUMERIC bytes/decimal(38, 9), BIGNUMERIC decimal(77, 38), DATE int/date,
 * TIME long/time-micros, TIMESTAMP long/timestamp-micros, DATETIME string/datetime,
 * GEOGRAPHY and JSON strings with a sqlType annotation, ARRAY array, STRUCT record), and
 * "unions with the Avro NULL type" represent nullable columns. Rows are Avro binary-encoded
 * records, concatenated as {@code AvroRows.serialized_binary_rows}.
 */
final class BigQueryAvro {

    private static final ObjectMapper JSON = new ObjectMapper();

    private BigQueryAvro() {}

    // ── Schema ────────────────────────────────────────────────────────────────

    static String schema(List<TableFieldSchema> fields) {
        Map<String, Object> root = record("__root__", fields, new int[] {0});
        try {
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            throw GcpException.internal("Could not build the Avro schema: " + e.getMessage());
        }
    }

    private static Map<String, Object> record(String name, List<TableFieldSchema> fields, int[] counter) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("type", "record");
        record.put("name", name);
        List<Map<String, Object>> avroFields = new ArrayList<>();
        for (TableFieldSchema field : fields) {
            Map<String, Object> avroField = new LinkedHashMap<>();
            avroField.put("name", field.getName());
            if (field.getDescription() != null) {
                avroField.put("doc", field.getDescription());
            }
            Object type = elementType(field, counter);
            if ("REPEATED".equals(field.getMode())) {
                avroField.put("type", Map.of("type", "array", "items", type));
            } else if ("REQUIRED".equals(field.getMode())) {
                avroField.put("type", type);
            } else {
                avroField.put("type", List.of("null", type));
            }
            avroFields.add(avroField);
        }
        record.put("fields", avroFields);
        return record;
    }

    private static Object elementType(TableFieldSchema field, int[] counter) {
        String type = field.getType() != null ? field.getType() : "STRING";
        return switch (type) {
            case "INTEGER", "INT64" -> "long";
            case "FLOAT", "FLOAT64" -> "double";
            case "BOOLEAN", "BOOL" -> "boolean";
            case "BYTES" -> "bytes";
            case "DATE" -> Map.of("type", "int", "logicalType", "date");
            case "TIME" -> Map.of("type", "long", "logicalType", "time-micros");
            case "TIMESTAMP" -> Map.of("type", "long", "logicalType", "timestamp-micros");
            case "DATETIME" -> Map.of("type", "string", "logicalType", "datetime");
            case "NUMERIC" -> decimal(38, 9);
            case "BIGNUMERIC" -> decimal(77, 38);
            case "GEOGRAPHY" -> Map.of("type", "string", "sqlType", "GEOGRAPHY");
            case "JSON" -> Map.of("type", "string", "sqlType", "JSON");
            case "RECORD", "STRUCT" -> record(field.getName() + "_" + (counter[0]++),
                    field.getFields() != null ? field.getFields() : List.of(), counter);
            default -> "string";
        };
    }

    private static Map<String, Object> decimal(int precision, int scale) {
        Map<String, Object> decimal = new LinkedHashMap<>();
        decimal.put("type", "bytes");
        decimal.put("logicalType", "decimal");
        decimal.put("precision", precision);
        decimal.put("scale", scale);
        return decimal;
    }

    // ── Binary encoding ──────────────────────────────────────────────────────

    /** Encodes rows (stored representation) as concatenated Avro binary records. */
    static byte[] encode(List<TableFieldSchema> fields, List<Map<String, Object>> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map<String, Object> row : rows) {
            writeRecord(out, fields, row);
        }
        return out.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static void writeRecord(ByteArrayOutputStream out, List<TableFieldSchema> fields, Map<String, Object> row) {
        for (TableFieldSchema field : fields) {
            Object value = row != null ? valueFor(row, field.getName()) : null;
            if ("REPEATED".equals(field.getMode())) {
                List<Object> items = value instanceof List<?> list ? (List<Object>) list : List.of();
                if (!items.isEmpty()) {
                    writeLong(out, items.size());
                    for (Object item : items) {
                        writeValue(out, field, item);
                    }
                }
                writeLong(out, 0);
            } else if ("REQUIRED".equals(field.getMode())) {
                if (value == null) {
                    throw GcpException.internal("Required field " + field.getName() + " has no value");
                }
                writeValue(out, field, value);
            } else if (value == null) {
                writeLong(out, 0); // union branch "null"
            } else {
                writeLong(out, 1);
                writeValue(out, field, value);
            }
        }
    }

    private static Object valueFor(Map<String, Object> row, String name) {
        if (row.containsKey(name)) {
            return row.get(name);
        }
        return row.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(ByteArrayOutputStream out, TableFieldSchema field, Object value) {
        String type = field.getType() != null ? field.getType() : "STRING";
        switch (type) {
            case "INTEGER", "INT64" -> writeLong(out, value instanceof Number n ? n.longValue()
                    : Long.parseLong(value.toString()));
            case "FLOAT", "FLOAT64" -> {
                double d = value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString());
                long bits = Double.doubleToLongBits(d);
                for (int i = 0; i < 8; i++) {
                    out.write((int) (bits >>> (8 * i)) & 0xFF);
                }
            }
            case "BOOLEAN", "BOOL" -> out.write(Boolean.parseBoolean(value.toString()) ? 1 : 0);
            case "BYTES" -> writeBytes(out, Base64.getDecoder().decode(value.toString()));
            case "DATE" -> writeLong(out, LocalDate.parse(value.toString()).toEpochDay());
            case "TIME" -> writeLong(out, LocalTime.parse(value.toString()).toNanoOfDay() / 1000);
            case "TIMESTAMP" -> writeLong(out, Long.parseLong(
                    RowCodec.encodeTimestamp(value.toString(), RowCodec.TimestampFormat.INT64)));
            case "NUMERIC" -> writeBytes(out, unscaled(value, 9));
            case "BIGNUMERIC" -> writeBytes(out, unscaled(value, 38));
            case "RECORD", "STRUCT" -> writeRecord(out, field.getFields() != null ? field.getFields() : List.of(),
                    value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of());
            default -> writeBytes(out, (value instanceof String s ? s : String.valueOf(value))
                    .getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Avro decimal: the two's-complement big-endian unscaled value at the schema's scale. */
    private static byte[] unscaled(Object value, int scale) {
        return new BigDecimal(value.toString()).setScale(scale, RoundingMode.HALF_EVEN).unscaledValue().toByteArray();
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] bytes) {
        writeLong(out, bytes.length);
        out.writeBytes(bytes);
    }

    /** Avro int and long: zig-zag encoded variable-length integers. */
    static void writeLong(ByteArrayOutputStream out, long value) {
        long n = (value << 1) ^ (value >> 63);
        while ((n & ~0x7FL) != 0) {
            out.write((int) ((n & 0x7F) | 0x80));
            n >>>= 7;
        }
        out.write((int) n);
    }
}
