package io.floci.gcp.services.bigquery;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.bigquery.model.TableFieldSchema;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites a GoogleSQL {@code SELECT} into DuckDB SQL. Works on a token stream, never on raw
 * text, so string literals, comments and quoted identifiers are never touched by accident.
 *
 * <ul>
 *   <li>Table paths ({@code `p.d.t`}, {@code d.t}, bare names with a default dataset) become
 *       {@code "d"."t"} and are reported so the engine can stage them.</li>
 *   <li>Query parameters ({@code @name}, {@code ?}) are inlined as typed, escaped literals.</li>
 *   <li>GoogleSQL literals, casts and a documented set of functions are mapped to DuckDB.</li>
 *   <li>Unaliased expressions in the outer select list are named {@code f0_}, {@code f1_}, …
 *       as BigQuery names anonymous result columns.</li>
 * </ul>
 * Anything not recognised passes through unchanged, so DuckDB reports it as a query error.
 */
final class SqlDialectTranslator {

    /** A dataset-qualified table the query reads. */
    record TableRef(String datasetId, String tableId) {}

    record Translation(String sql, Set<TableRef> tables) {}

    private static final Set<String> CLAUSE_END_KEYWORDS = Set.of(
            "WHERE", "GROUP", "HAVING", "QUALIFY", "WINDOW", "ORDER", "LIMIT", "OFFSET",
            "UNION", "INTERSECT", "EXCEPT", "SELECT");

    private static final Set<String> NON_ALIAS_KEYWORDS = Set.of(
            "WHERE", "GROUP", "HAVING", "QUALIFY", "WINDOW", "ORDER", "LIMIT", "OFFSET", "UNION",
            "INTERSECT", "EXCEPT", "SELECT", "FROM", "JOIN", "INNER", "LEFT", "RIGHT", "FULL",
            "CROSS", "OUTER", "ON", "USING", "AS", "END", "AND", "OR", "NOT", "IS", "NULL", "IN",
            "LIKE", "BETWEEN", "CASE", "WHEN", "THEN", "ELSE", "TRUE", "FALSE", "DESC", "ASC",
            "WITH", "UNNEST", "TABLESAMPLE", "FOR", "ROWS", "RANGE", "OVER", "PARTITION", "BY",
            "NULLS", "FIRST", "LAST", "DISTINCT", "ALL", "INTERVAL", "LATERAL", "NATURAL");

    private static final Set<String> SHIMMED_FUNCTIONS = Set.of(
            "CAST", "SAFE_CAST", "EXTRACT", "STRUCT", "SAFE_DIVIDE", "IEEE_DIVIDE", "DIV", "IF", "COUNTIF",
            "LOGICAL_AND", "LOGICAL_OR", "ARRAY_LENGTH", "ARRAY_REVERSE", "GENERATE_ARRAY", "SPLIT", "FORMAT",
            "TO_JSON_STRING", "JSON_VALUE", "JSON_EXTRACT_SCALAR", "JSON_QUERY", "JSON_EXTRACT",
            "REGEXP_CONTAINS", "REGEXP_EXTRACT", "REGEXP_REPLACE", "CURRENT_TIMESTAMP", "CURRENT_DATE",
            "CURRENT_DATETIME", "UNIX_SECONDS", "UNIX_MILLIS", "UNIX_MICROS", "UNIX_DATE",
            "TIMESTAMP_SECONDS", "TIMESTAMP_MILLIS", "TIMESTAMP_MICROS", "TIMESTAMP_ADD", "DATETIME_ADD",
            "TIME_ADD", "TIMESTAMP_SUB", "DATETIME_SUB", "TIME_SUB", "DATE_ADD", "DATE_SUB",
            "TIMESTAMP_DIFF", "DATETIME_DIFF", "DATE_DIFF", "TIME_DIFF", "TIMESTAMP_TRUNC", "DATETIME_TRUNC",
            "DATE_TRUNC", "FORMAT_TIMESTAMP", "FORMAT_DATETIME", "FORMAT_DATE", "FORMAT_TIME",
            "PARSE_TIMESTAMP", "PARSE_DATETIME", "PARSE_DATE", "DATE", "DATETIME", "TIMESTAMP");

    /**
     * Words DuckDB reserves (or restricts) that GoogleSQL allows as plain column names; they
     * are quoted when used as bare identifiers. GoogleSQL's own reserved words, and words
     * GoogleSQL uses as syntax (OFFSET, PIVOT, type names in literals), are deliberately absent.
     */
    private static final Set<String> DUCKDB_ONLY_RESERVED = Set.of(
            "analyse", "analyze", "asymmetric", "both", "check", "column", "constraint", "deferrable",
            "describe", "do", "foreign", "initially", "lambda", "leading", "only", "pivot_longer",
            "pivot_wider", "placing", "primary", "references", "returning", "show", "summarize",
            "symmetric", "table", "trailing", "unique", "variadic", "anti", "asof", "authorization",
            "binary", "collation", "columns", "concurrently", "freeze", "generated", "glob", "ilike",
            "isnull", "map", "notnull", "overlaps", "positional", "semi", "similar", "unpack", "verbose",
            "bigint", "bit", "boolean", "char", "character", "dec", "float", "inout", "int", "integer",
            "national", "nchar", "none", "out", "precision", "real", "row", "setof", "smallint", "values",
            "varchar");

    private final String projectId;
    private final String defaultDatasetId;
    private final QueryParameters parameters;
    private final Set<String> cteNames = new HashSet<>();
    private final Set<TableRef> tables = new LinkedHashSet<>();

    private List<Token> tokens;

    private SqlDialectTranslator(String projectId, String defaultDatasetId, QueryParameters parameters) {
        this.projectId = projectId;
        this.defaultDatasetId = defaultDatasetId;
        this.parameters = parameters;
    }

    static Translation translate(String sql, String projectId, String defaultDatasetId,
                                 QueryParameters parameters) {
        SqlDialectTranslator translator = new SqlDialectTranslator(projectId, defaultDatasetId, parameters);
        return translator.run(sql);
    }

    // ── Statements (DML / DDL) ──────────────────────────────────────────────

    enum StatementKind {
        QUERY, INSERT, UPDATE, DELETE, MERGE, TRUNCATE, CREATE_TABLE, CREATE_VIEW, DROP_TABLE, DROP_VIEW,
        CREATE_SCHEMA, DROP_SCHEMA
    }

    /**
     * A classified statement. {@code querySql} is the GoogleSQL text of the {@code SELECT} part
     * of {@code CREATE TABLE ... AS} and {@code CREATE VIEW}; {@code columns} is the column list
     * of a plain {@code CREATE TABLE}, already in BigQuery schema form.
     */
    record Statement(StatementKind kind, String statementType, TableRef target, String datasetTarget,
                     boolean orReplace, boolean ifNotExists, boolean ifExists, boolean cascade,
                     boolean materialized, String querySql, List<TableFieldSchema> columns) {

        boolean isDml() {
            return kind == StatementKind.INSERT || kind == StatementKind.UPDATE || kind == StatementKind.DELETE
                    || kind == StatementKind.MERGE;
        }
    }

    /** Classifies a statement and resolves its target, without translating it to DuckDB. */
    static Statement parseStatement(String sql, String projectId, String defaultDatasetId) {
        SqlDialectTranslator translator = new SqlDialectTranslator(projectId, defaultDatasetId,
                QueryParameters.none());
        return translator.classify(sql);
    }

    /**
     * Translates {@code INSERT}, {@code UPDATE}, {@code DELETE} or {@code MERGE} to DuckDB. The
     * target table is part of {@link Translation#tables()}; {@code MERGE} gains
     * {@code RETURNING merge_action} so the engine can split its counts.
     */
    static Translation translateDml(String sql, String projectId, String defaultDatasetId,
                                    QueryParameters parameters) {
        SqlDialectTranslator translator = new SqlDialectTranslator(projectId, defaultDatasetId, parameters);
        return translator.runDml(sql);
    }

    private void prepare(String sql) {
        if (sql == null || sql.isBlank()) {
            throw invalidQuery("Syntax error: Unexpected end of script");
        }
        tokens = new Lexer(sql).tokenize();
        stripTrailingSemicolons();
        rejectScripts();
    }

    private Statement classify(String sql) {
        prepare(sql);
        List<Integer> sig = significantIndexes();
        if (sig.isEmpty()) {
            throw invalidQuery("Syntax error: Unexpected end of script");
        }
        String first = tokens.get(sig.getFirst()).upper();
        if (first.equals("SELECT") || first.equals("WITH") || tokens.get(sig.getFirst()).isPunct("(")) {
            return statement(StatementKind.QUERY, "SELECT", null);
        }
        return switch (first) {
            case "INSERT", "UPDATE", "DELETE", "MERGE" -> {
                int at = 1;
                if (at < sig.size() && ((first.equals("INSERT") || first.equals("MERGE")) && keywordAt(sig, at, "INTO")
                        || first.equals("DELETE") && keywordAt(sig, at, "FROM"))) {
                    at++;
                }
                TableRef target = resolveTable(pathAt(sig, at).segments());
                if ((first.equals("UPDATE") || first.equals("DELETE")) && !hasTopLevelKeyword("WHERE")) {
                    throw invalidQuery(first + " must have a WHERE clause");
                }
                yield statement(StatementKind.valueOf(first), first, target);
            }
            case "TRUNCATE" -> {
                expectKeyword(sig, 1, "TABLE");
                yield statement(StatementKind.TRUNCATE, "TRUNCATE_TABLE", resolveTable(pathAt(sig, 2).segments()));
            }
            case "CREATE" -> classifyCreate(sig);
            case "DROP" -> classifyDrop(sig);
            default -> throw invalidQuery("Statement type " + first + " is not supported by the floci BigQuery"
                    + " emulator yet.");
        };
    }

    private Statement classifyCreate(List<Integer> sig) {
        int at = 1;
        boolean orReplace = false;
        if (keywordAt(sig, at, "OR")) {
            expectKeyword(sig, at + 1, "REPLACE");
            orReplace = true;
            at += 2;
        }
        if (keywordAt(sig, at, "TEMP") || keywordAt(sig, at, "TEMPORARY")) {
            throw invalidQuery("Temporary tables need a multi-statement script, which the floci BigQuery"
                    + " emulator does not support.");
        }
        boolean materialized = false;
        if (keywordAt(sig, at, "MATERIALIZED")) {
            materialized = true;
            at++;
        }
        String object = at < sig.size() ? tokens.get(sig.get(at)).upper() : "";
        at++;
        boolean ifNotExists = false;
        if (keywordAt(sig, at, "IF")) {
            expectKeyword(sig, at + 1, "NOT");
            expectKeyword(sig, at + 2, "EXISTS");
            ifNotExists = true;
            at += 3;
        }
        if (orReplace && ifNotExists) {
            throw invalidQuery("CREATE statements cannot combine OR REPLACE and IF NOT EXISTS");
        }
        Path path = pathAt(sig, at);
        switch (object) {
            case "SCHEMA" -> {
                return new Statement(StatementKind.CREATE_SCHEMA, "CREATE_SCHEMA", null, datasetOf(path.segments()),
                        orReplace, ifNotExists, false, false, false, null, null);
            }
            case "TABLE" -> {
                TableRef target = resolveTable(path.segments());
                int asIndex = topLevelAsSelect(path.end());
                if (asIndex >= 0) {
                    return new Statement(StatementKind.CREATE_TABLE, "CREATE_TABLE_AS_SELECT", target, null,
                            orReplace, ifNotExists, false, false, false, text(asIndex + 1, tokens.size()), null);
                }
                int open = nextSignificant(path.end(), tokens.size());
                if (open < 0 || !tokens.get(open).isPunct("(")) {
                    throw invalidQuery("CREATE TABLE needs a column list or AS SELECT"
                            + " (LIKE and COPY are not supported by the floci BigQuery emulator)");
                }
                List<TableFieldSchema> columns = columnDefinitions(open, matchingParen(open));
                return new Statement(StatementKind.CREATE_TABLE, "CREATE_TABLE", target, null, orReplace,
                        ifNotExists, false, false, false, null, columns);
            }
            case "VIEW" -> {
                TableRef target = resolveTable(path.segments());
                int asIndex = topLevelAsSelect(path.end());
                if (asIndex < 0) {
                    throw invalidQuery("CREATE VIEW needs AS followed by a query");
                }
                return new Statement(StatementKind.CREATE_VIEW,
                        materialized ? "CREATE_MATERIALIZED_VIEW" : "CREATE_VIEW", target, null, orReplace,
                        ifNotExists, false, false, materialized, text(asIndex + 1, tokens.size()).trim(), null);
            }
            default -> throw invalidQuery("CREATE " + object + " is not supported by the floci BigQuery emulator yet.");
        }
    }

    private Statement classifyDrop(List<Integer> sig) {
        int at = 1;
        boolean materialized = false;
        if (keywordAt(sig, at, "MATERIALIZED")) {
            materialized = true;
            at++;
        }
        String object = at < sig.size() ? tokens.get(sig.get(at)).upper() : "";
        at++;
        boolean ifExists = false;
        if (keywordAt(sig, at, "IF")) {
            expectKeyword(sig, at + 1, "EXISTS");
            ifExists = true;
            at += 2;
        }
        Path path = pathAt(sig, at);
        int after = nextSignificant(path.end(), tokens.size());
        boolean cascade = after >= 0 && tokens.get(after).isKeyword("CASCADE");
        return switch (object) {
            case "SCHEMA" -> new Statement(StatementKind.DROP_SCHEMA, "DROP_SCHEMA", null, datasetOf(path.segments()),
                    false, false, ifExists, cascade, false, null, null);
            case "TABLE" -> new Statement(StatementKind.DROP_TABLE, "DROP_TABLE", resolveTable(path.segments()), null,
                    false, false, ifExists, false, false, null, null);
            case "VIEW" -> new Statement(StatementKind.DROP_VIEW,
                    materialized ? "DROP_MATERIALIZED_VIEW" : "DROP_VIEW", resolveTable(path.segments()), null,
                    false, false, ifExists, false, materialized, null, null);
            default -> throw invalidQuery("DROP " + object + " is not supported by the floci BigQuery emulator yet.");
        };
    }

    private Statement statement(StatementKind kind, String statementType, TableRef target) {
        return new Statement(kind, statementType, target, null, false, false, false, false, false, null, null);
    }

    private String datasetOf(List<String> segments) {
        if (segments.size() == 2) {
            if (!segments.get(0).equals(projectId)) {
                throw invalidQuery("Cross-project statements are not supported by the floci BigQuery emulator: "
                        + String.join(".", segments));
            }
            return segments.get(1);
        }
        if (segments.size() != 1) {
            throw invalidQuery("Invalid dataset name " + String.join(".", segments));
        }
        return segments.getFirst();
    }

    private Translation runDml(String sql) {
        prepare(sql);
        List<Integer> sig = significantIndexes();
        String first = tokens.get(sig.getFirst()).upper();
        int at = 1;
        if ((first.equals("INSERT") || first.equals("MERGE")) && keywordAt(sig, at, "INTO")
                || first.equals("DELETE") && keywordAt(sig, at, "FROM")) {
            at++;
        }
        Path path = pathAt(sig, at);
        TableRef target = resolveTable(path.segments());
        tables.add(target);
        String targetSql = DuckTypes.quoteIdentifier(target.datasetId()) + "."
                + DuckTypes.quoteIdentifier(target.tableId());
        String head = switch (first) {
            case "INSERT" -> "INSERT INTO ";
            case "DELETE" -> "DELETE FROM ";
            case "MERGE" -> "MERGE INTO ";
            default -> "UPDATE ";
        };
        StringBuilder out = new StringBuilder(head).append(targetSql);
        int rest = path.end();
        int next = nextSignificant(rest, tokens.size());
        // UPDATE/DELETE/MERGE target alias: "t", "AS t"
        if (next >= 0 && !first.equals("INSERT")) {
            Token n = tokens.get(next);
            int aliasIndex = -1;
            if (n.isKeyword("AS")) {
                aliasIndex = nextSignificant(next + 1, tokens.size());
            } else if ((n.kind == Kind.IDENT && !NON_ALIAS_KEYWORDS.contains(n.upper()) && !n.isKeyword("SET")
                    && !n.isKeyword("USING")) || n.kind == Kind.QIDENT) {
                aliasIndex = next;
            }
            if (aliasIndex >= 0) {
                out.append(" AS ").append(DuckTypes.quoteIdentifier(tokens.get(aliasIndex).identifierText()));
                rest = aliasIndex + 1;
            }
        }
        out.append(render(rest, tokens.size()));
        if (first.equals("MERGE")) {
            out.append(" RETURNING merge_action");
        }
        return new Translation(out.toString().trim(), tables);
    }

    // ── Statement parsing helpers ───────────────────────────────────────────

    private record Path(List<String> segments, int end) {}

    private List<Integer> significantIndexes() {
        List<Integer> sig = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).kind != Kind.SPACE) {
                sig.add(i);
            }
        }
        return sig;
    }

    private boolean keywordAt(List<Integer> sig, int at, String keyword) {
        return at < sig.size() && tokens.get(sig.get(at)).isKeyword(keyword);
    }

    private void expectKeyword(List<Integer> sig, int at, String keyword) {
        if (!keywordAt(sig, at, keyword)) {
            throw invalidQuery("Syntax error: Expected keyword " + keyword);
        }
    }

    /** Reads a (possibly backtick-quoted, dotted) name starting at significant token {@code at}. */
    private Path pathAt(List<Integer> sig, int at) {
        if (at >= sig.size()) {
            throw invalidQuery("Syntax error: Unexpected end of statement");
        }
        List<String> segments = new ArrayList<>();
        int k = sig.get(at);
        while (true) {
            Token t = tokens.get(k);
            if (t.kind == Kind.QIDENT) {
                segments.addAll(List.of(t.value.split("\\.", -1)));
            } else if (t.kind == Kind.IDENT) {
                segments.add(t.text);
            } else {
                throw invalidQuery("Syntax error: Expected a name but got \"" + t.text + "\"");
            }
            int dot = nextSignificant(k + 1, tokens.size());
            if (dot >= 0 && tokens.get(dot).isPunct(".")) {
                int name = nextSignificant(dot + 1, tokens.size());
                if (name >= 0 && (tokens.get(name).kind == Kind.IDENT || tokens.get(name).kind == Kind.QIDENT)) {
                    k = name;
                    continue;
                }
            }
            return new Path(segments, k + 1);
        }
    }

    private boolean hasTopLevelKeyword(String keyword) {
        int depth = 0;
        for (Token t : tokens) {
            if (t.isPunct("(")) {
                depth++;
            } else if (t.isPunct(")")) {
                depth--;
            } else if (depth == 0 && t.isKeyword(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** Index of a top-level {@code AS} that is followed by a query, searching from {@code from}. */
    private int topLevelAsSelect(int from) {
        int depth = 0;
        for (int k = from; k < tokens.size(); k++) {
            Token t = tokens.get(k);
            if (t.isPunct("(")) {
                depth++;
            } else if (t.isPunct(")")) {
                depth--;
            } else if (depth == 0 && t.isKeyword("AS")) {
                int next = nextSignificant(k + 1, tokens.size());
                if (next >= 0 && (tokens.get(next).isKeyword("SELECT") || tokens.get(next).isKeyword("WITH")
                        || tokens.get(next).isPunct("("))) {
                    return k;
                }
            }
        }
        return -1;
    }

    private String text(int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int k = from; k < to; k++) {
            sb.append(tokens.get(k).text);
        }
        return sb.toString();
    }

    /** {@code (name TYPE [NOT NULL] [OPTIONS(description = '...')], ...)} → BigQuery fields. */
    private List<TableFieldSchema> columnDefinitions(int open, int close) {
        List<TableFieldSchema> fields = new ArrayList<>();
        for (List<Token> item : columnItems(open, close)) {
            if (item.isEmpty()) {
                continue;
            }
            if (item.getFirst().isKeyword("PRIMARY") || item.getFirst().isKeyword("FOREIGN")
                    || item.getFirst().isKeyword("CONSTRAINT")) {
                continue; // unenforced table constraints carry no data
            }
            int[] pos = {1};
            TableFieldSchema field = columnType(item, pos);
            field.setName(item.getFirst().identifierText());
            while (pos[0] < item.size()) {
                Token t = item.get(pos[0]);
                if (t.isKeyword("NOT") && pos[0] + 1 < item.size() && item.get(pos[0] + 1).isKeyword("NULL")) {
                    field.setMode("REQUIRED");
                    pos[0] += 2;
                } else if (t.isKeyword("OPTIONS")) {
                    pos[0]++;
                    String description = optionValue(item, pos, "description");
                    if (description != null) {
                        field.setDescription(description);
                    }
                } else {
                    pos[0]++;
                }
            }
            fields.add(field);
        }
        return fields;
    }

    /**
     * Splits a column list on top-level commas. Commas inside parentheses and inside
     * {@code ARRAY<...>} / {@code STRUCT<...>} brackets do not split; {@code >>} is split into
     * two closing brackets.
     */
    private List<List<Token>> columnItems(int open, int close) {
        List<List<Token>> items = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        int parens = 0;
        int angles = 0;
        Token previous = null;
        for (Token t : significant(open + 1, close)) {
            if (t.isPunct("(")) {
                parens++;
            } else if (t.isPunct(")")) {
                parens--;
            } else if (t.isPunct("<") && previous != null
                    && (previous.isKeyword("ARRAY") || previous.isKeyword("STRUCT"))) {
                angles++;
            } else if (t.isPunct(">>") && angles > 0) {
                angles -= 2;
                current.add(new Token(Kind.PUNCT, ">", null));
                current.add(new Token(Kind.PUNCT, ">", null));
                previous = t;
                continue;
            } else if (t.isPunct(">") && angles > 0) {
                angles--;
            } else if (t.isPunct(",") && parens == 0 && angles == 0) {
                items.add(current);
                current = new ArrayList<>();
                previous = t;
                continue;
            }
            current.add(t);
            previous = t;
        }
        if (!current.isEmpty()) {
            items.add(current);
        }
        return items;
    }

    /** A GoogleSQL column type ({@code INT64}, {@code ARRAY<...>}, {@code STRUCT<...>}) as a field. */
    private static TableFieldSchema columnType(List<Token> item, int[] pos) {
        TableFieldSchema field = new TableFieldSchema();
        field.setMode("NULLABLE");
        if (pos[0] >= item.size()) {
            throw invalidQuery("Column " + item.getFirst().text + " has no type");
        }
        String type = item.get(pos[0]++).upper();
        if (type.equals("ARRAY")) {
            expectPunct(item, pos, "<");
            TableFieldSchema element = columnType(item, pos);
            expectPunct(item, pos, ">");
            element.setMode("REPEATED");
            return element;
        }
        if (type.equals("STRUCT")) {
            expectPunct(item, pos, "<");
            List<TableFieldSchema> children = new ArrayList<>();
            while (pos[0] < item.size() && !item.get(pos[0]).isPunct(">")) {
                if (!children.isEmpty()) {
                    expectPunct(item, pos, ",");
                }
                String name = item.get(pos[0]++).identifierText();
                TableFieldSchema child = columnType(item, pos);
                child.setName(name);
                if (pos[0] + 1 < item.size() && item.get(pos[0]).isKeyword("NOT")
                        && item.get(pos[0] + 1).isKeyword("NULL")) {
                    child.setMode("REQUIRED");
                    pos[0] += 2;
                }
                children.add(child);
            }
            expectPunct(item, pos, ">");
            field.setType("RECORD");
            field.setFields(children);
            return field;
        }
        field.setType(RowCodec.legacyType(switch (type) {
            case "INT", "SMALLINT", "INTEGER", "BIGINT", "TINYINT", "BYTEINT" -> "INT64";
            case "DECIMAL" -> "NUMERIC";
            case "BIGDECIMAL" -> "BIGNUMERIC";
            default -> type;
        }));
        if (pos[0] < item.size() && item.get(pos[0]).isPunct("(")) {
            int depth = 0;
            while (pos[0] < item.size()) {
                Token t = item.get(pos[0]++);
                if (t.isPunct("(")) {
                    depth++;
                } else if (t.isPunct(")") && --depth == 0) {
                    break;
                }
            }
        }
        return field;
    }

    private static void expectPunct(List<Token> item, int[] pos, String punct) {
        if (pos[0] < item.size() && item.get(pos[0]).isPunct(punct)) {
            pos[0]++;
            return;
        }
        throw invalidQuery("Syntax error in column definition: expected \"" + punct + "\"");
    }

    /** Reads {@code (key = 'value', ...)} after OPTIONS and returns {@code key}'s string value. */
    private static String optionValue(List<Token> item, int[] pos, String key) {
        if (pos[0] >= item.size() || !item.get(pos[0]).isPunct("(")) {
            return null;
        }
        String value = null;
        int depth = 0;
        for (; pos[0] < item.size(); pos[0]++) {
            Token t = item.get(pos[0]);
            if (t.isPunct("(")) {
                depth++;
            } else if (t.isPunct(")")) {
                if (--depth == 0) {
                    pos[0]++;
                    break;
                }
            } else if (depth == 1 && t.isKeyword(key) && pos[0] + 2 < item.size()
                    && item.get(pos[0] + 1).isPunct("=") && item.get(pos[0] + 2).kind == Kind.STRING) {
                value = item.get(pos[0] + 2).value;
            }
        }
        return value;
    }

    /** The statement type BigQuery would report, derived from the first keyword. */
    static String statementType(String sql) {
        for (Token t : new Lexer(sql).tokenize()) {
            if (t.kind == Kind.SPACE || t.isPunct("(")) {
                continue;
            }
            if (t.kind == Kind.IDENT) {
                String upper = t.upper();
                return upper.equals("WITH") ? "SELECT" : upper;
            }
            return "UNKNOWN";
        }
        return "UNKNOWN";
    }

    private Translation run(String sql) {
        prepare(sql);
        String statement = statementType(sql);
        if (!statement.equals("SELECT")) {
            throw invalidQuery("Statement type " + statement + " is not supported by the floci BigQuery"
                    + " emulator yet; only SELECT queries run on the SQL engine.");
        }
        collectCteNames();
        nameAnonymousColumns();
        String rendered = render(0, tokens.size()).trim();
        return new Translation(rendered, tables);
    }

    private void stripTrailingSemicolons() {
        while (!tokens.isEmpty() && (tokens.getLast().isPunct(";") || tokens.getLast().kind == Kind.SPACE)) {
            tokens.removeLast();
        }
    }

    private void rejectScripts() {
        for (Token t : tokens) {
            if (t.isPunct(";")) {
                throw invalidQuery("Multi-statement scripts are not supported by the floci BigQuery emulator.");
            }
        }
    }

    // ── CTEs and anonymous column names ─────────────────────────────────────

    private void collectCteNames() {
        int n = tokens.size();
        int i = nextSignificant(0, n);
        if (i < 0 || !tokens.get(i).isKeyword("WITH")) {
            return;
        }
        i = nextSignificant(i + 1, n);
        if (i >= 0 && tokens.get(i).isKeyword("RECURSIVE")) {
            i = nextSignificant(i + 1, n);
        }
        while (i >= 0) {
            Token name = tokens.get(i);
            if (name.kind != Kind.IDENT && name.kind != Kind.QIDENT) {
                return;
            }
            cteNames.add(name.identifierText().toLowerCase(Locale.ROOT));
            i = nextSignificant(i + 1, n);
            if (i < 0 || !tokens.get(i).isKeyword("AS")) {
                return;
            }
            i = nextSignificant(i + 1, n);
            if (i < 0 || !tokens.get(i).isPunct("(")) {
                return;
            }
            i = nextSignificant(matchingParen(i) + 1, n);
            if (i < 0 || !tokens.get(i).isPunct(",")) {
                return;
            }
            i = nextSignificant(i + 1, n);
        }
    }

    /**
     * BigQuery names unaliased, non-column expressions in the outermost select list
     * {@code f0_}, {@code f1_}, … ; DuckDB would name them after the expression text.
     */
    private void nameAnonymousColumns() {
        int select = firstTopLevelSelect();
        if (select < 0) {
            return;
        }
        int start = select + 1;
        int first = nextSignificant(start, tokens.size());
        while (first >= 0 && (tokens.get(first).isKeyword("DISTINCT") || tokens.get(first).isKeyword("ALL"))) {
            start = first + 1;
            first = nextSignificant(start, tokens.size());
        }
        if (first >= 0 && tokens.get(first).isKeyword("AS")) {
            return; // SELECT AS STRUCT / AS VALUE
        }
        int end = start;
        int depth = 0;
        List<int[]> items = new ArrayList<>();
        int itemStart = start;
        for (; end < tokens.size(); end++) {
            Token t = tokens.get(end);
            if (t.isPunct("(") || t.isPunct("[")) {
                depth++;
            } else if (t.isPunct(")") || t.isPunct("]")) {
                depth--;
            } else if (depth == 0 && t.isPunct(",")) {
                items.add(new int[] {itemStart, end});
                itemStart = end + 1;
            } else if (depth == 0 && t.kind == Kind.IDENT
                    && (t.isKeyword("FROM") || CLAUSE_END_KEYWORDS.contains(t.upper()))) {
                break;
            }
        }
        items.add(new int[] {itemStart, end});

        List<Integer> insertAt = new ArrayList<>();
        for (int[] item : items) {
            if (needsGeneratedName(item[0], item[1])) {
                int last = item[1] - 1;
                while (tokens.get(last).kind == Kind.SPACE) {
                    last--;
                }
                insertAt.add(last + 1);
            }
        }
        // Insert from the back so earlier indexes stay valid.
        for (int k = insertAt.size() - 1; k >= 0; k--) {
            tokens.add(insertAt.get(k), Token.raw(" AS f" + k + "_"));
        }
    }

    private int firstTopLevelSelect() {
        int depth = 0;
        int firstToken = nextSignificant(0, tokens.size());
        boolean afterWith = firstToken >= 0 && tokens.get(firstToken).isKeyword("WITH");
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.isPunct("(")) {
                depth++;
            } else if (t.isPunct(")")) {
                depth--;
            } else if (t.isKeyword("SELECT") && depth == 0) {
                return i;
            }
        }
        // (SELECT ...) UNION ... : name the first nested select.
        if (!afterWith) {
            for (int i = 0; i < tokens.size(); i++) {
                if (tokens.get(i).isKeyword("SELECT")) {
                    return i;
                }
            }
        }
        return -1;
    }

    private boolean needsGeneratedName(int start, int end) {
        List<Token> item = significant(start, end);
        if (item.isEmpty() || item.getLast().isPunct("*") || isPath(item)) {
            return false; // *, t.*, or a column/field reference that keeps its own name
        }
        for (int k = 0; k + 1 < item.size(); k++) {
            if (item.get(k).isPunct("*") && (item.get(k + 1).isKeyword("EXCEPT")
                    || item.get(k + 1).isKeyword("REPLACE"))) {
                return false;
            }
        }
        if (item.size() >= 2) {
            Token last = item.getLast();
            Token previous = item.get(item.size() - 2);
            boolean lastIsName = last.kind == Kind.QIDENT
                    || (last.kind == Kind.IDENT && !NON_ALIAS_KEYWORDS.contains(last.upper()));
            if (lastIsName && (previous.isKeyword("AS") || isAliasable(previous))) {
                return false; // explicit or implicit alias
            }
        }
        return true;
    }

    private static boolean isAliasable(Token beforeLast) {
        return beforeLast.kind == Kind.IDENT || beforeLast.kind == Kind.QIDENT || beforeLast.isPunct(")")
                || beforeLast.isPunct("]") || beforeLast.kind == Kind.STRING || beforeLast.kind == Kind.NUMBER;
    }

    private static boolean isPath(List<Token> item) {
        for (int i = 0; i < item.size(); i++) {
            Token t = item.get(i);
            boolean nameSlot = i % 2 == 0;
            if (nameSlot && !(t.kind == Kind.QIDENT
                    || (t.kind == Kind.IDENT && !NON_ALIAS_KEYWORDS.contains(t.upper())))) {
                return false;
            }
            if (!nameSlot && !t.isPunct(".")) {
                return false;
            }
        }
        return item.size() % 2 == 1;
    }

    private List<Token> significant(int start, int end) {
        List<Token> out = new ArrayList<>();
        for (int i = start; i < end; i++) {
            if (tokens.get(i).kind != Kind.SPACE) {
                out.add(tokens.get(i));
            }
        }
        return out;
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    private String render(int start, int end) {
        StringBuilder out = new StringBuilder();
        boolean inFrom = false;
        boolean expectTable = false;
        Set<String> rangeVariables = new HashSet<>();

        int i = start;
        while (i < end) {
            Token t = tokens.get(i);
            switch (t.kind) {
                case SPACE, RAW -> {
                    out.append(t.text);
                    i++;
                    continue;
                }
                case STRING -> {
                    out.append(DuckTypes.quoteLiteral(t.value));
                    i++;
                    continue;
                }
                case BYTES -> {
                    out.append("CAST(").append(DuckTypes.quoteLiteral(t.value)).append(" AS BLOB)");
                    i++;
                    continue;
                }
                case NAMED_PARAM -> {
                    out.append(parameters.named(t.value));
                    i++;
                    continue;
                }
                case POSITIONAL_PARAM -> {
                    out.append(parameters.nextPositional());
                    i++;
                    continue;
                }
                default -> {
                    // handled below
                }
            }

            if (t.kind == Kind.PUNCT) {
                if (t.text.equals("(")) {
                    int close = matchingParen(i);
                    out.append('(').append(render(i + 1, close)).append(')');
                    i = close + 1;
                    expectTable = false;
                    continue;
                }
                if (t.text.equals("*")) {
                    out.append('*');
                    int next = nextSignificant(i + 1, end);
                    if (next >= 0 && tokens.get(next).isKeyword("EXCEPT")
                            && nextSignificantIs(next + 1, end, "(")) {
                        out.append(" EXCLUDE");
                        i = next + 1;
                        continue;
                    }
                    i++;
                    continue;
                }
                if (t.text.equals(",") && inFrom) {
                    expectTable = true;
                }
                out.append(t.text);
                i++;
                continue;
            }

            if (t.kind == Kind.NUMBER) {
                out.append(t.text);
                i++;
                continue;
            }

            String upper = t.kind == Kind.IDENT ? t.upper() : "";

            if (expectTable && (t.kind == Kind.IDENT || t.kind == Kind.QIDENT) && !upper.equals("UNNEST")
                    && !upper.equals("LATERAL")) {
                i = renderTablePath(i, end, out, rangeVariables);
                expectTable = false;
                continue;
            }
            if (expectTable && upper.equals("UNNEST")) {
                i = renderUnnest(i, end, out);
                expectTable = false;
                continue;
            }

            if (t.kind == Kind.QIDENT) {
                out.append(quotePath(t.value));
                i++;
                continue;
            }

            // IDENT
            if (upper.equals("FROM")) {
                inFrom = true;
                expectTable = true;
            } else if (upper.equals("JOIN")) {
                expectTable = true;
            } else if (upper.equals("ON")) {
                expectTable = false;
            } else if (upper.equals("USING")) {
                // JOIN ... USING (columns) vs MERGE ... USING source_table
                int after = nextSignificant(i + 1, end);
                expectTable = after >= 0 && !tokens.get(after).isPunct("(");
            } else if (CLAUSE_END_KEYWORDS.contains(upper)) {
                inFrom = false;
                expectTable = false;
                if (upper.equals("UNION") || upper.equals("INTERSECT") || upper.equals("EXCEPT")) {
                    out.append(t.text);
                    int next = nextSignificant(i + 1, end);
                    if (next >= 0 && tokens.get(next).isKeyword("DISTINCT")) {
                        i = next + 1;
                    } else {
                        i++;
                    }
                    continue;
                }
            }

            int next = nextSignificant(i + 1, end);
            if (upper.equals("IN") && next >= 0 && tokens.get(next).isKeyword("UNNEST")) {
                // x IN UNNEST(array): DuckDB only accepts UNNEST in a select list.
                int open = nextSignificant(next + 1, end);
                if (open >= 0 && tokens.get(open).isPunct("(")) {
                    int close = matchingParen(open);
                    out.append(t.text).append(" (SELECT UNNEST(").append(render(open + 1, close)).append("))");
                    i = close + 1;
                    continue;
                }
            }
            if (next >= 0 && tokens.get(next).kind == Kind.STRING && isTypedLiteral(upper)) {
                out.append(typedLiteral(upper, tokens.get(next).value));
                i = next + 1;
                continue;
            }
            if (next >= 0 && tokens.get(next).isPunct("(") && SHIMMED_FUNCTIONS.contains(upper)
                    && !isPrecededByDot(i)) {
                int close = matchingParen(next);
                String call = renderCall(upper, t.text, next, close);
                if (call != null) {
                    out.append(call);
                    i = close + 1;
                    continue;
                }
            }
            if (upper.equals("CURRENT_TIMESTAMP") || upper.equals("CURRENT_DATE")) {
                out.append(t.text.toLowerCase(Locale.ROOT));
                i++;
                continue;
            }
            boolean call = next >= 0 && tokens.get(next).isPunct("(");
            if (!call && DUCKDB_ONLY_RESERVED.contains(t.text.toLowerCase(Locale.ROOT))) {
                out.append(DuckTypes.quoteIdentifier(t.text));
            } else {
                out.append(t.text);
            }
            i++;
        }
        return out.toString();
    }

    private boolean isPrecededByDot(int index) {
        for (int k = index - 1; k >= 0; k--) {
            Token t = tokens.get(k);
            if (t.kind == Kind.SPACE) {
                continue;
            }
            return t.isPunct(".");
        }
        return false;
    }

    /** Renders a table path in a FROM/JOIN position plus its optional alias. */
    private int renderTablePath(int i, int end, StringBuilder out, Set<String> rangeVariables) {
        List<String> segments = new ArrayList<>();
        int k = i;
        while (k < end) {
            Token t = tokens.get(k);
            if (t.kind == Kind.QIDENT) {
                for (String part : t.value.split("\\.", -1)) {
                    segments.add(part);
                }
            } else if (t.kind == Kind.IDENT) {
                segments.add(t.text);
            } else {
                break;
            }
            int next = nextSignificant(k + 1, end);
            if (next >= 0 && tokens.get(next).isPunct(".") && next + 1 < end) {
                int afterDot = nextSignificant(next + 1, end);
                if (afterDot >= 0 && (tokens.get(afterDot).kind == Kind.IDENT
                        || tokens.get(afterDot).kind == Kind.QIDENT)) {
                    k = afterDot;
                    continue;
                }
            }
            k++;
            break;
        }

        String alias = null;
        int aliasEnd = k;
        int next = nextSignificant(k, end);
        if (next >= 0) {
            Token n = tokens.get(next);
            if (n.isKeyword("AS")) {
                int name = nextSignificant(next + 1, end);
                if (name >= 0 && (tokens.get(name).kind == Kind.IDENT || tokens.get(name).kind == Kind.QIDENT)) {
                    alias = tokens.get(name).identifierText();
                    aliasEnd = name + 1;
                }
            } else if ((n.kind == Kind.IDENT && !NON_ALIAS_KEYWORDS.contains(n.upper())
                    && !n.isKeyword("WITH")) || n.kind == Kind.QIDENT) {
                alias = n.identifierText();
                aliasEnd = next + 1;
            }
        }

        String first = segments.getFirst();
        if (segments.size() >= 2 && rangeVariables.contains(first.toLowerCase(Locale.ROOT))) {
            // FROM t, t.arr AS x : an implicit UNNEST of an array column.
            String element = alias != null ? alias : segments.getLast();
            out.append("UNNEST(").append(quoteSegments(segments)).append(") AS ")
                    .append(DuckTypes.quoteIdentifier("_unnest_" + element)).append('(')
                    .append(DuckTypes.quoteIdentifier(element)).append(')');
            return aliasEnd;
        }

        TableRef ref = resolveTable(segments);
        if (ref == null) {
            out.append(DuckTypes.quoteIdentifier(first));
            rangeVariables.add(first.toLowerCase(Locale.ROOT));
        } else {
            tables.add(ref);
            out.append(DuckTypes.quoteIdentifier(ref.datasetId())).append('.')
                    .append(DuckTypes.quoteIdentifier(ref.tableId()));
            rangeVariables.add(ref.tableId().toLowerCase(Locale.ROOT));
        }
        if (alias != null) {
            rangeVariables.add(alias.toLowerCase(Locale.ROOT));
            out.append(" AS ").append(DuckTypes.quoteIdentifier(alias));
        }
        return aliasEnd;
    }

    /** {@code UNNEST(expr) [AS] x} in FROM: DuckDB needs {@code AS t(x)} to name the element column. */
    private int renderUnnest(int i, int end, StringBuilder out) {
        int open = nextSignificant(i + 1, end);
        if (open < 0 || !tokens.get(open).isPunct("(")) {
            out.append(tokens.get(i).text);
            return i + 1;
        }
        int close = matchingParen(open);
        String array = render(open + 1, close);
        int next = nextSignificant(close + 1, end);
        int nameIndex = -1;
        if (next >= 0 && tokens.get(next).isKeyword("AS")) {
            nameIndex = nextSignificant(next + 1, end);
        } else if (next >= 0 && ((tokens.get(next).kind == Kind.IDENT
                && !NON_ALIAS_KEYWORDS.contains(tokens.get(next).upper())) || tokens.get(next).kind == Kind.QIDENT)) {
            nameIndex = next;
        }
        if (next >= 0 && tokens.get(next).isKeyword("WITH")) {
            throw invalidQuery("UNNEST ... WITH OFFSET is not supported by the floci BigQuery emulator.");
        }
        if (nameIndex < 0) {
            // Unaliased: GoogleSQL expands STRUCT elements into one column per field.
            out.append("(SELECT UNNEST(").append(array).append(", max_depth := 2))");
            return close + 1;
        }
        out.append("UNNEST(").append(array).append(')');
        if (tokens.get(nameIndex).kind == Kind.IDENT || tokens.get(nameIndex).kind == Kind.QIDENT) {
            String element = tokens.get(nameIndex).identifierText();
            out.append(" AS ").append(DuckTypes.quoteIdentifier("_unnest_" + element)).append('(')
                    .append(DuckTypes.quoteIdentifier(element)).append(')');
            int after = nextSignificant(nameIndex + 1, end);
            if (after >= 0 && tokens.get(after).isKeyword("WITH")) {
                throw invalidQuery("UNNEST ... WITH OFFSET is not supported by the floci BigQuery emulator.");
            }
            return nameIndex + 1;
        }
        return close + 1;
    }

    private TableRef resolveTable(List<String> segments) {
        if (segments.size() == 1) {
            String name = segments.getFirst();
            if (cteNames.contains(name.toLowerCase(Locale.ROOT))) {
                return null;
            }
            if (defaultDatasetId == null || defaultDatasetId.isBlank()) {
                throw invalidQuery("Table name \"" + name
                        + "\" missing dataset while no default dataset is set in the request.");
            }
            return new TableRef(defaultDatasetId, name);
        }
        if (segments.size() == 2) {
            return new TableRef(segments.get(0), segments.get(1));
        }
        if (segments.size() == 3) {
            if (!segments.get(0).equals(projectId)) {
                throw invalidQuery("Cross-project queries are not supported by the floci BigQuery emulator: "
                        + String.join(".", segments));
            }
            return new TableRef(segments.get(1), segments.get(2));
        }
        throw invalidQuery("Unsupported table reference " + String.join(".", segments)
                + " (INFORMATION_SCHEMA and wildcard tables are not emulated).");
    }

    // ── Functions, casts and literals ───────────────────────────────────────

    private static boolean isTypedLiteral(String upper) {
        return switch (upper) {
            case "TIMESTAMP", "DATETIME", "DATE", "TIME", "NUMERIC", "BIGNUMERIC", "JSON" -> true;
            default -> false;
        };
    }

    private static String typedLiteral(String type, String value) {
        String literal = DuckTypes.quoteLiteral(value);
        return switch (type) {
            case "TIMESTAMP" -> "CAST(" + literal + " AS TIMESTAMPTZ)";
            case "DATETIME" -> "CAST(" + literal + " AS TIMESTAMP)";
            case "DATE" -> "CAST(" + literal + " AS DATE)";
            case "TIME" -> "CAST(" + literal + " AS TIME)";
            case "JSON" -> "CAST(" + literal + " AS JSON)";
            default -> "CAST(" + literal + " AS DECIMAL(38,9))";
        };
    }

    /** Returns the DuckDB rendering of {@code name(...)} when it needs a shim, else {@code null}. */
    private String renderCall(String name, String original, int open, int close) {
        switch (name) {
            case "CAST", "SAFE_CAST" -> {
                return renderCast(name.equals("SAFE_CAST") ? "TRY_CAST" : "CAST", open, close);
            }
            case "EXTRACT" -> {
                return renderExtract(open, close);
            }
            case "STRUCT" -> {
                return renderStruct(open, close);
            }
            default -> {
                // fall through to argument-based shims
            }
        }
        List<String> a = arguments(open, close);
        return switch (name) {
            case "SAFE_DIVIDE" -> args(a, 2, name, "(CASE WHEN (" + at(a, 1) + ") = 0 THEN NULL ELSE ("
                    + at(a, 0) + ") / (" + at(a, 1) + ") END)");
            case "IEEE_DIVIDE" -> args(a, 2, name, "(CAST(" + at(a, 0) + " AS DOUBLE) / (" + at(a, 1) + "))");
            case "DIV" -> args(a, 2, name, "((" + at(a, 0) + ") // (" + at(a, 1) + "))");
            case "IF" -> args(a, 3, name, "(CASE WHEN " + at(a, 0) + " THEN " + at(a, 1) + " ELSE "
                    + at(a, 2) + " END)");
            case "COUNTIF" -> "count_if(" + String.join(", ", a) + ")";
            case "LOGICAL_AND" -> "bool_and(" + String.join(", ", a) + ")";
            case "LOGICAL_OR" -> "bool_or(" + String.join(", ", a) + ")";
            case "ARRAY_LENGTH" -> "len(" + String.join(", ", a) + ")";
            case "ARRAY_REVERSE" -> "list_reverse(" + String.join(", ", a) + ")";
            case "GENERATE_ARRAY" -> "generate_series(" + String.join(", ", a) + ")";
            case "SPLIT" -> a.size() == 1 ? "string_split(" + a.getFirst() + ", ',')"
                    : "string_split(" + String.join(", ", a) + ")";
            case "FORMAT" -> "printf(" + String.join(", ", a) + ")";
            case "TO_JSON_STRING" -> "CAST(to_json(" + at(a, 0) + ") AS VARCHAR)";
            case "JSON_VALUE", "JSON_EXTRACT_SCALAR" -> a.size() == 1
                    ? "json_extract_string(" + a.getFirst() + ", '$')"
                    : "json_extract_string(" + String.join(", ", a) + ")";
            case "JSON_QUERY", "JSON_EXTRACT" -> a.size() == 1
                    ? "json_extract(" + a.getFirst() + ", '$')"
                    : "json_extract(" + String.join(", ", a) + ")";
            case "REGEXP_CONTAINS" -> "regexp_matches(" + String.join(", ", a) + ")";
            case "REGEXP_EXTRACT" -> args(a, 2, name, "regexp_extract(" + at(a, 0) + ", " + at(a, 1) + ", "
                    + (hasCaptureGroup(open, close) ? "1" : "0") + ")");
            case "REGEXP_REPLACE" -> args(a, 3, name, "regexp_replace(" + at(a, 0) + ", " + at(a, 1) + ", "
                    + at(a, 2) + ", 'g')");
            case "CURRENT_TIMESTAMP" -> "current_timestamp";
            case "CURRENT_DATE" -> "current_date";
            case "CURRENT_DATETIME" -> "CAST(current_timestamp AS TIMESTAMP)";
            case "UNIX_SECONDS" -> "CAST(epoch(" + at(a, 0) + ") AS BIGINT)";
            case "UNIX_MILLIS" -> "epoch_ms(" + at(a, 0) + ")";
            case "UNIX_MICROS" -> "epoch_us(" + at(a, 0) + ")";
            case "UNIX_DATE" -> "date_diff('day', DATE '1970-01-01', " + at(a, 0) + ")";
            case "TIMESTAMP_SECONDS" -> "make_timestamptz(CAST(" + at(a, 0) + " AS BIGINT) * 1000000)";
            case "TIMESTAMP_MILLIS" -> "make_timestamptz(CAST(" + at(a, 0) + " AS BIGINT) * 1000)";
            case "TIMESTAMP_MICROS" -> "make_timestamptz(CAST(" + at(a, 0) + " AS BIGINT))";
            case "TIMESTAMP_ADD", "DATETIME_ADD", "TIME_ADD" -> args(a, 2, name,
                    "(" + at(a, 0) + " + " + at(a, 1) + ")");
            case "TIMESTAMP_SUB", "DATETIME_SUB", "TIME_SUB" -> args(a, 2, name,
                    "(" + at(a, 0) + " - " + at(a, 1) + ")");
            case "DATE_ADD" -> args(a, 2, name, "CAST(" + at(a, 0) + " + " + at(a, 1) + " AS DATE)");
            case "DATE_SUB" -> args(a, 2, name, "CAST(" + at(a, 0) + " - " + at(a, 1) + " AS DATE)");
            case "TIMESTAMP_DIFF", "DATETIME_DIFF", "DATE_DIFF", "TIME_DIFF" -> args(a, 3, name,
                    "date_diff(" + DuckTypes.quoteLiteral(datePart(a.get(2))) + ", " + at(a, 1) + ", "
                            + at(a, 0) + ")");
            case "TIMESTAMP_TRUNC", "DATETIME_TRUNC" -> args(a, 2, name,
                    "date_trunc(" + DuckTypes.quoteLiteral(datePart(a.get(1))) + ", " + at(a, 0) + ")");
            case "DATE_TRUNC" -> args(a, 2, name, "CAST(date_trunc(" + DuckTypes.quoteLiteral(datePart(a.get(1)))
                    + ", " + at(a, 0) + ") AS DATE)");
            case "FORMAT_TIMESTAMP", "FORMAT_DATETIME", "FORMAT_DATE", "FORMAT_TIME" -> args(a, 2, name,
                    "strftime(" + at(a, 1) + ", " + at(a, 0) + ")");
            case "PARSE_TIMESTAMP" -> args(a, 2, name,
                    "CAST(strptime(" + at(a, 1) + ", " + at(a, 0) + ") AS TIMESTAMPTZ)");
            case "PARSE_DATETIME" -> args(a, 2, name, "strptime(" + at(a, 1) + ", " + at(a, 0) + ")");
            case "PARSE_DATE" -> args(a, 2, name,
                    "CAST(strptime(" + at(a, 1) + ", " + at(a, 0) + ") AS DATE)");
            case "DATE" -> a.size() == 3 ? "make_date(" + String.join(", ", a) + ")"
                    : "CAST(" + at(a, 0) + " AS DATE)";
            case "DATETIME" -> "CAST(" + at(a, 0) + " AS TIMESTAMP)";
            case "TIMESTAMP" -> "CAST(" + at(a, 0) + " AS TIMESTAMPTZ)";
            default -> original + "(" + String.join(", ", a) + ")";
        };
    }

    private static String args(List<String> a, int expected, String name, String rendered) {
        if (a.size() < expected) {
            throw invalidQuery("No matching signature for function " + name + " with " + a.size() + " arguments");
        }
        return rendered;
    }

    private static String at(List<String> a, int index) {
        if (index >= a.size()) {
            throw invalidQuery("Missing function argument " + (index + 1));
        }
        return a.get(index).trim();
    }

    private static String datePart(String part) {
        String p = part.trim().toLowerCase(Locale.ROOT);
        return switch (p) {
            case "dayofweek" -> "dow";
            case "dayofyear" -> "doy";
            case "isoweek" -> "week";
            default -> p;
        };
    }

    private boolean hasCaptureGroup(int open, int close) {
        int depth = 0;
        int commas = 0;
        for (int k = open + 1; k < close; k++) {
            Token t = tokens.get(k);
            if (t.isPunct("(")) {
                depth++;
            } else if (t.isPunct(")")) {
                depth--;
            } else if (depth == 0 && t.isPunct(",")) {
                commas++;
            } else if (commas == 1 && depth == 0 && t.kind == Kind.STRING) {
                String pattern = t.value;
                for (int p = 0; p < pattern.length() - 1; p++) {
                    if (pattern.charAt(p) == '\\') {
                        p++;
                        continue;
                    }
                    if (pattern.charAt(p) == '(' && pattern.charAt(p + 1) != '?') {
                        return true;
                    }
                }
                return false;
            }
        }
        return false;
    }

    private String renderCast(String function, int open, int close) {
        int depth = 0;
        for (int k = open + 1; k < close; k++) {
            Token t = tokens.get(k);
            if (t.isPunct("(")) {
                depth++;
            } else if (t.isPunct(")")) {
                depth--;
            } else if (depth == 0 && t.isKeyword("AS")) {
                String expr = render(open + 1, k).trim();
                String type = translateType(significant(k + 1, close));
                return function + "(" + expr + " AS " + type + ")";
            }
        }
        return function + "(" + render(open + 1, close) + ")";
    }

    private String renderExtract(int open, int close) {
        List<Token> inner = significant(open + 1, close);
        if (inner.size() >= 3 && inner.get(1).isKeyword("FROM")) {
            String part = inner.getFirst().upper();
            int fromIndex = -1;
            for (int k = open + 1; k < close; k++) {
                if (tokens.get(k).isKeyword("FROM")) {
                    fromIndex = k;
                    break;
                }
            }
            String expr = render(fromIndex + 1, close).trim();
            return switch (part) {
                case "DAYOFWEEK" -> "(dayofweek(" + expr + ") + 1)";
                case "DAYOFYEAR" -> "dayofyear(" + expr + ")";
                case "DATE" -> "CAST(" + expr + " AS DATE)";
                default -> "EXTRACT(" + part + " FROM " + expr + ")";
            };
        }
        return "EXTRACT(" + render(open + 1, close) + ")";
    }

    /** {@code STRUCT(a AS x, b AS y)} → {@code {'x': a, 'y': b}}. */
    private String renderStruct(int open, int close) {
        List<int[]> ranges = argumentRanges(open, close);
        if (ranges.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        for (int n = 0; n < ranges.size(); n++) {
            int[] r = ranges.get(n);
            int asIndex = -1;
            int depth = 0;
            for (int k = r[0]; k < r[1]; k++) {
                Token t = tokens.get(k);
                if (t.isPunct("(")) {
                    depth++;
                } else if (t.isPunct(")")) {
                    depth--;
                } else if (depth == 0 && t.isKeyword("AS")) {
                    asIndex = k;
                }
            }
            String name;
            String expr;
            if (asIndex >= 0) {
                expr = render(r[0], asIndex).trim();
                name = significant(asIndex + 1, r[1]).getFirst().identifierText();
            } else {
                expr = render(r[0], r[1]).trim();
                List<Token> item = significant(r[0], r[1]);
                name = isPath(item) ? item.getLast().identifierText() : "_field_" + (n + 1);
            }
            if (n > 0) {
                sb.append(", ");
            }
            sb.append(DuckTypes.quoteLiteral(name)).append(": ").append(expr);
        }
        return sb.append('}').toString();
    }

    /** Maps a GoogleSQL type (possibly {@code ARRAY<...>} / {@code STRUCT<...>}) to DuckDB. */
    private static String translateType(List<Token> tokens) {
        List<Token> type = new ArrayList<>();
        for (Token t : tokens) {
            if (t.isPunct(">>")) {
                type.add(new Token(Kind.PUNCT, ">", null));
                type.add(new Token(Kind.PUNCT, ">", null));
            } else {
                type.add(t);
            }
        }
        StringBuilder sb = new StringBuilder();
        int[] pos = {0};
        sb.append(parseTypeTokens(type, pos));
        return sb.toString();
    }

    private static String parseTypeTokens(List<Token> type, int[] pos) {
        if (pos[0] >= type.size()) {
            return "VARCHAR";
        }
        Token head = type.get(pos[0]++);
        String name = head.upper();
        if ((name.equals("ARRAY") || name.equals("STRUCT")) && pos[0] < type.size() && type.get(pos[0]).isPunct("<")) {
            pos[0]++;
            if (name.equals("ARRAY")) {
                String element = parseTypeTokens(type, pos);
                expect(type, pos, ">");
                return element + "[]";
            }
            StringBuilder fields = new StringBuilder("STRUCT(");
            boolean first = true;
            while (pos[0] < type.size() && !type.get(pos[0]).isPunct(">")) {
                if (!first) {
                    expect(type, pos, ",");
                }
                String fieldName = type.get(pos[0]++).identifierText();
                fields.append(first ? "" : ", ").append(DuckTypes.quoteIdentifier(fieldName)).append(' ')
                        .append(parseTypeTokens(type, pos));
                first = false;
            }
            expect(type, pos, ">");
            return fields.append(')').toString();
        }
        String mapped = switch (name) {
            case "INT64", "INTEGER", "INT", "BIGINT", "SMALLINT", "TINYINT", "BYTEINT" -> "BIGINT";
            case "FLOAT64", "FLOAT" -> "DOUBLE";
            case "BOOL", "BOOLEAN" -> "BOOLEAN";
            case "STRING" -> "VARCHAR";
            case "BYTES" -> "BLOB";
            case "NUMERIC", "DECIMAL", "BIGNUMERIC", "BIGDECIMAL" -> "DECIMAL(38,9)";
            case "DATE" -> "DATE";
            case "TIME" -> "TIME";
            case "DATETIME" -> "TIMESTAMP";
            case "TIMESTAMP" -> "TIMESTAMPTZ";
            case "JSON" -> "JSON";
            case "INTERVAL" -> "INTERVAL";
            default -> head.text;
        };
        // Skip parameterized precision, e.g. NUMERIC(10, 2) or STRING(20).
        if (pos[0] < type.size() && type.get(pos[0]).isPunct("(")) {
            int depth = 0;
            while (pos[0] < type.size()) {
                Token t = type.get(pos[0]++);
                if (t.isPunct("(")) {
                    depth++;
                } else if (t.isPunct(")") && --depth == 0) {
                    break;
                }
            }
        }
        return mapped;
    }

    private static void expect(List<Token> type, int[] pos, String punct) {
        if (pos[0] < type.size() && type.get(pos[0]).isPunct(punct)) {
            pos[0]++;
            return;
        }
        throw invalidQuery("Syntax error in type: expected \"" + punct + "\"");
    }

    // ── Token helpers ───────────────────────────────────────────────────────

    private List<String> arguments(int open, int close) {
        List<String> args = new ArrayList<>();
        for (int[] r : argumentRanges(open, close)) {
            args.add(render(r[0], r[1]).trim());
        }
        return args;
    }

    private List<int[]> argumentRanges(int open, int close) {
        List<int[]> ranges = new ArrayList<>();
        int depth = 0;
        int start = open + 1;
        boolean any = false;
        for (int k = open + 1; k < close; k++) {
            Token t = tokens.get(k);
            if (t.kind != Kind.SPACE) {
                any = true;
            }
            if (t.isPunct("(") || t.isPunct("[")) {
                depth++;
            } else if (t.isPunct(")") || t.isPunct("]")) {
                depth--;
            } else if (depth == 0 && t.isPunct(",")) {
                ranges.add(new int[] {start, k});
                start = k + 1;
            }
        }
        if (any) {
            ranges.add(new int[] {start, close});
        }
        return ranges;
    }

    private int matchingParen(int open) {
        int depth = 0;
        for (int k = open; k < tokens.size(); k++) {
            Token t = tokens.get(k);
            if (t.isPunct("(")) {
                depth++;
            } else if (t.isPunct(")")) {
                depth--;
                if (depth == 0) {
                    return k;
                }
            }
        }
        throw invalidQuery("Syntax error: Expected \")\" but got end of script");
    }

    private int nextSignificant(int from, int end) {
        for (int k = from; k < end; k++) {
            if (tokens.get(k).kind != Kind.SPACE && tokens.get(k).kind != Kind.RAW) {
                return k;
            }
        }
        return -1;
    }

    private boolean nextSignificantIs(int from, int end, String punct) {
        int next = nextSignificant(from, end);
        return next >= 0 && tokens.get(next).isPunct(punct);
    }

    private static String quotePath(String backtickContent) {
        List<String> parts = List.of(backtickContent.split("\\.", -1));
        return quoteSegments(parts);
    }

    private static String quoteSegments(List<String> segments) {
        StringBuilder sb = new StringBuilder();
        for (int n = 0; n < segments.size(); n++) {
            if (n > 0) {
                sb.append('.');
            }
            sb.append(DuckTypes.quoteIdentifier(segments.get(n)));
        }
        return sb.toString();
    }

    static GcpException invalidQuery(String message) {
        return GcpException.invalidArgument(message).withReason("invalidQuery");
    }

    // ── Query parameters ────────────────────────────────────────────────────

    /** Named or positional query parameters, rendered as typed DuckDB literals. */
    static final class QueryParameters {

        private final List<Map<String, Object>> parameters;
        private final boolean positional;
        private int nextPositional;

        QueryParameters(List<Map<String, Object>> parameters, String parameterMode) {
            this.parameters = parameters != null ? parameters : List.of();
            this.positional = "POSITIONAL".equalsIgnoreCase(parameterMode);
        }

        static QueryParameters none() {
            return new QueryParameters(List.of(), null);
        }

        String named(String name) {
            for (Map<String, Object> p : parameters) {
                if (p.get("name") instanceof String n && n.equalsIgnoreCase(name)) {
                    return literal(asMap(p.get("parameterType")), asMap(p.get("parameterValue")));
                }
            }
            throw invalidQuery("Query parameter '" + name + "' not found");
        }

        String nextPositional() {
            if (!positional && !parameters.isEmpty() && parameters.getFirst().get("name") != null) {
                throw invalidQuery("Positional parameters are not allowed when parameterMode is NAMED");
            }
            if (nextPositional >= parameters.size()) {
                throw invalidQuery("Too few positional query parameters were supplied");
            }
            Map<String, Object> p = parameters.get(nextPositional++);
            return literal(asMap(p.get("parameterType")), asMap(p.get("parameterValue")));
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> asMap(Object o) {
            return o instanceof Map ? (Map<String, Object>) o : Map.of();
        }

        @SuppressWarnings("unchecked")
        static String literal(Map<String, Object> type, Map<String, Object> value) {
            String typeName = type.get("type") instanceof String s ? s.toUpperCase(Locale.ROOT) : "STRING";
            if (typeName.equals("ARRAY")) {
                Map<String, Object> elementType = asMap(type.get("arrayType"));
                String duckElement = duckTypeOf(elementType);
                Object values = value.get("arrayValues");
                if (!(values instanceof List<?> list)) {
                    return "CAST(NULL AS " + duckElement + "[])";
                }
                if (list.isEmpty()) {
                    return "CAST([] AS " + duckElement + "[])";
                }
                StringBuilder sb = new StringBuilder("[");
                for (int n = 0; n < list.size(); n++) {
                    if (n > 0) {
                        sb.append(", ");
                    }
                    sb.append(literal(elementType, asMap(list.get(n))));
                }
                return sb.append(']').toString();
            }
            if (typeName.equals("STRUCT")) {
                Object structTypes = type.get("structTypes");
                Map<String, Object> structValues = asMap(value.get("structValues"));
                if (!(structTypes instanceof List<?> fields)) {
                    return "NULL";
                }
                StringBuilder sb = new StringBuilder("{");
                for (int n = 0; n < fields.size(); n++) {
                    Map<String, Object> field = asMap(fields.get(n));
                    String fieldName = field.get("name") instanceof String s ? s : "_field_" + (n + 1);
                    if (n > 0) {
                        sb.append(", ");
                    }
                    sb.append(DuckTypes.quoteLiteral(fieldName)).append(": ")
                            .append(literal(asMap(field.get("type")), asMap(structValues.get(fieldName))));
                }
                return sb.append('}').toString();
            }
            Object raw = value.get("value");
            String duckType = duckTypeOf(type);
            if (raw == null) {
                return "CAST(NULL AS " + duckType + ")";
            }
            String text = raw.toString();
            try {
                return switch (typeName) {
                    case "INT64", "INTEGER" -> "CAST(" + Long.parseLong(text.trim()) + " AS BIGINT)";
                    case "FLOAT64", "FLOAT" -> floatLiteral(text.trim());
                    case "BOOL", "BOOLEAN" -> boolLiteral(text.trim(), typeName);
                    case "NUMERIC", "BIGNUMERIC" -> "CAST(" + DuckTypes.quoteLiteral(
                            new BigDecimal(text.trim()).toPlainString()) + " AS DECIMAL(38,9))";
                    case "BYTES" -> "from_base64(" + DuckTypes.quoteLiteral(text) + ")";
                    default -> "CAST(" + DuckTypes.quoteLiteral(text) + " AS " + duckType + ")";
                };
            } catch (NumberFormatException e) {
                throw invalidQuery("Invalid " + typeName + " query parameter value: " + text);
            }
        }

        /**
         * Boolean.parseBoolean turns anything that is not "true" into false, so a malformed value
         * would quietly flip a predicate instead of failing the query, unlike the numeric types in
         * the same switch.
         */
        private static String boolLiteral(String text, String typeName) {
            if (text.equalsIgnoreCase("true")) {
                return "TRUE";
            }
            if (text.equalsIgnoreCase("false")) {
                return "FALSE";
            }
            throw invalidQuery("Invalid " + typeName + " query parameter value: " + text);
        }

        private static String floatLiteral(String text) {
            return switch (text.toLowerCase(Locale.ROOT)) {
                case "nan" -> "CAST('NaN' AS DOUBLE)";
                case "inf", "+inf", "infinity" -> "CAST('Infinity' AS DOUBLE)";
                case "-inf", "-infinity" -> "CAST('-Infinity' AS DOUBLE)";
                default -> "CAST(" + Double.parseDouble(text) + " AS DOUBLE)";
            };
        }

        @SuppressWarnings("unchecked")
        private static String duckTypeOf(Map<String, Object> type) {
            String typeName = type.get("type") instanceof String s ? s.toUpperCase(Locale.ROOT) : "STRING";
            if (typeName.equals("ARRAY")) {
                return duckTypeOf(asMap(type.get("arrayType"))) + "[]";
            }
            if (typeName.equals("STRUCT") && type.get("structTypes") instanceof List<?> fields) {
                StringBuilder sb = new StringBuilder("STRUCT(");
                for (int n = 0; n < fields.size(); n++) {
                    Map<String, Object> field = asMap(fields.get(n));
                    if (n > 0) {
                        sb.append(", ");
                    }
                    sb.append(DuckTypes.quoteIdentifier(String.valueOf(field.getOrDefault("name", "_field_" + (n + 1)))))
                            .append(' ').append(duckTypeOf(asMap(field.get("type"))));
                }
                return sb.append(')').toString();
            }
            return switch (typeName) {
                case "INT64", "INTEGER" -> "BIGINT";
                case "FLOAT64", "FLOAT" -> "DOUBLE";
                case "BOOL", "BOOLEAN" -> "BOOLEAN";
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
    }

    // ── Lexer ───────────────────────────────────────────────────────────────

    enum Kind { SPACE, IDENT, QIDENT, STRING, BYTES, NUMBER, NAMED_PARAM, POSITIONAL_PARAM, PUNCT, RAW }

    static final class Token {
        final Kind kind;
        final String text;
        /** Decoded content for STRING/BYTES/QIDENT, the name for NAMED_PARAM. */
        final String value;

        Token(Kind kind, String text, String value) {
            this.kind = kind;
            this.text = text;
            this.value = value;
        }

        static Token raw(String text) {
            return new Token(Kind.RAW, text, text);
        }

        String upper() {
            return text.toUpperCase(Locale.ROOT);
        }

        boolean isKeyword(String keyword) {
            return kind == Kind.IDENT && text.equalsIgnoreCase(keyword);
        }

        boolean isPunct(String punct) {
            return kind == Kind.PUNCT && text.equals(punct);
        }

        String identifierText() {
            return kind == Kind.QIDENT ? value : text;
        }

        @Override
        public String toString() {
            return kind + ":" + text;
        }
    }

    static final class Lexer {
        private final String s;
        private int pos;

        Lexer(String s) {
            this.s = s;
        }

        List<Token> tokenize() {
            List<Token> out = new ArrayList<>();
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (Character.isWhitespace(c)) {
                    int start = pos;
                    while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                        pos++;
                    }
                    out.add(new Token(Kind.SPACE, s.substring(start, pos), null));
                } else if (c == '-' && peek(1) == '-' || c == '#') {
                    while (pos < s.length() && s.charAt(pos) != '\n') {
                        pos++;
                    }
                    out.add(new Token(Kind.SPACE, " ", null));
                } else if (c == '/' && peek(1) == '*') {
                    int close = s.indexOf("*/", pos + 2);
                    if (close < 0) {
                        throw invalidQuery("Syntax error: Unclosed comment");
                    }
                    pos = close + 2;
                    out.add(new Token(Kind.SPACE, " ", null));
                } else if (c == '`') {
                    int close = s.indexOf('`', pos + 1);
                    if (close < 0) {
                        throw invalidQuery("Syntax error: Unclosed identifier literal");
                    }
                    String content = s.substring(pos + 1, close);
                    pos = close + 1;
                    out.add(new Token(Kind.QIDENT, "`" + content + "`", content));
                } else if (isStringStart()) {
                    out.add(readString());
                } else if (Character.isDigit(c) || (c == '.' && Character.isDigit(peek(1)))) {
                    int start = pos;
                    while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '.'
                            || ((s.charAt(pos) == '+' || s.charAt(pos) == '-')
                            && (s.charAt(pos - 1) == 'e' || s.charAt(pos - 1) == 'E')))) {
                        pos++;
                    }
                    out.add(new Token(Kind.NUMBER, s.substring(start, pos), null));
                } else if (Character.isLetter(c) || c == '_') {
                    int start = pos;
                    while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) {
                        pos++;
                    }
                    out.add(new Token(Kind.IDENT, s.substring(start, pos), null));
                } else if (c == '@' && peek(1) == '@') {
                    throw invalidQuery("System variables (@@...) are not supported by the floci BigQuery emulator.");
                } else if (c == '@') {
                    int start = ++pos;
                    if (pos < s.length() && s.charAt(pos) == '`') {
                        int close = s.indexOf('`', pos + 1);
                        if (close < 0) {
                            throw invalidQuery("Syntax error: Unclosed identifier literal");
                        }
                        String name = s.substring(pos + 1, close);
                        pos = close + 1;
                        out.add(new Token(Kind.NAMED_PARAM, "@" + name, name));
                        continue;
                    }
                    while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) {
                        pos++;
                    }
                    String name = s.substring(start, pos);
                    out.add(new Token(Kind.NAMED_PARAM, "@" + name, name));
                } else if (c == '?') {
                    pos++;
                    out.add(new Token(Kind.POSITIONAL_PARAM, "?", null));
                } else {
                    out.add(readPunct());
                }
            }
            return out;
        }

        private boolean isStringStart() {
            char c = s.charAt(pos);
            if (c == '\'' || c == '"') {
                return true;
            }
            char lower = Character.toLowerCase(c);
            if (lower == 'r' || lower == 'b') {
                char n1 = peek(1);
                if (n1 == '\'' || n1 == '"') {
                    return pos == 0 || !Character.isLetterOrDigit(s.charAt(pos - 1));
                }
                char lowerN1 = Character.toLowerCase(n1);
                if ((lowerN1 == 'r' || lowerN1 == 'b') && lowerN1 != lower && (peek(2) == '\'' || peek(2) == '"')) {
                    return pos == 0 || !Character.isLetterOrDigit(s.charAt(pos - 1));
                }
            }
            return false;
        }

        private Token readString() {
            int start = pos;
            boolean raw = false;
            boolean bytes = false;
            while (s.charAt(pos) != '\'' && s.charAt(pos) != '"') {
                char p = Character.toLowerCase(s.charAt(pos++));
                raw |= p == 'r';
                bytes |= p == 'b';
            }
            char quote = s.charAt(pos);
            boolean triple = peek(1) == quote && peek(2) == quote;
            String delimiter = triple ? String.valueOf(quote).repeat(3) : String.valueOf(quote);
            pos += delimiter.length();
            StringBuilder value = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw invalidQuery("Syntax error: Unclosed string literal");
                }
                if (s.startsWith(delimiter, pos)) {
                    pos += delimiter.length();
                    break;
                }
                char c = s.charAt(pos++);
                if (c == '\\' && pos < s.length()) {
                    char e = s.charAt(pos++);
                    if (raw) {
                        value.append('\\').append(e);
                        continue;
                    }
                    switch (e) {
                        case 'n' -> value.append('\n');
                        case 't' -> value.append('\t');
                        case 'r' -> value.append('\r');
                        case '0' -> value.append('\0');
                        default -> value.append(e);
                    }
                    continue;
                }
                value.append(c);
            }
            return new Token(bytes ? Kind.BYTES : Kind.STRING, s.substring(start, pos), value.toString());
        }

        private Token readPunct() {
            String[] multi = {"<=", ">=", "<>", "!=", "||", "<<", ">>", "=>"};
            for (String m : multi) {
                if (s.startsWith(m, pos)) {
                    pos += m.length();
                    return new Token(Kind.PUNCT, m, null);
                }
            }
            return new Token(Kind.PUNCT, String.valueOf(s.charAt(pos++)), null);
        }

        private char peek(int offset) {
            int index = pos + offset;
            return index < s.length() ? s.charAt(index) : '\0';
        }
    }
}
