package io.floci.gcp.services.bigquery;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.bigquery.model.TableFieldSchema;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    record Translation(String sql, Set<TableRef> tables, Set<InformationSchema.Ref> informationSchema,
                       boolean needsUnicodeCaseFold) {}

    private record UnnestOffset(String name, int nextToken) {}

    private static final Set<String> CLAUSE_END_KEYWORDS = Set.of(
            "WHERE", "GROUP", "HAVING", "QUALIFY", "WINDOW", "ORDER", "LIMIT", "OFFSET",
            "UNION", "INTERSECT", "EXCEPT", "SELECT");

    private static final Set<String> NON_ALIAS_KEYWORDS = Set.of(
            "WHERE", "GROUP", "HAVING", "QUALIFY", "WINDOW", "ORDER", "LIMIT", "OFFSET", "UNION",
            "INTERSECT", "EXCEPT", "SELECT", "FROM", "JOIN", "INNER", "LEFT", "RIGHT", "FULL",
            "CROSS", "OUTER", "ON", "USING", "AS", "END", "AND", "OR", "NOT", "IS", "NULL", "IN",
            "LIKE", "BETWEEN", "CASE", "WHEN", "THEN", "ELSE", "TRUE", "FALSE", "DESC", "ASC",
            "WITH", "UNNEST", "TABLESAMPLE", "FOR", "ROWS", "RANGE", "OVER", "PARTITION", "BY",
            "NULLS", "FIRST", "LAST", "DISTINCT", "ALL", "INTERVAL", "LATERAL", "NATURAL", "PIVOT", "UNPIVOT");

    /**
     * GoogleSQL reserved keywords
     * (https://docs.cloud.google.com/bigquery/docs/reference/standard-sql/lexical#reserved_keywords).
     * BigQuery rejects them as an implicit alias, so they are never rewritten to one.
     */
    private static final Set<String> GOOGLESQL_RESERVED = Set.of(
            "ALL", "AND", "ANY", "ARRAY", "AS", "ASC", "ASSERT_ROWS_MODIFIED", "AT", "BETWEEN", "BY", "CASE",
            "CAST", "COLLATE", "CONTAINS", "CREATE", "CROSS", "CUBE", "CURRENT", "DEFAULT", "DEFINE", "DESC",
            "DISTINCT", "ELSE", "END", "ENUM", "ESCAPE", "EXCEPT", "EXCLUDE", "EXISTS", "EXTRACT", "FALSE",
            "FETCH", "FOLLOWING", "FOR", "FROM", "FULL", "GRAPH_TABLE", "GROUP", "GROUPING", "GROUPS", "HASH",
            "HAVING", "IF", "IGNORE", "IN", "INNER", "INTERSECT", "INTERVAL", "INTO", "IS", "JOIN", "LATERAL",
            "LEFT", "LIKE", "LIMIT", "LOOKUP", "MERGE", "NATURAL", "NEW", "NO", "NOT", "NULL", "NULLS", "OF",
            "ON", "OR", "ORDER", "OUTER", "OVER", "PARTITION", "PRECEDING", "PROTO", "QUALIFY", "RANGE",
            "RECURSIVE", "RESPECT", "RIGHT", "ROLLUP", "ROWS", "SELECT", "SET", "SOME", "STRUCT",
            "TABLESAMPLE", "THEN", "TO", "TREAT", "TRUE", "UNBOUNDED", "UNION", "UNNEST", "USING", "WHEN",
            "WHERE", "WINDOW", "WITH", "WITHIN");

    private static final Set<String> CALLABLE_RESERVED_KEYWORDS = Set.of(
            "ARRAY", "CAST", "COLLATE", "EXISTS", "EXTRACT", "GRAPH_TABLE", "GROUPING", "IF", "STRUCT", "TREAT");

    private static final Set<String> SHIMMED_FUNCTIONS = Set.of(
            "CAST", "SAFE_CAST", "EXTRACT", "STRUCT", "SAFE_DIVIDE", "IEEE_DIVIDE", "DIV", "IF", "COUNTIF", "CONCAT",
            "LOGICAL_AND", "LOGICAL_OR", "ARRAY_LENGTH", "ARRAY_REVERSE", "ARRAY_AGG", "ARRAY_CONCAT_AGG", "CONTAINS_SUBSTR",
            "APPROX_COUNT_DISTINCT", "TO_HEX", "MD5", "SHA1", "SHA256", "RAND", "INITCAP", "ANY_VALUE",
            "GENERATE_ARRAY", "GENERATE_DATE_ARRAY", "SPLIT", "FORMAT", "APPROX_QUANTILES",
            "TO_JSON_STRING", "JSON_VALUE", "JSON_EXTRACT_SCALAR", "JSON_QUERY", "JSON_EXTRACT", "JSON_TYPE",
            "REGEXP_CONTAINS", "REGEXP_EXTRACT", "REGEXP_REPLACE", "CURRENT_TIMESTAMP", "CURRENT_DATE",
            "CURRENT_DATETIME", "UNIX_SECONDS", "UNIX_MILLIS", "UNIX_MICROS", "UNIX_DATE",
            "TIMESTAMP_SECONDS", "TIMESTAMP_MILLIS", "TIMESTAMP_MICROS", "TIMESTAMP_ADD", "DATETIME_ADD",
            "TIME_ADD", "TIMESTAMP_SUB", "DATETIME_SUB", "TIME_SUB", "DATE_ADD", "DATE_SUB",
            "TIMESTAMP_DIFF", "DATETIME_DIFF", "DATE_DIFF", "TIME_DIFF", "TIMESTAMP_TRUNC", "DATETIME_TRUNC",
            "DATE_TRUNC", "FORMAT_TIMESTAMP", "FORMAT_DATETIME", "FORMAT_DATE", "FORMAT_TIME",
            "PARSE_TIMESTAMP", "PARSE_DATETIME", "PARSE_DATE", "DATE", "DATETIME", "TIMESTAMP",
            "GENERATE_UUID");

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
    private final List<CteScope> cteScopes = new ArrayList<>();
    private final Set<TableRef> tables = new LinkedHashSet<>();
    private final Set<InformationSchema.Ref> informationSchema = new LinkedHashSet<>();
    private final Map<Token, String> offsetValueColumns = new IdentityHashMap<>();
    private int generatedUnnestAlias;
    private int generatedDerivedTableAlias;
    private int generatedConcatAlias;
    private int generatedInitcapName;
    private int generatedAnyValueName;
    private boolean needsUnicodeCaseFold;
    private boolean queryResult;

    private List<Token> tokens;
    /** The {@code UNNEST} token of an unaliased MERGE source, rendered with {@link #mergeUnnestName} as its name. */
    private Token mergeUnnestSource;
    private String mergeUnnestName;
    /** The alias of an aliased MERGE {@code UNNEST} source, which subqueries can still reference. */
    private String mergeUnnestAlias;
    /** A table's lower-cased column names, or null when they are unknown. */
    private Function<TableRef, Set<String>> columnsOf = ref -> null;
    /** The MERGE source table, when the source is a table rather than a subquery. */
    private TableRef mergeSource;
    private MergeScope mergeScope;

    /**
     * Names that refer to the MERGE target or source; the columns known of either (null when neither's
     * are known); and whether those are all of their columns.
     */
    private record MergeScope(Set<String> names, Set<String> columns, boolean complete) {}

    private record CteScope(String name, Token start, Token end) {}

    private SqlDialectTranslator(String projectId, String defaultDatasetId, QueryParameters parameters) {
        this.projectId = projectId;
        this.defaultDatasetId = defaultDatasetId;
        this.parameters = parameters;
    }

    static Translation translate(String sql, String projectId, String defaultDatasetId,
                                 QueryParameters parameters) {
        return translate(sql, projectId, defaultDatasetId, parameters, ref -> null);
    }

    /**
     * As {@link #translate(String, String, String, QueryParameters)}, with {@code columnsOf} giving a
     * table's lower-cased column names (or null when unknown), which nested queries use to resolve
     * local columns before correlated range variables.
     */
    static Translation translate(String sql, String projectId, String defaultDatasetId,
                                 QueryParameters parameters, Function<TableRef, Set<String>> columnsOf) {
        SqlDialectTranslator translator = new SqlDialectTranslator(projectId, defaultDatasetId, parameters);
        translator.columnsOf = columnsOf;
        return translator.run(sql);
    }

    /** Converts BigQuery's {@code FROM source PIVOT (aggregate FOR column IN (...))} clause to DuckDB syntax. */
    private void rewritePivotSyntax() {
        for (int pivot = 0; pivot < tokens.size(); pivot++) {
            if (!tokens.get(pivot).upper().equals("PIVOT")) {
                continue;
            }
            int open = nextSignificant(pivot + 1, tokens.size());
            if (open < 0 || !tokens.get(open).isPunct("(")) {
                continue;
            }
            int close = matchingParen(open);
            int forIndex = topLevelKeyword(open + 1, close, "FOR");
            int inIndex = forIndex < 0 ? -1 : topLevelKeyword(forIndex + 1, close, "IN");
            if (forIndex < 0 || inIndex < 0) {
                continue;
            }
            int source = pivotSourceStart(pivot);
            if (source < 0 || source >= pivot) {
                continue;
            }
            // Render the source and PIVOT body before replacing them with RAW SQL. In a normal
            // FROM rendering pass, USING is interpreted as JOIN/MERGE syntax and its aggregate
            // function as a table source.
            boolean preserveSourceTokens = tokens.get(source).isPunct("(");
            List<Token> sourceTokens = preserveSourceTokens
                    ? new ArrayList<>(tokens.subList(source, pivot)) : List.of();
            String sourceSql = preserveSourceTokens ? null : renderPivotSource(source, pivot);
            String column = render(forIndex + 1, inIndex).trim();
            String aggregate = render(open + 1, forIndex).trim();
            String values = render(inIndex, close).trim();
            List<Token> replacement = new ArrayList<>();
            replacement.add(new Token(Kind.PUNCT, "(", null));
            String renames = pivotColumnRenames(open, forIndex, inIndex, close);
            if (renames != null) {
                replacement.add(Token.raw("SELECT * RENAME (" + renames + ") FROM"));
                replacement.add(new Token(Kind.PUNCT, "(", null));
            }
            if (preserveSourceTokens) {
                replacement.add(Token.raw("PIVOT "));
                replacement.addAll(sourceTokens);
                replacement.add(Token.raw(" ON " + column + " " + values + " USING " + aggregate));
            } else {
                replacement.add(Token.raw("PIVOT " + sourceSql + " ON " + column + " " + values
                        + " USING " + aggregate));
            }
            if (renames != null) {
                replacement.add(new Token(Kind.PUNCT, ")", null));
            }
            replacement.add(new Token(Kind.PUNCT, ")", null));
            tokens.subList(source, close + 1).clear();
            tokens.addAll(source, replacement);
            pivot = source + replacement.size() - 1;
        }
    }

    /**
     * DuckDB names pivot columns {@code <value>_<aggregate alias>}; GoogleSQL names them
     * {@code <prefix>_<value>}. Returns a RENAME list for the aggregates that carry a prefix, or null when
     * no rename is needed or a value's column name cannot be derived statically.
     */
    private String pivotColumnRenames(int open, int forIndex, int inIndex, int close) {
        List<String> prefixes = new ArrayList<>();
        for (int[] range : argumentRanges(open, forIndex)) {
            int as = topLevelAs(range[0], range[1]);
            int alias = as >= 0 ? nextSignificant(as + 1, range[1]) : implicitSelectAlias(range[0], range[1]);
            prefixes.add(alias < 0 ? null : tokens.get(alias).identifierText());
        }
        if (prefixes.stream().allMatch(prefix -> prefix == null)) {
            return null;
        }
        int valuesOpen = nextSignificant(inIndex + 1, close);
        if (valuesOpen < 0 || !tokens.get(valuesOpen).isPunct("(")) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (int[] range : argumentRanges(valuesOpen, matchingParen(valuesOpen))) {
            int as = topLevelAs(range[0], range[1]);
            int alias = as >= 0 ? nextSignificant(as + 1, range[1]) : implicitSelectAlias(range[0], range[1]);
            if (alias >= 0) {
                names.add(tokens.get(alias).identifierText());
                continue;
            }
            List<Token> value = significant(range[0], range[1]);
            if (value.size() != 1 || value.getFirst().kind != Kind.STRING && value.getFirst().kind != Kind.NUMBER) {
                return null;
            }
            names.add(value.getFirst().kind == Kind.STRING ? value.getFirst().value : value.getFirst().text);
        }
        List<String> renames = new ArrayList<>();
        for (String prefix : prefixes) {
            if (prefix == null) {
                continue;
            }
            for (String name : names) {
                String duckName = name + "_" + prefix;
                String googleName = prefix + "_" + name;
                if (!duckName.equals(googleName)) {
                    renames.add(DuckTypes.quoteIdentifier(duckName) + " AS " + DuckTypes.quoteIdentifier(googleName));
                }
            }
        }
        return renames.isEmpty() ? null : String.join(", ", renames);
    }

    private String renderPivotSource(int source, int pivot) {
        Token first = tokens.get(source);
        if (first.kind != Kind.IDENT && first.kind != Kind.QIDENT || first.isKeyword("UNNEST")) {
            return render(source, pivot).trim();
        }
        StringBuilder rendered = new StringBuilder();
        int next = renderTablePath(source, pivot, rendered, new HashSet<>());
        rendered.append(render(next, pivot));
        return rendered.toString().trim();
    }

    /** Finds the {@code from_item} immediately decorated by a PIVOT operator. */
    private int pivotSourceStart(int pivot) {
        int depth = 0;
        for (int i = pivot - 1; i >= 0; i--) {
            Token token = tokens.get(i);
            if (token.isPunct(")")) {
                depth++;
            } else if (token.isPunct("(")) {
                depth--;
            } else if (depth == 0 && (token.isKeyword("FROM") || token.isKeyword("JOIN")
                    || token.isPunct(","))) {
                return nextSignificant(i + 1, pivot);
            }
        }
        return -1;
    }

    private int topLevelKeyword(int start, int end, String keyword) {
        int depth = 0;
        for (int i = start; i < end; i++) {
            if (tokens.get(i).isPunct("(")) {
                depth++;
            } else if (tokens.get(i).isPunct(")")) {
                depth--;
            } else if (depth == 0 && tokens.get(i).upper().equals(keyword)) {
                return i;
            }
        }
        return -1;
    }

    // ── Statements (DML / DDL) ──────────────────────────────────────────────

    enum StatementKind {
        QUERY, INSERT, UPDATE, DELETE, MERGE, TRUNCATE, CREATE_TABLE, CREATE_VIEW, DROP_TABLE, DROP_VIEW,
        CREATE_SCHEMA, DROP_SCHEMA, ALTER_TABLE, ALTER_VIEW
    }

    enum PartitionExpression {
        INGESTION_TIME, BARE_DATE, DATE, TIMESTAMP_TRUNC, DATETIME_TRUNC, DATE_TRUNC, RANGE_BUCKET
    }

    private record PartitionSpec(Map<String, Object> timeMetadata, Map<String, Object> rangeMetadata,
                                 PartitionExpression expression) {}

    /**
     * A classified statement. {@code querySql} is the GoogleSQL text of the {@code SELECT} part
     * of {@code CREATE TABLE ... AS} and {@code CREATE VIEW}; {@code columns} is the column list
     * of a plain {@code CREATE TABLE}, already in BigQuery schema form. {@code options} are the
     * {@code SET OPTIONS} of an {@code ALTER TABLE}/{@code ALTER VIEW}, by option name; a null value
     * clears the option.
     */
    record Statement(StatementKind kind, String statementType, TableRef target, String datasetTarget,
                     boolean orReplace, boolean ifNotExists, boolean ifExists, boolean cascade,
                     boolean materialized, String querySql, List<TableFieldSchema> columns,
                     Map<String, Object> options, TableRef copySource,
                     Map<String, Object> timePartitioning, Map<String, Object> rangePartitioning,
                     PartitionExpression partitionExpression) {

        Statement(StatementKind kind, String statementType, TableRef target, String datasetTarget,
                  boolean orReplace, boolean ifNotExists, boolean ifExists, boolean cascade,
                  boolean materialized, String querySql, List<TableFieldSchema> columns) {
            this(kind, statementType, target, datasetTarget, orReplace, ifNotExists, ifExists, cascade,
                    materialized, querySql, columns, null, null, null, null, null);
        }

        Statement(StatementKind kind, String statementType, TableRef target, String datasetTarget,
                  boolean orReplace, boolean ifNotExists, boolean ifExists, boolean cascade,
                  boolean materialized, String querySql, List<TableFieldSchema> columns,
                  Map<String, Object> options) {
            this(kind, statementType, target, datasetTarget, orReplace, ifNotExists, ifExists, cascade,
                    materialized, querySql, columns, options, null, null, null, null);
        }

        Statement(StatementKind kind, String statementType, TableRef target, String datasetTarget,
                  boolean orReplace, boolean ifNotExists, boolean ifExists, boolean cascade,
                  boolean materialized, String querySql, List<TableFieldSchema> columns,
                  Map<String, Object> timePartitioning, Map<String, Object> rangePartitioning,
                  PartitionExpression partitionExpression) {
            this(kind, statementType, target, datasetTarget, orReplace, ifNotExists, ifExists, cascade,
                    materialized, querySql, columns, null, null, timePartitioning, rangePartitioning,
                    partitionExpression);
        }

        Statement(StatementKind kind, String statementType, TableRef target, String datasetTarget,
                  boolean orReplace, boolean ifNotExists, boolean ifExists, boolean cascade,
                  boolean materialized, String querySql, List<TableFieldSchema> columns,
                  TableRef copySource,
                  Map<String, Object> timePartitioning, Map<String, Object> rangePartitioning,
                  PartitionExpression partitionExpression) {
            this(kind, statementType, target, datasetTarget, orReplace, ifNotExists, ifExists, cascade,
                    materialized, querySql, columns, null, copySource, timePartitioning, rangePartitioning,
                    partitionExpression);
        }

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
        return translateDml(sql, projectId, defaultDatasetId, parameters, ref -> null);
    }

    /**
     * As {@link #translateDml(String, String, String, QueryParameters)}, with {@code columnsOf} giving a
     * table's lower-cased column names (or null when unknown), which {@code MERGE} uses to tell source
     * columns from other names.
     */
    static Translation translateDml(String sql, String projectId, String defaultDatasetId,
                                    QueryParameters parameters, Function<TableRef, Set<String>> columnsOf) {
        SqlDialectTranslator translator = new SqlDialectTranslator(projectId, defaultDatasetId, parameters);
        translator.columnsOf = columnsOf;
        return translator.runDml(sql);
    }

    private void prepare(String sql) {
        if (sql == null || sql.isBlank()) {
            throw invalidQuery("Syntax error: Unexpected end of script");
        }
        tokens = new Lexer(sql).tokenize();
        stripTrailingSemicolons();
        rejectScripts();
        quoteOffsetIdentifiers();
        parenthesizeIntervalValues();
    }

    /**
     * GoogleSQL array subscripts are 0-based ({@code arr[OFFSET(i)]}) or 1-based ({@code arr[ORDINAL(i)]}).
     * The plain forms fail on an index outside the array, the {@code SAFE_} forms return NULL. A bare
     * {@code x[i]} is left as it is: GoogleSQL's JSON subscript operator has that form too
     * ({@code json[0]}, {@code json['field']}), and a JSON value cannot be told from an array by its tokens. DuckDB lists are 1-based, return NULL out of range and count a negative index from
     * the end, so every subscript is rewritten to a CASE over the array and the index: a {@code SAFE_} form
     * is NULL out of range, a plain form raises BigQuery's error. The bounds are checked before the index is
     * moved to DuckDB's 1-based one, so the largest INT64 is out of range rather than an overflow. The array
     * and the index are bound once, as the fields of a struct a one-element {@code list_transform} reads, so
     * each is written (and each positional parameter consumed) once and in its own order, and a chain of
     * subscripts grows linearly. The brackets written here are RAW tokens, so a rewritten subscript is not
     * seen again; scanning resumes at the array, so nested ones are too.
     * Only the DuckDB renderings run it, not {@link #prepare}: a CREATE TABLE/VIEW keeps its query as
     * GoogleSQL text and translates it again when it runs, and this rewrite is not idempotent.
     */
    private void rewriteArraySubscripts() {
        int i = 0;
        while (i < tokens.size()) {
            Token t = tokens.get(i);
            int previous = previousSignificantBefore(i);
            if (!t.isPunct("[") || previous < 0 || !endsArrayOperand(tokens.get(previous))) {
                i++;
                continue;
            }
            int close = matchingArraySubscriptBracket(i);
            int first = nextSignificant(i + 1, close);
            String accessor = first >= 0 ? tokens.get(first).upper() : "";
            boolean wrapped = Set.of("OFFSET", "SAFE_OFFSET", "ORDINAL", "SAFE_ORDINAL").contains(accessor)
                    && tokens.get(first).kind == Kind.IDENT;
            if (!wrapped) {
                i++;
                continue;
            }
            int open = nextSignificant(first + 1, close);
            if (open < 0 || !tokens.get(open).isPunct("(") || nextSignificant(matchingParen(open) + 1, close) >= 0) {
                throw invalidQuery("Syntax error: Expected \")\" after " + accessor + " in an array subscript");
            }
            int indexFrom = open + 1;
            int indexTo = matchingParen(open);
            List<Token> index = new ArrayList<>(tokens.subList(indexFrom, indexTo));
            int ordinalBase = accessor.endsWith("ORDINAL") ? 1 : 0;
            int start = arrayOperandStart(previous);
            List<Token> array = new ArrayList<>(tokens.subList(start, i));
            String bound = ordinalBase == 0 ? "" : " + 1";
            String position = ordinalBase == 0 ? "_s.i + 1" : "_s.i";
            String body = accessor.startsWith("SAFE_")
                    ? "CASE WHEN _s.i >= " + ordinalBase + " AND _s.i < len(_s.a)" + bound + " THEN _s.a[" + position + "] END"
                    : "CASE WHEN _s.i IS NULL OR _s.a IS NULL THEN NULL WHEN _s.i < " + ordinalBase
                            + " THEN error('Array index ' || CAST(_s.i AS VARCHAR) || ' is out of bounds (underflow)')"
                            + " WHEN _s.i >= len(_s.a)" + bound
                            + " THEN error('Array index ' || CAST(_s.i AS VARCHAR) || ' is out of bounds (overflow)')"
                            + " ELSE _s.a[" + position + "] END";
            List<Token> replacement = new ArrayList<>();
            replacement.add(new Token(Kind.PUNCT, "(", "("));
            replacement.add(Token.raw("list_transform([struct_pack(a := "));
            replacement.addAll(array);
            replacement.add(Token.raw(", i := "));
            replacement.addAll(index);
            replacement.add(Token.raw(")], lambda _s: " + body + ")[1]"));
            replacement.add(new Token(Kind.PUNCT, ")", ")"));
            tokens.subList(start, close + 1).clear();
            tokens.addAll(start, replacement);
            i = start;
        }
    }

    /** True when {@code t} can end an array value, so a {@code [} right after it is a subscript. */
    private static boolean endsArrayOperand(Token t) {
        return t.kind == Kind.QIDENT || t.kind == Kind.NAMED_PARAM || t.kind == Kind.POSITIONAL_PARAM
                || t.isPunct(")") || t.isPunct("]")
                || (t.kind == Kind.IDENT && !GOOGLESQL_RESERVED.contains(t.upper())
                        && !NON_ALIAS_KEYWORDS.contains(t.upper()));
    }

    /**
     * First token of the array a subscript applies to, given its last token: a dotted path, a function
     * call (with its {@code OVER} clause), a parenthesized expression, an array literal or another
     * subscript, and any field access after a call, a parenthesized expression or a subscript.
     */
    private int arrayOperandStart(int last) {
        Token t = tokens.get(last);
        int start;
        if (t.isPunct(")")) {
            start = matchingOpen(last, "(", ")");
            int name = previousSignificantBefore(start);
            if (name >= 0 && tokens.get(name).isKeyword("OVER")) {
                int call = previousSignificantBefore(name);
                return call >= 0 && tokens.get(call).isPunct(")") ? arrayOperandStart(call) : start;
            }
            if (name >= 0 && tokens.get(name).kind == Kind.IDENT && (endsArrayOperand(tokens.get(name))
                    || Set.of("IF", "CAST", "EXTRACT", "ARRAY").contains(tokens.get(name).upper()))) {
                start = name;
            }
        } else if (t.isPunct("]")) {
            start = matchingOpen(last, "[", "]");
            int before = previousSignificantBefore(start);
            if (before >= 0 && tokens.get(before).isKeyword("ARRAY")) {
                return before;
            }
            if (before >= 0 && endsArrayOperand(tokens.get(before))) {
                return arrayOperandStart(before);
            }
            return start;
        } else {
            start = last;
            int over = previousSignificantBefore(last);
            if (over >= 0 && tokens.get(over).isKeyword("OVER")) {
                int call = previousSignificantBefore(over);
                return call >= 0 && tokens.get(call).isPunct(")") ? arrayOperandStart(call) : start;
            }
        }
        int dot = previousSignificantBefore(start);
        while (dot >= 0 && tokens.get(dot).isPunct(".")) {
            int name = previousSignificantBefore(dot);
            if (name >= 0 && (tokens.get(name).isPunct(")") || tokens.get(name).isPunct("]"))) {
                return arrayOperandStart(name);
            }
            if (name < 0 || !(tokens.get(name).kind == Kind.IDENT || tokens.get(name).kind == Kind.QIDENT)) {
                break;
            }
            start = name;
            dot = previousSignificantBefore(start);
        }
        return start;
    }

    private int previousSignificantBefore(int before) {
        int k = before - 1;
        while (k >= 0 && tokens.get(k).kind == Kind.SPACE) {
            k--;
        }
        return k;
    }

    private int matchingArraySubscriptBracket(int open) {
        int depth = 0;
        for (int k = open; k < tokens.size(); k++) {
            if (tokens.get(k).isPunct("[")) {
                depth++;
            } else if (tokens.get(k).isPunct("]") && --depth == 0) {
                return k;
            }
        }
        throw invalidQuery("Syntax error: Unclosed \"[\"");
    }

    private int matchingOpen(int close, String openText, String closeText) {
        int depth = 0;
        for (int k = close; k >= 0; k--) {
            if (tokens.get(k).isPunct(closeText)) {
                depth++;
            } else if (tokens.get(k).isPunct(openText) && --depth == 0) {
                return k;
            }
        }
        throw invalidQuery("Syntax error: Unexpected \"" + closeText + "\"");
    }

    /**
     * GoogleSQL does not reserve OFFSET, so {@code offset} is an ordinary name (a column, or an alias as
     * in {@code SELECT 1 offset}), while DuckDB reserves it. Every {@code offset} that is not the
     * {@code LIMIT … OFFSET} clause, {@code WITH OFFSET} or the {@code OFFSET(n)} array subscript
     * becomes a quoted identifier.
     */
    /** {@code WITH offset AS (...)} declares a CTE named offset; {@code WITH OFFSET [AS o]} is the clause. */
    private boolean startsCte(int afterName) {
        if (afterName < 0 || !tokens.get(afterName).isKeyword("AS")) {
            return false;
        }
        int open = nextSignificant(afterName + 1, tokens.size());
        return open >= 0 && tokens.get(open).isPunct("(");
    }

    private void quoteOffsetIdentifiers() {
        Deque<Boolean> limitSeenOuter = new ArrayDeque<>();
        boolean limitSeen = false;
        Token previous = null;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind == Kind.SPACE) {
                continue;
            }
            if (t.isPunct("(") || t.isPunct("[")) {
                limitSeenOuter.push(limitSeen);
                limitSeen = false;
            } else if (t.isPunct(")") || t.isPunct("]")) {
                limitSeen = !limitSeenOuter.isEmpty() && limitSeenOuter.pop();
            } else if (t.isKeyword("SELECT")) {
                limitSeen = false;
            } else if (t.isKeyword("LIMIT")) {
                limitSeen = true;
            } else if (t.isKeyword("OFFSET")) {
                int next = nextSignificant(i + 1, tokens.size());
                boolean keyword = limitSeen || (previous != null && previous.isKeyword("WITH") && !startsCte(next))
                        || (next >= 0 && tokens.get(next).isPunct("("));
                if (!keyword) {
                    tokens.set(i, new Token(Kind.QIDENT, "`" + t.text + "`", t.text));
                }
            }
            previous = t;
        }
    }

    /**
     * GoogleSQL takes any INT64 expression as an interval's step size ({@code INTERVAL -5 DAY},
     * {@code INTERVAL n * 2 HOUR}); DuckDB only takes a bare literal there, so anything else is
     * wrapped in parentheses: {@code INTERVAL (-5) DAY}.
     */
    private void parenthesizeIntervalValues() {
        List<Integer> sig = significantIndexes();
        List<Token> sigTokens = new ArrayList<>(sig.size());
        for (int index : sig) {
            sigTokens.add(tokens.get(index));
        }
        // Insertions are collected first and applied from the back, so a nested interval that also
        // needs parentheses does not shift the positions computed for the outer one.
        List<int[]> inserts = new ArrayList<>();
        for (int k = 0; k < sigTokens.size(); k++) {
            if (!sigTokens.get(k).isKeyword("INTERVAL")) {
                continue;
            }
            int end = intervalEnd(sigTokens, k);
            if (end < 0) {
                continue;
            }
            int part = sigTokens.get(end - 1).isKeyword("TO") ? end - 2 : end;
            int first = k + 1;
            int last = part - 1;
            Token value = sigTokens.get(first);
            boolean literal = first == last && (value.kind == Kind.NUMBER || value.kind == Kind.STRING);
            boolean grouped = value.isPunct("(") && closingParen(sigTokens, first) == last;
            if (literal || grouped) {
                continue;
            }
            inserts.add(new int[] {sig.get(first), 0});
            inserts.add(new int[] {sig.get(last) + 1, 1});
        }
        inserts.sort((a, b) -> a[0] != b[0] ? Integer.compare(b[0], a[0]) : Integer.compare(a[1], b[1]));
        for (int[] insert : inserts) {
            String paren = insert[1] == 0 ? "(" : ")";
            tokens.add(insert[0], new Token(Kind.PUNCT, paren, paren));
        }
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
            case "ALTER" -> classifyAlter(sig);
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
                        orReplace, ifNotExists, false, false, false, null, null, null, null, null);
            }
            case "TABLE" -> {
                TableRef target = resolveTable(path.segments());
                int copy = nextSignificant(path.end(), tokens.size());
                if (copy >= 0 && tokens.get(copy).isKeyword("COPY")) {
                    int sourceAt = sig.indexOf(copy) + 1;
                    Path source = pathAt(sig, sourceAt);
                    if (nextSignificant(source.end(), tokens.size()) >= 0) {
                        throw invalidQuery("Syntax error: Unexpected tokens after COPY source table.");
                    }
                    return new Statement(StatementKind.CREATE_TABLE, "CREATE_TABLE_COPY", target, null,
                            orReplace, ifNotExists, false, false, false, null, null,
                            resolveTable(source.segments()), null, null, null);
                }
                int asIndex = topLevelAsSelect(path.end());
                if (asIndex >= 0) {
                    PartitionSpec partitioning = createTablePartitioning(path.end(), asIndex);
                    if (partitioning != null && partitioning.timeMetadata() != null
                            && !partitioning.timeMetadata().containsKey("field")) {
                        throw invalidQuery("CREATE TABLE AS SELECT does not support ingestion-time partitioning");
                    }
                    return new Statement(StatementKind.CREATE_TABLE, "CREATE_TABLE_AS_SELECT", target, null,
                            orReplace, ifNotExists, false, false, false, text(asIndex + 1, tokens.size()), null,
                            partitioning != null ? partitioning.timeMetadata() : null,
                            partitioning != null ? partitioning.rangeMetadata() : null,
                            partitioning != null ? partitioning.expression() : null);
                }
                int open = nextSignificant(path.end(), tokens.size());
                if (open < 0 || !tokens.get(open).isPunct("(")) {
                    throw invalidQuery("CREATE TABLE needs a column list, AS SELECT, or COPY source_table");
                }
                List<TableFieldSchema> columns = columnDefinitions(open, matchingParen(open));
                PartitionSpec partitioning = createTablePartitioning(matchingParen(open) + 1, tokens.size());
                return new Statement(StatementKind.CREATE_TABLE, "CREATE_TABLE", target, null, orReplace,
                        ifNotExists, false, false, false, null, columns,
                        partitioning != null ? partitioning.timeMetadata() : null,
                        partitioning != null ? partitioning.rangeMetadata() : null,
                        partitioning != null ? partitioning.expression() : null);
            }
            case "VIEW" -> {
                TableRef target = resolveTable(path.segments());
                int asIndex = topLevelAsSelect(path.end());
                if (asIndex < 0) {
                    throw invalidQuery("CREATE VIEW needs AS followed by a query");
                }
                return new Statement(StatementKind.CREATE_VIEW,
                        materialized ? "CREATE_MATERIALIZED_VIEW" : "CREATE_VIEW", target, null, orReplace,
                        ifNotExists, false, false, materialized, text(asIndex + 1, tokens.size()).trim(), null, null,
                        null, null);
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
        // CASCADE and RESTRICT belong to DROP SCHEMA only. Anything else trailing the target is a
        // syntax error. Ignoring it would let a typo such as "DROP TABLE ds.t GARBAGE" through,
        // and DROP runs before anyone sees the mistake.
        boolean schema = object.equals("SCHEMA");
        boolean cascade = schema && after >= 0 && tokens.get(after).isKeyword("CASCADE");
        int trailing = cascade || (schema && after >= 0 && tokens.get(after).isKeyword("RESTRICT"))
                ? nextSignificant(after + 1, tokens.size()) : after;
        if (trailing >= 0) {
            throw invalidQuery("Syntax error: Unexpected \"" + tokens.get(trailing).text + "\"");
        }
        return switch (object) {
            case "SCHEMA" -> new Statement(StatementKind.DROP_SCHEMA, "DROP_SCHEMA", null, datasetOf(path.segments()),
                    false, false, ifExists, cascade, false, null, null, null, null, null);
            case "TABLE" -> new Statement(StatementKind.DROP_TABLE, "DROP_TABLE", resolveTable(path.segments()), null,
                    false, false, ifExists, false, false, null, null, null, null, null);
            case "VIEW" -> new Statement(StatementKind.DROP_VIEW,
                    materialized ? "DROP_MATERIALIZED_VIEW" : "DROP_VIEW", resolveTable(path.segments()), null,
                    false, false, ifExists, false, materialized, null, null, null, null, null);
            default -> throw invalidQuery("DROP " + object + " is not supported by the floci BigQuery emulator yet.");
        };
    }

    /** Options {@code ALTER TABLE/VIEW ... SET OPTIONS} applies; BigQuery's other ones are rejected as unsupported. */
    private static final Set<String> ALTERABLE_OPTIONS = Set.of("description", "friendly_name", "labels",
            "expiration_timestamp");

    private static final Set<String> UNSUPPORTED_TABLE_OPTIONS = Set.of("partition_expiration_days",
            "require_partition_filter", "kms_key_name", "default_rounding_mode", "enable_change_history",
            "max_staleness", "enable_fine_grained_mutations", "storage_uri", "file_format", "table_format",
            "tags", "privacy_policy");

    /** {@code ALTER {TABLE|VIEW} [IF EXISTS] name SET OPTIONS (name = value, ...)}. */
    private Statement classifyAlter(List<Integer> sig) {
        String object = sig.size() > 1 ? tokens.get(sig.get(1)).upper() : "";
        if (!object.equals("TABLE") && !object.equals("VIEW")) {
            throw invalidQuery("ALTER " + object + " is not supported by the floci BigQuery emulator yet.");
        }
        int at = 2;
        boolean ifExists = false;
        if (keywordAt(sig, at, "IF")) {
            expectKeyword(sig, at + 1, "EXISTS");
            ifExists = true;
            at += 2;
        }
        Path path = pathAt(sig, at);
        TableRef target = resolveTable(path.segments());
        int set = nextSignificant(path.end(), tokens.size());
        int options = set < 0 ? -1 : nextSignificant(set + 1, tokens.size());
        if (set < 0 || !tokens.get(set).isKeyword("SET") || options < 0 || !tokens.get(options).isKeyword("OPTIONS")) {
            throw invalidQuery("ALTER " + object + " is only supported with SET OPTIONS by the floci BigQuery"
                    + " emulator yet.");
        }
        int open = nextSignificant(options + 1, tokens.size());
        if (open < 0 || !tokens.get(open).isPunct("(")) {
            throw invalidQuery("Syntax error: Expected \"(\" after OPTIONS");
        }
        int close = matchingParen(open);
        int trailing = nextSignificant(close + 1, tokens.size());
        if (trailing >= 0) {
            throw invalidQuery("Syntax error: Unexpected \"" + tokens.get(trailing).text + "\"");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        int depth = 0;
        int start = open + 1;
        for (int k = open + 1; k <= close; k++) {
            Token t = tokens.get(k);
            if (k == close || (depth == 0 && t.isPunct(","))) {
                List<Token> item = significant(start, k);
                if (!item.isEmpty()) {
                    readOption(item, values);
                }
                start = k + 1;
            } else if (t.isPunct("(") || t.isPunct("[")) {
                depth++;
            } else if (t.isPunct(")") || t.isPunct("]")) {
                depth--;
            }
        }
        return new Statement(object.equals("TABLE") ? StatementKind.ALTER_TABLE : StatementKind.ALTER_VIEW,
                "ALTER_" + object, target, null, false, false, ifExists, false, false, null, null, values);
    }

    private static void readOption(List<Token> item, Map<String, Object> values) {
        if (item.size() < 3 || item.get(0).kind != Kind.IDENT || !item.get(1).isPunct("=")) {
            throw invalidQuery("Syntax error: Expected an option assignment in OPTIONS");
        }
        String name = item.get(0).text.toLowerCase(Locale.ROOT);
        if (UNSUPPORTED_TABLE_OPTIONS.contains(name)) {
            throw invalidQuery("Option " + name + " is not supported by the floci BigQuery emulator yet.");
        }
        if (!ALTERABLE_OPTIONS.contains(name)) {
            throw invalidQuery("Unknown option: " + name);
        }
        List<Token> value = item.subList(2, item.size());
        if (value.size() == 1 && value.getFirst().isKeyword("NULL")) {
            values.put(name, null);
            return;
        }
        switch (name) {
            case "description", "friendly_name" -> values.put(name, stringOption(name, value));
            case "labels" -> values.put(name, labelsOption(value));
            default -> values.put(name, timestampOption(value));
        }
    }

    private static String stringOption(String name, List<Token> value) {
        if (value.size() == 1 && value.getFirst().kind == Kind.STRING) {
            return value.getFirst().value;
        }
        throw invalidQuery("Option " + name + " value has type " + literalType(value)
                + " which cannot be coerced to expected type STRING");
    }

    /** The GoogleSQL type of a single literal, for BigQuery's coercion error. */
    private static String literalType(List<Token> value) {
        if (value.size() == 1) {
            Token t = value.getFirst();
            if (t.kind == Kind.NUMBER) {
                return t.text.contains(".") || t.text.toLowerCase(Locale.ROOT).contains("e") ? "FLOAT64" : "INT64";
            }
            if (t.isKeyword("TRUE") || t.isKeyword("FALSE")) {
                return "BOOL";
            }
            if (t.kind == Kind.BYTES) {
                return "BYTES";
            }
        }
        return "an expression the floci BigQuery emulator does not evaluate in OPTIONS";
    }

    /** {@code [('key', 'value'), ...]} or {@code [STRUCT('key' AS key, 'value' AS value), ...]}. */
    private static Map<String, String> labelsOption(List<Token> value) {
        if (value.isEmpty() || !value.getFirst().isPunct("[") || !value.getLast().isPunct("]")) {
            throw invalidQuery("Option labels value must be an ARRAY<STRUCT<STRING, STRING>> literal");
        }
        Map<String, String> labels = new LinkedHashMap<>();
        List<String> pair = new ArrayList<>();
        boolean afterTuple = false;
        for (int k = 1; k < value.size() - 1; k++) {
            Token t = value.get(k);
            boolean tupleStart = t.isKeyword("STRUCT") || (t.isPunct("(") && !value.get(k - 1).isKeyword("STRUCT"));
            if (tupleStart && afterTuple) {
                throw invalidQuery("Option labels value must be an ARRAY<STRUCT<STRING, STRING>> literal");
            }
            if (t.kind == Kind.STRING) {
                pair.add(t.value);
            } else if (t.isPunct(")")) {
                if (pair.size() != 2) {
                    throw invalidQuery("Option labels value must be an ARRAY<STRUCT<STRING, STRING>> literal");
                }
                labels.put(pair.get(0), pair.get(1));
                pair.clear();
                afterTuple = true;
            } else if (t.isPunct(",") && pair.isEmpty() && !tupleOpen(value, k)) {
                if (!afterTuple) {
                    throw invalidQuery("Option labels value must be an ARRAY<STRUCT<STRING, STRING>> literal");
                }
                afterTuple = false;
            } else if (!(t.isPunct("(") || t.isPunct(",") || t.isKeyword("STRUCT") || t.isKeyword("AS")
                    || (t.kind == Kind.IDENT && k > 0 && value.get(k - 1).isKeyword("AS")))) {
                throw invalidQuery("Option labels value must be an ARRAY<STRUCT<STRING, STRING>> literal");
            }
        }
        if (!pair.isEmpty() || (!labels.isEmpty() && !afterTuple)) {
            throw invalidQuery("Option labels value must be an ARRAY<STRUCT<STRING, STRING>> literal");
        }
        return labels;
    }

    /** Whether the token at {@code index} sits inside a label tuple's parentheses. */
    private static boolean tupleOpen(List<Token> value, int index) {
        int depth = 0;
        for (int k = 1; k < index; k++) {
            if (value.get(k).isPunct("(")) {
                depth++;
            } else if (value.get(k).isPunct(")")) {
                depth--;
            }
        }
        return depth > 0;
    }

    /** {@code TIMESTAMP '...'} (or a plain string) as epoch milliseconds. */
    private static Long timestampOption(List<Token> value) {
        Token literal = value.size() == 2 && value.getFirst().isKeyword("TIMESTAMP") ? value.get(1)
                : value.size() == 1 ? value.getFirst() : null;
        if (literal == null || literal.kind != Kind.STRING) {
            throw invalidQuery("Option expiration_timestamp value has type " + literalType(value)
                    + " which cannot be coerced to expected type TIMESTAMP");
        }
        String text = literal.value.trim();
        if (text.endsWith(" UTC")) {
            text = text.substring(0, text.length() - 4);
        }
        if (text.matches("-?\\d{4,}-\\d{2}-\\d{2}")) {
            text = text + " 00:00:00";
        }
        try {
            return new BigDecimal(DuckTypes.timestampTextToSeconds(text)).movePointRight(3).longValue();
        } catch (NumberFormatException | DateTimeException | ArithmeticException e) {
            String reason = value.size() == 2 ? "Invalid TIMESTAMP literal"
                    : "Could not cast literal \"" + literal.value + "\" to type TIMESTAMP";
            throw invalidQuery(reason + "; failed to set 'expiration_timestamp' in OPTIONS()");
        }
    }

    private Statement statement(StatementKind kind, String statementType, TableRef target) {
        return new Statement(kind, statementType, target, null, false, false, false, false, false, null, null, null,
                null, null);
    }

    /** Extracts BigQuery time or integer-range partitioning metadata from a CREATE TABLE suffix. */
    private PartitionSpec createTablePartitioning(int from, int to) {
        int depth = 0;
        int partition = -1;
        for (int i = from; i < to; i++) {
            Token token = tokens.get(i);
            if (token.isPunct("(")) {
                depth++;
            } else if (token.isPunct(")")) {
                depth--;
            } else if (depth == 0 && token.isKeyword("PARTITION")) {
                int by = nextSignificant(i + 1, to);
                if (by >= 0 && tokens.get(by).isKeyword("BY")) {
                    partition = nextSignificant(by + 1, to);
                    break;
                }
            }
        }
        if (partition < 0) {
            return null;
        }

        depth = 0;
        int end = to;
        for (int i = partition; i < to; i++) {
            Token token = tokens.get(i);
            if (token.isPunct("(")) {
                depth++;
            } else if (token.isPunct(")")) {
                depth--;
            } else if (depth == 0 && (token.isKeyword("CLUSTER") || token.isKeyword("OPTIONS")
                    || token.isKeyword("AS"))) {
                end = i;
                break;
            }
        }
        String expression = tokens.subList(partition, end).stream()
                .filter(token -> token.kind != Kind.SPACE).map(token -> token.text).collect(Collectors.joining());
        if (expression.equalsIgnoreCase("_PARTITIONDATE")
                || expression.equalsIgnoreCase("DATE(_PARTITIONTIME)")) {
            return new PartitionSpec(Map.of("type", "DAY"), null, PartitionExpression.INGESTION_TIME);
        }

        String identifier = "(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)";
        Matcher range = Pattern.compile(
                "(?i)RANGE_BUCKET\\(" + identifier + ","
                        + "GENERATE_ARRAY\\((-?[0-9]+),(-?[0-9]+)(?:,(-?[0-9]+))?\\)\\)")
                .matcher(expression);
        if (range.matches()) {
            String interval = range.group(4) != null ? range.group(4) : "1";
            Map<String, Object> bounds = Map.of(
                    "start", range.group(2), "end", range.group(3), "interval", interval);
            return new PartitionSpec(null, Map.of(
                    "field", unquoteIdentifier(range.group(1)), "range", bounds), PartitionExpression.RANGE_BUCKET);
        }

        Matcher truncated = Pattern.compile(
                "(?i)(TIMESTAMP|DATETIME|DATE)_TRUNC\\(" + identifier + ",(HOUR|DAY|MONTH|YEAR)\\)")
                .matcher(expression);
        if (truncated.matches()) {
            String function = truncated.group(1).toUpperCase(Locale.ROOT);
            String field = unquoteIdentifier(truncated.group(2));
            String type = truncated.group(3).toUpperCase(Locale.ROOT);
            PartitionExpression expressionKind = PartitionExpression.valueOf(function + "_TRUNC");
            if (expressionKind == PartitionExpression.DATE_TRUNC
                    && ("HOUR".equals(type) || "DAY".equals(type))) {
                throw invalidQuery("DATE_TRUNC partitioning supports MONTH or YEAR granularity");
            }
            if (field.equalsIgnoreCase(BigQueryService.PARTITION_TIME)) {
                if (expressionKind != PartitionExpression.TIMESTAMP_TRUNC) {
                    throw invalidQuery("_PARTITIONTIME must use TIMESTAMP_TRUNC");
                }
                return new PartitionSpec(Map.of("type", type), null, PartitionExpression.INGESTION_TIME);
            }
            return new PartitionSpec(Map.of("type", type, "field", field), null, expressionKind);
        }
        Matcher date = Pattern.compile("(?i)DATE\\(" + identifier + "\\)")
                .matcher(expression);
        if (date.matches()) {
            return new PartitionSpec(Map.of("type", "DAY", "field", unquoteIdentifier(date.group(1))), null,
                    PartitionExpression.DATE);
        }
        if (expression.matches("(?i)" + identifier)) {
            return new PartitionSpec(Map.of("type", "DAY", "field", unquoteIdentifier(expression)), null,
                    PartitionExpression.BARE_DATE);
        }
        throw invalidQuery("Unsupported PARTITION BY expression in CREATE TABLE: " + expression);
    }

    private static String unquoteIdentifier(String identifier) {
        return identifier.startsWith("`") && identifier.endsWith("`")
                ? identifier.substring(1, identifier.length() - 1).replace("``", "`") : identifier;
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
        bindPositionalParameters();
        collectCteNames();
        rewriteTypedArrayLiterals();
        rewriteArraySubscripts();
        rewriteSelectAsStruct();
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
        String targetAlias = target.tableId();
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
                targetAlias = tokens.get(aliasIndex).identifierText();
                rest = aliasIndex + 1;
            }
        }
        if (first.equals("MERGE")) {
            qualifyNotMatchedSourceColumns(rest, target, targetAlias);
        }
        out.append(render(rest, tokens.size()));
        if (first.equals("MERGE")) {
            out.append(" RETURNING merge_action");
        }
        return new Translation(out.toString().trim(), tables, informationSchema, needsUnicodeCaseFold);
    }

    /**
     * A {@code WHEN NOT MATCHED [BY TARGET]} clause has no target row, so GoogleSQL resolves a bare column
     * in its search condition or {@code INSERT ... VALUES} to the source; dbt-bigquery's incremental
     * MERGE relies on it ({@code INSERT (`a`) VALUES (`a`)}). DuckDB resolves those names against both
     * tables and rejects them as ambiguous, so they are qualified with the source alias here. When the
     * target's columns are known, only names that are also target columns are qualified: no other name
     * can be ambiguous.
     */
    private void qualifyNotMatchedSourceColumns(int from, TableRef target, String targetAlias) {
        String source = mergeSourceAlias(from);
        String correlationSource = source != null ? source : mergeUnnestAlias;
        if (correlationSource == null) {
            return;
        }
        Set<String> targetColumns = columnsOf.apply(target);
        Set<String> sourceColumns = mergeSource == null ? null : columnsOf.apply(mergeSource);
        Set<String> outerColumns = null;
        if (targetColumns != null || sourceColumns != null) {
            outerColumns = new HashSet<>();
            outerColumns.addAll(targetColumns != null ? targetColumns : Set.of());
            outerColumns.addAll(sourceColumns != null ? sourceColumns : Set.of());
        }
        mergeScope = new MergeScope(new HashSet<>(List.of(correlationSource.toLowerCase(Locale.ROOT),
                targetAlias.toLowerCase(Locale.ROOT), target.tableId().toLowerCase(Locale.ROOT))), outerColumns,
                targetColumns != null && sourceColumns != null);
        List<Integer> columns = new ArrayList<>();
        int depth = 0;
        int caseDepth = 0;
        int clauseStart = -1;
        for (int i = from; i <= tokens.size(); i++) {
            Token t = i < tokens.size() ? tokens.get(i) : null;
            boolean clauseEnds = t == null || (depth == 0 && caseDepth == 0 && t.isKeyword("WHEN"));
            if (clauseEnds && clauseStart >= 0) {
                collectBareColumns(clauseStart, i, columns);
                clauseStart = -1;
            }
            if (t == null) {
                break;
            }
            if (t.isPunct("(")) {
                depth++;
            } else if (t.isPunct(")")) {
                depth--;
            } else if (t.isKeyword("CASE")) {
                caseDepth++;
            } else if (t.isKeyword("END") && caseDepth > 0) {
                caseDepth--;
            } else if (depth == 0 && caseDepth == 0 && t.isKeyword("WHEN")) {
                clauseStart = notMatchedByTargetBody(i);
            }
        }
        mergeScope = null;
        if (source == null) {
            return;
        }
        for (int k = columns.size() - 1; k >= 0; k--) {
            int at = columns.get(k);
            if (targetColumns != null
                    && !targetColumns.contains(tokens.get(at).identifierText().toLowerCase(Locale.ROOT))) {
                continue;
            }
            tokens.add(at, new Token(Kind.PUNCT, ".", "."));
            tokens.add(at, new Token(Kind.QIDENT, "`" + source + "`", source));
        }
    }

    /** Index after {@code WHEN NOT MATCHED [BY TARGET]}, or -1 for any other WHEN clause. */
    private int notMatchedByTargetBody(int when) {
        int not = nextSignificant(when + 1, tokens.size());
        if (not < 0 || !tokens.get(not).isKeyword("NOT")) {
            return -1;
        }
        int matched = nextSignificant(not + 1, tokens.size());
        if (matched < 0 || !tokens.get(matched).isKeyword("MATCHED")) {
            return -1;
        }
        int by = nextSignificant(matched + 1, tokens.size());
        if (by >= 0 && tokens.get(by).isKeyword("BY")) {
            int side = nextSignificant(by + 1, tokens.size());
            return side >= 0 && tokens.get(side).isKeyword("TARGET") ? side + 1 : -1;
        }
        return matched + 1;
    }

    /** The name the MERGE source goes by: its alias, or the table's own name. */
    private String mergeSourceAlias(int from) {
        int using = -1;
        int depth = 0;
        for (int i = from; i < tokens.size() && using < 0; i++) {
            Token t = tokens.get(i);
            if (t.isPunct("(")) {
                depth++;
            } else if (t.isPunct(")")) {
                depth--;
            } else if (depth == 0 && t.isKeyword("USING")) {
                using = i;
            }
        }
        int source = using < 0 ? -1 : nextSignificant(using + 1, tokens.size());
        if (source < 0) {
            return null;
        }
        String name = null;
        int after;
        int unnestOpen = tokens.get(source).isKeyword("UNNEST") ? nextSignificant(source + 1, tokens.size()) : -1;
        if (tokens.get(source).isPunct("(")) {
            after = nextSignificant(matchingParen(source) + 1, tokens.size());
        } else if (unnestOpen >= 0 && tokens.get(unnestOpen).isPunct("(")) {
            after = nextSignificant(matchingParen(unnestOpen) + 1, tokens.size());
            if (after >= 0 && (tokens.get(after).isKeyword("AS") || tokens.get(after).kind == Kind.QIDENT
                    || (tokens.get(after).kind == Kind.IDENT && !tokens.get(after).isKeyword("ON")))) {
                // An aliased UNNEST renders as one STRUCT column, which the qualified names cannot reach.
                int alias = tokens.get(after).isKeyword("AS") ? nextSignificant(after + 1, tokens.size()) : after;
                mergeUnnestAlias = alias >= 0 ? tokens.get(alias).identifierText() : null;
                return null;
            }
            // Without an alias it renders as an unnamed subquery, so it gets a name to qualify with.
            mergeUnnestSource = tokens.get(source);
            mergeUnnestName = unusedName(MERGE_UNNEST_SOURCE);
            return mergeUnnestName;
        } else {
            int last = source;
            List<String> segments = new ArrayList<>(pathSegments(tokens.get(last)));
            int dot = nextSignificant(last + 1, tokens.size());
            while (dot >= 0 && tokens.get(dot).isPunct(".")) {
                last = nextSignificant(dot + 1, tokens.size());
                segments.addAll(pathSegments(tokens.get(last)));
                dot = nextSignificant(last + 1, tokens.size());
            }
            mergeSource = tableRefOrNull(segments);
            String path = tokens.get(last).identifierText();
            name = path.substring(path.lastIndexOf('.') + 1);
            after = dot;
        }
        if (after >= 0 && tokens.get(after).isKeyword("AS")) {
            after = nextSignificant(after + 1, tokens.size());
        }
        if (after >= 0 && (tokens.get(after).kind == Kind.QIDENT
                || (tokens.get(after).kind == Kind.IDENT && !tokens.get(after).isKeyword("ON")))) {
            name = tokens.get(after).identifierText();
        }
        return name;
    }

    /**
     * Bare column names in {@code [from, to)}: an identifier that is not a keyword, a function name, part
     * of a dotted path, a name introduced by AS (a type or field alias), or a word following an operand
     * ({@code DAY} in {@code INTERVAL 1 DAY}). Also not a column: the type of a typed literal
     * ({@code DATE '2026-01-01'}), a date part ({@code EXTRACT(DAY FROM d)}, a part argument of
     * {@code DATE_TRUNC}, {@code DATE_DIFF} and the like, the end of an interval range, the weekday in
     * {@code WEEK(MONDAY)}), a function called without parentheses ({@code CURRENT_DATE}), the
     * {@code TIME ZONE} of {@code AT TIME ZONE}, the name of a named argument ({@code make_valid => TRUE}), and the
     * types inside {@code ARRAY<...>}, {@code STRUCT<...>} and {@code RANGE<...>}. The {@code INSERT}
     * column list names target columns and is skipped, and so is a subquery, whose bare names belong to
     * its own tables.
     */
    private void collectBareColumns(int from, int to, List<Integer> columns) {
        collectBareColumns(from, to, columns, true);
    }

    /**
     * As {@link #collectBareColumns(int, int, List)}; when {@code mergeClause} is false the range is a plain
     * expression, where the MERGE clause words ({@code SOURCE}, {@code INSERT}, ...) are column names.
     */
    private void collectBareColumns(int from, int to, List<Integer> columns, boolean mergeClause) {
        Deque<String> calls = new ArrayDeque<>();
        Deque<int[]> argumentIndexes = new ArrayDeque<>();
        boolean inInsert = false;
        for (int i = from; i < to; i++) {
            Token t = tokens.get(i);
            if (mergeClause && t.isKeyword("INSERT")) {
                inInsert = true;
                int open = nextSignificant(i + 1, to);
                if (open >= 0 && tokens.get(open).isPunct("(")) {
                    i = matchingParen(open);
                }
                continue;
            }
            if (t.isPunct("(")) {
                int first = nextSignificant(i + 1, to);
                if (first >= 0 && (tokens.get(first).isKeyword("SELECT") || tokens.get(first).isKeyword("WITH"))) {
                    int close = matchingParen(i);
                    if (mergeScope != null && isInSubqueryOfAnExpression(i, mergeScope.names(),
                            mergeScope.complete() ? mergeScope.columns() : null)) {
                        throw invalidQuery("Correlated Subquery is unsupported in " + (inInsert ? "INSERT" : "WHEN")
                                + " clause.");
                    }
                    rejectCorrelatedSubquery(i, close, inInsert ? "INSERT" : "WHEN");
                    i = close;
                    continue;
                }
                int callee = previousSignificantIndex(i);
                calls.push(callee >= 0 && tokens.get(callee).kind == Kind.IDENT ? tokens.get(callee).upper() : "");
                argumentIndexes.push(new int[] {0});
                continue;
            }
            if (t.isPunct("[")) {
                calls.push("");
                argumentIndexes.push(new int[] {0});
                continue;
            }
            if (t.isPunct(")") || t.isPunct("]")) {
                calls.poll();
                argumentIndexes.poll();
                continue;
            }
            if (t.isPunct(",") && !argumentIndexes.isEmpty()) {
                argumentIndexes.peek()[0]++;
                continue;
            }
            if (t.isKeyword("ARRAY") || t.isKeyword("STRUCT") || t.isKeyword("RANGE")) {
                int open = nextSignificant(i + 1, to);
                if (open >= 0 && tokens.get(open).isPunct("<")) {
                    i = closingAngle(open, to);
                }
                continue;
            }
            boolean name = t.kind == Kind.QIDENT ? !t.value.contains(".")
                    : t.kind == Kind.IDENT && !GOOGLESQL_RESERVED.contains(t.upper())
                            && !NON_ALIAS_KEYWORDS.contains(t.upper())
                            && !(mergeClause && MERGE_CLAUSE_WORDS.contains(t.upper()));
            if (!name) {
                continue;
            }
            int next = nextSignificant(i + 1, tokens.size());
            Token n = next >= 0 ? tokens.get(next) : null;
            if (n != null && (n.isPunct("(") || n.isPunct(".") || n.isPunct("=>") || n.kind == Kind.STRING
                    || n.kind == Kind.BYTES || n.isKeyword("FROM"))) {
                continue;
            }
            if (t.kind == Kind.IDENT && PARENLESS_FUNCTIONS.contains(t.upper())) {
                continue;
            }
            int previous = previousSignificantIndex(i);
            Token p = previous >= 0 ? tokens.get(previous) : null;
            if (p != null && (p.isPunct(".") || p.isKeyword("AS") || p.isKeyword("AT") || endsMergeOperand(p))) {
                continue;
            }
            if (t.isKeyword("ZONE") && p != null && p.isKeyword("TIME")) {
                continue;
            }
            String call = calls.peek();
            Integer partArgument = call == null ? null : DATE_PART_ARGUMENT.get(call);
            boolean datePartArgument = partArgument != null && argumentIndexes.peek()[0] == partArgument
                    && p != null && p.isPunct(",") && n != null && (n.isPunct(")") || n.isPunct(","));
            boolean intervalRangeEnd = p != null && p.isKeyword("TO") && DATE_PART_NAMES.contains(t.upper());
            if ("WEEK".equals(call) || datePartArgument || intervalRangeEnd) {
                continue;
            }
            columns.add(i);
        }
    }

    /** The base of the name an unaliased {@code MERGE ... USING UNNEST(...)} source is rendered and qualified with. */
    private static final String MERGE_UNNEST_SOURCE = "_merge_source";

    /** {@code base}, or {@code base_1}, {@code base_2}, ... when the statement already uses that name. */
    private String unusedName(String base) {
        Set<String> used = new HashSet<>();
        for (Token t : tokens) {
            if (t.kind == Kind.IDENT || t.kind == Kind.QIDENT) {
                for (String part : t.identifierText().split("\\.", -1)) {
                    used.add(part.toLowerCase(Locale.ROOT));
                }
            }
        }
        String name = base;
        for (int n = 1; used.contains(name); n++) {
            name = base + "_" + n;
        }
        return name;
    }

    /**
     * GoogleSQL rejects a correlated subquery in a {@code WHEN NOT MATCHED} clause ("Correlated Subquery is
     * unsupported in INSERT clause.", or "... in WHEN clause." in the condition), where DuckDB would run it
     * or fail on an ambiguous name. That covers a subquery that reads the target or the source, a
     * subquery nested in it that reads an enclosing subquery, and the subquery of {@code x IN (SELECT ...)}
     * when {@code x} is not a constant, which BigQuery runs as a semi-join correlated through {@code x}.
     * Each query level is checked on its own, against the names its enclosing levels and the MERGE make
     * visible.
     */
    private void rejectCorrelatedSubquery(int open, int close, String clause) {
        MergeScope scope = mergeScope;
        if (scope == null) {
            return;
        }
        mergeScope = null;
        try {
            rejectCorrelatedQuery(open, close, clause, scope.names(), scope.columns());
        } finally {
            mergeScope = scope;
        }
    }

    /**
     * Rejects the query in {@code (open, close)} when it reads an enclosing name: a name qualified with
     * one of {@code outerNames} that none of its own FROM items hides, or, when the columns of all its
     * FROM items are known, a bare name in {@code outerColumns} that is not one of their columns or, from
     * {@code GROUP BY} on, a select-list alias. {@code outerColumns} is null when the enclosing columns
     * are unknown. Then checks
     * each subquery nested in it, with this level's names and columns added to the enclosing ones.
     */
    private void rejectCorrelatedQuery(int open, int close, String clause, Set<String> outerNames,
                                       Set<String> outerColumns) {
        Set<String> localNames = new HashSet<>();
        Set<String> localColumns = new HashSet<>();
        Set<String> aliases = new HashSet<>();
        Set<Integer> fromItems = new HashSet<>();
        List<int[]> nested = new ArrayList<>();
        int first = nextSignificant(open + 1, close);
        boolean known = first < 0 || !tokens.get(first).isKeyword("WITH");
        boolean seenFrom = false;
        boolean inFrom = false;
        int aliasesVisibleFrom = -1;
        int itemStart = -1;
        int depth = 0;
        for (int k = open + 1; k < close; k++) {
            Token t = tokens.get(k);
            if (t.isPunct("(")) {
                int inner = nextSignificant(k + 1, close);
                if (inner >= 0 && (tokens.get(inner).isKeyword("SELECT") || tokens.get(inner).isKeyword("WITH"))) {
                    int end = matchingParen(k);
                    nested.add(new int[] {k, end});
                    k = end;
                } else {
                    depth++;
                }
                continue;
            }
            if (t.isPunct(")")) {
                depth--;
                continue;
            }
            if (depth != 0) {
                continue;
            }
            if (!seenFrom && t.isKeyword("SELECT")) {
                itemStart = k;
                continue;
            }
            if (!seenFrom && itemStart >= 0 && (t.isPunct(",") || t.isKeyword("FROM"))) {
                recordImplicitAlias(itemStart, k, aliases);
                itemStart = t.isPunct(",") ? k : -1;
                if (t.isPunct(",")) {
                    continue;
                }
            }
            if (t.isKeyword("FROM") || t.isKeyword("JOIN") || (inFrom && t.isPunct(","))) {
                seenFrom = true;
                inFrom = true;
                known &= fromItem(nextSignificant(k + 1, close), close, localNames, localColumns, fromItems);
            } else if (t.kind == Kind.IDENT && FROM_CLAUSE_ENDS.contains(t.upper())) {
                inFrom = false;
                if (aliasesVisibleFrom < 0 && ALIAS_CLAUSES.contains(t.upper())) {
                    aliasesVisibleFrom = k;
                }
            } else if (!seenFrom && t.isKeyword("AS")) {
                int alias = nextSignificant(k + 1, close);
                if (alias >= 0 && (tokens.get(alias).kind == Kind.IDENT || tokens.get(alias).kind == Kind.QIDENT)) {
                    aliases.add(tokens.get(alias).identifierText().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (!seenFrom && itemStart >= 0) {
            recordImplicitAlias(itemStart, close, aliases);
        }
        Set<String> visible = new HashSet<>(outerNames);
        visible.removeAll(localNames);
        Set<String> operandNames = new HashSet<>(outerNames);
        operandNames.addAll(localNames);
        Set<String> enclosingColumns = null;
        if (outerColumns != null && known) {
            enclosingColumns = new HashSet<>(outerColumns);
            enclosingColumns.addAll(localColumns);
        }
        for (int[] sub : nested) {
            if (isInSubqueryOfAnExpression(sub[0], operandNames, null)) {
                throw invalidQuery("Correlated Subquery is unsupported in " + clause + " clause.");
            }
        }
        int nextNested = 0;
        for (int k = open + 1; k < close; k++) {
            if (nextNested < nested.size() && k == nested.get(nextNested)[0]) {
                k = nested.get(nextNested++)[1];
                continue;
            }
            Token t = tokens.get(k);
            if (fromItems.contains(k) || (t.kind != Kind.IDENT && t.kind != Kind.QIDENT)) {
                continue;
            }
            int previous = previousSignificantIndex(k);
            if (previous >= 0 && tokens.get(previous).isPunct(".")) {
                continue;
            }
            String qualifier = null;
            if (t.kind == Kind.QIDENT && t.value.contains(".")) {
                qualifier = t.value.substring(0, t.value.indexOf('.'));
            } else {
                int next = nextSignificant(k + 1, close);
                if (next >= 0 && tokens.get(next).isPunct(".")) {
                    qualifier = t.identifierText();
                }
            }
            if (qualifier != null && visible.contains(qualifier.toLowerCase(Locale.ROOT))) {
                throw invalidQuery("Correlated Subquery is unsupported in " + clause + " clause.");
            }
        }
        if (known && outerColumns != null) {
            List<Integer> bare = new ArrayList<>();
            collectBareColumns(open + 1, close, bare);
            for (int k : bare) {
                String name = tokens.get(k).identifierText().toLowerCase(Locale.ROOT);
                boolean alias = aliases.contains(name) && aliasesVisibleFrom >= 0 && k > aliasesVisibleFrom;
                if (!fromItems.contains(k) && outerColumns.contains(name) && !localColumns.contains(name) && !alias) {
                    throw invalidQuery("Correlated Subquery is unsupported in " + clause + " clause.");
                }
            }
        }
        visible.addAll(localNames);
        for (int[] sub : nested) {
            rejectCorrelatedQuery(sub[0], sub[1], clause, visible, enclosingColumns);
        }
    }

    /**
     * Whether the subquery opening at {@code open} is the right side of {@code x [NOT] IN} where {@code x}
     * reads an enclosing row, which BigQuery runs as a semi-join correlated through {@code x}. The operand
     * is walked back from {@code IN} to its start (a comparison, a logical operator, a comma or an enclosing
     * bracket). It reads a row through a name qualified with one of {@code names}, or through a bare column
     * name as {@link #collectBareColumns} finds them (so not a function, a cast type, a date part or the
     * type of a typed literal) that is one of {@code columns}, or any such name when {@code columns} is null.
     */
    private boolean isInSubqueryOfAnExpression(int open, Set<String> names, Set<String> columns) {
        int in = previousSignificantIndex(open);
        if (in < 0 || !tokens.get(in).isKeyword("IN")) {
            return false;
        }
        int end = previousSignificantIndex(in);
        if (end >= 0 && tokens.get(end).isKeyword("NOT")) {
            end = previousSignificantIndex(end);
        }
        if (end < 0) {
            return false;
        }
        int start = 0;
        int depth = 0;
        for (int k = end; k >= 0; k--) {
            Token t = tokens.get(k);
            if (t.isPunct(")") || t.isPunct("]")) {
                depth++;
            } else if (t.isPunct("(") || t.isPunct("[")) {
                if (depth == 0) {
                    start = k + 1;
                    break;
                }
                depth--;
            } else if (depth == 0 && (t.isPunct(",") || (t.kind == Kind.PUNCT && t.text.matches("[=<>!]+"))
                    || (t.kind == Kind.IDENT && IN_OPERAND_BOUNDARIES.contains(t.upper())))) {
                start = k + 1;
                break;
            }
        }
        for (int k = start; k <= end; k++) {
            Token t = tokens.get(k);
            if (t.kind != Kind.IDENT && t.kind != Kind.QIDENT) {
                continue;
            }
            int previous = previousSignificantIndex(k);
            if (previous >= 0 && tokens.get(previous).isPunct(".")) {
                continue;
            }
            String qualifier = null;
            if (t.kind == Kind.QIDENT && t.value.contains(".")) {
                qualifier = t.value.substring(0, t.value.indexOf('.'));
            } else {
                int next = nextSignificant(k + 1, end + 1);
                if (next >= 0 && tokens.get(next).isPunct(".")) {
                    qualifier = t.identifierText();
                }
            }
            if (qualifier != null && names.contains(qualifier.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        List<Integer> bare = new ArrayList<>();
        MergeScope scope = mergeScope;
        mergeScope = null;
        try {
            collectBareColumns(start, end + 1, bare, false);
        } finally {
            mergeScope = scope;
        }
        for (int k : bare) {
            if (columns == null || columns.contains(tokens.get(k).identifierText().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Records the implicit alias of the select item in {@code (start, end)}: its last name when no
     * {@code AS} comes before it and an operand ends right before it ({@code MAX(name) v}, {@code lk v}).
     */
    private void recordImplicitAlias(int start, int end, Set<String> aliases) {
        int last = previousSignificantIndex(end);
        if (last <= start) {
            return;
        }
        Token alias = tokens.get(last);
        boolean name = alias.kind == Kind.QIDENT || (alias.kind == Kind.IDENT
                && !GOOGLESQL_RESERVED.contains(alias.upper()) && !NON_ALIAS_KEYWORDS.contains(alias.upper()));
        int previous = previousSignificantIndex(last);
        if (!name || previous <= start) {
            return;
        }
        Token p = tokens.get(previous);
        if (!p.isPunct(".") && !p.isKeyword("AS") && (endsMergeOperand(p) || p.isKeyword("END"))) {
            aliases.add(alias.identifierText().toLowerCase(Locale.ROOT));
        }
    }

    /** Words before the left operand of {@code IN}: walking back past one leaves the operand. */
    private static final Set<String> IN_OPERAND_BOUNDARIES = Set.of("AND", "OR", "NOT", "WHEN", "THEN", "ELSE",
            "CASE", "SELECT", "WHERE", "ON", "HAVING", "QUALIFY", "BETWEEN", "IS");

    /** The clauses from which on a select-list alias hides a column of the same name. */
    private static final Set<String> ALIAS_CLAUSES = Set.of("GROUP", "HAVING", "QUALIFY", "ORDER");

    /** Words that end a FROM clause, so that a comma after them no longer starts a FROM item. */
    private static final Set<String> FROM_CLAUSE_ENDS = Set.of("WHERE", "GROUP", "HAVING", "QUALIFY", "WINDOW",
            "ORDER", "LIMIT", "UNION", "INTERSECT", "EXCEPT");

    /**
     * Records the FROM item starting at {@code item}: its table name and alias in {@code names}, its
     * tokens in {@code fromItems}, and its columns in {@code columns}. Returns false when its columns are
     * unknown: a subquery, an {@code UNNEST}, a parenthesized join, or a table this pass cannot look up.
     */
    private boolean fromItem(int item, int close, Set<String> names, Set<String> columns, Set<Integer> fromItems) {
        if (item < 0) {
            return false;
        }
        Token t = tokens.get(item);
        int end;
        boolean known;
        int inner = t.isPunct("(") ? nextSignificant(item + 1, close) : -1;
        if (inner >= 0 && !tokens.get(inner).isKeyword("SELECT") && !tokens.get(inner).isKeyword("WITH")) {
            end = matchingParen(item);
            known = joinedItems(item, end, names, columns, fromItems);
        } else if (t.isPunct("(") || t.isKeyword("UNNEST")) {
            int paren = t.isPunct("(") ? item : nextSignificant(item + 1, close);
            if (paren < 0 || !tokens.get(paren).isPunct("(")) {
                return false;
            }
            end = matchingParen(paren);
            known = false;
        } else if (t.kind == Kind.IDENT || t.kind == Kind.QIDENT) {
            List<String> segments = new ArrayList<>(pathSegments(t));
            end = item;
            fromItems.add(item);
            int dot = nextSignificant(end + 1, close);
            while (dot >= 0 && tokens.get(dot).isPunct(".")) {
                int part = nextSignificant(dot + 1, close);
                if (part < 0) {
                    break;
                }
                end = part;
                fromItems.add(part);
                segments.addAll(pathSegments(tokens.get(part)));
                dot = nextSignificant(end + 1, close);
            }
            names.add(segments.getLast().toLowerCase(Locale.ROOT));
            TableRef ref = tableRefOrNull(segments);
            Set<String> tableColumns = ref == null ? null : columnsOf.apply(ref);
            known = tableColumns != null;
            if (known) {
                columns.addAll(tableColumns);
            }
        } else {
            return false;
        }
        int alias = nextSignificant(end + 1, close);
        if (alias >= 0 && tokens.get(alias).isKeyword("AS")) {
            alias = nextSignificant(alias + 1, close);
        }
        if (alias >= 0 && (tokens.get(alias).kind == Kind.QIDENT || (tokens.get(alias).kind == Kind.IDENT
                && !GOOGLESQL_RESERVED.contains(tokens.get(alias).upper())
                && !NON_ALIAS_KEYWORDS.contains(tokens.get(alias).upper())))) {
            fromItems.add(alias);
            names.add(tokens.get(alias).identifierText().toLowerCase(Locale.ROOT));
        }
        return known;
    }

    /**
     * Records the FROM items of the parenthesized join in {@code (open, close)}, as {@link #fromItem} does;
     * false when the columns of any of them are unknown.
     */
    private boolean joinedItems(int open, int close, Set<String> names, Set<String> columns,
                                Set<Integer> fromItems) {
        boolean known = fromItem(nextSignificant(open + 1, close), close, names, columns, fromItems);
        int depth = 0;
        for (int k = open + 1; k < close; k++) {
            Token t = tokens.get(k);
            if (t.isPunct("(")) {
                depth++;
            } else if (t.isPunct(")")) {
                depth--;
            } else if (depth == 0 && (t.isKeyword("JOIN") || t.isPunct(","))) {
                known &= fromItem(nextSignificant(k + 1, close), close, names, columns, fromItems);
            }
        }
        return known;
    }

    private static List<String> pathSegments(Token t) {
        return t.kind == Kind.QIDENT ? List.of(t.value.split("\\.", -1)) : List.of(t.text);
    }

    /** The table a path names, or null when it names none this pass can look up. */
    private TableRef tableRefOrNull(List<String> segments) {
        try {
            return resolveTable(segments);
        } catch (GcpException e) {
            return null;
        }
    }

    private static final Set<String> MERGE_CLAUSE_WORDS = Set.of("MATCHED", "TARGET", "SOURCE", "INSERT",
            "VALUES", "ROW", "UPDATE", "DELETE");

    /** Functions BigQuery lets you call without parentheses. */
    private static final Set<String> PARENLESS_FUNCTIONS = Set.of("CURRENT_DATE", "CURRENT_DATETIME", "CURRENT_TIME",
            "CURRENT_TIMESTAMP");

    /**
     * The 0-based argument that holds the date part ({@code DATE_TRUNC(d, MONTH)},
     * {@code TIMESTAMP_TRUNC(ts, DAY, 'UTC')}, {@code DATE_DIFF(a, b, DAY)}); the other arguments are
     * expressions, so a column named {@code day} there is still a column.
     */
    private static final Map<String, Integer> DATE_PART_ARGUMENT = Map.of("DATE_TRUNC", 1, "DATETIME_TRUNC", 1,
            "TIMESTAMP_TRUNC", 1, "TIME_TRUNC", 1, "LAST_DAY", 1, "DATE_DIFF", 2, "DATETIME_DIFF", 2,
            "TIMESTAMP_DIFF", 2, "TIME_DIFF", 2);

    /** Date part names; after {@code TO} in an interval range ({@code INTERVAL '0-1' YEAR TO MONTH}) one is not a column. */
    private static final Set<String> DATE_PART_NAMES = Set.of("MICROSECOND", "MILLISECOND", "SECOND", "MINUTE",
            "HOUR", "DAY", "DAYOFWEEK", "DAYOFYEAR", "WEEK", "ISOWEEK", "MONTH", "QUARTER", "YEAR", "ISOYEAR",
            "DATE", "DATETIME", "TIME");

    private int previousSignificantIndex(int index) {
        for (int k = index - 1; k >= 0; k--) {
            if (tokens.get(k).kind != Kind.SPACE && tokens.get(k).kind != Kind.RAW) {
                return k;
            }
        }
        return -1;
    }

    /** Index of the {@code >} that closes the type bracket opened at {@code open}; {@code >>} closes two. */
    private int closingAngle(int open, int to) {
        int depth = 0;
        for (int i = open; i < to; i++) {
            Token t = tokens.get(i);
            if (t.isPunct("<")) {
                depth++;
            } else if (t.isPunct(">")) {
                depth--;
            } else if (t.isPunct(">>")) {
                depth -= 2;
            }
            if (depth <= 0) {
                return i;
            }
        }
        return to - 1;
    }

    /** True when {@code t} can end an operand, so a word right after it is not a column reference. */
    private static boolean endsMergeOperand(Token t) {
        return t.kind == Kind.NUMBER || t.kind == Kind.STRING || t.kind == Kind.QIDENT
                || t.isPunct(")") || t.isPunct("]")
                || (t.kind == Kind.IDENT && !GOOGLESQL_RESERVED.contains(t.upper())
                        && !NON_ALIAS_KEYWORDS.contains(t.upper()) && !MERGE_CLAUSE_WORDS.contains(t.upper()));
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
        bindPositionalParameters();
        String statement = statementType(sql);
        if (!statement.equals("SELECT")) {
            throw invalidQuery("Statement type " + statement + " is not supported by the floci BigQuery"
                    + " emulator yet; only SELECT queries run on the SQL engine.");
        }
        queryResult = true;
        collectCteNames();
        rewriteTypedArrayLiterals();
        rewriteArraySubscripts();
        // SELECT AS STRUCT renders its fields to text, so queries nested in them must be rewritten first.
        quoteImplicitSelectAliases();
        expandHavingStructAliasFields();
        rewritePivotSyntax();
        rewriteSelectAsStruct();
        rewriteIndexedStructWildcards();
        quoteImplicitSelectAliases();
        expandHavingStructAliasFields();
        rewritePivotSyntax();
        nameAnonymousColumns();
        expandStructStars();
        String replacementStarExclusion = replacementStarExclusion();
        String rendered = render(0, tokens.size()).trim();
        if (replacementStarExclusion != null) {
            rendered = "SELECT *" + replacementStarExclusion + " FROM (" + rendered
                    + ") AS \"_floci_star_replace\"";
        }
        return new Translation(rendered, tables, informationSchema, needsUnicodeCaseFold);
    }

    /**
     * {@code expr.*} expands a STRUCT into one column per field. DuckDB only takes {@code name.*} on a bare
     * column or table, so {@code .*} after a dotted path, a call, a subscript or a parenthesized expression
     * becomes {@code unnest(expr)}, which DuckDB expands the same way, inside aggregate queries too. It runs
     * after {@link #nameAnonymousColumns}, so the expansion does not get a generated name.
     */
    private void expandStructStars() {
        for (int i = 0; i < tokens.size(); i++) {
            if (!tokens.get(i).isPunct("*")) {
                continue;
            }
            int dot = significantBefore(i);
            if (dot < 0 || !tokens.get(dot).isPunct(".")) {
                continue;
            }
            int last = significantBefore(dot);
            if (last < 0) {
                continue;
            }
            int start = starOperandStart(last);
            Token first = tokens.get(start);
            boolean bareName = start == last && (first.kind == Kind.IDENT || first.kind == Kind.QIDENT)
                    && !first.identifierText().contains(".");
            if (bareName) {
                continue;
            }
            int after = nextSignificant(i + 1, tokens.size());
            int afterNext = after >= 0 ? nextSignificant(after + 1, tokens.size()) : -1;
            if (after >= 0 && (tokens.get(after).isKeyword("EXCEPT") || tokens.get(after).isKeyword("REPLACE"))
                    && afterNext >= 0 && tokens.get(afterNext).isPunct("(")) {
                throw invalidQuery("Dot-star with " + tokens.get(after).upper() + " on a STRUCT expression is not"
                        + " supported by the floci BigQuery emulator yet.");
            }
            List<Token> expansion = new ArrayList<>();
            expansion.add(Token.raw("unnest"));
            expansion.add(new Token(Kind.PUNCT, "(", "("));
            expansion.addAll(tokens.subList(start, dot));
            expansion.add(new Token(Kind.PUNCT, ")", ")"));
            tokens.subList(start, i + 1).clear();
            tokens.addAll(start, expansion);
            i = start + expansion.size() - 1;
        }
    }

    /**
     * First token of the value a {@code .*} applies to: a dotted path, a call, a subscript chain or parentheses,
     * and any mix of them such as {@code a[OFFSET(0)].s} or {@code f(x).s}.
     */
    private int starOperandStart(int last) {
        Token t = tokens.get(last);
        int start = last;
        if (t.isPunct(")")) {
            start = openingFor(last, "(", ")");
            int name = significantBefore(start);
            if (name >= 0 && tokens.get(name).kind == Kind.IDENT && !NON_ALIAS_KEYWORDS.contains(tokens.get(name).upper())) {
                start = name;
            }
        } else if (t.isPunct("]")) {
            int open = openingFor(last, "[", "]");
            int before = significantBefore(open);
            if (before >= 0 && (tokens.get(before).isPunct(")") || tokens.get(before).isPunct("]")
                    || tokens.get(before).kind == Kind.IDENT || tokens.get(before).kind == Kind.QIDENT)) {
                return starOperandStart(before);
            }
            return open;
        }
        int dot = significantBefore(start);
        while (dot >= 0 && tokens.get(dot).isPunct(".")) {
            int name = significantBefore(dot);
            if (name >= 0 && (tokens.get(name).isPunct(")") || tokens.get(name).isPunct("]"))) {
                return starOperandStart(name);
            }
            if (name < 0 || !(tokens.get(name).kind == Kind.IDENT || tokens.get(name).kind == Kind.QIDENT)) {
                break;
            }
            start = name;
            dot = significantBefore(start);
        }
        return start;
    }

    private int significantBefore(int index) {
        int k = index - 1;
        while (k >= 0 && tokens.get(k).kind == Kind.SPACE) {
            k--;
        }
        return k;
    }

    private int openingFor(int close, String open, String closeText) {
        int depth = 0;
        for (int k = close; k >= 0; k--) {
            if (tokens.get(k).isPunct(closeText)) {
                depth++;
            } else if (tokens.get(k).isPunct(open) && --depth == 0) {
                return k;
            }
        }
        throw invalidQuery("Syntax error: Unexpected \"" + closeText + "\"");
    }

    /**
     * Binds every positional placeholder once, in source order, before rewrites can render expressions
     * out of order or copy their tokens. The bound literal stays on the token, so every later rendering
     * of that placeholder uses the same value.
     */
    private void bindPositionalParameters() {
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.kind == Kind.POSITIONAL_PARAM) {
                tokens.set(i, new Token(token.kind, token.text, parameters.nextPositional()));
            }
        }
    }

    private void rewriteTypedArrayLiterals() {
        for (int i = 0; i < tokens.size(); i++) {
            if (!tokens.get(i).isKeyword("ARRAY")) {
                continue;
            }
            int openAngle = nextSignificant(i + 1, tokens.size());
            if (openAngle < 0 || !tokens.get(openAngle).isPunct("<")) {
                continue;
            }
            int closeAngle = matchingTypeAngle(openAngle);
            if (closeAngle < 0) {
                continue;
            }
            int openBracket = nextSignificant(closeAngle + 1, tokens.size());
            if (openBracket < 0 || !tokens.get(openBracket).isPunct("[")) {
                continue;
            }
            int closeBracket = matchingBracket(openBracket);
            if (closeBracket < 0) {
                continue;
            }
            List<Token> type = tokens.subList(i, closeAngle + 1).stream()
                    .filter(token -> token.kind != Kind.SPACE).toList();
            if (nextSignificant(openBracket + 1, closeBracket) == -1) {
                String literal = "CAST([] AS " + translateType(type) + ")";
                tokens.subList(i, closeBracket + 1).clear();
                tokens.add(i, Token.raw(literal));
            } else {
                // DuckDB infers anonymous struct member names for e.g. [(id_type, company_id)].
                // Keep the GoogleSQL element type as a cast so ARRAY<STRUCT<type STRING,
                // company_id INT64>>[(...)] retains its declared field names.
                String translatedType = translateType(type);
                tokens.subList(i, openBracket).clear();
                tokens.add(i, new Token(Kind.IDENT, "CAST", "CAST"));
                tokens.add(i + 1, new Token(Kind.PUNCT, "(", null));
                int castEnd = matchingBracket(i + 2) + 1;
                tokens.add(castEnd, Token.raw(" AS " + translatedType));
                tokens.add(castEnd + 1, new Token(Kind.PUNCT, ")", null));
            }
        }
    }

    /** Rewrites BigQuery's {@code SELECT AS STRUCT ...} into a DuckDB struct value. */
    private void rewriteSelectAsStruct() {
        // Work from the innermost SELECT outwards.  This lets render() handle a nested
        // SELECT AS STRUCT while the enclosing struct is being assembled.
        for (int select = tokens.size() - 1; select >= 0; select--) {
            if (!tokens.get(select).isKeyword("SELECT")) {
                continue;
            }
            int marker = nextSignificant(select + 1, tokens.size());
            String modifier = "";
            while (marker >= 0 && (tokens.get(marker).isKeyword("DISTINCT")
                    || tokens.get(marker).isKeyword("ALL"))) {
                modifier = " " + tokens.get(marker).upper();
                marker = nextSignificant(marker + 1, tokens.size());
            }
            if (marker < 0 || !tokens.get(marker).isKeyword("AS")) {
                continue;
            }
            int struct = nextSignificant(marker + 1, tokens.size());
            if (struct < 0 || !tokens.get(struct).isKeyword("STRUCT")) {
                continue;
            }

            int listStart = struct + 1;
            int listEnd = selectStructListEnd(listStart);
            List<int[]> items = selectStructItems(listStart, listEnd);
            if (items.isEmpty()) {
                continue;
            }
            if (queryResult && isOutermostQuerySelect(select)) {
                nameAnonymousStructFields(items);
                int end = struct + 1;
                if (end < tokens.size() && tokens.get(end).kind == Kind.SPACE) {
                    end++;
                }
                tokens.subList(marker, end).clear();
                continue;
            }
            if (structProjectionHasAliasClause(listEnd, parenthesisDepth(select))) {
                nameAnonymousStructFields(items);
                listEnd = selectStructListEnd(listStart);
                wrapStructProjection(select, marker, struct, listEnd);
                select = tokens.size();
                continue;
            }
            if (items.size() == 1 && isStarProjection(significant(items.getFirst()[0], items.getFirst()[1]))) {
                int itemStart = items.getFirst()[0];
                int itemEnd = items.getFirst()[1];
                String indexed = indexedStructWildcardExpression(itemStart, itemEnd);
                String projection;
                if (indexed != null) {
                    projection = "(SELECT struct_pack(*COLUMNS(*)) FROM (SELECT unnest([" + indexed
                            + "], max_depth := 2)) AS \"_floci_indexed_struct\")";
                } else {
                    String star = renderSelectAsStructStar(itemStart, itemEnd);
                    projection = "struct_pack(*COLUMNS(" + star + "))";
                }
                boolean clauseFollows = listEnd < tokens.size() && tokens.get(listEnd).kind == Kind.IDENT;
                tokens.subList(select, listEnd).clear();
                String suffix = clauseFollows ? " " : "";
                replaceSelectHead(select, modifier, projection + suffix);
                select = tokens.size();
                continue;
            }
            boolean mixedStars = items.stream().anyMatch(item -> isStarProjection(significant(item[0], item[1])));
            if (mixedStars) {
                wrapStructProjection(select, marker, struct, listEnd);
                select = tokens.size();
                continue;
            }
            StringBuilder replacement = new StringBuilder("{");
            for (int n = 0; n < items.size(); n++) {
                int[] item = items.get(n);
                List<Token> significant = significant(item[0], item[1]);
                if (significant.isEmpty()) {
                    continue;
                }
                if (n > 0) {
                    replacement.append(", ");
                }
                int as = topLevelAs(item[0], item[1]);
                String name;
                String expression;
                if (as >= 0) {
                    name = significant(as + 1, item[1]).getFirst().identifierText();
                    expression = render(item[0], as).trim();
                } else {
                    int implicitAlias = implicitSelectAlias(item[0], item[1]);
                    if (implicitAlias >= 0) {
                        name = tokens.get(implicitAlias).identifierText();
                        expression = render(item[0], implicitAlias).trim();
                    } else {
                        name = isPath(significant) ? significant.getLast().identifierText() : "_field_" + (n + 1);
                        expression = render(item[0], item[1]).trim();
                    }
                }
                replacement.append(DuckTypes.quoteLiteral(name)).append(": ").append(expression);
            }
            replacement.append('}');
            if (listEnd < tokens.size() && tokens.get(listEnd).kind == Kind.IDENT) {
                replacement.append(' ');
            }
            tokens.subList(select, listEnd).clear();
            replaceSelectHead(select, modifier, replacement.toString());
            select = tokens.size();
        }
    }

    /**
     * A query returns the struct of its outermost {@code SELECT AS STRUCT}, in every branch of a set
     * operation, as one column per field, so that SELECT runs as a plain one. Nested ones (subqueries,
     * CTEs, {@code ARRAY(...)}) and the source of an INSERT stay one struct value.
     */
    private boolean isOutermostQuerySelect(int select) {
        for (int open = enclosingParenthesis(select); open >= 0; open = enclosingParenthesis(open)) {
            int before = previousSignificant(open - 1);
            if (before >= 0 && !tokens.get(before).isPunct("(") && !opensSetOperationBranch(before)) {
                return false;
            }
        }
        return true;
    }

    /** {@code UNION ALL (}, {@code INTERSECT DISTINCT (} and the like start a parenthesized branch. */
    private boolean opensSetOperationBranch(int keyword) {
        Token token = tokens.get(keyword);
        if (token.isKeyword("ALL") || token.isKeyword("DISTINCT")) {
            int operator = previousSignificant(keyword - 1);
            return operator >= 0 && opensSetOperationBranch(operator);
        }
        return token.isKeyword("UNION") || token.isKeyword("INTERSECT") || token.isKeyword("EXCEPT");
    }

    private int enclosingParenthesis(int index) {
        int depth = 0;
        for (int k = index - 1; k >= 0; k--) {
            if (tokens.get(k).isPunct(")")) {
                depth++;
            } else if (tokens.get(k).isPunct("(") && depth-- == 0) {
                return k;
            }
        }
        return -1;
    }

    /** Clauses that can resolve names assigned by the SELECT list must stay inside the struct wrapper. */
    private boolean structProjectionHasAliasClause(int start, int depth) {
        int end = structQueryEnd(start, depth);
        for (int i = start; i < end; i++) {
            Token token = tokens.get(i);
            if (parenthesisDepth(i) == depth && (token.isKeyword("GROUP") || token.isKeyword("HAVING")
                    || token.isKeyword("QUALIFY") || token.isKeyword("ORDER"))) {
                return true;
            }
        }
        return false;
    }

    private void nameAnonymousStructFields(List<int[]> items) {
        for (int n = items.size() - 1; n >= 0; n--) {
            int[] item = items.get(n);
            if (!needsGeneratedName(item[0], item[1])) {
                continue;
            }
            int end = item[1];
            while (tokens.get(end - 1).kind == Kind.SPACE) {
                end--;
            }
            tokens.addAll(end, new Lexer(" AS `_field_" + (n + 1) + "`").tokenize());
        }
    }

    /** The head stays recognisable as a SELECT so later clauses can still resolve the query's FROM scope. */
    private void replaceSelectHead(int select, String modifier, String projection) {
        tokens.add(select, Token.rawSelectHead("SELECT" + modifier + " " + projection));
    }

    private void wrapStructProjection(int select, int marker, int struct, int listEnd) {
        int queryEnd = structQueryEnd(listEnd, parenthesisDepth(select));
        tokens.addAll(queryEnd, new Lexer(") AS _floci_struct_row ").tokenize());
        tokens.subList(marker, struct + 1).clear();
        List<Token> prefix = new ArrayList<>(new Lexer("SELECT ").tokenize());
        prefix.add(Token.raw("struct_pack(*COLUMNS(_floci_struct_row.*))"));
        prefix.addAll(new Lexer(" FROM (").tokenize());
        tokens.addAll(select, prefix);
    }

    private int structQueryEnd(int start, int depth) {
        for (int i = start; i < tokens.size(); i++) {
            int currentDepth = parenthesisDepth(i);
            Token token = tokens.get(i);
            if (currentDepth < depth || currentDepth == depth && (token.isPunct(")")
                    || token.isKeyword("UNION") || token.isKeyword("INTERSECT") || token.isKeyword("EXCEPT"))) {
                return i;
            }
        }
        return tokens.size();
    }

    private String renderSelectAsStructStar(int start, int end) {
        int star = start;
        while (star < end && !tokens.get(star).isPunct("*")) {
            star++;
        }
        int except = nextSignificant(star + 1, end);
        if (star >= end || except < 0 || !tokens.get(except).isKeyword("EXCEPT")
                || !nextSignificantIs(except + 1, end, "(")) {
            return render(start, end).trim();
        }
        int open = nextSignificant(except + 1, end);
        int close = matchingParen(open);
        return (render(start, star) + "*" + starExclusionList(star, open, close)
                + render(close + 1, end)).trim();
    }

    private String indexedStructWildcardExpression(int start, int end) {
        int star = previousSignificant(end - 1);
        int dot = star < 0 ? -1 : previousSignificant(star - 1);
        int expressionEnd = dot < 0 ? -1 : previousSignificant(dot - 1);
        if (star < 0 || !tokens.get(star).isPunct("*") || dot < start || !tokens.get(dot).isPunct(".")
                || expressionEnd < start || !isIndexedStructWildcardExpression(expressionEnd)) {
            return null;
        }
        return render(start, dot).trim();
    }

    private boolean isIndexedStructWildcardExpression(int expressionEnd) {
        if (tokens.get(expressionEnd).isPunct("]")) {
            return matchingBracketBefore(expressionEnd) >= 0;
        }
        if (tokens.get(expressionEnd).isPunct(")")) {
            int openParen = matchingOpen(expressionEnd, "(", ")");
            return render(openParen, expressionEnd + 1).trim()
                    .startsWith("(list_transform([struct_pack(a := ");
        }
        return tokens.get(expressionEnd).kind == Kind.RAW;
    }

    /** Rewrites BigQuery's {@code array_expression[OFFSET(n)].*} projection.
     *
     * DuckDB cannot expand a struct-valued expression followed by {@code .*}, but its
     * recursive UNNEST expands a singleton list of that struct into the same columns.
     */
    private void rewriteIndexedStructWildcards() {
        for (int dot = tokens.size() - 1; dot >= 0; dot--) {
            if (!tokens.get(dot).isPunct(".")) {
                continue;
            }
            int star = nextSignificant(dot + 1, tokens.size());
            int expressionEnd = previousSignificant(dot - 1);
            if (star < 0 || !tokens.get(star).isPunct("*") || expressionEnd < 0) {
                continue;
            }
            int expressionStart;
            if (tokens.get(expressionEnd).isPunct("]")) {
                int openBracket = matchingBracketBefore(expressionEnd);
                if (openBracket < 0) {
                    continue;
                }
                expressionStart = projectionExpressionStart(openBracket);
            } else if (tokens.get(expressionEnd).isPunct(")")) {
                int openParen = matchingOpen(expressionEnd, "(", ")");
                if (!render(openParen, expressionEnd + 1).trim()
                        .startsWith("(list_transform([struct_pack(a := ")) {
                    continue;
                }
                expressionStart = openParen;
            } else if (tokens.get(expressionEnd).kind == Kind.RAW) {
                expressionStart = expressionEnd;
            } else {
                continue;
            }
            String indexed = render(expressionStart, expressionEnd + 1).trim();
            tokens.subList(expressionStart, star + 1).clear();
            tokens.add(expressionStart, Token.raw(" unnest([" + indexed + "], max_depth := 2)"));
            dot = expressionStart;
        }
    }

    private int matchingBracketBefore(int close) {
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            if (tokens.get(i).isPunct("]")) {
                depth++;
            } else if (tokens.get(i).isPunct("[") && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private int projectionExpressionStart(int openBracket) {
        int parens = 0;
        int brackets = 0;
        for (int i = openBracket - 1; i >= 0; i--) {
            Token t = tokens.get(i);
            if (t.isPunct(")")) {
                parens++;
            } else if (t.isPunct("(") && parens > 0) {
                parens--;
            } else if (t.isPunct("]")) {
                brackets++;
            } else if (t.isPunct("[") && brackets > 0) {
                brackets--;
            } else if (parens == 0 && brackets == 0
                    && (t.isPunct(",") || t.isKeyword("SELECT"))) {
                return i + 1;
            }
        }
        return 0;
    }

    private int implicitSelectAlias(int start, int end) {
        List<Token> item = significant(start, end);
        if (!endsWithImplicitAlias(item)) {
            return -1;
        }
        for (int i = end - 1; i >= start; i--) {
            if (tokens.get(i).kind != Kind.SPACE) {
                return i;
            }
        }
        return -1;
    }

    private int selectStructListEnd(int start) {
        int parens = 0;
        int brackets = 0;
        int angles = 0;
        for (int i = start; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.isPunct("(")) {
                parens++;
            } else if (t.isPunct(")")) {
                if (parens == 0 && brackets == 0 && angles == 0) {
                    return i;
                }
                parens--;
            } else if (t.isPunct("[")) {
                brackets++;
            } else if (t.isPunct("]")) {
                brackets--;
            } else if (t.isPunct("<") && opensTypeArguments(i, angles)) {
                angles++;
            } else if (angles > 0 && (t.isPunct(">") || t.isPunct(">>"))) {
                angles = Math.max(0, angles - (t.isPunct(">>") ? 2 : 1));
            } else if (parens == 0 && brackets == 0 && angles == 0 && t.kind == Kind.IDENT
                    && (isFromClause(i) || CLAUSE_END_KEYWORDS.contains(t.upper()))
                    && !(t.isKeyword("EXCEPT") && followsStar(i))) {
                return i;
            }
        }
        return tokens.size();
    }

    private boolean opensTypeArguments(int index, int angles) {
        if (angles > 0) {
            return true;
        }
        int previous = previousSignificant(index - 1);
        if (previous < 0) {
            return false;
        }
        String name = tokens.get(previous).upper();
        return name.equals("ARRAY") || name.equals("STRUCT") || name.equals("RANGE");
    }

    private List<int[]> selectStructItems(int start, int end) {
        List<int[]> items = new ArrayList<>();
        int itemStart = start;
        int parens = 0;
        int brackets = 0;
        int angles = 0;
        for (int i = start; i < end; i++) {
            Token t = tokens.get(i);
            if (t.isPunct("(")) {
                parens++;
            } else if (t.isPunct(")")) {
                parens--;
            } else if (t.isPunct("[")) {
                brackets++;
            } else if (t.isPunct("]")) {
                brackets--;
            } else if (t.isPunct("<") && opensTypeArguments(i, angles)) {
                angles++;
            } else if (angles > 0 && t.isPunct(">")) {
                angles--;
            } else if (angles > 0 && t.isPunct(">>")) {
                angles = Math.max(0, angles - 2);
            } else if (t.isPunct(",") && parens == 0 && brackets == 0 && angles == 0) {
                items.add(new int[] {itemStart, i});
                itemStart = i + 1;
            }
        }
        if (nextSignificant(itemStart, end) >= 0) {
            items.add(new int[] {itemStart, end});
        }
        return items;
    }

    private int topLevelAs(int start, int end) {
        int parens = 0;
        int brackets = 0;
        for (int i = start; i < end; i++) {
            Token t = tokens.get(i);
            if (t.isPunct("(")) {
                parens++;
            } else if (t.isPunct(")")) {
                parens--;
            } else if (t.isPunct("[")) {
                brackets++;
            } else if (t.isPunct("]")) {
                brackets--;
            } else if (parens == 0 && brackets == 0 && t.isKeyword("AS")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * BigQuery lets HAVING dereference a SELECT alias that is a STRUCT, including aggregate
     * fields such as {@code HAVING latest.pt <= ...}. DuckDB instead binds {@code pt} to an input
     * column and rejects it as ungrouped. Expand only explicit STRUCT alias fields in HAVING.
     */
    private void expandHavingStructAliasFields() {
        for (int having = tokens.size() - 1; having >= 0; having--) {
            if (!tokens.get(having).isKeyword("HAVING")) {
                continue;
            }

            int depth = parenthesisDepth(having);
            int select = precedingSelect(having, depth);
            if (select < 0) {
                continue;
            }
            Map<String, Map<String, List<Token>>> aliases = structSelectAliases(select, having, depth);
            if (aliases.isEmpty()) {
                continue;
            }
            int end = clauseEnd(having + 1, depth);
            boolean[] nestedQuery = nestedQueryPositions(having + 1, end);
            for (int i = end - 1; i >= having; i--) {
                if (nestedQuery[i]) {
                    continue;
                }
                Token alias = tokens.get(i);
                int dot = nextSignificant(i + 1, end);
                int field = dot < 0 ? -1 : nextSignificant(dot + 1, end);
                if ((alias.kind != Kind.IDENT && alias.kind != Kind.QIDENT) || dot < 0 || field < 0
                        || !tokens.get(dot).isPunct(".")
                        || (tokens.get(field).kind != Kind.IDENT && tokens.get(field).kind != Kind.QIDENT)) {
                    continue;
                }
                Map<String, List<Token>> fields = aliases.get(alias.identifierText().toLowerCase(Locale.ROOT));
                List<Token> expression = fields == null
                        ? null
                        : fields.get(tokens.get(field).identifierText().toLowerCase(Locale.ROOT));
                if (expression == null) {
                    continue;
                }
                List<Token> replacement = new ArrayList<>();
                replacement.add(Token.raw("("));
                replacement.addAll(expression);
                replacement.add(Token.raw(")"));
                tokens.subList(i, field + 1).clear();
                tokens.addAll(i, replacement);
            }
        }
    }

    /** Marks tokens that belong to a query nested inside the current clause. */
    private boolean[] nestedQueryPositions(int start, int end) {
        boolean[] nested = new boolean[end];
        Deque<Boolean> queryParentheses = new ArrayDeque<>();
        int queryDepth = 0;
        for (int i = start; i < end; i++) {
            Token token = tokens.get(i);
            if (token.isPunct("(")) {
                int close = matchingParen(i);
                boolean query = close < end && isQuery(i + 1, close);
                queryParentheses.push(query);
                if (query) {
                    queryDepth++;
                }
            }
            nested[i] = queryDepth > 0;
            if (token.isPunct(")") && !queryParentheses.isEmpty()) {
                if (queryParentheses.pop()) {
                    queryDepth--;
                }
            }
        }
        return nested;
    }

    private int parenthesisDepth(int end) {
        if (tokens instanceof TokenList list) {
            return list.depthBefore(end);
        }
        int depth = 0;
        for (int i = 0; i < end; i++) {
            if (tokens.get(i).isPunct("(")) {
                depth++;
            } else if (tokens.get(i).isPunct(")")) {
                depth--;
            }
        }
        return depth;
    }

    /**
     * Token list that caches the parenthesis depth before every token. Depth lookups sit inside
     * scans over the whole list, so recomputing them per call made translation cubic in script size.
     * The cache is rebuilt after any structural change; {@code set} never swaps a parenthesis.
     */
    private static final class TokenList extends ArrayList<Token> {
        private int[] depths;
        private int cachedModCount = -1;

        int depthBefore(int end) {
            if (end < 0 || end > size()) {
                throw new IndexOutOfBoundsException("Index: " + end + ", Size: " + size());
            }
            if (depths == null || cachedModCount != modCount) {
                int[] computed = new int[size() + 1];
                int depth = 0;
                for (int i = 0; i < size(); i++) {
                    Token token = get(i);
                    if (token.isPunct("(")) {
                        depth++;
                    } else if (token.isPunct(")")) {
                        depth--;
                    }
                    computed[i + 1] = depth;
                }
                depths = computed;
                cachedModCount = modCount;
            }
            return depths[end];
        }
    }

    private int precedingSelect(int before, int depth) {
        for (int i = before - 1; i >= 0; i--) {
            if (parenthesisDepth(i) == depth && tokens.get(i).isKeyword("SELECT")) {
                return i;
            }
        }
        return -1;
    }

    private int clauseEnd(int start, int depth) {
        for (int i = start; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (parenthesisDepth(i) < depth
                    || parenthesisDepth(i) == depth && token.kind == Kind.IDENT
                    && CLAUSE_END_KEYWORDS.contains(token.upper())) {
                return i;
            }
        }
        return tokens.size();
    }

    private Map<String, Map<String, List<Token>>> structSelectAliases(int select, int before, int depth) {
        Map<String, Map<String, List<Token>>> aliases = new HashMap<>();
        int start = select + 1;
        int selectEnd = before;
        for (int i = start; i < before; i++) {
            if (parenthesisDepth(i) == depth && isFromClause(i)) {
                selectEnd = i;
                break;
            }
        }
        int braceDepth = 0;
        for (int end = start; end <= selectEnd; end++) {
            Token token = end == selectEnd ? null : tokens.get(end);
            boolean atEnd = end == selectEnd || braceDepth == 0 && parenthesisDepth(end) == depth
                    && token.isPunct(",");
            if (atEnd) {
                addStructSelectAlias(start, end, aliases);
                start = end + 1;
            }
            if (token != null && token.isPunct("{")) {
                braceDepth++;
            } else if (token != null && token.isPunct("}")) {
                braceDepth--;
            }
        }
        return aliases;
    }

    private void addStructSelectAlias(int start, int end, Map<String, Map<String, List<Token>>> aliases) {
        int as = -1;
        for (int i = end - 1; i >= start; i--) {
            if (tokens.get(i).isKeyword("AS")) {
                as = i;
                break;
            }
        }
        int alias = as < 0 ? -1 : nextSignificant(as + 1, end);
        int open = nextSignificant(start, as < 0 ? end : as);
        if (alias < 0 || open < 0) {
            return;
        }
        int close;
        Map<String, List<Token>> fields;
        if (tokens.get(open).isPunct("{")) {
            close = matchingBrace(open);
            fields = structFields(open, close);
        } else if (tokens.get(open).isKeyword("STRUCT")) {
            int parenthesis = nextSignificant(open + 1, as);
            if (parenthesis < 0 || !tokens.get(parenthesis).isPunct("(")) {
                return;
            }
            close = matchingParen(parenthesis);
            fields = googleSqlStructFields(parenthesis, close);
        } else {
            return;
        }
        if (close >= as) {
            return;
        }
        if (!fields.isEmpty()) {
            aliases.put(tokens.get(alias).identifierText().toLowerCase(Locale.ROOT), fields);
        }
    }

    private int matchingBrace(int open) {
        int depth = 0;
        for (int i = open; i < tokens.size(); i++) {
            if (tokens.get(i).isPunct("{")) {
                depth++;
            } else if (tokens.get(i).isPunct("}") && --depth == 0) {
                return i;
            }
        }
        return tokens.size();
    }

    private Map<String, List<Token>> structFields(int open, int close) {
        Map<String, List<Token>> fields = new HashMap<>();
        int start = nextSignificant(open + 1, close);
        while (start >= 0 && start < close) {
            Token key = tokens.get(start);
            int colon = nextSignificant(start + 1, close);
            int value = colon < 0 ? -1 : nextSignificant(colon + 1, close);
            if (key.kind != Kind.STRING || colon < 0 || !tokens.get(colon).isPunct(":") || value < 0) {
                return Map.of();
            }
            int end = value;
            int nesting = 0;
            while (end < close) {
                Token token = tokens.get(end);
                if (token.isPunct("(") || token.isPunct("{") || token.isPunct("[")) {
                    nesting++;
                } else if (token.isPunct(")") || token.isPunct("}") || token.isPunct("]")) {
                    nesting--;
                } else if (nesting == 0 && token.isPunct(",")) {
                    break;
                }
                end++;
            }
            fields.put(key.value.toLowerCase(Locale.ROOT), new ArrayList<>(tokens.subList(value, end)));
            start = nextSignificant(end + 1, close);
        }
        return fields;
    }

    private Map<String, List<Token>> googleSqlStructFields(int open, int close) {
        Map<String, List<Token>> fields = new HashMap<>();
        int start = nextSignificant(open + 1, close);
        while (start >= 0 && start < close) {
            int end = structArgumentEnd(start, close);
            int as = -1;
            int nesting = 0;
            for (int i = start; i < end; i++) {
                Token token = tokens.get(i);
                if (token.isPunct("(") || token.isPunct("{") || token.isPunct("[")) {
                    nesting++;
                } else if (token.isPunct(")") || token.isPunct("}") || token.isPunct("]")) {
                    nesting--;
                } else if (nesting == 0 && token.isKeyword("AS")) {
                    as = i;
                }
            }
            int field = as < 0 ? start : nextSignificant(as + 1, end);
            if (field < 0 || (tokens.get(field).kind != Kind.IDENT && tokens.get(field).kind != Kind.QIDENT)) {
                return Map.of();
            }
            int expressionEnd = as < 0 ? end : as;
            List<Token> expression = new ArrayList<>(tokens.subList(start, expressionEnd));
            while (!expression.isEmpty() && expression.getLast().kind == Kind.SPACE) {
                expression.removeLast();
            }
            fields.put(tokens.get(field).identifierText().toLowerCase(Locale.ROOT), expression);
            start = nextSignificant(end + 1, close);
        }
        return fields;
    }

    private int structArgumentEnd(int start, int close) {
        int nesting = 0;
        for (int i = start; i < close; i++) {
            Token token = tokens.get(i);
            if (token.isPunct("(") || token.isPunct("{") || token.isPunct("[")) {
                nesting++;
            } else if (token.isPunct(")") || token.isPunct("}") || token.isPunct("]")) {
                nesting--;
            } else if (nesting == 0 && token.isPunct(",")) {
                return i;
            }
        }
        return close;
    }

    private int matchingTypeAngle(int openAngle) {
        int depth = 0;
        for (int i = openAngle; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.isPunct("<")) {
                depth++;
            } else if (token.kind == Kind.PUNCT && token.text.matches(">+")) {
                depth -= token.text.length();
            }
            if (depth == 0) {
                return i;
            }
        }
        return -1;
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

    /**
     * Names declared by every {@code WITH} that opens a query: the statement's own, and those of
     * parenthesized queries and subqueries ({@code (WITH s AS (...) SELECT * FROM s)}).
     */
    private void collectCteNames() {
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).isKeyword("WITH")) {
                collectCteNamesAt(i);
            }
        }
    }

    private void collectCteNamesAt(int with) {
        int scopeEnd = cteScopeEnd(with);
        int i = nextSignificant(with + 1, scopeEnd);
        if (i >= 0 && tokens.get(i).isKeyword("RECURSIVE")) {
            i = nextSignificant(i + 1, scopeEnd);
        }
        while (i >= 0) {
            Token name = tokens.get(i);
            if (name.kind != Kind.IDENT && name.kind != Kind.QIDENT) {
                return;
            }
            i = nextSignificant(i + 1, scopeEnd);
            if (i < 0 || !tokens.get(i).isKeyword("AS")) {
                return;
            }
            i = nextSignificant(i + 1, scopeEnd);
            if (i < 0 || !tokens.get(i).isPunct("(")) {
                return;
            }
            int close = matchingParen(i);
            cteScopes.add(new CteScope(name.identifierText().toLowerCase(Locale.ROOT), tokens.get(with),
                    scopeEnd < tokens.size() ? tokens.get(scopeEnd) : null));
            i = nextSignificant(close + 1, scopeEnd);
            if (i < 0 || !tokens.get(i).isPunct(",")) {
                return;
            }
            i = nextSignificant(i + 1, scopeEnd);
        }
    }

    private int cteScopeEnd(int with) {
        Deque<Integer> openParens = new ArrayDeque<>();
        for (int i = 0; i < with; i++) {
            if (tokens.get(i).isPunct("(")) {
                openParens.push(i);
            } else if (tokens.get(i).isPunct(")")) {
                openParens.pop();
            }
        }
        return openParens.isEmpty() ? tokens.size() : matchingParen(openParens.peek());
    }

    private boolean cteVisibleAt(String name, int position) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return cteScopes.stream().anyMatch(scope -> {
            int start = tokens.indexOf(scope.start());
            int end = scope.end() == null ? tokens.size() : tokens.indexOf(scope.end());
            return scope.name().equals(normalized) && start < position && position < end;
        });
    }

    /**
     * Rewrites an implicit select-list alias ({@code SELECT expr name}) to {@code AS "name"}, in every
     * SELECT. GoogleSQL accepts non-reserved words such as {@code name}, {@code value} or {@code type}
     * as an implicit alias, while DuckDB rejects its own keywords there unless {@code AS} precedes them.
     */
    private void quoteImplicitSelectAliases() {
        for (int select = 0; select < tokens.size(); select++) {
            if (tokens.get(select).isKeyword("SELECT")) {
                quoteImplicitAliases(select);
            }
        }
    }

    private void quoteImplicitAliases(int select) {
        int first = nextSignificant(select + 1, tokens.size());
        while (first >= 0 && (tokens.get(first).isKeyword("DISTINCT") || tokens.get(first).isKeyword("ALL"))) {
            first = nextSignificant(first + 1, tokens.size());
        }
        if (first < 0 || tokens.get(first).isKeyword("AS")) {
            return; // SELECT AS STRUCT / AS VALUE
        }
        int depth = 0;
        int itemStart = first;
        for (int i = first; i <= tokens.size(); i++) {
            Token t = i < tokens.size() ? tokens.get(i) : null;
            boolean itemEnds = t == null
                    || (depth == 0 && (t.isPunct(",") || t.isPunct(")")
                    || (t.kind == Kind.IDENT && (isFromClause(i)
                            || CLAUSE_END_KEYWORDS.contains(t.upper()))
                            && !followsStar(i))));
            if (itemEnds) {
                quoteImplicitAlias(itemStart, i);
                if (t == null || !t.isPunct(",")) {
                    return;
                }
                itemStart = i + 1;
            } else if (t.isPunct("(") || t.isPunct("[")) {
                depth++;
            } else if (t.isPunct(")") || t.isPunct("]")) {
                depth--;
            }
        }
    }

    /** {@code * EXCEPT (...)} is a column filter, not the EXCEPT set operation. */
    private boolean followsStar(int index) {
        int previous = index - 1;
        while (previous >= 0 && tokens.get(previous).kind == Kind.SPACE) {
            previous--;
        }
        return previous >= 0 && tokens.get(previous).isPunct("*") && tokens.get(index).isKeyword("EXCEPT");
    }

    private void quoteImplicitAlias(int start, int end) {
        int alias = -1;
        for (int i = start; i < end; i++) {
            if (tokens.get(i).kind != Kind.SPACE) {
                alias = i;
            }
        }
        if (alias < 0 || tokens.get(alias).kind != Kind.IDENT || GOOGLESQL_RESERVED.contains(tokens.get(alias).upper())
                || !endsWithImplicitAlias(significant(start, end))) {
            return;
        }
        Token last = tokens.get(alias);
        tokens.set(alias, new Token(Kind.QIDENT, "`" + last.text + "`", last.text));
        tokens.add(alias, new Token(Kind.SPACE, " ", " "));
        tokens.add(alias, new Token(Kind.IDENT, "AS", "AS"));
    }

    /**
     * BigQuery names unaliased, non-column expressions in the outermost select list
     * {@code f0_}, {@code f1_}, … ; DuckDB would name them after the expression text. In a nested
     * select list (subquery, CTE, scalar subquery) such a column stays anonymous: BigQuery calls it
     * {@code $col<position>} and names it {@code f<n>_} only if a {@code *} brings it into the result,
     * numbered with the result's other anonymous columns. Those get {@code AS "$col<position>"} here
     * and are numbered once the query has run and the output order is known; so are the outermost
     * list's own anonymous columns when that list expands a {@code *}.
     */
    private void nameAnonymousColumns() {
        int outermost = firstTopLevelSelect();
        if (outermost < 0) {
            return;
        }
        int outerDepth = 0;
        for (int i = 0; i < outermost; i++) {
            if (tokens.get(i).isPunct("(")) {
                outerDepth++;
            } else if (tokens.get(i).isPunct(")")) {
                outerDepth--;
            }
        }
        // Later branches of a top-level set operation take their column names from the first one.
        List<Integer> selects = new ArrayList<>();
        int depth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.isPunct("(")) {
                depth++;
            } else if (t.isPunct(")")) {
                depth--;
            } else if (t.isKeyword("SELECT") && (i == outermost || depth > outerDepth)) {
                selects.add(i);
            }
        }
        // From the last SELECT back: names are inserted after a SELECT, so earlier ones keep their index.
        for (int k = selects.size() - 1; k >= 0; k--) {
            int select = selects.get(k);
            List<int[]> items = selectItems(select);
            if (items == null) {
                continue;
            }
            boolean positional = select != outermost || items.stream().anyMatch(this::expandsStar);
            List<int[]> anonymous = new ArrayList<>();
            for (int position = 0; position < items.size(); position++) {
                int[] item = items.get(position);
                if (needsGeneratedName(item[0], item[1])) {
                    int last = item[1] - 1;
                    while (tokens.get(last).kind == Kind.SPACE) {
                        last--;
                    }
                    anonymous.add(new int[] {last + 1, position + 1});
                }
            }
            // BigQuery numbers anonymous columns with the next f<n>_ that no other column of the result uses.
            List<String> generated = new ArrayList<>();
            if (!positional) {
                Set<String> taken = new HashSet<>();
                for (int[] item : items) {
                    String name = needsGeneratedName(item[0], item[1]) ? null : outputName(item[0], item[1]);
                    if (name != null) {
                        taken.add(name);
                    }
                }
                int next = 0;
                for (int n = 0; n < anonymous.size(); n++) {
                    while (taken.contains("f" + next + "_")) {
                        next++;
                    }
                    generated.add("f" + next++ + "_");
                }
            }
            // Insert from the back so earlier indexes stay valid.
            for (int n = anonymous.size() - 1; n >= 0; n--) {
                String alias = positional ? " AS " + DuckTypes.quoteIdentifier("$col" + anonymous.get(n)[1])
                        : " AS " + generated.get(n);
                tokens.add(anonymous.get(n)[0], Token.raw(alias));
            }
        }
    }

    /** The lower-cased name a named select item gives its column: its alias or the column it reads. */
    private String outputName(int start, int end) {
        List<Token> item = significant(start, end);
        if (item.isEmpty()) {
            return null;
        }
        List<Token> path = parenthesizedPath(item);
        Token last = path != null ? path.getLast() : item.getLast();
        if (last.kind != Kind.IDENT && last.kind != Kind.QIDENT) {
            return null;
        }
        String name = last.identifierText();
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    /** True for a {@code *}, {@code t.*} or {@code * EXCEPT/REPLACE (...)} select item. */
    private boolean expandsStar(int[] item) {
        List<Token> significant = significant(item[0], item[1]);
        for (int k = 0; k < significant.size(); k++) {
            if (significant.get(k).isPunct("*") && (k == significant.size() - 1
                    || significant.get(k + 1).isKeyword("EXCEPT") || significant.get(k + 1).isKeyword("REPLACE"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The items of the select list that starts at {@code select}, as {@code [start, end)} token ranges,
     * or null for {@code SELECT AS STRUCT} / {@code AS VALUE}, whose fields are named differently.
     */
    private List<int[]> selectItems(int select) {
        int start = select + 1;
        int first = nextSignificant(start, tokens.size());
        while (first >= 0 && (tokens.get(first).isKeyword("DISTINCT") || tokens.get(first).isKeyword("ALL"))) {
            start = first + 1;
            first = nextSignificant(start, tokens.size());
        }
        if (first >= 0 && tokens.get(first).isKeyword("AS")) {
            return null;
        }
        int end = start;
        int depth = 0;
        List<int[]> items = new ArrayList<>();
        int itemStart = start;
        for (; end < tokens.size(); end++) {
            Token t = tokens.get(end);
            if (t.isPunct("(") || t.isPunct("[") || t.isPunct("{")) {
                depth++;
            } else if (t.isPunct(")") || t.isPunct("]") || t.isPunct("}")) {
                if (depth == 0) {
                    break; // closes a parenthesized query: (SELECT ...)
                }
                depth--;
            } else if (depth == 0 && t.isPunct(",")) {
                items.add(new int[] {itemStart, end});
                itemStart = end + 1;
            } else if (depth == 0 && t.kind == Kind.IDENT
                    && (isFromClause(end)
                            || CLAUSE_END_KEYWORDS.contains(t.upper())) && !followsStar(end)) {
                break;
            }
        }
        items.add(new int[] {itemStart, end});
        return items;
    }

    /**
     * The outermost query's first SELECT. Parentheses that open the statement wrap that query
     * ({@code (SELECT ...)}, {@code ((SELECT ...) UNION ALL ...)}, {@code (WITH s AS (...) SELECT ...)}),
     * so its SELECT sits at their depth; deeper ones belong to CTEs and subqueries.
     */
    private int firstTopLevelSelect() {
        int base = 0;
        int first = nextSignificant(0, tokens.size());
        while (first >= 0 && tokens.get(first).isPunct("(")) {
            base++;
            first = nextSignificant(first + 1, tokens.size());
        }
        int depth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.isPunct("(")) {
                depth++;
            } else if (t.isPunct(")")) {
                depth--;
            } else if (t.isKeyword("SELECT") && depth == base) {
                return i;
            }
        }
        return -1;
    }

    private boolean needsGeneratedName(int start, int end) {
        List<Token> item = significant(start, end);
        if (item.isEmpty() || item.getLast().isPunct("*") || parenthesizedPath(item) != null) {
            return false; // *, t.*, or a column/field reference that keeps its own name
        }
        for (int k = 0; k + 1 < item.size(); k++) {
            if (item.get(k).isPunct("*") && (item.get(k + 1).isKeyword("EXCEPT")
                    || item.get(k + 1).isKeyword("REPLACE"))) {
                return false;
            }
        }
        if (item.size() >= 2 && ((isName(item.getLast()) && item.get(item.size() - 2).isKeyword("AS"))
                || endsWithImplicitAlias(item))) {
            return false; // explicit or implicit alias
        }
        return true;
    }

    /** A path with any number of parentheses around it, or null when the item is an expression. */
    private static List<Token> parenthesizedPath(List<Token> item) {
        List<Token> candidate = item;
        while (candidate.size() >= 3 && enclosesEntireItem(candidate)) {
            candidate = candidate.subList(1, candidate.size() - 1);
        }
        return isPath(candidate) ? candidate : null;
    }

    private static boolean enclosesEntireItem(List<Token> item) {
        if (!item.getFirst().isPunct("(") || !item.getLast().isPunct(")")) {
            return false;
        }
        int depth = 0;
        for (int i = 0; i < item.size(); i++) {
            if (item.get(i).isPunct("(")) {
                depth++;
            } else if (item.get(i).isPunct(")")) {
                depth--;
                if (depth == 0 && i < item.size() - 1) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    /** DuckDB requires its pattern-filtered star and REPLACE modifiers to live in separate SELECTs. */
    private String replacementStarExclusion() {
        int select = firstTopLevelSelect();
        if (select < 0) {
            return null;
        }
        List<int[]> items = selectItems(select);
        if (items == null || items.size() != 1) {
            return null;
        }
        int[] item = items.getFirst();
        for (int i = item[0]; i < item[1]; i++) {
            if (!tokens.get(i).isPunct("*")) {
                continue;
            }
            int next = nextSignificant(i + 1, item[1]);
            if (next >= 0 && tokens.get(next).isKeyword("EXCEPT")) {
                int open = nextSignificant(next + 1, item[1]);
                next = open >= 0 && tokens.get(open).isPunct("(")
                        ? nextSignificant(matchingParen(open) + 1, item[1]) : -1;
            }
            if (next >= 0 && tokens.get(next).isKeyword("REPLACE")) {
                return replacementStarIsLocallyFiltered(i) ? null : projectionStarExclusion(i);
            }
        }
        return null;
    }

    private boolean replacementStarIsLocallyFiltered(int star) {
        for (ProjectionSource source : projectionSourcesForStar(star)) {
            if (!source.directTable()) {
                continue;
            }
            TableRef ref = projectionSourceTable(source.startToken());
            if (source.wildcardTable() || ref == null || columnsOf.apply(ref) == null) {
                return false;
            }
        }
        return true;
    }

    private static boolean isName(Token t) {
        return t.kind == Kind.QIDENT || (t.kind == Kind.IDENT && !NON_ALIAS_KEYWORDS.contains(t.upper()));
    }

    /**
     * Keywords that close a value ({@code CASE ... END}, {@code x IS NOT NULL}, {@code TRUE}), so a name
     * right after one is an implicit alias rather than an operand.
     */
    private static final Set<String> VALUE_ENDING_KEYWORDS = Set.of("END", "NULL", "TRUE", "FALSE");

    /**
     * True when a select item ends in an implicit alias ({@code expr alias}). The last word is not an
     * alias when it is an operand of an operator keyword ({@code a LIKE b}, {@code SUM(x) OVER w}) or
     * part of an interval literal ({@code INTERVAL 1 DAY}, {@code INTERVAL '1:2' HOUR TO MINUTE}).
     */
    private static boolean endsWithImplicitAlias(List<Token> item) {
        if (item.size() < 2) {
            return false;
        }
        Token previous = item.get(item.size() - 2);
        if (!isName(item.getLast()) || !isAliasable(previous)
                || (previous.kind == Kind.IDENT && NON_ALIAS_KEYWORDS.contains(previous.upper())
                        && !VALUE_ENDING_KEYWORDS.contains(previous.upper()))) {
            return false;
        }
        return !endsInsideInterval(item);
    }

    private static boolean endsInsideInterval(List<Token> item) {
        for (int k = 0; k < item.size(); k++) {
            if (item.get(k).isKeyword("INTERVAL") && intervalEnd(item, k) >= item.size() - 1) {
                return true;
            }
        }
        return false;
    }

    private static final Set<String> INTERVAL_PARTS = Set.of(
            "YEAR", "QUARTER", "MONTH", "WEEK", "DAY", "HOUR", "MINUTE", "SECOND", "MILLISECOND", "MICROSECOND");

    /**
     * Index of the last token of the interval literal whose INTERVAL keyword is at {@code start}: its
     * datetime part, or the ending part of a {@code TO} range. The step size before it may be any
     * expression, so a datetime-part word only ends it when the token before can end an operand
     * ({@code INTERVAL n + day DAY}: {@code day} is a column). -1 when no datetime part follows at the
     * same nesting level; the scan stops at the next INTERVAL at that level, so repeated literals stay linear.
     */
    private static int intervalEnd(List<Token> sig, int start) {
        int depth = 0;
        for (int i = start + 1; i < sig.size(); i++) {
            Token t = sig.get(i);
            if (depth == 0 && t.isKeyword("INTERVAL")) {
                return -1;
            } else if (t.isPunct("(") || t.isPunct("[")) {
                depth++;
            } else if (t.isPunct(")") || t.isPunct("]")) {
                if (--depth < 0) {
                    return -1;
                }
            } else if (depth == 0 && i > start + 1 && isIntervalPart(t) && endsOperand(sig.get(i - 1))) {
                return i + 2 < sig.size() && sig.get(i + 1).isKeyword("TO") && isIntervalPart(sig.get(i + 2))
                        ? i + 2 : i;
            } else if (depth == 0 && (t.isPunct(",") || t.isKeyword("FROM")
                    || (t.kind == Kind.IDENT && CLAUSE_END_KEYWORDS.contains(t.upper())))) {
                return -1;
            }
        }
        return -1;
    }

    private static boolean endsOperand(Token t) {
        return t.kind == Kind.NUMBER || t.kind == Kind.STRING || t.kind == Kind.QIDENT
                || t.kind == Kind.NAMED_PARAM || t.kind == Kind.POSITIONAL_PARAM || t.isPunct(")")
                || t.isPunct("]") || t.isKeyword("END")
                || (t.kind == Kind.IDENT && !NON_ALIAS_KEYWORDS.contains(t.upper()));
    }

    private static boolean isIntervalPart(Token t) {
        return t.kind == Kind.IDENT && INTERVAL_PARTS.contains(t.upper());
    }

    private static int closingParen(List<Token> item, int open) {
        int depth = 0;
        for (int i = open; i < item.size(); i++) {
            if (item.get(i).isPunct("(")) {
                depth++;
            } else if (item.get(i).isPunct(")") && --depth == 0) {
                return i;
            }
        }
        return item.size() - 1;
    }

    private static boolean isAliasable(Token beforeLast) {
        return beforeLast.kind == Kind.IDENT || beforeLast.kind == Kind.QIDENT || beforeLast.isPunct(")")
                || beforeLast.isPunct("]") || beforeLast.kind == Kind.STRING || beforeLast.kind == Kind.NUMBER
                || beforeLast.kind == Kind.NAMED_PARAM || beforeLast.kind == Kind.POSITIONAL_PARAM;
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

    /** True for {@code *}, {@code table.*}, and {@code * EXCEPT/REPLACE}, not multiplication. */
    private static boolean isStarProjection(List<Token> item) {
        for (int i = 0; i < item.size(); i++) {
            if (!item.get(i).isPunct("*")) {
                continue;
            }
            if (i == 0 || item.get(i - 1).isPunct(".")
                    || (i + 1 < item.size() && (item.get(i + 1).isKeyword("EXCEPT")
                    || item.get(i + 1).isKeyword("REPLACE")))) {
                return true;
            }
        }
        return false;
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
                    out.append("from_hex(").append(DuckTypes.quoteLiteral(bytesHex(t.value))).append(')');
                    i++;
                    continue;
                }
                case NAMED_PARAM -> {
                    out.append(parameters.named(t.value));
                    i++;
                    continue;
                }
                case POSITIONAL_PARAM -> {
                    out.append(t.value);
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
                    if (expectTable && isQuery(i + 1, close) && !hasTableAlias(close + 1, end)) {
                        out.append(" AS ").append(DuckTypes.quoteIdentifier(
                                "_floci_derived_" + generatedDerivedTableAlias++));
                    }
                    i = close + 1;
                    expectTable = false;
                    continue;
                }
                if (t.text.equals("*")) {
                    int next = nextSignificant(i + 1, end);
                    String mixedSources = next >= 0 && (tokens.get(next).isKeyword("EXCEPT")
                            || tokens.get(next).isKeyword("REPLACE")) ? null : mixedSourceProjectionStar(i);
                    if (mixedSources != null) {
                        out.append(mixedSources);
                        i++;
                        continue;
                    }
                    out.append('*');
                    if (next >= 0 && tokens.get(next).isKeyword("EXCEPT")
                            && nextSignificantIs(next + 1, end, "(")) {
                        int open = nextSignificant(next + 1, end);
                        int close = matchingParen(open);
                        int after = nextSignificant(close + 1, end);
                        if (after >= 0 && tokens.get(after).isKeyword("REPLACE")) {
                            out.append(starExclusionList(i, open, close));
                        } else {
                            out.append(" NOT SIMILAR TO ").append(DuckTypes.quoteLiteral(
                                    starExclusionPattern(i, open, close)));
                        }
                        i = close + 1;
                        continue;
                    }
                    if (isProjectionStar(i) && next >= 0 && tokens.get(next).isKeyword("REPLACE")) {
                        List<String> hidden = presentStarHiddenColumns(i);
                        if (!hidden.isEmpty()) {
                            out.append(" EXCLUDE (").append(hidden.stream().map(DuckTypes::quoteIdentifier)
                                    .collect(Collectors.joining(", "))).append(')');
                        }
                    }
                    if (isProjectionStar(i) && (next < 0 || !tokens.get(next).isKeyword("REPLACE"))) {
                        String exclusion = projectionStarExclusion(i);
                        if (exclusion != null) {
                            out.append(exclusion);
                        }
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

            String record = unnestRangeValue(i);
            if (record != null) {
                out.append(record);
                i++;
                continue;
            }

            if (t.kind == Kind.QIDENT) {
                out.append(quotePath(t.value));
                i++;
                continue;
            }

            // IDENT
            if (upper.equals("FROM") && !isDistinctFromOperator(i)) {
                inFrom = true;
                expectTable = true;
            } else if (upper.equals("JOIN")) {
                // GoogleSQL permits a correlated UNNEST to be introduced with a bare JOIN
                // (without an ON clause). That is an implicit cross join; DuckDB requires
                // the CROSS keyword for the same form.
                int joined = nextSignificant(i + 1, end);
                int modifier = previousSignificant(i - 1);
                boolean bareJoin = modifier < 0 || !(tokens.get(modifier).isKeyword("LEFT")
                        || tokens.get(modifier).isKeyword("RIGHT") || tokens.get(modifier).isKeyword("FULL")
                        || tokens.get(modifier).isKeyword("INNER") || tokens.get(modifier).isKeyword("CROSS"));
                if (bareJoin && joined >= 0 && tokens.get(joined).isKeyword("UNNEST")
                        && !unnestHasJoinCondition(joined, end)) {
                    out.append("CROSS JOIN");
                    expectTable = true;
                    i++;
                    continue;
                }
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
            if (upper.equals("SAFE") && next >= 0 && tokens.get(next).isPunct(".")) {
                int function = nextSignificant(next + 1, end);
                int open = function < 0 ? -1 : nextSignificant(function + 1, end);
                if (function >= 0 && open >= 0 && tokens.get(open).isPunct("(")) {
                    int close = matchingParen(open);
                    String call = renderSafeCall(tokens.get(function).upper(), open, close);
                    if (call != null) {
                        out.append(call);
                        i = close + 1;
                        continue;
                    }
                }
            }
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
            if (!call && DUCKDB_ONLY_RESERVED.contains(t.text.toLowerCase(Locale.ROOT)) && !isFrameBoundRow(i)) {
                out.append(DuckTypes.quoteIdentifier(t.text));
            } else {
                out.append(t.text);
            }
            i++;
        }
        return out.toString();
    }

    /** {@code CURRENT ROW} bounds a window frame: there ROW is the keyword, not a column named row. */
    private boolean isFrameBoundRow(int index) {
        if (!tokens.get(index).isKeyword("ROW")) {
            return false;
        }
        int k = index - 1;
        while (k >= 0 && tokens.get(k).kind == Kind.SPACE) {
            k--;
        }
        return k >= 0 && tokens.get(k).isKeyword("CURRENT");
    }

    private boolean unnestHasJoinCondition(int unnest, int end) {
        int open = nextSignificant(unnest + 1, end);
        if (open < 0 || !tokens.get(open).isPunct("(")) {
            return false;
        }
        int close = matchingParen(open);
        int depth = 0;
        for (int i = close + 1; i < end; i++) {
            Token token = tokens.get(i);
            if (token.isPunct("(")) {
                depth++;
            } else if (token.isPunct(")")) {
                if (depth == 0) {
                    return false;
                }
                depth--;
            } else if (depth == 0 && (token.isKeyword("ON") || token.isKeyword("USING"))) {
                return true;
            } else if (depth == 0 && (token.isPunct(",") || token.isKeyword("JOIN")
                    || token.isKeyword("WHERE") || token.isKeyword("GROUP") || token.isKeyword("HAVING")
                    || token.isKeyword("QUALIFY") || token.isKeyword("WINDOW") || token.isKeyword("ORDER")
                    || token.isKeyword("LIMIT") || token.isKeyword("UNION") || token.isKeyword("INTERSECT")
                    || token.isKeyword("EXCEPT"))) {
                return false;
            }
        }
        return false;
    }

    private boolean isIntervalPartSyntax(int index) {
        List<Integer> significant = significantIndexes();
        List<Token> significantTokens = new ArrayList<>(significant.size());
        int tokenPosition = -1;
        for (int i = 0; i < significant.size(); i++) {
            significantTokens.add(tokens.get(significant.get(i)));
            if (significant.get(i) == index) {
                tokenPosition = i;
            }
        }
        if (tokenPosition < 0) {
            return false;
        }
        for (int i = 0; i < tokenPosition; i++) {
            if (significantTokens.get(i).isKeyword("INTERVAL")
                    && intervalEnd(significantTokens, i) == tokenPosition) {
                return true;
            }
        }
        return false;
    }

    private boolean isDatePartSyntax(int index, int end) {
        int next = nextSignificant(index + 1, end);
        int depth = 0;
        for (int i = index - 1; i >= 0; i--) {
            Token token = tokens.get(i);
            if (token.isPunct(")") || token.isPunct("]")) {
                depth++;
            } else if (token.isPunct("(") || token.isPunct("[")) {
                if (depth > 0) {
                    depth--;
                    continue;
                }
                if (token.isPunct("[")) {
                    return false;
                }
                int callee = previousSignificant(i - 1);
                if (callee < 0 || tokens.get(callee).kind != Kind.IDENT) {
                    return false;
                }
                String function = tokens.get(callee).upper();
                if (function.equals("EXTRACT")) {
                    return next >= 0 && tokens.get(next).isKeyword("FROM");
                }
                if (function.equals("WEEK")) {
                    return true;
                }
                Integer partArgument = DATE_PART_ARGUMENT.get(function);
                return partArgument != null && functionArgumentIndex(i, index) == partArgument;
            }
        }
        return false;
    }

    private int functionArgumentIndex(int open, int index) {
        int argument = 0;
        int parens = 0;
        int brackets = 0;
        int angles = 0;
        for (int i = open + 1; i < index; i++) {
            Token token = tokens.get(i);
            if (token.isPunct("(")) {
                parens++;
            } else if (token.isPunct(")")) {
                parens--;
            } else if (token.isPunct("[")) {
                brackets++;
            } else if (token.isPunct("]")) {
                brackets--;
            } else if (token.isPunct("<") && opensTypeArguments(i, angles)) {
                angles++;
            } else if (angles > 0 && token.isPunct(">")) {
                angles--;
            } else if (angles > 0 && token.isPunct(">>")) {
                angles = Math.max(0, angles - 2);
            } else if (token.isPunct(",") && parens == 0 && brackets == 0 && angles == 0) {
                argument++;
            }
        }
        return argument;
    }

    private String starExclusionPattern(int star, int open, int close) {
        List<String> names = new ArrayList<>(projectionStarHiddenColumns(star));
        for (Token token : significant(open + 1, close)) {
            if (token.kind == Kind.IDENT || token.kind == Kind.QIDENT) {
                names.add(token.identifierText());
            }
        }
        return "(?i)^(" + names.stream().map(SqlDialectTranslator::regexLiteral).collect(Collectors.joining("|"))
                + ")$";
    }

    /** DuckDB supports EXCLUDE followed by REPLACE, unlike its pattern-filtered star form. */
    private String starExclusionList(int star, int open, int close) {
        ProjectionSource source = projectionSourceForStar(star);
        List<String> names = presentStarHiddenColumns(star).stream()
                .filter(name -> !name.equals("_TABLE_SUFFIX") || source != null && source.wildcardTable())
                .map(DuckTypes::quoteIdentifier).collect(Collectors.toCollection(ArrayList::new));
        for (Token token : significant(open + 1, close)) {
            if (token.kind == Kind.IDENT || token.kind == Kind.QIDENT) {
                names.add(DuckTypes.quoteIdentifier(token.identifierText()));
            }
        }
        return " EXCLUDE (" + String.join(", ", names) + ")";
    }

    /** Hidden columns that are known to exist in the staged source and are therefore safe in DuckDB EXCLUDE. */
    private List<String> presentStarHiddenColumns(int star) {
        List<String> names = new ArrayList<>(offsetStarHiddenColumns(star));
        for (ProjectionSource source : projectionSourcesForStar(star)) {
            names.addAll(presentSourceHiddenColumns(source));
        }
        return names.stream().distinct().toList();
    }

    private List<String> presentSourceHiddenColumns(ProjectionSource source) {
        if (!source.directTable()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        TableRef ref = projectionSourceTable(source.startToken());
        Set<String> columns = ref == null ? null : columnsOf.apply(ref);
        if (columns != null) {
            if (columns.contains(BigQueryService.PARTITION_DATE.toLowerCase(Locale.ROOT))) {
                names.add(BigQueryService.PARTITION_DATE);
            }
            if (columns.contains(BigQueryService.PARTITION_TIME.toLowerCase(Locale.ROOT))) {
                names.add(BigQueryService.PARTITION_TIME);
            }
            if (columns.contains(BigQueryService.DML_ORIGINAL_PARTITION_TIME.toLowerCase(Locale.ROOT))) {
                names.add(BigQueryService.DML_ORIGINAL_PARTITION_TIME);
            }
            if (columns.contains(BigQueryService.DML_ORIGINAL_UNPARTITIONED.toLowerCase(Locale.ROOT))) {
                names.add(BigQueryService.DML_ORIGINAL_UNPARTITIONED);
            }
        }
        if (source.wildcardTable() && !names.contains("_TABLE_SUFFIX")) {
            names.add("_TABLE_SUFFIX");
        }
        return names;
    }

    /**
     * DuckDB's filter on an unqualified star applies to every source. When physical and derived
     * sources are mixed, qualify each source instead so physical-table pseudocolumns stay hidden
     * without removing same-named columns that the derived source intentionally exposes.
     */
    private String mixedSourceProjectionStar(int star) {
        if (!isProjectionStar(star) || isPrecededByDot(star)) {
            return null;
        }
        List<ProjectionSource> sources = projectionSourcesForStar(star);
        if (sources.size() < 2 || sources.stream().noneMatch(ProjectionSource::directTable)
                || sources.stream().allMatch(ProjectionSource::directTable)
                || sources.stream().anyMatch(source -> source.alias() == null)) {
            return null;
        }
        if (sources.stream().filter(ProjectionSource::directTable)
                .anyMatch(source -> projectionSourceColumns(source) == null)) {
            return null;
        }
        if (sources.stream().filter(ProjectionSource::directTable)
                .noneMatch(source -> !presentSourceHiddenColumns(source).isEmpty())) {
            return null;
        }
        List<String> projections = new ArrayList<>();
        List<UsingProjection> usingProjections = usingProjectionsForStar(star, sources);
        for (UsingProjection using : usingProjections) {
            String merged = using.sources().stream()
                    .map(source -> DuckTypes.quoteIdentifier(source.alias()) + "."
                            + DuckTypes.quoteIdentifier(using.name()))
                    .collect(Collectors.joining(", ", "COALESCE(", ")"));
            projections.add(merged + " AS " + DuckTypes.quoteIdentifier(using.name()));
        }
        for (ProjectionSource source : sources) {
            String projection = DuckTypes.quoteIdentifier(source.alias()) + ".*";
            List<String> hidden = usingProjections.stream().filter(using -> using.sources().contains(source))
                    .map(UsingProjection::name).collect(Collectors.toCollection(ArrayList::new));
            hidden.addAll(presentSourceHiddenColumns(source));
            if (source.offsetValueColumn() != null) {
                hidden.add(source.offsetValueColumn());
            }
            if (!sourceHasVisibleColumn(source, hidden)) {
                continue;
            }
            if (!hidden.isEmpty()) {
                projection += " NOT SIMILAR TO " + DuckTypes.quoteLiteral("(?i)^(" + hidden.stream()
                        .map(SqlDialectTranslator::regexLiteral).collect(Collectors.joining("|")) + ")$");
            }
            projections.add(projection);
        }
        return String.join(", ", projections);
    }

    private boolean sourceHasVisibleColumn(ProjectionSource source, List<String> hidden) {
        Set<String> columns = projectionSourceColumns(source);
        if (columns == null) {
            return true;
        }
        Set<String> hiddenNames = hidden.stream().map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return columns.stream().anyMatch(column -> !hiddenNames.contains(column));
    }

    private Set<String> projectionSourceColumns(ProjectionSource source) {
        int start = source.startToken();
        if (tokens.get(start).isPunct("(")) {
            return parenthesizedQueryColumns(start);
        }
        if (source.directTable()) {
            TableRef ref = projectionSourceTable(start);
            return ref == null ? null : columnsOf.apply(ref);
        }
        return null;
    }

    private Set<String> parenthesizedQueryColumns(int open) {
        int close = matchingParen(open);
        int depth = parenthesisDepth(open) + 1;
        for (int i = open + 1; i < close; i++) {
            if (!tokens.get(i).isKeyword("SELECT") || parenthesisDepth(i) != depth) {
                continue;
            }
            List<int[]> items = selectItems(i);
            if (items == null) {
                return null;
            }
            Set<String> columns = new LinkedHashSet<>();
            for (int[] item : items) {
                List<Token> expression = significant(item[0], item[1]);
                if (expression.isEmpty() || isStarProjection(expression)) {
                    return null;
                }
                Token name = expression.getLast();
                if (name.kind != Kind.IDENT && name.kind != Kind.QIDENT) {
                    return null;
                }
                columns.add(name.identifierText().toLowerCase(Locale.ROOT));
            }
            return columns;
        }
        return null;
    }

    private record UsingProjection(String name, List<ProjectionSource> sources) {}

    /** Columns and sources merged by top-level JOIN ... USING clauses in this SELECT scope. */
    private List<UsingProjection> usingProjectionsForStar(int star, List<ProjectionSource> sources) {
        int depth = parenthesisDepth(star);
        Map<String, UsingProjection> merged = new LinkedHashMap<>();
        List<String> outputOrder = new ArrayList<>();
        for (int sourceIndex = 1; sourceIndex < sources.size(); sourceIndex++) {
            ProjectionSource joined = sources.get(sourceIndex);
            List<String> joinedColumns = new ArrayList<>();
            for (String column : usingColumnsAfterSource(joined, depth)) {
                String key = column.toLowerCase(Locale.ROOT);
                UsingProjection existing = merged.get(key);
                List<ProjectionSource> participants = existing == null
                        ? sources.subList(0, sourceIndex).stream()
                                .filter(source -> sourceExposesColumn(source, column))
                                .collect(Collectors.toCollection(ArrayList::new))
                        : new ArrayList<>(existing.sources());
                participants.add(joined);
                merged.put(key, new UsingProjection(existing == null ? column : existing.name(), participants));
                joinedColumns.add(key);
            }
            outputOrder.removeAll(joinedColumns);
            outputOrder.addAll(0, joinedColumns);
        }
        return outputOrder.stream().map(merged::get).toList();
    }

    private List<String> usingColumnsAfterSource(ProjectionSource source, int depth) {
        for (int i = source.startToken() + 1; i < tokens.size(); i++) {
            int currentDepth = parenthesisDepth(i);
            Token token = tokens.get(i);
            if (currentDepth < depth || currentDepth == depth && (token.isKeyword("JOIN")
                    || token.isPunct(",") || token.kind == Kind.IDENT
                    && CLAUSE_END_KEYWORDS.contains(token.upper()))) {
                break;
            }
            if (currentDepth != depth || !token.isKeyword("USING")) {
                continue;
            }
            int open = nextSignificant(i + 1, tokens.size());
            if (open < 0 || !tokens.get(open).isPunct("(")) {
                return List.of();
            }
            int close = matchingParen(open);
            return significant(open + 1, close).stream()
                    .filter(candidate -> candidate.kind == Kind.IDENT || candidate.kind == Kind.QIDENT)
                    .map(Token::identifierText).toList();
        }
        return List.of();
    }

    /**
     * A pseudocolumn belongs to a physical table scan, not to a derived table or CTE result that
     * happens to expose a column with the same name. Limit the DuckDB star filter to direct table
     * sources in this SELECT scope.
     */
    private String projectionStarExclusion(int star) {
        List<String> names = projectionStarHiddenColumns(star);
        if (names.isEmpty()) {
            return null;
        }
        return " NOT SIMILAR TO " + DuckTypes.quoteLiteral("^(" + names.stream()
                .map(SqlDialectTranslator::regexLiteral).collect(Collectors.joining("|")) + ")$");
    }

    private List<String> projectionStarHiddenColumns(int star) {
        List<ProjectionSource> sources = projectionSourcesForStar(star);
        List<String> names = new ArrayList<>();
        boolean allDirect = !sources.isEmpty() && sources.stream().allMatch(ProjectionSource::directTable);
        for (int i = 0; i < sources.size(); i++) {
            ProjectionSource source = sources.get(i);
            if (!source.directTable()) {
                continue;
            }
            if (projectionSourceColumns(source) == null) {
                if (allDirect || i == 0) {
                    names.add(BigQueryService.PARTITION_DATE);
                    names.add(BigQueryService.PARTITION_TIME);
                    if (sourceExposesColumn(source, BigQueryService.DML_ORIGINAL_PARTITION_TIME)) {
                        names.add(BigQueryService.DML_ORIGINAL_PARTITION_TIME);
                    }
                    if (sourceExposesColumn(source, BigQueryService.DML_ORIGINAL_UNPARTITIONED)) {
                        names.add(BigQueryService.DML_ORIGINAL_UNPARTITIONED);
                    }
                    names.add("_TABLE_SUFFIX");
                }
            } else {
                names.addAll(presentSourceHiddenColumns(source));
            }
        }
        names.addAll(offsetStarHiddenColumns(star));
        return names.stream().distinct().toList();
    }

    private List<String> offsetStarHiddenColumns(int star) {
        int previous = previousSignificant(star - 1);
        if (previous >= 0 && tokens.get(previous).isPunct(".")) {
            return List.of();
        }
        return projectionSourcesForStar(star).stream().map(ProjectionSource::offsetValueColumn)
                .filter(name -> name != null).toList();
    }

    private ProjectionSource projectionSourceForStar(int star) {
        List<ProjectionSource> sources = projectionSourcesForStar(star);
        return sources.isEmpty() ? null : sources.getFirst();
    }

    private List<ProjectionSource> projectionSourcesForStar(int star) {
        int depth = parenthesisDepth(star);
        int from = -1;
        for (int i = star + 1; i < tokens.size(); i++) {
            int currentDepth = parenthesisDepth(i);
            if (currentDepth < depth) {
                break;
            }
            if (currentDepth == depth && isFromClause(i)) {
                from = i;
                break;
            }
            if (currentDepth == depth && tokens.get(i).kind == Kind.IDENT
                    && CLAUSE_END_KEYWORDS.contains(tokens.get(i).upper()) && !followsStar(i)) {
                break;
            }
        }
        if (from < 0) {
            return List.of();
        }
        int dot = previousSignificant(star - 1);
        String qualifier = null;
        if (dot >= 0 && tokens.get(dot).isPunct(".")) {
            int name = previousSignificant(dot - 1);
            if (name < 0 || (tokens.get(name).kind != Kind.IDENT && tokens.get(name).kind != Kind.QIDENT)) {
                return List.of();
            }
            qualifier = tokens.get(name).identifierText();
        }
        List<ProjectionSource> sources = projectionSources(from, depth);
        String name = qualifier;
        return qualifier == null ? sources : sources.stream()
                .filter(source -> source.alias() != null && source.alias().equalsIgnoreCase(name)).toList();
    }

    private record ProjectionSource(String alias, boolean directTable, boolean wildcardTable,
                                    boolean structAliasCollision, int nextToken, String offsetValueColumn, int startToken) {}

    /** A value-table alias denotes the entire element even when a struct field has that name. */
    private String unnestRangeValue(int index) {
        Token token = tokens.get(index);
        if (token.kind != Kind.IDENT && token.kind != Kind.QIDENT || isPrecededByDot(index)
                || isUsingColumnList(index) || isDatePartSyntax(index, tokens.size())
                || isIntervalPartSyntax(index)) {
            return null;
        }
        int next = nextSignificant(index + 1, tokens.size());
        int previous = previousSignificant(index - 1);
        if (next >= 0 && tokens.get(next).isPunct("(")
                || previous >= 0 && tokens.get(previous).isKeyword("AS")) {
            return null;
        }
        int depth = parenthesisDepth(index);
        int nearestSelect = -1;
        ProjectionSource source = null;
        for (int i = index - 1; i >= 0; i--) {
            depth = Math.min(depth, parenthesisDepth(i));
            if (!tokens.get(i).isKeyword("SELECT") || parenthesisDepth(i) != depth) {
                continue;
            }
            if (nearestSelect < 0) {
                nearestSelect = i;
            }
            int from = queryFrom(i, depth);
            List<ProjectionSource> sources = from < 0 ? List.of() : projectionSources(from, depth);
            source = sources.stream().filter(candidate -> candidate.alias() != null
                    && candidate.alias().equalsIgnoreCase(token.identifierText())).findFirst().orElse(null);
            if (source != null) {
                break;
            }
            if (sources.stream().anyMatch(candidate -> sourceExposesColumn(candidate, token.identifierText()))) {
                return null;
            }
            // A sibling SELECT in a set operation is not an enclosing scope.
            depth--;
        }
        if (source == null || source.nextToken() - 1 == index) {
            return null;
        }
        String value;
        if (source.offsetValueColumn() != null) {
            value = DuckTypes.quoteIdentifier(source.offsetValueColumn());
        } else if (source.structAliasCollision() && !(next >= 0 && tokens.get(next).isPunct("."))) {
            value = "struct_pack(*COLUMNS(" + DuckTypes.quoteIdentifier(source.alias()) + ".*))";
        } else {
            return null;
        }
        List<int[]> items = selectItems(nearestSelect);
        if (items != null) {
            for (int[] item : items) {
                if (implicitSelectAlias(item[0], item[1]) == index) {
                    return null;
                }
                if (nextSignificant(item[0], item[1]) == index
                        && nextSignificant(index + 1, item[1]) < 0) {
                    return value + " AS " + DuckTypes.quoteIdentifier(token.identifierText());
                }
            }
        }
        return value;
    }

    private boolean isUsingColumnList(int index) {
        int depth = 0;
        for (int i = index - 1; i >= 0; i--) {
            if (tokens.get(i).isPunct(")")) {
                depth++;
            } else if (tokens.get(i).isPunct("(")) {
                if (depth == 0) {
                    int previous = previousSignificant(i - 1);
                    return previous >= 0 && tokens.get(previous).isKeyword("USING")
                            && !isQuery(i + 1, matchingParen(i));
                }
                depth--;
            }
        }
        return false;
    }

    private int queryFrom(int select, int depth) {
        for (int i = select + 1; i < tokens.size(); i++) {
            int currentDepth = parenthesisDepth(i);
            if (currentDepth < depth || currentDepth == depth && tokens.get(i).kind == Kind.IDENT
                    && CLAUSE_END_KEYWORDS.contains(tokens.get(i).upper())) {
                break;
            }
            if (currentDepth == depth && isFromClause(i)) {
                return i;
            }
        }
        return -1;
    }

    /** Whether the first SELECT directly inside the parenthesised query at {@code open} outputs {@code name}. */
    private boolean parenthesizedQueryExposesColumn(int open, String name) {
        int close = matchingParen(open);
        int depth = parenthesisDepth(open) + 1;
        for (int i = open + 1; i < close; i++) {
            if (tokens.get(i).isKeyword("SELECT") && parenthesisDepth(i) == depth) {
                List<int[]> items = selectItems(i);
                if (items == null) {
                    return false;
                }
                for (int[] item : items) {
                    List<Token> expression = significant(item[0], item[1]);
                    if (!expression.isEmpty() && isStarProjection(expression)
                            && starProjectionExposesColumn(item[0], item[1], name)) {
                        return true;
                    }
                    if (!expression.isEmpty() && !isStarProjection(expression)
                            && (expression.getLast().kind == Kind.IDENT || expression.getLast().kind == Kind.QIDENT)
                            && expression.getLast().identifierText().equalsIgnoreCase(name)) {
                        return true;
                    }
                }
                return false;
            }
        }
        return false;
    }

    /** Follows a derived star to its source columns, while respecting GoogleSQL's EXCEPT modifier. */
    private boolean starProjectionExposesColumn(int start, int end, String name) {
        int star = -1;
        for (int i = start; i < end; i++) {
            if (tokens.get(i).isPunct("*")) {
                star = i;
                break;
            }
        }
        if (star < 0) {
            return false;
        }
        int modifier = nextSignificant(star + 1, end);
        if (modifier >= 0 && tokens.get(modifier).isKeyword("EXCEPT")) {
            int open = nextSignificant(modifier + 1, end);
            if (open >= 0 && tokens.get(open).isPunct("(")) {
                int close = matchingParen(open);
                boolean excluded = significant(open + 1, close).stream()
                        .filter(token -> token.kind == Kind.IDENT || token.kind == Kind.QIDENT)
                        .anyMatch(token -> token.identifierText().equalsIgnoreCase(name));
                if (excluded) {
                    return false;
                }
            }
        }
        return projectionSourcesForStar(star).stream().anyMatch(source -> sourceExposesColumn(source, name));
    }

    /** The "(" opening the body of the nearest CTE called {@code name} declared before {@code before}. */
    private int cteBodyOpen(String name, int before) {
        for (int i = before - 1; i >= 0; i--) {
            Token token = tokens.get(i);
            if ((token.kind != Kind.IDENT && token.kind != Kind.QIDENT) || !token.identifierText().equalsIgnoreCase(name)) {
                continue;
            }
            int previous = previousSignificant(i - 1);
            int as = nextSignificant(i + 1, tokens.size());
            int open = as < 0 ? -1 : nextSignificant(as + 1, tokens.size());
            if (previous >= 0 && (tokens.get(previous).isKeyword("WITH") || tokens.get(previous).isKeyword("RECURSIVE")
                    || tokens.get(previous).isPunct(",")) && as >= 0 && tokens.get(as).isKeyword("AS")
                    && open >= 0 && tokens.get(open).isPunct("(")) {
                return open;
            }
        }
        return -1;
    }

    private boolean sourceExposesColumn(ProjectionSource source, String name) {
        int start = source.startToken();
        if (tokens.get(start).isPunct("(")) {
            return parenthesizedQueryExposesColumn(start, name);
        } else if (source.directTable()) {
            TableRef ref = projectionSourceTable(start);
            Set<String> columns = ref == null ? null : columnsOf.apply(ref);
            return columns != null && columns.contains(name.toLowerCase(Locale.ROOT));
        } else if (!source.directTable() && (tokens.get(start).kind == Kind.IDENT
                || tokens.get(start).kind == Kind.QIDENT) && !tokens.get(start).isKeyword("UNNEST")) {
            int body = cteBodyOpen(tokens.get(start).identifierText(), start);
            return body >= 0 && parenthesizedQueryExposesColumn(body, name);
        } else if (tokens.get(start).isKeyword("UNNEST")) {
            int open = nextSignificant(start + 1, tokens.size());
            int close = matchingParen(open);
            if (isExplicitStructArray(open, close)) {
                int bracket = nextSignificant(open + 1, close);
                int struct = nextSignificant(bracket + 1, close);
                int structOpen = nextSignificant(struct + 1, close);
                if (structOpen >= 0 && tokens.get(structOpen).isPunct("(")) {
                    return googleSqlStructFields(structOpen, matchingParen(structOpen))
                            .containsKey(name.toLowerCase(Locale.ROOT));
                }
            }
        }
        return false;
    }

    private TableRef projectionSourceTable(int start) {
        List<String> segments = new ArrayList<>();
        int current = start;
        while (current < tokens.size()) {
            Token name = tokens.get(current);
            if (name.kind == Kind.QIDENT) {
                segments.addAll(List.of(name.value.split("\\.", -1)));
            } else if (name.kind == Kind.IDENT) {
                segments.add(name.identifierText());
            } else {
                break;
            }
            int dot = nextSignificant(current + 1, tokens.size());
            int afterDot = dot < 0 ? -1 : nextSignificant(dot + 1, tokens.size());
            if (dot < 0 || !tokens.get(dot).isPunct(".") || afterDot < 0
                    || tokens.get(afterDot).kind != Kind.IDENT && tokens.get(afterDot).kind != Kind.QIDENT) {
                break;
            }
            current = afterDot;
        }
        return tableRefOrNull(segments);
    }

    private List<ProjectionSource> projectionSources(int from, int depth) {
        List<ProjectionSource> sources = new ArrayList<>();
        boolean expectSource = true;
        for (int i = from + 1; i < tokens.size(); i++) {
            int currentDepth = parenthesisDepth(i);
            if (currentDepth < depth) {
                break;
            }
            Token token = tokens.get(i);
            if (currentDepth == depth && token.kind == Kind.IDENT
                    && CLAUSE_END_KEYWORDS.contains(token.upper())) {
                break;
            }
            if (currentDepth == depth && (token.isKeyword("JOIN") || token.isPunct(","))) {
                expectSource = true;
                continue;
            }
            if (!expectSource || currentDepth != depth || token.kind == Kind.SPACE) {
                continue;
            }
            if (token.isKeyword("LATERAL")) {
                continue;
            }
            ProjectionSource source = parseProjectionSource(i);
            if (source == null) {
                expectSource = false;
                continue;
            }
            sources.add(source);
            expectSource = false;
            i = source.nextToken() - 1;
        }
        return sources;
    }

    private ProjectionSource parseProjectionSource(int source) {
        Token first = tokens.get(source);
        if (first.isPunct("(")) {
            int close = matchingParen(source);
            SourceAlias alias = sourceAlias(close + 1, null);
            return new ProjectionSource(alias.name(), false, false, false, alias.nextToken(), null, source);
        }
        if (first.isKeyword("UNNEST")) {
            int open = nextSignificant(source + 1, tokens.size());
            if (open < 0 || !tokens.get(open).isPunct("(")) {
                return null;
            }
            int close = matchingParen(open);
            SourceAlias alias = sourceAlias(close + 1, null);
            int afterAlias = nextSignificant(alias.nextToken(), tokens.size());
            if (alias.name() != null && afterAlias >= 0 && tokens.get(afterAlias).isKeyword("WITH")) {
                UnnestOffset offset = unnestOffset(afterAlias, tokens.size());
                return new ProjectionSource(alias.name(), false, false, false,
                        offset.nextToken(), offsetValueColumn(source), source);
            }
            boolean expanded = alias.name() != null && isExplicitStructArray(open, close)
                    && !(afterAlias >= 0 && tokens.get(afterAlias).isKeyword("WITH"));
            boolean collision = false;
            if (expanded) {
                int bracket = nextSignificant(open + 1, close);
                int struct = nextSignificant(bracket + 1, close);
                int structOpen = nextSignificant(struct + 1, close);
                if (structOpen >= 0 && tokens.get(structOpen).isPunct("(")) {
                    collision = googleSqlStructFields(structOpen, matchingParen(structOpen))
                            .containsKey(alias.name().toLowerCase(Locale.ROOT));
                }
            }
            String valueColumn = expanded ? offsetValueColumn(source) : null;
            return new ProjectionSource(alias.name(), false, false, collision, alias.nextToken(), valueColumn, source);
        }
        if (first.kind != Kind.IDENT && first.kind != Kind.QIDENT) {
            return null;
        }
        List<String> segments = new ArrayList<>();
        int end = source;
        while (end < tokens.size()) {
            Token name = tokens.get(end);
            if (name.kind == Kind.QIDENT) {
                segments.addAll(List.of(name.value.split("\\.", -1)));
            } else if (name.kind == Kind.IDENT) {
                segments.add(name.identifierText());
            } else {
                break;
            }
            int dot = nextSignificant(end + 1, tokens.size());
            int afterDot = dot < 0 ? -1 : nextSignificant(dot + 1, tokens.size());
            if (dot >= 0 && tokens.get(dot).isPunct(".") && afterDot >= 0
                    && (tokens.get(afterDot).kind == Kind.IDENT || tokens.get(afterDot).kind == Kind.QIDENT)) {
                end = afterDot;
                continue;
            }
            end++;
            break;
        }
        SourceAlias alias = sourceAlias(end, segments.getLast());
        boolean direct = segments.size() > 1 || !cteVisibleAt(segments.getFirst(), source);
        boolean wildcard = direct && segments.getLast().endsWith("*");
        return new ProjectionSource(alias.name(), direct, wildcard, false, alias.nextToken(), null, source);
    }

    private record SourceAlias(String name, int nextToken) {}

    private SourceAlias sourceAlias(int start, String implicit) {
        int next = nextSignificant(start, tokens.size());
        if (next < 0) {
            return new SourceAlias(implicit, start);
        }
        if (tokens.get(next).isKeyword("AS")) {
            int name = nextSignificant(next + 1, tokens.size());
            if (name >= 0 && (tokens.get(name).kind == Kind.IDENT || tokens.get(name).kind == Kind.QIDENT)) {
                return new SourceAlias(tokens.get(name).identifierText(), name + 1);
            }
        } else if ((tokens.get(next).kind == Kind.IDENT && !NON_ALIAS_KEYWORDS.contains(tokens.get(next).upper()))
                || tokens.get(next).kind == Kind.QIDENT) {
            return new SourceAlias(tokens.get(next).identifierText(), next + 1);
        }
        return new SourceAlias(implicit, start);
    }

    private static String regexLiteral(String value) {
        return value.replace("\\", "\\\\").replace(".", "\\.").replace("^", "\\^")
                .replace("$", "\\$").replace("|", "\\|").replace("?", "\\?")
                .replace("*", "\\*").replace("+", "\\+").replace("(", "\\(")
                .replace(")", "\\)").replace("[", "\\[").replace("]", "\\]")
                .replace("{", "\\{").replace("}", "\\}");
    }

    private boolean isQuery(int start, int end) {
        int first = nextSignificant(start, end);
        if (first < 0) {
            return false;
        }
        if (tokens.get(first).isKeyword("SELECT") || tokens.get(first).isKeyword("WITH")) {
            return true;
        }
        if (!tokens.get(first).isPunct("(")) {
            return false;
        }

        // A parenthesized join group can also start with '('. Unlike a parenthesized
        // set-operation query, it preserves the range variables declared inside it;
        // turning it into a derived table would hide names such as pw/cw from its
        // enclosing join condition. Only a following set operator identifies this
        // nested form as a query that needs a DuckDB alias.
        int close = matchingParen(first);
        int next = nextSignificant(close + 1, end);
        return next >= 0 && (tokens.get(next).isKeyword("UNION") || tokens.get(next).isKeyword("INTERSECT")
                || tokens.get(next).isKeyword("EXCEPT"));
    }

    private boolean hasTableAlias(int start, int end) {
        int next = nextSignificant(start, end);
        if (next < 0) {
            return false;
        }
        if (tokens.get(next).isKeyword("AS")) {
            return true;
        }
        return tokens.get(next).kind == Kind.QIDENT
                || tokens.get(next).kind == Kind.IDENT && !NON_ALIAS_KEYWORDS.contains(tokens.get(next).upper());
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

    /** Distinguishes a select-list wildcard from multiplication and function stars such as {@code COUNT(*)}. */
    private boolean isProjectionStar(int index) {
        int depth = parenthesisDepth(index);
        int select = precedingSelect(index, depth);
        if (select < 0) {
            return false;
        }
        for (int i = select + 1; i < index; i++) {
            if (parenthesisDepth(i) == depth && isFromClause(i)) {
                return false;
            }
        }
        int previous = previousSignificant(index - 1);
        return previous == select || previous >= 0 && (tokens.get(previous).isPunct(".")
                || tokens.get(previous).isPunct(",") || tokens.get(previous).isKeyword("DISTINCT")
                || tokens.get(previous).isKeyword("ALL"));
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
                StringBuilder segment = new StringBuilder(t.text);
                if (t.text.equalsIgnoreCase("region")) {
                    // Unquoted region qualifiers such as region-us or region-us-central1.
                    int dash = nextSignificant(k + 1, end);
                    while (dash >= 0 && tokens.get(dash).isPunct("-") && dash + 1 < end
                            && (tokens.get(dash + 1).kind == Kind.IDENT || tokens.get(dash + 1).kind == Kind.NUMBER)) {
                        segment.append('-').append(tokens.get(dash + 1).text);
                        k = dash + 1;
                        dash = nextSignificant(k + 1, end);
                    }
                }
                segments.add(segment.toString());
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

        InformationSchema.Ref schemaRef = informationSchemaRef(segments);
        if (schemaRef != null) {
            informationSchema.add(schemaRef);
            out.append(DuckTypes.quoteIdentifier(InformationSchema.SCHEMA)).append('.')
                    .append(DuckTypes.quoteIdentifier(schemaRef.stagedName()));
            String name = alias != null ? alias : schemaRef.view();
            rangeVariables.add(name.toLowerCase(Locale.ROOT));
            out.append(" AS ").append(DuckTypes.quoteIdentifier(name));
            return aliasEnd;
        }
        TableRef ref = resolveTable(segments, i);
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
        boolean leftJoin = isCorrelatedLeftJoin(i);
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
        if (nameIndex < 0) {
            // Unaliased: GoogleSQL expands STRUCT elements into one column per field.
            if (next >= 0 && tokens.get(next).isKeyword("WITH")) {
                UnnestOffset offset = unnestOffset(next, end);
                out.append("LATERAL (SELECT UNNEST([").append(DuckTypes.quoteIdentifier("_value"))
                        .append("], max_depth := 2), ")
                        .append(DuckTypes.quoteIdentifier("_ordinality")).append(" - 1 AS ")
                        .append(DuckTypes.quoteIdentifier(offset.name()))
                        .append(" FROM UNNEST(").append(array).append(") WITH ORDINALITY AS ")
                        .append(DuckTypes.quoteIdentifier("_floci_offset")).append('(')
                        .append(DuckTypes.quoteIdentifier("_value")).append(", ")
                        .append(DuckTypes.quoteIdentifier("_ordinality")).append(")) AS ")
                        .append(DuckTypes.quoteIdentifier("_floci_unnest_" + generatedUnnestAlias++));
                appendLeftJoinCondition(out, offset.nextToken(), end, leftJoin);
                return offset.nextToken();
            }
            if (leftJoin) {
                out.append("LATERAL ");
            }
            out.append("(SELECT UNNEST(").append(array).append(", max_depth := 2))");
            if (tokens.get(i) == mergeUnnestSource) {
                out.append(" AS ").append(DuckTypes.quoteIdentifier(mergeUnnestName));
            } else {
                // DuckDB requires every derived table in FROM to have an alias. GoogleSQL's
                // unaliased UNNEST has no range variable, so use a private, unique one while
                // preserving the expanded STRUCT fields exposed by the inner SELECT.
                out.append(" AS ")
                        .append(DuckTypes.quoteIdentifier("_floci_unnest_" + generatedUnnestAlias++));
            }
            appendLeftJoinCondition(out, close + 1, end, leftJoin);
            return close + 1;
        }
        if (leftJoin) {
            out.append("LATERAL ");
        }
        if (tokens.get(nameIndex).kind == Kind.IDENT || tokens.get(nameIndex).kind == Kind.QIDENT) {
            String element = tokens.get(nameIndex).identifierText();
            int after = nextSignificant(nameIndex + 1, end);
            if (after >= 0 && tokens.get(after).isKeyword("WITH")) {
                UnnestOffset offset = unnestOffset(after, end);
                if (!leftJoin) {
                    out.append("LATERAL ");
                }
                out.append("(SELECT ")
                        .append(DuckTypes.quoteIdentifier("_value")).append(" AS ")
                        .append(DuckTypes.quoteIdentifier(offsetValueColumn(i)))
                        .append(", UNNEST([").append(DuckTypes.quoteIdentifier("_value"))
                        .append("], max_depth := 2) AS ")
                        .append(DuckTypes.quoteIdentifier(element)).append(", ")
                        .append(DuckTypes.quoteIdentifier("_ordinality")).append(" - 1 AS ")
                        .append(DuckTypes.quoteIdentifier(offset.name()))
                        .append(" FROM UNNEST(").append(array).append(") WITH ORDINALITY AS ")
                        .append(DuckTypes.quoteIdentifier("_floci_offset")).append('(')
                        .append(DuckTypes.quoteIdentifier("_value")).append(", ")
                        .append(DuckTypes.quoteIdentifier("_ordinality")).append(")) AS ")
                        .append(DuckTypes.quoteIdentifier("_unnest_" + element));
                appendLeftJoinCondition(out, offset.nextToken(), end, leftJoin);
                return offset.nextToken();
            }
            if (isExplicitStructArray(open, close)) {
                out.append("(SELECT ")
                        .append(DuckTypes.quoteIdentifier("_value")).append(" AS ")
                        .append(DuckTypes.quoteIdentifier(offsetValueColumn(i)))
                        .append(", UNNEST([").append(DuckTypes.quoteIdentifier("_value"))
                        .append("], max_depth := 2) AS ")
                        .append(DuckTypes.quoteIdentifier(element))
                        .append(" FROM UNNEST(").append(array).append(") AS ")
                        .append(DuckTypes.quoteIdentifier("_floci_value")).append('(')
                        .append(DuckTypes.quoteIdentifier("_value")).append(")) AS ")
                        .append(DuckTypes.quoteIdentifier("_unnest_" + element));
                appendLeftJoinCondition(out, nameIndex + 1, end, leftJoin);
                return nameIndex + 1;
            }
            out.append("UNNEST(").append(array).append(')');
            out.append(" AS ").append(DuckTypes.quoteIdentifier("_unnest_" + element)).append('(')
                    .append(DuckTypes.quoteIdentifier(element)).append(')');
            appendLeftJoinCondition(out, nameIndex + 1, end, leftJoin);
            return nameIndex + 1;
        }
        out.append("UNNEST(").append(array).append(')');
        appendLeftJoinCondition(out, close + 1, end, leftJoin);
        return close + 1;
    }

    private String offsetValueColumn(int source) {
        return offsetValueColumns.computeIfAbsent(tokens.get(source),
                ignored -> "_floci_offset_value_" + offsetValueColumns.size());
    }

    private boolean isExplicitStructArray(int open, int close) {
        int bracket = nextSignificant(open + 1, close);
        if (bracket < 0 || !tokens.get(bracket).isPunct("[")) {
            return false;
        }
        int firstElement = nextSignificant(bracket + 1, matchingBracket(bracket));
        return firstElement >= 0 && tokens.get(firstElement).isKeyword("STRUCT");
    }

    private UnnestOffset unnestOffset(int with, int end) {
        int offset = nextSignificant(with + 1, end);
        if (offset < 0 || !tokens.get(offset).isKeyword("OFFSET")) {
            throw invalidQuery("Expected OFFSET after WITH in UNNEST");
        }
        int candidate = nextSignificant(offset + 1, end);
        if (candidate >= 0 && tokens.get(candidate).isKeyword("AS")) {
            int alias = nextSignificant(candidate + 1, end);
            if (alias < 0 || (tokens.get(alias).kind != Kind.IDENT && tokens.get(alias).kind != Kind.QIDENT)) {
                throw invalidQuery("Expected an alias after AS in UNNEST WITH OFFSET");
            }
            return new UnnestOffset(tokens.get(alias).identifierText(), alias + 1);
        }
        if (candidate >= 0 && (tokens.get(candidate).kind == Kind.QIDENT
                || tokens.get(candidate).kind == Kind.IDENT
                && !NON_ALIAS_KEYWORDS.contains(tokens.get(candidate).upper()))) {
            return new UnnestOffset(tokens.get(candidate).identifierText(), candidate + 1);
        }
        return new UnnestOffset("offset", offset + 1);
    }

    private boolean isCorrelatedLeftJoin(int unnest) {
        int join = previousSignificant(unnest - 1);
        if (join < 0 || !tokens.get(join).isKeyword("JOIN")) {
            return false;
        }
        int modifier = previousSignificant(join - 1);
        if (modifier >= 0 && tokens.get(modifier).isKeyword("OUTER")) {
            modifier = previousSignificant(modifier - 1);
        }
        return modifier >= 0 && tokens.get(modifier).isKeyword("LEFT");
    }

    private int previousSignificant(int index) {
        for (int k = index; k >= 0; k--) {
            if (tokens.get(k).kind != Kind.SPACE) {
                return k;
            }
        }
        return -1;
    }

    private void appendLeftJoinCondition(StringBuilder out, int afterUnnest, int end, boolean leftJoin) {
        if (!leftJoin) {
            return;
        }
        int next = nextSignificant(afterUnnest, end);
        if (next < 0 || !(tokens.get(next).isKeyword("ON") || tokens.get(next).isKeyword("USING"))) {
            out.append(" ON TRUE");
        }
    }

    /**
     * {@code [project.]dataset.INFORMATION_SCHEMA.VIEW} or {@code [project.]`region-x`.INFORMATION_SCHEMA.VIEW};
     * SCHEMATA also accepts {@code [project.]INFORMATION_SCHEMA.SCHEMATA}, which reads the US region.
     */
    private InformationSchema.Ref informationSchemaRef(List<String> segments) {
        int marker = -1;
        for (int n = 0; n < segments.size(); n++) {
            if (segments.get(n).equalsIgnoreCase("INFORMATION_SCHEMA")) {
                marker = n;
            }
        }
        if (marker < 0) {
            return null;
        }
        if (marker != segments.size() - 2) {
            throw invalidQuery("Invalid INFORMATION_SCHEMA reference " + String.join(".", segments));
        }
        String view = segments.getLast();
        if (!InformationSchema.isView(view)) {
            throw invalidQuery("INFORMATION_SCHEMA." + view + " is not supported by the floci BigQuery emulator"
                    + " (view names are case-sensitive); supported views: SCHEMATA, TABLES, COLUMNS,"
                    + " COLUMN_FIELD_PATHS, TABLE_OPTIONS, VIEWS, PARTITIONS.");
        }
        List<String> qualifiers = segments.subList(0, marker);
        if (qualifiers.size() > 2) {
            throw invalidQuery("Invalid INFORMATION_SCHEMA reference " + String.join(".", segments));
        }
        if (qualifiers.size() == 2 && !qualifiers.get(0).equals(projectId)) {
            throw invalidQuery("Cross-project queries are not supported by the floci BigQuery emulator: "
                    + String.join(".", segments));
        }
        String scope = qualifiers.isEmpty() ? null : qualifiers.getLast();
        if (scope != null && scope.toLowerCase(Locale.ROOT).startsWith("region-")) {
            if (InformationSchema.datasetOnly(view)) {
                throw invalidQuery("INFORMATION_SCHEMA." + view
                        + " needs a dataset qualifier; region qualifiers are not supported");
            }
            return new InformationSchema.Ref(view, null, scope.substring("region-".length()));
        }
        if (InformationSchema.regionOnly(view)) {
            if (qualifiers.size() == 2 || (scope != null && !scope.equals(projectId))) {
                throw invalidQuery("INFORMATION_SCHEMA." + view + " takes a project or region qualifier, not a"
                        + " dataset");
            }
            return new InformationSchema.Ref(view, null, "us");
        }
        String dataset = scope != null ? scope : defaultDatasetId;
        if (dataset == null || dataset.isBlank()) {
            throw invalidQuery("INFORMATION_SCHEMA." + view + " needs a dataset or region qualifier");
        }
        return new InformationSchema.Ref(view, dataset, null);
    }

    private TableRef resolveTable(List<String> segments) {
        return resolveTable(segments, -1);
    }

    private TableRef resolveTable(List<String> segments, int position) {
        validateWildcard(segments);
        if (segments.size() == 1) {
            String name = segments.getFirst();
            if (position >= 0 && cteVisibleAt(name, position)) {
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
                + " (INFORMATION_SCHEMA is not emulated).");
    }

    private static void validateWildcard(List<String> segments) {
        long wildcards = segments.stream().filter(segment -> segment.indexOf('*') >= 0).count();
        if (wildcards == 0) {
            return;
        }
        if (wildcards != 1 || segments.getLast().indexOf('*') < 0
                || !segments.getLast().endsWith("*") || segments.getLast().length() == 1
                || segments.getLast().indexOf('*') != segments.getLast().length() - 1) {
            throw invalidQuery("Wildcard table references must contain one terminal '*' after a non-empty prefix: "
                    + String.join(".", segments));
        }
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

    /** Renders the lexer-decoded contents of a GoogleSQL BYTES literal without UTF-8 re-encoding. */
    private static String bytesHex(String value) {
        StringBuilder hex = new StringBuilder(value.length() * 2);
        for (int i = 0; i < value.length(); i++) {
            int valueByte = value.charAt(i);
            if (valueByte > 0xff) {
                throw invalidQuery("BYTES literals may contain only bytes, not Unicode characters");
            }
            hex.append(Character.forDigit(valueByte >>> 4, 16));
            hex.append(Character.forDigit(valueByte & 0xf, 16));
        }
        return hex.toString();
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
            case "ARRAY_AGG" -> {
                return renderArrayAgg(original, open, close);
            }
            case "ARRAY_CONCAT_AGG" -> {
                return renderArrayConcatAgg(open, close);
            }
            case "COUNTIF" -> {
                return renderCountIf(open, close);
            }
            case "ANY_VALUE" -> {
                return renderAnyValueHaving(open, close);
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
            // DuckDB skips NULL arguments while BigQuery propagates NULL. Preserve BigQuery's
            // behavior explicitly; this is especially important for distinct counts of pairs.
            case "CONCAT" -> renderConcat(a, argumentRanges(open, close));
            case "LOGICAL_AND" -> "bool_and(" + String.join(", ", a) + ")";
            case "LOGICAL_OR" -> "bool_or(" + String.join(", ", a) + ")";
            case "ARRAY_LENGTH" -> "len(" + String.join(", ", a) + ")";
            case "ARRAY_REVERSE" -> "list_reverse(" + String.join(", ", a) + ")";
            // DuckDB's generate_series is integral-only. Build the integral positions first,
            // then map them to DOUBLE quantile positions for quantile_disc. BigQuery's
            // APPROX_QUANTILES returns observed/discrete values rather than interpolating.
            case "APPROX_QUANTILES" -> renderApproxQuantiles(a, name);
            case "CONTAINS_SUBSTR" -> {
                needsUnicodeCaseFold = true;
                yield args(a, 2, name, "contains(_floci_nfkc_casefold(" + at(a, 0)
                        + "), _floci_nfkc_casefold(" + at(a, 1) + "))");
            }
            // BigQuery emits lowercase hexadecimal text; DuckDB's hex/to_hex emits uppercase.
            case "TO_HEX" -> args(a, 1, name, "lower(hex(" + at(a, 0) + "))");
            // BigQuery's cryptographic hash functions return raw BYTES. DuckDB returns the
            // hexadecimal digest as VARCHAR, so decode it back to a BLOB before exposing it.
            case "MD5" -> args(a, 1, name, "from_hex(md5(" + at(a, 0) + "))");
            case "SHA1" -> args(a, 1, name, "from_hex(sha1(" + at(a, 0) + "))");
            case "SHA256" -> args(a, 1, name, "from_hex(sha256(" + at(a, 0) + "))");
            case "RAND" -> args(a, 0, name, "random()");
            case "GENERATE_ARRAY" -> "generate_series(" + String.join(", ", a) + ")";
            case "GENERATE_DATE_ARRAY" -> generateDateArray(a);
            case "SPLIT" -> a.size() == 1 ? "string_split(" + a.getFirst() + ", ',')"
                    : "string_split(" + String.join(", ", a) + ")";
            case "FORMAT" -> "printf(" + String.join(", ", a) + ")";
            case "INITCAP" -> initcap(a);
            case "TO_JSON_STRING" -> "CAST(to_json(" + at(a, 0) + ") AS VARCHAR)";
            case "JSON_VALUE", "JSON_EXTRACT_SCALAR" -> a.size() == 1
                    ? "json_extract_string(" + a.getFirst() + ", '$')"
                    : "json_extract_string(" + String.join(", ", a) + ")";
            case "JSON_QUERY", "JSON_EXTRACT" -> a.size() == 1
                    ? "json_extract(" + a.getFirst() + ", '$')"
                    : "json_extract(" + String.join(", ", a) + ")";
            // DuckDB names the physical type (UBIGINT, VARCHAR, ...); BigQuery returns the JSON type name.
            case "JSON_TYPE" -> args(a, 1, name, "(CASE json_type(" + at(a, 0) + ")"
                    + " WHEN 'OBJECT' THEN 'object' WHEN 'ARRAY' THEN 'array' WHEN 'VARCHAR' THEN 'string'"
                    + " WHEN 'BOOLEAN' THEN 'boolean' WHEN 'NULL' THEN 'null'"
                    + " WHEN 'BIGINT' THEN 'number' WHEN 'UBIGINT' THEN 'number' WHEN 'HUGEINT' THEN 'number'"
                    + " WHEN 'DOUBLE' THEN 'number' END)");
            case "REGEXP_CONTAINS" -> "regexp_matches(" + String.join(", ", a) + ")";
            case "REGEXP_EXTRACT" -> args(a, 2, name, "regexp_extract(" + at(a, 0) + ", " + at(a, 1) + ", "
                    + (hasCaptureGroup(open, close) ? "1" : "0") + ")");
            case "REGEXP_REPLACE" -> args(a, 3, name, "regexp_replace(" + at(a, 0) + ", " + at(a, 1) + ", "
                    + at(a, 2) + ", 'g')");
            case "CURRENT_TIMESTAMP" -> "current_timestamp";
            case "CURRENT_DATE" -> "current_date";
            case "CURRENT_DATETIME" -> "CAST(current_timestamp AS TIMESTAMP)";
            case "GENERATE_UUID" -> "uuid()";
            case "APPROX_COUNT_DISTINCT" -> args(a, 1, name, "COUNT(DISTINCT " + at(a, 0) + ")");
            case "UNIX_SECONDS" -> "CAST(epoch(" + at(a, 0) + ") AS BIGINT)";
            case "UNIX_MILLIS" -> "epoch_ms(" + at(a, 0) + ")";
            case "UNIX_MICROS" -> "epoch_us(" + at(a, 0) + ")";
            case "UNIX_DATE" -> "date_diff('day', DATE '1970-01-01', " + at(a, 0) + ")";
            case "TIMESTAMP_SECONDS" -> "make_timestamptz(CAST(" + at(a, 0) + " AS BIGINT) * 1000000)";
            case "TIMESTAMP_MILLIS" -> "make_timestamptz(CAST(" + at(a, 0) + " AS BIGINT) * 1000)";
            case "TIMESTAMP_MICROS" -> "make_timestamptz(CAST(" + at(a, 0) + " AS BIGINT))";
            case "TIMESTAMP_ADD" -> args(a, 2, name,
                    "(" + timestampArithmeticValue(at(a, 0)) + " + " + at(a, 1) + ")");
            case "TIMESTAMP_SUB" -> args(a, 2, name,
                    "(" + timestampArithmeticValue(at(a, 0)) + " - " + at(a, 1) + ")");
            case "DATETIME_ADD", "TIME_ADD" -> args(a, 2, name,
                    "(" + at(a, 0) + " + " + at(a, 1) + ")");
            case "DATETIME_SUB", "TIME_SUB" -> args(a, 2, name,
                    "(" + at(a, 0) + " - " + at(a, 1) + ")");
            case "DATE_ADD" -> args(a, 2, name, "CAST(" + at(a, 0) + " + " + at(a, 1) + " AS DATE)");
            case "DATE_SUB" -> args(a, 2, name, "CAST(" + at(a, 0) + " - " + at(a, 1) + " AS DATE)");
            case "TIMESTAMP_DIFF", "DATETIME_DIFF", "DATE_DIFF", "TIME_DIFF" -> args(a, 3, name,
                    dateDiff(at(a, 0), at(a, 1), at(a, 2)));
            case "TIMESTAMP_TRUNC" -> renderTimestampTrunc(a, name);
            case "DATETIME_TRUNC" -> exactArgs(a, 2, name,
                    dateTrunc(at(a, 0), at(a, 1), false, "TIMESTAMP"));
            // DATE_TRUNC is overloaded in BigQuery and returns the type of its first argument.
            // In particular, a TIMESTAMP input must remain a TIMESTAMP rather than becoming a
            // DATE. DuckDB's cast_to_type preserves that runtime type for column expressions too.
            case "DATE_TRUNC" -> renderDateTrunc(a, name);
            case "FORMAT_TIMESTAMP", "FORMAT_DATETIME", "FORMAT_DATE", "FORMAT_TIME" -> args(a, 2, name,
                    // DuckDB's strftime takes no TIME, so a TIME is formatted on a placeholder date it never prints.
                    formatCall(name, name.equals("FORMAT_TIME") ? "(DATE '1970-01-01' + " + at(a, 1) + ")" : at(a, 1), at(a, 0)));
            case "PARSE_TIMESTAMP" -> renderParseTimestamp(a, name, false);
            case "PARSE_DATETIME" -> args(a, 2, name,
                    "strptime(" + at(a, 1) + ", " + timeFormat(at(a, 0)) + ")");
            case "PARSE_DATE" -> args(a, 2, name,
                    "CAST(strptime(" + at(a, 1) + ", " + timeFormat(at(a, 0)) + ") AS DATE)");
            case "DATE" -> a.size() == 3 ? "make_date(" + String.join(", ", a) + ")"
                    : "CAST(" + at(a, 0) + " AS DATE)";
            case "DATETIME" -> "CAST(" + at(a, 0) + " AS TIMESTAMP)";
            case "TIMESTAMP" -> "CAST(" + at(a, 0) + " AS TIMESTAMPTZ)";
            default -> original + "(" + String.join(", ", a) + ")";
        };
    }

    /**
     * {@code GENERATE_DATE_ARRAY(start, end[, INTERVAL n part])}: every date from start to end, both
     * included, one day apart by default. DuckDB's generate_series steps the same way over timestamps and, like
     * BigQuery, returns a NULL array for a NULL argument (which the response shows as {@code []}); BigQuery also
     * rejects a zero step.
     */
    private static String generateDateArray(List<String> a) {
        if (a.size() < 2 || a.size() > 3) {
            throw invalidQuery("No matching signature for function GENERATE_DATE_ARRAY with " + a.size() + " arguments");
        }
        String step = a.size() == 3 ? a.get(2) : "INTERVAL 1 DAY";
        return "(CASE WHEN (" + step + ") = INTERVAL 0 DAY THEN error('GENERATE_ARRAY step cannot be 0.')"
                + " ELSE CAST(generate_series(CAST(CAST(" + a.get(0) + " AS DATE) AS TIMESTAMP),"
                + " CAST(CAST(" + a.get(1) + " AS DATE) AS TIMESTAMP), " + step + ") AS DATE[])"
                + " END)";
    }

    /** COUNT keeps empty-input and window semantics, with DISTINCT outside the conditional. */
    private String renderCountIf(int open, int close) {
        List<int[]> ranges = argumentRanges(open, close);
        if (ranges.size() != 1) {
            return args(arguments(open, close), 1, "COUNTIF", "");
        }
        int first = nextSignificant(open + 1, close);
        boolean distinct = first >= 0 && tokens.get(first).isKeyword("DISTINCT");
        String condition = render(distinct ? first + 1 : open + 1, close).trim();
        return "count(" + (distinct ? "DISTINCT " : "") + "CASE WHEN " + condition + " THEN 1 END)";
    }

    private String renderConcat(List<String> arguments, List<int[]> ranges) {
        if (arguments.isEmpty()) {
            throw invalidQuery("CONCAT requires at least one argument");
        }
        // DuckDB's concat() always returns VARCHAR; || keeps BLOB operands as BLOB and propagates NULL.
        if (ranges.stream().anyMatch(range -> isBytesExpression(range[0], range[1]))) {
            return "(" + arguments.stream().map(argument -> "(" + argument + ")")
                    .collect(Collectors.joining(" || ")) + ")";
        }
        int call = generatedConcatAlias++;
        String value = "_floci_concat_value_" + call;
        List<String> fields = new ArrayList<>(arguments.size());
        List<String> values = new ArrayList<>(arguments.size());
        for (int i = 0; i < arguments.size(); i++) {
            String field = DuckTypes.quoteIdentifier("_floci_concat_" + call + "_" + i);
            fields.add(field);
            values.add(value + "." + field);
        }
        List<String> packed = new ArrayList<>(arguments.size());
        for (int i = 0; i < arguments.size(); i++) {
            packed.add(fields.get(i) + " := " + arguments.get(i));
        }
        return "list_extract(list_transform([struct_pack(" + String.join(", ", packed) + ")], " + value
                + " -> CASE WHEN " + values.stream().map(item -> item + " IS NULL")
                .collect(Collectors.joining(" OR ")) + " THEN NULL ELSE concat("
                + String.join(", ", values) + ") END), 1)";
    }

    private static final Set<String> BYTES_FUNCTIONS = Set.of("FROM_HEX", "FROM_BASE64", "FROM_BASE32", "MD5",
            "SHA1", "SHA256", "SHA512", "CODE_POINTS_TO_BYTES");

    /** Recognises expressions that are statically BYTES: literals, casts to BYTES and bytes-returning calls. */
    private boolean isBytesExpression(int start, int end) {
        List<Token> item = significant(start, end);
        if (item.isEmpty()) {
            return false;
        }
        if (item.size() == 1) {
            return item.getFirst().kind == Kind.BYTES;
        }
        int first = start;
        while (tokens.get(first).kind == Kind.SPACE || tokens.get(first).kind == Kind.RAW) {
            first++;
        }
        int open = nextSignificant(first + 1, end);
        if (tokens.get(first).kind != Kind.IDENT || open < 0 || !tokens.get(open).isPunct("(")
                || nextSignificant(matchingParen(open) + 1, end) >= 0) {
            return false;
        }
        String name = tokens.get(first).upper();
        if (BYTES_FUNCTIONS.contains(name)) {
            return true;
        }
        if (name.equals("CAST") || name.equals("SAFE_CAST")) {
            int as = topLevelAs(open + 1, matchingParen(open));
            int type = as < 0 ? -1 : nextSignificant(as + 1, matchingParen(open));
            return type >= 0 && tokens.get(type).isKeyword("BYTES");
        }
        return false;
    }

    /** GoogleSQL applies ARRAY_CONCAT_AGG's LIMIT to input arrays before concatenating them. */
    private String renderArrayConcatAgg(int open, int close) {
        int limit = -1;
        int depth = 0;
        for (int k = open + 1; k < close; k++) {
            Token token = tokens.get(k);
            if (token.isPunct("(") || token.isPunct("[")) {
                depth++;
            } else if (token.isPunct(")") || token.isPunct("]")) {
                depth--;
            } else if (depth == 0 && !isPrecededByDot(k) && token.isKeyword("LIMIT")) {
                limit = k;
            }
        }
        int bodyEnd = limit >= 0 ? limit : close;
        String arrays = "list(" + render(open + 1, bodyEnd).trim() + ")";
        arrays = "list_filter(" + arrays + ", _floci_concat_array -> _floci_concat_array IS NOT NULL)";
        String concatenated = "_floci_concat_arrays";
        if (limit >= 0) {
            concatenated = "list_slice(" + concatenated + ", 1, " + arrayAggLimit(limit, close) + ")";
        }
        // No non-NULL input arrays at all yields NULL, not an empty array.
        return "list_extract(list_transform([" + arrays + "], _floci_concat_arrays -> CASE WHEN"
                + " coalesce(len(_floci_concat_arrays), 0) = 0 THEN NULL ELSE flatten(" + concatenated
                + ") END), 1)";
    }

    private static String renderApproxQuantiles(List<String> arguments, String name) {
        if (arguments.size() != 2) {
            return args(arguments, 2, name, "");
        }
        String countArgument = arguments.get(1);
        boolean respectNulls = countArgument.matches("(?is).*\\s+RESPECT\\s+NULLS\\s*$");
        String quantileCount = countArgument
                .replaceFirst("(?i)\\s+(?:IGNORE|RESPECT)\\s+NULLS\\s*$", "").trim();
        if (respectNulls) {
            String value = at(arguments, 0);
            String expression = value.replaceFirst("(?i)^DISTINCT\\s+", "").trim();
            String values = "list(" + value + " ORDER BY " + expression + " ASC NULLS FIRST)";
            String index = "CAST(floor(CAST(_floci_quantile_index AS DOUBLE)"
                    + " * (len(_floci_quantile_values) - 1) / CAST(" + quantileCount
                    + " AS DOUBLE)) AS BIGINT) + 1";
            return "list_extract(list_transform([" + values + "], _floci_quantile_values -> CASE WHEN len("
                    + "list_filter(_floci_quantile_values, _floci_quantile_value -> _floci_quantile_value IS NOT NULL))"
                    + " = 0 THEN NULL ELSE list_transform(generate_series(0, CAST(" + quantileCount
                    + " AS BIGINT)), _floci_quantile_index -> list_extract(_floci_quantile_values, " + index
                    + ")) END), 1)";
        }
        return "quantile_disc(" + at(arguments, 0) + ", list_transform(generate_series(0, CAST(" + quantileCount
                + " AS BIGINT)), x -> CAST(x AS DOUBLE) / CAST(" + quantileCount + " AS DOUBLE)))";
    }

    private String renderSafeCall(String function, int open, int close) {
        List<String> a = arguments(open, close);
        return switch (function) {
            case "PARSE_DATE" -> args(a, 2, "SAFE.PARSE_DATE",
                    "CAST(try_strptime(" + at(a, 1) + ", " + timeFormat(at(a, 0)) + ") AS DATE)");
            case "PARSE_TIMESTAMP" -> renderParseTimestamp(a, "SAFE.PARSE_TIMESTAMP", true);
            case "FROM_HEX" -> renderSafeFromHex(a);
            default -> null;
        };
    }

    private static String renderSafeFromHex(List<String> arguments) {
        exactArgs(arguments, 1, "SAFE.FROM_HEX", "");
        return "list_extract(list_transform([coalesce(" + at(arguments, 0)
                + ", CAST(NULL AS VARCHAR))], _floci_hex_value -> CASE WHEN"
                + " regexp_full_match(_floci_hex_value, '[0-9A-Fa-f]*')"
                + " THEN from_hex(_floci_hex_value) END), 1)";
    }

    private static String renderParseTimestamp(List<String> arguments, String name, boolean safe) {
        if (arguments.size() < 2 || arguments.size() > 3) {
            throw invalidQuery("No matching signature for function " + name + " with " + arguments.size()
                    + " arguments");
        }
        String parser = safe ? "try_strptime" : "strptime";
        String parsed = parser + "(" + at(arguments, 1) + ", " + timeFormat(at(arguments, 0)) + ")";
        String zone = arguments.size() == 3 ? at(arguments, 2) : DuckTypes.quoteLiteral("UTC");
        return "list_extract(list_transform([" + parsed + "], _floci_parsed_timestamp -> CASE WHEN"
                + " typeof(_floci_parsed_timestamp) = 'TIMESTAMP WITH TIME ZONE'"
                + " THEN CAST(_floci_parsed_timestamp AS TIMESTAMPTZ) ELSE timezone("
                + zone + ", CAST(_floci_parsed_timestamp AS TIMESTAMP)) END), 1)";
    }

    /** BigQuery's default INITCAP delimiters: whitespace plus {@code [](){}/|\<>!?@"^#$&~_,.:;*%+-}. */
    private static final String INITCAP_DELIMITERS = "(' ' || chr(9) || chr(10) || chr(11) || chr(12) || chr(13)"
            + " || '[](){}/|\\<>!?@\"^#$&~_,.:;*%+-')";

    /**
     * {@code INITCAP(value[, delimiters])}: DuckDB has no equivalent, so walk the characters. A delimiter is
     * kept as is, the character after one (or the first) is upper-cased and every other character lower-cased.
     * A NULL value or delimiter set yields NULL; an empty delimiter set makes the whole value one word. Both
     * arguments are evaluated once, outside the lambdas (a subquery may not appear inside one), and the lambda
     * variables get generated names so they cannot hide a column of the query.
     */
    private String initcap(List<String> a) {
        if (a.isEmpty() || a.size() > 2) {
            throw invalidQuery("No matching signature for function INITCAP with " + a.size() + " arguments");
        }
        String d = a.size() == 2 ? "(" + a.get(1) + ")" : INITCAP_DELIMITERS;
        String v = "_floci_initcap_" + generatedInitcapName++;
        String i = v + "_i";
        String s = v + ".s";
        String ch = "substr(" + s + ", " + i + ", 1)";
        return "list_extract(list_transform([struct_pack(s := (" + a.get(0) + "), d := " + d + ")], " + v
                + " -> CASE WHEN " + s + " IS NULL OR " + v + ".d IS NULL THEN NULL ELSE array_to_string(list_transform("
                + "range(1, length(" + s + ") + 1), " + i + " -> CASE WHEN strpos(" + v + ".d, " + ch + ") > 0 THEN "
                + ch + " WHEN " + i + " = 1 OR strpos(" + v + ".d, substr(" + s + ", " + i + " - 1, 1)) > 0 THEN upper("
                + ch + ") ELSE lower(" + ch + ") END), '') END), 1)";
    }

    private static String args(List<String> a, int expected, String name, String rendered) {
        if (a.size() < expected) {
            throw invalidQuery("No matching signature for function " + name + " with " + a.size() + " arguments");
        }
        return rendered;
    }

    private static String exactArgs(List<String> arguments, int expected, String name, String rendered) {
        if (arguments.size() != expected) {
            throw invalidQuery("No matching signature for function " + name + " with " + arguments.size()
                    + " arguments");
        }
        return rendered;
    }

    private static String at(List<String> a, int index) {
        if (index >= a.size()) {
            throw invalidQuery("Missing function argument " + (index + 1));
        }
        return a.get(index).trim();
    }

    /** BigQuery format elements that DuckDB's strftime and strptime handle the same way. */
    private static final String SHARED_ELEMENTS = "AaBbdGHhIjMmpSTUuVWwXYyZ";

    /** BigQuery composite elements, spelled with elements DuckDB knows; valid for strptime too. */
    private static final Map<String, String> COMPOSITE_ELEMENTS = Map.of(
            "%F", "%Y-%m-%d", "%D", "%m/%d/%y", "%x", "%m/%d/%y", "%R", "%H:%M", "%r", "%I:%M:%S %p",
            "%E4Y", "%Y");

    /** Elements DuckDB lacks or renders differently, emulated in SQL by {@link #emulatedElement}. */
    private static final List<String> EMULATED_ELEMENTS = List.of(
            "%C", "%c", "%e", "%g", "%J", "%k", "%l", "%P", "%Q", "%s", "%E*S");

    /** Every element a format read from a column may use, each rendered with a constant format. */
    private static final List<String> PER_ROW_ELEMENTS = perRowElements();

    private static List<String> perRowElements() {
        List<String> elements = new ArrayList<>(List.of("%%", "%t", "%n", "%z", "%Ez"));
        for (char c : SHARED_ELEMENTS.toCharArray()) {
            elements.add("%" + c);
        }
        elements.addAll(COMPOSITE_ELEMENTS.keySet());
        elements.addAll(EMULATED_ELEMENTS);
        return List.copyOf(elements);
    }

    /**
     * Renders FORMAT_DATE, FORMAT_DATETIME, FORMAT_TIME and FORMAT_TIMESTAMP. A constant format (a
     * literal or a parameter) is split into elements here: those strftime renders like BigQuery stay in
     * one strftime call, the rest are emulated and concatenated. DuckDB only takes a constant strftime
     * format, so a format read from a column is split per row in SQL instead, with a constant strftime
     * per element. The value (and a per-row format) is evaluated once, outside the lambdas: emulated
     * elements read it several times, and a subquery may not appear inside a lambda.
     */
    private static String formatCall(String function, String value, String format) {
        String constant = constantString(format);
        if (constant == null) {
            return perRowFormat(function, value, format);
        }
        List<String> parts = new ArrayList<>();
        StringBuilder pattern = new StringBuilder();
        boolean emulated = false;
        for (String element : formatElements(constant)) {
            String fragment = printedAsWritten(function, element) ? element.replace("%", "%%") : strftimePattern(element);
            if (fragment != null) {
                pattern.append(fragment);
                continue;
            }
            if (!pattern.isEmpty()) {
                parts.add(strftimeCall(FORMAT_VALUE, pattern.toString()));
                pattern.setLength(0);
            }
            parts.add(emulatedElement(FORMAT_VALUE, element));
            emulated = true;
        }
        if (!emulated) {
            return strftimeCall(value, pattern.toString());
        }
        if (!pattern.isEmpty()) {
            parts.add(strftimeCall(FORMAT_VALUE, pattern.toString()));
        }
        String body = parts.size() == 1 ? parts.getFirst() : "(" + String.join(" || ", parts) + ")";
        return "list_extract(list_transform([(" + value + ")], " + FORMAT_VALUE + " -> " + body + "), 1)";
    }

    /**
     * Elements a function prints as written because its type has no such part, as BigQuery does: the time and zone
     * elements for a DATE, the zone elements for a DATETIME, and the date, epoch and zone elements for a TIME.
     */
    private static final Map<String, Set<String>> PRINTED_AS_WRITTEN = Map.of(
            "FORMAT_DATE", Set.of("%c", "%H", "%I", "%k", "%l", "%M", "%P", "%p", "%R", "%r", "%S", "%s", "%T", "%X", "%Z",
                    "%z", "%Ez", "%E*S"),
            "FORMAT_DATETIME", Set.of("%Z", "%z", "%Ez"),
            "FORMAT_TIME", Set.of("%A", "%a", "%B", "%b", "%C", "%c", "%D", "%d", "%e", "%F", "%G", "%g", "%h", "%J", "%j", "%m",
                    "%Q", "%s", "%U", "%u", "%V", "%W", "%w", "%x", "%Y", "%y", "%Z", "%z", "%Ez", "%E4Y"));

    private static boolean printedAsWritten(String function, String element) {
        return PRINTED_AS_WRITTEN.getOrDefault(function, Set.of()).contains(element)
                || function.equals("FORMAT_DATE") && element.matches("%E\\d+S");
    }

    private static final String FORMAT_VALUE = "_floci_format_value";
    private static final String FORMAT_ROW = "_floci_format_row";
    private static final String FORMAT_ELEMENT = "_floci_format_element";

    private static String perRowFormat(String function, String value, String format) {
        String rowValue = FORMAT_ROW + ".v";
        StringBuilder elements = new StringBuilder("CASE " + FORMAT_ELEMENT);
        for (String element : PER_ROW_ELEMENTS) {
            if (printedAsWritten(function, element)) {
                continue;
            }
            String pattern = strftimePattern(element);
            elements.append(" WHEN ").append(DuckTypes.quoteLiteral(element)).append(" THEN ")
                    .append(pattern != null ? strftimeCall(rowValue, pattern) : emulatedElement(rowValue, element));
        }
        elements.append(" ELSE ").append(FORMAT_ELEMENT).append(" END");
        String digits = "ltrim(regexp_extract(" + FORMAT_ELEMENT + ", '[0-9]+'), '0')";
        String fraction = printedAsWritten(function, "%E0S") ? elements.toString() : "CASE WHEN regexp_full_match("
                + FORMAT_ELEMENT + ", '%E[0-9]+S') THEN " + fractionalSeconds(rowValue, "CASE WHEN length(" + digits + ") > 2 THEN "
                + MAX_FRACTION_DIGITS + " ELSE least(CAST('0' || " + digits + " AS INTEGER), " + MAX_FRACTION_DIGITS + ") END")
                + " ELSE " + elements + " END";
        return "list_extract(list_transform([struct_pack(v := (" + value + "), f := (" + format + "))], " + FORMAT_ROW
                + " -> CASE WHEN " + FORMAT_ROW + ".f IS NULL OR " + rowValue + " IS NULL THEN NULL ELSE array_to_string("
                + "list_transform(regexp_extract_all(" + FORMAT_ROW + ".f, '%E\\*S|%E[0-9]+S|%E4Y|%Ez|%.?|[^%]+'), "
                + FORMAT_ELEMENT + " -> " + fraction + "), '') END), 1)";
    }

    private static String strftimeCall(String value, String pattern) {
        return "strftime(" + value + ", " + DuckTypes.quoteLiteral(pattern) + ")";
    }

    /** Splits a format into BigQuery elements ({@code %Y}, {@code %%}, {@code %E*S}, ...) and literal text. */
    private static List<String> formatElements(String format) {
        List<String> elements = new ArrayList<>();
        int i = 0;
        while (i < format.length()) {
            int end;
            if (format.charAt(i) != '%') {
                end = format.indexOf('%', i);
                end = end < 0 ? format.length() : end;
            } else if (i + 1 == format.length()) {
                end = i + 1;
            } else if (format.charAt(i + 1) == 'E') {
                end = extendedElementEnd(format, i + 2);
            } else {
                end = i + 2;
            }
            elements.add(format.substring(i, end));
            i = end;
        }
        return elements;
    }

    /** End of a {@code %E} element whose modifier starts at {@code i}: *S, a digit count and S, 4Y or z. */
    private static int extendedElementEnd(String format, int i) {
        if (format.startsWith("*S", i) || format.startsWith("4Y", i)) {
            return i + 2;
        }
        if (format.startsWith("z", i)) {
            return i + 1;
        }
        int digits = i;
        while (digits < format.length() && Character.isDigit(format.charAt(digits))) {
            digits++;
        }
        return digits > i && digits < format.length() && format.charAt(digits) == 'S' ? digits + 1 : i;
    }

    /**
     * The strftime pattern for one element, or null when it has to be emulated. Literal text has no
     * percent sign; an element BigQuery does not know is printed as written, as BigQuery does.
     */
    private static String strftimePattern(String element) {
        if (element.charAt(0) != '%') {
            return element;
        }
        String composite = COMPOSITE_ELEMENTS.get(element);
        if (composite != null) {
            return composite;
        }
        if (element.length() == 2
                && (element.charAt(1) == '%' || SHARED_ELEMENTS.indexOf(element.charAt(1)) >= 0)) {
            return element;
        }
        if (EMULATED_ELEMENTS.contains(element) || element.matches("%E\\d+S")) {
            return null;
        }
        return switch (element) {
            case "%t" -> "\t";
            case "%n" -> "\n";
            // Values are formatted in UTC: FORMAT_TIMESTAMP's time zone argument is not applied.
            case "%z" -> "+0000";
            case "%Ez" -> "+00:00";
            default -> "%" + element;
        };
    }

    private static String emulatedElement(String value, String element) {
        return switch (element) {
            case "%C" -> "lpad(CAST(year(" + value + ") // 100 AS VARCHAR), 2, '0')";
            case "%c" -> "(strftime(" + value + ", '%a %b ') || " + emulatedElement(value, "%e")
                    + " || strftime(" + value + ", ' %H:%M:%S %Y'))";
            case "%e" -> "lpad(CAST(day(" + value + ") AS VARCHAR), 2, ' ')";
            case "%g" -> "right(strftime(" + value + ", '%G'), 2)";
            case "%J" -> "lpad(CAST((weekofyear(" + value + ") - 1) * 7 + isodow(" + value + ") AS VARCHAR), 3, '0')";
            case "%k" -> "lpad(CAST(hour(" + value + ") AS VARCHAR), 2, ' ')";
            case "%l" -> "lpad(CAST((hour(" + value + ") + 11) % 12 + 1 AS VARCHAR), 2, ' ')";
            case "%P" -> "lower(strftime(" + value + ", '%p'))";
            case "%Q" -> "CAST(quarter(" + value + ") AS VARCHAR)";
            case "%s" -> "CAST((epoch_us(" + value + ") - (epoch_us(" + value + ") % 1000000 + 1000000) % 1000000) // 1000000"
                    + " AS VARCHAR)";
            case "%E*S" -> "(strftime(" + value + ", '%S') || CASE WHEN strftime(" + value + ", '%f') = '000000'"
                    + " THEN '' ELSE '.' || rtrim(strftime(" + value + ", '%f'), '0') END)";
            default -> {
                int digits = fractionDigits(element);
                yield digits == 0 ? strftimeCall(value, "%S") : "(strftime(" + value + ", '%S.') || left(rpad(strftime("
                        + value + ", '%f'), " + digits + ", '0'), " + digits + "))";
            }
        };
    }

    /** BigQuery prints at most this many fractional digits for {@code %E<n>S}, however large n is. */
    private static final int MAX_FRACTION_DIGITS = 18;

    /** The digit count of a {@code %E<n>S} element, capped at {@link #MAX_FRACTION_DIGITS}; leading zeros are ignored. */
    private static int fractionDigits(String element) {
        String digits = element.substring(2, element.length() - 1).replaceFirst("^0+", "");
        return digits.length() > 2 ? MAX_FRACTION_DIGITS : Math.min(digits.isEmpty() ? 0 : Integer.parseInt(digits),
                MAX_FRACTION_DIGITS);
    }

    /** Seconds with {@code digits} (a SQL integer expression) fractional digits, as {@code %E<n>S} prints them. */
    private static String fractionalSeconds(String value, String digits) {
        return "list_extract(list_transform([" + digits + "], _floci_format_digits -> CASE WHEN _floci_format_digits = 0"
                + " THEN strftime(" + value + ", '%S') ELSE strftime(" + value + ", '%S.') || left(rpad(strftime(" + value
                + ", '%f'), _floci_format_digits, '0'), _floci_format_digits) END), 1)";
    }

    /**
     * Format for strptime: composite elements of a constant format (a literal or a parameter) are
     * expanded. DuckDB only takes a constant strptime format, so any other format is passed through.
     */
    private static String timeFormat(String format) {
        String constant = constantString(format);
        if (constant == null) {
            return format;
        }
        StringBuilder out = new StringBuilder(constant.length() + 16);
        for (String element : formatElements(constant)) {
            out.append(COMPOSITE_ELEMENTS.getOrDefault(element, element));
        }
        return DuckTypes.quoteLiteral(out.toString());
    }

    /** The value of a string literal or of a STRING parameter ({@code CAST('...' AS VARCHAR)}); null otherwise. */
    private static String constantString(String sql) {
        String literal = sql.startsWith("CAST(") && sql.endsWith(" AS VARCHAR)")
                ? sql.substring("CAST(".length(), sql.length() - " AS VARCHAR)".length()) : sql;
        return isStringLiteral(literal) ? literal.substring(1, literal.length() - 1).replace("''", "'") : null;
    }

    /** True for exactly one single-quoted SQL string literal, with quotes escaped by doubling. */
    private static boolean isStringLiteral(String sql) {
        if (sql.length() < 2 || sql.charAt(0) != '\'' || sql.charAt(sql.length() - 1) != '\'') {
            return false;
        }
        for (int i = 1; i < sql.length() - 1; i++) {
            if (sql.charAt(i) == '\'') {
                if (sql.charAt(i + 1) != '\'' || i + 1 == sql.length() - 1) {
                    return false;
                }
                i++;
            }
        }
        return true;
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

    /**
     * DuckDB weeks start on Monday; GoogleSQL's WEEK defaults to Sunday and allows every weekday
     * as a week boundary. Shift to DuckDB's Monday boundary, then shift the result back where a
     * truncated value is required.
     */
    private static Integer weekStartOffset(String part) {
        String normalized = part.trim().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (normalized.equals("WEEK")) {
            return 6;
        }
        if (!normalized.startsWith("WEEK(") || !normalized.endsWith(")")) {
            return null;
        }
        return switch (normalized.substring("WEEK(".length(), normalized.length() - 1)) {
            case "MONDAY" -> 0;
            case "TUESDAY" -> 1;
            case "WEDNESDAY" -> 2;
            case "THURSDAY" -> 3;
            case "FRIDAY" -> 4;
            case "SATURDAY" -> 5;
            case "SUNDAY" -> 6;
            default -> null;
        };
    }

    private static String dateDiff(String end, String start, String part) {
        Integer offset = weekStartOffset(part);
        if (offset == null) {
            return "date_diff(" + DuckTypes.quoteLiteral(datePart(part)) + ", " + start + ", " + end + ")";
        }
        String shift = " INTERVAL " + offset + " DAY";
        return "date_diff('week', (" + start + " -" + shift + "), (" + end + " -" + shift + "))";
    }

    private static String dateTrunc(String value, String part, boolean preserveInputType, String literalType) {
        String input = dateTruncValue(value, literalType);
        Integer offset = weekStartOffset(part);
        String rendered;
        if (offset == null) {
            rendered = "date_trunc(" + DuckTypes.quoteLiteral(datePart(part)) + ", " + input + ")";
        } else {
            String shift = " INTERVAL " + offset + " DAY";
            rendered = "(date_trunc('week', " + input + " -" + shift + ") +" + shift + ")";
        }
        return preserveInputType ? "cast_to_type(" + rendered + ", " + input + ")" : rendered;
    }

    private static String renderTimestampTrunc(List<String> arguments, String name) {
        if (arguments.size() < 2 || arguments.size() > 3) {
            throw invalidQuery("No matching signature for function " + name + " with " + arguments.size()
                    + " arguments");
        }
        String input = dateTruncValue(at(arguments, 0), "TIMESTAMPTZ");
        String zone = arguments.size() == 3 ? at(arguments, 2) : DuckTypes.quoteLiteral("UTC");
        String local = "timezone(" + zone + ", " + input + ")";
        String truncated = dateTrunc(local, at(arguments, 1), false, null);
        return "timezone(" + zone + ", " + truncated + ")";
    }

    private static String renderDateTrunc(List<String> arguments, String name) {
        if (arguments.size() == 2) {
            return dateTrunc(at(arguments, 0), at(arguments, 1), true, "DATE");
        }
        return renderTimestampTrunc(arguments, name);
    }

    /**
     * BigQuery coerces a string literal supplied to DATE_TRUNC into DATE. DuckDB cannot pick
     * a date_trunc overload when both its part and value arguments are string literals.
     */
    private static String dateTruncValue(String value, String literalType) {
        return literalType != null && value.startsWith("'")
                ? "CAST(" + value + " AS " + literalType + ")" : value;
    }

    private static String timestampArithmeticValue(String value) {
        return value.startsWith("'") ? "CAST(" + value + " AS TIMESTAMPTZ)" : value;
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
        if (inner.size() >= 3) {
            int fromIndex = -1;
            for (int k = open + 1; k < close; k++) {
                if (tokens.get(k).isKeyword("FROM")) {
                    fromIndex = k;
                    break;
                }
            }
            if (fromIndex < 0) {
                return "EXTRACT(" + render(open + 1, close) + ")";
            }
            String part = text(open + 1, fromIndex).trim().toUpperCase(Locale.ROOT);
            String expr = render(fromIndex + 1, close).trim();
            Integer weekOffset = weekStartOffset(part);
            if (weekOffset != null) {
                return extractWeek(expr, weekOffset);
            }
            return switch (part) {
                case "DAYOFWEEK" -> "(dayofweek(" + expr + ") + 1)";
                case "DAYOFYEAR" -> "dayofyear(" + expr + ")";
                case "DATE" -> "CAST(" + expr + " AS DATE)";
                default -> "EXTRACT(" + part + " FROM " + expr + ")";
            };
        }
        return "EXTRACT(" + render(open + 1, close) + ")";
    }

    /** BigQuery's WEEK(WEEKDAY) numbers are Gregorian-year weeks, with dates before the first
     * requested weekday in the year assigned week zero. */
    private static String extractWeek(String expression, int weekday) {
        String date = "CAST(" + expression + " AS DATE)";
        String yearStart = "make_date(year(" + date + "), 1, 1)";
        // weekStartOffset is Monday-based, while DuckDB dayofweek is Sunday-based.
        int duckWeekday = (weekday + 1) % 7;
        String firstWeekday = "CAST(" + yearStart + " + (((" + duckWeekday + " - dayofweek(" + yearStart
                + ") + 7) % 7) * INTERVAL 1 DAY) AS DATE)";
        return "(CASE WHEN " + date + " < " + firstWeekday + " THEN 0 ELSE (date_diff('day', "
                + firstWeekday + ", " + date + ") // 7) + 1 END)";
    }

    /** {@code STRUCT(a AS x, b AS y)} → {@code {'x': a, 'y': b}}. */
    private String renderStruct(int open, int close) {
        List<int[]> ranges = argumentRanges(open, close);
        if (ranges.isEmpty()) {
            return "{}";
        }
        List<String> inheritedNames = inheritedArrayStructFieldNames(open);
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
            } else {
                expr = render(r[0], r[1]).trim();
            }
            if (n < inheritedNames.size()) {
                name = inheritedNames.get(n);
            } else if (asIndex >= 0) {
                name = significant(asIndex + 1, r[1]).getFirst().identifierText();
            } else {
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

    /**
     * BigQuery gives an array of STRUCT values one positional schema: field names come from the
     * first element even when later STRUCT constructors omit (or use different) aliases. DuckDB
     * instead keeps each struct literal's names, so make later literals use the first schema.
     */
    private List<String> inheritedArrayStructFieldNames(int structOpen) {
        int arrayOpen = -1;
        int bracketDepth = 0;
        for (int i = structOpen - 1; i >= 0; i--) {
            Token token = tokens.get(i);
            if (token.isPunct("]")) {
                bracketDepth++;
            } else if (token.isPunct("[")) {
                if (bracketDepth == 0) {
                    arrayOpen = i;
                    break;
                }
                bracketDepth--;
            }
        }
        if (arrayOpen < 0 || !directArrayElementStruct(arrayOpen, structOpen)) {
            return List.of();
        }

        int firstStruct = nextSignificant(arrayOpen + 1, structOpen);
        if (firstStruct < 0 || !tokens.get(firstStruct).isKeyword("STRUCT")) {
            return List.of();
        }
        int firstOpen = nextSignificant(firstStruct + 1, structOpen + 1);
        if (firstOpen < 0 || !tokens.get(firstOpen).isPunct("(") || firstOpen == structOpen) {
            return List.of();
        }
        int firstClose = matchingParen(firstOpen);
        if (firstClose >= structOpen) {
            return List.of();
        }

        List<String> names = new ArrayList<>();
        List<int[]> firstRanges = argumentRanges(firstOpen, firstClose);
        for (int n = 0; n < firstRanges.size(); n++) {
            int[] range = firstRanges.get(n);
            int asIndex = -1;
            int depth = 0;
            for (int i = range[0]; i < range[1]; i++) {
                Token token = tokens.get(i);
                if (token.isPunct("(")) {
                    depth++;
                } else if (token.isPunct(")")) {
                    depth--;
                } else if (depth == 0 && token.isKeyword("AS")) {
                    asIndex = i;
                }
            }
            if (asIndex >= 0) {
                names.add(significant(asIndex + 1, range[1]).getFirst().identifierText());
            } else {
                List<Token> item = significant(range[0], range[1]);
                names.add(isPath(item) ? item.getLast().identifierText() : "_field_" + (n + 1));
            }
        }
        return names;
    }

    private boolean directArrayElementStruct(int arrayOpen, int structOpen) {
        int struct = previousSignificant(structOpen - 1);
        if (struct < 0 || !tokens.get(struct).isKeyword("STRUCT")) {
            return false;
        }
        int parentheses = 0;
        int brackets = 0;
        for (int i = arrayOpen + 1; i < struct; i++) {
            Token token = tokens.get(i);
            if (token.isPunct("(")) {
                parentheses++;
            } else if (token.isPunct(")")) {
                parentheses--;
            } else if (token.isPunct("[")) {
                brackets++;
            } else if (token.isPunct("]")) {
                brackets--;
            }
        }
        return parentheses == 0 && brackets == 0;
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

    /**
     * {@code ARRAY_AGG([DISTINCT] expr [{IGNORE|RESPECT} NULLS] [ORDER BY …] [LIMIT n])}: DuckDB has no
     * null modifier and no LIMIT inside {@code array_agg}. {@code RESPECT NULLS} is the default and is
     * dropped; {@code IGNORE NULLS} becomes an aggregate {@code FILTER} on the aggregated expression;
     * {@code LIMIT n} keeps the first n elements with {@code list_slice}. BigQuery rejects all of them on
     * the analytic form. Returns null without any of them, so the call is rendered as before.
     */
    private String renderArrayAgg(String original, int open, int close) {
        int modifier = -1;
        int limit = -1;
        int depth = 0;
        for (int k = open + 1; k < close; k++) {
            Token t = tokens.get(k);
            if (t.isPunct("(") || t.isPunct("[")) {
                depth++;
            } else if (t.isPunct(")") || t.isPunct("]")) {
                depth--;
            } else if (depth > 0 || isPrecededByDot(k)) {
                // A field name after a dot may be a keyword: `t.limit`, `t.ignore`.
                continue;
            } else if (t.isKeyword("IGNORE") || t.isKeyword("RESPECT")) {
                int nulls = nextSignificant(k + 1, close);
                if (nulls >= 0 && tokens.get(nulls).isKeyword("NULLS")) {
                    modifier = k;
                }
            } else if (t.isKeyword("LIMIT")) {
                limit = k;
            }
        }
        if (modifier < 0 && limit < 0) {
            return null;
        }
        int over = nextSignificant(close + 1, tokens.size());
        if (over >= 0 && tokens.get(over).isKeyword("OVER")) {
            throw invalidQuery(modifier >= 0
                    ? "Analytic function array_agg does not support IGNORE NULLS or RESPECT NULLS."
                    : "LIMIT in arguments is not supported on analytic functions");
        }
        int bodyEnd = limit >= 0 ? limit : close;
        String call;
        if (modifier < 0) {
            call = original + "(" + render(open + 1, bodyEnd).trim() + ")";
        } else {
            // Each range is rendered once, in source order: rendering consumes positional parameters.
            int expressionStart = nextSignificant(open + 1, modifier);
            if (expressionStart >= 0 && tokens.get(expressionStart).isKeyword("DISTINCT")) {
                expressionStart++;
            }
            String distinct = render(open + 1, expressionStart).trim();
            String expression = render(expressionStart, modifier).trim();
            String rest = render(nextSignificant(modifier + 1, close) + 1, bodyEnd).trim();
            String body = (distinct + " " + expression + " " + rest).trim();
            call = original + "(" + body + ")";
            if (tokens.get(modifier).isKeyword("IGNORE")) {
                call += " FILTER (WHERE (" + expression + ") IS NOT NULL)";
            }
        }
        if (limit >= 0) {
            call = "list_slice(" + call + ", 1, " + arrayAggLimit(limit, close) + ")";
        }
        return call;
    }

    /**
     * The {@code LIMIT} of an {@code ARRAY_AGG}: BigQuery takes only a non-negative integer literal or a
     * query parameter, and rejects a negative parameter value when the query runs.
     */
    private String arrayAggLimit(int limit, int close) {
        List<Token> value = significant(limit + 1, close);
        if (value.size() == 1 && value.getFirst().kind == Kind.NUMBER && value.getFirst().text.matches("\\d+")) {
            return value.getFirst().text;
        }
        if (value.size() == 2 && value.getFirst().isPunct("-") && value.get(1).kind == Kind.NUMBER
                && value.get(1).text.matches("\\d+")) {
            throw invalidQuery("LIMIT expects a non-negative integer literal or parameter");
        }
        if (value.size() == 1 && (value.getFirst().kind == Kind.NAMED_PARAM
                || value.getFirst().kind == Kind.POSITIONAL_PARAM)) {
            String parameter = render(limit + 1, close).trim();
            return "(CASE WHEN (" + parameter + ") < 0 THEN error('LIMIT value should not be negative') ELSE ("
                    + parameter + ") END)";
        }
        throw invalidQuery("LIMIT expects an integer literal or parameter");
    }

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

    /**
     * {@code ANY_VALUE(x HAVING MAX|MIN y)}: x from the rows whose y is the group's maximum (minimum). Rows with a
     * NULL y never qualify, and among tied rows a non-NULL x wins, but a NULL x on the extreme row is not replaced
     * by another row's value. Each row becomes one struct (y, x is non-NULL, x), or NULL when y is NULL, and the
     * max (min) of those structs is taken, so x and y are evaluated once per row. A NaN y makes BigQuery's MAX and
     * MIN NaN, which no row equals, so the result is NULL: a leading flag makes a NaN row win the max (min), and
     * the result is NULL when it does. Returns null when there is no HAVING modifier, so plain ANY_VALUE passes
     * through.
     */
    private String renderAnyValueHaving(int open, int close) {
        int having = -1;
        int depth = 0;
        for (int k = open + 1; k < close && having < 0; k++) {
            Token t = tokens.get(k);
            if (t.isPunct("(")) {
                depth++;
            } else if (t.isPunct(")")) {
                depth--;
            } else if (depth == 0 && t.isKeyword("HAVING")) {
                having = k;
            }
        }
        if (having < 0) {
            return null;
        }
        int bound = nextSignificant(having + 1, close);
        boolean max = bound >= 0 && tokens.get(bound).isKeyword("MAX");
        if (!max && (bound < 0 || !tokens.get(bound).isKeyword("MIN"))) {
            throw invalidQuery("Syntax error: Expected keyword MAX or keyword MIN");
        }
        int after = nextSignificant(close + 1, tokens.size());
        if (after >= 0 && tokens.get(after).isKeyword("OVER")) {
            throw invalidQuery("HAVING modifier is not supported on analytic functions");
        }
        String value = render(open + 1, having).strip();
        String key = render(bound + 1, close).strip();
        String row = "_floci_any_value_" + generatedAnyValueName++;
        String nan = "(typeof(" + row + ".k) IN ('DOUBLE', 'FLOAT') AND isnan(TRY_CAST(CAST(" + row
                + ".k AS VARCHAR) AS DOUBLE)))";
        String best = (max ? "max" : "min") + "(list_extract(list_transform([struct_pack(k := (" + key + "), v := (" + value
                + "))], " + row + " -> CASE WHEN " + row + ".k IS NULL THEN NULL ELSE struct_pack(z := " + (max ? "" : "NOT ")
                + nan + ", k := " + row + ".k, n := " + row + ".v IS " + (max ? "NOT NULL" : "NULL") + ", v := " + row
                + ".v) END), 1))";
        String winner = row + "_m";
        return "list_extract(list_transform([" + best + "], " + winner + " -> CASE WHEN " + (max ? "" : "NOT ") + winner
                + ".z THEN NULL ELSE " + winner + ".v END), 1)";
    }

    /** True when the token at {@code index} starts a FROM clause, not the FROM of {@code IS [NOT] DISTINCT FROM}. */
    private boolean isFromClause(int index) {
        return tokens.get(index).isKeyword("FROM") && !isDistinctFromOperator(index);
    }

    /** True when the FROM at {@code index} belongs to {@code IS [NOT] DISTINCT FROM}, not a FROM clause. */
    private boolean isDistinctFromOperator(int index) {
        int distinct = significantIndexBefore(index);
        if (distinct < 0 || !tokens.get(distinct).isKeyword("DISTINCT")) {
            return false;
        }
        int before = significantIndexBefore(distinct);
        return before >= 0 && (tokens.get(before).isKeyword("IS") || tokens.get(before).isKeyword("NOT"));
    }

    private int significantIndexBefore(int index) {
        for (int k = index - 1; k >= 0; k--) {
            if (tokens.get(k).kind != Kind.SPACE) {
                return k;
            }
        }
        return -1;
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

    private int matchingBracket(int open) {
        int depth = 0;
        for (int k = open; k < tokens.size(); k++) {
            Token token = tokens.get(k);
            if (token.isPunct("[")) {
                depth++;
            } else if (token.isPunct("]")) {
                depth--;
                if (depth == 0) {
                    return k;
                }
            }
        }
        return -1;
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
        /** Decoded content for STRING/BYTES/QIDENT, the name for NAMED_PARAM, or a bound positional literal. */
        final String value;
        /** A rewritten "SELECT ..." head that must still act as its query's SELECT when resolving scopes. */
        private boolean selectHead;

        Token(Kind kind, String text, String value) {
            this.kind = kind;
            this.text = text;
            this.value = value;
        }

        static Token raw(String text) {
            return new Token(Kind.RAW, text, text);
        }

        static Token rawSelectHead(String text) {
            Token token = raw(text);
            token.selectHead = true;
            return token;
        }

        String upper() {
            return text.toUpperCase(Locale.ROOT);
        }

        boolean isKeyword(String keyword) {
            return kind == Kind.IDENT && text.equalsIgnoreCase(keyword)
                    || selectHead && keyword.equals("SELECT");
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
            List<Token> out = new TokenList();
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
                        case 'a' -> value.append('\u0007');
                        case 'b' -> value.append('\b');
                        case 'f' -> value.append('\f');
                        case 'n' -> value.append('\n');
                        case 't' -> value.append('\t');
                        case 'r' -> value.append('\r');
                        case 'v' -> value.append('\u000b');
                        case 'x', 'X' -> value.append((char) readEscapeDigits(2, 16, "hexadecimal"));
                        case 'u' -> appendUnicodeEscape(value, bytes, 4);
                        case 'U' -> appendUnicodeEscape(value, bytes, 8);
                        case '0', '1', '2', '3', '4', '5', '6', '7' -> {
                            int octal = (e - '0') * 64 + readEscapeDigits(2, 8, "octal");
                            if (octal > 0xff) {
                                throw invalidQuery("Syntax error: Octal escape sequence exceeds one byte");
                            }
                            value.append((char) octal);
                        }
                        default -> value.append(e);
                    }
                    continue;
                }
                if (bytes && c > 0x7f) {
                    throw invalidQuery("BYTES literals may contain only ASCII characters or byte escapes");
                }
                value.append(c);
            }
            return new Token(bytes ? Kind.BYTES : Kind.STRING, s.substring(start, pos), value.toString());
        }

        private int readEscapeDigits(int count, int radix, String description) {
            if (pos + count > s.length()) {
                throw invalidQuery("Syntax error: Incomplete " + description + " escape sequence");
            }
            int value = 0;
            for (int i = 0; i < count; i++) {
                int digit = Character.digit(s.charAt(pos++), radix);
                if (digit < 0) {
                    throw invalidQuery("Syntax error: Invalid " + description + " escape sequence");
                }
                value = value * radix + digit;
            }
            return value;
        }

        private void appendUnicodeEscape(StringBuilder value, boolean bytes, int digits) {
            if (bytes) {
                throw invalidQuery("Unicode escape sequences are not allowed in BYTES literals");
            }
            int codePoint = readEscapeDigits(digits, 16, "Unicode");
            if (!Character.isValidCodePoint(codePoint)
                    || codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
                throw invalidQuery("Syntax error: Invalid Unicode code point in escape sequence");
            }
            value.appendCodePoint(codePoint);
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
