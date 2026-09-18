package io.floci.gcp.services.bigquery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.services.bigquery.model.TableFieldSchema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Type bridge between BigQuery table schemas and DuckDB, used by {@link DuckSqlEngine}:
 * BigQuery field → DuckDB column type for staging, DuckDB {@code DESCRIBE} type → BigQuery
 * result field, and DuckDB result values → the stored row representation that
 * {@link RowCodec} encodes onto the wire.
 */
final class DuckTypes {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** How a result column must be projected so floci-duck returns its value losslessly. */
    enum Projection { RAW, VARCHAR, EPOCH_MICROS, BASE64, JSON }

    /** A parsed DuckDB type: a scalar base name, a STRUCT (fields) or a LIST (element). */
    record DuckType(String base, List<DuckField> fields, DuckType element) {

        static DuckType scalar(String base) {
            return new DuckType(base, null, null);
        }

        boolean isList() {
            return element != null;
        }

        boolean isStruct() {
            return fields != null;
        }
    }

    record DuckField(String name, DuckType type) {}

    private DuckTypes() {}

    // ── BigQuery schema → DuckDB ─────────────────────────────────────────────

    /** DuckDB column type for a BigQuery field, including REPEATED and RECORD nesting. */
    static String duckType(TableFieldSchema field) {
        String base = "RECORD".equals(field.getType()) ? structType(field) : scalarDuckType(field.getType());
        return "REPEATED".equals(field.getMode()) ? base + "[]" : base;
    }

    private static String structType(TableFieldSchema field) {
        List<TableFieldSchema> children = field.getFields() != null ? field.getFields() : List.of();
        if (children.isEmpty()) {
            return "JSON";
        }
        StringBuilder sb = new StringBuilder("STRUCT(");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(quoteIdentifier(children.get(i).getName())).append(' ').append(duckType(children.get(i)));
        }
        return sb.append(')').toString();
    }

    static String scalarDuckType(String bigQueryType) {
        return switch (bigQueryType == null ? "STRING" : bigQueryType) {
            case "INTEGER" -> "BIGINT";
            case "FLOAT" -> "DOUBLE";
            case "BOOLEAN" -> "BOOLEAN";
            case "NUMERIC", "BIGNUMERIC" -> "DECIMAL(38,9)";
            case "BYTES" -> "BLOB";
            case "DATE" -> "DATE";
            case "TIME" -> "TIME";
            case "DATETIME" -> "TIMESTAMP";
            case "TIMESTAMP" -> "TIMESTAMPTZ";
            case "JSON" -> "JSON";
            default -> "VARCHAR";
        };
    }

    /**
     * True when a top-level column is staged as VARCHAR and converted in SQL, because
     * {@code insertAll} stores it as text that {@code read_json} cannot parse directly
     * (epoch-second timestamps, base64 bytes, decimal strings, JSON documents).
     */
    static boolean stagedAsText(TableFieldSchema field) {
        if ("REPEATED".equals(field.getMode()) || "RECORD".equals(field.getType())) {
            return false;
        }
        return switch (field.getType() == null ? "STRING" : field.getType()) {
            case "TIMESTAMP", "BYTES", "NUMERIC", "BIGNUMERIC", "JSON", "DATE", "TIME", "DATETIME" -> true;
            default -> false;
        };
    }

    /** SQL converting a column staged as text into its DuckDB type. */
    static String convertStagedText(TableFieldSchema field, String column) {
        return switch (field.getType()) {
            case "TIMESTAMP" -> "CASE WHEN " + column + " IS NULL THEN NULL"
                    + " WHEN regexp_full_match(" + column + ", '-?[0-9]+(\\.[0-9]*)?([eE][-+]?[0-9]+)?')"
                    + " THEN make_timestamptz(CAST(round(CAST(" + column + " AS DOUBLE) * 1000000) AS BIGINT))"
                    + " ELSE CAST(" + column + " AS TIMESTAMPTZ) END";
            case "BYTES" -> "from_base64(" + column + ")";
            default -> "CAST(" + column + " AS " + scalarDuckType(field.getType()) + ")";
        };
    }

    // ── DuckDB type strings ──────────────────────────────────────────────────

    /** Parses a DuckDB type as printed by {@code DESCRIBE}, e.g. {@code STRUCT(a INTEGER[], "b c" VARCHAR)[]}. */
    static DuckType parse(String type) {
        return new TypeParser(type).parseType();
    }

    private static final class TypeParser {
        private final String s;
        private int pos;

        TypeParser(String s) {
            this.s = s.trim();
        }

        DuckType parseType() {
            skipSpaces();
            String word = readWord();
            String upper = word.toUpperCase(Locale.ROOT);
            DuckType type;
            if ("STRUCT".equals(upper) && peek() == '(') {
                pos++;
                List<DuckField> fields = new ArrayList<>();
                while (true) {
                    skipSpaces();
                    if (peek() == ')') {
                        pos++;
                        break;
                    }
                    String name = readName();
                    DuckType fieldType = parseType();
                    fields.add(new DuckField(name, fieldType));
                    skipSpaces();
                    if (peek() == ',') {
                        pos++;
                    }
                }
                type = new DuckType("STRUCT", fields, null);
            } else {
                StringBuilder base = new StringBuilder(upper);
                // Multi-word names: TIMESTAMP WITH TIME ZONE, DOUBLE PRECISION, TIME WITH TIME ZONE.
                while (true) {
                    int save = pos;
                    skipSpaces();
                    String next = readWord();
                    if (!next.isEmpty() && isTypeContinuation(next)) {
                        base.append(' ').append(next.toUpperCase(Locale.ROOT));
                    } else {
                        pos = save;
                        break;
                    }
                }
                skipSpaces();
                if (peek() == '(') {
                    skipBalanced();
                }
                type = DuckType.scalar(base.toString());
            }
            skipSpaces();
            while (peek() == '[') {
                while (pos < s.length() && s.charAt(pos) != ']') {
                    pos++;
                }
                pos++;
                type = new DuckType("LIST", null, type);
                skipSpaces();
            }
            return type;
        }

        private static boolean isTypeContinuation(String word) {
            String upper = word.toUpperCase(Locale.ROOT);
            return upper.equals("WITH") || upper.equals("TIME") || upper.equals("ZONE") || upper.equals("PRECISION");
        }

        private String readName() {
            skipSpaces();
            if (peek() == '"') {
                pos++;
                StringBuilder sb = new StringBuilder();
                while (pos < s.length()) {
                    char c = s.charAt(pos++);
                    if (c == '"') {
                        if (peek() == '"') {
                            sb.append('"');
                            pos++;
                            continue;
                        }
                        break;
                    }
                    sb.append(c);
                }
                return sb.toString();
            }
            return readWord();
        }

        private String readWord() {
            int start = pos;
            while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) {
                pos++;
            }
            return s.substring(start, pos);
        }

        private void skipBalanced() {
            int depth = 0;
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '\'') {
                    while (pos < s.length() && s.charAt(pos++) != '\'') {
                        // skip quoted enum labels
                    }
                } else if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        return;
                    }
                }
            }
        }

        private void skipSpaces() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            return pos < s.length() ? s.charAt(pos) : '\0';
        }
    }

    // ── DuckDB → BigQuery schema ─────────────────────────────────────────────

    static TableFieldSchema toField(String name, DuckType type) {
        TableFieldSchema field = new TableFieldSchema();
        field.setName(name);
        if (type.isList()) {
            DuckType element = type.element();
            field.setMode("REPEATED");
            if (element.isList()) {
                // BigQuery has no ARRAY<ARRAY<...>>; nested arrays surface as JSON text.
                field.setType("STRING");
            } else if (element.isStruct()) {
                field.setType("RECORD");
                field.setFields(toFields(element.fields()));
            } else {
                field.setType(scalarBigQueryType(element.base()));
            }
            return field;
        }
        field.setMode("NULLABLE");
        if (type.isStruct()) {
            field.setType("RECORD");
            field.setFields(toFields(type.fields()));
        } else {
            field.setType(scalarBigQueryType(type.base()));
        }
        return field;
    }

    private static List<TableFieldSchema> toFields(List<DuckField> fields) {
        List<TableFieldSchema> result = new ArrayList<>(fields.size());
        for (DuckField f : fields) {
            result.add(toField(f.name(), f.type()));
        }
        return result;
    }

    static String scalarBigQueryType(String duckBase) {
        String base = duckBase.toUpperCase(Locale.ROOT);
        return switch (base) {
            case "TINYINT", "SMALLINT", "INTEGER", "BIGINT", "HUGEINT", "UTINYINT", "USMALLINT",
                 "UINTEGER", "UBIGINT", "UHUGEINT", "NULL", "\"NULL\"", "INT", "INT4", "INT8", "INT2" -> "INTEGER";
            case "FLOAT", "REAL", "DOUBLE", "DOUBLE PRECISION", "FLOAT4", "FLOAT8" -> "FLOAT";
            case "DECIMAL", "NUMERIC" -> "NUMERIC";
            case "BOOLEAN", "BOOL" -> "BOOLEAN";
            case "DATE" -> "DATE";
            case "TIME" -> "TIME";
            case "TIMESTAMP", "TIMESTAMP_S", "TIMESTAMP_MS", "TIMESTAMP_NS", "DATETIME" -> "DATETIME";
            case "TIMESTAMP WITH TIME ZONE", "TIMESTAMPTZ" -> "TIMESTAMP";
            case "BLOB", "BYTEA", "VARBINARY" -> "BYTES";
            case "JSON" -> "JSON";
            default -> "STRING";
        };
    }

    /** The projection a top-level result column of this type needs. */
    static Projection projection(DuckType type) {
        if (type.isList() || type.isStruct()) {
            return Projection.JSON;
        }
        String base = type.base();
        return switch (base) {
            case "TIMESTAMP WITH TIME ZONE", "TIMESTAMPTZ" -> Projection.EPOCH_MICROS;
            case "BLOB", "BYTEA", "VARBINARY" -> Projection.BASE64;
            case "BOOLEAN", "BOOL", "VARCHAR", "TINYINT", "SMALLINT", "INTEGER", "BIGINT", "UTINYINT",
                 "USMALLINT", "UINTEGER", "FLOAT", "REAL", "DOUBLE", "DOUBLE PRECISION" -> Projection.RAW;
            default -> base.startsWith("MAP") || base.startsWith("UNION") ? Projection.JSON : Projection.VARCHAR;
        };
    }

    /** SQL projecting column {@code column} so its value survives floci-duck's JSON encoding. */
    static String project(Projection projection, String column) {
        return switch (projection) {
            case RAW -> column;
            case VARCHAR -> "CAST(" + column + " AS VARCHAR)";
            case EPOCH_MICROS -> "CAST(epoch_us(" + column + ") AS VARCHAR)";
            case BASE64 -> "base64(" + column + ")";
            case JSON -> "CAST(to_json(" + column + ") AS VARCHAR)";
        };
    }

    // ── DuckDB values → stored rows ──────────────────────────────────────────

    /** Converts one projected result value into the stored representation for {@code field}. */
    static Object decode(Object value, Projection projection, DuckType type, TableFieldSchema field) {
        if (value == null) {
            return null;
        }
        return switch (projection) {
            case EPOCH_MICROS -> microsToSeconds(Long.parseLong(value.toString()));
            case BASE64 -> value.toString();
            case JSON -> decodeNested(readJson(value.toString()), type, field);
            case RAW, VARCHAR -> decodeScalar(value, field.getType());
        };
    }

    /**
     * Converts one value of floci-duck's {@code typed_values} encoding (exact decimal text,
     * ISO-8601 temporals, native JSON lists and objects, base64 blobs) into the stored
     * representation for {@code field}.
     */
    static Object decodeTyped(Object value, DuckType type, TableFieldSchema field) {
        return decodeNested(value, type, field);
    }

    private static Object readJson(String json) {
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    @SuppressWarnings("unchecked")
    private static Object decodeNested(Object value, DuckType type, TableFieldSchema field) {
        if (value == null) {
            return null;
        }
        if (type.isList()) {
            if (!(value instanceof List<?> list)) {
                return null;
            }
            List<Object> out = new ArrayList<>(list.size());
            for (Object element : list) {
                if (type.element().isList()) {
                    out.add(writeJson(element));
                } else {
                    TableFieldSchema elementField = copyWithMode(field, "NULLABLE");
                    out.add(decodeNested(element, type.element(), elementField));
                }
            }
            return out;
        }
        if (type.isStruct()) {
            if (!(value instanceof Map<?, ?> map)) {
                return null;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            List<TableFieldSchema> children = field.getFields() != null ? field.getFields() : List.of();
            for (int i = 0; i < type.fields().size(); i++) {
                DuckField child = type.fields().get(i);
                TableFieldSchema childField = i < children.size() ? children.get(i) : toField(child.name(), child.type());
                out.put(child.name(), decodeNested(((Map<String, Object>) map).get(child.name()),
                        child.type(), childField));
            }
            return out;
        }
        String base = type.base();
        if (base.startsWith("MAP") || base.startsWith("UNION")) {
            return writeJson(value);
        }
        return switch (field.getType()) {
            case "TIMESTAMP" -> value instanceof Number n
                    ? microsToSeconds(n.longValue())
                    : timestampTextToSeconds(value.toString());
            default -> decodeScalar(value, field.getType());
        };
    }

    private static TableFieldSchema copyWithMode(TableFieldSchema field, String mode) {
        TableFieldSchema copy = new TableFieldSchema();
        copy.setName(field.getName());
        copy.setType(field.getType());
        copy.setMode(mode);
        copy.setFields(field.getFields());
        return copy;
    }

    private static Object decodeScalar(Object value, String bigQueryType) {
        switch (bigQueryType) {
            case "INTEGER" -> {
                if (value instanceof Number n && !(value instanceof Double) && !(value instanceof Float)) {
                    return n.longValue();
                }
                String text = value.toString();
                try {
                    return Long.parseLong(text);
                } catch (NumberFormatException e) {
                    try {
                        return new BigDecimal(text).toBigIntegerExact().longValueExact();
                    } catch (ArithmeticException | NumberFormatException ignored) {
                        return text;
                    }
                }
            }
            case "FLOAT" -> {
                return value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString());
            }
            case "BOOLEAN" -> {
                return value instanceof Boolean b ? b : Boolean.parseBoolean(value.toString());
            }
            case "NUMERIC", "BIGNUMERIC" -> {
                return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
            }
            case "DATETIME" -> {
                return value.toString().replace(' ', 'T');
            }
            default -> {
                return value instanceof String s ? s : writeJson(value);
            }
        }
    }

    private static String writeJson(Object value) {
        if (value instanceof String s) {
            return s;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static final Pattern DUCK_TIMESTAMP = Pattern.compile(
            "(-?\\d{4,}-\\d{2}-\\d{2})[ T](\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?)\\s*(Z|[+-]\\d{2}(?::?\\d{2})?)?");

    /** Parses DuckDB's TIMESTAMPTZ text ({@code 2024-01-02 03:04:05.123+00}) into epoch seconds. */
    static String timestampTextToSeconds(String text) {
        Matcher m = DUCK_TIMESTAMP.matcher(text.trim());
        if (!m.matches()) {
            return text;
        }
        String offset = m.group(3) == null ? "Z" : m.group(3);
        if (!offset.equals("Z")) {
            String digits = offset.substring(1).replace(":", "");
            String hh = digits.substring(0, 2);
            String mm = digits.length() >= 4 ? digits.substring(2, 4) : "00";
            offset = offset.charAt(0) + hh + ":" + mm;
        }
        OffsetDateTime parsed = OffsetDateTime.parse(m.group(1) + "T" + m.group(2) + offset);
        java.time.Instant instant = parsed.withOffsetSameInstant(ZoneOffset.UTC).toInstant();
        long micros = Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L),
                instant.getNano() / 1_000);
        return microsToSeconds(micros);
    }

    /** Epoch microseconds → BigQuery's float-seconds text, e.g. {@code 1704164645.123456}. */
    static String microsToSeconds(long micros) {
        return BigDecimal.valueOf(micros, 6).stripTrailingZeros().toPlainString();
    }

    static String quoteIdentifier(String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    static String quoteLiteral(String value) {
        return '\'' + value.replace("'", "''") + '\'';
    }
}
