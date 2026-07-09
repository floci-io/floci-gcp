package io.floci.gcp.services.bigquery;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.bigquery.model.TableFieldSchema;
import io.floci.gcp.services.bigquery.model.TableSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-rolled parser/evaluator for the Phase 1 SQL subset:
 * <pre>SELECT *|col[, col...]|COUNT(*) FROM [project.]dataset.table
 *     [WHERE col = literal [AND ...]] [LIMIT n]</pre>
 * Anything else surfaces as {@code INVALID_ARGUMENT} with reason {@code invalidQuery} —
 * never silent divergence. A real GoogleSQL engine is Phase 2.
 */
final class QueryEngine {

    private static final String GRAMMAR_HINT =
            "The Phase 1 BigQuery emulator supports: SELECT *|columns|COUNT(*) FROM table "
                    + "[WHERE column = literal [AND ...]] [LIMIT n]";

    enum Kind { STAR, COUNT_STAR, COLUMNS }

    record TableRef(String projectId, String datasetId, String tableId) {}

    record Condition(String column, Object literal) {}

    record ParsedQuery(Kind kind, List<String> columns, TableRef table,
                       List<Condition> conditions, Integer limit) {}

    record Result(TableSchema schema, List<Map<String, Object>> rows) {}

    private QueryEngine() {}

    // ── Parsing ──────────────────────────────────────────────────────────────

    static ParsedQuery parse(String sql) {
        if (sql == null || sql.isBlank()) {
            throw invalidQuery("No query supplied");
        }
        Tokenizer tokens = new Tokenizer(sql);

        tokens.expectKeyword("SELECT");

        Kind kind;
        List<String> columns = new ArrayList<>();
        if (tokens.consumeSymbol('*')) {
            kind = Kind.STAR;
        } else if (tokens.consumeKeyword("COUNT")) {
            tokens.expectSymbol('(');
            tokens.expectSymbol('*');
            tokens.expectSymbol(')');
            kind = Kind.COUNT_STAR;
        } else {
            kind = Kind.COLUMNS;
            columns.add(tokens.expectIdentifier("column name"));
            while (tokens.consumeSymbol(',')) {
                columns.add(tokens.expectIdentifier("column name"));
            }
        }

        tokens.expectKeyword("FROM");
        TableRef table = parseTableRef(tokens);

        List<Condition> conditions = new ArrayList<>();
        if (tokens.consumeKeyword("WHERE")) {
            conditions.add(parseCondition(tokens));
            while (tokens.consumeKeyword("AND")) {
                conditions.add(parseCondition(tokens));
            }
        }

        Integer limit = null;
        if (tokens.consumeKeyword("LIMIT")) {
            limit = tokens.expectIntegerLiteral();
        }

        tokens.consumeSymbol(';');
        if (!tokens.atEnd()) {
            throw invalidQuery("Unsupported SQL near \"" + tokens.remainder() + "\". " + GRAMMAR_HINT);
        }
        return new ParsedQuery(kind, columns, table, conditions, limit);
    }

    private static TableRef parseTableRef(Tokenizer tokens) {
        List<String> parts = new ArrayList<>(tokens.expectTableRefPart());
        while (tokens.consumeSymbol('.')) {
            parts.addAll(tokens.expectTableRefPart());
        }
        return switch (parts.size()) {
            case 1 -> new TableRef(null, null, parts.get(0));
            case 2 -> new TableRef(null, parts.get(0), parts.get(1));
            case 3 -> new TableRef(parts.get(0), parts.get(1), parts.get(2));
            default -> throw invalidQuery("Invalid table reference: " + String.join(".", parts));
        };
    }

    private static Condition parseCondition(Tokenizer tokens) {
        String column = tokens.expectIdentifier("column name");
        tokens.expectSymbol('=');
        Object literal = tokens.expectLiteral();
        return new Condition(column, literal);
    }

    // ── Evaluation ───────────────────────────────────────────────────────────

    static Result evaluate(ParsedQuery query, TableSchema schema, List<Map<String, Object>> storedRows) {
        List<TableFieldSchema> fields = schema != null && schema.getFields() != null
                ? schema.getFields() : List.of();

        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> row : storedRows) {
            if (matches(row, query.conditions(), fields)) {
                filtered.add(row);
            }
        }

        if (query.kind() == Kind.COUNT_STAR) {
            TableFieldSchema countField = new TableFieldSchema();
            countField.setName("f0_");
            countField.setType("INTEGER");
            countField.setMode("NULLABLE");
            Map<String, Object> countRow = new LinkedHashMap<>();
            countRow.put("f0_", (long) filtered.size());
            List<Map<String, Object>> countRows = query.limit() != null && query.limit() < 1
                    ? List.of() : List.of(countRow);
            return new Result(new TableSchema(List.of(countField)), countRows);
        }

        if (query.limit() != null && filtered.size() > query.limit()) {
            filtered = filtered.subList(0, query.limit());
        }

        if (query.kind() == Kind.STAR) {
            return new Result(new TableSchema(fields), filtered);
        }

        List<TableFieldSchema> projected = new ArrayList<>();
        for (String column : query.columns()) {
            projected.add(resolveColumn(column, fields));
        }
        List<Map<String, Object>> projectedRows = new ArrayList<>(filtered.size());
        for (Map<String, Object> row : filtered) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (TableFieldSchema field : projected) {
                out.put(field.getName(), row.get(field.getName()));
            }
            projectedRows.add(out);
        }
        return new Result(new TableSchema(projected), projectedRows);
    }

    private static boolean matches(Map<String, Object> row, List<Condition> conditions,
                                   List<TableFieldSchema> fields) {
        for (Condition condition : conditions) {
            TableFieldSchema field = resolveColumn(condition.column(), fields);
            if ("REPEATED".equals(field.getMode()) || "RECORD".equals(field.getType())) {
                throw invalidQuery("Filtering on " + field.getMode() + "/" + field.getType()
                        + " column " + field.getName() + " is not supported. " + GRAMMAR_HINT);
            }
            Object stored = row.get(field.getName());
            if (stored == null || !compare(field, stored, condition.literal())) {
                return false;
            }
        }
        return true;
    }

    private static boolean compare(TableFieldSchema field, Object stored, Object literal) {
        switch (field.getType()) {
            case "INTEGER" -> {
                if (!(literal instanceof Long l)) {
                    throw typeMismatch(field, literal);
                }
                return stored instanceof Number n && n.longValue() == l;
            }
            case "FLOAT" -> {
                double target;
                if (literal instanceof Long l) {
                    target = l;
                } else if (literal instanceof Double d) {
                    target = d;
                } else {
                    throw typeMismatch(field, literal);
                }
                return stored instanceof Number n && n.doubleValue() == target;
            }
            case "BOOLEAN" -> {
                if (!(literal instanceof Boolean b)) {
                    throw typeMismatch(field, literal);
                }
                return stored instanceof Boolean s && s == b;
            }
            default -> {
                if (!(literal instanceof String s)) {
                    throw typeMismatch(field, literal);
                }
                return String.valueOf(stored).equals(s);
            }
        }
    }

    private static TableFieldSchema resolveColumn(String name, List<TableFieldSchema> fields) {
        return fields.stream()
                .filter(f -> f.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> invalidQuery("Unrecognized name: " + name));
    }

    private static GcpException typeMismatch(TableFieldSchema field, Object literal) {
        String literalType = switch (literal) {
            case Long ignored -> "INT64";
            case Double ignored -> "FLOAT64";
            case Boolean ignored -> "BOOL";
            default -> "STRING";
        };
        return invalidQuery("No matching signature for operator = for argument types: "
                + field.getType() + ", " + literalType);
    }

    static GcpException invalidQuery(String message) {
        return GcpException.invalidArgument(message).withReason("invalidQuery");
    }

    // ── Tokenizer ────────────────────────────────────────────────────────────

    private static final class Tokenizer {

        private final String sql;
        private int pos;

        Tokenizer(String sql) {
            this.sql = sql;
        }

        boolean atEnd() {
            skipWhitespace();
            return pos >= sql.length();
        }

        String remainder() {
            return sql.substring(pos).trim();
        }

        void expectKeyword(String keyword) {
            if (!consumeKeyword(keyword)) {
                throw invalidQuery("Expected " + keyword + " near \"" + remainder() + "\". " + GRAMMAR_HINT);
            }
        }

        boolean consumeKeyword(String keyword) {
            skipWhitespace();
            int end = pos + keyword.length();
            if (end > sql.length() || !sql.substring(pos, end).equalsIgnoreCase(keyword)) {
                return false;
            }
            if (end < sql.length() && isIdentifierChar(sql.charAt(end))) {
                return false;
            }
            pos = end;
            return true;
        }

        void expectSymbol(char symbol) {
            if (!consumeSymbol(symbol)) {
                throw invalidQuery("Expected '" + symbol + "' near \"" + remainder() + "\". " + GRAMMAR_HINT);
            }
        }

        boolean consumeSymbol(char symbol) {
            skipWhitespace();
            if (pos < sql.length() && sql.charAt(pos) == symbol) {
                pos++;
                return true;
            }
            return false;
        }

        String expectIdentifier(String what) {
            skipWhitespace();
            int start = pos;
            while (pos < sql.length() && isIdentifierChar(sql.charAt(pos))) {
                pos++;
            }
            if (pos == start) {
                throw invalidQuery("Expected " + what + " near \"" + remainder() + "\". " + GRAMMAR_HINT);
            }
            String identifier = sql.substring(start, pos);
            if (isReserved(identifier)) {
                throw invalidQuery("Unsupported SQL construct: " + identifier.toUpperCase() + ". " + GRAMMAR_HINT);
            }
            return identifier;
        }

        /** One table-ref part: a backtick-quoted path (may contain dots) or a plain identifier. */
        List<String> expectTableRefPart() {
            skipWhitespace();
            if (pos < sql.length() && sql.charAt(pos) == '`') {
                int close = sql.indexOf('`', pos + 1);
                if (close < 0) {
                    throw invalidQuery("Unterminated backtick identifier. " + GRAMMAR_HINT);
                }
                String quoted = sql.substring(pos + 1, close);
                pos = close + 1;
                return List.of(quoted.split("\\."));
            }
            return List.of(expectIdentifier("table name"));
        }

        Object expectLiteral() {
            skipWhitespace();
            if (pos >= sql.length()) {
                throw invalidQuery("Expected a literal value. " + GRAMMAR_HINT);
            }
            char c = sql.charAt(pos);
            if (c == '\'' || c == '"') {
                return stringLiteral(c);
            }
            if (c == '-' || Character.isDigit(c)) {
                return numberLiteral();
            }
            if (consumeKeyword("TRUE")) {
                return Boolean.TRUE;
            }
            if (consumeKeyword("FALSE")) {
                return Boolean.FALSE;
            }
            if (consumeKeyword("NULL")) {
                throw invalidQuery("Comparisons with NULL are not supported (use of '= NULL'). " + GRAMMAR_HINT);
            }
            throw invalidQuery("Expected a literal value near \"" + remainder() + "\". " + GRAMMAR_HINT);
        }

        int expectIntegerLiteral() {
            Object literal = expectLiteral();
            if (literal instanceof Long l && l >= 0 && l <= Integer.MAX_VALUE) {
                return l.intValue();
            }
            throw invalidQuery("LIMIT requires a non-negative integer literal. " + GRAMMAR_HINT);
        }

        private String stringLiteral(char quote) {
            StringBuilder value = new StringBuilder();
            pos++;
            while (pos < sql.length()) {
                char c = sql.charAt(pos);
                if (c == '\\' && pos + 1 < sql.length()) {
                    value.append(sql.charAt(pos + 1));
                    pos += 2;
                    continue;
                }
                if (c == quote) {
                    pos++;
                    return value.toString();
                }
                value.append(c);
                pos++;
            }
            throw invalidQuery("Unterminated string literal. " + GRAMMAR_HINT);
        }

        private Object numberLiteral() {
            int start = pos;
            if (sql.charAt(pos) == '-') {
                pos++;
            }
            boolean isFloat = false;
            while (pos < sql.length()) {
                char c = sql.charAt(pos);
                if (Character.isDigit(c)) {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E'
                        || ((c == '-' || c == '+') && pos > start
                            && (sql.charAt(pos - 1) == 'e' || sql.charAt(pos - 1) == 'E'))) {
                    isFloat = true;
                    pos++;
                } else {
                    break;
                }
            }
            String text = sql.substring(start, pos);
            try {
                return isFloat ? (Object) Double.parseDouble(text) : (Object) Long.parseLong(text);
            } catch (NumberFormatException e) {
                throw invalidQuery("Invalid numeric literal: " + text + ". " + GRAMMAR_HINT);
            }
        }

        private void skipWhitespace() {
            while (pos < sql.length() && Character.isWhitespace(sql.charAt(pos))) {
                pos++;
            }
        }

        private static boolean isIdentifierChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_';
        }

        private static boolean isReserved(String identifier) {
            return switch (identifier.toUpperCase()) {
                case "SELECT", "FROM", "WHERE", "AND", "OR", "JOIN", "GROUP", "ORDER", "BY",
                     "HAVING", "UNION", "INSERT", "UPDATE", "DELETE", "CREATE", "DROP",
                     "AS", "DISTINCT", "LIMIT" -> true;
                default -> false;
            };
        }
    }
}
