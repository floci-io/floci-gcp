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

    /**
     * {@code duplicateOutputNames} lists the top-level select-list names written more than once, before
     * BigQuery's suffixes; a CREATE TABLE or CREATE VIEW statement rejects those.
     */
    record Translation(String sql, Set<TableRef> tables, Set<InformationSchema.Ref> informationSchema,
                       boolean needsUnicodeCaseFold, List<String> duplicateOutputNames,
                       List<MissingStructField> missingStructFields, OrderByFieldPaths orderByFieldPaths) {

        Translation(String sql, Set<TableRef> tables, Set<InformationSchema.Ref> informationSchema,
                    boolean needsUnicodeCaseFold) {
            this(sql, tables, informationSchema, needsUnicodeCaseFold, List.of(), List.of(), null);
        }
    }

    /**
     * An {@code alias.field} in ORDER BY naming a field the STRUCT select alias does not have, as written, and
     * whether it belongs to the outermost query, whose aliases are the result's columns.
     */
    record MissingStructField(String alias, String field, boolean outermost, Set<List<String>> anonymousFields) {}

    /**
     * The dotted paths, such as {@code s.zz}, left in the outermost query's ORDER BY once the translator has
     * expanded the fields of STRUCT select aliases it can read, and the query rendered without that ORDER BY.
     */
    record OrderByFieldPaths(String sqlWithoutOrderBy, List<List<String>> paths) {}

    /**
     * A supported script translated for one DuckDB session. {@code setupSql} holds temporary
     * macros and statements whose results BigQuery discards; {@code sql} is the final query or DML.
     */
    record ScriptTranslation(String sql, String setupSql, Set<TableRef> tables,
                             Set<InformationSchema.Ref> informationSchema, boolean needsUnicodeCaseFold,
                             List<String> duplicateOutputNames, Statement finalStatement,
                             List<MissingStructField> missingStructFields, OrderByFieldPaths orderByFieldPaths) {}

    sealed interface ScriptItem permits SqlItem, IfBlock, BeginBlock, WhileBlock, LoopBlock, RepeatBlock,
            ForBlock, CaseBlock, LoopControl {}
    record SqlItem(String sql) implements ScriptItem {}
    record IfBlock(String condition, List<ScriptItem> whenTrue, List<ScriptItem> whenFalse) implements ScriptItem {}
    record BeginBlock(String label, List<ScriptItem> items) implements ScriptItem {}
    record WhileBlock(String label, String condition, List<ScriptItem> items) implements ScriptItem {}
    record LoopBlock(String label, List<ScriptItem> items) implements ScriptItem {}
    record RepeatBlock(String label, String condition, List<ScriptItem> items) implements ScriptItem {}
    record ForBlock(String label, String variable, String query, List<ScriptItem> items) implements ScriptItem {}
    record CaseWhen(String condition, List<ScriptItem> items) {}
    record CaseBlock(String expression, List<CaseWhen> branches, List<ScriptItem> otherwise) implements ScriptItem {
        private static final Pattern SINGLE_OPERAND = Pattern.compile(
                "(?i)[A-Za-z_][A-Za-z0-9_.]*|[-+]?[0-9]+(?:\\.[0-9]+)?|'(?:[^']|'')*'");

        String condition(CaseWhen branch) {
            return expression != null
                    ? groupedOperand(expression) + " = " + groupedOperand(branch.condition())
                    : branch.condition();
        }

        /** BigQuery compares the whole WHEN operand, so an expression needs its own parentheses. */
        private static String groupedOperand(String operand) {
            String trimmed = operand.trim();
            return SINGLE_OPERAND.matcher(trimmed).matches() ? trimmed : "(" + trimmed + ")";
        }
    }
    record LoopControl(boolean continueLoop, String label) implements ScriptItem {}
    record ProceduralScript(List<ScriptItem> items) {}

    private record UnnestOffset(String name, int nextToken) {}

    private static final Set<String> CLAUSE_END_KEYWORDS = Set.of(
            "WHERE", "GROUP", "HAVING", "QUALIFY", "WINDOW", "ORDER", "LIMIT", "OFFSET",
            "UNION", "INTERSECT", "EXCEPT", "SELECT");

    /** Statements BigQuery runs as a script even when the request holds only that statement. */
    private static final Set<String> SCRIPT_STATEMENT_KEYWORDS = Set.of("DECLARE", "SET", "BEGIN", "IF", "WHILE",
            "LOOP", "REPEAT", "FOR", "CASE", "BREAK", "LEAVE", "CONTINUE", "ITERATE");

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
            "GENERATE_ARRAY", "GENERATE_DATE_ARRAY", "GENERATE_TIMESTAMP_ARRAY", "SPLIT", "FORMAT", "APPROX_QUANTILES",
            "TO_JSON_STRING", "JSON_VALUE", "JSON_EXTRACT_SCALAR", "JSON_QUERY", "JSON_EXTRACT", "JSON_TYPE",
            "REGEXP_CONTAINS", "REGEXP_EXTRACT", "REGEXP_EXTRACT_ALL", "REGEXP_REPLACE", "CURRENT_TIMESTAMP",
            "CURRENT_DATE",
            "CURRENT_DATETIME", "UNIX_SECONDS", "UNIX_MILLIS", "UNIX_MICROS", "UNIX_DATE",
            "TIMESTAMP_SECONDS", "TIMESTAMP_MILLIS", "TIMESTAMP_MICROS", "TIMESTAMP_ADD", "DATETIME_ADD",
            "TIME_ADD", "TIMESTAMP_SUB", "DATETIME_SUB", "TIME_SUB", "DATE_ADD", "DATE_SUB",
            "TIMESTAMP_DIFF", "DATETIME_DIFF", "DATE_DIFF", "TIME_DIFF", "TIMESTAMP_TRUNC", "DATETIME_TRUNC",
            "DATE_TRUNC", "FORMAT_TIMESTAMP", "FORMAT_DATETIME", "FORMAT_DATE", "FORMAT_TIME",
            "PARSE_TIMESTAMP", "PARSE_DATETIME", "PARSE_DATE", "DATE", "DATETIME", "TIMESTAMP",
            "GENERATE_UUID", "ARRAY_SLICE");

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
    /** The project a dataset-qualified name ({@code ds.t}) or an unqualified dataset name belongs to. */
    private final String jobProjectId;
    private final String defaultDatasetId;
    private final QueryParameters parameters;
    /** Names bound by a preceding CREATE TEMP TABLE in the current script. */
    private final Set<String> temporaryTables;
    private final List<CteScope> cteScopes = new ArrayList<>();
    /** Names a generated projection may not take, including expanded fields and sibling outputs. */
    private final Set<String> structStarTakenNames = new HashSet<>();
    /** Selects whose {@code *} columns are being resolved, so a recursive CTE cannot loop. */
    private final Set<Integer> structStarColumnScopes = new HashSet<>();
    private final Set<TableRef> tables = new LinkedHashSet<>();
    private final Set<InformationSchema.Ref> informationSchema = new LinkedHashSet<>();
    private final Map<Token, String> offsetValueColumns = new IdentityHashMap<>();
    private int generatedUnnestAlias;
    private int generatedDerivedTableAlias;
    private int generatedConcatAlias;
    private int generatedInitcapName;
    private int generatedAnyValueName;
    private boolean needsUnicodeCaseFold;
    private final List<MissingStructField> missingStructFields = new ArrayList<>();
    /**
     * The {@code _field_<position>} keys of anonymous fields, per field map from {@link #googleSqlStructFields}.
     * Star expansion reads those keys, but no {@code alias.field} reference can name an anonymous field, as in
     * BigQuery, and the missing-field message writes it by type alone.
     */
    private final Map<Map<String, List<Token>>, Set<List<String>>> anonymousStructFields = new IdentityHashMap<>();
    private boolean queryResult;
    private boolean scalarUdfBody;
    private boolean scalarUdfNamedTableSource;
    private Map<String, String> scalarUdfParameterAliases = Map.of();

    private List<Token> tokens;
    /** The {@code UNNEST} token of an unaliased MERGE source, rendered with {@link #mergeUnnestName} as its name. */
    private Token mergeUnnestSource;
    private String mergeUnnestName;
    /** The alias of an aliased MERGE {@code UNNEST} source, which subqueries can still reference. */
    private String mergeUnnestAlias;
    /** A table's lower-cased column names, or null when they are unknown. */
    private Function<TableRef, Set<String>> columnsOf = ref -> null;
    private Function<TableRef, List<TableFieldSchema>> fieldsOf = ref -> null;
    /** The top-level select's written output names, or null when one of them cannot be derived. */
    private List<String> topLevelOutputNames;
    /** The top-level names written more than once, in select order. */
    private List<String> duplicateOutputNames = List.of();
    /** Guards the recursion that resolves a star's source columns through nested select lists. */
    private final Set<Integer> writtenNameScopes = new HashSet<>();
    /** True when a struct-star rewrite already assigned the select list's output names. */
    private boolean outputNamesHandled;
    /** The MERGE source table, when the source is a table rather than a subquery. */
    private TableRef mergeSource;
    private MergeScope mergeScope;

    /**
     * Names that refer to the MERGE target or source; the columns known of either (null when neither's
     * are known); and whether those are all of their columns.
     */
    private record MergeScope(Set<String> names, Set<String> columns, boolean complete) {}

    private record CteScope(String name, Token start, Token end) {}

    /** The project {@code _SESSION} belongs to, as BigQuery names it in a not-found error. */
    private String sessionDatasetProject() {
        return projectId != null && !projectId.isBlank() ? projectId : jobProjectId;
    }

    private SqlDialectTranslator(String projectId, String defaultDatasetId, QueryParameters parameters) {
        this(projectId, projectId, defaultDatasetId, parameters, Set.of());
    }

    private SqlDialectTranslator(String projectId, String defaultDatasetId, QueryParameters parameters,
                                 Set<String> temporaryTables) {
        this(projectId, projectId, defaultDatasetId, parameters, temporaryTables);
    }

    /**
     * {@code projectId} holds the tables the statement can reach, the default dataset among them;
     * {@code jobProjectId} is the project the job runs in, which dataset-qualified names resolve to.
     */
    private SqlDialectTranslator(String projectId, String jobProjectId, String defaultDatasetId,
                                 QueryParameters parameters) {
        this(projectId, jobProjectId, defaultDatasetId, parameters, Set.of());
    }

    private SqlDialectTranslator(String projectId, String jobProjectId, String defaultDatasetId,
                                 QueryParameters parameters, Set<String> temporaryTables) {
        this.projectId = projectId;
        this.jobProjectId = jobProjectId;
        this.defaultDatasetId = defaultDatasetId;
        this.parameters = parameters;
        this.temporaryTables = Set.copyOf(temporaryTables);
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
        return translate(sql, projectId, projectId, defaultDatasetId, parameters, columnsOf);
    }

    /** As {@link #translate(String, String, String, QueryParameters, Function)} for a job run in {@code jobProjectId}. */
    static Translation translate(String sql, String projectId, String jobProjectId, String defaultDatasetId,
                                 QueryParameters parameters, Function<TableRef, Set<String>> columnsOf) {
        return translate(sql, projectId, jobProjectId, defaultDatasetId, parameters, columnsOf, ref -> null);
    }

    static Translation translate(String sql, String projectId, String jobProjectId, String defaultDatasetId,
                                 QueryParameters parameters, Function<TableRef, Set<String>> columnsOf,
                                 Function<TableRef, List<TableFieldSchema>> fieldsOf) {
        SqlDialectTranslator translator = new SqlDialectTranslator(projectId, jobProjectId, defaultDatasetId,
                parameters);
        translator.columnsOf = columnsOf;
        translator.fieldsOf = fieldsOf;
        return translator.run(sql);
    }

    /** Translates one statement, treating {@code temporaryTables} as request-local names. */
    static Translation translateStatement(String sql, String projectId, String jobProjectId, String defaultDatasetId,
                                          QueryParameters parameters, Collection<String> temporaryTables) {
        return translateWithTemporaryTables(sql, projectId, jobProjectId, defaultDatasetId, parameters,
                Set.copyOf(temporaryTables));
    }

    private static Translation translateWithTemporaryTables(String sql, String projectId, String jobProjectId,
                                                            String defaultDatasetId, QueryParameters parameters,
                                                            Set<String> temporaryTables) {
        return translateWithTemporaryTables(sql, projectId, jobProjectId, defaultDatasetId, parameters,
                temporaryTables, ref -> null);
    }

    private static Translation translateWithTemporaryTables(String sql, String projectId, String jobProjectId,
                                                            String defaultDatasetId, QueryParameters parameters,
                                                            Set<String> temporaryTables,
                                                            Function<TableRef, Set<String>> columnsOf) {
        return translateWithTemporaryTables(sql, projectId, jobProjectId, defaultDatasetId, parameters,
                temporaryTables, columnsOf, ref -> null);
    }

    private static Translation translateWithTemporaryTables(String sql, String projectId, String jobProjectId,
                                                            String defaultDatasetId, QueryParameters parameters,
                                                            Set<String> temporaryTables,
                                                            Function<TableRef, Set<String>> columnsOf,
                                                            Function<TableRef, List<TableFieldSchema>> fieldsOf) {
        SqlDialectTranslator translator = new SqlDialectTranslator(projectId, jobProjectId, defaultDatasetId,
                parameters, temporaryTables);
        translator.columnsOf = columnsOf;
        translator.fieldsOf = fieldsOf;
        return translator.run(sql);
    }

    private static Translation translateDmlWithTemporaryTables(String sql, String projectId, String jobProjectId,
                                                               String defaultDatasetId, QueryParameters parameters,
                                                               Set<String> temporaryTables) {
        return translateDmlWithTemporaryTables(sql, projectId, jobProjectId, defaultDatasetId, parameters,
                temporaryTables, ref -> null);
    }

    private static Translation translateDmlWithTemporaryTables(String sql, String projectId, String jobProjectId,
                                                               String defaultDatasetId, QueryParameters parameters,
                                                               Set<String> temporaryTables,
                                                               Function<TableRef, Set<String>> columnsOf) {
        return translateDmlWithTemporaryTables(sql, projectId, jobProjectId, defaultDatasetId, parameters,
                temporaryTables, columnsOf, ref -> null);
    }

    private static Translation translateDmlWithTemporaryTables(String sql, String projectId, String jobProjectId,
                                                               String defaultDatasetId, QueryParameters parameters,
                                                               Set<String> temporaryTables,
                                                               Function<TableRef, Set<String>> columnsOf,
                                                               Function<TableRef, List<TableFieldSchema>> fieldsOf) {
        SqlDialectTranslator translator = new SqlDialectTranslator(projectId, jobProjectId, defaultDatasetId,
                parameters, temporaryTables);
        translator.columnsOf = columnsOf;
        translator.fieldsOf = fieldsOf;
        return translator.runDml(sql);
    }

    /**
     * Translates the request-local portion of a BigQuery script. Statements are kept in source
     * order: temporary functions and tables, and result-discarding queries, can be interleaved in
     * any order before the final query or DML. Temporary objects disappear with DuckDB's request
     * session, just as they do with a BigQuery script job.
     *
     * <p>Intermediate persistent DML/DDL is intentionally excluded. Applying it would require
     * atomically committing every changed staged table back to the emulator; the Duck sidecar
     * protocol presently returns only one follow-up result.</p>
     */
    static ScriptTranslation translateScript(String sql, String projectId, String defaultDatasetId,
                                             QueryParameters parameters) {
        return translateScript(sql, projectId, projectId, defaultDatasetId, parameters, ref -> null);
    }

    static ScriptTranslation translateScript(String sql, String projectId, String defaultDatasetId,
                                             QueryParameters parameters,
                                             Function<TableRef, Set<String>> columnsOf) {
        return translateScript(sql, projectId, projectId, defaultDatasetId, parameters, columnsOf);
    }

    static ScriptTranslation translateScript(String sql, String projectId, String jobProjectId,
                                             String defaultDatasetId, QueryParameters parameters) {
        return translateScript(sql, projectId, jobProjectId, defaultDatasetId, parameters, ref -> null);
    }

    static ScriptTranslation translateScript(String sql, String projectId, String jobProjectId,
                                             String defaultDatasetId, QueryParameters parameters,
                                             Function<TableRef, Set<String>> columnsOf) {
        return translateScript(sql, projectId, jobProjectId, defaultDatasetId, parameters, columnsOf, ref -> null);
    }

    static ScriptTranslation translateScript(String sql, String projectId, String jobProjectId,
                                             String defaultDatasetId, QueryParameters parameters,
                                             Function<TableRef, Set<String>> columnsOf,
                                             Function<TableRef, List<TableFieldSchema>> fieldsOf) {
        List<String> statements = splitScript(sql);
        if (statements.isEmpty()) {
            throw invalidQuery("Syntax error: Unexpected end of script");
        }
        // A request-level multi-statement script never reaches here with positional parameters:
        // BigQueryService.query rejects it, because control flow may run statements in another order
        // than the parameters were supplied. The SQL here can still be multi-statement when the
        // service prepends request-local temporary objects to one dynamic statement, and that
        // statement's own positional parameters are bound in source order.
        Set<TableRef> tables = new LinkedHashSet<>();
        Set<InformationSchema.Ref> informationSchema = new LinkedHashSet<>();
        Set<String> temporaryTables = new LinkedHashSet<>();
        Set<String> temporaryFunctions = new LinkedHashSet<>();
        StringBuilder setup = new StringBuilder();
        boolean needsUnicodeCaseFold = false;
        for (int i = 0; i < statements.size(); i++) {
            String statement = statements.get(i);
            boolean last = i == statements.size() - 1;
            if (temporaryFunction(statement)) {
                if (last) {
                    throw invalidQuery("A script must end with a query or DML statement.");
                }
                TemporaryFunctionDefinition definition = temporaryFunctionDefinition(statement);
                Translation macro = translateTemporaryFunction(statement, projectId, jobProjectId, defaultDatasetId,
                        parameters, temporaryTables, temporaryFunctions);
                setup.append(macro.sql()).append(";\n");
                temporaryFunctions.add(definition.name().toLowerCase(Locale.ROOT));
                tables.addAll(macro.tables());
                informationSchema.addAll(macro.informationSchema());
                needsUnicodeCaseFold |= macro.needsUnicodeCaseFold();
                continue;
            }
            if (temporaryTable(statement)) {
                if (last) {
                    throw invalidQuery("A script must end with a query or DML statement.");
                }
                TemporaryTableTranslation temporary = translateTemporaryTable(statement, projectId, jobProjectId,
                        defaultDatasetId, parameters, temporaryTables, columnsOf, fieldsOf);
                Translation table = temporary.translation();
                setup.append(table.sql()).append(";\n");
                tables.addAll(table.tables());
                informationSchema.addAll(table.informationSchema());
                needsUnicodeCaseFold |= table.needsUnicodeCaseFold();
                temporaryTables.add(temporary.name());
                continue;
            }
            String droppedTemporaryTable = droppedTemporaryTable(statement, temporaryTables);
            if (droppedTemporaryTable != null) {
                if (last) {
                    throw invalidQuery("A script must end with a query or DML statement.");
                }
                setup.append("DROP TABLE ").append(DuckTypes.quoteIdentifier(droppedTemporaryTable)).append(";\n");
                temporaryTables.remove(droppedTemporaryTable);
                continue;
            }
            if (!last) {
                String temporaryTarget = temporaryDmlTarget(statement, temporaryTables);
                if (temporaryTarget != null) {
                    Translation mutation = translateDmlWithTemporaryTables(statement, projectId, jobProjectId,
                            defaultDatasetId, parameters, temporaryTables, columnsOf, fieldsOf);
                    setup.append(mutation.sql()).append(";\n");
                    tables.addAll(mutation.tables());
                    informationSchema.addAll(mutation.informationSchema());
                    needsUnicodeCaseFold |= mutation.needsUnicodeCaseFold();
                    continue;
                }
                Statement intermediate = parseStatement(statement, projectId, jobProjectId, defaultDatasetId);
                if (intermediate.kind() != StatementKind.QUERY) {
                    throw invalidQuery("An intermediate script statement must be CREATE TEMP FUNCTION, CREATE TEMP "
                            + "TABLE, DROP TABLE for a temporary table, or a query. Intermediate persistent DML "
                            + "and DDL are not supported by the floci BigQuery emulator yet.");
                }
                Translation query = translateWithTemporaryTables(statement, projectId, jobProjectId,
                        defaultDatasetId, parameters, temporaryTables, columnsOf, fieldsOf);
                setup.append(query.sql()).append(";\n");
                tables.addAll(query.tables());
                informationSchema.addAll(query.informationSchema());
                needsUnicodeCaseFold |= query.needsUnicodeCaseFold();
                continue;
            }
            String temporaryTarget = temporaryDmlTarget(statement, temporaryTables);
            Statement finalStatement = parseStatement(statement, projectId, jobProjectId,
                    temporaryTarget != null ? "_floci_temporary" : defaultDatasetId, temporaryTables);
            Translation query = finalStatement.isDml()
                    ? translateDmlWithTemporaryTables(statement, projectId, jobProjectId, defaultDatasetId, parameters,
                            temporaryTables, columnsOf, fieldsOf)
                    : translateWithTemporaryTables(statement, projectId, jobProjectId, defaultDatasetId, parameters,
                            temporaryTables, columnsOf, fieldsOf);
            return new ScriptTranslation(query.sql(), setup.toString(), tablesWith(tables, query.tables()),
                    informationSchemaWith(informationSchema, query.informationSchema()),
                    needsUnicodeCaseFold || query.needsUnicodeCaseFold(), query.duplicateOutputNames(),
                    finalStatement, query.missingStructFields(), query.orderByFieldPaths());
        }
        throw invalidQuery("A script must end with a query or DML statement.");
    }

    static Statement scriptFinalStatement(String sql, String projectId, String defaultDatasetId) {
        return scriptFinalStatement(sql, projectId, projectId, defaultDatasetId);
    }

    static Statement scriptFinalStatement(String sql, String projectId, String jobProjectId, String defaultDatasetId) {
        List<String> statements = splitScript(sql);
        if (statements.isEmpty()) {
            throw invalidQuery("Syntax error: Unexpected end of script");
        }
        return parseStatement(statements.getLast(), projectId, jobProjectId, defaultDatasetId);
    }

    /**
     * A request of one statement that only a script can run: a declaration, an assignment, control flow
     * or a loop-control statement. BigQuery runs such a request as a script, so it returns no rows when
     * no statement produces a result.
     */
    static boolean singleStatementScript(String sql) {
        List<String> statements = splitScript(sql);
        if (statements.size() != 1) {
            return false;
        }
        List<Token> tokens = significant(new Lexer(statements.getFirst()).tokenize());
        return !tokens.isEmpty() && SCRIPT_STATEMENT_KEYWORDS.stream().anyMatch(tokens.getFirst()::isKeyword);
    }

    static boolean multiStatementScript(String sql) {
        List<String> statements = splitScript(sql);
        if (statements.size() <= 1) {
            return false;
        }
        for (int i = 0; i < statements.size() - 1; i++) {
            if (!temporaryFunction(statements.get(i))) {
                return true;
            }
        }
        List<Token> finalTokens = significant(new Lexer(statements.getLast()).tokenize());
        return finalTokens.isEmpty() || !(finalTokens.getFirst().isKeyword("SELECT")
                || finalTokens.getFirst().isKeyword("WITH") || finalTokens.getFirst().isPunct("("));
    }

    /** Parses BigQuery script statements and nested IF blocks without prescribing their contents. */
    static ProceduralScript proceduralScript(String sql) {
        ScriptParser parser = new ScriptParser(sql);
        return new ProceduralScript(parser.parseBlock(false));
    }

    private static final class ScriptParser {
        private final List<Token> tokens;
        private int at;
        private int blockDepth;

        ScriptParser(String sql) {
            tokens = new Lexer(sql).tokenize();
        }

        List<ScriptItem> parseBlock(boolean stopAtControl) {
            blockDepth++;
            if (blockDepth > 51) {
                blockDepth--;
                throw invalidQuery("Procedural scripts may not nest blocks and conditionals more than 50 levels.");
            }
            try {
                return parseBlockContents(stopAtControl);
            } finally {
                blockDepth--;
            }
        }

        private List<ScriptItem> parseBlockContents(boolean stopAtControl) {
            List<ScriptItem> items = new ArrayList<>();
            while (true) {
                skipTrivia();
                if (at >= tokens.size() || stopAtControl && controlAt(at)) {
                    return items;
                }
                if (tokens.get(at).isPunct(";")) {
                    throw invalidQuery("Empty statements are not allowed in a multi-statement query.");
                }
                String label = consumeLabel();
                if (tokens.get(at).isKeyword("IF")) {
                    if (label != null) {
                        throw invalidQuery("Labels are supported only on BEGIN blocks and loops.");
                    }
                    items.add(parseIf());
                    continue;
                }
                if (tokens.get(at).isKeyword("CASE")) {
                    if (label != null) {
                        throw invalidQuery("Labels are supported only on BEGIN blocks and loops.");
                    }
                    items.add(parseCase());
                    continue;
                }
                if (tokens.get(at).isKeyword("BEGIN")) {
                    items.add(parseBegin(label));
                    continue;
                }
                if (tokens.get(at).isKeyword("WHILE")) {
                    items.add(parseWhile(label));
                    continue;
                }
                if (tokens.get(at).isKeyword("LOOP")) {
                    items.add(parseLoop(label));
                    continue;
                }
                if (tokens.get(at).isKeyword("REPEAT")) {
                    items.add(parseRepeat(label));
                    continue;
                }
                if (tokens.get(at).isKeyword("FOR")) {
                    items.add(parseFor(label));
                    continue;
                }
                if (label != null) {
                    throw invalidQuery("A label must precede a BEGIN block or loop.");
                }
                if (tokens.get(at).isKeyword("BREAK") || tokens.get(at).isKeyword("LEAVE")
                        || tokens.get(at).isKeyword("CONTINUE") || tokens.get(at).isKeyword("ITERATE")) {
                    items.add(parseLoopControl());
                    continue;
                }
                int start = at;
                int depth = 0;
                while (at < tokens.size()) {
                    Token token = tokens.get(at++);
                    if (token.isPunct("(")) {
                        depth++;
                    } else if (token.isPunct(")")) {
                        depth--;
                    } else if (token.isPunct(";") && depth == 0) {
                        break;
                    }
                }
                String statement = text(tokens, start, at).replaceFirst(";\\s*$", "").trim();
                if (!statement.isEmpty()) {
                    items.add(new SqlItem(statement));
                }
            }
        }

        private ForBlock parseFor(String label) {
            at++;
            skipTrivia();
            if (at >= tokens.size() || tokens.get(at).kind != Kind.IDENT) {
                throw invalidQuery("FOR loop requires a loop variable.");
            }
            String variable = tokens.get(at++).text;
            skipTrivia();
            if (at >= tokens.size() || !tokens.get(at).isKeyword("IN")) {
                throw invalidQuery("FOR loop requires IN.");
            }
            at++;
            skipTrivia();
            if (at >= tokens.size() || !tokens.get(at).isPunct("(")) {
                throw invalidQuery("FOR loop requires a parenthesized table expression.");
            }
            int queryStart = ++at;
            int depth = 1;
            while (at < tokens.size() && depth > 0) {
                if (tokens.get(at).isPunct("(")) {
                    depth++;
                } else if (tokens.get(at).isPunct(")")) {
                    depth--;
                }
                at++;
            }
            if (depth != 0) {
                throw invalidQuery("FOR loop table expression is missing a closing parenthesis.");
            }
            String query = text(tokens, queryStart, at - 1).trim();
            int doIndex = findControl("DO");
            at = doIndex + 1;
            List<ScriptItem> items = parseBlock(true);
            skipTrivia();
            if (at >= tokens.size() || !tokens.get(at).isKeyword("END")) {
                throw invalidQuery("FOR loop requires END FOR.");
            }
            at++;
            skipTrivia();
            if (at >= tokens.size() || !tokens.get(at).isKeyword("FOR")) {
                throw invalidQuery("END must be followed by FOR.");
            }
            at++;
            consumeClosingLabel(label, "FOR");
            consumeOptionalSemicolon();
            return new ForBlock(label, variable, query, items);
        }

        private LoopBlock parseLoop(String label) {
            at++;
            List<ScriptItem> items = parseBlock(true);
            skipTrivia();
            if (at >= tokens.size() || !tokens.get(at).isKeyword("END")) {
                throw invalidQuery("LOOP requires END LOOP.");
            }
            at++;
            skipTrivia();
            if (at >= tokens.size() || !tokens.get(at).isKeyword("LOOP")) {
                throw invalidQuery("END must be followed by LOOP.");
            }
            at++;
            consumeClosingLabel(label, "LOOP");
            consumeOptionalSemicolon();
            return new LoopBlock(label, items);
        }

        private RepeatBlock parseRepeat(String label) {
            at++;
            List<ScriptItem> items = parseBlock(true);
            skipTrivia();
            if (at >= tokens.size() || !tokens.get(at).isKeyword("UNTIL")) {
                throw invalidQuery("REPEAT loop requires UNTIL.");
            }
            at++;
            int conditionStart = at;
            int depth = 0;
            int caseDepth = 0;
            while (at < tokens.size()) {
                Token token = tokens.get(at);
                if (token.isPunct("(")) {
                    depth++;
                } else if (token.isPunct(")")) {
                    depth--;
                } else if (token.isKeyword("CASE")) {
                    caseDepth++;
                } else if (token.isKeyword("END") && caseDepth > 0) {
                    caseDepth--;
                } else if (depth == 0 && caseDepth == 0 && token.isKeyword("END")) {
                    break;
                }
                at++;
            }
            String condition = text(tokens, conditionStart, at).trim();
            skipTrivia();
            if (at >= tokens.size() || !tokens.get(at).isKeyword("END")) {
                throw invalidQuery("REPEAT loop requires END REPEAT.");
            }
            at++;
            skipTrivia();
            if (at >= tokens.size() || !tokens.get(at).isKeyword("REPEAT")) {
                throw invalidQuery("END must be followed by REPEAT.");
            }
            at++;
            consumeClosingLabel(label, "REPEAT");
            consumeOptionalSemicolon();
            return new RepeatBlock(label, condition, items);
        }

        private String consumeLabel() {
            if (at >= tokens.size() || (tokens.get(at).kind != Kind.IDENT && tokens.get(at).kind != Kind.QIDENT)) {
                return null;
            }
            int colon = nextSignificant(at + 1);
            if (colon < 0 || !tokens.get(colon).isPunct(":")) {
                return null;
            }
            String label = identifier(tokens.get(at));
            at = colon + 1;
            skipTrivia();
            return label;
        }

        private int nextSignificant(int from) {
            for (int index = from; index < tokens.size(); index++) {
                if (tokens.get(index).kind != Kind.SPACE) {
                    return index;
                }
            }
            return -1;
        }

        private void consumeClosingLabel(String openingLabel, String construct) {
            skipTrivia();
            if (at >= tokens.size() || (tokens.get(at).kind != Kind.IDENT && tokens.get(at).kind != Kind.QIDENT)) {
                return;
            }
            String closingLabel = identifier(tokens.get(at++));
            if (openingLabel == null || !openingLabel.equalsIgnoreCase(closingLabel)) {
                throw invalidQuery("Closing " + construct + " label does not match its opening label.");
            }
            skipTrivia();
        }

        private static String identifier(Token token) {
            return token.kind == Kind.QIDENT ? token.value : token.text;
        }

        private void consumeOptionalSemicolon() {
            skipTrivia();
            if (at < tokens.size() && tokens.get(at).isPunct(";")) {
                at++;
            }
        }

        private LoopControl parseLoopControl() {
            boolean continueLoop = tokens.get(at).isKeyword("CONTINUE") || tokens.get(at).isKeyword("ITERATE");
            String keyword = tokens.get(at++).upper();
            skipTrivia();
            String label = null;
            if (at < tokens.size() && (tokens.get(at).kind == Kind.IDENT || tokens.get(at).kind == Kind.QIDENT)) {
                label = identifier(tokens.get(at++));
                skipTrivia();
            }
            if (at < tokens.size() && tokens.get(at).isPunct(";")) {
                at++;
            } else {
                throw invalidQuery(keyword + " statement requires a semicolon.");
            }
            return new LoopControl(continueLoop, label);
        }

        private WhileBlock parseWhile(String label) {
            int whileIndex = at++;
            int doIndex = findControl("DO");
            String condition = text(tokens, whileIndex + 1, doIndex).trim();
            at = doIndex + 1;
            List<ScriptItem> items = parseBlock(true);
            skipTrivia();
            if (at >= tokens.size() || !tokens.get(at).isKeyword("END")) {
                throw invalidQuery("WHILE loop requires END WHILE.");
            }
            at++;
            skipTrivia();
            if (at >= tokens.size() || !tokens.get(at).isKeyword("WHILE")) {
                throw invalidQuery("END must be followed by WHILE in a procedural WHILE loop.");
            }
            at++;
            skipTrivia();
            consumeClosingLabel(label, "WHILE");
            consumeOptionalSemicolon();
            return new WhileBlock(label, condition, items);
        }

        private BeginBlock parseBegin(String label) {
            at++;
            skipTrivia();
            if (at < tokens.size() && tokens.get(at).isKeyword("TRANSACTION")) {
                throw invalidQuery("Transactions are not supported in procedural scripts.");
            }
            List<ScriptItem> items = parseBlock(true);
            skipTrivia();
            if (at >= tokens.size() || !tokens.get(at).isKeyword("END")) {
                throw invalidQuery("BEGIN block requires END.");
            }
            at++;
            skipTrivia();
            consumeClosingLabel(label, "BEGIN");
            consumeOptionalSemicolon();
            return new BeginBlock(label, items);
        }

        private CaseBlock parseCase() {
            at++;
            skipTrivia();
            String expression = null;
            if (at >= tokens.size()) {
                throw invalidQuery("CASE statement requires at least one WHEN clause.");
            }
            if (!tokens.get(at).isKeyword("WHEN")) {
                int firstWhen = findControl("WHEN");
                expression = text(tokens, at, firstWhen).trim();
                at = firstWhen;
            }
            List<CaseWhen> branches = new ArrayList<>();
            while (at < tokens.size() && tokens.get(at).isKeyword("WHEN")) {
                int whenIndex = at++;
                int thenIndex = findControl("THEN");
                String condition = text(tokens, whenIndex + 1, thenIndex).trim();
                at = thenIndex + 1;
                branches.add(new CaseWhen(condition, parseBlock(true)));
                skipTrivia();
            }
            List<ScriptItem> otherwise = List.of();
            if (at < tokens.size() && tokens.get(at).isKeyword("ELSE")) {
                at++;
                otherwise = parseBlock(true);
                skipTrivia();
            }
            if (at >= tokens.size() || !tokens.get(at).isKeyword("END")) {
                throw invalidQuery("CASE statement requires END CASE.");
            }
            at++;
            skipTrivia();
            if (at >= tokens.size() || !tokens.get(at).isKeyword("CASE")) {
                throw invalidQuery("END must be followed by CASE in a procedural CASE statement.");
            }
            at++;
            consumeOptionalSemicolon();
            return new CaseBlock(expression, branches, otherwise);
        }

        private IfBlock parseIf() {
            int ifIndex = at++;
            int then = findControl("THEN");
            String condition = text(tokens, ifIndex + 1, then).trim();
            at = then + 1;
            List<ScriptItem> whenTrue = parseBlock(true);
            List<ScriptItem> whenFalse = List.of();
            skipTrivia();
            if (at < tokens.size() && tokens.get(at).isKeyword("ELSE")) {
                at++;
                whenFalse = parseBlock(true);
            } else if (at < tokens.size() && tokens.get(at).upper().equals("ELSEIF")) {
                tokens.set(at, new Token(Kind.IDENT, "IF", "IF"));
                return new IfBlock(condition, whenTrue, List.of(parseIf()));
            }
            skipTrivia();
            if (at >= tokens.size() || !tokens.get(at).isKeyword("END")) {
                throw invalidQuery("IF statement requires END IF.");
            }
            at++;
            skipTrivia();
            if (at >= tokens.size() || !tokens.get(at).isKeyword("IF")) {
                throw invalidQuery("END must be followed by IF in a procedural IF statement.");
            }
            at++;
            skipTrivia();
            if (at < tokens.size() && tokens.get(at).isPunct(";")) {
                at++;
            }
            return new IfBlock(condition, whenTrue, whenFalse);
        }

        private int findControl(String keyword) {
            int depth = 0;
            int caseDepth = 0;
            for (int i = at; i < tokens.size(); i++) {
                Token token = tokens.get(i);
                if (token.isPunct("(")) {
                    depth++;
                } else if (token.isPunct(")")) {
                    depth--;
                } else if (token.isKeyword("CASE")) {
                    caseDepth++;
                } else if (token.isKeyword("END") && caseDepth > 0) {
                    caseDepth--;
                } else if (depth == 0 && caseDepth == 0 && token.isKeyword(keyword)) {
                    return i;
                }
            }
            throw invalidQuery("IF statement requires THEN.");
        }

        private boolean controlAt(int index) {
            return tokens.get(index).isKeyword("ELSE") || tokens.get(index).isKeyword("END")
                    || tokens.get(index).isKeyword("WHEN") || tokens.get(index).isKeyword("UNTIL")
                    || tokens.get(index).upper().equals("ELSEIF");
        }

        private void skipTrivia() {
            while (at < tokens.size() && (tokens.get(at).kind == Kind.SPACE || tokens.get(at).kind == Kind.RAW)) {
                at++;
            }
        }
    }

    private static String text(List<Token> tokens, int from, int to) {
        StringBuilder result = new StringBuilder();
        for (int i = from; i < to; i++) {
            result.append(tokens.get(i).text);
        }
        return result.toString();
    }

    private static Set<TableRef> tablesWith(Set<TableRef> current, Set<TableRef> additional) {
        Set<TableRef> all = new LinkedHashSet<>(current);
        all.addAll(additional);
        return all;
    }

    private static Set<InformationSchema.Ref> informationSchemaWith(Set<InformationSchema.Ref> current,
                                                                    Set<InformationSchema.Ref> additional) {
        Set<InformationSchema.Ref> all = new LinkedHashSet<>(current);
        all.addAll(additional);
        return all;
    }

    private record TemporaryDeclaration(List<Token> significant, int object, int name,
                                        boolean orReplace, boolean ifNotExists) {}

    record TemporaryTableDefinition(String name, List<TableFieldSchema> fields,
                                    boolean orReplace, boolean ifNotExists) {}

    record TemporaryFunctionDefinition(String name, boolean orReplace, boolean ifNotExists) {}

    record TemporaryTableDrop(String name, boolean ifExists, boolean sessionQualified) {}

    private static TemporaryDeclaration temporaryDeclaration(String sql, String objectName) {
        List<Token> significant = significant(new Lexer(sql).tokenize());
        if (significant.isEmpty() || !significant.getFirst().isKeyword("CREATE")) {
            return null;
        }
        int at = 1;
        boolean orReplace = at + 1 < significant.size() && significant.get(at).isKeyword("OR")
                && significant.get(at + 1).isKeyword("REPLACE");
        if (orReplace) {
            at += 2;
        }
        if (at >= significant.size() || !(significant.get(at).isKeyword("TEMP")
                || significant.get(at).isKeyword("TEMPORARY"))) {
            return null;
        }
        at++;
        if (at >= significant.size() || !significant.get(at).isKeyword(objectName)) {
            return null;
        }
        at++;
        boolean ifNotExists = at + 2 < significant.size() && significant.get(at).isKeyword("IF")
                && significant.get(at + 1).isKeyword("NOT") && significant.get(at + 2).isKeyword("EXISTS");
        if (ifNotExists) {
            at += 3;
        }
        if (at < significant.size() && significant.get(at).isKeyword("OR")) {
            // BigQuery's grammar takes OR REPLACE before TEMP, so after the object name it is a syntax error.
            throw invalidQuery("Syntax error: Unexpected keyword OR");
        }
        return at < significant.size()
                ? new TemporaryDeclaration(significant, at - 1, at, orReplace, ifNotExists) : null;
    }

    private static boolean temporaryFunction(String sql) {
        return temporaryDeclaration(sql, "FUNCTION") != null;
    }

    static TemporaryFunctionDefinition temporaryFunctionDefinition(String sql) {
        TemporaryDeclaration declaration = temporaryDeclaration(sql, "FUNCTION");
        if (declaration == null) {
            return null;
        }
        if (declaration.orReplace() || declaration.ifNotExists()) {
            // BigQuery rejects every clause of the statement, OR REPLACE and IF NOT EXISTS alike.
            throw invalidQuery("Clauses such as IF NOT EXISTS are not yet supported with CREATE TEMP FUNCTION");
        }
        Token name = declaration.significant().get(declaration.name());
        return new TemporaryFunctionDefinition(name.identifierText(), declaration.orReplace(),
                declaration.ifNotExists());
    }

    private record TemporaryTableTranslation(String name, Translation translation) {}

    private static boolean temporaryTable(String sql) {
        return temporaryDeclaration(sql, "TABLE") != null;
    }

    static void validateTemporaryFunctionDeclaration(String sql, String projectId, String defaultDatasetId,
                                                       QueryParameters parameters,
                                                       Collection<String> temporaryTables) {
        validateTemporaryFunctionDeclaration(sql, projectId, projectId, defaultDatasetId, parameters,
                temporaryTables);
    }

    static void validateTemporaryFunctionDeclaration(String sql, String projectId, String jobProjectId,
                                                       String defaultDatasetId, QueryParameters parameters,
                                                       Collection<String> temporaryTables) {
        if (temporaryFunction(sql)) {
            translateTemporaryFunction(sql, projectId, jobProjectId, defaultDatasetId, parameters,
                    Set.copyOf(temporaryTables), Set.of());
        }
    }

    static String declaredTemporaryTable(String sql) {
        TemporaryTableDefinition definition = temporaryTableDefinition(sql);
        return definition != null ? definition.name() : null;
    }

    static TemporaryTableDefinition temporaryTableDefinition(String sql) {
        TemporaryDeclaration declaration = temporaryDeclaration(sql, "TABLE");
        if (declaration == null) {
            return null;
        }
        if (declaration.orReplace() && declaration.ifNotExists()) {
            throw invalidQuery("CREATE TEMP TABLE cannot combine OR REPLACE and IF NOT EXISTS");
        }
        SqlDialectTranslator translator = new SqlDialectTranslator("", null, QueryParameters.none());
        translator.tokens = new Lexer(sql).tokenize();
        Path path = translator.pathAt(translator.significantIndexes(), declaration.name());
        String name = temporaryTableName(path.segments());
        if (name == null) {
            throw invalidQuery("CREATE TEMP TABLE requires an unqualified name or the _SESSION qualifier.");
        }
        int schemaOpen = translator.nextSignificant(path.end(), translator.tokens.size());
        int schemaClose = schemaOpen >= 0 && translator.tokens.get(schemaOpen).isPunct("(")
                ? translator.matchingParen(schemaOpen) : -1;
        List<TableFieldSchema> fields = schemaClose >= 0
                ? translator.columnDefinitions(schemaOpen, schemaClose) : List.of();
        return new TemporaryTableDefinition(name, fields, declaration.orReplace(), declaration.ifNotExists());
    }

    static String droppedTemporaryTable(String sql, Collection<String> temporaryTables) {
        return droppedTemporaryTable(sql, Set.copyOf(temporaryTables));
    }

    /**
     * A named query parameter may appear in any statement of a script (BigQuery binds it wherever
     * it is written), so the caller's parameters are substituted during validation too instead of
     * reporting every placeholder as missing.
     */
    static void validateProceduralStatement(String sql, String projectId, String jobProjectId,
                                            String defaultDatasetId, Collection<String> temporaryTables,
                                            QueryParameters parameters) {
        Set<String> names = new LinkedHashSet<>(temporaryTables);
        names.addAll(sessionTableNames(sql));
        String validationDataset = defaultDatasetId != null && !defaultDatasetId.isBlank()
                ? defaultDatasetId : "_floci_validation";
        if (temporaryFunction(sql)) {
            translateTemporaryFunction(sql, projectId, jobProjectId, validationDataset, parameters,
                    names, Set.of());
            return;
        }
        if (temporaryTable(sql)) {
            translateTemporaryTable(sql, projectId, jobProjectId, validationDataset, parameters, names,
                    ref -> null, ref -> null);
            return;
        }
        Statement statement = parseStatement(sql, projectId, jobProjectId, validationDataset, names);
        if (statement.kind() == StatementKind.QUERY) {
            translateWithTemporaryTables(sql, projectId, jobProjectId, validationDataset, parameters,
                    names);
        }
    }

    private static Set<String> sessionTableNames(String sql) {
        List<Token> tokens = significant(new Lexer(sql).tokenize());
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i + 2 < tokens.size(); i++) {
            if ((tokens.get(i).kind == Kind.IDENT || tokens.get(i).kind == Kind.QIDENT)
                    && tokens.get(i).identifierText().equalsIgnoreCase("_SESSION")
                    && tokens.get(i + 1).isPunct(".")
                    && (tokens.get(i + 2).kind == Kind.IDENT || tokens.get(i + 2).kind == Kind.QIDENT)) {
                names.add(tokens.get(i + 2).identifierText());
            }
        }
        return names;
    }

    static String temporaryDmlTarget(String sql, Collection<String> temporaryTables) {
        Set<String> names = Set.copyOf(temporaryTables);
        SqlDialectTranslator translator = new SqlDialectTranslator("", null, QueryParameters.none(), names);
        translator.prepare(sql);
        List<Integer> significant = translator.significantIndexes();
        if (significant.isEmpty()) {
            return null;
        }
        String first = translator.tokens.get(significant.getFirst()).upper();
        if (!Set.of("INSERT", "UPDATE", "DELETE", "MERGE").contains(first)) {
            return null;
        }
        int at = 1;
        if (at < significant.size() && ((first.equals("INSERT") || first.equals("MERGE"))
                && translator.keywordAt(significant, at, "INTO")
                || first.equals("DELETE") && translator.keywordAt(significant, at, "FROM"))) {
            at++;
        }
        Path path = translator.pathAt(significant, at);
        String name = temporaryTableName(path.segments());
        return name != null && names.contains(name) ? name : null;
    }

    static String temporaryTruncateTarget(String sql, Collection<String> temporaryTables) {
        Set<String> names = Set.copyOf(temporaryTables);
        SqlDialectTranslator translator = new SqlDialectTranslator("", null, QueryParameters.none(), names);
        translator.prepare(sql);
        List<Integer> significant = translator.significantIndexes();
        if (significant.size() < 3 || !translator.keywordAt(significant, 0, "TRUNCATE")
                || !translator.keywordAt(significant, 1, "TABLE")) {
            return null;
        }
        Path path = translator.pathAt(significant, 2);
        String name = temporaryTableName(path.segments());
        return name != null && names.contains(name) ? name : null;
    }

    private static TemporaryTableTranslation translateTemporaryTable(String sql, String projectId,
                                                                      String jobProjectId, String defaultDatasetId,
                                                                      QueryParameters parameters,
                                                                      Set<String> temporaryTables,
                                                                      Function<TableRef, Set<String>> columnsOf,
                                                                      Function<TableRef, List<TableFieldSchema>> fieldsOf) {
        SqlDialectTranslator translator = new SqlDialectTranslator(projectId, jobProjectId, defaultDatasetId,
                parameters);
        translator.tokens = new Lexer(sql).tokenize();
        List<Integer> significant = translator.significantIndexes();
        TemporaryDeclaration declaration = temporaryDeclaration(sql, "TABLE");
        Path path = translator.pathAt(significant, declaration.name());
        String name = temporaryTableName(path.segments());
        if (name == null) {
            throw invalidQuery("CREATE TEMP TABLE requires an unqualified name or the _SESSION qualifier.");
        }
        int schemaOpen = translator.nextSignificant(path.end(), translator.tokens.size());
        int schemaClose = schemaOpen >= 0 && translator.tokens.get(schemaOpen).isPunct("(")
                ? translator.matchingParen(schemaOpen) : -1;
        List<TableFieldSchema> fields = schemaClose >= 0
                ? translator.columnDefinitions(schemaOpen, schemaClose) : List.of();
        int as = translator.topLevelKeyword(path.end(), translator.tokens.size(), "AS");
        if (fields.isEmpty() && as < 0) {
            throw invalidQuery("CREATE TEMP TABLE needs a column list or AS followed by a query.");
        }
        String prefix = "CREATE " + (declaration.orReplace() ? "OR REPLACE " : "") + "TEMP TABLE "
                + (declaration.ifNotExists() ? "IF NOT EXISTS " : "");
        StringBuilder translated = new StringBuilder(prefix).append(DuckTypes.quoteIdentifier(name));
        if (!fields.isEmpty()) {
            translated.append(" (");
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) {
                    translated.append(", ");
                }
                TableFieldSchema field = fields.get(i);
                translated.append(DuckTypes.quoteIdentifier(field.getName())).append(' ')
                        .append(DuckTypes.duckType(field));
                if ("REQUIRED".equals(field.getMode())) {
                    translated.append(" NOT NULL");
                }
            }
            translated.append(')');
        }
        if (as < 0) {
            return new TemporaryTableTranslation(name,
                    new Translation(translated.toString(), Set.of(), Set.of(), false));
        }
        int queryStart = translator.nextSignificant(as + 1, translator.tokens.size());
        if (queryStart < 0) {
            throw invalidQuery("CREATE TEMP TABLE requires a query after AS.");
        }
        int queryEnd = translator.tokens.size();
        if (translator.tokens.get(queryStart).isPunct("(")) {
            int close = translator.matchingParen(queryStart);
            if (translator.nextSignificant(close + 1, translator.tokens.size()) >= 0) {
                throw invalidQuery("Syntax error: Unexpected tokens after CREATE TEMP TABLE query.");
            }
            queryStart++;
            queryEnd = close;
        }
        Translation query = translateWithTemporaryTables(translator.text(queryStart, queryEnd), projectId,
                jobProjectId, defaultDatasetId, parameters, temporaryTables, columnsOf, fieldsOf);
        if (fields.isEmpty()) {
            translated.append(" AS (").append(query.sql()).append(')');
        } else {
            translated.append(";\nINSERT INTO ").append(DuckTypes.quoteIdentifier(name)).append(' ')
                    .append(query.sql());
        }
        return new TemporaryTableTranslation(name,
                new Translation(translated.toString(), query.tables(), query.informationSchema(),
                        query.needsUnicodeCaseFold()));
    }

    private static String droppedTemporaryTable(String sql, Set<String> temporaryTables) {
        TemporaryTableDrop drop = temporaryTableDrop(sql);
        return drop != null && temporaryTables.contains(drop.name()) ? drop.name() : null;
    }

    static TemporaryTableDrop temporaryTableDrop(String sql) {
        SqlDialectTranslator translator = new SqlDialectTranslator("", null, QueryParameters.none());
        translator.tokens = new Lexer(sql).tokenize();
        List<Integer> significant = translator.significantIndexes();
        int at = 0;
        if (significant.size() < 3 || !translator.tokens.get(significant.get(at++)).isKeyword("DROP")
                || !translator.tokens.get(significant.get(at++)).isKeyword("TABLE")) {
            return null;
        }
        boolean ifExists = at + 2 < significant.size() && translator.keywordAt(significant, at, "IF")
                && translator.keywordAt(significant, at + 1, "EXISTS");
        if (ifExists) {
            at += 2;
        }
        Path path = translator.pathAt(significant, at);
        if (translator.nextSignificant(path.end(), translator.tokens.size()) >= 0) {
            return null;
        }
        String name = temporaryTableName(path.segments());
        if (name == null) {
            return null;
        }
        boolean sessionQualified = path.segments().size() == 2
                && path.segments().getFirst().equalsIgnoreCase("_SESSION");
        return new TemporaryTableDrop(name, ifExists, sessionQualified);
    }

    private static String temporaryTableName(List<String> segments) {
        if (segments.size() == 1) {
            return segments.getFirst();
        }
        return segments.size() == 2 && segments.getFirst().equalsIgnoreCase("_SESSION")
                ? segments.get(1) : null;
    }

    private static Translation translateTemporaryFunction(String sql, String projectId, String jobProjectId,
                                                           String defaultDatasetId, QueryParameters parameters,
                                                           Set<String> temporaryTables,
                                                           Set<String> temporaryFunctions) {
        SqlDialectTranslator translator = new SqlDialectTranslator(projectId, jobProjectId, defaultDatasetId,
                parameters, temporaryTables);
        translator.tokens = new Lexer(sql).tokenize();
        List<Integer> significant = translator.significantIndexes();
        TemporaryDeclaration declaration = temporaryDeclaration(sql, "FUNCTION");
        if (declaration == null) {
            throw invalidQuery("CREATE TEMP FUNCTION requires a name, parameters and AS expression.");
        }
        int name = significant.get(declaration.name());
        Token function = translator.tokens.get(name);
        if (function.kind != Kind.IDENT && function.kind != Kind.QIDENT) {
            throw invalidQuery("CREATE TEMP FUNCTION requires a function name.");
        }
        int open = translator.nextSignificant(name + 1, translator.tokens.size());
        if (open < 0 || !translator.tokens.get(open).isPunct("(")) {
            throw invalidQuery("CREATE TEMP FUNCTION requires a parameter list.");
        }
        int close = translator.matchingParen(open);
        int as = translator.nextSignificant(close + 1, translator.tokens.size());
        while (as >= 0 && !translator.tokens.get(as).isKeyword("AS")) {
            as = translator.nextSignificant(as + 1, translator.tokens.size());
        }
        if (as < 0) {
            throw invalidQuery("CREATE TEMP FUNCTION requires AS followed by an expression.");
        }
        int bodyOpen = translator.nextSignificant(as + 1, translator.tokens.size());
        if (bodyOpen < 0 || !translator.tokens.get(bodyOpen).isPunct("(")) {
            throw invalidQuery("CREATE TEMP FUNCTION requires an expression in parentheses.");
        }
        int bodyClose = translator.matchingParen(bodyOpen);
        if (translator.nextSignificant(bodyClose + 1, translator.tokens.size()) >= 0) {
            throw invalidQuery("Syntax error: Unexpected tokens after CREATE TEMP FUNCTION expression.");
        }
        String body = translator.text(bodyOpen + 1, bodyClose);
        List<List<Token>> arguments = splitArguments(translator.tokens, open + 1, close);
        int returns = translator.nextSignificant(close + 1, as);
        String returnType = returns >= 0 && translator.tokens.get(returns).isKeyword("RETURNS")
                ? translateType(translator.significant(returns + 1, as)) : null;
        StringBuilder macro = new StringBuilder("CREATE ")
                .append(declaration.orReplace() ? "OR REPLACE " : "")
                .append("TEMP MACRO ")
                .append(declaration.ifNotExists() ? "IF NOT EXISTS " : "")
                .append(DuckTypes.quoteIdentifier(function.identifierText())).append('(');
        Map<String, String> parameterReferences = new HashMap<>();
        List<String> parameterValues = new ArrayList<>();
        List<String> parameterNames = new ArrayList<>();
        for (int i = 0; i < arguments.size(); i++) {
            List<Token> argument = arguments.get(i);
            if (argument.isEmpty() || (argument.getFirst().kind != Kind.IDENT
                    && argument.getFirst().kind != Kind.QIDENT)) {
                throw invalidQuery("CREATE TEMP FUNCTION parameter " + (i + 1) + " requires a name.");
            }
            if (i > 0) {
                macro.append(", ");
            }
            String parameterAlias = "_floci_udf_arg_" + i;
            String quotedParameterAlias = DuckTypes.quoteIdentifier(parameterAlias);
            macro.append(quotedParameterAlias);
            boolean anyType = argument.size() == 3 && argument.get(1).isKeyword("ANY")
                    && argument.get(2).isKeyword("TYPE");
            if (argument.size() < 2) {
                throw invalidQuery("CREATE TEMP FUNCTION parameter " + (i + 1) + " requires a type.");
            }
            String reference = anyType ? quotedParameterAlias : "CAST(" + quotedParameterAlias + " AS "
                    + translateType(argument.subList(1, argument.size())) + ")";
            parameterReferences.put(argument.getFirst().identifierText().toLowerCase(Locale.ROOT), reference);
            parameterValues.add(reference);
            parameterNames.add(DuckTypes.quoteIdentifier(argument.getFirst().identifierText()));
        }
        translator.scalarUdfParameterAliases = Map.copyOf(parameterReferences);
        Translation expression = translator.translateExpression(body);
        boolean relationBinding = translator.scalarUdfNamedTableSource
                || referencesTemporaryFunctionWithConcat(body, temporaryFunctions);
        if (relationBinding) {
            // Bind scalar parameters in one-row relation scope. This keeps nested expressions composable and lets
            // DuckDB apply BigQuery's column-over-argument precedence when a named source exposes the same name.
            translator = new SqlDialectTranslator(projectId, defaultDatasetId, parameters, temporaryTables);
            expression = translator.translateExpression(body);
        }
        String typedExpression = returnType == null ? expression.sql()
                : "CAST((" + expression.sql() + ") AS " + returnType + ")";
        if (relationBinding && !parameterValues.isEmpty()) {
            typedExpression = "SELECT (" + typedExpression + ") FROM (VALUES ("
                    + String.join(", ", parameterValues) + ")) AS "
                    + DuckTypes.quoteIdentifier("_floci_udf_parameters") + "("
                    + String.join(", ", parameterNames) + ")";
        }
        return new Translation(macro.append(") AS (").append(typedExpression).append(')').toString(),
                expression.tables(), expression.informationSchema(), expression.needsUnicodeCaseFold());
    }

    private static boolean referencesTemporaryFunctionWithConcat(String body, Set<String> temporaryFunctions) {
        if (temporaryFunctions.isEmpty()) {
            return false;
        }
        List<Token> bodyTokens = significant(new Lexer(body).tokenize());
        boolean containsConcat = false;
        for (int index = 0; index < bodyTokens.size(); index++) {
            Token token = bodyTokens.get(index);
            if ((token.kind == Kind.IDENT || token.kind == Kind.QIDENT)
                    && token.identifierText().equalsIgnoreCase("CONCAT")) {
                int next = index + 1;
                if (next < bodyTokens.size() && bodyTokens.get(next).isPunct("(")) {
                    containsConcat = true;
                    break;
                }
            }
        }
        if (!containsConcat) {
            return false;
        }
        for (int index = 0; index + 1 < bodyTokens.size(); index++) {
            Token token = bodyTokens.get(index);
            if ((token.kind == Kind.IDENT || token.kind == Kind.QIDENT)
                    && temporaryFunctions.contains(token.identifierText().toLowerCase(Locale.ROOT))
                    && bodyTokens.get(index + 1).isPunct("(")) {
                return true;
            }
        }
        return false;
    }

    private Translation translateExpression(String expression) {
        if (expression.isBlank()) {
            throw invalidQuery("CREATE TEMP FUNCTION expression cannot be empty.");
        }
        scalarUdfBody = true;
        tokens = new Lexer(expression).tokenize();
        rejectScripts();
        collectCteNames();
        rewriteTypedArrayLiterals();
        rewriteArraySubscripts();
        rewriteSelectAsStruct();
        rewriteIndexedStructWildcards();
        rewritePivotSyntax();
        quoteImplicitSelectAliases();
        expandHavingStructAliasFields();
        quoteOffsetIdentifiers();
        parenthesizeIntervalValues();
        return new Translation(renderShortCircuitLogical(0, tokens.size()), tables, informationSchema,
                needsUnicodeCaseFold);
    }

    private String renderShortCircuitLogical(int start, int end) {
        if (isQuery(start, end)) {
            return render(start, end).trim();
        }
        int or = rootLogicalOperator(start, end, "OR");
        if (or >= 0) {
            String left = renderShortCircuitLogical(start, or);
            String right = renderShortCircuitLogical(or + 1, end);
            return "CASE (" + left + ") WHEN TRUE THEN TRUE WHEN FALSE THEN (" + right
                    + ") ELSE CASE (" + right + ") WHEN TRUE THEN TRUE ELSE NULL END END";
        }
        int and = rootLogicalOperator(start, end, "AND");
        if (and >= 0) {
            String left = renderShortCircuitLogical(start, and);
            String right = renderShortCircuitLogical(and + 1, end);
            return "CASE (" + left + ") WHEN FALSE THEN FALSE WHEN TRUE THEN (" + right
                    + ") ELSE CASE (" + right + ") WHEN FALSE THEN FALSE ELSE NULL END END";
        }
        int first = nextSignificant(start, end);
        int last = previousSignificant(end - 1);
        if (first >= 0 && last >= first && tokens.get(first).isPunct("(") && matchingParen(first) == last) {
            return "(" + renderShortCircuitLogical(first + 1, last) + ")";
        }
        return render(start, end).trim();
    }

    private int rootLogicalOperator(int start, int end, String operator) {
        int parentheses = 0;
        int brackets = 0;
        int caseDepth = 0;
        int pendingBetween = 0;
        int found = -1;
        for (int i = start; i < end; i++) {
            Token token = tokens.get(i);
            if (token.kind == Kind.SPACE) {
                continue;
            }
            if (token.isPunct("(")) {
                parentheses++;
                continue;
            }
            if (token.isPunct(")")) {
                parentheses--;
                continue;
            }
            if (token.isPunct("[")) {
                brackets++;
                continue;
            }
            if (token.isPunct("]")) {
                brackets--;
                continue;
            }
            if (parentheses != 0 || brackets != 0) {
                continue;
            }
            if (token.isKeyword("CASE")) {
                caseDepth++;
                continue;
            }
            if (token.isKeyword("END") && caseDepth > 0) {
                caseDepth--;
                continue;
            }
            if (caseDepth != 0) {
                continue;
            }
            if (token.isKeyword("BETWEEN")) {
                pendingBetween++;
                continue;
            }
            if (token.isKeyword("AND") && pendingBetween > 0) {
                pendingBetween--;
                continue;
            }
            if (token.isKeyword(operator)) {
                found = i;
            }
        }
        return found;
    }

    private static List<String> splitScript(String sql) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int parentheses = 0;
        boolean hasSignificantToken = false;
        for (Token token : new Lexer(sql).tokenize()) {
            if (token.isPunct("(")) {
                parentheses++;
            } else if (token.isPunct(")")) {
                parentheses--;
            }
            if (token.isPunct(";") && parentheses == 0) {
                if (!hasSignificantToken) {
                    throw invalidQuery("Empty statements are not allowed in a multi-statement query.");
                }
                statements.add(current.toString().trim());
                current.setLength(0);
                hasSignificantToken = false;
            } else {
                current.append(token.text);
                if (token.kind != Kind.SPACE && token.kind != Kind.RAW) {
                    hasSignificantToken = true;
                }
            }
        }
        if (hasSignificantToken) {
            statements.add(current.toString().trim());
        }
        return statements;
    }

    static List<List<Token>> splitArguments(List<Token> tokens, int from, int to) {
        List<List<Token>> arguments = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        int parentheses = 0;
        int angleBrackets = 0;
        for (int i = from; i < to; i++) {
            Token token = tokens.get(i);
            if (token.isPunct("(")) {
                parentheses++;
            } else if (token.isPunct(")")) {
                parentheses--;
            } else if (token.isPunct("<")) {
                angleBrackets++;
            } else if (token.isPunct(">")) {
                angleBrackets--;
            } else if (token.isPunct(">>")) {
                angleBrackets -= 2;
            }
            if (token.isPunct(",") && parentheses == 0 && angleBrackets == 0) {
                arguments.add(significant(current));
                current.clear();
            } else {
                current.add(token);
            }
        }
        if (!current.isEmpty()) {
            arguments.add(significant(current));
        }
        return arguments;
    }

    private static List<Token> significant(List<Token> tokens) {
        return tokens.stream().filter(token -> token.kind != Kind.SPACE && token.kind != Kind.RAW).toList();
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
     * {@code SET OPTIONS} of an {@code ALTER TABLE}/{@code ALTER VIEW} or the overrides of
     * {@code CREATE TABLE COPY}, by option name; a null value clears the option.
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

        Statement withQuerySql(String sql) {
            return new Statement(kind, statementType, target, datasetTarget, orReplace, ifNotExists, ifExists,
                    cascade, materialized, sql, columns, copySource, timePartitioning, rangePartitioning,
                    partitionExpression);
        }

    }

    /** Classifies a statement and resolves its target, without translating it to DuckDB. */
    static Statement parseStatement(String sql, String projectId, String defaultDatasetId) {
        return parseStatement(sql, projectId, projectId, defaultDatasetId);
    }

    /** As {@link #parseStatement(String, String, String)} for a job run in {@code jobProjectId}. */
    static Statement parseStatement(String sql, String projectId, String jobProjectId, String defaultDatasetId) {
        SqlDialectTranslator translator = new SqlDialectTranslator(projectId, jobProjectId, defaultDatasetId,
                QueryParameters.none());
        return translator.classify(sql);
    }

    static Statement parseStatement(String sql, String projectId, String defaultDatasetId,
                                    Collection<String> temporaryTables) {
        return parseStatement(sql, projectId, projectId, defaultDatasetId, temporaryTables);
    }

    static Statement parseStatement(String sql, String projectId, String jobProjectId, String defaultDatasetId,
                                    Collection<String> temporaryTables) {
        SqlDialectTranslator translator = new SqlDialectTranslator(projectId, jobProjectId, defaultDatasetId,
                QueryParameters.none(), Set.copyOf(temporaryTables));
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
        return translateDml(sql, projectId, projectId, defaultDatasetId, parameters, columnsOf);
    }

    /** As {@link #translateDml(String, String, String, QueryParameters, Function)} for a job run in {@code jobProjectId}. */
    static Translation translateDml(String sql, String projectId, String jobProjectId, String defaultDatasetId,
                                    QueryParameters parameters, Function<TableRef, Set<String>> columnsOf) {
        SqlDialectTranslator translator = new SqlDialectTranslator(projectId, jobProjectId, defaultDatasetId,
                parameters);
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
        validateStarReplacementWindows();
        quoteOffsetIdentifiers();
        parenthesizeIntervalValues();
    }

    private void validateStarReplacementWindows() {
        for (int star = 0; star < tokens.size(); star++) {
            if (!tokens.get(star).isPunct("*") || !isProjectionStar(star)) {
                continue;
            }
            int clause = nextSignificant(star + 1, tokens.size());
            if (clause >= 0 && tokens.get(clause).isKeyword("EXCEPT")) {
                int open = nextSignificant(clause + 1, tokens.size());
                if (open < 0 || !tokens.get(open).isPunct("(")) {
                    continue;
                }
                clause = nextSignificant(matchingParen(open) + 1, tokens.size());
            }
            if (clause < 0 || !tokens.get(clause).isKeyword("REPLACE")) {
                continue;
            }
            int open = nextSignificant(clause + 1, tokens.size());
            if (open < 0 || !tokens.get(open).isPunct("(")) {
                continue;
            }
            int close = matchingParen(open);
            for (int i = open + 1; i < close; i++) {
                if (tokens.get(i).isPunct("(")) {
                    int queryClose = nestedQueryClose(i, close);
                    if (queryClose >= 0) {
                        i = queryClose;
                        continue;
                    }
                }
                int previous = previousSignificantBefore(i);
                if (tokens.get(i).isKeyword("OVER") && previous >= open && tokens.get(previous).isPunct(")")) {
                    throw invalidQuery("Cannot use analytic functions inside SELECT * REPLACE");
                }
            }
        }
    }

    /**
     * GoogleSQL array subscripts are 0-based ({@code arr[OFFSET(i)]}) or 1-based ({@code arr[ORDINAL(i)]}).
     * The plain forms fail on an index outside the array, the {@code SAFE_} forms return NULL. A bare
     * {@code x[i]} is 0-based like {@code x[OFFSET(i)]}, but it is rewritten only when {@code x} is visibly an
     * array (see {@link #isVisiblyArray}): GoogleSQL's JSON subscript operator has the same form ({@code json[0]},
     * {@code json['field']}), and a JSON value cannot be told from an array by its tokens otherwise. DuckDB lists are 1-based, return NULL out of range and count a negative index from
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
            int start = arrayOperandStart(previous);
            if (!wrapped && (first < 0 || !isVisiblyArray(start, previous))) {
                i++;
                continue;
            }
            List<Token> index;
            if (wrapped) {
                int open = nextSignificant(first + 1, close);
                if (open < 0 || !tokens.get(open).isPunct("(") || nextSignificant(matchingParen(open) + 1, close) >= 0) {
                    throw invalidQuery("Syntax error: Expected \")\" after " + accessor + " in an array subscript");
                }
                index = new ArrayList<>(tokens.subList(open + 1, matchingParen(open)));
            } else {
                accessor = "OFFSET";
                index = new ArrayList<>(tokens.subList(i + 1, close));
            }
            int ordinalBase = accessor.endsWith("ORDINAL") ? 1 : 0;
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
            replacement.add(Token.raw(INDEXED_AGGREGATE_PREFIX));
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

    /** Functions that always return an ARRAY, so a bare subscript on a call to one is an array subscript. */
    private static final Set<String> ARRAY_FUNCTIONS = Set.of("ARRAY_AGG", "ARRAY_CONCAT", "ARRAY_CONCAT_AGG",
            "ARRAY_REVERSE", "ARRAY_SLICE", "SPLIT", "REGEXP_EXTRACT_ALL", "GENERATE_ARRAY", "GENERATE_DATE_ARRAY",
            "GENERATE_TIMESTAMP_ARRAY");

    /**
     * True when the operand from {@code start} to {@code last} is an array by its own tokens: an array literal,
     * {@code ARRAY(...)}, or a whole call to one of {@link #ARRAY_FUNCTIONS}, with or without an {@code OVER}
     * clause, looking through parentheses around any of them.
     */
    private boolean isVisiblyArray(int start, int last) {
        Token first = tokens.get(start);
        if (first.isPunct("(") && tokens.get(last).isPunct(")") && matchingParen(start) == last) {
            int innerStart = nextSignificant(start + 1, last);
            int innerLast = previousSignificantBefore(last);
            return innerStart >= 0 && innerLast >= innerStart && isVisiblyArray(innerStart, innerLast);
        }
        if (first.isPunct("[") || first.isKeyword("ARRAY") || isArrayCast(start, last)) {
            return true;
        }
        if (first.kind != Kind.IDENT || !ARRAY_FUNCTIONS.contains(first.upper()) || !tokens.get(last).isPunct(")")) {
            return false;
        }
        int open = nextSignificant(start + 1, last + 1);
        if (open < 0 || !tokens.get(open).isPunct("(")) {
            return false;
        }
        int close = matchingParen(open);
        if (close == last) {
            return true;
        }
        int over = nextSignificant(close + 1, last + 1);
        int window = over >= 0 && tokens.get(over).isKeyword("OVER") ? nextSignificant(over + 1, last + 1) : -1;
        return window >= 0 && tokens.get(window).isPunct("(") && matchingParen(window) == last;
    }

    /**
     * A whole {@code CAST(... AS T[])}, which is how {@link #rewriteTypedArrayLiterals} renders a typed array
     * literal such as {@code ARRAY<INT64>[1, 2, 3]} before subscripts are rewritten, or a {@code CAST} or
     * {@code SAFE_CAST} to {@code ARRAY<...>} the query writes itself.
     */
    private boolean isArrayCast(int start, int last) {
        Token first = tokens.get(start);
        if (!(first.isKeyword("CAST") || first.isKeyword("SAFE_CAST")) || !tokens.get(last).isPunct(")")) {
            return false;
        }
        int open = nextSignificant(start + 1, last + 1);
        if (open < 0 || !tokens.get(open).isPunct("(") || matchingParen(open) != last) {
            return false;
        }
        int type = previousSignificantBefore(last);
        if (type >= 0 && tokens.get(type).kind == Kind.RAW) {
            String text = tokens.get(type).text.trim();
            return text.startsWith("AS ") && text.endsWith("[]");
        }
        // A cast the query writes itself, CAST(expr AS ARRAY<T>): the type after its top-level AS is an ARRAY.
        int nesting = 0;
        for (int i = open + 1; i < last; i++) {
            Token token = tokens.get(i);
            if (token.isPunct("(") || token.isPunct("[") || token.isPunct("{")) {
                nesting++;
            } else if (token.isPunct(")") || token.isPunct("]") || token.isPunct("}")) {
                nesting--;
            } else if (nesting == 0 && token.isKeyword("AS")) {
                int target = nextSignificant(i + 1, last);
                return target >= 0 && tokens.get(target).isKeyword("ARRAY");
            }
        }
        return false;
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
                    int afterSource = nextSignificant(source.end(), tokens.size());
                    Map<String, Object> options = null;
                    if (afterSource >= 0) {
                        if (!tokens.get(afterSource).isKeyword("OPTIONS")) {
                            throw invalidQuery("Syntax error: Unexpected tokens after COPY source table.");
                        }
                        options = tableOptions(afterSource);
                    }
                    return new Statement(StatementKind.CREATE_TABLE, "CREATE_TABLE_COPY", target, null,
                            orReplace, ifNotExists, false, false, false, null, null, options,
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

    /**
     * An option value given as a constant expression ({@code CONCAT('a', 'b')}), as GoogleSQL text. The
     * service evaluates it with the SQL engine and checks its type before applying the option. A positional
     * parameter binds in statement order, so the expression's {@code ?} are the {@code parameterCount}
     * parameters from {@code firstParameter} on.
     */
    public record OptionExpression(String sql, int firstParameter, int parameterCount) {
    }

    /** Options supported by ALTER SET OPTIONS and CREATE TABLE COPY. */
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
        return new Statement(object.equals("TABLE") ? StatementKind.ALTER_TABLE : StatementKind.ALTER_VIEW,
                "ALTER_" + object, target, null, false, false, ifExists, false, false, null, null,
                tableOptions(options));
    }

    private Map<String, Object> tableOptions(int options) {
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
        int positionalBefore = 0;
        for (int k = open + 1; k <= close; k++) {
            Token t = tokens.get(k);
            if (k == close || (depth == 0 && t.isPunct(","))) {
                List<Token> item = significant(start, k);
                if (!item.isEmpty()) {
                    readOption(item, values, positionalBefore);
                    positionalBefore += (int) item.stream()
                            .filter(token -> token.kind == Kind.POSITIONAL_PARAM).count();
                }
                start = k + 1;
            } else if (t.isPunct("(") || t.isPunct("[")) {
                depth++;
            } else if (t.isPunct(")") || t.isPunct("]")) {
                depth--;
            }
        }
        return values;
    }

    private static void readOption(List<Token> item, Map<String, Object> values, int positionalBefore) {
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
            case "description", "friendly_name" -> values.put(name, isLiteral(value) ? stringOption(name, value)
                    : optionExpression(name, value, positionalBefore));
            case "labels" -> values.put(name, labelsOption(value));
            default -> values.put(name, isLiteral(value) || isTimestampLiteral(value) ? timestampOption(value)
                    : optionExpression(name, value, positionalBefore));
        }
    }

    private static boolean isLiteral(List<Token> value) {
        return value.size() == 1 && (value.getFirst().kind == Kind.STRING || value.getFirst().kind == Kind.NUMBER
                || value.getFirst().kind == Kind.BYTES || value.getFirst().isKeyword("TRUE")
                || value.getFirst().isKeyword("FALSE"));
    }

    private static boolean isTimestampLiteral(List<Token> value) {
        return value.size() == 2 && value.getFirst().isKeyword("TIMESTAMP") && value.get(1).kind == Kind.STRING;
    }

    /**
     * A value BigQuery evaluates as a constant expression; a subquery is not a constant one, and a query clause
     * after the expression is a syntax error ({@code IS [NOT] DISTINCT FROM} is an operator, not a clause).
     */
    private static OptionExpression optionExpression(String name, List<Token> value, int firstParameter) {
        if (value.stream().anyMatch(t -> t.isKeyword("SELECT"))) {
            throw invalidQuery("Expected constant expression but found unsupported statement or clause; failed to"
                    + " evaluate '" + name + "' in table OPTIONS");
        }
        int depth = 0;
        for (int k = 0; k < value.size(); k++) {
            Token t = value.get(k);
            boolean distinctFrom = t.isKeyword("FROM") && k > 0 && value.get(k - 1).isKeyword("DISTINCT");
            if (t.isPunct("(") || t.isPunct("[")) {
                depth++;
            } else if (t.isPunct(")") || t.isPunct("]")) {
                depth--;
            } else if (depth == 0 && !distinctFrom && (t.isKeyword("FROM") || t.kind == Kind.IDENT
                    && CLAUSE_END_KEYWORDS.contains(t.upper()))) {
                throw invalidQuery("Syntax error: Expected \")\" but got keyword " + t.upper());
            }
        }
        StringBuilder sql = new StringBuilder();
        for (Token t : value) {
            if (!sql.isEmpty()) {
                sql.append(' ');
            }
            sql.append(t.text);
        }
        int parameterCount = (int) value.stream().filter(token -> token.kind == Kind.POSITIONAL_PARAM).count();
        return new OptionExpression(sql.toString(), firstParameter, parameterCount);
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
        for (int k = 0; k + 1 < value.size(); k++) {
            if (value.get(k).kind == Kind.IDENT && !value.get(k).isKeyword("STRUCT") && value.get(k + 1).isPunct("(")) {
                throw invalidQuery("Found unsupported function call 'ARRAY[...]'; failed to evaluate 'labels' in"
                        + " table OPTIONS");
            }
        }
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
            if (!segments.get(0).equals(jobProjectId)) {
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
        rewriteTypedStructConstructors();
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
        String targetSql;
        if (target == null) {
            targetSql = DuckTypes.quoteIdentifier(path.segments().getLast());
        } else {
            tables.add(target);
            targetSql = DuckTypes.quoteIdentifier(target.datasetId()) + "."
                    + DuckTypes.quoteIdentifier(target.tableId());
        }
        String head = switch (first) {
            case "INSERT" -> "INSERT INTO ";
            case "DELETE" -> "DELETE FROM ";
            case "MERGE" -> "MERGE INTO ";
            default -> "UPDATE ";
        };
        StringBuilder out = new StringBuilder(head).append(targetSql);
        String targetAlias = target != null ? target.tableId() : path.segments().getLast();
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
        return new Translation(out.toString().trim(), tables, informationSchema, needsUnicodeCaseFold,
                List.of(), List.copyOf(missingStructFields), null);
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
        Set<String> targetColumns = target != null ? columnsOf.apply(target) : null;
        Set<String> sourceColumns = mergeSource == null ? null : columnsOf.apply(mergeSource);
        Set<String> outerColumns = null;
        if (targetColumns != null || sourceColumns != null) {
            outerColumns = new HashSet<>();
            outerColumns.addAll(targetColumns != null ? targetColumns : Set.of());
            outerColumns.addAll(sourceColumns != null ? sourceColumns : Set.of());
        }
        Set<String> scopeNames = new HashSet<>(List.of(correlationSource.toLowerCase(Locale.ROOT),
                targetAlias.toLowerCase(Locale.ROOT)));
        if (target != null) {
            scopeNames.add(target.tableId().toLowerCase(Locale.ROOT));
        }
        mergeScope = new MergeScope(scopeNames, outerColumns, targetColumns != null && sourceColumns != null);
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
        topLevelOutputNames = topLevelOutputNames();
        duplicateOutputNames = duplicateNames(topLevelOutputNames);
        validateSelectNameAmbiguity();
        validateSourceReferences();
        validateWindowReferences();
        validateSourceStarLists();
        validatePivotReferences();
        rewriteSourceStarExclusions();
        rewriteUnpivotStars();
        augmentPivotStarExclusions();
        nameNestedDuplicateOutputs();
        rewriteTypedArrayLiterals();
        rewriteTypedStructConstructors();
        rewriteArraySubscripts();
        // SELECT AS STRUCT renders its fields to text, so queries nested in them must be rewritten first.
        quoteImplicitSelectAliases();
        expandHavingStructAliasFields();
        expandQualifyStructAliasFields();
        expandOrderByStructAliasFields();
        rewritePivotSyntax();
        rewriteSelectAsStruct();
        rewriteIndexedStructWildcards();
        quoteImplicitSelectAliases();
        expandHavingStructAliasFields();
        expandQualifyStructAliasFields();
        expandOrderByStructAliasFields();
        rewritePivotSyntax();
        nameAnonymousColumns();
        rewriteStructStarExclusions();
        expandStructStars();
        orderNanLikeBigQuery();
        wrapDuplicateOutputNames();
        String replacementStarExclusion = replacementStarExclusion();
        String rendered = render(0, tokens.size()).trim();
        if (replacementStarExclusion != null) {
            rendered = "SELECT *" + replacementStarExclusion + " FROM (" + rendered
                    + ") AS \"_floci_star_replace\"";
        }
        return new Translation(rendered, tables, informationSchema, needsUnicodeCaseFold,
                duplicateOutputNames, List.copyOf(missingStructFields), orderByFieldPaths(replacementStarExclusion));
    }

    /**
     * The dotted paths in the outermost query's ORDER BY, with the query rendered without it, or null when there
     * are none. DuckDB fails to bind {@code s.zz} on a STRUCT column it reads from a source, which the translator
     * cannot see into, and the engine names the struct's type from the query without its ORDER BY.
     */
    private OrderByFieldPaths orderByFieldPaths(String replacementStarExclusion) {
        int order = -1;
        int by = -1;
        for (int i = tokens.size() - 1; i >= 0 && order < 0; i--) {
            by = nextSignificant(i + 1, tokens.size());
            if (tokens.get(i).isKeyword("ORDER") && parenthesisDepth(i) == 0 && by >= 0
                    && tokens.get(by).isKeyword("BY")) {
                order = i;
            }
        }
        if (order < 0) {
            return null;
        }
        int end = clauseEnd(by + 1, 0);
        List<List<String>> paths = new ArrayList<>();
        for (int i = by + 1; i < end; i++) {
            int before = previousSignificantBefore(i);
            if (!isIdentifierToken(i) || before > by && tokens.get(before).isPunct(".")) {
                continue;
            }
            List<String> path = new ArrayList<>(List.of(tokens.get(i).identifierText()));
            int last = i;
            int dot = nextSignificant(last + 1, end);
            int next = dot < 0 ? -1 : nextSignificant(dot + 1, end);
            while (next >= 0 && tokens.get(dot).isPunct(".") && isIdentifierToken(next)) {
                path.add(tokens.get(next).identifierText());
                last = next;
                dot = nextSignificant(last + 1, end);
                next = dot < 0 ? -1 : nextSignificant(dot + 1, end);
            }
            if (path.size() > 1) {
                paths.add(List.copyOf(path));
            }
            i = last;
        }
        if (paths.isEmpty()) {
            return null;
        }
        String withoutOrderBy = (render(0, order) + render(end, tokens.size())).trim();
        if (replacementStarExclusion != null) {
            withoutOrderBy = "SELECT *" + replacementStarExclusion + " FROM (" + withoutOrderBy
                    + ") AS \"_floci_star_replace\"";
        }
        return new OrderByFieldPaths(withoutOrderBy, List.copyOf(paths));
    }

    private boolean isIdentifierToken(int i) {
        return tokens.get(i).kind == Kind.IDENT || tokens.get(i).kind == Kind.QIDENT;
    }

    /**
     * Rewrites an expression {@code .* EXCEPT (...)} or {@code .* REPLACE (...)} as a star over the
     * expression's expanded fields, in every query of the statement: the outermost one, a CTE or a subquery.
     */
    private void rewriteStructStarExclusions() {
        while (rewriteOneStructStarExclusion()) {
            // A rewrite wraps its query in a derived table, so the indices shift and the scan restarts.
        }
    }

    /** Rewrites the first query whose select list holds a {@code <expression>.*} star with a clause. */
    private boolean rewriteOneStructStarExclusion() {
        for (int select = 0; select < tokens.size(); select++) {
            if (tokens.get(select).isKeyword("SELECT") && rewriteStructStarExclusion(select)) {
                outputNamesHandled = true;
                return true;
            }
        }
        return false;
    }

    /** The token index that ends the query whose SELECT sits at {@code select}, closing parenthesis included. */
    private int structStarQueryEnd(int select) {
        int depth = parenthesisDepth(select);
        for (int i = select + 1; i < tokens.size(); i++) {
            if (parenthesisDepth(i) == depth && tokens.get(i).isPunct(")")) {
                return i;
            }
        }
        return tokens.size();
    }

    /**
     * True when the SELECT at {@code select} is a later arm of a set operation, so a trailing ORDER BY,
     * LIMIT or OFFSET after it names the whole result rather than this arm.
     */
    private boolean selectFollowsSetOperator(int select) {
        int position = significantBefore(select);
        if (position >= 0 && tokens.get(position).isPunct("(")) {
            // A parenthesized arm owns its trailing ORDER BY, LIMIT and OFFSET, unlike a bare last arm.
            return false;
        }
        while (position >= 0 && tokens.get(position).isPunct("(")) {
            position = significantBefore(position);
        }
        if (position >= 0 && (tokens.get(position).isKeyword("ALL")
                || tokens.get(position).isKeyword("DISTINCT"))) {
            position = significantBefore(position);
        }
        return position >= 0 && (tokens.get(position).isKeyword("UNION")
                || tokens.get(position).isKeyword("INTERSECT") || tokens.get(position).isKeyword("EXCEPT"));
    }

    /** The end of the arm, before the whole set's trailing ORDER BY, LIMIT or OFFSET. */
    private int structStarSetOperationTail(int select, int tailStart, int end, int queryDepth) {
        if (!selectFollowsSetOperator(select)) {
            return end;
        }
        for (String keyword : List.of("ORDER", "LIMIT", "OFFSET")) {
            int clause = structStarClauseStart(tailStart, end, queryDepth, keyword);
            if (clause < 0) {
                continue;
            }
            while (clause > tailStart && tokens.get(clause - 1).kind == Kind.SPACE) {
                clause--;
            }
            end = Math.min(end, clause);
        }
        return end;
    }

    /** The token index that starts a top-level set operation, or {@code end} when the query has none. */
    private int structStarArmEnd(int tailStart, int end, int queryDepth) {
        for (int i = tailStart; i < end; i++) {
            if (parenthesisDepth(i) != queryDepth || isPrecededByDot(i)) {
                continue;
            }
            Token token = tokens.get(i);
            if (token.kind == Kind.IDENT && (token.isKeyword("UNION") || token.isKeyword("INTERSECT")
                    || token.isKeyword("EXCEPT"))) {
                // The star's own EXCEPT list sits before FROM, so an EXCEPT here can only join two queries.
                // Keep the whitespace that follows this arm in place so the rewritten select still separates
                // from the operator.
                int armEnd = i;
                while (armEnd > tailStart && tokens.get(armEnd - 1).kind == Kind.SPACE) {
                    armEnd--;
                }
                return armEnd;
            }
        }
        return end;
    }

    /** A dotted STRUCT star in the current select list, with its EXCEPT/REPLACE clauses resolved. */
    private record SelectStructStar(int itemIndex, int operandStart, int dot,
                                    int exceptOpen, int exceptClose, List<String> exceptNames,
                                    Set<String> excludedFields, int replaceOpen, int replaceClose,
                                    Set<String> replacedFields, Set<String> fields) {

        boolean hasClause() {
            return exceptOpen >= 0 || replaceOpen >= 0;
        }
    }

    private boolean rewriteStructStarExclusion(int select) {
        int end = structStarQueryEnd(select);
        int queryDepth = parenthesisDepth(select);
        List<int[]> items = selectItems(select);
        if (items == null) {
            return false;
        }
        int starItems = 0;
        boolean bareStar = false;
        for (int[] item : items) {
            if (!expandsStar(item)) {
                continue;
            }
            starItems++;
            int star = projectionStarIndex(item);
            int dot = star < 0 ? -1 : significantBefore(star);
            if (dot < 0 || !tokens.get(dot).isPunct(".")) {
                bareStar = true;
            }
        }
        if (starItems > 1) {
            StructStarSources sources = structStarSources(items.getLast()[1], end, queryDepth);
            List<SelectStructStar> selectStars = selectStructStars(select, items, sources);
            if (selectStars.stream().anyMatch(SelectStructStar::hasClause)) {
                if (bareStar) {
                    // An unqualified * would also pick up the derived tables the rewrite adds.
                    throw unsupportedStructStarExclusion();
                }
                return rewriteMultipleStructStars(select, end, items, selectStars, sources, queryDepth);
            }
        }
        int targetItem = -1;
        int targetStar = -1;
        int targetDot = -1;
        int targetStart = -1;
        int exceptOpen = -1;
        int exceptClose = -1;
        int replaceOpen = -1;
        int replaceClose = -1;
        boolean groupedStar = structStarGroupsByPosition(items.getLast()[1], end, queryDepth);
        for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
            int[] item = items.get(itemIndex);
            for (int star = item[0]; star < item[1]; star++) {
                if (!tokens.get(star).isPunct("*") || insideNestedQuery(star, select)) {
                    continue;
                }
                int dot = significantBefore(star);
                int last = dot < 0 ? -1 : significantBefore(dot);
                int clause = nextSignificant(star + 1, item[1]);
                boolean except = clause >= 0 && tokens.get(clause).isKeyword("EXCEPT");
                boolean replace = clause >= 0 && tokens.get(clause).isKeyword("REPLACE");
                boolean plainGrouped = !except && !replace && clause < 0 && groupedStar;
                if (dot < item[0] || !tokens.get(dot).isPunct(".") || (!except && !replace && !plainGrouped)) {
                    continue;
                }
                int open = -1;
                int close = -1;
                int itemEnd = star;
                int openAfterExcept = -1;
                int closeAfterExcept = -1;
                if (except || replace) {
                    open = nextSignificant(clause + 1, item[1]);
                    if (open < 0 || !tokens.get(open).isPunct("(")) {
                        continue;
                    }
                    close = matchingParen(open);
                    itemEnd = close;
                }
                if (except) {
                    int after = nextSignificant(close + 1, item[1]);
                    if (after >= 0 && tokens.get(after).isKeyword("REPLACE")) {
                        openAfterExcept = nextSignificant(after + 1, item[1]);
                        if (openAfterExcept < 0 || !tokens.get(openAfterExcept).isPunct("(")) {
                            throw unsupportedStructStarExclusion();
                        }
                        closeAfterExcept = matchingParen(openAfterExcept);
                        itemEnd = closeAfterExcept;
                    }
                }
                int expressionStart = starOperandStart(last);
                int first = nextSignificant(item[0], item[1]);
                boolean bareName = expressionStart == last && (tokens.get(last).kind == Kind.IDENT
                        || tokens.get(last).kind == Kind.QIDENT)
                        && !tokens.get(last).identifierText().contains(".");
                if (bareName) {
                    continue;
                }
                if (expressionStart != first || nextSignificant(itemEnd + 1, item[1]) >= 0 || targetItem >= 0) {
                    throw unsupportedStructStarExclusion();
                }
                targetItem = itemIndex;
                targetStar = star;
                targetDot = dot;
                targetStart = expressionStart;
                if (except) {
                    exceptOpen = open;
                    exceptClose = close;
                }
                if (replace) {
                    replaceOpen = open;
                    replaceClose = close;
                } else if (openAfterExcept >= 0) {
                    replaceOpen = openAfterExcept;
                    replaceClose = closeAfterExcept;
                }
            }
        }
        if (targetItem < 0) {
            return false;
        }
        validateAggregateStarOperand(select, targetStart, targetDot);
        int tailStart = items.getLast()[1];
        // A set operation is not part of this query's tail: each arm is rewritten on its own and the
        // operator stays between the wrapped selects. A trailing ORDER BY, LIMIT or OFFSET after the
        // last arm belongs to the whole set, so it must not move into that arm's wrapper.
        end = structStarArmEnd(tailStart, end, queryDepth);
        end = structStarSetOperationTail(select, tailStart, end, queryDepth);
        // An aggregate star only needs the aggregate structure when the query has no grouping key of its
        // own (GROUP BY ALL or no GROUP BY) and every sibling is an aggregate, which is the shape whose
        // aggregations all run at once. Anything else keeps a structure that already handles it.
        if (structStarAggregates(targetStart, targetDot) && replaceOpen < 0
                && (groupByAllKeyword(tailStart, end, queryDepth) >= 0 || !hasGroupBy(tailStart, end, queryDepth))
                && allSiblingsAggregate(items, targetItem)) {
            return rewriteAggregateStructStar(select, end, items, targetItem, targetStar, targetStart, targetDot,
                    exceptOpen, exceptClose, replaceOpen, tailStart, queryDepth);
        }
        if (replaceOpen >= 0 && hasGroupBy(tailStart, end, queryDepth)
                && structStarClauseStart(tailStart, end, queryDepth, "WINDOW") < 0
                && !structStarAggregates(targetStart, targetDot)
                && replacementHasAggregate(replaceOpen, replaceClose)) {
            Set<String> groupedFields = operandFields(select, targetStart, targetDot);
            Set<String> groupedExcluded = new HashSet<>();
            if (exceptOpen >= 0) {
                for (Token token : significant(exceptOpen + 1, exceptClose)) {
                    if (token.kind == Kind.IDENT || token.kind == Kind.QIDENT) {
                        groupedExcluded.add(token.identifierText().toLowerCase(Locale.ROOT));
                    }
                }
            }
            if (groupedFields != null) {
                validateGroupedStarLists(groupedFields, groupedExcluded, exceptOpen, exceptClose,
                        replaceOpen, replaceClose);
                Set<String> groupedReplaced = replaceTargets(replaceOpen, replaceClose);
                if (!groupedFields.isEmpty()
                        && groupedFields.stream().filter(field -> !groupedExcluded.contains(field))
                        .allMatch(groupedReplaced::contains)) {
                    return rewriteGroupedStructStarReplacement(select, end, items, targetItem, targetStart,
                            targetDot, exceptOpen, exceptClose, replaceOpen, replaceClose, tailStart, queryDepth,
                            groupedFields);
                }
            }
        }
        if (structStarGroupsByPosition(tailStart, end, queryDepth)) {
            return rewriteStructStarByGroupingPositions(select, end, items, targetItem, targetStar, targetStart,
                    targetDot, exceptOpen, exceptClose, replaceOpen, replaceClose, tailStart, queryDepth);
        }
        Set<String> excludedNames = new HashSet<>();
        if (exceptOpen >= 0) {
            for (Token token : significant(exceptOpen + 1, exceptClose)) {
                if (token.kind == Kind.IDENT || token.kind == Kind.QIDENT) {
                    excludedNames.add(token.identifierText().toLowerCase(Locale.ROOT));
                }
            }
        }
        Set<String> replacedNames = replaceTargets(replaceOpen, replaceClose);
        Set<String> collidingNames = new HashSet<>(excludedNames);
        collidingNames.addAll(replacedNames);
        Set<String> reservedNames = new HashSet<>(collidingNames);
        Set<String> starFields = operandFields(select, targetStart, targetDot);
        if (starFields != null) {
            reservedNames.addAll(starFields);
        }
        Set<String> outputStarFields = starFields;
        if (starFields != null) {
            outputStarFields = new LinkedHashSet<>(starFields);
            outputStarFields.removeAll(excludedNames);
        }
        List<String> outputNames = new ArrayList<>();
        for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
            if (itemIndex == targetItem) {
                outputNames.add(null);
                continue;
            }
            int[] item = items.get(itemIndex);
            String outputName = outputNameText(item[0], item[1]);
            if (expandsStar(item) || outputName == null) {
                throw unsupportedStructStarExclusion();
            }
            outputNames.add(outputName);
            reservedNames.add(outputName.toLowerCase(Locale.ROOT));
        }
        reserveStructStarNames(select, end);
        structStarTakenNames.addAll(reservedNames);
        reservedNames.addAll(structStarTakenNames);
        for (String outputName : outputNames) {
            if (outputName != null) {
                structStarTakenNames.add(outputName.toLowerCase(Locale.ROOT));
            }
        }
        Map<Integer, String> projectionAliases = new HashMap<>();
        List<String> outputRenames = new ArrayList<>();
        Set<String> outputTakenNames = new HashSet<>(replacedNames);
        if (starFields != null) {
            outputTakenNames.addAll(starFields);
            outputTakenNames.removeAll(excludedNames);
        }
        for (String name : outputNames) {
            if (name != null) {
                outputTakenNames.add(name.toLowerCase(Locale.ROOT));
            }
        }
        int aliasNumber = 0;
        // BigQuery suffixes a duplicate output name in select-list order: the first use keeps the name.
        Set<String> claimedNames = new HashSet<>();
        boolean renamesReplacedField = false;
        for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
            String outputName = outputNames.get(itemIndex);
            if (itemIndex == targetItem) {
                for (String replaced : replaceTargetNames(replaceOpen, replaceClose)) {
                    String key = replaced.toLowerCase(Locale.ROOT);
                    if (excludedNames.contains(key) || claimedNames.add(key)) {
                        continue;
                    }
                    String renameTo = suffixedName(replaced, outputTakenNames);
                    structStarTakenNames.add(renameTo.toLowerCase(Locale.ROOT));
                    renamesReplacedField = true;
                    outputRenames.add(DuckTypes.quoteIdentifier(replaced) + " AS "
                            + DuckTypes.quoteIdentifier(renameTo));
                }
                continue;
            }
            if (outputName == null || !collidingNames.contains(outputName.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String projectionAlias;
            do {
                projectionAlias = "_floci_projection_" + aliasNumber++;
            } while (reservedNames.contains(projectionAlias.toLowerCase(Locale.ROOT)));
            reservedNames.add(projectionAlias.toLowerCase(Locale.ROOT));
            structStarTakenNames.add(projectionAlias.toLowerCase(Locale.ROOT));
            projectionAliases.put(itemIndex, projectionAlias);
            String renameTo = outputName;
            if (replacedNames.contains(renameTo.toLowerCase(Locale.ROOT))
                    && !excludedNames.contains(renameTo.toLowerCase(Locale.ROOT))
                    && !claimedNames.add(renameTo.toLowerCase(Locale.ROOT))) {
                // The name is already an earlier output, so this item needs BigQuery's suffix.
                renameTo = suffixedName(renameTo, outputTakenNames);
                structStarTakenNames.add(renameTo.toLowerCase(Locale.ROOT));
            }
            outputRenames.add(DuckTypes.quoteIdentifier(projectionAlias) + " AS "
                    + DuckTypes.quoteIdentifier(renameTo));
        }
        Map<String, String> renameTargets = new HashMap<>();
        for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
            String outputName = outputNames.get(itemIndex);
            if (outputName != null && projectionAliases.containsKey(itemIndex)) {
                renameTargets.put(outputName.toLowerCase(Locale.ROOT), projectionAliases.get(itemIndex));
            }
        }
        boolean distinct = false;
        for (int i = select + 1; i < items.getFirst()[0]; i++) {
            distinct |= tokens.get(i).isKeyword("DISTINCT");
        }
        List<String> innerNames = new ArrayList<>();
        for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
            innerNames.add(projectionAliases.getOrDefault(itemIndex, outputNames.get(itemIndex)));
        }
        StructStarSources sources = structStarSources(items.getLast()[1], end, queryDepth);
        List<String> writtenOutputNames = writtenOutputNames(outputNames, outputStarFields, targetItem);
        List<String> duplicateSafeNames = writtenOutputNames == null ? null
                : bigQueryOutputNames(writtenOutputNames);
        if (duplicateSafeNames != null && !duplicateSafeNames.equals(writtenOutputNames)) {
            validateGeneratedOrderByNames(select, items.getLast()[1], end, queryDepth, writtenOutputNames,
                    duplicateSafeNames, sources);
        }
        List<ItemColumn> itemColumns = itemColumns(items, innerNames, targetItem, -1);
        Map<String, SourceReference> sourceReferences = new LinkedHashMap<>();
        Map<String, StructProjection> structProjections = new LinkedHashMap<>();
        boolean starOutputOrder = orderNamesStarOutput(items.getLast()[1], end, queryDepth, starFields,
                excludedNames);
        RewrittenTail tail = rewriteStructStarClauses(items.getLast()[1], end, queryDepth,
                distinct || replaceOpen >= 0 || starOutputOrder, targetStart, targetDot, replaceOpen, replaceClose,
                renameTargets, sources, itemColumns, sourceReferences, structProjections, replacedNames,
                outputStarFields, innerNames);
        end -= tail.removed();
        List<Token> replacement = List.of();
        if (replaceOpen >= 0) {
            replacement = structStarReplacement(replaceOpen, replaceClose, targetStart, targetDot, sources,
                    itemColumns, sourceReferences, structProjections);
        }
        List<String> excluded = new ArrayList<>();
        if (exceptOpen >= 0) {
            for (Token token : significant(exceptOpen + 1, exceptClose)) {
                if (token.kind == Kind.IDENT || token.kind == Kind.QIDENT) {
                    excluded.add(token.identifierText());
                }
            }
        }
        excluded.addAll(generatedAliases(sourceReferences));
        for (StructProjection projection : structProjections.values()) {
            excluded.add(projection.alias());
        }
        List<Token> rewritten = new ArrayList<>();
        rewritten.addAll(new Lexer("SELECT").tokenize());
        rewritten.addAll(tokens.subList(select + 1, items.getFirst()[0]));
        rewritten.add(Token.raw(" *"));
        if (!excluded.isEmpty()) {
            rewritten.add(Token.raw(" EXCLUDE (" + excluded.stream().distinct()
                    .map(DuckTypes::quoteIdentifier).collect(Collectors.joining(", ")) + ")"));
        }
        if (replaceOpen >= 0) {
            rewritten.add(Token.raw(" REPLACE ("));
            rewritten.addAll(replacement);
            rewritten.add(Token.raw(")"));
        }
        if (!outputRenames.isEmpty() && !renamesReplacedField) {
            rewritten.add(Token.raw(" RENAME (" + String.join(", ", outputRenames) + ")"));
        }
        // DuckDB cannot unnest a scalar subquery, so an operand that is itself a query is materialized as a
        // column of an extra derived table before it is expanded.
        boolean queryOperand = isQueryOperand(targetStart, targetDot);
        String operandAlias = DuckTypes.quoteIdentifier(structStarName("_floci_operand"));
        rewritten.addAll(new Lexer(" FROM (SELECT ").tokenize());
        for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
            if (itemIndex > 0) {
                rewritten.addAll(new Lexer(", ").tokenize());
            }
            int[] item = items.get(itemIndex);
            if (itemIndex == targetItem) {
                if (queryOperand) {
                    rewritten.add(Token.raw("unnest(" + operandAlias + ")"));
                    continue;
                }
                rewritten.addAll(new Lexer("unnest(").tokenize());
                rewritten.addAll(tokens.subList(targetStart, targetDot));
                rewritten.addAll(new Lexer(")").tokenize());
            } else if (queryOperand) {
                rewritten.add(Token.raw(DuckTypes.quoteIdentifier(innerNames.get(itemIndex))));
            } else {
                String projectionAlias = projectionAliases.get(itemIndex);
                int first = nextSignificant(item[0], item[1]);
                if (projectionAlias == null) {
                    rewritten.addAll(tokens.subList(first, item[1]));
                } else {
                    int as = topLevelAs(item[0], item[1]);
                    int alias = as >= 0 ? as : implicitSelectAlias(item[0], item[1]);
                    int expressionEnd = alias >= 0 ? alias : item[1];
                    while (expressionEnd > first && tokens.get(expressionEnd - 1).kind == Kind.SPACE) {
                        expressionEnd--;
                    }
                    rewritten.addAll(tokens.subList(first, expressionEnd));
                    rewritten.add(Token.raw(" AS " + DuckTypes.quoteIdentifier(projectionAlias) + " "));
                }
            }
        }
        if (queryOperand) {
            // The expansion select only expands the operand, so the columns the outer clauses need are
            // carried through it by name.
            for (SourceReference reference : sourceReferences.values()) {
                rewritten.add(Token.raw(", " + DuckTypes.quoteIdentifier(reference.alias())));
            }
            for (StructProjection projection : structProjections.values()) {
                rewritten.add(Token.raw(", " + DuckTypes.quoteIdentifier(projection.alias())));
            }
            rewritten.addAll(new Lexer(" FROM (SELECT ").tokenize());
            for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
                if (itemIndex > 0) {
                    rewritten.addAll(new Lexer(", ").tokenize());
                }
                if (itemIndex == targetItem) {
                    rewritten.addAll(tokens.subList(targetStart, targetDot));
                    rewritten.add(Token.raw(" AS " + operandAlias));
                    continue;
                }
                int[] item = items.get(itemIndex);
                int first = nextSignificant(item[0], item[1]);
                String projectionAlias = projectionAliases.get(itemIndex);
                if (projectionAlias == null) {
                    rewritten.addAll(tokens.subList(first, item[1]));
                    continue;
                }
                int as = topLevelAs(item[0], item[1]);
                int alias = as >= 0 ? as : implicitSelectAlias(item[0], item[1]);
                int expressionEnd = alias >= 0 ? alias : item[1];
                while (expressionEnd > first && tokens.get(expressionEnd - 1).kind == Kind.SPACE) {
                    expressionEnd--;
                }
                rewritten.addAll(tokens.subList(first, expressionEnd));
                rewritten.add(Token.raw(" AS " + DuckTypes.quoteIdentifier(projectionAlias) + " "));
            }
        }
        for (SourceReference reference : sourceReferences.values()) {
            rewritten.add(Token.raw(", " + reference.path() + " AS "
                    + DuckTypes.quoteIdentifier(reference.alias())));
        }
        for (StructProjection projection : structProjections.values()) {
            rewritten.add(Token.raw(", "));
            rewritten.addAll(projection.tokens());
            rewritten.add(Token.raw(" AS " + DuckTypes.quoteIdentifier(projection.alias())));
        }
        if (queryOperand || !sourceReferences.isEmpty() || !structProjections.isEmpty()
                || targetItem == items.size() - 1) {
            rewritten.addAll(new Lexer(" ").tokenize());
        }
        rewritten.addAll(withoutTrailingSpace(tail.inside()));
        rewritten.addAll(new Lexer(queryOperand ? ") AS `_floci_operand_source`) AS `_expanded`"
                : ") AS `_expanded`").tokenize());
        if (!renamesReplacedField) {
            rewritten = withBigQueryOutputNames(rewritten, writtenOutputNames, duplicateSafeNames);
        }
        if (!tail.outside().isEmpty()) {
            rewritten.add(Token.raw(" "));
            rewritten.addAll(tail.outside());
        }
        if (renamesReplacedField) {
            // DuckDB cannot REPLACE and RENAME one column of a star, so the renames apply one level up.
            rewritten.addFirst(Token.raw("SELECT * RENAME (" + String.join(", ", outputRenames) + ") FROM ("));
            rewritten.add(Token.raw(") AS " + DuckTypes.quoteIdentifier(structStarName("_floci_renamed"))));
        }
        tokens.subList(select, end).clear();
        tokens.addAll(select, rewritten);
        return true;
    }

    /** True when a REPLACE list contains an aggregate call at the top level of an expression. */
    private boolean replacementHasAggregate(int open, int close) {
        for (int[] range : replaceEntryRanges(open + 1, close)) {
            int as = topLevelAs(range[0], range[1]);
            if (structStarAggregates(range[0], as < 0 ? range[1] : as)) {
                return true;
            }
        }
        return false;
    }

    /** The token index of a top-level clause keyword, or -1 when the query has none. */
    private int structStarClauseStart(int start, int end, int queryDepth, String keyword) {
        for (int i = start; i < end; i++) {
            if (parenthesisDepth(i) == queryDepth && tokens.get(i).isKeyword(keyword)
                    && !isPrecededByDot(i) && startsStructStarClause(i)) {
                return i;
            }
        }
        return -1;
    }

    /** Rejects a grouped star-list name the struct does not have, with BigQuery's wording. */
    private void validateGroupedStarLists(Set<String> fields, Set<String> excluded, int exceptOpen, int exceptClose,
                                          int replaceOpen, int replaceClose) {
        if (exceptOpen >= 0) {
            for (Token token : significant(exceptOpen + 1, exceptClose)) {
                if (token.kind != Kind.IDENT && token.kind != Kind.QIDENT) {
                    continue;
                }
                String name = token.identifierText();
                if (!fields.contains(name.toLowerCase(Locale.ROOT))) {
                    throw invalidQuery("Column " + name + " in SELECT * EXCEPT list does not exist");
                }
            }
        }
        for (String target : replaceTargetNames(replaceOpen, replaceClose)) {
            String key = target.toLowerCase(Locale.ROOT);
            if (!fields.contains(key)) {
                throw invalidQuery("Column " + target + " in SELECT * REPLACE list does not exist");
            }
            if (excluded.contains(key)) {
                throw invalidQuery("Column " + target + " cannot occur in both SELECT * EXCEPT and REPLACE");
            }
        }
    }

    /**
     * Rewrites a grouped query whose struct star replaces every surviving field with an aggregate. The
     * grouped sources compute the aggregates and carry the struct through {@code ANY_VALUE}; the expansion
     * happens one level up and the replacement values are applied to its columns. Every surviving field is
     * replaced, so the struct's own values never reach the output. Colliding output names take BigQuery's
     * suffix through a final RENAME, which also keeps DuckDB's REPLACE and RENAME apart.
     */
    private boolean rewriteGroupedStructStarReplacement(int select, int end, List<int[]> items, int targetItem,
                                                        int targetStart, int targetDot, int exceptOpen,
                                                        int exceptClose, int replaceOpen, int replaceClose,
                                                        int tailStart, int queryDepth, Set<String> fields) {
        int from = -1;
        for (int i = tailStart; i < end; i++) {
            if (parenthesisDepth(i) == queryDepth && isFromClause(i)) {
                from = i;
                break;
            }
        }
        if (from < 0) {
            return false;
        }
        List<String> exceptNames = new ArrayList<>();
        Set<String> excluded = new HashSet<>();
        if (exceptOpen >= 0) {
            for (Token token : significant(exceptOpen + 1, exceptClose)) {
                if (token.kind == Kind.IDENT || token.kind == Kind.QIDENT) {
                    exceptNames.add(token.identifierText());
                    excluded.add(token.identifierText().toLowerCase(Locale.ROOT));
                }
            }
        }
        List<String> targets = replaceTargetNames(replaceOpen, replaceClose);
        Map<String, String> writtenByField = new LinkedHashMap<>();
        for (String target : targets) {
            String key = target.toLowerCase(Locale.ROOT);
            if (!fields.contains(key)) {
                throw invalidQuery("Column " + target + " in SELECT * REPLACE list does not exist");
            }
            if (excluded.contains(key)) {
                throw invalidQuery("Column " + target + " cannot occur in both SELECT * EXCEPT and REPLACE");
            }
            writtenByField.put(key, target);
        }
        for (String name : exceptNames) {
            if (!fields.contains(name.toLowerCase(Locale.ROOT))) {
                throw invalidQuery("Column " + name + " in SELECT * EXCEPT list does not exist");
            }
        }
        reserveStructStarNames(select, end);
        String starAlias = structStarName("_floci_star");
        String starFieldsAlias = structStarName("_floci_star_fields");
        String groupAlias = structStarName("_floci_group");
        String renamedAlias = structStarName("_floci_renamed_groups");
        List<String> helperAliases = new ArrayList<>();
        for (int index = 0; index < targets.size(); index++) {
            helperAliases.add(structStarName("_floci_replacement_" + index));
        }
        Set<String> reserved = new HashSet<>(structStarTakenNames);
        for (String field : fields) {
            if (!excluded.contains(field)) {
                reserved.add(field);
            }
        }
        for (int[] item : items) {
            String name = outputNameText(item[0], item[1]);
            if (name != null) {
                reserved.add(name.toLowerCase(Locale.ROOT));
            }
        }
        Set<String> claimed = new HashSet<>();
        Map<Integer, String> projections = new HashMap<>();
        List<String> renames = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            int[] item = items.get(index);
            if (index == targetItem) {
                for (String field : fields) {
                    if (excluded.contains(field)) {
                        continue;
                    }
                    if (claimed.add(field)) {
                        continue;
                    }
                    String written = writtenByField.getOrDefault(field, field);
                    String renamed = suffixedName(written, reserved);
                    claimed.add(renamed.toLowerCase(Locale.ROOT));
                    renames.add(DuckTypes.quoteIdentifier(written) + " AS "
                            + DuckTypes.quoteIdentifier(renamed));
                }
                continue;
            }
            String name = outputNameText(item[0], item[1]);
            if (name == null) {
                throw unsupportedStructStarExclusion();
            }
            String key = name.toLowerCase(Locale.ROOT);
            boolean taken = claimed.contains(key);
            String finalName = name;
            if (taken) {
                finalName = suffixedName(name, reserved);
                claimed.add(finalName.toLowerCase(Locale.ROOT));
            } else {
                claimed.add(key);
            }
            if (taken || writtenByField.containsKey(key)) {
                String projection = structStarName("_floci_projection_" + index);
                projections.put(index, projection);
                renames.add(DuckTypes.quoteIdentifier(projection) + " AS "
                        + DuckTypes.quoteIdentifier(finalName));
            }
        }
        int fromEnd = structStarFromEnd(from, end, queryDepth);
        int outside = end;
        for (String keyword : List.of("QUALIFY", "ORDER", "LIMIT", "OFFSET")) {
            int clause = structStarClauseStart(fromEnd, end, queryDepth, keyword);
            if (clause >= 0) {
                outside = Math.min(outside, clause);
            }
        }

        GroupedTail groupedTail = rewriteGroupedTailAggregates(outside, end, replaceOpen, replaceClose,
                helperAliases);
        List<Token> inner = new ArrayList<>();
        inner.addAll(new Lexer("SELECT").tokenize());
        for (int index = 0; index < items.size(); index++) {
            int[] item = items.get(index);
            inner.add(Token.raw(index == 0 ? " " : ", "));
            if (index == targetItem) {
                inner.addAll(new Lexer("ANY_VALUE(").tokenize());
                inner.addAll(tokens.subList(targetStart, targetDot));
                inner.addAll(new Lexer(")").tokenize());
                inner.add(Token.raw(" AS " + DuckTypes.quoteIdentifier(starAlias)));
                continue;
            }
            String projection = projections.get(index);
            if (projection != null) {
                inner.addAll(selectExpressionTokens(item));
                inner.add(Token.raw(" AS " + DuckTypes.quoteIdentifier(projection)));
            } else {
                inner.addAll(tokens.subList(nextSignificant(item[0], item[1]), item[1]));
            }
        }
        int helper = 0;
        for (int[] range : replaceEntryRanges(replaceOpen + 1, replaceClose)) {
            int as = topLevelAs(range[0], range[1]);
            if (as < 0 || helper >= helperAliases.size()) {
                return false;
            }
            inner.add(Token.raw(", "));
            inner.addAll(withoutLeadingSpace(withoutTrailingSpace(tokens.subList(range[0], as))));
            inner.add(Token.raw(" AS " + DuckTypes.quoteIdentifier(helperAliases.get(helper++))));
        }
        for (ExtraHelper extra : groupedTail.helpers()) {
            inner.add(Token.raw(", "));
            inner.addAll(extra.expression());
            inner.add(Token.raw(" AS " + DuckTypes.quoteIdentifier(extra.alias())));
        }
        int survivingFields = 0;
        for (String field : fields) {
            if (!excluded.contains(field)) {
                survivingFields++;
            }
        }
        List<Token> innerTail = rewriteGroupedInnerTail(fromEnd, outside, queryDepth, targetItem + 1,
                survivingFields, targets, helperAliases);
        if (innerTail == null) {
            return false;
        }
        inner.add(Token.raw(" "));
        inner.addAll(tokens.subList(from, fromEnd));
        inner.addAll(withoutTrailingSpace(innerTail));

        List<Token> outer = new ArrayList<>();
        outer.addAll(new Lexer("SELECT").tokenize());
        outer.addAll(tokens.subList(select + 1, items.getFirst()[0]));
        for (int index = 0; index < items.size(); index++) {
            outer.add(Token.raw(index == 0 ? " " : ", "));
            if (index == targetItem) {
                outer.add(Token.raw(DuckTypes.quoteIdentifier(starFieldsAlias) + ".*"));
                if (!exceptNames.isEmpty()) {
                    outer.add(Token.raw(" EXCLUDE (" + exceptNames.stream().map(DuckTypes::quoteIdentifier)
                            .collect(Collectors.joining(", ")) + ")"));
                }
                List<String> replacements = new ArrayList<>();
                for (int replaceIndex = 0; replaceIndex < targets.size(); replaceIndex++) {
                    replacements.add(DuckTypes.quoteIdentifier(helperAliases.get(replaceIndex)) + " AS "
                            + DuckTypes.quoteIdentifier(targets.get(replaceIndex)));
                }
                outer.add(Token.raw(" REPLACE (" + String.join(", ", replacements) + ")"));
                continue;
            }
            String projection = projections.get(index);
            String reference = projection != null ? projection : outputNameText(items.get(index)[0], items.get(index)[1]);
            outer.add(Token.raw(DuckTypes.quoteIdentifier(reference)));
        }
        List<String> helperColumns = new ArrayList<>();
        if (!renames.isEmpty()) {
            // The rename wrapper hides the grouped query, so the helpers the moved tail still names have
            // to travel through it. They are excluded from the final output.
            helperColumns.addAll(helperAliases);
            for (ExtraHelper extra : groupedTail.helpers()) {
                if (!helperColumns.contains(extra.alias())) {
                    helperColumns.add(extra.alias());
                }
            }
            for (String helperColumn : helperColumns) {
                outer.add(Token.raw(", " + DuckTypes.quoteIdentifier(groupAlias) + "."
                        + DuckTypes.quoteIdentifier(helperColumn)));
            }
        }
        outer.addAll(new Lexer(" FROM (").tokenize());
        outer.addAll(inner);
        outer.addAll(new Lexer(") AS").tokenize());
        outer.add(Token.raw(" " + DuckTypes.quoteIdentifier(groupAlias) + ", (SELECT unnest("
                + DuckTypes.quoteIdentifier(groupAlias) + "." + DuckTypes.quoteIdentifier(starAlias)
                + ")) AS " + DuckTypes.quoteIdentifier(starFieldsAlias)));

        List<Token> rewritten = outer;
        if (!renames.isEmpty()) {
            rewritten = new ArrayList<>();
            rewritten.addAll(new Lexer("SELECT *").tokenize());
            if (!helperColumns.isEmpty()) {
                rewritten.add(Token.raw(" EXCLUDE (" + helperColumns.stream().map(DuckTypes::quoteIdentifier)
                        .collect(Collectors.joining(", ")) + ")"));
            }
            rewritten.addAll(new Lexer(" RENAME (").tokenize());
            rewritten.add(Token.raw(String.join(", ", renames)));
            rewritten.addAll(new Lexer(") FROM (").tokenize());
            rewritten.addAll(outer);
            rewritten.addAll(new Lexer(") AS `" + renamedAlias + "`").tokenize());
        }
        if (outside < end) {
            rewritten.add(Token.raw(" "));
            rewritten.addAll(withoutLeadingSpace(groupedTail.tokens()));
        }
        tokens.subList(select, end).clear();
        tokens.addAll(select, rewritten);
        return true;
    }

    /**
     * The inner grouped tail: GROUP BY positions count the original select list, where the star occupies one
     * position per expanded field, and a HAVING alias for a replaced field names the helper column. Returns
     * null when a position names a field of the star, which BigQuery rejects for an aggregate replacement.
     */
    private List<Token> rewriteGroupedInnerTail(int start, int end, int queryDepth, int starStart,
                                                int survivingFields, List<String> targets,
                                                List<String> helpers) {
        List<Token> tail = new ArrayList<>(tokens.subList(start, end));
        int group = structStarClauseStart(start, end, queryDepth, "GROUP");
        if (group >= 0) {
            int by = nextSignificant(group + 1, end);
            int clauseEnd = end;
            for (int i = by + 1; i < end; i++) {
                if (parenthesisDepth(i) == queryDepth && startsStructStarClause(i)) {
                    clauseEnd = i;
                    break;
                }
            }
            if (by >= 0) {
                for (int[] range : topLevelRanges(by + 1, clauseEnd, queryDepth)) {
                    if (!isColumnPosition(range[0], range[1])) {
                        continue;
                    }
                    int position = nextSignificant(range[0], range[1]);
                    int original = Integer.parseInt(tokens.get(position).text);
                    int mapped = original;
                    if (original >= starStart) {
                        if (original < starStart + survivingFields) {
                            return null;
                        }
                        mapped = original - survivingFields + 1;
                    }
                    if (mapped != original) {
                        tail.set(position - start, new Token(Kind.NUMBER, String.valueOf(mapped), null));
                    }
                }
            }
        }
        int having = structStarClauseStart(start, end, queryDepth, "HAVING");
        if (having < 0) {
            return tail;
        }
        Map<String, String> aliasByTarget = new HashMap<>();
        for (int index = 0; index < targets.size() && index < helpers.size(); index++) {
            aliasByTarget.put(targets.get(index).toLowerCase(Locale.ROOT), helpers.get(index));
        }
        int conditionStart = nextSignificant(having + 1, end);
        int conditionEnd = end;
        for (String keyword : List.of("QUALIFY", "ORDER", "LIMIT", "OFFSET", "WINDOW")) {
            int clause = structStarClauseStart(having + 1, end, queryDepth, keyword);
            if (clause >= 0) {
                conditionEnd = Math.min(conditionEnd, clause);
            }
        }
        if (conditionStart < 0) {
            return tail;
        }
        boolean[] nested = nestedQueryPositions(conditionStart, conditionEnd);
        for (int i = conditionStart; i < conditionEnd; i++) {
            if (nested[i]) {
                continue;
            }
            Token token = tail.get(i - start);
            if (token.kind != Kind.IDENT && token.kind != Kind.QIDENT) {
                continue;
            }
            int previous = i - 1;
            while (previous >= start && tokens.get(previous).kind == Kind.SPACE) {
                previous--;
            }
            if (previous >= 0 && tokens.get(previous).isPunct(".")) {
                continue;
            }
            int next = nextSignificant(i + 1, conditionEnd);
            if (next >= 0 && (tokens.get(next).isPunct("(") || tokens.get(next).isPunct("."))) {
                continue;
            }
            String helper = aliasByTarget.get(token.identifierText().toLowerCase(Locale.ROOT));
            if (helper != null) {
                tail.set(i - start, Token.raw(DuckTypes.quoteIdentifier(helper)));
            }
        }
        return tail;
    }

    /** The tail that follows the grouped wrapper, plus any aggregate helpers it needs. */
    private record GroupedTail(List<Token> tokens, List<ExtraHelper> helpers) {}

    /** An aggregate expression projected in the grouped inner query for the moved tail. */
    private record ExtraHelper(String alias, List<Token> expression) {}

    /**
     * Points aggregates the moved QUALIFY/ORDER BY still names at a column the grouped query computed. An
     * expression that is already in the REPLACE list reuses its helper; any other aggregate gets its own
     * helper. A nested query keeps its own scope.
     */
    private GroupedTail rewriteGroupedTailAggregates(int start, int end, int replaceOpen, int replaceClose,
                                                     List<String> replaceHelpers) {
        List<Token> tail = new ArrayList<>(tokens.subList(start, end));
        List<ExtraHelper> helpers = new ArrayList<>();
        if (start >= end) {
            return new GroupedTail(tail, helpers);
        }
        Map<String, String> helperByExpression = new LinkedHashMap<>();
        if (replaceOpen >= 0) {
            List<int[]> ranges = replaceEntryRanges(replaceOpen + 1, replaceClose);
            for (int index = 0; index < ranges.size() && index < replaceHelpers.size(); index++) {
                int as = topLevelAs(ranges.get(index)[0], ranges.get(index)[1]);
                if (structStarAggregates(ranges.get(index)[0], as < 0 ? ranges.get(index)[1] : as)) {
                    helperByExpression.put(expressionKey(ranges.get(index)[0],
                            as < 0 ? ranges.get(index)[1] : as), replaceHelpers.get(index));
                }
            }
        }
        boolean[] nested = nestedQueryPositions(start, end);
        Map<Integer, Integer> spanEnd = new LinkedHashMap<>();
        Map<Integer, String> spanHelper = new HashMap<>();
        int i = start;
        while (i < end) {
            if (nested[i]) {
                i++;
                continue;
            }
            Token token = tokens.get(i);
            if (token.kind != Kind.IDENT || isPrecededByDot(i)
                    || !STRUCT_STAR_AGGREGATES.contains(token.upper())) {
                i++;
                continue;
            }
            int open = nextSignificant(i + 1, end);
            if (open < 0 || !tokens.get(open).isPunct("(")) {
                i++;
                continue;
            }
            int close = matchingParen(open);
            int after = nextSignificant(close + 1, end);
            if (after >= 0 && tokens.get(after).isKeyword("OVER")) {
                // A window function is computed after grouping, so only aggregates inside it are projected.
                i = close + 1;
                continue;
            }
            String key = expressionKey(i, close + 1);
            String helper = helperByExpression.get(key);
            if (helper == null) {
                helper = structStarName("_floci_aggregate_" + helpers.size());
                helpers.add(new ExtraHelper(helper, new ArrayList<>(tokens.subList(i, close + 1))));
                helperByExpression.put(key, helper);
            }
            spanEnd.put(i, close);
            spanHelper.put(i, helper);
            i = close + 1;
        }
        if (spanEnd.isEmpty()) {
            return new GroupedTail(tail, helpers);
        }
        List<Token> out = new ArrayList<>();
        for (int index = start; index < end; index++) {
            Integer last = spanEnd.get(index);
            if (last != null) {
                out.add(Token.raw(DuckTypes.quoteIdentifier(spanHelper.get(index))));
                index = last;
                continue;
            }
            out.add(tokens.get(index));
        }
        return new GroupedTail(out, helpers);
    }

    /** A key that compares two expressions token by token, ignoring spaces and identifier case. */
    private String expressionKey(int start, int end) {
        StringBuilder key = new StringBuilder();
        for (Token token : significant(start, end)) {
            if (token.kind == Kind.IDENT || token.kind == Kind.QIDENT) {
                key.append(token.identifierText().toLowerCase(Locale.ROOT));
            } else {
                key.append(token.text);
            }
            key.append('|');
        }
        return key.toString();
    }

    /**
     * Rewrites a select list with more than one star expansion. Each dotted STRUCT star is expanded by a
     * correlated derived table, so the original sources stay in the outer FROM: a REPLACE expression reads
     * the FROM row as BigQuery reads it, and the star's EXCEPT/REPLACE/RENAME applies only to its own
     * columns. An unqualified {@code *} beside a struct star stays unsupported because it would also pick
     * up the derived tables.
     */
    private boolean rewriteMultipleStructStars(int select, int end, List<int[]> items,
                                               List<SelectStructStar> stars, StructStarSources sources,
                                               int queryDepth) {
        int tailStart = items.getLast()[1];
        end = structStarArmEnd(tailStart, end, queryDepth);
        end = structStarSetOperationTail(select, tailStart, end, queryDepth);
        int from = -1;
        for (int i = tailStart; i < end; i++) {
            if (parenthesisDepth(i) == queryDepth && isFromClause(i)) {
                from = i;
                break;
            }
        }
        reserveStructStarNames(select, end);
        Set<String> reserved = new HashSet<>(structStarTakenNames);
        Map<Integer, SelectStructStar> starsByItem = new HashMap<>();
        for (SelectStructStar star : stars) {
            starsByItem.put(star.itemIndex(), star);
            if (star.fields() != null) {
                for (String field : star.fields()) {
                    if (!star.excludedFields().contains(field)) {
                        reserved.add(field);
                    }
                }
            }
        }
        for (int index = 0; index < items.size(); index++) {
            if (starsByItem.containsKey(index)) {
                continue;
            }
            String name = outputNameText(items.get(index)[0], items.get(index)[1]);
            if (name != null) {
                reserved.add(name.toLowerCase(Locale.ROOT));
            }
        }
        int fromEnd = from >= 0 ? structStarFromEnd(from, end, queryDepth)
                : structStarClauseBoundary(tailStart, end, queryDepth);
        replaceMultiStarGroupByAll(fromEnd, end, queryDepth, items, starsByItem);
        Set<String> claimed = new HashSet<>();
        List<List<Token>> outputItems = new ArrayList<>();
        Map<Integer, String> starAliases = new HashMap<>();
        Map<Integer, Map<String, String>> starRenames = new HashMap<>();
        for (int index = 0; index < items.size(); index++) {
            int[] item = items.get(index);
            SelectStructStar star = starsByItem.get(index);
            if (star != null) {
                String alias = structStarName("_floci_star_" + index);
                starAliases.put(index, alias);
                Map<String, String> renames = new LinkedHashMap<>();
                starRenames.put(index, renames);
                outputItems.add(renderExpandedStar(select, star, alias, sources, claimed, reserved, renames));
                continue;
            }
            String name = outputNameText(item[0], item[1]);
            if (name == null) {
                outputItems.add(withoutTrailingSpace(tokens.subList(nextSignificant(item[0], item[1]), item[1])));
                continue;
            }
            String key = name.toLowerCase(Locale.ROOT);
            if (!claimed.add(key)) {
                String renamed = suffixedName(name, reserved);
                claimed.add(renamed.toLowerCase(Locale.ROOT));
                outputItems.add(renderRenamedItem(item, renamed));
                continue;
            }
            outputItems.add(withoutTrailingSpace(tokens.subList(nextSignificant(item[0], item[1]), item[1])));
        }
        List<List<Token>> derivedSources = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            SelectStructStar star = starsByItem.get(index);
            if (star == null) {
                continue;
            }
            List<Token> expansion = new ArrayList<>();
            expansion.addAll(new Lexer("SELECT unnest(").tokenize());
            // DuckDB cannot unnest a scalar subquery, so a query operand is materialized as a column of an
            // extra derived table and expanded from there.
            if (isQueryOperand(star.operandStart(), star.dot())) {
                String operandAlias = structStarName("_floci_operand_" + index);
                String sourceAlias = structStarName("_floci_operand_source_" + index);
                List<Token> operand = new ArrayList<>();
                operand.addAll(new Lexer("(SELECT ").tokenize());
                operand.addAll(tokens.subList(star.operandStart(), star.dot()));
                operand.addAll(new Lexer(" AS `" + operandAlias + "`) AS `" + sourceAlias + "`").tokenize());
                derivedSources.add(operand);
                expansion.add(Token.raw(DuckTypes.quoteIdentifier(sourceAlias) + "."
                        + DuckTypes.quoteIdentifier(operandAlias)));
            } else {
                expansion.addAll(tokens.subList(star.operandStart(), star.dot()));
            }
            expansion.addAll(new Lexer(")").tokenize());
            Map<String, String> renameFields = starRenames.get(index);
            List<Token> source = new ArrayList<>();
            if (!renameFields.isEmpty()) {
                source.addAll(new Lexer("(SELECT * RENAME (").tokenize());
                source.add(Token.raw(starRenameList(star, renameFields)));
                source.addAll(new Lexer(") FROM (").tokenize());
                source.addAll(expansion);
                source.addAll(new Lexer(")) AS `" + starAliases.get(index) + "`").tokenize());
            } else {
                source.addAll(new Lexer("(").tokenize());
                source.addAll(expansion);
                source.addAll(new Lexer(") AS `" + starAliases.get(index) + "`").tokenize());
            }
            derivedSources.add(source);
        }
        List<Token> fromTokens = new ArrayList<>();
        if (from >= 0) {
            fromTokens.addAll(tokens.subList(from, fromEnd));
            for (List<Token> source : derivedSources) {
                fromTokens.addAll(new Lexer(", ").tokenize());
                fromTokens.addAll(source);
            }
        } else {
            fromTokens.addAll(new Lexer("FROM ").tokenize());
            for (int index = 0; index < derivedSources.size(); index++) {
                if (index > 0) {
                    fromTokens.addAll(new Lexer(", ").tokenize());
                }
                fromTokens.addAll(derivedSources.get(index));
            }
        }
        if (!derivedSources.isEmpty()) {
            fromTokens.add(Token.raw(" "));
        }
        List<Token> rewritten = new ArrayList<>();
        rewritten.addAll(new Lexer("SELECT").tokenize());
        rewritten.addAll(tokens.subList(select + 1, items.getFirst()[0]));
        for (int index = 0; index < outputItems.size(); index++) {
            rewritten.add(Token.raw(index == 0 ? " " : ", "));
            rewritten.addAll(withoutTrailingSpace(outputItems.get(index)));
        }
        rewritten.add(Token.raw(" "));
        rewritten.addAll(fromTokens);
        tokens.subList(select, fromEnd).clear();
        tokens.addAll(select, rewritten);
        return true;
    }

    /**
     * Replaces {@code GROUP BY ALL} with the explicit output positions when a {@code QUALIFY} clause follows,
     * because DuckDB cannot combine the two. The positions count the star expansions exactly as the rebuilt
     * select list emits them.
     */
    private void replaceMultiStarGroupByAll(int fromEnd, int end, int queryDepth, List<int[]> items,
                                            Map<Integer, SelectStructStar> starsByItem) {
        int group = groupByAllKeyword(fromEnd, end, queryDepth);
        if (group < 0 || structStarQualifyStart(fromEnd, end, queryDepth) < 0) {
            return;
        }
        List<Integer> positions = new ArrayList<>();
        int position = 1;
        for (int index = 0; index < items.size(); index++) {
            int[] item = items.get(index);
            SelectStructStar star = starsByItem.get(index);
            if (star != null) {
                if (star.fields() == null) {
                    return;
                }
                int count = 0;
                for (String field : star.fields()) {
                    if (!star.excludedFields().contains(field)) {
                        count++;
                    }
                }
                if (count == 0) {
                    return;
                }
                for (int offset = 0; offset < count; offset++) {
                    positions.add(position + offset);
                }
                position += count;
                continue;
            }
            int first = nextSignificant(item[0], item[1]);
            int as = topLevelAs(item[0], item[1]);
            int alias = as >= 0 ? as : implicitSelectAlias(item[0], item[1]);
            int expressionEnd = alias >= 0 ? alias : item[1];
            if (!structStarAggregates(first, expressionEnd) && !hasWindowExpression(item[0], expressionEnd)) {
                positions.add(position);
            }
            position++;
        }
        int by = nextSignificant(group + 1, end);
        int all = by < 0 ? -1 : nextSignificant(by + 1, end);
        if (all >= 0) {
            tokens.set(all, Token.raw(positions.stream().map(String::valueOf)
                    .collect(Collectors.joining(", "))));
        }
    }

    /** The dotted STRUCT stars in this select list, each with its EXCEPT/REPLACE lists resolved. */
    private List<SelectStructStar> selectStructStars(int select, List<int[]> items,
                                                     StructStarSources sources) {
        List<SelectStructStar> stars = new ArrayList<>();
        for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
            int[] item = items.get(itemIndex);
            for (int star = item[0]; star < item[1]; star++) {
                if (!tokens.get(star).isPunct("*") || insideNestedQuery(star, select)) {
                    continue;
                }
                int dot = significantBefore(star);
                int last = dot < 0 ? -1 : significantBefore(dot);
                if (dot < item[0] || !tokens.get(dot).isPunct(".") || last < 0) {
                    continue;
                }
                int operandStart = starOperandStart(last);
                Set<String> fields;
                boolean bareName = operandStart == last
                        && (tokens.get(last).kind == Kind.IDENT || tokens.get(last).kind == Kind.QIDENT)
                        && !tokens.get(last).identifierText().contains(".");
                if (bareName) {
                    String name = tokens.get(last).identifierText().toLowerCase(Locale.ROOT);
                    if (sources.aliases().contains(name)) {
                        // A whole-row star such as t.* keeps its own meaning; it is not a STRUCT expansion.
                        continue;
                    }
                    fields = operandFields(select, operandStart, dot);
                    if (fields == null) {
                        // A table or CTE name without an alias, which is also a whole-row star.
                        continue;
                    }
                } else {
                    fields = operandFields(select, operandStart, dot);
                    if (fields == null && isQueryOperand(operandStart, dot)) {
                        fields = structLiteralFieldsInQuery(operandStart, dot);
                    }
                }
                int exceptOpen = -1;
                int exceptClose = -1;
                int replaceOpen = -1;
                int replaceClose = -1;
                int clause = nextSignificant(star + 1, item[1]);
                if (clause >= 0 && tokens.get(clause).isKeyword("EXCEPT")) {
                    int open = nextSignificant(clause + 1, item[1]);
                    if (open < 0 || !tokens.get(open).isPunct("(")) {
                        continue;
                    }
                    exceptOpen = open;
                    exceptClose = matchingParen(open);
                    int after = nextSignificant(exceptClose + 1, item[1]);
                    if (after >= 0 && tokens.get(after).isKeyword("REPLACE")) {
                        int replaceStart = nextSignificant(after + 1, item[1]);
                        if (replaceStart < 0 || !tokens.get(replaceStart).isPunct("(")) {
                            continue;
                        }
                        replaceOpen = replaceStart;
                        replaceClose = matchingParen(replaceStart);
                    }
                } else if (clause >= 0 && tokens.get(clause).isKeyword("REPLACE")) {
                    int open = nextSignificant(clause + 1, item[1]);
                    if (open < 0 || !tokens.get(open).isPunct("(")) {
                        continue;
                    }
                    replaceOpen = open;
                    replaceClose = matchingParen(open);
                }
                List<String> exceptNames = new ArrayList<>();
                Set<String> excludedFields = new HashSet<>();
                if (exceptOpen >= 0) {
                    for (Token token : significant(exceptOpen + 1, exceptClose)) {
                        if (token.kind == Kind.IDENT || token.kind == Kind.QIDENT) {
                            exceptNames.add(token.identifierText());
                            excludedFields.add(token.identifierText().toLowerCase(Locale.ROOT));
                        }
                    }
                }
                validateAggregateStarOperand(select, operandStart, dot);
                stars.add(new SelectStructStar(itemIndex, operandStart, dot, exceptOpen, exceptClose, exceptNames,
                        excludedFields, replaceOpen, replaceClose, replaceTargets(replaceOpen, replaceClose),
                        fields));
                break;
            }
        }
        return stars;
    }

    /** The token index of the unqualified star a select item expands, or -1 for a non-star item. */
    private int projectionStarIndex(int[] item) {
        for (int i = item[0]; i < item[1]; i++) {
            if (!tokens.get(i).isPunct("*")) {
                continue;
            }
            int next = nextSignificant(i + 1, item[1]);
            if (next < 0 || tokens.get(next).isKeyword("EXCEPT") || tokens.get(next).isKeyword("REPLACE")) {
                return i;
            }
        }
        return -1;
    }

    /** The token index that ends the FROM clause of the query at {@code queryDepth}. */
    private int structStarFromEnd(int from, int end, int queryDepth) {
        for (int i = from + 1; i < end; i++) {
            if (parenthesisDepth(i) == queryDepth && startsStructStarClause(i)) {
                return i;
            }
        }
        return end;
    }

    /** The token index of the first top-level clause after a select list that has no FROM. */
    private int structStarClauseBoundary(int start, int end, int queryDepth) {
        for (int i = start; i < end; i++) {
            if (parenthesisDepth(i) == queryDepth && startsStructStarClause(i)) {
                return i;
            }
        }
        return end;
    }

    /** The fields of a STRUCT literal returned by a scalar-subquery operand, or null when it is not one. */
    private Set<String> structLiteralFieldsInQuery(int start, int end) {
        for (int i = start; i < end; i++) {
            if (!tokens.get(i).isKeyword("STRUCT")) {
                continue;
            }
            int open = nextSignificant(i + 1, end);
            if (open < 0 || !tokens.get(open).isPunct("(")) {
                continue;
            }
            int close = matchingParen(open);
            Set<String> fields = structLiteralFields(i, close + 1);
            if (fields != null) {
                return fields;
            }
        }
        return null;
    }

    /** One star expansion in the multi-star structure, with its own EXCLUDE and REPLACE lists. */
    private List<Token> renderExpandedStar(int select, SelectStructStar star, String alias,
                                           StructStarSources sources, Set<String> claimed,
                                           Set<String> reserved, Map<String, String> renamedFields) {
        validateStarLists(star);
        List<Token> out = new ArrayList<>();
        out.add(Token.raw(DuckTypes.quoteIdentifier(alias) + ".*"));
        if (star.exceptOpen() >= 0 && !star.exceptNames().isEmpty()) {
            out.add(Token.raw(" EXCLUDE (" + star.exceptNames().stream().map(DuckTypes::quoteIdentifier)
                    .collect(Collectors.joining(", ")) + ")"));
        }
        if (star.fields() != null) {
            for (String field : star.fields()) {
                if (star.excludedFields().contains(field)) {
                    continue;
                }
                if (claimed.add(field)) {
                    continue;
                }
                String renamed = suffixedName(field, reserved);
                claimed.add(renamed.toLowerCase(Locale.ROOT));
                renamedFields.put(field, renamed);
            }
        }
        if (star.replaceOpen() >= 0) {
            out.add(Token.raw(" REPLACE ("));
            out.addAll(qualifyReplacementNames(star.replaceOpen() + 1, star.replaceClose(), select, sources, false,
                    renamedFields));
            out.add(Token.raw(")"));
        }
        return out;
    }

    /**
     * The RENAME list applied inside a star's expansion. Renaming there keeps a replaced field out of
     * DuckDB's REPLACE and RENAME conflict, and the outer star still expands in field order.
     */
    private String starRenameList(SelectStructStar star, Map<String, String> renamedFields) {
        Map<String, String> written = new LinkedHashMap<>();
        for (String target : replaceTargetNames(star.replaceOpen(), star.replaceClose())) {
            written.put(target.toLowerCase(Locale.ROOT), target);
        }
        return renamedFields.entrySet().stream()
                .map(entry -> DuckTypes.quoteIdentifier(written.getOrDefault(entry.getKey(), entry.getKey()))
                        + " AS " + DuckTypes.quoteIdentifier(entry.getValue()))
                .collect(Collectors.joining(", "));
    }

    /** Rejects a star list name the struct does not have, with BigQuery's wording. */
    private void validateStarLists(SelectStructStar star) {
        if (star.fields() == null) {
            return;
        }
        for (String name : star.exceptNames()) {
            if (!star.fields().contains(name.toLowerCase(Locale.ROOT))) {
                throw invalidQuery("Column " + name + " in SELECT * EXCEPT list does not exist");
            }
        }
        for (String target : replaceTargetNames(star.replaceOpen(), star.replaceClose())) {
            String key = target.toLowerCase(Locale.ROOT);
            if (!star.fields().contains(key)) {
                throw invalidQuery("Column " + target + " in SELECT * REPLACE list does not exist");
            }
            if (star.excludedFields().contains(key)) {
                throw invalidQuery("Column " + target + " cannot occur in both SELECT * EXCEPT and REPLACE");
            }
        }
    }

    /** Copies a non-star select item with a different output name. */
    private List<Token> renderRenamedItem(int[] item, String name) {
        List<Token> out = new ArrayList<>(selectExpressionTokens(item));
        out.add(Token.raw(" AS " + DuckTypes.quoteIdentifier(name)));
        return out;
    }

    /** The expression of a select item without its output name. */
    private List<Token> selectExpressionTokens(int[] item) {
        int first = nextSignificant(item[0], item[1]);
        int as = topLevelAs(item[0], item[1]);
        int last = item[1] - 1;
        while (last >= item[0] && tokens.get(last).kind == Kind.SPACE) {
            last--;
        }
        int expressionEnd = item[1];
        if (as >= 0) {
            expressionEnd = as;
        } else if (last >= 0 && tokens.get(last).kind == Kind.RAW
                && tokens.get(last).text.trim().regionMatches(true, 0, "AS ", 0, 3)) {
            expressionEnd = last;
        }
        return withoutTrailingSpace(tokens.subList(first, expressionEnd));
    }

    /**
     * Copies a REPLACE list for the multi-star structure. The original sources stay in the outer FROM, so a
     * bare name is qualified with the source that supplies it instead of being projected; a name no source
     * supplies is rejected the way BigQuery rejects it, and a nested query keeps its own scope.
     */
    private List<Token> qualifyReplacementNames(int start, int close, int select, StructStarSources sources,
                                                boolean nested, Map<String, String> renamedFields) {
        Set<Integer> targetAliases = new HashSet<>();
        if (!renamedFields.isEmpty()) {
            for (int[] range : replaceEntryRanges(start, close)) {
                int as = topLevelAs(range[0], range[1]);
                int alias = as < 0 ? -1 : nextSignificant(as + 1, range[1]);
                if (alias >= 0) {
                    targetAliases.add(alias);
                }
            }
        }
        List<Token> out = new ArrayList<>();
        for (int i = start; i < close; i++) {
            Token token = tokens.get(i);
            if (token.isPunct("(")) {
                int nestedEnd = nestedQueryClose(i, close);
                if (nestedEnd >= 0) {
                    out.add(token);
                    out.addAll(qualifyReplacementNames(i + 1, nestedEnd, select,
                            sources.without(nestedAliases(i + 1, nestedEnd)), true, Map.of()));
                    out.add(tokens.get(nestedEnd));
                    i = nestedEnd;
                    continue;
                }
            }
            if (targetAliases.contains(i)) {
                String renamed = renamedFields.get(token.identifierText().toLowerCase(Locale.ROOT));
                if (renamed != null) {
                    out.add(Token.raw(DuckTypes.quoteIdentifier(renamed)));
                    continue;
                }
            }
            int previous = significantBefore(i);
            boolean namesAlias = previous >= 0 && tokens.get(previous).isKeyword("AS");
            if (!nested && isName(token) && !isPrecededByDot(i) && !namesAlias) {
                String name = token.identifierText();
                int next = nextSignificant(i + 1, close);
                boolean call = next >= 0 && tokens.get(next).isPunct("(");
                boolean dottedCall = next >= 0 && tokens.get(next).isPunct(".") && dottedCallFollows(i, close);
                if (!call && !dottedCall && !inSyntaxRole(i, close)
                        && !STRUCT_STAR_SYNTAX_WORDS.contains(token.upper())) {
                    if (sources.aliases().contains(name.toLowerCase(Locale.ROOT))) {
                        // A source alias read as a whole row stays visible in the outer FROM.
                        out.add(token);
                        continue;
                    }
                    String reference = replacementSourceReference(select, name, sources);
                    if (reference != null) {
                        PathTail tail = pathTail(next, close);
                        out.add(Token.raw(reference + tail.text()));
                        i = tail.lastIndex() < 0 ? i : tail.lastIndex();
                        continue;
                    }
                }
            }
            out.add(token);
        }
        return out;
    }

    /** True when a name is followed by {@code .field(...)}, which names a dotted function, not a column. */
    private boolean dottedCallFollows(int index, int end) {
        int dot = nextSignificant(index + 1, end);
        int field = dot < 0 ? -1 : nextSignificant(dot + 1, end);
        if (dot < 0 || !tokens.get(dot).isPunct(".") || field < 0 || !isName(tokens.get(field))) {
            return false;
        }
        int after = nextSignificant(field + 1, end);
        return after >= 0 && tokens.get(after).isPunct("(");
    }

    /**
     * The source reference a bare REPLACE name belongs to: this query's source, else an enclosing query's,
     * else the statement's. A USING column becomes the merged value, so an outer join keeps the side that
     * has a row. Null when this query's sources are not fully known, so the name may still belong to one of
     * them.
     */
    private String replacementSourceReference(int select, String name, StructStarSources sources) {
        String key = name.toLowerCase(Locale.ROOT);
        List<String> merged = sources.usingSources().get(key);
        if (merged != null && merged.size() > 1) {
            return merged.stream()
                    .map(alias -> DuckTypes.quoteIdentifier(alias) + "." + DuckTypes.quoteIdentifier(name))
                    .collect(Collectors.joining(", ", "COALESCE(", ")"));
        }
        String qualifier = replacementSourceQualifier(select, name, sources);
        return qualifier == null ? null : DuckTypes.quoteIdentifier(qualifier) + "."
                + DuckTypes.quoteIdentifier(name);
    }

    private String replacementSourceQualifier(int select, String name, StructStarSources sources) {
        List<String> owners = sourceAliasesIn(sources, name);
        if (owners.size() == 1) {
            return owners.getFirst();
        }
        if (owners.size() > 1) {
            List<String> merged = sources.usingSources().get(name.toLowerCase(Locale.ROOT));
            if (merged != null) {
                return merged.getFirst();
            }
            throw invalidQuery("Column name " + name + " is ambiguous");
        }
        for (int outer = parentQuerySelect(select); outer >= 0; outer = parentQuerySelect(outer)) {
            List<String> outerOwners = sourceAliasesFor(outer, name);
            if (outerOwners.size() == 1) {
                return outerOwners.getFirst();
            }
            if (outerOwners.size() > 1) {
                throw invalidQuery("Column name " + name + " is ambiguous");
            }
        }
        if (enclosingQueryParen(select) >= 0) {
            List<String> statementOwners = statementAliasesFor(name);
            if (statementOwners.size() == 1) {
                return statementOwners.getFirst();
            }
            if (statementOwners.size() > 1) {
                throw invalidQuery("Column name " + name + " is ambiguous");
            }
        }
        if (sources.allKnown()) {
            throw invalidQuery("Unrecognized name: " + name);
        }
        return null;
    }

    private List<String> sourceAliasesIn(StructStarSources sources, String name) {
        List<String> owners = new ArrayList<>();
        String key = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Set<String>> entry : sources.columnsByAlias().entrySet()) {
            if (entry.getValue().contains(key)) {
                owners.add(entry.getKey());
            }
        }
        return owners;
    }

    /**
     * True when the query groups by output column positions or by {@code ALL}. Those keys count the projected
     * columns, which only exist outside the star's expansion, so the query needs the flat structure that
     * {@link #rewriteStructStarByGroupingPositions} builds.
     */
    private boolean structStarGroupsByPosition(int tailStart, int end, int queryDepth) {
        for (int i = tailStart; i < end; i++) {
            if (parenthesisDepth(i) != queryDepth || !tokens.get(i).isKeyword("GROUP") || isPrecededByDot(i)) {
                continue;
            }
            int by = nextSignificant(i + 1, end);
            if (by < 0 || !tokens.get(by).isKeyword("BY")) {
                continue;
            }
            int clauseEnd = end;
            for (int k = by + 1; k < end; k++) {
                if (parenthesisDepth(k) == queryDepth && tokens.get(k).kind == Kind.IDENT && !isPrecededByDot(k)
                        && STRUCT_STAR_CLAUSES.contains(tokens.get(k).upper())) {
                    clauseEnd = k;
                    break;
                }
            }
            if (ALL.equals(only(by + 1, clauseEnd))) {
                return true;
            }
            for (int[] range : topLevelRanges(by + 1, clauseEnd, queryDepth)) {
                if (isColumnPosition(range[0], range[1])) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    /**
     * Rewrites a star whose own expression is an aggregate, as in {@code ANY_VALUE(t.s).* EXCEPT (a), COUNT(*)}
     * with {@code GROUP BY ALL} or no grouping clause. These aggregate-only select lists infer no grouping
     * keys, so the aggregates run together over the filtered source rows before expanding the struct.
     */
    private boolean rewriteAggregateStructStar(int select, int end, List<int[]> items, int targetItem,
                                               int targetStar, int targetStart, int targetDot, int exceptOpen,
                                               int exceptClose, int replaceOpen, int tailStart, int queryDepth) {
        int[] operand = unwrappedOperand(targetStart, targetDot);
        while (operand[0] < operand[1] && tokens.get(operand[0]).isPunct("(")) {
            int close = matchingParen(operand[0]);
            int inner = close < 0 ? -1 : nextSignificant(operand[0] + 1, close);
            int innerEnd = close < 0 ? -1 : previousSignificant(close - 1);
            if (close < 0 || close >= operand[1] || inner < 0 || innerEnd < inner) {
                break;
            }
            // An indexed aggregate can be wrapped in parentheses, as in (ARRAY_AGG(t))[OFFSET(0)].*.
            operand = new int[] {inner, previousSignificant(innerEnd) + 1};
        }
        int callOpen = nextSignificant(operand[0] + 1, operand[1]);
        int callClose = callOpen < 0 ? -1 : matchingParen(callOpen);
        int group = groupByAllKeyword(tailStart, end, queryDepth);
        if (hasGroupBy(tailStart, end, queryDepth) && group < 0) {
            // A positional GROUP BY cannot name the fields an aggregate star produces.
            throw unsupportedStructStarExclusion();
        }
        if (replaceOpen >= 0 || callOpen < 0 || !tokens.get(callOpen).isPunct("(") || callClose < 0
                || callClose >= operand[1]) {
            throw unsupportedStructStarExclusion();
        }
        for (int index = 0; index < items.size(); index++) {
            if (index == targetItem) {
                continue;
            }
            int[] item = items.get(index);
            int first = nextSignificant(item[0], item[1]);
            int as = topLevelAs(item[0], item[1]);
            int alias = as >= 0 ? as : implicitSelectAlias(item[0], item[1]);
            int expressionEnd = alias >= 0 ? alias : item[1];
            if (!structStarAggregates(first, expressionEnd)) {
                // A non-aggregate item would be one of the grouping keys, which the star does not project.
                throw unsupportedStructStarExclusion();
            }
        }
        int groupEnd = end;
        for (int i = (group >= 0 ? group : tailStart) + 1; i < end; i++) {
            if (parenthesisDepth(i) != queryDepth || tokens.get(i).kind != Kind.IDENT || isPrecededByDot(i)) {
                continue;
            }
            String name = tokens.get(i).upper();
            if (group < 0 || !name.equals("BY")) {
                if (startsStructStarClause(i) && !name.equals("WHERE")) {
                    groupEnd = i;
                    break;
                }
            }
        }
        int fromEnd = group >= 0 ? group : groupEnd;
        int orderStart = -1;
        int limitStart = -1;
        for (int i = groupEnd; i < end; i++) {
            if (parenthesisDepth(i) != queryDepth || tokens.get(i).kind != Kind.IDENT || isPrecededByDot(i)) {
                continue;
            }
            String name = tokens.get(i).upper();
            if (name.equals("ORDER") && orderStart < 0) {
                orderStart = i;
            } else if ((name.equals("LIMIT") || name.equals("OFFSET")) && limitStart < 0) {
                limitStart = i;
            }
        }
        int havingStart = -1;
        for (int i = groupEnd; i < end; i++) {
            if (parenthesisDepth(i) == queryDepth && tokens.get(i).isKeyword("HAVING") && !isPrecededByDot(i)) {
                havingStart = i;
                break;
            }
        }
        int havingEnd = end;
        if (havingStart >= 0) {
            for (int i = havingStart + 1; i < end; i++) {
                if (parenthesisDepth(i) == queryDepth && tokens.get(i).kind == Kind.IDENT && !isPrecededByDot(i)
                        && STRUCT_STAR_CLAUSES.contains(tokens.get(i).upper())) {
                    havingEnd = i;
                    break;
                }
            }
        }
        // A HAVING that aggregates belongs with the aggregate; one that only filters the projected fields
        // is a WHERE over the expansion, where those fields exist.
        boolean havingOutside = havingStart >= 0 && !structStarAggregates(havingStart + 1, havingEnd);

        // HAVING, QUALIFY and WINDOW stay with the aggregate. An ordering that aggregates has to stay with
        // it as well, or it would sort a column the outer select no longer aggregates.
        boolean orderWithAggregate = false;
        if (orderStart >= 0) {
            int by = nextSignificant(orderStart + 1, limitStart >= 0 ? limitStart : end);
            int bodyStart = by < 0 ? -1 : nextSignificant(by + 1, limitStart >= 0 ? limitStart : end);
            if (bodyStart >= 0) {
                for (int[] range : topLevelRanges(bodyStart, limitStart >= 0 ? limitStart : end, queryDepth)) {
                    if (structStarAggregates(range[0], range[1])) {
                        orderWithAggregate = true;
                        break;
                    }
                }
            }
        }
        int innerClauseEnd = orderWithAggregate ? (limitStart >= 0 ? limitStart : end)
                : orderStart >= 0 ? orderStart : limitStart >= 0 ? limitStart : end;
        int innerTailEnd = havingOutside ? Math.min(innerClauseEnd, havingStart) : innerClauseEnd;

        StructStarSources sources = structStarSources(tailStart, end, queryDepth);
        List<String> outputNames = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            outputNames.add(index == targetItem ? null : outputNameText(items.get(index)[0], items.get(index)[1]));
        }
        Map<String, SourceReference> sourceReferences = new LinkedHashMap<>();
        Map<String, StructProjection> structProjections = new LinkedHashMap<>();
        List<String> excluded = new ArrayList<>();
        if (exceptOpen >= 0) {
            for (Token token : significant(exceptOpen + 1, exceptClose)) {
                if (token.kind == Kind.IDENT || token.kind == Kind.QIDENT) {
                    excluded.add(token.identifierText());
                }
            }
        }
        ProjectionSource starSource = projectionSourceForStar(targetStar);
        for (String hidden : presentStarHiddenColumns(targetStar)) {
            if (!hidden.equals("_TABLE_SUFFIX") || starSource != null && starSource.wildcardTable()) {
                excluded.add(hidden);
            }
        }
        // A sibling whose own name is an excluded struct field needs a temporary name, restored by RENAME
        // once the excluded fields are gone.
        Set<String> excludedNames = new HashSet<>();
        for (String name : excluded) {
            excludedNames.add(name.toLowerCase(Locale.ROOT));
        }
        reserveStructStarNames(select, end);
        structStarTakenNames.addAll(excludedNames);
        Set<String> aggregateFields = operandFields(select, callOpen + 1, callClose);
        if (aggregateFields != null) {
            structStarTakenNames.addAll(aggregateFields);
        }
        for (String name : outputNames) {
            if (name != null) {
                structStarTakenNames.add(name.toLowerCase(Locale.ROOT));
            }
        }
        List<String> innerNames = new ArrayList<>();
        List<String> renames = new ArrayList<>();
        Map<String, String> renamedSiblings = new LinkedHashMap<>();
        int aliasNumber = 0;
        for (int index = 0; index < items.size(); index++) {
            String name = outputNames.get(index);
            if (index == targetItem || name == null || !excludedNames.contains(name.toLowerCase(Locale.ROOT))) {
                innerNames.add(name);
                continue;
            }
            String temporary;
            do {
                temporary = "_floci_projection_" + aliasNumber++;
            } while (!structStarTakenNames.add(temporary.toLowerCase(Locale.ROOT)));
            innerNames.add(temporary);
            renames.add(DuckTypes.quoteIdentifier(temporary) + " AS " + DuckTypes.quoteIdentifier(name));
            renamedSiblings.put(name.toLowerCase(Locale.ROOT), temporary);
        }

        List<Token> rewritten = new ArrayList<>();
        String starAlias = DuckTypes.quoteIdentifier(structStarName("_floci_star"));
        rewritten.addAll(new Lexer("SELECT *").tokenize());
        if (!excluded.isEmpty()) {
            rewritten.add(Token.raw(" EXCLUDE (" + excluded.stream().distinct()
                    .map(DuckTypes::quoteIdentifier).collect(Collectors.joining(", ")) + ")"));
        }
        if (!renames.isEmpty()) {
            rewritten.add(Token.raw(" RENAME (" + String.join(", ", renames) + ")"));
        }
        rewritten.addAll(new Lexer(" FROM (SELECT").tokenize());
        for (int index = 0; index < items.size(); index++) {
            rewritten.add(Token.raw(index > 0 ? ", " : " "));
            if (index == targetItem) {
                rewritten.add(Token.raw("unnest(" + starAlias + ")"));
            } else {
                rewritten.add(Token.raw(DuckTypes.quoteIdentifier(innerNames.get(index))));
            }
        }
        rewritten.addAll(new Lexer(" FROM (SELECT").tokenize());
        for (int index = 0; index < items.size(); index++) {
            rewritten.add(Token.raw(index > 0 ? ", " : " "));
            if (index == targetItem) {
                rewritten.addAll(tokens.subList(targetStart, targetDot));
                rewritten.add(Token.raw(" AS " + starAlias));
                continue;
            }
            int[] item = items.get(index);
            int first = nextSignificant(item[0], item[1]);
            String innerName = innerNames.get(index);
            if (innerName.equals(outputNames.get(index))) {
                rewritten.addAll(withoutTrailingSpace(tokens.subList(first, item[1])));
                continue;
            }
            int as = topLevelAs(item[0], item[1]);
            int alias = as >= 0 ? as : implicitSelectAlias(item[0], item[1]);
            int expressionEnd = alias >= 0 ? alias : item[1];
            rewritten.addAll(withoutTrailingSpace(tokens.subList(first, expressionEnd)));
            rewritten.add(Token.raw(" AS " + DuckTypes.quoteIdentifier(innerName)));
        }
        rewritten.add(Token.raw(" "));
        rewritten.addAll(withoutTrailingSpace(tokens.subList(tailStart, fromEnd)));
        Set<String> starFields = operandFields(select, callOpen + 1, callClose);
        if (starFields == null) {
            starFields = Set.of();
        }
        Set<String> innerReserved = new HashSet<>();
        for (String name : innerNames) {
            if (name != null) {
                innerReserved.add(name.toLowerCase(Locale.ROOT));
            }
        }
        int innerTailFirstEnd = havingOutside ? havingStart : innerTailEnd;
        if (groupEnd < innerTailFirstEnd) {
            rewriteRenamedOutputReferences(groupEnd, innerTailFirstEnd, renamedSiblings);
            rewritten.add(Token.raw(" "));
            rewritten.addAll(withoutTrailingSpace(rewriteStarFieldReferences(
                    groupEnd, innerTailFirstEnd, starAlias, starFields, innerReserved)));
        }
        if (havingOutside && havingEnd < innerClauseEnd) {
            // The clauses after the moved filter, such as QUALIFY, still belong with the aggregate.
            rewriteRenamedOutputReferences(havingEnd, innerClauseEnd, renamedSiblings);
            rewritten.add(Token.raw(" "));
            rewritten.addAll(withoutLeadingSpace(withoutTrailingSpace(rewriteStarFieldReferences(
                    havingEnd, innerClauseEnd, starAlias, starFields, innerReserved))));
        }
        rewritten.addAll(new Lexer(") AS `_floci_group`) AS `_expanded`").tokenize());
        if (havingOutside) {
            // Outside the wrapper the columns carry their final names, so a renamed sibling needs no rewrite.
            rewritten.add(Token.raw(" WHERE "));
            rewritten.addAll(withoutLeadingSpace(withoutTrailingSpace(
                    tokens.subList(havingStart + 1, havingEnd))));
        }
        if (orderStart >= 0 && !orderWithAggregate) {
            rewritten.add(Token.raw(" "));
            rewritten.addAll(withoutTrailingSpace(tokens.subList(orderStart, limitStart >= 0 ? limitStart : end)));
        }
        if (limitStart >= 0) {
            rewritten.add(Token.raw(" "));
            rewritten.addAll(tokens.subList(limitStart, end));
        }
        tokens.subList(select, end).clear();
        tokens.addAll(select, rewritten);
        return true;
    }

    /**
     * BigQuery sorts a path such as {@code ORDER BY t.s.a} by the struct's field even when REPLACE changed
     * that output, which a plain field reference would miss. The original field is projected instead, as the
     * non-grouped rewrite does.
     */
    private int rewriteReplacedOrderPaths(int group, int end, int targetStart, int targetDot, int replaceOpen,
                                          int replaceClose, int queryDepth) {
        int orderStart = -1;
        for (int i = group; i < end; i++) {
            if (parenthesisDepth(i) == queryDepth && tokens.get(i).isKeyword("ORDER") && !isPrecededByDot(i)) {
                orderStart = i;
                break;
            }
        }
        if (orderStart < 0) {
            return end;
        }
        int by = nextSignificant(orderStart + 1, end);
        int bodyStart = by < 0 ? -1 : nextSignificant(by + 1, end);
        if (bodyStart < 0) {
            return end;
        }
        int bodyEnd = end;
        for (int i = bodyStart; i < end; i++) {
            if (parenthesisDepth(i) == queryDepth && !isPrecededByDot(i)
                    && (tokens.get(i).isKeyword("LIMIT") || tokens.get(i).isKeyword("OFFSET"))) {
                bodyEnd = i;
                break;
            }
        }
        Set<String> replacedNames = replaceTargets(replaceOpen, replaceClose);
        List<int[]> ranges = topLevelRanges(bodyStart, bodyEnd, queryDepth);
        int dropped = 0;
        for (int index = ranges.size() - 1; index >= 0; index--) {
            int[] range = ranges.get(index);
            int removed = rewriteReplacedGroupedOrderItem(range[0], range[1], targetStart, targetDot,
                    replacedNames);
            if (removed > 0) {
                dropped += removed;
            }
        }
        return end - dropped;
    }

    /**
     * BigQuery sorts a path such as ORDER BY t.s.a by the struct's field even when REPLACE changed that
     * output, so the ordering names the field of the expansion, which still holds the struct's own value.
     */
    private int rewriteReplacedGroupedOrderItem(int start, int end, int targetStart, int targetDot,
                                                Set<String> replacedNames) {
        int first = nextSignificant(start, end);
        if (first < 0) {
            return -1;
        }
        int matched = matchPath(first, end, significant(targetStart, targetDot));
        int dot = matched < 0 ? -1 : nextSignificant(matched, end);
        int field = dot < 0 ? -1 : nextSignificant(dot + 1, end);
        if (field < 0 || !tokens.get(dot).isPunct(".") || !isName(tokens.get(field))) {
            return -1;
        }
        for (Token token : significant(field + 1, end)) {
            if (!isOrderModifier(token)) {
                return -1;
            }
        }
        String name = tokens.get(field).identifierText();
        if (!replacedNames.contains(name.toLowerCase(Locale.ROOT))) {
            return -1;
        }
        tokens.subList(first, field).clear();
        tokens.set(first, Token.raw(DuckTypes.quoteIdentifier("_expanded") + "."
                + DuckTypes.quoteIdentifier(name)));
        return field - first;
    }

    /** True when the query has a top-level GROUP BY clause. */
    private boolean hasGroupBy(int tailStart, int end, int queryDepth) {
        for (int i = tailStart; i < end; i++) {
            if (parenthesisDepth(i) == queryDepth && tokens.get(i).isKeyword("GROUP") && !isPrecededByDot(i)) {
                return true;
            }
        }
        return false;
    }

    /** The GROUP BY keyword of a {@code GROUP BY ALL} clause, or -1 when the clause is something else. */
    private int groupByAllKeyword(int tailStart, int end, int queryDepth) {
        for (int i = tailStart; i < end; i++) {
            if (parenthesisDepth(i) != queryDepth || !tokens.get(i).isKeyword("GROUP") || isPrecededByDot(i)) {
                continue;
            }
            int by = nextSignificant(i + 1, end);
            if (by < 0 || !tokens.get(by).isKeyword("BY")) {
                return -1;
            }
            int clauseEnd = end;
            for (int k = by + 1; k < end; k++) {
                if (parenthesisDepth(k) == queryDepth && tokens.get(k).kind == Kind.IDENT && !isPrecededByDot(k)
                        && STRUCT_STAR_CLAUSES.contains(tokens.get(k).upper())) {
                    clauseEnd = k;
                    break;
                }
            }
            return ALL.equals(only(by + 1, clauseEnd)) ? i : -1;
        }
        return -1;
    }

    /** The token index of a top-level {@code QUALIFY} clause, or -1 when the query has none. */
    private int structStarQualifyStart(int start, int end, int queryDepth) {
        for (int i = start; i < end; i++) {
            if (parenthesisDepth(i) == queryDepth && tokens.get(i).isKeyword("QUALIFY") && !isPrecededByDot(i)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The select positions a {@code GROUP BY ALL} clause groups: every field the star still expands and every
     * sibling that is not an aggregate, in select order. Null when the star's fields are not known.
     */
    private String structStarGroupingPositions(List<int[]> items, int targetItem, Set<String> starFields,
                                               Set<String> excludedFields) {
        if (starFields == null) {
            return null;
        }
        int starFieldsCount = 0;
        for (String field : starFields) {
            if (!excludedFields.contains(field)) {
                starFieldsCount++;
            }
        }
        if (starFieldsCount == 0) {
            return null;
        }
        List<Integer> positions = new ArrayList<>();
        int position = 1;
        for (int index = 0; index < items.size(); index++) {
            int[] item = items.get(index);
            if (index == targetItem) {
                for (int field = 0; field < starFieldsCount; field++) {
                    positions.add(position + field);
                }
                position += starFieldsCount;
                continue;
            }
            int first = nextSignificant(item[0], item[1]);
            int as = topLevelAs(item[0], item[1]);
            int alias = as >= 0 ? as : implicitSelectAlias(item[0], item[1]);
            int expressionEnd = alias >= 0 ? alias : item[1];
            if (!structStarAggregates(first, expressionEnd) && !hasWindowExpression(item[0], expressionEnd)) {
                positions.add(position);
            }
            position++;
        }
        return positions.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    /**
     * Rewrites the star where the query groups by column positions or {@code ALL} by keeping only the
     * expansion inside the derived table: the exclusion, the aggregates and the trailing clauses move
     * outside, in the select order the query wrote them, so a position counts the projected fields exactly
     * as BigQuery counts them. Columns of the query's sources that the outer part still needs are projected
     * next to the expansion under a generated name.
     */
    private boolean rewriteStructStarByGroupingPositions(int select, int end, List<int[]> items, int targetItem,
                                                         int targetStar, int targetStart, int targetDot,
                                                         int exceptOpen, int exceptClose, int replaceOpen,
                                                         int replaceClose, int tailStart, int queryDepth) {
        int group = -1;
        for (int i = tailStart; i < end; i++) {
            if (parenthesisDepth(i) == queryDepth && tokens.get(i).isKeyword("GROUP") && !isPrecededByDot(i)) {
                group = i;
                break;
            }
        }
        if (group < 0) {
            throw unsupportedStructStarExclusion();
        }
        for (int i = tailStart; i < group; i++) {
            if (parenthesisDepth(i) == queryDepth && tokens.get(i).isKeyword("WINDOW") && !isPrecededByDot(i)) {
                throw unsupportedStructStarExclusion();
            }
        }
        if (structStarAggregates(targetStart, targetDot)) {
            // A positional GROUP BY cannot name the fields an aggregate star produces.
            throw unsupportedStructStarExclusion();
        }
        StructStarSources sources = structStarSources(tailStart, end, queryDepth);
        Map<String, SourceReference> sourceReferences = new LinkedHashMap<>();
        Map<String, StructProjection> structProjections = new LinkedHashMap<>();
        end = rewriteReplacedOrderPaths(group, end, targetStart, targetDot, replaceOpen, replaceClose,
                queryDepth);
        Set<String> aliasNames = new HashSet<>();
        List<String> outputNames = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            String name = index == targetItem ? null : outputNameText(items.get(index)[0], items.get(index)[1]);
            outputNames.add(name);
            if (name != null) {
                aliasNames.add(name.toLowerCase(Locale.ROOT));
            }
        }
        List<ItemColumn> itemColumns = List.of();
        reserveStructStarNames(select, end);
        Set<String> starFields = operandFields(select, targetStart, targetDot);
        if (starFields != null) {
            structStarTakenNames.addAll(starFields);
        }
        structStarTakenNames.addAll(aliasNames);
        Set<String> excludedFields = new HashSet<>();
        if (exceptOpen >= 0) {
            for (Token token : significant(exceptOpen + 1, exceptClose)) {
                if (token.kind == Kind.IDENT || token.kind == Kind.QIDENT) {
                    excludedFields.add(token.identifierText().toLowerCase(Locale.ROOT));
                }
            }
        }
        int groupAll = groupByAllKeyword(group, end, queryDepth);
        String groupingPositions = groupAll < 0 || structStarQualifyStart(group, end, queryDepth) < 0
                ? null : structStarGroupingPositions(items, targetItem, starFields, excludedFields);
        if (groupingPositions != null) {
            // DuckDB cannot combine QUALIFY with GROUP BY ALL; the explicit positions are the same grouping.
            int by = nextSignificant(groupAll + 1, end);
            int all = by < 0 ? -1 : nextSignificant(by + 1, end);
            if (all >= 0) {
                tokens.set(all, Token.raw(groupingPositions));
            }
        }
        List<String> expandedStarFields = new ArrayList<>();
        if (starFields != null) {
            for (String field : starFields) {
                if (!excludedFields.contains(field.toLowerCase(Locale.ROOT))) {
                    expandedStarFields.add(field);
                }
            }
        }
        Set<String> reservedOutputNames = new HashSet<>();
        for (int index = 0; index < items.size(); index++) {
            String name = outputNames.get(index);
            if (name != null) {
                reservedOutputNames.add(name.toLowerCase(Locale.ROOT));
            }
        }
        Map<Integer, String> renamedItems = new HashMap<>();
        Map<String, String> renamedStarFields = new LinkedHashMap<>();
        Set<String> claimedOutputNames = new HashSet<>();
        // A generated suffix must not take an explicit sibling alias or a field the star still expands.
        Set<String> unavailableOutputNames = new HashSet<>(reservedOutputNames);
        for (String field : expandedStarFields) {
            unavailableOutputNames.add(field.toLowerCase(Locale.ROOT));
        }
        for (int index = 0; index < items.size(); index++) {
            if (index == targetItem) {
                for (String field : expandedStarFields) {
                    if (!claimedOutputNames.add(field.toLowerCase(Locale.ROOT))) {
                        String renamed = suffixedName(field, unavailableOutputNames);
                        claimedOutputNames.add(renamed.toLowerCase(Locale.ROOT));
                        renamedStarFields.put(field, renamed);
                    }
                }
                continue;
            }
            String name = outputNames.get(index);
            if (name != null && !claimedOutputNames.add(name.toLowerCase(Locale.ROOT))) {
                String renamed = suffixedName(name, unavailableOutputNames);
                claimedOutputNames.add(renamed.toLowerCase(Locale.ROOT));
                renamedItems.put(index, renamed);
            }
        }
        List<String> writtenNames = new ArrayList<>();
        List<String> assignedNames = new ArrayList<>();
        boolean namedSiblings = true;
        for (int index = 0; index < items.size(); index++) {
            if (index == targetItem) {
                for (String field : expandedStarFields) {
                    writtenNames.add(field);
                    assignedNames.add(renamedStarFields.getOrDefault(field, field));
                }
                continue;
            }
            String name = outputNames.get(index);
            if (name == null) {
                namedSiblings = false;
                break;
            }
            writtenNames.add(name);
            assignedNames.add(renamedItems.getOrDefault(index, name));
        }
        if (namedSiblings && !assignedNames.equals(writtenNames)) {
            validateGeneratedOrderByNames(select, tailStart, end, queryDepth, writtenNames, assignedNames, sources);
        }
        int[] substitutions = new int[1];
        List<List<Token>> rewrittenItems = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            if (index == targetItem) {
                rewrittenItems.add(null);
                continue;
            }
            int[] item = items.get(index);
            int first = nextSignificant(item[0], item[1]);
            int as = topLevelAs(item[0], item[1]);
            int alias = as >= 0 ? as : implicitSelectAlias(item[0], item[1]);
            int expressionEnd = alias >= 0 ? alias : item[1];
            int before = substitutions[0];
            List<Token> expression = structStarFlatExpression(first, expressionEnd, targetStart, targetDot, sources,
                    Set.of(), itemColumns, false, sourceReferences, structProjections, substitutions);
            List<Token> rendered = new ArrayList<>(withoutTrailingSpace(expression));
            String renamed = renamedItems.get(index);
            if (renamed != null) {
                rendered.add(Token.raw(" AS " + DuckTypes.quoteIdentifier(renamed)));
            } else if (as >= 0) {
                rendered.add(Token.raw(" "));
                rendered.addAll(tokens.subList(as, item[1]));
            } else if (substitutions[0] > before) {
                String name = outputNameText(item[0], item[1]);
                if (name != null) {
                    rendered.add(Token.raw(" AS " + DuckTypes.quoteIdentifier(name)));
                }
            } else if (expressionEnd < item[1]) {
                rendered.add(Token.raw(" "));
                rendered.addAll(tokens.subList(expressionEnd, item[1]));
            }
            rewrittenItems.add(rendered);
        }
        List<Token> clauses = structStarFlatExpression(group, end, targetStart, targetDot, sources, aliasNames,
                itemColumns, true, sourceReferences, structProjections, substitutions);
        List<Token> replacement = List.of();
        if (replaceOpen >= 0) {
            replacement = structStarReplacement(replaceOpen, replaceClose, targetStart, targetDot, sources,
                    itemColumns, sourceReferences, structProjections);
        }

        List<String> excluded = new ArrayList<>();
        if (exceptOpen >= 0) {
            for (Token token : significant(exceptOpen + 1, exceptClose)) {
                if (token.kind == Kind.IDENT || token.kind == Kind.QIDENT) {
                    excluded.add(token.identifierText());
                }
            }
        }
        ProjectionSource starSource = projectionSourceForStar(targetStar);
        for (String hidden : presentStarHiddenColumns(targetStar)) {
            if (!hidden.equals("_TABLE_SUFFIX") || starSource != null && starSource.wildcardTable()) {
                excluded.add(hidden);
            }
        }
        excluded.addAll(generatedAliases(sourceReferences));
        for (StructProjection projection : structProjections.values()) {
            excluded.add(projection.alias());
        }

        List<Token> rewritten = new ArrayList<>();
        rewritten.addAll(new Lexer("SELECT").tokenize());
        rewritten.addAll(tokens.subList(select + 1, items.getFirst()[0]));
        for (int index = 0; index < items.size(); index++) {
            rewritten.add(Token.raw(index > 0 ? ", " : " "));
            if (index != targetItem) {
                rewritten.addAll(withoutTrailingSpace(rewrittenItems.get(index)));
                continue;
            }
            rewritten.add(Token.raw("*"));
            if (!excluded.isEmpty()) {
                rewritten.add(Token.raw(" EXCLUDE (" + excluded.stream().distinct()
                        .map(DuckTypes::quoteIdentifier).collect(Collectors.joining(", ")) + ")"));
            }
            if (replaceOpen >= 0) {
                rewritten.add(Token.raw(" REPLACE ("));
                rewritten.addAll(replacement);
                rewritten.add(Token.raw(")"));
            }
            if (!renamedStarFields.isEmpty()) {
                // DuckDB accepts RENAME after REPLACE, so the suffix lands on the right output without a
                // wrapper that would leave DuckDB's own duplicate numbering in charge of the names.
                rewritten.add(Token.raw(" RENAME ("));
                rewritten.add(Token.raw(renamedStarFields.entrySet().stream()
                        .map(entry -> DuckTypes.quoteIdentifier(entry.getKey()) + " AS "
                                + DuckTypes.quoteIdentifier(entry.getValue()))
                        .collect(Collectors.joining(", "))));
                rewritten.add(Token.raw(")"));
            }
        }
        // DuckDB cannot unnest a scalar subquery, so a query operand is materialized one level down.
        boolean queryOperand = isQueryOperand(targetStart, targetDot);
        String operandAlias = DuckTypes.quoteIdentifier(structStarName("_floci_operand"));
        if (queryOperand) {
            rewritten.add(Token.raw(" FROM (SELECT unnest(" + operandAlias + ")"));
            for (SourceReference reference : sourceReferences.values()) {
                rewritten.add(Token.raw(", " + DuckTypes.quoteIdentifier(reference.alias())));
            }
            for (StructProjection projection : structProjections.values()) {
                rewritten.add(Token.raw(", " + DuckTypes.quoteIdentifier(projection.alias())));
            }
            rewritten.addAll(new Lexer(" FROM (SELECT ").tokenize());
            rewritten.addAll(tokens.subList(targetStart, targetDot));
            rewritten.add(Token.raw(" AS " + operandAlias));
        } else {
            rewritten.addAll(new Lexer(" FROM (SELECT unnest(").tokenize());
            rewritten.addAll(tokens.subList(targetStart, targetDot));
            rewritten.addAll(new Lexer(")").tokenize());
        }
        for (SourceReference reference : sourceReferences.values()) {
            rewritten.add(Token.raw(", " + reference.path() + " AS "
                    + DuckTypes.quoteIdentifier(reference.alias())));
        }
        for (StructProjection projection : structProjections.values()) {
            rewritten.add(Token.raw(", "));
            rewritten.addAll(projection.tokens());
            rewritten.add(Token.raw(" AS " + DuckTypes.quoteIdentifier(projection.alias())));
        }
        rewritten.add(Token.raw(" "));
        rewritten.addAll(withoutTrailingSpace(tokens.subList(tailStart, group)));
        rewritten.addAll(new Lexer(queryOperand ? ") AS `_floci_operand_source`) AS `_expanded` "
                : ") AS `_expanded` ").tokenize());
        rewritten.addAll(clauses);
        tokens.subList(select, end).clear();
        tokens.addAll(select, rewritten);
        return true;
    }

    /**
     * Copies an expression for the flat structure: a field of the replaced STRUCT becomes the expanded
     * column, and a column of the query's own sources is projected next to the expansion and referenced by
     * that name, because the source itself is no longer in scope outside. A name that is one of the query's
     * output columns, an expression syntax word, or a call is left alone.
     */
    private List<Token> structStarFlatExpression(int start, int end, int targetStart, int targetDot,
                                                 StructStarSources sources, Set<String> aliasNames,
                                                 List<ItemColumn> itemColumns, boolean clauses,
                                                 Map<String, SourceReference> sourceReferences,
                                                 Map<String, StructProjection> structProjections,
                                                 int[] substitutions) {
        return structStarFlatExpression(start, end, targetStart, targetDot, sources, aliasNames, itemColumns,
                clauses, sourceReferences, structProjections, substitutions, false);
    }

    /**
     * Copies an expression for the flat structure. Inside a nested query only qualified references to the
     * enclosing query's sources are rewritten, because that query's own FROM decides its bare names.
     */
    private List<Token> structStarFlatExpression(int start, int end, int targetStart, int targetDot,
                                                 StructStarSources sources, Set<String> aliasNames,
                                                 List<ItemColumn> itemColumns, boolean clauses,
                                                 Map<String, SourceReference> sourceReferences,
                                                 Map<String, StructProjection> structProjections,
                                                 int[] substitutions, boolean nested) {
        List<Token> target = significant(targetStart, targetDot);
        List<Token> out = new ArrayList<>();
        boolean targetInScope = targetPathInScope(targetStart, targetDot, sources, nested);
        for (int i = start; i < end; i++) {
            int matched = targetInScope ? matchPath(i, end, target) : -1;
            if (matched >= 0) {
                int dot = nextSignificant(matched, end);
                int field = dot < 0 ? -1 : nextSignificant(dot + 1, end);
                if (dot >= 0 && tokens.get(dot).isPunct(".") && field >= 0 && isName(tokens.get(field))) {
                    out.add(Token.raw(DuckTypes.quoteIdentifier(tokens.get(field).identifierText())));
                    substitutions[0]++;
                    i = field;
                    continue;
                }
                out.add(Token.raw(DuckTypes.quoteIdentifier(
                        wholeStructReference(targetStart, targetDot, structProjections))));
                substitutions[0]++;
                i = matched - 1;
                continue;
            }
            Token token = tokens.get(i);
            if (token.isPunct("(")) {
                int nestedEnd = nestedQueryClose(i, end);
                if (nestedEnd >= 0) {
                    out.add(token);
                    out.addAll(structStarFlatExpression(i + 1, nestedEnd, targetStart, targetDot,
                            sources.without(nestedAliases(i + 1, nestedEnd)), aliasNames, itemColumns, clauses,
                            sourceReferences, structProjections, substitutions, true));
                    out.add(tokens.get(nestedEnd));
                    i = nestedEnd;
                    continue;
                }
            }
            int previous = significantBefore(i);
            boolean namesOutput = previous >= 0 && tokens.get(previous).isKeyword("AS");
            if (isName(token) && !isPrecededByDot(i) && !namesOutput) {
                String qualifier = token.identifierText();
                int dot = nextSignificant(i + 1, end);
                int column = dot < 0 ? -1 : nextSignificant(dot + 1, end);
                boolean qualified = dot >= 0 && tokens.get(dot).isPunct(".") && column >= 0
                        && isName(tokens.get(column))
                        && sources.aliases().contains(qualifier.toLowerCase(Locale.ROOT))
                        && !sources.rangeVariables().contains(qualifier.toLowerCase(Locale.ROOT));
                if (qualified) {
                    out.add(Token.raw(projectedPath(qualifier, tokens.get(column).identifierText(), column, end,
                            sources, itemColumns, sourceReferences)));
                    substitutions[0]++;
                    i = pathEnd(column, end);
                    continue;
                }
                int after = nextSignificant(i + 1, end);
                boolean call = after >= 0 && tokens.get(after).isPunct("(");
                if (!nested && !call && !inSyntaxRole(i, end)
                        && !aliasNames.contains(qualifier.toLowerCase(Locale.ROOT))) {
                    String reference = bareSourceReference(sources, qualifier, sourceReferences);
                    if (reference != null) {
                        PathTail tail = pathTail(after, end);
                        out.add(Token.raw(DuckTypes.quoteIdentifier(reference) + tail.text()));
                        substitutions[0]++;
                        i = tail.lastIndex() < 0 ? i : tail.lastIndex();
                        continue;
                    }
                    PathTail rowValue = clauses ? null : fromRowValue(i, end, sources, structProjections);
                    if (rowValue != null) {
                        out.add(Token.raw(rowValue.text()));
                        substitutions[0]++;
                        i = rowValue.lastIndex();
                        continue;
                    }
                    if (!clauses && sources.allKnown() && !STRUCT_STAR_SYNTAX_WORDS.contains(token.upper())) {
                        // A moved clause can still name a field the star expands, so only a select item
                        // is refused here.
                        throw invalidQuery("Unrecognized name: " + qualifier);
                    }
                }
            }
            out.add(token);
        }
        return out;
    }

    /**
     * The tail of a rewritten query, split into what stays inside the derived table and what follows it.
     * {@code removed} counts the tokens dropped while rewriting references, so the caller can find the end
     * of the query again.
     */
    private record RewrittenTail(List<Token> inside, List<Token> outside, int removed) {}

    private static List<Token> withoutLeadingSpace(List<Token> tokens) {
        int start = 0;
        while (start < tokens.size() && tokens.get(start).kind == Kind.SPACE) {
            start++;
        }
        return tokens.subList(start, tokens.size());
    }

    private static List<Token> withoutTrailingSpace(List<Token> tokens) {
        int end = tokens.size();
        while (end > 0 && tokens.get(end - 1).kind == Kind.SPACE) {
            end--;
        }
        return tokens.subList(0, end);
    }

    /**
     * Splits the clauses that follow a select list. {@code LIMIT} and {@code OFFSET} move after the
     * wrapper so they still apply to the filtered and de-duplicated rows, as in BigQuery. {@code ORDER BY}
     * moves with them when it counts output columns by position, and always under {@code DISTINCT} and next
     * to a {@code REPLACE}, where the output value differs from the one inside the wrapper.
     * References to a renamed output are pointed at its projection alias, because the excluded struct
     * field still holds the original name inside the wrapper.
     */
    private RewrittenTail rewriteStructStarClauses(int tailStart, int end, int queryDepth, boolean hoistOrder,
                                                   int targetStart, int targetDot, int replaceOpen, int replaceClose,
                                                   Map<String, String> renameTargets, StructStarSources sources,
                                                   List<ItemColumn> itemColumns,
                                                   Map<String, SourceReference> sourceReferences,
                                                   Map<String, StructProjection> structProjections,
                                                   Set<String> replacedNames, Set<String> starFields,
                                                   List<String> innerNames) {
        List<Integer> keywords = new ArrayList<>();
        for (int i = tailStart; i < end; i++) {
            Token token = tokens.get(i);
            int previous = significantBefore(i);
            boolean named = isPrecededByDot(i)
                    || previous >= 0 && (tokens.get(previous).isPunct(".") || tokens.get(previous).isKeyword("AS"));
            if (parenthesisDepth(i) == queryDepth && token.kind == Kind.IDENT && !named
                    && startsStructStarClause(i)) {
                keywords.add(i);
            }
        }
        int orderStart = -1;
        int orderBodyStart = -1;
        int orderEnd = -1;
        int limitStart = -1;
        int removedTotal = 0;
        for (int index = 0; index < keywords.size(); index++) {
            int keyword = keywords.get(index);
            int clauseEnd = index + 1 < keywords.size() ? keywords.get(index + 1) : end;
            String name = tokens.get(keyword).upper();
            if (name.equals("LIMIT") || name.equals("OFFSET")) {
                limitStart = limitStart < 0 ? keyword : limitStart;
                continue;
            }
            if (!Set.of("GROUP", "HAVING", "QUALIFY", "ORDER").contains(name)) {
                continue;
            }
            int bodyStart = keyword + 1;
            if (name.equals("GROUP") || name.equals("ORDER")) {
                int by = nextSignificant(keyword + 1, clauseEnd);
                if (by < 0 || !tokens.get(by).isKeyword("BY")) {
                    throw unsupportedStructStarExclusion();
                }
                bodyStart = by + 1;
            }
            if (name.equals("GROUP") && ALL.equals(only(bodyStart, clauseEnd))) {
                // GROUP BY ALL would group by the fields the wrapper filters out again.
                throw unsupportedStructStarExclusion();
            }
            rewriteRenamedOutputReferences(bodyStart, clauseEnd, renameTargets);
            if (name.equals("GROUP")) {
                for (int[] range : topLevelRanges(bodyStart, clauseEnd, queryDepth)) {
                    if (isColumnPosition(range[0], range[1])) {
                        // A position counts the expanded output columns, which the wrapper cannot number.
                        throw unsupportedStructStarExclusion();
                    }
                }
            }
            if (name.equals("ORDER")) {
                orderStart = keyword;
                orderBodyStart = bodyStart;
                orderEnd = clauseEnd;
            }
        }
        if (orderStart >= 0) {
            List<int[]> orderItems = topLevelRanges(orderBodyStart, orderEnd, queryDepth);
            boolean anyPositional = orderItems.stream()
                    .anyMatch(range -> isColumnPosition(range[0], range[1]));
            boolean allPositional = !orderItems.isEmpty() && orderItems.stream()
                    .allMatch(range -> isColumnPosition(range[0], range[1]));
            if (hoistOrder) {
                Set<String> sourceAliases = structStarFromAliases(tailStart, end, queryDepth);
                int removed = 0;
                for (int index = orderItems.size() - 1; index >= 0; index--) {
                    int[] range = orderItems.get(index);
                    if (isColumnPosition(range[0], range[1])) {
                        continue;
                    }
                    if (isBareReference(range[0], range[1])) {
                        int first = nextSignificant(range[0], range[1]);
                        String name = tokens.get(first).identifierText();
                        boolean output = innerNames.stream().anyMatch(inner -> inner != null
                                && inner.equalsIgnoreCase(name));
                        if (!output && starFields != null && !starFields.contains(name.toLowerCase(Locale.ROOT))) {
                            String reference = bareSourceReference(sources, name, sourceReferences);
                            if (reference != null) {
                                tokens.set(first, Token.raw(DuckTypes.quoteIdentifier(reference)));
                            }
                        }
                        continue;
                    }
                    int dropped = rewriteStructFieldOrderItem(range[0], range[1], targetStart, targetDot,
                            replacedNames, structProjections);
                    if (dropped < 0) {
                        OrderRewrite replacedPath = rewriteReplacedFieldPathOrderItem(range[0], range[1],
                                replaceOpen, replaceClose, targetStart, targetDot, sources, itemColumns,
                                sourceReferences, structProjections);
                        if (replacedPath != null) {
                            removed += replacedPath.removed();
                            continue;
                        }
                        dropped = rewriteQualifiedOrderItem(range[0], range[1], sourceAliases, sources, itemColumns,
                                sourceReferences);
                        if (dropped < 0) {
                            dropped = rewriteBareSourcePathOrderItem(range[0], range[1], sources, starFields,
                                    innerNames, sourceReferences);
                        }
                    }
                    if (dropped > 0) {
                        removed += dropped;
                    }
                }
                orderEnd -= removed;
                if (limitStart >= 0) {
                    limitStart -= removed;
                }
                removedTotal += removed;
                end -= removed;
            } else {
                if (anyPositional && !allPositional) {
                    throw unsupportedStructStarExclusion();
                }
                if (!allPositional) {
                    orderStart = -1;
                }
            }
        }
        int insideEnd = limitStart < 0 ? end : limitStart;
        List<Token> inside = new ArrayList<>();
        if (orderStart >= 0) {
            inside.addAll(tokens.subList(tailStart, orderStart));
            inside.addAll(tokens.subList(orderEnd, insideEnd));
        } else {
            inside.addAll(tokens.subList(tailStart, insideEnd));
        }
        List<Token> outside = new ArrayList<>();
        if (orderStart >= 0) {
            outside.addAll(tokens.subList(orderStart, orderEnd));
        }
        if (limitStart >= 0) {
            outside.addAll(tokens.subList(limitStart, end));
        }
        return new RewrittenTail(inside, outside, removedTotal);
    }

    private static final Set<String> STRUCT_STAR_CLAUSES = Set.of("WHERE", "GROUP", "HAVING", "QUALIFY",
            "WINDOW", "ORDER", "LIMIT", "OFFSET");

    /**
     * The prefix {@code rewriteArraySubscripts} writes for an indexed aggregate and the later pinned
     * aggregate-star validation and indexed-wildcard rewrites recognize. Keep the three uses in step.
     */
    private static final String INDEXED_AGGREGATE_PREFIX = "list_transform([struct_pack(a := ";

    /** True when the keyword at {@code index} opens a clause, not the {@code OFFSET} of {@code UNNEST ... WITH OFFSET}. */
    private boolean startsStructStarClause(int index) {
        Token token = tokens.get(index);
        if (!STRUCT_STAR_CLAUSES.contains(token.upper())) {
            return false;
        }
        int previous = significantBefore(index);
        return !token.isKeyword("OFFSET") || previous < 0 || !tokens.get(previous).isKeyword("WITH");
    }

    private static final String ALL = "ALL";

    /** The upper-cased text of the only significant token in a range, or null when there is not exactly one. */
    private String only(int start, int end) {
        List<Token> item = significant(start, end);
        return item.size() == 1 ? item.getFirst().upper() : null;
    }

    private List<int[]> topLevelRanges(int start, int end, int depth) {
        List<int[]> ranges = new ArrayList<>();
        int itemStart = start;
        for (int i = start; i < end; i++) {
            if (parenthesisDepth(i) == depth && tokens.get(i).isPunct(",")) {
                ranges.add(new int[] {itemStart, i});
                itemStart = i + 1;
            }
        }
        ranges.add(new int[] {itemStart, end});
        return ranges;
    }

    /** True for an {@code ORDER BY} item that counts output columns ({@code 1}, {@code 2 DESC}). */
    private boolean isColumnPosition(int start, int end) {
        List<Token> item = significant(start, end);
        if (item.isEmpty() || item.getFirst().kind != Kind.NUMBER || !item.getFirst().text.matches("\\d+")) {
            return false;
        }
        for (Token token : item.subList(1, item.size())) {
            if (!isOrderModifier(token)) {
                return false;
            }
        }
        return true;
    }

    /** True for an {@code ORDER BY} item that names one output column ({@code b}, {@code omitted DESC}). */
    private boolean isBareReference(int start, int end) {
        List<Token> item = significant(start, end);
        if (item.isEmpty() || (item.getFirst().kind != Kind.IDENT && item.getFirst().kind != Kind.QIDENT)) {
            return false;
        }
        for (Token token : item.subList(1, item.size())) {
            if (!isOrderModifier(token)) {
                return false;
            }
        }
        return true;
    }

    private Set<String> structStarFromAliases(int tailStart, int end, int queryDepth) {
        Set<String> aliases = new HashSet<>();
        for (int i = tailStart; i < end; i++) {
            if (parenthesisDepth(i) != queryDepth || !isFromClause(i)) {
                continue;
            }
            for (ProjectionSource source : projectionSources(i, queryDepth)) {
                if (source.alias() != null) {
                    aliases.add(source.alias().toLowerCase(Locale.ROOT));
                }
            }
            break;
        }
        return aliases;
    }

    /**
     * Rewrites {@code source.column} to the output column {@code column} so it still resolves once the clause
     * moves outside the wrapper, which only holds the projected columns. Returns the number of tokens removed,
     * or -1 for a shape it cannot map so the caller can refuse instead of emitting broken SQL.
     */
    private int rewriteQualifiedOrderItem(int start, int end, Set<String> sourceAliases, StructStarSources sources,
                                          List<ItemColumn> itemColumns,
                                          Map<String, SourceReference> sourceReferences) {
        int first = nextSignificant(start, end);
        int dot = first < 0 ? -1 : nextSignificant(first + 1, end);
        int column = dot < 0 ? -1 : nextSignificant(dot + 1, end);
        if (first < 0 || !isName(tokens.get(first)) || dot < 0 || !tokens.get(dot).isPunct(".")
                || column < 0 || !isName(tokens.get(column))
                || !sourceAliases.contains(tokens.get(first).identifierText().toLowerCase(Locale.ROOT))) {
            return -1;
        }
        int last = pathEnd(column, end);
        for (Token token : significant(last + 1, end)) {
            if (!isOrderModifier(token)) {
                return -1;
            }
        }
        String replacement = sourceColumnReference(tokens.get(first).identifierText(),
                tokens.get(column).identifierText(), sources, itemColumns, sourceReferences);
        Token columnToken = tokens.get(column);
        int firstToken = start;
        while (firstToken < end && tokens.get(firstToken).kind == Kind.SPACE) {
            firstToken++;
        }
        for (int i = firstToken; i < end; i++) {
            if (tokens.get(i) == columnToken) {
                tokens.subList(firstToken, i).clear();
                tokens.set(firstToken, Token.raw(DuckTypes.quoteIdentifier(replacement)));
                return i - firstToken;
            }
        }
        return -1;
    }

    /**
     * Rewrites {@code column.field...} for a bare source column, projecting the column next to the
     * expansion and keeping the nested path, so {@code ORDER BY u.n} still resolves outside the wrapper.
     * Returns 0 when the item was rewritten in place, or -1 for a shape it cannot map.
     */
    private int rewriteBareSourcePathOrderItem(int start, int end, StructStarSources sources,
                                               Set<String> starFields, List<String> innerNames,
                                               Map<String, SourceReference> sourceReferences) {
        int first = nextSignificant(start, end);
        if (first < 0 || !isName(tokens.get(first)) || isPrecededByDot(first)) {
            return -1;
        }
        int dot = nextSignificant(first + 1, end);
        int field = dot < 0 ? -1 : nextSignificant(dot + 1, end);
        if (dot < 0 || !tokens.get(dot).isPunct(".") || field < 0 || !isName(tokens.get(field))) {
            return -1;
        }
        int last = pathEnd(field, end);
        for (Token token : significant(last + 1, end)) {
            if (!isOrderModifier(token)) {
                return -1;
            }
        }
        String name = tokens.get(first).identifierText();
        boolean output = innerNames.stream().anyMatch(inner -> inner != null && inner.equalsIgnoreCase(name));
        if (output || starFields != null && starFields.contains(name.toLowerCase(Locale.ROOT))) {
            // The path names an expanded field or a sibling output, which the wrapper still exposes.
            return -1;
        }
        String reference = bareSourceReference(sources, name, sourceReferences);
        if (reference == null) {
            return -1;
        }
        tokens.set(first, Token.raw(DuckTypes.quoteIdentifier(reference)));
        return 0;
    }

    /**
     * Points a REPLACE expression at the wrapper's columns. Fields of the replaced struct read a separate
     * projection of the original struct. A column of the query's own sources becomes a projection next to
     * the expansion, because BigQuery evaluates the expression against the {@code FROM} row and the wrapper
     * no longer exposes it.
     * A name that no source of the query can supply is rejected the way BigQuery rejects it.
     */
    private List<Token> structStarReplacement(int open, int close, int targetStart, int targetDot,
                                              StructStarSources sources, List<ItemColumn> itemColumns,
                                              Map<String, SourceReference> sourceReferences,
                                              Map<String, StructProjection> structProjections) {
        return structStarReplacementRange(open + 1, close, targetStart, targetDot, sources, itemColumns,
                sourceReferences, structProjections, false);
    }

    /**
     * Copies a REPLACE expression. Inside a nested query only qualified references to the enclosing query's
     * sources are rewritten: its own FROM decides what its bare names mean.
     */
    private List<Token> structStarReplacementRange(int start, int close, int targetStart, int targetDot,
                                                   StructStarSources sources, List<ItemColumn> itemColumns,
                                                   Map<String, SourceReference> sourceReferences,
                                                   Map<String, StructProjection> structProjections, boolean nested) {
        List<Token> target = significant(targetStart, targetDot);
        List<Token> body = new ArrayList<>();
        boolean targetInScope = targetPathInScope(targetStart, targetDot, sources, nested);
        for (int i = start; i < close; i++) {
            int targetEnd = targetInScope ? matchPath(i, close, target) : -1;
            if (targetEnd >= 0) {
                int dot = nextSignificant(targetEnd, close);
                int field = dot < 0 ? -1 : nextSignificant(dot + 1, close);
                if (dot >= 0 && tokens.get(dot).isPunct(".") && field >= 0 && isName(tokens.get(field))) {
                    body.add(Token.raw(DuckTypes.quoteIdentifier(
                            wholeStructReference(targetStart, targetDot, structProjections)) + "."
                            + DuckTypes.quoteIdentifier(tokens.get(field).identifierText())));
                    i = field;
                    continue;
                }
                body.add(Token.raw(DuckTypes.quoteIdentifier(
                        wholeStructReference(targetStart, targetDot, structProjections))));
                i = targetEnd - 1;
                continue;
            }
            Token token = tokens.get(i);
            if (token.isPunct("(")) {
                int nestedEnd = nestedQueryClose(i, close);
                if (nestedEnd >= 0) {
                    body.add(token);
                    body.addAll(structStarReplacementRange(i + 1, nestedEnd, targetStart, targetDot,
                            sources.without(nestedAliases(i + 1, nestedEnd)), itemColumns, sourceReferences,
                            structProjections, true));
                    body.add(tokens.get(nestedEnd));
                    i = nestedEnd;
                    continue;
                }
            }
            int previous = significantBefore(i);
            boolean namesReplacement = previous >= 0 && tokens.get(previous).isKeyword("AS");
            if (isName(token) && !isPrecededByDot(i) && !namesReplacement) {
                String qualifier = token.identifierText();
                int dot = nextSignificant(i + 1, close);
                int column = dot < 0 ? -1 : nextSignificant(dot + 1, close);
                boolean qualified = dot >= 0 && tokens.get(dot).isPunct(".") && column >= 0
                        && isName(tokens.get(column))
                        && sources.aliases().contains(qualifier.toLowerCase(Locale.ROOT))
                        && !sources.rangeVariables().contains(qualifier.toLowerCase(Locale.ROOT));
                if (qualified) {
                    body.add(Token.raw(projectedPath(qualifier, tokens.get(column).identifierText(), column, close,
                            sources, itemColumns, sourceReferences)));
                    i = pathEnd(column, close);
                    continue;
                }
                int after = nextSignificant(i + 1, close);
                boolean call = after >= 0 && tokens.get(after).isPunct("(");
                if (!nested && !call && !inSyntaxRole(i, close)) {
                    String reference = bareSourceReference(sources, qualifier, sourceReferences);
                    if (reference != null) {
                        PathTail tail = pathTail(after, close);
                        body.add(Token.raw(DuckTypes.quoteIdentifier(reference) + tail.text()));
                        i = tail.lastIndex() < 0 ? i : tail.lastIndex();
                        continue;
                    }
                    PathTail rowValue = fromRowValue(i, close, sources, structProjections);
                    if (rowValue != null) {
                        body.add(Token.raw(rowValue.text()));
                        i = rowValue.lastIndex();
                        continue;
                    }
                    if (sources.allKnown() && !STRUCT_STAR_SYNTAX_WORDS.contains(token.upper())) {
                        throw invalidQuery("Unrecognized name: " + qualifier);
                    }
                }
            }
            body.add(token);
        }
        return body;
    }

    /**
     * Projects a bare name that the {@code FROM} row supplies but no known source column names: an
     * {@code UNNEST} range variable or its offset, a source alias read as a whole row, or a column of a source
     * whose columns are unknown, such as a subquery without an alias. BigQuery reads it from the row, so it is
     * evaluated next to the expansion, where a same-named field of the struct cannot shadow it. Returns the
     * projection with any {@code .field} path, or null when the name is not such a value.
     */
    private PathTail fromRowValue(int index, int end, StructStarSources sources,
                                  Map<String, StructProjection> projections) {
        Token token = tokens.get(index);
        boolean sourceAlias = sources.aliases().contains(token.identifierText().toLowerCase(Locale.ROOT));
        if (!sourceAlias && (sources.allKnown()
                || token.kind != Kind.QIDENT && STRUCT_STAR_SYNTAX_WORDS.contains(token.upper()))) {
            return null;
        }
        PathTail tail = pathTail(nextSignificant(index + 1, end), end);
        int last = tail.lastIndex() < 0 ? index : tail.lastIndex();
        int after = nextSignificant(last + 1, end);
        if (after >= 0 && tokens.get(after).isPunct("(")) {
            // A dotted call such as SAFE.PARSE_DATE(...) names a function, not a value.
            return null;
        }
        String alias = projectionFor(List.of(token), projections);
        return new PathTail(DuckTypes.quoteIdentifier(alias) + tail.text(), last);
    }

    /** A {@code .field...} path that follows a name, or an empty text with no index. */
    private record PathTail(String text, int lastIndex) {}

    /** The {@code .field} path that starts at {@code dot}, as written, and its last token. */
    private PathTail pathTail(int dot, int end) {
        if (dot < 0 || !tokens.get(dot).isPunct(".")) {
            return new PathTail("", -1);
        }
        StringBuilder text = new StringBuilder();
        int field = nextSignificant(dot + 1, end);
        int last = -1;
        while (field >= 0 && isName(tokens.get(field))) {
            text.append('.').append(DuckTypes.quoteIdentifier(tokens.get(field).identifierText()));
            last = field;
            int nextDot = nextSignificant(field + 1, end);
            if (nextDot < 0 || !tokens.get(nextDot).isPunct(".")) {
                break;
            }
            field = nextSignificant(nextDot + 1, end);
        }
        return new PathTail(text.toString(), last);
    }

    /**
     * True when a name in a REPLACE expression is syntax rather than a column: the unit of an
     * {@code INTERVAL 1 DAY}, or a date part such as {@code EXTRACT(DAY FROM d)} or
     * {@code DATE_TRUNC(d, MONTH)}.
     */
    private boolean inSyntaxRole(int index, int end) {
        Token token = tokens.get(index);
        if (token.kind == Kind.QIDENT || !STRUCT_STAR_SYNTAX_WORDS.contains(token.upper())) {
            return false;
        }
        if (isIntervalPartSyntax(index) || isDatePartSyntax(index, end)) {
            return true;
        }
        int previous = significantBefore(index);
        if (previous < 0) {
            return false;
        }
        Token before = tokens.get(previous);
        if (before.kind == Kind.NUMBER || before.kind == Kind.STRING
                || before.isKeyword("INTERVAL") || before.isKeyword("TO")) {
            return true;
        }
        return false;
    }

    /** The fields of the struct an aggregate operand refers to, such as {@code t.s} in {@code ANY_VALUE(t.s)}. */
    private Set<String> operandFields(int select, int start, int end) {
        Set<String> literal = structLiteralFields(start, end);
        if (literal != null) {
            return literal;
        }
        List<Token> tokensInOperand = significant(start, end);
        for (int i = tokensInOperand.size() - 1; i >= 0; i--) {
            Token token = tokensInOperand.get(i);
            if (!isName(token)) {
                continue;
            }
            int first = i;
            while (first >= 2 && tokensInOperand.get(first - 1).isPunct(".")
                    && isName(tokensInOperand.get(first - 2))) {
                first -= 2;
            }
            List<String> path = new ArrayList<>();
            for (int part = first; part <= i; part += 2) {
                path.addAll(pathSegments(tokensInOperand.get(part)));
            }
            Set<String> fields = columnFields(select, path);
            if (fields != null) {
                return fields;
            }
            i = first;
        }
        return null;
    }

    /**
     * The bare name at {@code index} when it can only name a field of the aggregate star, or null. When the
     * fields are known they decide; a stored table has no readable struct literal, so there the name has to
     * look like a field: not a keyword, an alias, a call, a path or the unit of an interval.
     */
    private String bareStarField(int index, int end, Set<String> fields, Set<String> reserved) {
        Token token = tokens.get(index);
        if ((token.kind != Kind.IDENT && token.kind != Kind.QIDENT) || isPrecededByDot(index)) {
            return null;
        }
        String name = token.identifierText().toLowerCase(Locale.ROOT);
        int previous = significantBefore(index);
        if (reserved.contains(name) || previous >= 0 && tokens.get(previous).isKeyword("AS")) {
            return null;
        }
        int next = nextSignificant(index + 1, end);
        if (next >= 0 && (tokens.get(next).isPunct("(") || tokens.get(next).isPunct(".")
                || tokens.get(next).kind == Kind.STRING || tokens.get(next).kind == Kind.NUMBER)) {
            return null;
        }
        if (!fields.isEmpty()) {
            return fields.contains(name) ? name : null;
        }
        return bareOuterName(index, end, false) ? name : null;
    }

    /** True when the bare name at {@code index} can only be a column of the query's sources. */
    private boolean bareOuterName(int index, int end, boolean insideAggregateNames) {
        Token token = tokens.get(index);
        if ((token.kind != Kind.IDENT && token.kind != Kind.QIDENT) || isPrecededByDot(index)) {
            return false;
        }
        int previous = significantBefore(index);
        if (previous >= 0 && tokens.get(previous).isKeyword("AS")) {
            return false;
        }
        int next = nextSignificant(index + 1, end);
        if (next >= 0 && (tokens.get(next).isPunct("(") || tokens.get(next).isPunct(".")
                || tokens.get(next).kind == Kind.STRING || tokens.get(next).kind == Kind.NUMBER)) {
            return false;
        }
        String upper = token.upper();
        if (GOOGLESQL_RESERVED.contains(upper) || DATE_PART_NAMES.contains(upper)
                || STRUCT_STAR_CLAUSES.contains(upper) || STRUCT_STAR_SYNTAX_WORDS.contains(upper)
                || WINDOW_TAIL_WORDS.contains(upper) || STRUCT_STAR_WRAPPER_TYPES.contains(upper)
                || PARENLESS_FUNCTIONS.contains(upper) || inSyntaxRole(index, end)) {
            return false;
        }
        return insideAggregateNames || !insideAggregateArgument(index);
    }

    /**
     * True when the token sits in the arguments of a plain aggregate, where names are the aggregate's input
     * columns rather than fields of a projected struct. The window after {@code OVER} is the exception: it
     * reads the projected fields.
     */
    private boolean insideAggregateArgument(int index) {
        int depth = 0;
        for (int i = index - 1; i >= 0; i--) {
            Token token = tokens.get(i);
            if (token.isPunct(")")) {
                depth++;
                continue;
            }
            if (!token.isPunct("(")) {
                continue;
            }
            if (depth > 0) {
                depth--;
                continue;
            }
            int call = significantBefore(i);
            if (call < 0 || tokens.get(call).kind != Kind.IDENT) {
                return false;
            }
            String name = tokens.get(call).upper();
            if (name.equals("OVER")) {
                return false;
            }
            int close = matchingParen(i);
            int after = close < 0 ? -1 : nextSignificant(close + 1, tokens.size());
            return STRUCT_STAR_AGGREGATES.contains(name)
                    && (after < 0 || !tokens.get(after).isKeyword("OVER"));
        }
        return false;
    }

    /**
     * Rewrites a bare name that names a field of the aggregate star to that field of the struct, which is
     * where the fields live inside the aggregate wrapper. A clause such as {@code QUALIFY ROW_NUMBER() OVER
     * (PARTITION BY b) = 1} or an aggregate {@code HAVING} can read them that way.
     */
    private List<Token> rewriteStarFieldReferences(int start, int end, String starAlias, Set<String> fields,
                                                   Set<String> reserved) {
        List<Token> out = new ArrayList<>();
        for (int i = start; i < end; i++) {
            Token token = tokens.get(i);
            if (token.isPunct("(")) {
                int nestedEnd = nestedQueryClose(i, end);
                if (nestedEnd >= 0) {
                    // A nested query decides what its own names mean, including the names it declares.
                    out.addAll(tokens.subList(i, nestedEnd + 1));
                    i = nestedEnd;
                    continue;
                }
            }
            if (bareStarField(i, end, fields, reserved) == null) {
                out.add(token);
                continue;
            }
            // A select-list alias cannot be read as a struct path, so extract the field instead.
            // The struct column itself is not a grouping key, so read the field off the aggregate's alias.
            out.add(Token.raw("struct_extract(" + starAlias + ", '"
                    + token.identifierText().replace("'", "''") + "')"));
        }
        return out;
    }

    /** The index of the last token in the {@code .field} path that starts at {@code column}. */
    private int pathEnd(int column, int end) {
        int position = column;
        while (true) {
            int dot = nextSignificant(position + 1, end);
            int next = dot < 0 ? -1 : nextSignificant(dot + 1, end);
            if (dot < 0 || !tokens.get(dot).isPunct(".") || next < 0 || !isName(tokens.get(next))) {
                return position;
            }
            position = next;
        }
    }

    /**
     * Resolves {@code alias.column} to a projected column of that source, keeping any nested field path:
     * {@code t.other.b} projects {@code t.other} and then reads its {@code b} field.
     */
    private String projectedPath(String qualifier, String column, int columnIndex, int end,
                                 StructStarSources sources, List<ItemColumn> itemColumns,
                                 Map<String, SourceReference> sourceReferences) {
        String reference = sourceColumnReference(qualifier, column, sources, itemColumns, sourceReferences);
        StringBuilder path = new StringBuilder(DuckTypes.quoteIdentifier(reference));
        int position = columnIndex;
        while (true) {
            int dot = nextSignificant(position + 1, end);
            int next = dot < 0 ? -1 : nextSignificant(dot + 1, end);
            if (dot < 0 || !tokens.get(dot).isPunct(".") || next < 0 || !isName(tokens.get(next))) {
                return path.toString();
            }
            path.append('.').append(DuckTypes.quoteIdentifier(tokens.get(next).identifierText()));
            position = next;
        }
    }

    /** Resolves {@code alias.column} inside a REPLACE expression to a projected column of that source. */
    private String sourceColumnReference(String qualifier, String column, StructStarSources sources,
                                         List<ItemColumn> itemColumns,
                                         Map<String, SourceReference> sourceReferences) {
        String selected = selectedColumn(itemColumns, qualifier, column);
        Set<String> columns = sources.columnsByAlias().get(qualifier.toLowerCase(Locale.ROOT));
        if (columns == null || columns.contains(column.toLowerCase(Locale.ROOT))) {
            return selected != null ? selected : sourceReference(qualifier, column, sourceReferences);
        }
        if (sources.allKnown()) {
            throw invalidQuery("Unrecognized name: " + qualifier + "." + column);
        }
        return sourceReference(qualifier, column, sourceReferences);
    }

    /**
     * The columns each source of a query exposes. BigQuery resolves a REPLACE expression against the
     * {@code FROM} row, so those names decide what the wrapper has to project and what it must reject.
     */
    private record StructStarSources(Map<String, Set<String>> columnsByAlias, Set<String> aliases,
                                     Set<String> rangeVariables, Map<String, List<String>> usingSources,
                                     boolean allKnown) {

        /** The same view without the aliases a nested query declares itself, which shadow the outer ones. */
        StructStarSources without(Set<String> shadowed) {
            if (shadowed.isEmpty()) {
                return this;
            }
            Map<String, Set<String>> columns = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> entry : columnsByAlias.entrySet()) {
                if (!shadowed.contains(entry.getKey())) {
                    columns.put(entry.getKey(), entry.getValue());
                }
            }
            Set<String> remaining = new HashSet<>(aliases);
            remaining.removeAll(shadowed);
            Set<String> remainingRanges = new HashSet<>(rangeVariables);
            remainingRanges.removeAll(shadowed);
            return new StructStarSources(columns, remaining, remainingRanges, usingSources, allKnown);
        }
    }

    /**
     * True when the star's own path still names the outer struct at this point. Inside a nested query that
     * declares the same alias, or when the star is a bare column, the path belongs to that query's scope.
     */
    private boolean targetPathInScope(int targetStart, int targetDot, StructStarSources sources, boolean nested) {
        if (!nested) {
            return true;
        }
        List<Token> path = significant(targetStart, targetDot);
        if (path.size() < 3 || !isName(path.getFirst()) || !path.get(1).isPunct(".")) {
            return false;
        }
        return sources.aliases().contains(path.getFirst().identifierText().toLowerCase(Locale.ROOT));
    }

    /** The whole STRUCT value of the star's operand, projected next to the expansion for an outer expression. */
    private record StructProjection(String alias, List<Token> tokens) {}

    /** A select item whose expression is exactly one source column, and the name it gives that column. */
    private record ItemColumn(String path, String name) {}

    /** A source column projected next to the expansion, so an outer clause can still read it. */
    private record SourceReference(String qualifier, String column, String alias, String expression) {
        SourceReference(String qualifier, String column, String alias) {
            this(qualifier, column, alias, null);
        }

        String path() {
            return expression != null ? expression
                    : DuckTypes.quoteIdentifier(qualifier) + "." + DuckTypes.quoteIdentifier(column);
        }
    }

    private static final Set<String> STRUCT_STAR_SYNTAX_WORDS = structStarSyntaxWords();
    private static final Set<String> WINDOW_TAIL_WORDS = Set.of("FIRST", "LAST", "ROW", "RANK", "DENSE_RANK",
            "PERCENT_RANK", "CUME_DIST", "NTILE", "LAG", "LEAD");
    private static final Set<String> STRUCT_STAR_WRAPPER_TYPES = Set.of("DATE", "DATETIME", "TIME", "TIMESTAMP",
            "NUMERIC", "BIGNUMERIC", "BIGDECIMAL", "DECIMAL", "JSON", "BYTES", "BOOL", "BOOLEAN", "INT64",
            "FLOAT64", "STRING", "GEOGRAPHY", "RANGE");

    private static Set<String> structStarSyntaxWords() {
        Set<String> words = new HashSet<>(GOOGLESQL_RESERVED);
        words.addAll(Set.of("MICROSECOND", "MILLISECOND", "SECOND", "MINUTE", "HOUR", "DAY", "WEEK", "MONTH",
                "QUARTER", "YEAR", "MICROSECONDS", "MILLISECONDS", "SECONDS", "MINUTES", "HOURS", "DAYS", "WEEKS",
                "MONTHS", "QUARTERS", "YEARS", "DAYOFWEEK", "DAYOFYEAR", "WEEKDAY", "ISOWEEK", "ISOYEAR",
                "INT64", "FLOAT64", "STRING", "BYTES", "BOOL", "BOOLEAN", "NUMERIC", "BIGNUMERIC", "DATE",
                "DATETIME", "TIME", "TIMESTAMP", "JSON", "GEOGRAPHY", "RECORD", "NULL", "TRUE", "FALSE"));
        return Set.copyOf(words);
    }

    private StructStarSources structStarSources(int tailStart, int end, int queryDepth) {
        int from = -1;
        for (int i = tailStart; i < end; i++) {
            if (parenthesisDepth(i) == queryDepth && isFromClause(i)) {
                from = i;
                break;
            }
        }
        if (from < 0) {
            return new StructStarSources(Map.of(), Set.of(), Set.of(), Map.of(), false);
        }
        List<ProjectionSource> sourceList = projectionSources(from, queryDepth);
        Map<String, Set<String>> columnsByAlias = new LinkedHashMap<>();
        Set<String> aliases = new HashSet<>();
        Set<String> rangeVariables = new HashSet<>();
        boolean allKnown = true;
        for (ProjectionSource source : sourceList) {
            if (source.alias() == null) {
                // A source without an alias cannot be named, so its columns stay out of reach.
                allKnown = false;
                continue;
            }
            String alias = source.alias().toLowerCase(Locale.ROOT);
            aliases.add(alias);
            if (tokens.get(source.startToken()).isKeyword("UNNEST")) {
                rangeVariables.add(alias);
            }
            Set<String> columns = sourceColumns(source);
            if (columns == null) {
                allKnown = false;
                continue;
            }
            columnsByAlias.put(alias, columns);
        }
        Map<String, List<String>> usingSources = new LinkedHashMap<>();
        for (int index = 1; index < sourceList.size(); index++) {
            ProjectionSource joined = sourceList.get(index);
            for (String column : usingColumnsAfterSource(joined, queryDepth)) {
                String key = column.toLowerCase(Locale.ROOT);
                List<String> participants = usingSources.computeIfAbsent(key, name -> new ArrayList<>());
                if (participants.isEmpty()) {
                    for (int prior = 0; prior < index; prior++) {
                        ProjectionSource source = sourceList.get(prior);
                        if (source.alias() != null && sourceExposesColumn(source, column)) {
                            participants.add(source.alias().toLowerCase(Locale.ROOT));
                        }
                    }
                }
                if (joined.alias() != null) {
                    String alias = joined.alias().toLowerCase(Locale.ROOT);
                    if (!participants.contains(alias)) {
                        participants.add(alias);
                    }
                }
            }
        }
        return new StructStarSources(columnsByAlias, aliases, rangeVariables, usingSources, allKnown);
    }

    private static final Set<String> STRUCT_STAR_AGGREGATES = Set.of("COUNT", "COUNTIF", "SUM", "AVG", "MIN", "MAX",
            "ANY_VALUE", "ARRAY_AGG", "STRING_AGG", "LOGICAL_AND", "LOGICAL_OR", "BIT_AND", "BIT_OR", "BIT_XOR",
            "APPROX_COUNT_DISTINCT", "APPROX_QUANTILES", "APPROX_TOP_COUNT", "APPROX_TOP_SUM", "STDDEV",
            "STDDEV_POP", "STDDEV_SAMP", "VAR_POP", "VAR_SAMP", "VARIANCE", "CORR", "COVAR_POP", "COVAR_SAMP");

    /**
     * Rejects a star over an aggregate that cannot produce a STRUCT. BigQuery errors before expansion, and
     * letting DuckDB do it would return rows for {@code MAX(t.s).*} instead.
     */
    private void validateAggregateStarOperand(int select, int start, int end) {
        int firstToken = nextNonSpace(start, end);
        int afterOpen = firstToken >= 0 && tokens.get(firstToken).isPunct("(")
                ? nextNonSpace(firstToken + 1, end) : -1;
        if (afterOpen >= 0 && tokens.get(afterOpen).kind == Kind.RAW
                && tokens.get(afterOpen).text.contains(INDEXED_AGGREGATE_PREFIX)) {
            // An indexed aggregate has already been rewritten to an array subscript, which yields one
            // element, so the dot-star applies to that element rather than to the aggregate's ARRAY.
            return;
        }
        int[] operand = unwrappedOperand(start, end);
        int first = nextSignificant(operand[0], operand[1]);
        if (first < 0) {
            return;
        }
        Token token = tokens.get(first);
        if (token.kind != Kind.IDENT || !STRUCT_STAR_AGGREGATES.contains(token.upper())
                || token.isKeyword("ANY_VALUE")) {
            return;
        }
        int open = nextSignificant(first + 1, operand[1]);
        if (open < 0 || !tokens.get(open).isPunct("(")) {
            return;
        }
        int close = matchingParen(open);
        int afterCall = close < 0 ? -1 : nextNonSpace(close + 1, operand[1]);
        String name = token.upper();
        if (afterCall >= 0 && (tokens.get(afterCall).isPunct("[") || tokens.get(afterCall).kind == Kind.RAW)
                && (name.equals("ARRAY_AGG") || name.equals("ARRAY_CONCAT_AGG"))) {
            // An indexed array aggregate yields one element, so the dot-star applies to that element,
            // not to the aggregate's ARRAY result.
            return;
        }
        if (name.equals("COUNT") || name.equals("COUNTIF")) {
            throw invalidQuery("Dot-star is not supported for type INT64");
        }
        if (name.equals("ARRAY_AGG") || name.equals("ARRAY_CONCAT_AGG")) {
            throw invalidQuery("Dot-star is not supported for type ARRAY");
        }
        Set<String> fields = operandFields(select, open + 1, close);
        if (fields != null) {
            throw invalidQuery(name + " is not defined for arguments of type STRUCT");
        }
        throw invalidQuery("Dot-star is not supported for the aggregate expression");
    }

    /** The operand without parentheses that wrap all of it: {@code (ANY_VALUE(t.s))} is {@code ANY_VALUE(t.s)}. */
    private int[] unwrappedOperand(int start, int end) {
        int first = nextSignificant(start, end);
        int last = previousSignificant(end - 1);
        while (first >= 0 && last >= 0 && tokens.get(first).isPunct("(")) {
            int close = matchingParen(first);
            if (close < 0 || previousSignificant(end - 1) != close) {
                break;
            }
            int next = nextSignificant(first + 1, close);
            int innerLast = previousSignificant(close - 1);
            if (next < 0 || innerLast < 0) {
                break;
            }
            first = next;
            last = innerLast;
            end = close;
        }
        return new int[] {first, previousSignificant(last) + 1};
    }

    /** True when the star's operand is itself a parenthesized query. */
    private boolean isQueryOperand(int start, int end) {
        int first = nextSignificant(start, end);
        return first >= 0 && tokens.get(first).isPunct("(") && nestedQueryClose(first, end) >= 0;
    }

    /** True when every select item other than the star is an aggregate call. */
    private boolean allSiblingsAggregate(List<int[]> items, int targetItem) {
        for (int index = 0; index < items.size(); index++) {
            if (index == targetItem) {
                continue;
            }
            int[] item = items.get(index);
            int first = nextSignificant(item[0], item[1]);
            int as = topLevelAs(item[0], item[1]);
            int alias = as >= 0 ? as : implicitSelectAlias(item[0], item[1]);
            int expressionEnd = alias >= 0 ? alias : item[1];
            if (!structStarAggregates(first, expressionEnd)) {
                return false;
            }
        }
        return true;
    }

    /** True when the star's own expression is an aggregate, which the flat structure cannot keep in step. */
    private boolean structStarAggregates(int start, int end) {
        for (int i = start; i < end; i++) {
            Token token = tokens.get(i);
            if (token.kind != Kind.IDENT || !STRUCT_STAR_AGGREGATES.contains(token.upper())
                    || insideNestedQuery(i, start)) {
                // An aggregate inside a nested query belongs to that query, not to this one.
                continue;
            }
            int next = nextSignificant(i + 1, end);
            if (next >= 0 && tokens.get(next).isPunct("(")) {
                // A call, not an alias: `sum.s.*` reads a source named `sum`, it does not aggregate. A call
                // followed by OVER is a window function, which computes per row rather than per group.
                int close = matchingParen(next);
                int after = close < 0 ? -1 : nextSignificant(close + 1, end);
                if (after < 0 || !tokens.get(after).isKeyword("OVER")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True when a select item is a window expression, which GROUP BY ALL must not group by. */
    private boolean hasWindowExpression(int start, int end) {
        boolean[] nested = nestedQueryPositions(start, end);
        for (int i = start; i < end; i++) {
            if (!nested[i] && tokens.get(i).isKeyword("OVER")) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code index} sits inside a parenthesized query rather than the one at {@code select}. */
    private boolean insideNestedQuery(int index, int select) {
        int position = index;
        while (true) {
            int open = enclosingParenthesis(position);
            if (open < 0 || open < select) {
                return false;
            }
            int next = nextNonSpace(open + 1, tokens.size());
            if (next >= 0 && startsQuery(tokens.get(next))) {
                return true;
            }
            position = open;
        }
    }

    /** The aliases the query starting at {@code start} declares, which hide the enclosing ones. */
    private Set<String> nestedAliases(int start, int end) {
        int depth = parenthesisDepth(start);
        int select = -1;
        for (int i = start; i < end; i++) {
            if (parenthesisDepth(i) == depth && tokens.get(i).isKeyword("SELECT")) {
                select = i;
                break;
            }
        }
        if (select < 0) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        for (int i = select + 1; i < end; i++) {
            if (parenthesisDepth(i) == depth && isFromClause(i)) {
                for (ProjectionSource source : projectionSources(i, depth)) {
                    if (source.alias() != null) {
                        names.add(source.alias().toLowerCase(Locale.ROOT));
                    }
                }
                break;
            }
        }
        return names;
    }

    /** The columns a source exposes, or null when they cannot be determined. */
    private Set<String> sourceColumns(ProjectionSource source) {
        if (source.directTable()) {
            TableRef ref = projectionSourceTable(source.startToken());
            Set<String> columns = ref == null ? null : columnsOf.apply(ref);
            return columns == null ? null : lowercasedNames(columns);
        }
        Token name = tokens.get(source.startToken());
        if (isName(name)) {
            Set<String> cte = cteColumns(name.identifierText(), source.startToken());
            if (cte != null) {
                return cte;
            }
        }
        int inner = nextSignificant(source.startToken() + 1, tokens.size());
        if (inner < 0 || !tokens.get(inner).isKeyword("SELECT")) {
            return null;
        }
        return selectListColumns(inner);
    }

    /** The output names of a query's select list, or null when one of them cannot be derived. */
    private Set<String> selectListColumns(int select) {
        List<int[]> items = selectItems(select);
        if (items == null) {
            return null;
        }
        Set<String> names = new HashSet<>();
        for (int[] item : items) {
            if (expandsStar(item)) {
                Set<String> expanded = structStarColumns(select, item[0], item[1]);
                if (expanded == null) {
                    return null;
                }
                names.addAll(expanded);
                continue;
            }
            String name = outputNameText(item[0], item[1]);
            if (name == null) {
                return null;
            }
            names.add(name.toLowerCase(Locale.ROOT));
        }
        return names;
    }

    /**
     * The columns a star select item expands to: a bare {@code *} takes the query's single source, while
     * {@code alias.column.*} takes the fields of the struct that column is built from. Anything else is
     * unknown, which keeps the caller from recording the wrong names.
     */
    private Set<String> structStarColumns(int select, int start, int end) {
        List<Token> item = significant(start, end);
        if (item.isEmpty()) {
            return null;
        }
        if (item.getFirst().isPunct("*")) {
            return withoutExcludedColumns(starColumns(select), item);
        }
        int star = 0;
        while (star < item.size() && !item.get(star).isPunct("*")) {
            star++;
        }
        int dot = star - 1;
        if (dot < 1 || !item.get(dot).isPunct(".") || !isName(item.get(dot - 1))) {
            return null;
        }
        List<String> path = new ArrayList<>();
        for (int i = 0; i < dot; i++) {
            if (i % 2 == 0) {
                if (!isName(item.get(i))) {
                    return null;
                }
                path.addAll(pathSegments(item.get(i)));
            } else if (!item.get(i).isPunct(".")) {
                return null;
            }
        }
        return withoutExcludedColumns(columnFields(select, path), item);
    }

    /**
     * The columns a star item still produces: the ones its {@code EXCEPT} list does not name, since a query
     * reading the item's source cannot see those.
     */
    private Set<String> withoutExcludedColumns(Set<String> columns, List<Token> item) {
        if (columns == null) {
            return null;
        }
        Set<String> excluded = new LinkedHashSet<>();
        for (int i = 0; i < item.size(); i++) {
            if (!item.get(i).isKeyword("EXCEPT") || i + 1 >= item.size() || !item.get(i + 1).isPunct("(")) {
                continue;
            }
            int depth = 0;
            for (int k = i + 1; k < item.size(); k++) {
                Token token = item.get(k);
                if (token.isPunct("(")) {
                    depth++;
                    continue;
                }
                if (token.isPunct(")")) {
                    if (--depth == 0) {
                        break;
                    }
                    continue;
                }
                if (isName(token)) {
                    excluded.add(token.identifierText().toLowerCase(Locale.ROOT));
                }
            }
            break;
        }
        if (excluded.isEmpty()) {
            return columns;
        }
        Set<String> remaining = new LinkedHashSet<>(columns);
        remaining.removeAll(excluded);
        return remaining;
    }

    /** The fields at a complete source path, or null when they cannot be derived. */
    private Set<String> columnFields(int select, List<String> path) {
        int depth = parenthesisDepth(select);
        for (int i = select + 1; i < tokens.size(); i++) {
            if (parenthesisDepth(i) < depth) {
                break;
            }
            if (parenthesisDepth(i) != depth || !isFromClause(i)) {
                continue;
            }
            List<ProjectionSource> sources = projectionSources(i, depth);
            boolean qualified = path.size() > 1 && sources.stream()
                    .anyMatch(source -> source.alias() != null && source.alias().equalsIgnoreCase(path.getFirst()));
            List<String> columnPath = qualified ? path.subList(1, path.size()) : path;
            for (ProjectionSource source : sources) {
                if (qualified
                        && (source.alias() == null || !source.alias().equalsIgnoreCase(path.getFirst()))) {
                    continue;
                }
                if (source.directTable()) {
                    TableRef ref = projectionSourceTable(source.startToken());
                    List<TableFieldSchema> fields = ref == null ? null : fieldsOf.apply(ref);
                    for (String segment : columnPath) {
                        if (fields == null) {
                            break;
                        }
                        TableFieldSchema matching = null;
                        for (TableFieldSchema field : fields) {
                            if (field.getName().equalsIgnoreCase(segment)) {
                                matching = field;
                                break;
                            }
                        }
                        fields = matching == null ? null : matching.getFields();
                    }
                    if (fields != null) {
                        return fields.stream().map(field -> field.getName().toLowerCase(Locale.ROOT))
                                .collect(Collectors.toCollection(LinkedHashSet::new));
                    }
                    continue;
                }
                int sourceSelect = sourceSelect(source);
                if (sourceSelect < 0) {
                    continue;
                }
                int[] expression = itemExpression(sourceSelect, columnPath.getFirst());
                if (expression == null) {
                    continue;
                }
                Set<String> fields = structLiteralFields(expression[0], expression[1],
                        columnPath.subList(1, columnPath.size()));
                if (fields != null) {
                    return fields;
                }
            }
            break;
        }
        return null;
    }

    /** The select behind a source, or -1 when it is a table or a CTE whose definition cannot be read. */
    private int sourceSelect(ProjectionSource source) {
        if (source.directTable()) {
            return -1;
        }
        int inner = nextSignificant(source.startToken() + 1, tokens.size());
        if (inner >= 0 && tokens.get(inner).isKeyword("SELECT")) {
            return inner;
        }
        Token name = tokens.get(source.startToken());
        return isName(name) ? cteSelect(name.identifierText(), source.startToken()) : -1;
    }

    /** The select list item of {@code select} whose output name is {@code name}, or null. */
    private int[] itemExpression(int select, String name) {
        List<int[]> items = selectItems(select);
        if (items == null) {
            return null;
        }
        for (int[] item : items) {
            String outputName = outputNameText(item[0], item[1]);
            if (outputName != null && outputName.equalsIgnoreCase(name)) {
                return item;
            }
        }
        return null;
    }

    /** The field names of a {@code STRUCT(...)} expression, or null when it is something else. */
    private Set<String> structLiteralFields(int start, int end) {
        return structLiteralFields(start, end, List.of());
    }

    private Set<String> structLiteralFields(int start, int end, List<String> nestedPath) {
        int first = nextSignificant(start, end);
        if (first < 0 || !tokens.get(first).isKeyword("STRUCT")) {
            return null;
        }
        int open = nextSignificant(first + 1, end);
        if (open < 0 || !tokens.get(open).isPunct("(")) {
            return null;
        }
        int close = matchingParen(open);
        if (close < 0 || close >= end) {
            return null;
        }
        Map<String, List<Token>> fields = googleSqlStructFields(open, close);
        if (!nestedPath.isEmpty()) {
            List<Token> expression = fields.get(nestedPath.getFirst().toLowerCase(Locale.ROOT));
            if (expression == null || expression.isEmpty()) {
                return null;
            }
            return structLiteralFields(tokens.indexOf(expression.getFirst()), tokens.indexOf(expression.getLast()) + 1,
                    nestedPath.subList(1, nestedPath.size()));
        }
        return new LinkedHashSet<>(fields.keySet());
    }

    /** The columns a {@code *} select list expands to, taken from the query's single source. */
    private Set<String> starColumns(int select) {
        if (!structStarColumnScopes.add(select)) {
            return null;
        }
        try {
            int depth = parenthesisDepth(select);
            for (int i = select + 1; i < tokens.size(); i++) {
                if (parenthesisDepth(i) < depth) {
                    break;
                }
                if (parenthesisDepth(i) == depth && isFromClause(i)) {
                    List<ProjectionSource> sources = projectionSources(i, depth);
                    return sources.size() == 1 ? sourceColumns(sources.getFirst()) : null;
                }
            }
            return null;
        } finally {
            structStarColumnScopes.remove(select);
        }
    }

    /** The columns a visible CTE exposes, or null when they cannot be determined. */
    private Set<String> cteColumns(String name, int position) {
        CteScope nearest = null;
        int nearestStart = -1;
        for (CteScope scope : cteScopes) {
            if (!scope.name().equalsIgnoreCase(name)) {
                continue;
            }
            int with = tokens.indexOf(scope.start());
            int scopeEnd = scope.end() == null ? tokens.size() : tokens.indexOf(scope.end());
            if (with < 0 || with >= position || position >= scopeEnd || with < nearestStart) {
                continue;
            }
            // A CTE declared in a nearer query hides an outer one with the same name.
            nearest = scope;
            nearestStart = with;
        }
        if (nearest == null) {
            return null;
        }
        int select = cteSelect(name, position);
        return select < 0 ? null : selectListColumns(select);
    }

    /** The select of a visible CTE's definition, or -1 when it cannot be read. */
    private int cteSelect(String name, int position) {
        CteScope nearest = null;
        int nearestStart = -1;
        for (CteScope scope : cteScopes) {
            if (!scope.name().equalsIgnoreCase(name)) {
                continue;
            }
            int with = tokens.indexOf(scope.start());
            int scopeEnd = scope.end() == null ? tokens.size() : tokens.indexOf(scope.end());
            if (with < 0 || with >= position || position >= scopeEnd || with < nearestStart) {
                continue;
            }
            nearest = scope;
            nearestStart = with;
        }
        if (nearest == null) {
            return -1;
        }
        int scopeEnd = nearest.end() == null ? tokens.size() : tokens.indexOf(nearest.end());
        for (int i = nearestStart + 1; i < scopeEnd; i++) {
            if (!isName(tokens.get(i)) || !tokens.get(i).identifierText().equalsIgnoreCase(name)) {
                continue;
            }
            int as = nextSignificant(i + 1, scopeEnd);
            int open = as < 0 ? -1 : nextSignificant(as + 1, scopeEnd);
            if (as >= 0 && tokens.get(as).isKeyword("AS") && open >= 0 && tokens.get(open).isPunct("(")) {
                int select = nextSignificant(open + 1, matchingParen(open));
                if (select >= 0 && tokens.get(select).isKeyword("SELECT")) {
                    return select;
                }
            }
        }
        return -1;
    }

    private static Set<String> lowercasedNames(Set<String> names) {
        Set<String> lowered = new HashSet<>();
        for (String name : names) {
            lowered.add(name.toLowerCase(Locale.ROOT));
        }
        return lowered;
    }

    private static List<String> generatedAliases(Map<String, SourceReference> sourceReferences) {
        List<String> aliases = new ArrayList<>();
        for (SourceReference reference : sourceReferences.values()) {
            aliases.add(reference.alias());
        }
        return aliases;
    }

    /** The first {@code name_1}, {@code name_2}, ... that is not taken, and records it as taken. */
    private static String suffixedName(String name, Set<String> taken) {
        int suffix = 1;
        String candidate;
        do {
            candidate = name + "_" + suffix++;
        } while (!taken.add(candidate.toLowerCase(Locale.ROOT)));
        return candidate;
    }

    /**
     * Gives a star expansion BigQuery's output names when DuckDB would number a duplicate differently.
     * BigQuery reserves every name the select list writes, so a duplicate skips a suffix a later output
     * already uses ({@code b, a, b, b_1} becomes {@code b, a, b_2, b_1}); DuckDB numbers duplicates in
     * place ({@code b, a, b_1, b_1_1}), so the projection is emitted by position to keep both row order
     * and names. {@code written} is null when the expanded fields are not known.
     */
    private List<Token> withBigQueryOutputNames(List<Token> select, List<String> written, List<String> names) {
        if (written == null || names == null || names.equals(duckOutputNames(written))) {
            return select;
        }
        List<Token> wrapped = new ArrayList<>();
        wrapped.addAll(new Lexer("SELECT ").tokenize());
        for (int index = 0; index < names.size(); index++) {
            if (index > 0) {
                wrapped.addAll(commaSeparator());
            }
            wrapped.addAll(positionalColumn(index + 1, names.get(index)));
        }
        wrapped.addAll(new Lexer(" FROM (").tokenize());
        wrapped.addAll(select);
        wrapped.addAll(new Lexer(") AS `" + structStarName("_floci_names") + "`").tokenize());
        return wrapped;
    }

    private static List<Token> positionalColumn(int position, String name) {
        return List.of(Token.raw("#" + position), space(), new Token(Kind.IDENT, "AS", "AS"),
                space(), new Token(Kind.QIDENT, DuckTypes.quoteIdentifier(name), name));
    }

    private static Token space() {
        return new Token(Kind.SPACE, " ", " ");
    }

    private static List<Token> commaSeparator() {
        return List.of(new Token(Kind.PUNCT, ",", ","), space());
    }

    /** The names a select list writes in output order, or null when the star's fields are not known. */
    private static List<String> writtenOutputNames(List<String> siblingNames, Set<String> starFields, int starItem) {
        if (starFields == null) {
            return null;
        }
        List<String> written = new ArrayList<>();
        for (int index = 0; index < siblingNames.size(); index++) {
            if (index == starItem) {
                written.addAll(starFields);
            } else {
                written.add(siblingNames.get(index));
            }
        }
        return written;
    }

    private record PivotShape(List<String> remaining, List<String> generated) {}

    /**
     * The names a PIVOT writes: the source columns it keeps unchanged (base columns minus the FOR column
     * and the columns its aggregates consume), then one column per aggregate and IN value.
     */
    private PivotShape pivotShape(int select) {
        int depth = parenthesisDepth(select);
        int from = namesFromClause(select, depth);
        if (from < 0) {
            return null;
        }
        int fromEnd = structStarFromEnd(from, structStarQueryEnd(select), depth);
        int pivot = -1;
        for (int i = from; i < fromEnd; i++) {
            if (parenthesisDepth(i) != depth) {
                continue;
            }
            if (tokens.get(i).isKeyword("UNPIVOT")) {
                return null;
            }
            if (tokens.get(i).isKeyword("PIVOT")) {
                pivot = i;
                break;
            }
        }
        if (pivot < 0) {
            return null;
        }
        List<ProjectionSource> sources = projectionSources(from, depth);
        if (sources.size() != 1) {
            return null;
        }
        List<String> base = sourceWrittenColumns(sources.getFirst(), select);
        if (base == null) {
            return null;
        }
        int open = -1;
        for (int i = pivot + 1; i < fromEnd; i++) {
            if (parenthesisDepth(i) == depth && tokens.get(i).isPunct("(")) {
                open = i;
                break;
            }
        }
        if (open < 0) {
            return null;
        }
        int close = matchingParen(open);
        int contentsDepth = depth + 1;
        int forKeyword = keywordAtDepth(open + 1, close, contentsDepth, "FOR");
        if (forKeyword < 0) {
            return null;
        }
        Set<String> consumed = new HashSet<>();
        for (int i = open + 1; i < forKeyword; i++) {
            if (!isReferenceName(i, forKeyword, Set.of())) {
                continue;
            }
            int next = nextSignificant(i + 1, forKeyword);
            if (next >= 0 && tokens.get(next).isPunct(".")) {
                continue; // a qualifier; the field after the dot is the reference
            }
            consumed.add(tokens.get(i).identifierText().toLowerCase(Locale.ROOT));
        }
        int column = nextSignificant(forKeyword + 1, close);
        if (column >= 0 && isName(tokens.get(column))) {
            consumed.add(tokens.get(column).identifierText().toLowerCase(Locale.ROOT));
        }
        List<String> remaining = new ArrayList<>();
        for (String field : base) {
            if (!consumed.contains(field.toLowerCase(Locale.ROOT))) {
                remaining.add(field);
            }
        }
        int inKeyword = keywordAtDepth(forKeyword + 1, close, contentsDepth, "IN");
        List<String> generated = inKeyword < 0 ? null
                : pivotGeneratedNames(open + 1, forKeyword, inKeyword, close);
        return new PivotShape(remaining, generated);
    }

    /**
     * The output name of each PIVOT aggregate column in DuckDB's output order: aggregate-major, then IN
     * value. A single unnamed aggregate is named by its value; every other aggregate needs an alias,
     * which GoogleSQL emits as {@code <alias>_<value>}. Null when a name cannot be derived.
     */
    private List<String> pivotGeneratedNames(int aggregatesStart, int forIndex, int inIndex, int close) {
        List<String> prefixes = new ArrayList<>();
        for (int[] range : argumentRanges(aggregatesStart, forIndex)) {
            int as = topLevelAs(range[0], range[1]);
            int alias = as >= 0 ? nextSignificant(as + 1, range[1]) : implicitSelectAlias(range[0], range[1]);
            prefixes.add(alias < 0 ? null : tokens.get(alias).identifierText());
        }
        int valuesOpen = nextSignificant(inIndex + 1, close);
        if (valuesOpen < 0 || !tokens.get(valuesOpen).isPunct("(")) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (int[] range : argumentRanges(valuesOpen, matchingParen(valuesOpen))) {
            int as = topLevelAs(range[0], range[1]);
            int alias = as >= 0 ? nextSignificant(as + 1, range[1]) : implicitSelectAlias(range[0], range[1]);
            if (alias >= 0) {
                values.add(tokens.get(alias).identifierText());
                continue;
            }
            List<Token> value = significant(range[0], range[1]);
            if (value.size() != 1 || value.getFirst().kind != Kind.STRING && value.getFirst().kind != Kind.NUMBER) {
                return null;
            }
            values.add(value.getFirst().kind == Kind.STRING ? value.getFirst().value : value.getFirst().text);
        }
        if (prefixes.size() == 1 && prefixes.getFirst() == null) {
            return values;
        }
        if (prefixes.stream().anyMatch(prefix -> prefix == null)) {
            return null;
        }
        List<String> generated = new ArrayList<>();
        for (String prefix : prefixes) {
            for (String value : values) {
                generated.add(prefix + "_" + value);
            }
        }
        return generated;
    }

    /** The full written-name list of a PIVOT, or null when a generated name cannot be derived. */
    private List<String> pivotOutputNames(int select) {
        PivotShape shape = pivotShape(select);
        if (shape == null || shape.generated() == null) {
            return null;
        }
        List<String> names = new ArrayList<>(shape.remaining());
        names.addAll(shape.generated());
        return names;
    }

    /**
     * A star's EXCEPT list over PIVOT output names written columns. When a surviving source column repeats,
     * BigQuery suffixes the later outputs, so excluding the written name must exclude its suffixes too.
     * DuckDB's PIVOT keeps the source names, so the suffixes are added to the EXCEPT list before the pivot
     * is rewritten.
     */
    private void augmentPivotStarExclusions() {
        for (int select = tokens.size() - 1; select >= 0; select--) {
            if (!tokens.get(select).isKeyword("SELECT")) {
                continue;
            }
            PivotShape pivot = pivotShape(select);
            if (pivot == null) {
                continue;
            }
            List<String> remaining = pivot.remaining();
            List<String> suffixed = bigQueryOutputNames(remaining);
            boolean duplicates = nameCounts(remaining).values().stream().anyMatch(count -> count > 1);
            List<int[]> items = selectItems(select);
            if (items == null) {
                continue;
            }
            for (int index = items.size() - 1; index >= 0; index--) {
                int[] item = items.get(index);
                int star = projectionStarIndex(item);
                if (star < 0) {
                    continue;
                }
                int clause = nextSignificant(star + 1, item[1]);
                if (clause < 0 || !tokens.get(clause).isKeyword("EXCEPT")) {
                    continue;
                }
                int open = nextSignificant(clause + 1, item[1]);
                if (open < 0 || !tokens.get(open).isPunct("(")) {
                    continue;
                }
                int close = matchingParen(open);
                List<String> extras = new ArrayList<>();
                for (Token token : significant(open + 1, close)) {
                    if (token.kind != Kind.IDENT && token.kind != Kind.QIDENT) {
                        continue;
                    }
                    String name = token.identifierText();
                    boolean written = remaining.stream().anyMatch(field -> field.equalsIgnoreCase(name));
                    boolean generated = !written && suffixed.stream().anyMatch(n -> n.equalsIgnoreCase(name));
                    if (generated) {
                        throw invalidQuery("Column " + name + " in SELECT * EXCEPT list does not exist");
                    }
                    if (!duplicates || !written) {
                        continue;
                    }
                    for (int position = 0; position < remaining.size(); position++) {
                        if (remaining.get(position).equalsIgnoreCase(name)
                                && !suffixed.get(position).equalsIgnoreCase(name)) {
                            extras.add(suffixed.get(position));
                        }
                    }
                }
                if (!extras.isEmpty()) {
                    List<Token> addition = new ArrayList<>();
                    for (String extra : extras.stream().distinct().toList()) {
                        addition.addAll(commaSeparator());
                        addition.add(new Token(Kind.QIDENT, DuckTypes.quoteIdentifier(extra), extra));
                    }
                    tokens.addAll(close, addition);
                }
            }
        }
    }

    /**
     * The columns a bare star over an UNPIVOT source outputs, in BigQuery's order and with the reference
     * that reads each from the FROM relation. The UNPIVOT source contributes the columns it does not
     * consume, then the value columns, then the name column; any other source contributes its columns in
     * source order. Null when the clause cannot be read, the FROM has a USING join, or a PIVOT is present.
     */
    private List<StarColumn> unpivotStarColumns(int select) {
        int depth = parenthesisDepth(select);
        int from = namesFromClause(select, depth);
        if (from < 0 || hasUsingClause(select)) {
            return null;
        }
        int fromEnd = structStarFromEnd(from, structStarQueryEnd(select), depth);
        for (int i = from; i < fromEnd; i++) {
            if (parenthesisDepth(i) == depth && tokens.get(i).isKeyword("PIVOT")) {
                return null;
            }
        }
        List<ProjectionSource> sources = projectionSources(from, depth);
        List<StarColumn> columns = new ArrayList<>();
        int offset = 0;
        boolean anyUnpivot = false;
        for (int index = 0; index < sources.size(); index++) {
            ProjectionSource source = sources.get(index);
            List<String> sourceColumns = sourceWrittenColumns(source, select);
            if (sourceColumns == null) {
                return null;
            }
            int segmentStart = Math.max(source.nextToken(), from);
            int segmentEnd = index + 1 < sources.size() ? sources.get(index + 1).startToken() : fromEnd;
            int unpivot = -1;
            int unpivotCount = 0;
            for (int i = segmentStart; i < segmentEnd; i++) {
                if (parenthesisDepth(i) == depth && tokens.get(i).isKeyword("UNPIVOT")) {
                    unpivotCount++;
                    if (unpivot < 0) {
                        unpivot = i;
                    }
                }
            }
            if (unpivotCount > 1) {
                return null;
            }
            if (unpivot >= 0) {
                UnpivotFields shaped = unpivotClauseFields(unpivot, segmentEnd, depth, sourceColumns);
                if (shaped == null) {
                    return null;
                }
                for (int i = 0; i < shaped.fields().size(); i++) {
                    columns.add(new StarColumn(shaped.fields().get(i),
                            positionReference(offset + shaped.positions().get(i))));
                }
                offset += shaped.fields().size();
                anyUnpivot = true;
                continue;
            }
            for (int i = 0; i < sourceColumns.size(); i++) {
                columns.add(new StarColumn(sourceColumns.get(i), positionReference(offset + i + 1)));
            }
            offset += sourceColumns.size();
        }
        return anyUnpivot ? columns : null;
    }

    private record UnpivotFields(List<String> fields, List<Integer> positions) {}

    /**
     * The columns one UNPIVOT clause writes, in BigQuery's order: the source columns it does not consume,
     * then the value columns, then the name column. Positions are relative to the unpivot source.
     */
    private UnpivotFields unpivotClauseFields(int unpivot, int segmentEnd, int depth, List<String> baseFields) {
        int open = -1;
        for (int i = unpivot + 1; i < segmentEnd; i++) {
            if (parenthesisDepth(i) == depth && tokens.get(i).isPunct("(")) {
                open = i;
                break;
            }
        }
        if (open < 0) {
            return null;
        }
        int close = matchingParen(open);
        if (close < 0 || close >= segmentEnd) {
            return null;
        }
        int contentsDepth = depth + 1;
        int forKeyword = keywordAtDepth(open + 1, close, contentsDepth, "FOR");
        if (forKeyword < 0) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (Token token : significant(open + 1, forKeyword)) {
            if (token.kind == Kind.IDENT || token.kind == Kind.QIDENT) {
                values.add(token.identifierText());
            }
        }
        int nameIndex = nextSignificant(forKeyword + 1, close);
        if (nameIndex < 0 || !isName(tokens.get(nameIndex))) {
            return null;
        }
        String name = tokens.get(nameIndex).identifierText();
        int inKeyword = keywordAtDepth(nameIndex + 1, close, contentsDepth, "IN");
        int listOpen = inKeyword < 0 ? -1 : nextSignificant(inKeyword + 1, close);
        if (listOpen < 0 || !tokens.get(listOpen).isPunct("(")) {
            return null;
        }
        int listClose = matchingParen(listOpen);
        Set<String> consumed = new HashSet<>();
        for (int i = listOpen + 1; i < listClose; i++) {
            if (isName(tokens.get(i))) {
                consumed.add(tokens.get(i).identifierText().toLowerCase(Locale.ROOT));
            }
        }
        List<String> remaining = new ArrayList<>();
        for (String field : baseFields) {
            if (!consumed.contains(field.toLowerCase(Locale.ROOT))) {
                remaining.add(field);
            }
        }
        List<String> fields = new ArrayList<>(remaining);
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < remaining.size(); i++) {
            positions.add(i + 1);
        }
        int namePosition = remaining.size() + 1;
        for (int i = 0; i < values.size(); i++) {
            fields.add(values.get(i));
            positions.add(namePosition + 1 + i);
        }
        fields.add(name);
        positions.add(namePosition);
        return new UnpivotFields(fields, positions);
    }

    private static List<String> starColumnNames(List<StarColumn> columns) {
        List<String> names = new ArrayList<>(columns.size());
        for (StarColumn column : columns) {
            names.add(column.name());
        }
        return names;
    }

    /**
     * Rewrites a bare star over an UNPIVOT source to positional outputs in BigQuery's column order. The
     * EXCEPT list is applied to the written names first, as in BigQuery.
     */
    private void rewriteUnpivotStars() {
        for (int select = tokens.size() - 1; select >= 0; select--) {
            if (!tokens.get(select).isKeyword("SELECT")) {
                continue;
            }
            List<StarColumn> unpivotColumns = unpivotStarColumns(select);
            if (unpivotColumns == null) {
                continue;
            }
            List<int[]> items = selectItems(select);
            if (items == null) {
                continue;
            }
            for (int index = items.size() - 1; index >= 0; index--) {
                int[] item = items.get(index);
                int star = projectionStarIndex(item);
                if (star < 0) {
                    continue;
                }
                int dot = significantBefore(star);
                if (dot >= 0 && tokens.get(dot).isPunct(".")) {
                    continue; // a qualified or struct star
                }
                Set<String> excluded = new HashSet<>();
                Map<String, List<Token>> replaced = new LinkedHashMap<>();
                int clause = nextSignificant(star + 1, item[1]);
                if (clause >= 0 && tokens.get(clause).isKeyword("EXCEPT")) {
                    int open = nextSignificant(clause + 1, item[1]);
                    if (open < 0 || !tokens.get(open).isPunct("(")) {
                        continue;
                    }
                    int close = matchingParen(open);
                    for (Token token : significant(open + 1, close)) {
                        if (token.kind != Kind.IDENT && token.kind != Kind.QIDENT) {
                            continue;
                        }
                        String name = token.identifierText();
                        if (unpivotColumns.stream().noneMatch(column -> column.name().equalsIgnoreCase(name))) {
                            throw invalidQuery("Column " + name + " in SELECT * EXCEPT list does not exist");
                        }
                        excluded.add(name.toLowerCase(Locale.ROOT));
                    }
                    clause = nextSignificant(close + 1, item[1]);
                }
                if (clause >= 0 && tokens.get(clause).isKeyword("REPLACE")) {
                    int open = nextSignificant(clause + 1, item[1]);
                    if (open >= 0 && tokens.get(open).isPunct("(")) {
                        int close = matchingParen(open);
                        for (int[] range : replaceEntryRanges(open + 1, close)) {
                            int as = topLevelAs(range[0], range[1]);
                            int target = as < 0 ? -1 : nextSignificant(as + 1, range[1]);
                            int first = nextSignificant(range[0], as < 0 ? range[1] : as);
                            if (target < 0 || first < 0 || !isName(tokens.get(target))) {
                                continue;
                            }
                            int end = as;
                            while (end > first && tokens.get(end - 1).kind == Kind.SPACE) {
                                end--;
                            }
                            replaced.put(tokens.get(target).identifierText().toLowerCase(Locale.ROOT),
                                    new ArrayList<>(tokens.subList(first, end)));
                        }
                    }
                }
                List<Integer> kept = new ArrayList<>();
                List<String> keptNames = new ArrayList<>();
                for (int position = 0; position < unpivotColumns.size(); position++) {
                    String column = unpivotColumns.get(position).name();
                    if (!excluded.contains(column.toLowerCase(Locale.ROOT))) {
                        kept.add(position);
                        keptNames.add(column);
                    }
                }
                if (kept.isEmpty()) {
                    throw invalidQuery("SELECT * expands to zero columns after applying EXCEPT");
                }
                List<String> names = bigQueryOutputNames(keptNames);
                List<Token> replacement = new ArrayList<>();
                replacement.add(space());
                for (int position = 0; position < kept.size(); position++) {
                    if (position > 0) {
                        replacement.addAll(commaSeparator());
                    }
                    String name = names.get(position);
                    List<Token> expression = replaced.get(keptNames.get(position).toLowerCase(Locale.ROOT));
                    if (expression == null) {
                        replacement.addAll(aliasedColumn(unpivotColumns.get(kept.get(position)).reference(), name));
                        continue;
                    }
                    replacement.addAll(expression);
                    replacement.add(space());
                    replacement.add(new Token(Kind.IDENT, "AS", "AS"));
                    replacement.add(space());
                    replacement.add(new Token(Kind.QIDENT, DuckTypes.quoteIdentifier(name), name));
                }
                replacement.add(space());
                tokens.subList(item[0], item[1]).clear();
                tokens.addAll(item[0], replacement);
            }
        }
    }

    /**
     * PIVOT and UNPIVOT read the source's columns too, so a duplicate name they reference is ambiguous.
     * The value and name columns UNPIVOT creates are aliases, not references, and PIVOT's IN values are
     * constants, so only the aggregate expressions, the FOR column and UNPIVOT's IN list are checked.
     */
    private void validatePivotReferences() {
        for (int select = 0; select < tokens.size(); select++) {
            if (!tokens.get(select).isKeyword("SELECT")) {
                continue;
            }
            int depth = parenthesisDepth(select);
            int from = namesFromClause(select, depth);
            if (from < 0 || !pivotInFrom(select, depth)) {
                continue;
            }
            int end = structStarQueryEnd(select);
            for (int i = from; i < end; i++) {
                if (parenthesisDepth(i) != depth) {
                    continue;
                }
                boolean pivot = tokens.get(i).isKeyword("PIVOT");
                boolean unpivot = tokens.get(i).isKeyword("UNPIVOT");
                if (!pivot && !unpivot) {
                    continue;
                }
                int open = nextSignificant(i + 1, end);
                if (open < 0 || !tokens.get(open).isPunct("(")) {
                    continue;
                }
                int close = matchingParen(open);
                if (close < 0) {
                    continue;
                }
                ProjectionSource base = null;
                for (ProjectionSource source : projectionSources(from, depth)) {
                    if (source.startToken() < i) {
                        base = source;
                    }
                }
                if (base == null) {
                    continue;
                }
                List<String> columns = sourceWrittenColumns(base, select);
                if (columns == null) {
                    continue;
                }
                Map<String, Integer> counts = nameCounts(columns);
                int contentsDepth = parenthesisDepth(open) + 1;
                if (pivot) {
                    int forKeyword = keywordAtDepth(open + 1, close, contentsDepth, "FOR");
                    int aggregatesEnd = forKeyword < 0 ? close : forKeyword;
                    validatePivotReferences(open + 1, aggregatesEnd, contentsDepth, select, counts);
                    int column = forKeyword < 0 ? -1 : nextSignificant(forKeyword + 1, close);
                    if (column >= 0 && isName(tokens.get(column))) {
                        validatePivotName(tokens.get(column), counts);
                    }
                } else {
                    int inKeyword = keywordAtDepth(open + 1, close, contentsDepth, "IN");
                    int listOpen = inKeyword < 0 ? -1 : nextSignificant(inKeyword + 1, close);
                    if (listOpen >= 0 && tokens.get(listOpen).isPunct("(")) {
                        int listClose = matchingParen(listOpen);
                        validatePivotReferences(listOpen + 1, listClose, contentsDepth + 1, select, counts);
                    }
                }
                i = close;
            }
        }
    }

    private void validatePivotReferences(int start, int end, int depth, int select, Map<String, Integer> counts) {
        for (int i = start; i < end; i++) {
            if (parenthesisDepth(i) < depth || insideNestedQuery(i, select)
                    || !isReferenceName(i, end, Set.of())) {
                continue;
            }
            validatePivotName(tokens.get(i), counts);
        }
    }

    private void validatePivotName(Token token, Map<String, Integer> counts) {
        String name = token.identifierText();
        if (counts.getOrDefault(name.toLowerCase(Locale.ROOT), 0) > 1) {
            throw invalidQuery("Column name " + name + " is ambiguous");
        }
    }

    private int keywordAtDepth(int start, int end, int depth, String keyword) {
        for (int i = start; i < end; i++) {
            if (parenthesisDepth(i) == depth && tokens.get(i).isKeyword(keyword) && !isPrecededByDot(i)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * A bare star's EXCEPT list drops every output written with that name, and REPLACE rejects an
     * ambiguous name. Both resolve against the source select list's written names.
     */
    private void validateSourceStarLists() {
        for (int select = 0; select < tokens.size(); select++) {
            if (!tokens.get(select).isKeyword("SELECT")) {
                continue;
            }
            List<int[]> items = selectItems(select);
            if (items == null) {
                continue;
            }
            for (int[] item : items) {
                validateSourceStarList(select, item);
            }
        }
    }

    private void validateSourceStarList(int select, int[] item) {
        int star = projectionStarIndex(item);
        if (star < 0 || !sourceStar(select, star)) {
            return;
        }
        List<String> fields = starSourceWrittenColumns(select, item);
        if (fields == null) {
            return;
        }
        Map<String, Integer> counts = nameCounts(fields);
        int clause = nextSignificant(star + 1, item[1]);
        if (clause < 0) {
            return;
        }
        Set<String> excluded = new HashSet<>();
        int replace = clause;
        if (tokens.get(clause).isKeyword("EXCEPT")) {
            int open = nextSignificant(clause + 1, item[1]);
            if (open < 0 || !tokens.get(open).isPunct("(")) {
                return;
            }
            int close = matchingParen(open);
            for (Token token : significant(open + 1, close)) {
                if (token.kind != Kind.IDENT && token.kind != Kind.QIDENT) {
                    continue;
                }
                String key = token.identifierText().toLowerCase(Locale.ROOT);
                if (!counts.containsKey(key)) {
                    throw invalidQuery("Column " + token.identifierText()
                            + " in SELECT * EXCEPT list does not exist");
                }
                excluded.add(key);
            }
            replace = nextSignificant(close + 1, item[1]);
        }
        if (replace < 0 || !tokens.get(replace).isKeyword("REPLACE")) {
            return;
        }
        int open = nextSignificant(replace + 1, item[1]);
        if (open < 0 || !tokens.get(open).isPunct("(")) {
            return;
        }
        int close = matchingParen(open);
        for (String target : replaceTargetNames(open, close)) {
            String key = target.toLowerCase(Locale.ROOT);
            if (!counts.containsKey(key)) {
                throw invalidQuery("Column " + target + " in SELECT * REPLACE list does not exist");
            }
            if (excluded.contains(key)) {
                throw invalidQuery("Column " + target + " cannot occur in both SELECT * EXCEPT and REPLACE");
            }
            if (counts.get(key) > 1) {
                throw invalidQuery("Column " + target + " in SELECT * REPLACE list is ambiguous");
            }
        }
    }

    /**
     * Rewrites a bare star's EXCEPT list to positional output names when one source is in scope. BigQuery
     * removes every output written with an excluded name and re-suffixes the rest, while DuckDB removes
     * one column and keeps its own numbering. Positional references keep both the values and the names.
     */
    private void rewriteSourceStarExclusions() {
        for (int select = tokens.size() - 1; select >= 0; select--) {
            if (!tokens.get(select).isKeyword("SELECT")) {
                continue;
            }
            List<int[]> items = selectItems(select);
            if (items == null) {
                continue;
            }
            for (int index = items.size() - 1; index >= 0; index--) {
                int[] item = items.get(index);
                int star = projectionStarIndex(item);
                if (star < 0) {
                    continue;
                }
                StarShape shape = starShape(select, star);
                if (shape == null) {
                    continue;
                }
                List<StarColumn> columns = shape.columns();
                List<String> fields = new ArrayList<>(columns.size());
                for (StarColumn column : columns) {
                    fields.add(column.name());
                }
                Set<String> excluded = new HashSet<>();
                Map<String, List<Token>> replaced = new LinkedHashMap<>();
                int clause = nextSignificant(star + 1, item[1]);
                if (clause >= 0 && tokens.get(clause).isKeyword("EXCEPT")) {
                    int open = nextSignificant(clause + 1, item[1]);
                    if (open < 0 || !tokens.get(open).isPunct("(")) {
                        continue;
                    }
                    int close = matchingParen(open);
                    for (Token token : significant(open + 1, close)) {
                        if (token.kind == Kind.IDENT || token.kind == Kind.QIDENT) {
                            excluded.add(token.identifierText().toLowerCase(Locale.ROOT));
                        }
                    }
                    clause = nextSignificant(close + 1, item[1]);
                }
                if (clause >= 0 && tokens.get(clause).isKeyword("REPLACE")) {
                    int open = nextSignificant(clause + 1, item[1]);
                    if (open >= 0 && tokens.get(open).isPunct("(")) {
                        int close = matchingParen(open);
                        for (int[] range : replaceEntryRanges(open + 1, close)) {
                            int as = topLevelAs(range[0], range[1]);
                            int target = as < 0 ? -1 : nextSignificant(as + 1, range[1]);
                            int first = nextSignificant(range[0], as < 0 ? range[1] : as);
                            if (target < 0 || first < 0 || !isName(tokens.get(target))) {
                                continue;
                            }
                            int end = as;
                            while (end > first && tokens.get(end - 1).kind == Kind.SPACE) {
                                end--;
                            }
                            replaced.put(tokens.get(target).identifierText().toLowerCase(Locale.ROOT),
                                    new ArrayList<>(tokens.subList(first, end)));
                        }
                    }
                }
                if (excluded.isEmpty() && replaced.isEmpty()) {
                    continue;
                }
                List<Integer> kept = new ArrayList<>();
                List<String> keptNames = new ArrayList<>();
                for (int position = 0; position < fields.size(); position++) {
                    if (!excluded.contains(fields.get(position).toLowerCase(Locale.ROOT))) {
                        kept.add(position);
                        keptNames.add(fields.get(position));
                    }
                }
                if (kept.isEmpty()) {
                    throw invalidQuery(shape.qualified()
                            ? "SELECT .* expands to zero columns after applying EXCEPT"
                            : "SELECT * expands to zero columns after applying EXCEPT");
                }
                if (kept.size() == fields.size()
                        || nameCounts(fields).values().stream().noneMatch(count -> count > 1)) {
                    continue;
                }
                List<String> names = bigQueryOutputNames(keptNames);
                List<Token> replacement = new ArrayList<>();
                replacement.add(space());
                for (int position = 0; position < kept.size(); position++) {
                    if (position > 0) {
                        replacement.addAll(commaSeparator());
                    }
                    String name = names.get(position);
                    List<Token> expression = replaced.get(keptNames.get(position).toLowerCase(Locale.ROOT));
                    if (expression != null) {
                        replacement.addAll(expression);
                        replacement.add(space());
                        replacement.add(new Token(Kind.IDENT, "AS", "AS"));
                        replacement.add(space());
                        replacement.add(new Token(Kind.QIDENT, DuckTypes.quoteIdentifier(name), name));
                        continue;
                    }
                    replacement.addAll(aliasedColumn(columns.get(kept.get(position)).reference(), name));
                }
                replacement.add(space());
                tokens.subList(item[0], item[1]).clear();
                tokens.addAll(item[0], replacement);
            }
        }
    }

    private record StarColumn(String name, List<Token> reference) {}

    private record StarShape(List<StarColumn> columns, boolean qualified) {}

    private static List<Token> positionReference(int position) {
        return List.of(Token.raw("#" + position));
    }

    private static List<Token> nameReference(String name) {
        return List.of(new Token(Kind.QIDENT, DuckTypes.quoteIdentifier(name), name));
    }

    private static List<Token> aliasedColumn(List<Token> reference, String name) {
        List<Token> out = new ArrayList<>(reference);
        out.add(space());
        out.add(new Token(Kind.IDENT, "AS", "AS"));
        out.add(space());
        out.add(new Token(Kind.QIDENT, DuckTypes.quoteIdentifier(name), name));
        return out;
    }

    /**
     * The written fields a source star expands to and, for each, the reference that reads its value in
     * the FROM relation. A plain source column is {@code #n}; a USING column merged across sources is read
     * by name, because DuckDB puts the merged column first in a {@code *} but keeps every source column in
     * relation order. Null for a struct or expression star, a mixed USING join, or an unknown source.
     */
    private StarShape starShape(int select, int star) {
        int depth = parenthesisDepth(select);
        if (pivotInFrom(select, depth)) {
            return null;
        }
        int from = namesFromClause(select, depth);
        if (from < 0) {
            return null;
        }
        int dot = significantBefore(star);
        int last = dot >= 0 && tokens.get(dot).isPunct(".") ? significantBefore(dot) : -1;
        int beforeLast = last < 0 ? -1 : significantBefore(last);
        if (last >= 0 && beforeLast >= 0 && tokens.get(beforeLast).isPunct(".")) {
            return null; // a struct field star
        }
        List<ProjectionSource> sources = projectionSources(from, depth);
        if (last >= 0 && dot >= 0 && tokens.get(dot).isPunct(".")) {
            if (!isName(tokens.get(last))) {
                return null; // an expression star
            }
            String alias = tokens.get(last).identifierText();
            int offset = 0;
            for (ProjectionSource source : sources) {
                List<String> columns = sourceWrittenColumns(source, select);
                if (columns == null) {
                    return null;
                }
                if (source.alias() != null && source.alias().equalsIgnoreCase(alias)) {
                    List<StarColumn> shaped = new ArrayList<>(columns.size());
                    for (int index = 0; index < columns.size(); index++) {
                        shaped.add(new StarColumn(columns.get(index), positionReference(offset + index + 1)));
                    }
                    return new StarShape(shaped, true);
                }
                offset += columns.size();
            }
            return null;
        }
        if (hasUsingClause(select)) {
            if (!usingOnlyJoins(select, depth)) {
                return null;
            }
            List<StarColumn> shaped = usingStarColumns(select, star, sources);
            return shaped == null ? null : new StarShape(shaped, false);
        }
        List<StarColumn> columns = new ArrayList<>();
        int position = 1;
        for (ProjectionSource source : sources) {
            List<String> sourceColumns = sourceWrittenColumns(source, select);
            if (sourceColumns == null) {
                return null;
            }
            for (String column : sourceColumns) {
                columns.add(new StarColumn(column, positionReference(position++)));
            }
        }
        return columns.isEmpty() ? null : new StarShape(columns, false);
    }

    /**
     * True when every source after the first is joined with USING and no comma join is present, so the
     * merged columns and their participants are unambiguous.
     */
    private boolean usingOnlyJoins(int select, int depth) {
        int from = namesFromClause(select, depth);
        if (from < 0) {
            return false;
        }
        List<ProjectionSource> sources = projectionSources(from, depth);
        if (sources.size() < 2) {
            return false;
        }
        for (int index = 1; index < sources.size(); index++) {
            if (usingColumnsAfterSource(sources.get(index), depth).isEmpty()) {
                return false;
            }
        }
        int fromEnd = structStarFromEnd(from, structStarQueryEnd(select), depth);
        for (int i = from; i < fromEnd; i++) {
            if (parenthesisDepth(i) == depth && tokens.get(i).isPunct(",")) {
                return false;
            }
        }
        return true;
    }

    /** The written names a bare star expands to, with USING columns merged and the rest in source order. */
    private List<String> usingStarColumns(int select, int star) {
        List<ProjectionSource> sources = projectionSourcesForStar(star);
        return usingStarColumnNames(select, star, sources);
    }

    private List<String> usingStarColumnNames(int select, int star, List<ProjectionSource> sources) {
        List<StarColumn> columns = usingStarColumns(select, star, sources);
        if (columns == null) {
            return null;
        }
        List<String> names = new ArrayList<>(columns.size());
        for (StarColumn column : columns) {
            names.add(column.name());
        }
        return names;
    }

    private List<StarColumn> usingStarColumns(int select, int star, List<ProjectionSource> sources) {
        List<UsingProjection> merged = usingProjectionsForStar(star, sources);
        Set<String> mergedNames = new HashSet<>();
        Map<Integer, Set<String>> skipped = new HashMap<>();
        for (UsingProjection projection : merged) {
            String key = projection.name().toLowerCase(Locale.ROOT);
            mergedNames.add(key);
            for (ProjectionSource participant : projection.sources()) {
                skipped.computeIfAbsent(participant.startToken(), ignored -> new HashSet<>()).add(key);
            }
        }
        List<StarColumn> columns = new ArrayList<>();
        for (UsingProjection projection : merged) {
            columns.add(new StarColumn(projection.name(), nameReference(projection.name())));
        }
        int offset = 0;
        for (ProjectionSource source : sources) {
            List<String> sourceColumns = sourceWrittenColumns(source, select);
            if (sourceColumns == null) {
                return null;
            }
            Set<String> skip = skipped.getOrDefault(source.startToken(), Set.of());
            for (int index = 0; index < sourceColumns.size(); index++) {
                String column = sourceColumns.get(index);
                if (skip.contains(column.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                boolean unrelatedMergedName = mergedNames.contains(column.toLowerCase(Locale.ROOT));
                if (unrelatedMergedName) {
                    // A source outside the USING join exposes the same name, so the merged name reference
                    // would be ambiguous. Leave this shape to DuckDB.
                    return null;
                }
                columns.add(new StarColumn(column, positionReference(offset + index + 1)));
            }
            offset += sourceColumns.size();
        }
        return columns;
    }

    /**
     * True for a bare {@code *} or {@code alias.*} that reads a FROM source, not a struct field star such
     * as {@code t.s.*} or an expression star such as {@code f(x).*}.
     */
    private boolean sourceStar(int select, int star) {
        int dot = significantBefore(star);
        if (dot < 0 || !tokens.get(dot).isPunct(".")) {
            return true;
        }
        int last = significantBefore(dot);
        int beforeLast = last < 0 ? -1 : significantBefore(last);
        if (last < 0 || !isName(tokens.get(last))
                || beforeLast >= 0 && tokens.get(beforeLast).isPunct(".")) {
            return false;
        }
        return qualifiedStarWrittenColumns(select, star) != null;
    }

    /**
     * BigQuery never resolves a bare ORDER BY name to a suffix it generated for a duplicate output, so a
     * reference to one is unrecognized unless a source column carries that name. Called before the tail
     * is rewritten, while the ORDER BY still holds the caller's names.
     */
    private void validateGeneratedOrderByNames(int select, int start, int end, int queryDepth,
                                               List<String> written, List<String> names,
                                               StructStarSources sources) {
        Set<String> writtenNames = new HashSet<>();
        for (String name : written) {
            writtenNames.add(name.toLowerCase(Locale.ROOT));
        }
        Set<String> generated = new HashSet<>();
        for (String name : names) {
            if (!writtenNames.contains(name.toLowerCase(Locale.ROOT))) {
                generated.add(name.toLowerCase(Locale.ROOT));
            }
        }
        if (generated.isEmpty()) {
            return;
        }
        int order = structStarClauseStart(start, end, queryDepth, "ORDER");
        if (order < 0) {
            return;
        }
        int by = nextSignificant(order + 1, end);
        if (by < 0 || !tokens.get(by).isKeyword("BY")) {
            return;
        }
        int orderEnd = Math.min(clauseEnd(by + 1, queryDepth), end);
        for (int[] range : topLevelRanges(by + 1, orderEnd, queryDepth)) {
            for (int i = range[0]; i < range[1]; i++) {
                if (insideNestedQuery(i, select) || !isBareOrderName(i, range[1])) {
                    continue;
                }
                Token token = tokens.get(i);
                String name = token.identifierText().toLowerCase(Locale.ROOT);
                if (writtenNames.contains(name) || !generated.contains(name)) {
                    continue;
                }
                if (sources != null && sources.allKnown() && !sourceHasColumn(sources, name)) {
                    throw invalidQuery("Unrecognized name: " + token.identifierText());
                }
            }
        }
    }

    /**
     * True for a token in an ORDER BY item that can name a select output: a bare name, not a field of a
     * path, not an order modifier, not a function call and not the type of a {@code CAST}.
     */
    private boolean isBareOrderName(int index, int end) {
        Token token = tokens.get(index);
        if (!isName(token) || isPrecededByDot(index) || isOrderModifier(token)) {
            return false;
        }
        int next = nextSignificant(index + 1, end);
        if (next >= 0 && tokens.get(next).isPunct("(")) {
            return false;
        }
        int before = significantBefore(index);
        return before < 0 || !tokens.get(before).isKeyword("AS");
    }

    private static boolean sourceHasColumn(StructStarSources sources, String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (sources.rangeVariables().contains(key) || sources.usingSources().containsKey(key)) {
            return true;
        }
        for (Set<String> columns : sources.columnsByAlias().values()) {
            if (columns.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * BigQuery rejects a reference to a name two source columns expose, and the select list, WHERE and ON
     * cannot see select aliases either. DuckDB would resolve one of them silently.
     */
    private void validateSourceReferences() {
        for (int select = 0; select < tokens.size(); select++) {
            if (tokens.get(select).isKeyword("SELECT")) {
                validateSourceReferences(select);
            }
        }
    }

    private void validateSourceReferences(int select) {
        List<int[]> items = selectItems(select);
        if (items == null) {
            return;
        }
        int depth = parenthesisDepth(select);
        int tailStart = items.getLast()[1];
        int end = structStarArmEnd(tailStart, structStarQueryEnd(select), depth);
        end = structStarSetOperationTail(select, tailStart, end, depth);
        Map<String, Integer> sources = sourceColumnCounts(select);
        List<String> writtenNames = orderedWrittenOutputNames(select, items);
        Map<String, Integer> written = writtenNames == null
                ? writtenOutputNameCounts(select, items) : nameCounts(writtenNames);
        Set<String> generated = writtenNames == null ? Set.of() : generatedNames(writtenNames);
        Set<String> sourceGenerated = sourceGeneratedNames(select);
        Map<String, Map<String, Integer>> aliasColumns = sourceAliasColumnCounts(select, depth);
        Set<Integer> implicitAliases = implicitAliasTokens(items);
        ReferenceScope scope = new ReferenceScope(select, written, generated, sources, sourceGenerated,
                aliasColumns);
        for (ReferenceRange range : referenceRanges(select, items, depth, end)) {
            if (range.groupBy()) {
                validateGroupByReferences(range, scope, depth);
                continue;
            }
            validateReferenceNames(range.start(), range.end(), range.aliasesHidden(), scope,
                    implicitAliases);
        }
    }

    /** The names a query's select list and sources expose, as a reference in it resolves them. */
    private record ReferenceScope(int select, Map<String, Integer> written, Set<String> generated,
                                  Map<String, Integer> sources, Set<String> sourceGenerated,
                                  Map<String, Map<String, Integer>> aliasColumns) {}

    /**
     * Rejects a bare name in a clause that resolves against the query's sources when it names more than
     * one of their columns, or when only a suffix generated for a duplicate carries it.
     */
    private void validateReferenceNames(int start, int end, boolean aliasesHidden, ReferenceScope scope,
                                        Set<Integer> implicitAliases) {
        for (int i = start; i < end; i++) {
            if (tokens.get(i).isKeyword("STRUCT") || tokens.get(i).isKeyword("ARRAY")) {
                // The field names of a type, STRUCT<n INT64>(x), are not references.
                int angle = nextSignificant(i + 1, end);
                int close = angle >= 0 && tokens.get(angle).isPunct("<") ? matchingTypeAngle(angle) : -1;
                if (close > i) {
                    i = close;
                    continue;
                }
            }
            if (isPrecededByDot(i) || insideNestedQuery(i, scope.select())
                    || !isReferenceName(i, end, implicitAliases)) {
                continue;
            }
            Token token = tokens.get(i);
            String name = token.identifierText();
            String key = name.toLowerCase(Locale.ROOT);
            int dot = nextSignificant(i + 1, end);
            if (dot >= 0 && tokens.get(dot).isPunct(".")) {
                if (ambiguousField(scope.aliasColumns(), key, dot, end)) {
                    continue;
                }
                if (scope.aliasColumns().containsKey(key)) {
                    continue;
                }
                // A struct column of the query's sources, not a table alias.
            }
            if (scope.sources() == null) {
                continue;
            }
            int occurrences = scope.sources().getOrDefault(key, 0);
            if (occurrences > 1) {
                throw invalidQuery("Column name " + name + " is ambiguous");
            }
            if (occurrences == 0 && scope.sourceGenerated().contains(key)) {
                throw invalidQuery("Unrecognized name: " + name);
            }
            if (aliasesHidden && occurrences == 0
                    && (scope.written().containsKey(key) || scope.generated().contains(key))) {
                throw invalidQuery("Unrecognized name: " + name);
            }
        }
    }

    /**
     * True when the field after a source alias is one the alias exposes more than once, which BigQuery
     * reports as ambiguous inside the alias.
     */
    private boolean ambiguousField(Map<String, Map<String, Integer>> aliasColumns, String alias, int dot,
                                   int end) {
        int field = nextSignificant(dot + 1, end);
        Map<String, Integer> columns = aliasColumns.get(alias);
        if (field < 0 || columns == null || !isName(tokens.get(field))) {
            return false;
        }
        String name = tokens.get(field).identifierText();
        if (columns.getOrDefault(name.toLowerCase(Locale.ROOT), 0) > 1) {
            throw invalidQuery("Name " + name + " is ambiguous inside " + tokens.get(
                    significantBefore(dot)).identifierText());
        }
        return true;
    }

    /**
     * A bare GROUP BY item names a unique select alias first; every other item resolves against the
     * sources, where a repeated name is ambiguous. BigQuery resolves {@code GROUP BY b} to the output
     * alias even when the input exposes several columns named b, but a qualified item such as
     * {@code GROUP BY t.b} still names the source alias.
     */
    private void validateGroupByReferences(ReferenceRange range, ReferenceScope scope, int depth) {
        for (int[] item : topLevelRanges(range.start(), range.end(), depth)) {
            List<Token> itemTokens = significant(item[0], item[1]);
            String alias = itemTokens.size() == 1 && isName(itemTokens.getFirst())
                    ? itemTokens.getFirst().identifierText() : null;
            if (alias != null && scope.written().getOrDefault(alias.toLowerCase(Locale.ROOT), 0) == 1) {
                continue;
            }
            validateReferenceNames(item[0], item[1], false, scope, Set.of());
        }
    }

    private record ReferenceRange(int start, int end, boolean aliasesHidden, boolean groupBy) {}

    /**
     * The ranges whose bare names must resolve against the query's sources: select list, WHERE and ON.
     * {@code aliasesHidden} marks the clauses where BigQuery cannot see a select alias; the select list
     * is left false because a typed literal or a PIVOT/UNPIVOT source can split its names before the
     * rewrite that understands them.
     */
    private List<ReferenceRange> referenceRanges(int select, List<int[]> items, int depth, int end) {
        List<ReferenceRange> ranges = new ArrayList<>();
        for (int[] item : items) {
            if (!expandsStar(item)) {
                ranges.add(new ReferenceRange(item[0], item[1], false, false));
            }
        }
        int where = structStarClauseStart(items.getLast()[1], end, depth, "WHERE");
        if (where >= 0) {
            ranges.add(new ReferenceRange(where + 1, Math.min(clauseEnd(where + 1, depth), end), true, false));
        }
        for (String keyword : List.of("GROUP", "WINDOW")) {
            int clause = structStarClauseStart(items.getLast()[1], end, depth, keyword);
            if (clause < 0) {
                continue;
            }
            int start = clause + 1;
            if (tokens.get(clause).isKeyword("GROUP")) {
                int by = nextSignificant(start, tokens.size());
                if (by < 0 || !tokens.get(by).isKeyword("BY")) {
                    continue;
                }
                start = by + 1;
            }
            ranges.add(new ReferenceRange(start, Math.min(clauseEnd(start, depth), end), false,
                    tokens.get(clause).isKeyword("GROUP")));
        }
        int from = namesFromClause(select, depth);
        if (from >= 0) {
            int fromEnd = structStarFromEnd(from, end, depth);
            for (int i = from; i < fromEnd; i++) {
                if (parenthesisDepth(i) != depth || !tokens.get(i).isKeyword("ON")) {
                    continue;
                }
                int onEnd = i + 1;
                for (; onEnd < fromEnd; onEnd++) {
                    if (parenthesisDepth(onEnd) == depth && tokens.get(onEnd).kind == Kind.IDENT
                            && (tokens.get(onEnd).isKeyword("JOIN") || tokens.get(onEnd).isKeyword("INNER")
                                    || tokens.get(onEnd).isKeyword("LEFT") || tokens.get(onEnd).isKeyword("RIGHT")
                                    || tokens.get(onEnd).isKeyword("FULL") || tokens.get(onEnd).isKeyword("CROSS")
                                    || tokens.get(onEnd).isKeyword("ON")
                                    || CLAUSE_END_KEYWORDS.contains(tokens.get(onEnd).upper()))) {
                        break;
                    }
                }
                ranges.add(new ReferenceRange(i + 1, onEnd, true, false));
            }
        }
        return ranges;
    }

    /** The token positions that are implicit select aliases, not source references. */
    private Set<Integer> implicitAliasTokens(List<int[]> items) {
        Set<Integer> aliases = new HashSet<>();
        for (int[] item : items) {
            if (expandsStar(item) || topLevelAs(item[0], item[1]) >= 0) {
                continue;
            }
            int alias = implicitSelectAlias(item[0], item[1]);
            if (alias >= 0) {
                aliases.add(alias);
            }
        }
        return aliases;
    }

    private boolean isReferenceName(int index, int end, Set<Integer> implicitAliases) {
        Token token = tokens.get(index);
        if (!isName(token) || isOrderModifier(token)) {
            return false;
        }
        int next = nextSignificant(index + 1, end);
        if (next >= 0 && tokens.get(next).isPunct("(")) {
            return false;
        }
        int before = significantBefore(index);
        if (before >= 0 && tokens.get(before).isKeyword("AS")) {
            return false;
        }
        return isPrecededByDot(index) || !implicitAliases.contains(index);
    }

    /** The suffixes BigQuery generated for names inside the query's subquery or CTE sources. */
    private Set<String> sourceGeneratedNames(int select) {
        int depth = parenthesisDepth(select);
        if (pivotInFrom(select, depth)) {
            List<StarColumn> unpivot = unpivotStarColumns(select);
            if (unpivot != null) {
                return generatedNames(starColumnNames(unpivot));
            }
            List<String> pivot = pivotOutputNames(select);
            return pivot == null ? Set.of() : generatedNames(pivot);
        }
        int from = namesFromClause(select, depth);
        if (from < 0) {
            return Set.of();
        }
        Set<String> generated = new HashSet<>();
        for (ProjectionSource source : projectionSources(from, depth)) {
            if (source.directTable()) {
                continue;
            }
            List<String> columns = sourceWrittenColumns(source, select);
            if (columns != null) {
                generated.addAll(generatedNames(columns));
            }
        }
        return generated;
    }

    /** How many of each column each named source exposes, for a qualified reference's field check. */
    private Map<String, Map<String, Integer>> sourceAliasColumnCounts(int select, int depth) {
        int from = namesFromClause(select, depth);
        if (from < 0 || pivotInFrom(select, depth)) {
            return Map.of();
        }
        Map<String, Map<String, Integer>> aliases = new HashMap<>();
        for (ProjectionSource source : projectionSources(from, depth)) {
            if (source.alias() == null) {
                continue;
            }
            List<String> columns = sourceWrittenColumns(source, select);
            if (columns != null) {
                aliases.put(source.alias().toLowerCase(Locale.ROOT), nameCounts(columns));
            }
        }
        return aliases;
    }

    /** True when {@code index} sits inside an {@code OVER (...)} window specification. */
    private boolean insideOverClause(int index) {
        int position = index;
        while (true) {
            int open = enclosingParenthesis(position);
            if (open < 0) {
                return false;
            }
            int before = significantBefore(open);
            if (before >= 0 && tokens.get(before).isKeyword("OVER")) {
                return true;
            }
            position = open;
        }
    }

    /**
     * A window in the select list reads the query's input row, not its sibling aliases, so a name two
     * source columns expose is ambiguous there. A window in ORDER BY, HAVING or QUALIFY resolves a bare
     * name to a unique select alias first, as the rest of that clause does.
     */
    private void validateWindowReferences() {
        for (int over = 0; over < tokens.size(); over++) {
            if (!tokens.get(over).isKeyword("OVER") || isPrecededByDot(over)) {
                continue;
            }
            int open = nextSignificant(over + 1, tokens.size());
            if (open < 0 || !tokens.get(open).isPunct("(")) {
                continue;
            }
            int close = matchingParen(open);
            int depth = parenthesisDepth(over);
            boolean aliasesVisible = insideSelectAliasClause(over, depth);
            int select = aliasesVisible ? firstSelectAtDepth(over, depth) : precedingSelect(over, depth);
            if (close < 0 || select < 0) {
                continue;
            }
            Map<String, Integer> sources = sourceColumnCounts(select);
            if (sources == null) {
                continue;
            }
            Map<String, Integer> written = aliasesVisible ? writtenOutputCounts(select) : Map.of();
            for (int i = open + 1; i < close; i++) {
                if (isPrecededByDot(i) || insideNestedQuery(i, select)) {
                    continue;
                }
                int next = nextSignificant(i + 1, close);
                if (next >= 0 && tokens.get(next).isPunct(".")) {
                    continue;
                }
                if (!isReferenceName(i, close, Set.of())) {
                    continue;
                }
                Token token = tokens.get(i);
                String key = token.identifierText().toLowerCase(Locale.ROOT);
                int aliases = written.getOrDefault(key, 0);
                if (aliases > 0) {
                    if (aliases > 1) {
                        throw invalidQuery("Column name " + token.identifierText() + " is ambiguous");
                    }
                    continue;
                }
                if (sources.getOrDefault(key, 0) > 1) {
                    throw invalidQuery("Column name " + token.identifierText() + " is ambiguous");
                }
            }
        }
    }

    /**
     * True when {@code index} sits in a clause that resolves a bare name to a select-list alias: ORDER BY,
     * HAVING or QUALIFY. A select-list item and the WHERE clause resolve against the sources instead.
     */
    private boolean insideSelectAliasClause(int index, int depth) {
        for (int i = index - 1; i >= 0; i--) {
            if (parenthesisDepth(i) != depth || tokens.get(i).kind != Kind.IDENT) {
                continue;
            }
            if (tokens.get(i).isKeyword("ORDER") || tokens.get(i).isKeyword("HAVING")
                    || tokens.get(i).isKeyword("QUALIFY")) {
                return true;
            }
            if (CLAUSE_END_KEYWORDS.contains(tokens.get(i).upper())) {
                return false;
            }
        }
        return false;
    }

    /** How many outputs a select list writes under each name, before BigQuery assigns suffixes. */
    private Map<String, Integer> writtenOutputCounts(int select) {
        List<int[]> items = selectItems(select);
        if (items == null) {
            return Map.of();
        }
        List<String> names = orderedWrittenOutputNames(select, items);
        return names == null ? writtenOutputNameCounts(select, items) : nameCounts(names);
    }

    /**
     * BigQuery resolves a bare name in ORDER BY, HAVING and QUALIFY to the select list first, and only a
     * bare item in GROUP BY, so a name that two outputs were written with is ambiguous in each. DuckDB
     * would resolve one of them silently, so the query is rejected here.
     */
    private void validateSelectNameAmbiguity() {
        for (int clause = tokens.size() - 1; clause >= 0; clause--) {
            boolean order = tokens.get(clause).isKeyword("ORDER");
            boolean group = tokens.get(clause).isKeyword("GROUP");
            boolean having = tokens.get(clause).isKeyword("HAVING");
            boolean qualify = tokens.get(clause).isKeyword("QUALIFY");
            if ((!order && !group && !having && !qualify) || isPrecededByDot(clause)) {
                continue;
            }
            int depth = parenthesisDepth(clause);
            if (order && insideOverClause(clause)) {
                // Window orderings read the input row, not select-list aliases.
                continue;
            }
            int select = firstSelectAtDepth(clause, depth);
            if (select < 0 || leavesDepth(select, clause, parenthesisDepth(select))) {
                continue;
            }
            List<int[]> items = selectItems(select);
            if (items == null) {
                continue;
            }
            List<String> writtenNames = orderedWrittenOutputNames(select, items);
            Map<String, Integer> written = writtenNames == null
                    ? writtenOutputNameCounts(select, items) : nameCounts(writtenNames);
            Set<String> generated = writtenNames == null
                    ? Set.of() : generatedNames(writtenNames);
            Map<String, Integer> sources = sourceColumnCounts(select);
            int start = clause + 1;
            if (order || group) {
                int by = nextSignificant(start, tokens.size());
                if (by < 0 || !tokens.get(by).isKeyword("BY")) {
                    continue;
                }
                start = by + 1;
            }
            int clauseEnd = clauseEnd(start, depth);
            for (int[] range : topLevelRanges(start, clauseEnd, depth)) {
                for (int i = range[0]; i < range[1]; i++) {
                    if (insideNestedQuery(i, select) || !isBareOrderName(i, range[1])) {
                        continue;
                    }
                    String name = tokens.get(i).identifierText();
                    String key = name.toLowerCase(Locale.ROOT);
                    int aliases = written.getOrDefault(key, 0);
                    // An alias shadows a source column of the same name, so only repeats at the same
                    // level are ambiguous.
                    boolean ambiguous = aliases > 1 || aliases == 0 && sources != null
                            && sources.getOrDefault(key, 0) > 1;
                    if (ambiguous) {
                        if (group) {
                            // GROUP BY resolves an alias only when the whole item is that bare name.
                            if (significant(range[0], range[1]).size() != 1) {
                                continue;
                            }
                            if (aliases > 1) {
                                throw invalidQuery("Name " + name + " in GROUP BY clause is ambiguous;"
                                        + " it may refer to multiple columns in the SELECT-list");
                            }
                        }
                        throw invalidQuery("Column name " + name + " is ambiguous");
                    }
                    if (aliases == 0 && sources != null && !sources.containsKey(key)
                            && generated.contains(key)) {
                        throw invalidQuery("Unrecognized name: " + name);
                    }
                }
            }
        }
    }

    /**
     * The first SELECT of the query holding {@code before}, which is the arm whose names a trailing
     * ORDER BY sees when the query is a set operation. SELECTs of nested queries sit deeper and are skipped.
     */
    private int firstSelectAtDepth(int before, int depth) {
        int first = -1;
        for (int i = before - 1; i >= 0; i--) {
            if (parenthesisDepth(i) < depth) {
                break;
            }
            if (parenthesisDepth(i) == depth && tokens.get(i).isKeyword("SELECT")) {
                first = i;
            }
        }
        return first;
    }

    /**
     * How many columns this query's sources expose under each name, before a star expansion renames them.
     * A USING join merges its column, so a name it merges counts once.
     */
    private Map<String, Integer> sourceColumnCounts(int select) {
        int depth = parenthesisDepth(select);
        if (pivotInFrom(select, depth)) {
            List<StarColumn> unpivot = unpivotStarColumns(select);
            if (unpivot != null) {
                return nameCounts(starColumnNames(unpivot));
            }
            List<String> pivot = pivotOutputNames(select);
            return pivot == null ? null : nameCounts(pivot);
        }
        int from = namesFromClause(select, depth);
        if (from < 0) {
            return Map.of();
        }
        List<ProjectionSource> sources = projectionSources(from, depth);
        Map<String, Integer> counts = new HashMap<>();
        for (ProjectionSource source : sources) {
            List<String> columns = sourceWrittenColumns(source, select);
            if (columns == null) {
                return null;
            }
            for (String column : columns) {
                counts.merge(column.toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
        }
        for (ProjectionSource source : sources) {
            for (String merged : usingColumnsAfterSource(source, depth)) {
                counts.put(merged.toLowerCase(Locale.ROOT), 1);
            }
        }
        return counts;
    }

    /** True when the query's FROM is reshaped by PIVOT or UNPIVOT, whose output names are generated. */
    private boolean pivotInFrom(int select, int depth) {
        int from = namesFromClause(select, depth);
        if (from < 0) {
            return false;
        }
        int fromEnd = structStarFromEnd(from, structStarQueryEnd(select), depth);
        for (int i = from; i < fromEnd; i++) {
            if (parenthesisDepth(i) == depth
                    && (tokens.get(i).isKeyword("PIVOT") || tokens.get(i).isKeyword("UNPIVOT"))) {
                return true;
            }
        }
        return false;
    }

    /** The FROM of the query at {@code select}, skipping an EXCEPT that belongs to a star's list. */
    private int namesFromClause(int select, int depth) {
        for (int i = select + 1; i < tokens.size(); i++) {
            int currentDepth = parenthesisDepth(i);
            if (currentDepth < depth) {
                break;
            }
            if (currentDepth == depth && isFromClause(i)) {
                return i;
            }
            if (currentDepth == depth && tokens.get(i).kind == Kind.IDENT
                    && CLAUSE_END_KEYWORDS.contains(tokens.get(i).upper()) && !followsStar(i)) {
                break;
            }
        }
        return -1;
    }

    private static Map<String, Integer> nameCounts(List<String> names) {
        Map<String, Integer> counts = new HashMap<>();
        for (String name : names) {
            counts.merge(name.toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        return counts;
    }

    /** The suffixes BigQuery generates for a select list's duplicates, which no reference can name. */
    private static Set<String> generatedNames(List<String> written) {
        Set<String> writtenNames = new HashSet<>();
        for (String name : written) {
            writtenNames.add(name.toLowerCase(Locale.ROOT));
        }
        Set<String> generated = new HashSet<>();
        for (String name : bigQueryOutputNames(written)) {
            if (!writtenNames.contains(name.toLowerCase(Locale.ROOT))) {
                generated.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return generated;
    }

    /** How many outputs this select list writes under each name, before suffixes are assigned. */
    private Map<String, Integer> writtenOutputNameCounts(int select, List<int[]> items) {
        Map<String, Integer> counts = new HashMap<>();
        for (int[] item : items) {
            if (expandsStar(item)) {
                List<String> fields = starItemFieldList(select, item);
                if (fields == null) {
                    continue;
                }
                for (String field : fields) {
                    counts.merge(field.toLowerCase(Locale.ROOT), 1, Integer::sum);
                }
                continue;
            }
            String name = outputNameText(item[0], item[1]);
            if (name != null) {
                counts.merge(name.toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
        }
        return counts;
    }

    /** The names the top-level select list writes, in order, or null when one cannot be derived. */
    private List<String> topLevelOutputNames() {
        int select = firstTopLevelSelect();
        return select < 0 ? null : orderedWrittenOutputNames(select, selectItems(select));
    }

    /**
     * The names a select list writes in order, including a star's source names, or null when any of them
     * cannot be derived. Repeats are kept: BigQuery suffixes them per select list, not per source.
     */
    private List<String> orderedWrittenOutputNames(int select, List<int[]> items) {
        if (items == null || !writtenNameScopes.add(select)) {
            return null;
        }
        try {
            List<String> names = new ArrayList<>();
            for (int[] item : items) {
                if (expandsStar(item)) {
                    List<String> fields = starItemFieldList(select, item);
                    if (fields == null) {
                        return null;
                    }
                    names.addAll(fields);
                    continue;
                }
                String name = outputNameText(item[0], item[1]);
                if (name == null) {
                    return null;
                }
                names.add(name);
            }
            return names;
        } finally {
            writtenNameScopes.remove(select);
        }
    }

    /** True when the top-level select is already a positional projection this translator emitted. */
    private boolean positionalOutputNamesApplied() {
        int select = nextSignificant(0, tokens.size());
        if (select < 0 || !tokens.get(select).isKeyword("SELECT")) {
            return false;
        }
        int first = select + 1;
        while (first < tokens.size() && tokens.get(first).kind == Kind.SPACE) {
            first++;
        }
        return first < tokens.size() && tokens.get(first).kind == Kind.RAW
                && tokens.get(first).text.startsWith("#1");
    }

    /** The names written more than once, in select order. */
    private static List<String> duplicateNames(List<String> names) {
        if (names == null) {
            return List.of();
        }
        List<String> duplicates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String name : names) {
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                duplicates.add(name);
            }
        }
        return duplicates;
    }

    /**
     * Names a nested query's output the way BigQuery does when DuckDB would number a duplicate differently.
     * An outer reference such as {@code b_1} then reads the column BigQuery reads, which DuckDB would
     * otherwise bind to an earlier duplicate it renamed. The outermost query is left for the final wrap.
     */
    private void nameNestedDuplicateOutputs() {
        List<Integer> selects = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).isKeyword("SELECT") && parenthesisDepth(i) > 0) {
                selects.add(i);
            }
        }
        for (int index = selects.size() - 1; index >= 0; index--) {
            int select = selects.get(index);
            if (selectFollowsSetOperator(select)) {
                continue;
            }
            List<int[]> items = selectItems(select);
            if (items == null) {
                continue;
            }
            List<String> written = orderedWrittenOutputNames(select, items);
            if (written == null) {
                continue;
            }
            List<String> names = bigQueryOutputNames(written);
            if (names.equals(duckOutputNames(written))) {
                continue;
            }
            int end = structStarQueryEnd(select);
            List<Token> wrapped = new ArrayList<>();
            wrapped.addAll(new Lexer("SELECT ").tokenize());
            for (int column = 0; column < names.size(); column++) {
                if (column > 0) {
                    wrapped.addAll(commaSeparator());
                }
                wrapped.addAll(positionalColumn(column + 1, names.get(column)));
            }
            wrapped.addAll(new Lexer(" FROM (").tokenize());
            wrapped.addAll(tokens.subList(select, end));
            wrapped.addAll(new Lexer(") AS `" + structStarName("_floci_names") + "`").tokenize());
            tokens.subList(select, end).clear();
            tokens.addAll(select, wrapped);
        }
    }

    /**
     * Wraps the finished query in a positional projection with BigQuery's output names when DuckDB would
     * show different ones. BigQuery expands a star to the names its source select list wrote, so
     * {@code SELECT * FROM (SELECT 5 AS b, 7 AS b, 6 AS b_1)} is {@code b, b_2, b_1}, while DuckDB numbers
     * a derived table's duplicates in place. The projection keeps row order and every clause inside it.
     */
    private void wrapDuplicateOutputNames() {
        if (topLevelOutputNames == null || outputNamesHandled || positionalOutputNamesApplied()) {
            return;
        }
        List<String> names = bigQueryOutputNames(topLevelOutputNames);
        if (names.equals(duckOutputNames(topLevelOutputNames))) {
            return;
        }
        List<Token> wrapped = new ArrayList<>();
        wrapped.addAll(new Lexer("SELECT ").tokenize());
        for (int index = 0; index < names.size(); index++) {
            if (index > 0) {
                wrapped.addAll(commaSeparator());
            }
            wrapped.addAll(positionalColumn(index + 1, names.get(index)));
        }
        wrapped.addAll(new Lexer(" FROM (").tokenize());
        wrapped.addAll(tokens);
        wrapped.addAll(new Lexer(") AS `" + structStarName("_floci_names") + "`").tokenize());
        tokens.clear();
        tokens.addAll(wrapped);
    }

    /** The written names a star select item expands to, in order, or null when they cannot be derived. */
    private List<String> starItemFieldList(int select, int[] item) {
        List<String> fields = starSourceWrittenColumns(select, item);
        if (fields == null) {
            return null;
        }
        int star = projectionStarIndex(item);
        int clause = nextSignificant(star + 1, item[1]);
        if (clause < 0 || !tokens.get(clause).isKeyword("EXCEPT")) {
            return fields;
        }
        int open = nextSignificant(clause + 1, item[1]);
        if (open < 0 || !tokens.get(open).isPunct("(")) {
            // EXCEPT starts a set operation here, not an EXCEPT list.
            return fields;
        }
        int close = matchingParen(open);
        if (close < 0) {
            return null;
        }
        Set<String> excluded = new HashSet<>();
        for (Token token : significant(open + 1, close)) {
            if (token.kind == Kind.IDENT || token.kind == Kind.QIDENT) {
                excluded.add(token.identifierText().toLowerCase(Locale.ROOT));
            }
        }
        List<String> remaining = new ArrayList<>(fields.size());
        for (String field : fields) {
            if (!excluded.contains(field.toLowerCase(Locale.ROOT))) {
                remaining.add(field);
            }
        }
        return remaining;
    }

    /** The written names a star's source exposes, before its own EXCEPT list removes any. */
    private List<String> starSourceWrittenColumns(int select, int[] item) {
        int star = projectionStarIndex(item);
        if (star < 0) {
            return null;
        }
        int dot = significantBefore(star);
        int last = dot >= 0 && tokens.get(dot).isPunct(".") ? significantBefore(dot) : -1;
        int beforeLast = last < 0 ? -1 : significantBefore(last);
        if (last >= 0 && beforeLast >= 0 && tokens.get(beforeLast).isPunct(".")) {
            Set<String> structFields = operandFields(select, starOperandStart(last), dot);
            return structFields == null ? null : new ArrayList<>(structFields);
        }
        if (last >= 0 && dot >= 0 && tokens.get(dot).isPunct(".")) {
            List<String> qualified = qualifiedStarWrittenColumns(select, star);
            if (qualified != null) {
                return qualified;
            }
            Set<String> structFields = operandFields(select, starOperandStart(last), dot);
            return structFields == null ? null : new ArrayList<>(structFields);
        }
        return unqualifiedStarWrittenColumns(select, star);
    }

    /** The written names of the single source a {@code t.*} item names, or null when it has none. */
    private List<String> qualifiedStarWrittenColumns(int select, int star) {
        if (pivotInFrom(select, parenthesisDepth(select))) {
            return null;
        }
        List<ProjectionSource> sources = projectionSourcesForStar(star);
        return sources.size() == 1 ? sourceWrittenColumns(sources.getFirst(), select) : null;
    }

    /**
     * The written names a bare {@code *} expands to. Without a USING join the expansions concatenate in
     * FROM order, which is also the order positional references index; a USING merge reorders them, so
     * only a single source is resolved there.
     */
    private List<String> unqualifiedStarWrittenColumns(int select, int star) {
        int depth = parenthesisDepth(select);
        List<StarColumn> unpivot = unpivotStarColumns(select);
        if (unpivot != null) {
            return starColumnNames(unpivot);
        }
        if (pivotInFrom(select, depth)) {
            return pivotOutputNames(select);
        }
        if (hasUsingClause(select)) {
            return usingOnlyJoins(select, depth) ? usingStarColumns(select, star) : null;
        }
        List<ProjectionSource> sources = projectionSourcesForStar(star);
        List<String> columns = new ArrayList<>();
        for (ProjectionSource source : sources) {
            List<String> sourceColumns = sourceWrittenColumns(source, select);
            if (sourceColumns == null) {
                return null;
            }
            columns.addAll(sourceColumns);
        }
        return columns.isEmpty() ? null : columns;
    }

    /**
     * The names a source contributes to a {@code *}, in order and with repeats kept. A subquery or CTE
     * contributes the names its own select list wrote, which is what BigQuery expands and re-suffixes,
     * not the names DuckDB happens to give the derived table.
     */
    private List<String> sourceWrittenColumns(ProjectionSource source, int position) {
        if (source.directTable()) {
            if (source.wildcardTable()) {
                return null;
            }
            TableRef ref = projectionSourceTable(source.startToken());
            return ref == null ? null : tableWrittenColumns(ref);
        }
        Token name = tokens.get(source.startToken());
        if (isName(name)) {
            int select = cteSelect(name.identifierText(), position);
            return select < 0 ? null : orderedWrittenOutputNames(select, selectItems(select));
        }
        int inner = nextSignificant(source.startToken() + 1, tokens.size());
        if (inner >= 0 && tokens.get(inner).isKeyword("SELECT")) {
            return orderedWrittenOutputNames(inner, selectItems(inner));
        }
        return null;
    }

    private List<String> tableWrittenColumns(TableRef ref) {
        List<TableFieldSchema> fields = fieldsOf.apply(ref);
        if (fields != null) {
            List<String> names = new ArrayList<>(fields.size());
            for (TableFieldSchema field : fields) {
                names.add(field.getName());
            }
            return names;
        }
        Set<String> columns = columnsOf.apply(ref);
        return columns == null ? null : new ArrayList<>(columns);
    }

    /**
     * BigQuery names duplicate outputs in select order after reserving every name the select list writes,
     * so a duplicate takes the first free {@code _N} suffix and a later written name keeps its spelling.
     */
    static List<String> bigQueryOutputNames(List<String> written) {
        Set<String> taken = new HashSet<>();
        for (String name : written) {
            taken.add(name.toLowerCase(Locale.ROOT));
        }
        Set<String> claimed = new HashSet<>();
        List<String> names = new ArrayList<>(written.size());
        for (String name : written) {
            if (claimed.add(name.toLowerCase(Locale.ROOT))) {
                names.add(name);
                continue;
            }
            String suffixed = suffixedName(name, taken);
            claimed.add(suffixed.toLowerCase(Locale.ROOT));
            names.add(suffixed);
        }
        return names;
    }

    /**
     * The names DuckDB gives a derived table's duplicate outputs: the first keeps its name and a later one
     * takes the next free {@code _N} suffix without reserving names that come later in the list.
     */
    private static List<String> duckOutputNames(List<String> written) {
        Set<String> used = new HashSet<>();
        List<String> names = new ArrayList<>(written.size());
        for (String name : written) {
            names.add(used.add(name.toLowerCase(Locale.ROOT)) ? name : suffixedName(name, used));
        }
        return names;
    }

    private String structStarName(String name) {
        return structStarTakenNames.add(name.toLowerCase(Locale.ROOT))
                ? name : suffixedName(name, structStarTakenNames);
    }

    private void reserveStructStarNames(int start, int end) {
        structStarTakenNames.clear();
        for (Token token : tokens.subList(start, end)) {
            if (isName(token)) {
                structStarTakenNames.add(token.identifierText().toLowerCase(Locale.ROOT));
            }
        }
    }

    /** The field names a REPLACE list targets, from the {@code AS <name>} of each of its entries. */
    private Set<String> replaceTargets(int open, int close) {
        Set<String> names = new HashSet<>();
        for (String name : replaceTargetNames(open, close)) {
            names.add(name.toLowerCase(Locale.ROOT));
        }
        return names;
    }

    /** The fields a REPLACE list names, as written and in order. */
    private List<String> replaceTargetNames(int open, int close) {
        List<String> names = new ArrayList<>();
        if (open < 0) {
            return names;
        }
        int depth = 0;
        for (int i = open + 1; i < close; i++) {
            Token token = tokens.get(i);
            if (token.isPunct("(") || token.isPunct("[")) {
                depth++;
                continue;
            }
            if (token.isPunct(")") || token.isPunct("]")) {
                depth--;
                continue;
            }
            if (depth != 0 || !token.isKeyword("AS")) {
                // An alias inside a nested query names a column of that query, not a replaced field.
                continue;
            }
            int name = nextSignificant(i + 1, close);
            if (name >= 0 && isName(tokens.get(name))) {
                names.add(tokens.get(name).identifierText());
            }
        }
        return names;
    }

    /** The select items that read exactly one source column, so a REPLACE expression can reuse them. */
    private List<ItemColumn> itemColumns(List<int[]> items, List<String> innerNames, int targetItem, int exclude) {
        List<ItemColumn> columns = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            String name = innerNames.get(index);
            if (index == targetItem || index == exclude || name == null) {
                continue;
            }
            int[] item = items.get(index);
            int as = topLevelAs(item[0], item[1]);
            int alias = as >= 0 ? as : implicitSelectAlias(item[0], item[1]);
            String path = sourcePath(significant(item[0], alias >= 0 ? alias : item[1]));
            if (path != null) {
                columns.add(new ItemColumn(path, name));
            }
        }
        return columns;
    }

    /** The {@code alias.column} path an expression reads, or null when it is something else. */
    private static String sourcePath(List<Token> expression) {
        if (expression.size() != 3 || !isName(expression.get(0)) || !expression.get(1).isPunct(".")
                || !isName(expression.get(2))) {
            return null;
        }
        return expression.get(0).identifierText().toLowerCase(Locale.ROOT) + "."
                + expression.get(2).identifierText().toLowerCase(Locale.ROOT);
    }

    /** The output name a select item gives the same source column, or null when no item reads exactly it. */
    private static String selectedColumn(List<ItemColumn> itemColumns, String qualifier, String column) {
        String path = qualifier.toLowerCase(Locale.ROOT) + "." + column.toLowerCase(Locale.ROOT);
        String name = null;
        for (ItemColumn item : itemColumns) {
            if (!item.path().equals(path)) {
                continue;
            }
            if (name != null) {
                return null;
            }
            name = item.name();
        }
        return name;
    }

    /** The projection a bare column reference needs, or null when no single known source supplies it. */
    private String bareSourceReference(StructStarSources sources, String name,
                                       Map<String, SourceReference> sourceReferences) {
        String key = name.toLowerCase(Locale.ROOT);
        List<String> merged = sources.usingSources().get(key);
        if (merged != null && merged.size() > 1) {
            // USING merges the column, and an outer join fills it from whichever side has a row.
            return mergedSourceReference(merged, name, sourceReferences);
        }
        String qualifier = null;
        for (Map.Entry<String, Set<String>> source : sources.columnsByAlias().entrySet()) {
            if (!source.getValue().contains(key)) {
                continue;
            }
            if (qualifier != null) {
                return null;
            }
            qualifier = source.getKey();
        }
        return qualifier == null ? null : sourceReference(qualifier, name, sourceReferences);
    }

    private String sourceReference(String qualifier, String column,
                                   Map<String, SourceReference> sourceReferences) {
        String key = qualifier.toLowerCase(Locale.ROOT) + "." + column.toLowerCase(Locale.ROOT);
        SourceReference reference = sourceReferences.get(key);
        if (reference == null) {
            reference = new SourceReference(qualifier, column, nextSourceAlias(sourceReferences));
            sourceReferences.put(key, reference);
        }
        return reference.alias();
    }

    private String mergedSourceReference(List<String> participants, String column,
                                         Map<String, SourceReference> sourceReferences) {
        String key = "using:" + column.toLowerCase(Locale.ROOT);
        SourceReference reference = sourceReferences.get(key);
        if (reference == null) {
            String expression = participants.stream()
                    .map(alias -> DuckTypes.quoteIdentifier(alias) + "." + DuckTypes.quoteIdentifier(column))
                    .collect(Collectors.joining(", ", "COALESCE(", ")"));
            reference = new SourceReference(participants.getFirst(), column, nextSourceAlias(sourceReferences),
                    expression);
            sourceReferences.put(key, reference);
        }
        return reference.alias();
    }

    private String nextSourceAlias(Map<String, SourceReference> sourceReferences) {
        String alias;
        int index = sourceReferences.size();
        do {
            alias = "_floci_source_" + index++;
        } while (!structStarTakenNames.add(alias.toLowerCase(Locale.ROOT)));
        return alias;
    }

    /** The projection that carries the whole STRUCT the star expands, so a REPLACE expression can use it. */
    private String wholeStructReference(int targetStart, int targetDot, Map<String, StructProjection> projections) {
        return projectionFor(tokens.subList(targetStart, targetDot), projections);
    }

    /** A projection that carries {@code path} as written, so an outer expression can still read it. */
    private String projectionFor(List<Token> path, Map<String, StructProjection> projections) {
        StringBuilder key = new StringBuilder();
        for (Token token : path) {
            if (token.kind != Kind.SPACE) {
                key.append(token.identifierText().toLowerCase(Locale.ROOT)).append(' ');
            }
        }
        String name = key.toString();
        StructProjection projection = projections.get(name);
        if (projection == null) {
            String alias;
            int index = projections.size();
            do {
                alias = "_floci_struct_" + index++;
            } while (!structStarTakenNames.add(alias.toLowerCase(Locale.ROOT)));
            projection = new StructProjection(alias, new ArrayList<>(path));
            projections.put(name, projection);
        }
        return projection.alias();
    }

    /** True for a SELECT or WITH token, including a rewritten select list kept as one raw token. */
    private static boolean startsQuery(Token token) {
        if (token.isKeyword("SELECT") || token.isKeyword("WITH")) {
            return true;
        }
        if (token.kind != Kind.RAW) {
            return false;
        }
        String text = token.text.strip().toUpperCase(Locale.ROOT);
        return text.startsWith("SELECT") || text.startsWith("WITH");
    }

    /**
     * The closing parenthesis of a nested query that starts at {@code open}, or -1 when the parenthesis
     * opens something else. A nested query resolves its own names, so the rewrite leaves it untouched.
     */
    private int nestedQueryClose(int open, int end) {
        int next = nextNonSpace(open + 1, end);
        if (next < 0 || !startsQuery(tokens.get(next))) {
            return -1;
        }
        int close = matchingParen(open);
        return close < 0 || close >= end ? -1 : close;
    }

    /** The index after {@code path} when it starts at {@code start}, or -1. */
    private int matchPath(int start, int end, List<Token> path) {
        int position = start;
        for (Token expected : path) {
            position = nextSignificant(position, end);
            if (position < 0) {
                return -1;
            }
            Token actual = tokens.get(position);
            boolean matches = expected.isPunct(".")
                    ? actual.isPunct(".")
                    : isName(actual) && isName(expected)
                            && actual.identifierText().equalsIgnoreCase(expected.identifierText());
            if (!matches) {
                return -1;
            }
            position++;
        }
        return position;
    }

    /**
     * Rewrites {@code <struct expression>.<field>} to the bare output column {@code field}. BigQuery accepts that
     * reference after {@code SELECT DISTINCT} because the field is one of the projected columns. Returns the
     * number of tokens removed, or -1 when the item is something else.
     */
    private int rewriteStructFieldOrderItem(int start, int end, int targetStart, int targetDot,
                                            Set<String> replacedNames,
                                            Map<String, StructProjection> structProjections) {
        int first = nextSignificant(start, end);
        if (first < 0) {
            return -1;
        }
        int matched = matchPath(first, end, significant(targetStart, targetDot));
        int dot = matched < 0 ? -1 : nextSignificant(matched, end);
        int field = dot < 0 ? -1 : nextSignificant(dot + 1, end);
        if (field < 0 || !tokens.get(dot).isPunct(".") || !isName(tokens.get(field))) {
            return -1;
        }
        for (Token token : significant(field + 1, end)) {
            if (!isOrderModifier(token)) {
                return -1;
            }
        }
        String name = tokens.get(field).identifierText();
        String replacement = replacedNames.contains(name.toLowerCase(Locale.ROOT))
                // BigQuery sorts this path by the struct's field, not by the value REPLACE put there.
                ? projectionFor(tokens.subList(first, field + 1), structProjections)
                : name;
        tokens.subList(first, field).clear();
        tokens.set(first, Token.raw(DuckTypes.quoteIdentifier(replacement)));
        return field - first;
    }

    private static boolean isOrderModifier(Token token) {
        return token.kind == Kind.IDENT && Set.of("ASC", "DESC", "NULLS", "FIRST", "LAST").contains(token.upper());
    }

    /** True when an ORDER BY item names one of the fields the star expands. */
    private boolean orderNamesStarOutput(int start, int end, int queryDepth, Set<String> starFields,
                                         Set<String> excludedNames) {
        if (starFields == null) {
            return false;
        }
        int orderStart = -1;
        for (int i = start; i < end; i++) {
            if (parenthesisDepth(i) != queryDepth || !tokens.get(i).isKeyword("ORDER") || isPrecededByDot(i)) {
                continue;
            }
            int by = nextSignificant(i + 1, end);
            if (by >= 0 && tokens.get(by).isKeyword("BY")) {
                orderStart = by + 1;
                break;
            }
        }
        if (orderStart < 0) {
            return false;
        }
        int orderEnd = end;
        for (int i = orderStart; i < end; i++) {
            if (parenthesisDepth(i) == queryDepth && !isPrecededByDot(i)
                    && (tokens.get(i).isKeyword("LIMIT") || tokens.get(i).isKeyword("OFFSET"))) {
                orderEnd = i;
                break;
            }
        }
        for (int i = orderStart; i < orderEnd; i++) {
            Token token = tokens.get(i);
            if ((token.kind != Kind.IDENT && token.kind != Kind.QIDENT) || isPrecededByDot(i)) {
                continue;
            }
            int next = nextSignificant(i + 1, orderEnd);
            if (next >= 0 && tokens.get(next).isPunct("(")) {
                // A call names a function; only a value can be an expanded field.
                continue;
            }
            String name = token.identifierText().toLowerCase(Locale.ROOT);
            if (starFields.contains(name) && !excludedNames.contains(name)) {
                // BigQuery reads this name as the select output, which only exists outside the wrapper.
                return true;
            }
        }
        return false;
    }

    /** The tokens an ORDER BY rewrite removed, negative when it inserted more than it removed. */
    private record OrderRewrite(int removed) {}

    /**
     * Sorts {@code ORDER BY <replaced field>.<path>} by the replacement value. BigQuery resolves the name as the
     * SELECT output; DuckDB still sees the struct field the wrapper expands, so the replacement expression is
     * rendered in its place. An aggregate replacement stays with its grouping and is not rewritten here.
     */
    private OrderRewrite rewriteReplacedFieldPathOrderItem(int start, int end, int replaceOpen, int replaceClose,
                                                           int targetStart, int targetDot,
                                                           StructStarSources sources, List<ItemColumn> itemColumns,
                                                           Map<String, SourceReference> sourceReferences,
                                                           Map<String, StructProjection> structProjections) {
        if (replaceOpen < 0) {
            return null;
        }
        int first = nextSignificant(start, end);
        if (first < 0 || !isName(tokens.get(first)) || isPrecededByDot(first)) {
            return null;
        }
        String name = tokens.get(first).identifierText();
        int dot = nextSignificant(first + 1, end);
        int field = dot < 0 ? -1 : nextSignificant(dot + 1, end);
        if (field < 0 || !tokens.get(dot).isPunct(".") || !isName(tokens.get(field))) {
            return null;
        }
        int pathEnd = pathEnd(field, end);
        for (Token token : significant(pathEnd + 1, end)) {
            if (!isOrderModifier(token)) {
                return null;
            }
        }
        int[] expression = replaceExpressionRange(replaceOpen, replaceClose, name);
        if (expression == null || structStarAggregates(expression[0], expression[1])) {
            return null;
        }
        List<Token> rendered = structStarReplacementRange(expression[0], expression[1], targetStart, targetDot,
                sources, itemColumns, sourceReferences, structProjections, false);
        List<Token> replacement = new ArrayList<>();
        replacement.addAll(new Lexer("(").tokenize());
        replacement.addAll(withoutTrailingSpace(rendered));
        replacement.addAll(new Lexer(")").tokenize());
        replacement.addAll(tokens.subList(dot, pathEnd + 1));
        int removed = pathEnd + 1 - first - replacement.size();
        tokens.subList(first, pathEnd + 1).clear();
        tokens.addAll(first, replacement);
        return new OrderRewrite(removed);
    }

    /** The expression of the REPLACE entry that writes {@code name}, as an [start, AS) range. */
    private int[] replaceExpressionRange(int open, int close, String name) {
        for (int[] range : replaceEntryRanges(open + 1, close)) {
            int as = topLevelAs(range[0], range[1]);
            if (as >= 0) {
                int alias = nextSignificant(as + 1, range[1]);
                if (alias >= 0 && isName(tokens.get(alias))
                        && tokens.get(alias).identifierText().equalsIgnoreCase(name)) {
                    return new int[] {range[0], as};
                }
            }
        }
        return null;
    }

    /** Splits a REPLACE list at its top-level commas, keeping array and struct literals together. */
    private List<int[]> replaceEntryRanges(int start, int close) {
        List<int[]> ranges = new ArrayList<>();
        int parens = 0;
        int brackets = 0;
        int braces = 0;
        int entryStart = start;
        for (int i = start; i <= close; i++) {
            boolean boundary = i == close;
            if (!boundary) {
                Token token = tokens.get(i);
                if (token.isPunct("(")) {
                    parens++;
                    continue;
                }
                if (token.isPunct(")")) {
                    parens--;
                    continue;
                }
                if (token.isPunct("[")) {
                    brackets++;
                    continue;
                }
                if (token.isPunct("]")) {
                    brackets--;
                    continue;
                }
                if (token.isPunct("{")) {
                    braces++;
                    continue;
                }
                if (token.isPunct("}")) {
                    braces--;
                    continue;
                }
                boundary = parens == 0 && brackets == 0 && braces == 0 && token.isPunct(",");
            }
            if (!boundary) {
                continue;
            }
            ranges.add(new int[] {entryStart, i});
            entryStart = i + 1;
        }
        return ranges;
    }

    /** Points an unqualified reference to a renamed select output at the projection alias it is stored under. */
    private void rewriteRenamedOutputReferences(int start, int end, Map<String, String> renameTargets) {
        if (renameTargets.isEmpty()) {
            return;
        }
        for (int i = start; i < end; i++) {
            Token token = tokens.get(i);
            if (token.kind != Kind.IDENT && token.kind != Kind.QIDENT) {
                continue;
            }
            String alias = renameTargets.get(token.identifierText().toLowerCase(Locale.ROOT));
            if (alias == null || isPrecededByDot(i)) {
                continue;
            }
            int next = nextSignificant(i + 1, end);
            if (next >= 0 && (tokens.get(next).isPunct(".") || tokens.get(next).isPunct("("))) {
                continue;
            }
            tokens.set(i, Token.raw(DuckTypes.quoteIdentifier(alias)));
        }
    }

    private void validateReplacementNames(int start, int end, int starQuery) {
        for (int i = start; i < end; i++) {
            if (!bareOuterName(i, end, true) || enclosingQuerySelect(i) != starQuery) {
                continue;
            }
            validateReplacementName(starQuery, tokens.get(i).identifierText());
        }
    }

    private void validateReplacementName(int starQuery, String name) {
        List<String> owners = sourceAliasesFor(starQuery, name);
        if (owners.size() == 1) {
            return;
        }
        if (owners.size() > 1) {
            if (!hasUsingClause(starQuery)) {
                throw invalidQuery("Column name " + name + " is ambiguous");
            }
            return;
        }
        for (int outer = parentQuerySelect(starQuery); outer >= 0; outer = parentQuerySelect(outer)) {
            List<String> outerOwners = sourceAliasesFor(outer, name);
            if (outerOwners.size() == 1) {
                return;
            }
            if (outerOwners.size() > 1) {
                throw invalidQuery("Column name " + name + " is ambiguous");
            }
        }
        if (enclosingQueryParen(starQuery) >= 0) {
            List<String> statementOwners = statementAliasesFor(name);
            if (statementOwners.size() == 1) {
                return;
            }
            if (statementOwners.size() > 1) {
                throw invalidQuery("Column name " + name + " is ambiguous");
            }
        }
        if (querySourcesKnown(starQuery)) {
            throw invalidQuery("Unrecognized name: " + name);
        }
    }

    /** The aliases of the query at {@code select} that supply {@code name}. */
    private List<String> sourceAliasesFor(int select, String name) {
        List<String> owners = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : querySources(select).columnsByAlias().entrySet()) {
            if (entry.getValue().contains(name.toLowerCase(Locale.ROOT))) {
                owners.add(entry.getKey());
            }
        }
        return owners;
    }

    private boolean querySourcesKnown(int select) {
        return querySources(select).allKnown();
    }

    /** The aliases of the statement itself, which is not reached through a parenthesized query. */
    private List<String> statementAliasesFor(String name) {
        List<String> owners = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : structStarSources(0, tokens.size(), 0)
                .columnsByAlias().entrySet()) {
            if (entry.getValue().contains(name.toLowerCase(Locale.ROOT))) {
                owners.add(entry.getKey());
            }
        }
        return owners;
    }

    private StructStarSources querySources(int select) {
        return structStarSources(select + 1, queryEnd(select), parenthesisDepth(select));
    }

    /** True when the query joins with {@code USING}, where two sources legitimately share a column. */
    private boolean hasUsingClause(int select) {
        int depth = parenthesisDepth(select);
        for (int i = select + 1; i < queryEnd(select); i++) {
            if (parenthesisDepth(i) == depth && tokens.get(i).isKeyword("USING")) {
                return true;
            }
        }
        return false;
    }

    /** The token index of the SELECT (or WITH) of the innermost query that encloses {@code index}. */
    private int enclosingQuerySelect(int index) {
        int open = enclosingQueryParen(index);
        return open < 0 ? -1 : nextSignificant(open + 1, tokens.size());
    }

    /** The parenthesized query that encloses {@code index}, or -1 when the statement itself does. */
    private int enclosingQueryParen(int index) {
        for (int i = index; i >= 0; i--) {
            if (!tokens.get(i).isPunct("(") || matchingParen(i) < index) {
                continue;
            }
            int next = nextSignificant(i + 1, tokens.size());
            if (next >= 0 && (tokens.get(next).isKeyword("SELECT") || tokens.get(next).isKeyword("WITH"))) {
                return i;
            }
        }
        return -1;
    }

    /** The query that encloses the query at {@code select}, or -1 when there is none. */
    private int parentQuerySelect(int select) {
        int open = enclosingQueryParen(select);
        return open < 0 ? -1 : enclosingQuerySelect(open - 1);
    }

    /** The end of the query whose SELECT is at {@code select}. */
    private int queryEnd(int select) {
        for (int i = select; i >= 0; i--) {
            if (tokens.get(i).isPunct("(") && matchingParen(i) > select) {
                return matchingParen(i);
            }
        }
        return tokens.size();
    }

    private GcpException unsupportedStructStarExclusion() {
        return invalidQuery("Dot-star with EXCEPT on a STRUCT expression is not supported by the floci BigQuery"
                + " emulator yet.");
    }

    /**
     * A struct star over a dotted path, as in {@code t.s.* EXCEPT (a)}, cannot carry the exclusion inside
     * DuckDB's {@code COLUMNS}, so the path is expanded through a derived table first. Returns null for any
     * other star, which the caller renders directly.
     */
    private String dottedStructStarProjection(int start, int end, ProjectionSource correlatedSource) {
        int star = -1;
        for (int i = start; i < end; i++) {
            if (tokens.get(i).isPunct("*")) {
                star = i;
                break;
            }
        }
        int dot = star < 0 ? -1 : significantBefore(star);
        if (star < 0 || dot < 0 || !tokens.get(dot).isPunct(".")) {
            return null;
        }
        int pathEnd = significantBefore(dot);
        int pathStart = starOperandStart(pathEnd);
        int select = precedingSelect(start, parenthesisDepth(start));
        if (select >= 0) {
            validateAggregateStarOperand(select, pathStart, dot);
        }
        boolean dottedPath = false;
        for (int i = pathStart; i >= 0 && i <= pathEnd; i++) {
            dottedPath |= tokens.get(i).isPunct(".");
        }
        if (pathStart != nextSignificant(start, end) || !dottedPath) {
            return null;
        }
        String exclusion = null;
        String replacement = null;
        List<String> replacementValues = new ArrayList<>();
        int position = nextSignificant(star + 1, end);
        while (position >= 0 && position < end) {
            int open = nextSignificant(position + 1, end);
            if (open < 0 || !tokens.get(open).isPunct("(")) {
                return null;
            }
            int close = matchingParen(open);
            if (close < 0) {
                return null;
            }
            if (tokens.get(position).isKeyword("EXCEPT") && exclusion == null) {
                exclusion = starExclusionList(star, open, close, correlatedSource);
            } else if (tokens.get(position).isKeyword("REPLACE") && replacement == null) {
                if (enclosingQuerySelect(start) < 0) {
                    return null;
                }
                validateReplacementNames(open + 1, close, enclosingQuerySelect(start));
                List<String> replacements = new ArrayList<>();
                for (int[] item : replaceEntryRanges(open + 1, close)) {
                    int as = topLevelAs(item[0], item[1]);
                    int field = as < 0 ? -1 : nextSignificant(as + 1, item[1]);
                    if (field < 0 || !isName(tokens.get(field))) {
                        throw invalidQuery("Syntax error: Expected expression AS identifier in SELECT * REPLACE");
                    }
                    String alias = DuckTypes.quoteIdentifier("_floci_replace_" + replacementValues.size());
                    replacementValues.add(render(item[0], as).trim() + " AS " + alias);
                    replacements.add("\"_floci_replacements\"." + alias + " AS "
                            + DuckTypes.quoteIdentifier(tokens.get(field).identifierText()));
                }
                replacement = " REPLACE (" + String.join(", ", replacements) + ")";
            } else {
                return null;
            }
            position = nextSignificant(close + 1, end);
        }
        if (exclusion == null && replacement == null) {
            return null;
        }
        if (replacement != null) {
            // Evaluate replacements before expanding fields, so no expanded field can capture a source
            // reference, including a correlated name inside a nested query.
            return "(SELECT struct_pack(*COLUMNS(\"_floci_struct_fields\".*"
                    + (exclusion == null ? "" : exclusion) + replacement
                    + ")) FROM (SELECT " + render(pathStart, pathEnd + 1) + " AS \"_floci_operand\", "
                    + String.join(", ", replacementValues) + ") AS \"_floci_replacements\","
                    + " LATERAL (SELECT unnest(\"_floci_replacements\".\"_floci_operand\"))"
                    + " AS \"_floci_struct_fields\")";
        }
        return "(SELECT struct_pack(*COLUMNS(*" + (exclusion == null ? "" : exclusion)
                + (replacement == null ? "" : replacement)
                + ")) FROM (SELECT unnest(" + render(pathStart, pathEnd + 1) + ")) AS \"_floci_struct_source\")";
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
            int select = precedingSelect(i, parenthesisDepth(i));
            if (select >= 0) {
                validateAggregateStarOperand(select, start, dot);
            }
            int after = nextSignificant(i + 1, tokens.size());
            int afterNext = after >= 0 ? nextSignificant(after + 1, tokens.size()) : -1;
            if (after >= 0 && (tokens.get(after).isKeyword("EXCEPT") || tokens.get(after).isKeyword("REPLACE"))
                    && afterNext >= 0 && tokens.get(afterNext).isPunct("(")) {
                if (tokens.get(after).isKeyword("EXCEPT")) {
                    throw unsupportedStructStarExclusion();
                }
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
            // DuckDB infers anonymous struct member names for e.g. [(id_type, company_id)].
            // Keep the GoogleSQL element type as a cast so ARRAY<STRUCT<type STRING,
            // company_id INT64>>[(...)] retains its declared field names. An empty literal
            // keeps the same tokens, so a subscript on it is still read as an OFFSET.
            String translatedType = translateType(type);
            tokens.subList(i, openBracket).clear();
            tokens.add(i, new Token(Kind.IDENT, "CAST", "CAST"));
            tokens.add(i + 1, new Token(Kind.PUNCT, "(", null));
            int castEnd = matchingBracket(i + 2) + 1;
            tokens.add(castEnd, Token.raw(" AS " + translatedType));
            tokens.add(castEnd + 1, new Token(Kind.PUNCT, ")", null));
        }
    }

    /**
     * Rewrites a typed struct constructor, {@code STRUCT<n INT64, m STRING>(x, 'a')}, to a DuckDB struct literal
     * with each value cast to its declared type, {@code {'n': CAST(x AS BIGINT), 'm': CAST('a' AS VARCHAR)}}. A
     * type written without a name gets BigQuery's {@code _field_1}, {@code _field_2}, ... The literal form is one
     * {@link #structSelectAliases} reads, so {@code ORDER BY s.n} works on it. A value count that differs from the
     * type list, or a missing value, fails with BigQuery's message.
     */
    private void rewriteTypedStructConstructors() {
        for (int i = tokens.size() - 1; i >= 0; i--) {
            if (!tokens.get(i).isKeyword("STRUCT")) {
                continue;
            }
            int openAngle = nextSignificant(i + 1, tokens.size());
            if (openAngle < 0 || !tokens.get(openAngle).isPunct("<")) {
                continue;
            }
            int closeAngle = matchingTypeAngle(openAngle);
            int open = closeAngle < 0 ? -1 : nextSignificant(closeAngle + 1, tokens.size());
            if (open < 0 || !tokens.get(open).isPunct("(")) {
                continue;
            }
            int close = matchingParen(open);
            List<List<Token>> types = topLevelItems(openAngle + 1, closeAngle, true);
            for (int extra = 1; extra < tokens.get(closeAngle).text.length() && !types.isEmpty(); extra++) {
                // A closing ">>" also ends the last field's own type.
                types.getLast().add(new Token(Kind.PUNCT, ">", null));
            }
            List<List<Token>> values = topLevelItems(open + 1, close, false);
            if (values.size() > 1 && values.contains(List.of())) {
                // BigQuery's parser rejects a missing value: STRUCT<n INT64>(,1) or STRUCT<n INT64>(1,).
                boolean trailing = values.indexOf(List.of()) == values.size() - 1;
                throw invalidQuery("Syntax error: Unexpected \"" + (trailing ? ")" : ",") + "\"");
            }
            values.removeIf(List::isEmpty);
            if (types.isEmpty()) {
                continue;
            }
            if (types.size() != values.size()) {
                throw invalidQuery("STRUCT type has " + types.size() + " fields but constructor call has "
                        + values.size() + " fields");
            }
            Set<String> names = new HashSet<>();
            List<Token> literal = new ArrayList<>(new Lexer("{").tokenize());
            for (int k = 0; k < types.size(); k++) {
                List<Token> type = types.get(k);
                boolean named = type.size() >= 2 && (type.get(0).kind == Kind.IDENT || type.get(0).kind == Kind.QIDENT)
                        && !type.get(1).isPunct("<") && !type.get(1).isPunct("(");
                // A repeated declared name, STRUCT<n INT64, n STRING>, gives the later field BigQuery's _field_N.
                String name = named && names.add(type.get(0).identifierText().toLowerCase(Locale.ROOT))
                        ? type.get(0).identifierText() : "_field_" + (k + 1);
                if (k > 0) {
                    literal.addAll(new Lexer(", ").tokenize());
                }
                literal.addAll(new Lexer(DuckTypes.quoteLiteral(name) + ": CAST(").tokenize());
                literal.addAll(values.get(k));
                literal.add(Token.raw(" AS " + translateType(named ? type.subList(1, type.size()) : type)));
                literal.addAll(new Lexer(")").tokenize());
            }
            literal.addAll(new Lexer("}").tokenize());
            tokens.subList(i, close + 1).clear();
            tokens.addAll(i, literal);
        }
    }

    /**
     * The comma-separated items from {@code start} to {@code end}, splitting only outside brackets (and outside
     * type angles when {@code types}); a type item drops its spaces, a value item keeps its inner ones.
     */
    private List<List<Token>> topLevelItems(int start, int end, boolean types) {
        List<List<Token>> items = new ArrayList<>();
        List<Token> item = new ArrayList<>();
        int nesting = 0;
        for (int i = start; i < end; i++) {
            Token token = tokens.get(i);
            if (token.isPunct("(") || token.isPunct("[") || token.isPunct("{") || types && token.isPunct("<")) {
                nesting++;
            } else if (token.isPunct(")") || token.isPunct("]") || token.isPunct("}")) {
                nesting--;
            } else if (types && token.kind == Kind.PUNCT && token.text.matches(">+")) {
                nesting -= token.text.length();
            } else if (nesting == 0 && token.isPunct(",")) {
                items.add(item);
                item = new ArrayList<>();
                continue;
            }
            if (!types || token.kind != Kind.SPACE) {
                item.add(token);
            }
        }
        items.add(item);
        for (List<Token> each : items) {
            while (!each.isEmpty() && each.getFirst().kind == Kind.SPACE) {
                each.removeFirst();
            }
            while (!each.isEmpty() && each.getLast().kind == Kind.SPACE) {
                each.removeLast();
            }
        }
        if (types) {
            items.removeIf(List::isEmpty);
        }
        return items;
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
                int scalarOpen = enclosingParenthesis(select);
                int scalarClose = scalarOpen < 0 ? -1 : matchingParen(scalarOpen);
                int caller = scalarOpen < 0 ? -1 : significantBefore(scalarOpen);
                // ARRAY(SELECT AS STRUCT ...) is a one-element array, which is a list literal in DuckDB. The
                // star has to stay in this query's scope: a star needs a FROM, and the subquery has none.
                boolean arrayConstructor = caller >= 0 && tokens.get(caller).isKeyword("ARRAY");
                if (indexed == null && scalarOpen >= 0
                        && (arrayConstructor || isSubqueryParenthesis(scalarOpen))
                        && nextSignificant(scalarOpen + 1, tokens.size()) == select
                        && listEnd == scalarClose && hasBareStructSourceStar(itemStart, itemEnd)) {
                    ProjectionSource correlatedSource = correlatedStructProjectionSource(itemStart, itemEnd);
                    String star = renderSelectAsStructStar(itemStart, itemEnd, correlatedSource);
                    String projection = "struct_pack(*COLUMNS(" + star + "))";
                    int start = arrayConstructor ? caller : scalarOpen;
                    tokens.subList(start, scalarClose + 1).clear();
                    tokens.add(start, Token.raw(arrayConstructor ? "[" + projection + "]" : projection));
                    select = tokens.size();
                    continue;
                }
                String projection;
                if (indexed != null) {
                    projection = "(SELECT struct_pack(*COLUMNS(*)) FROM (SELECT unnest([" + indexed
                            + "], max_depth := 2)) AS \"_floci_indexed_struct\")";
                } else {
                    ProjectionSource correlatedSource = correlatedStructProjectionSource(itemStart, itemEnd);
                    String dotted = dottedStructStarProjection(itemStart, itemEnd, correlatedSource);
                    if (dotted != null) {
                        projection = dotted;
                    } else {
                        String star = renderSelectAsStructStar(itemStart, itemEnd, correlatedSource);
                        projection = "struct_pack(*COLUMNS(" + star + "))";
                    }
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

    /**
     * True when the parenthesis at {@code open} wraps a scalar subquery rather than a call or a predicate
     * such as {@code ARRAY(...)}, {@code IN (...)} or {@code EXISTS (...)}. Only the former can be replaced
     * by its projection, because the others need the subquery to stay a subquery.
     */
    private boolean isSubqueryParenthesis(int open) {
        int before = significantBefore(open);
        if (before < 0) {
            return true;
        }
        Token token = tokens.get(before);
        return (token.kind != Kind.IDENT && token.kind != Kind.QIDENT)
                || SUBSELECT_PREFIX_KEYWORDS.contains(token.upper());
    }

    private static final Set<String> SUBSELECT_PREFIX_KEYWORDS = Set.of("SELECT", "AS", "WITH", "FROM", "WHERE",
            "ON", "AND", "OR", "NOT", "BY", "THEN", "ELSE", "WHEN", "UNION", "INTERSECT", "EXCEPT", "SET",
            "VALUES", "RETURNING", "LATERAL", "DISTINCT", "ALL");

    private boolean hasBareStructSourceStar(int start, int end) {
        return bareStructSourceToken(start, end) >= 0;
    }

    private int bareStructSourceToken(int start, int end) {
        for (int star = start; star < end; star++) {
            if (!tokens.get(star).isPunct("*")) {
                continue;
            }
            int dot = significantBefore(star);
            int source = dot < 0 ? -1 : significantBefore(dot);
            int beforeSource = source < 0 ? -1 : significantBefore(source);
            if (dot >= 0 && tokens.get(dot).isPunct(".") && source >= 0
                    && (tokens.get(source).kind == Kind.IDENT || tokens.get(source).kind == Kind.QIDENT)
                    && (beforeSource < 0 || !tokens.get(beforeSource).isPunct("."))) {
                return source;
            }
            return -1;
        }
        return -1;
    }

    /**
     * The FROM source a correlated {@code SELECT AS STRUCT <alias>.*} reads its struct from. The enclosing
     * query of the subquery is searched first, then each query further out, because the alias belongs to
     * the query whose FROM the subquery is correlated with, which is not necessarily the outermost one.
     */
    private ProjectionSource correlatedStructProjectionSource(int start, int end) {
        int sourceToken = bareStructSourceToken(start, end);
        if (sourceToken < 0) {
            return null;
        }
        String sourceName = tokens.get(sourceToken).identifierText();
        for (int select : enclosingSelects(start)) {
            int depth = parenthesisDepth(select);
            int from = -1;
            for (int i = select + 1; i < tokens.size(); i++) {
                if (parenthesisDepth(i) < depth) {
                    break;
                }
                if (parenthesisDepth(i) == depth && isFromClause(i)) {
                    from = i;
                    break;
                }
            }
            if (from < 0) {
                continue;
            }
            for (ProjectionSource source : projectionSources(from, depth)) {
                if (source.alias() != null && source.alias().equalsIgnoreCase(sourceName)) {
                    return source;
                }
            }
        }
        return null;
    }

    /** The SELECT of the query containing {@code index}, then of each query enclosing that one, innermost first. */
    private List<Integer> enclosingSelects(int index) {
        List<Integer> selects = new ArrayList<>();
        int position = index;
        while (position >= 0) {
            int select = selectInScopeBefore(position);
            if (select >= 0) {
                selects.add(select);
            }
            // A parenthesis that wraps another one, as in ((SELECT ...)), introduces no query of its own, so
            // the search continues outside it instead of stopping.
            position = enclosingParenthesis(select >= 0 ? select : position);
        }
        return selects;
    }

    /**
     * The SELECT that owns the scope holding {@code index}. Parenthesized groups that end before
     * {@code index} belong to siblings and are skipped, so an earlier scalar subquery cannot answer for
     * the query the position really sits in.
     */
    private int selectInScopeBefore(int index) {
        int skip = 0;
        for (int i = index - 1; i >= 0; i--) {
            Token token = tokens.get(i);
            if (token.isPunct(")")) {
                skip++;
            } else if (token.isPunct("(")) {
                if (skip == 0) {
                    return -1;
                }
                skip--;
            } else if (skip == 0 && token.isKeyword("SELECT")) {
                return i;
            }
        }
        return -1;
    }

    private String renderSelectAsStructStar(int start, int end) {
        return renderSelectAsStructStar(start, end, null);
    }

    private String renderSelectAsStructStar(int start, int end, ProjectionSource correlatedSource) {
        int star = start;
        while (star < end && !tokens.get(star).isPunct("*")) {
            star++;
        }
        int except = nextSignificant(star + 1, end);
        if (star >= end) {
            return render(start, end).trim();
        }
        if (except < 0 || !tokens.get(except).isKeyword("EXCEPT")
                || !nextSignificantIs(except + 1, end, "(")) {
            List<String> hidden = new ArrayList<>(presentStarHiddenColumns(star));
            if (correlatedSource != null) {
                hidden.addAll(presentSourceHiddenColumns(correlatedSource));
            }
            // _TABLE_SUFFIX only exists in the data of a wildcard source, so it can only be excluded there.
            boolean wildcard = correlatedSource != null
                    ? correlatedSource.wildcardTable()
                    : projectionSourcesForStar(star).stream().anyMatch(ProjectionSource::wildcardTable);
            String exclusion = hidden.stream()
                    .filter(name -> !name.equals("_TABLE_SUFFIX") || wildcard)
                    .distinct().map(DuckTypes::quoteIdentifier).collect(Collectors.joining(", "));
            String rendered = render(start, star) + "*";
            if (!exclusion.isEmpty()) {
                rendered += " EXCLUDE (" + exclusion + ")";
            }
            return (rendered + render(star + 1, end)).trim();
        }
        int open = nextSignificant(except + 1, end);
        int close = matchingParen(open);
        return (render(start, star) + "*" + starExclusionList(star, open, close, correlatedSource)
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
                    .startsWith("(" + INDEXED_AGGREGATE_PREFIX);
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
                        .startsWith("(" + INDEXED_AGGREGATE_PREFIX)) {
                    continue;
                }
                expressionStart = openParen;
            } else if (tokens.get(expressionEnd).kind == Kind.RAW) {
                expressionStart = expressionEnd;
            } else {
                continue;
            }
            int clause = nextSignificant(star + 1, tokens.size());
            if (clause >= 0 && (tokens.get(clause).isKeyword("EXCEPT") || tokens.get(clause).isKeyword("REPLACE"))) {
                // The struct-star rewrite owns a star with a list, because it can exclude or replace
                // the fields after the expression is expanded.
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
            replaceStructAliasFields(having + 1, clauseEnd(having + 1, depth), aliases, false);
        }
    }

    /**
     * QUALIFY sees the select aliases too, including in a window's {@code OVER (ORDER BY s.n)}, so a STRUCT
     * alias field there is expanded as in ORDER BY.
     */
    private void expandQualifyStructAliasFields() {
        for (int qualify = tokens.size() - 1; qualify >= 0; qualify--) {
            if (!tokens.get(qualify).isKeyword("QUALIFY")) {
                continue;
            }
            int depth = parenthesisDepth(qualify);
            int select = precedingSelect(qualify, depth);
            if (select < 0) {
                continue;
            }
            int quantifier = nextSignificant(select + 1, qualify);
            int listStart = quantifier >= 0 && (tokens.get(quantifier).isKeyword("DISTINCT")
                    || tokens.get(quantifier).isKeyword("ALL")) ? quantifier : select;
            Map<String, Map<String, List<Token>>> aliases = structSelectAliases(listStart, qualify, depth);
            if (!aliases.isEmpty()) {
                replaceStructAliasFields(qualify + 1, clauseEnd(qualify + 1, depth), aliases, true);
            }
        }
    }

    /**
     * BigQuery's ORDER BY also resolves {@code s.n} to a field of a STRUCT select alias {@code s}, ahead of an
     * input column of the same name, while DuckDB reads {@code s} as a table. Expand those fields as in HAVING.
     * An ORDER BY inside a call or {@code OVER (...)} is skipped, as is one after a set operation, whose
     * aliases come from the first SELECT and whose expressions DuckDB cannot sort by.
     */
    private void expandOrderByStructAliasFields() {
        for (int order = tokens.size() - 1; order >= 0; order--) {
            int by = nextSignificant(order + 1, tokens.size());
            if (!tokens.get(order).isKeyword("ORDER") || by < 0 || !tokens.get(by).isKeyword("BY")) {
                continue;
            }
            int depth = parenthesisDepth(order);
            int select = precedingSelect(order, depth);
            if (select < 0 || followsSetOperator(select) || leavesDepth(select, order, depth)) {
                continue;
            }
            int quantifier = nextSignificant(select + 1, order);
            int listStart = quantifier >= 0 && (tokens.get(quantifier).isKeyword("DISTINCT")
                    || tokens.get(quantifier).isKeyword("ALL")) ? quantifier : select;
            Map<String, Map<String, List<Token>>> aliases = structSelectAliases(listStart, order, depth);
            if (!aliases.isEmpty()) {
                replaceStructAliasFields(by + 1, clauseEnd(by + 1, depth), aliases, true);
            }
        }
    }

    private static final Set<String> ORDER_BY_END_KEYWORDS = Set.of(
            "LIMIT", "OFFSET", "FETCH", "ROWS", "RANGE", "GROUPS", "UNION", "INTERSECT", "EXCEPT");

    /**
     * BigQuery sorts a FLOAT64 NaN as the smallest non-NULL value: right after the NULLs ascending, right before
     * them descending, and first or last among the non-NULL values under an explicit NULLS LAST or NULLS FIRST.
     * DuckDB sorts NaN above every other value and has no setting for it, so each ORDER BY key, at query level,
     * in OVER (...) or in an ordered aggregate, gets a leading key that is NULL for a NULL value, 0 for NaN and 1
     * otherwise, in the key's own direction and NULLS order. Types are not known here, so the NaN test is guarded
     * by typeof, and the keys are only added when the query can see a FLOAT64 that may be NaN (see
     * {@link #mayHoldNan}). A key is left alone in a query with a set operation (DuckDB sorts those by result
     * column only), in a DISTINCT aggregate (whose ORDER BY may only name its arguments), in a window with a RANGE
     * frame (which takes one key), when it is an ordinal, and when it holds a positional parameter, which a copy
     * would renumber. It runs after the struct-star rewrites, which move ORDER BY out of their wrappers.
     */
    private void orderNanLikeBigQuery() {
        if (!mayHoldNan()) {
            return;
        }
        for (int order = tokens.size() - 1; order >= 0; order--) {
            int by = nextSignificant(order + 1, tokens.size());
            if (!tokens.get(order).isKeyword("ORDER") || by < 0 || !tokens.get(by).isKeyword("BY")) {
                continue;
            }
            int depth = parenthesisDepth(order);
            if (hasSetOperatorBefore(order, depth) || inDistinctAggregate(order, depth)) {
                continue;
            }
            int end = by + 1;
            while (end < tokens.size() && !(parenthesisDepth(end) == depth && tokens.get(end).isPunct(")"))
                    && !(parenthesisDepth(end) == depth && tokens.get(end).kind == Kind.IDENT
                    && ORDER_BY_END_KEYWORDS.contains(tokens.get(end).upper()) && !isPrecededByDot(end))) {
                end++;
            }
            if (end < tokens.size() && tokens.get(end).isKeyword("RANGE") || namedWindowExtendedByRange(order, depth)
                    || groupsByAll(order, depth)) {
                continue;
            }
            Map<String, List<Token>> aliases = selectAliasExpressions(order, depth);
            List<int[]> keys = new ArrayList<>();
            int start = by + 1;
            int nesting = 0;
            for (int i = by + 1; i <= end; i++) {
                Token token = i == end ? null : tokens.get(i);
                if (token != null && (token.isPunct("[") || token.isPunct("{"))) {
                    nesting++;
                } else if (token != null && (token.isPunct("]") || token.isPunct("}"))) {
                    nesting--;
                }
                if (i == end || nesting == 0 && parenthesisDepth(i) == depth && token.isPunct(",")) {
                    keys.add(new int[] {start, i});
                    start = i + 1;
                }
            }
            for (int k = keys.size() - 1; k >= 0; k--) {
                addNanSortKey(keys.get(k)[0], keys.get(k)[1], aliases);
            }
        }
    }

    private static final Pattern NAN_TEXT = Pattern.compile("[+-]?nan", Pattern.CASE_INSENSITIVE);

    /**
     * Whether the query can see a FLOAT64 that may be NaN: a 'NaN' string (as in {@code CAST('NaN' AS FLOAT64)}),
     * an {@code IEEE_DIVIDE} call, a FLOAT64 query parameter, or a table read after FROM or JOIN with a FLOAT64
     * column. Only those queries get the NaN sort key.
     */
    private boolean mayHoldNan() {
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            int next = nextSignificant(i + 1, tokens.size());
            if (token.kind == Kind.STRING && token.value != null && NAN_TEXT.matcher(token.value).matches()) {
                return true;
            }
            if (token.isKeyword("IEEE_DIVIDE") && next >= 0 && tokens.get(next).isPunct("(")) {
                return true;
            }
            if ((token.isKeyword("FROM") || token.isKeyword("JOIN")) && next >= 0 && readsFloatColumn(next)) {
                return true;
            }
        }
        return parameters.hasFloat();
    }

    private boolean readsFloatColumn(int start) {
        Token first = tokens.get(start);
        if (first.kind != Kind.IDENT && first.kind != Kind.QIDENT) {
            return false;
        }
        List<String> segments = new ArrayList<>(pathSegments(first));
        int dot = nextSignificant(start + 1, tokens.size());
        while (dot >= 0 && tokens.get(dot).isPunct(".")) {
            int part = nextSignificant(dot + 1, tokens.size());
            if (part < 0) {
                break;
            }
            segments.addAll(pathSegments(tokens.get(part)));
            dot = nextSignificant(part + 1, tokens.size());
        }
        TableRef ref = tableRefOrNull(segments);
        List<TableFieldSchema> fields = ref == null ? null : fieldsOf.apply(ref);
        return fields != null && hasFloatField(fields);
    }

    private static boolean hasFloatField(List<TableFieldSchema> fields) {
        for (TableFieldSchema field : fields) {
            if ("FLOAT".equals(field.getType()) || "FLOAT64".equals(field.getType())
                    || field.getFields() != null && hasFloatField(field.getFields())) {
                return true;
            }
        }
        return false;
    }

    /** Whether a UNION, INTERSECT or EXCEPT sits at {@code depth} in the query that holds {@code order}. */
    private boolean hasSetOperatorBefore(int order, int depth) {
        for (int i = order - 1; i >= 0 && parenthesisDepth(i) >= depth; i--) {
            Token token = tokens.get(i);
            if (parenthesisDepth(i) == depth && (token.isKeyword("UNION") || token.isKeyword("INTERSECT")
                    || token.isKeyword("EXCEPT"))) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code order} is inside the parentheses of a call whose arguments start with DISTINCT. */
    private boolean inDistinctAggregate(int order, int depth) {
        for (int i = order - 1; i >= 0; i--) {
            if (tokens.get(i).isPunct("(") && parenthesisDepth(i) == depth - 1) {
                int name = previousSignificantBefore(i);
                int first = nextSignificant(i + 1, order);
                return name >= 0 && tokens.get(name).kind == Kind.IDENT && !tokens.get(name).isKeyword("OVER")
                        && first >= 0 && tokens.get(first).isKeyword("DISTINCT");
            }
        }
        return false;
    }

    /**
     * Whether the query holding {@code order} (the enclosing one, for a window) uses GROUP BY ALL: DuckDB derives
     * its keys from the select list, so the leading key, which reads a column outside any select item, is not
     * grouped and fails to bind.
     */
    private boolean groupsByAll(int order, int depth) {
        int window = windowSpecificationOpen(order, depth);
        int select = window >= 0 ? precedingSelect(window, depth - 1) : precedingSelect(order, depth);
        if (select < 0) {
            return false;
        }
        int queryDepth = parenthesisDepth(select);
        for (int i = select; i < order; i++) {
            int by = tokens.get(i).isKeyword("GROUP") && parenthesisDepth(i) == queryDepth
                    ? nextSignificant(i + 1, order) : -1;
            int all = by < 0 ? -1 : nextSignificant(by + 1, order);
            if (all >= 0 && tokens.get(by).isKeyword("BY") && tokens.get(all).isKeyword("ALL")) {
                return true;
            }
        }
        return false;
    }

    private static final Set<String> VOLATILE_FUNCTIONS = Set.of("RAND", "GENERATE_UUID");

    /** The {@code (} of the window specification holding {@code order}, OVER (...) or WINDOW name AS (...), or -1. */
    private int windowSpecificationOpen(int order, int depth) {
        if (depth == 0) {
            return -1;
        }
        for (int i = order - 1; i >= 0; i--) {
            if (tokens.get(i).isPunct("(") && parenthesisDepth(i) == depth - 1) {
                int before = previousSignificantBefore(i);
                int first = nextSignificant(i + 1, order + 1);
                boolean query = first >= 0 && (tokens.get(first).isKeyword("SELECT")
                        || tokens.get(first).isKeyword("WITH") || tokens.get(first).isPunct("("));
                boolean window = before >= 0
                        && (tokens.get(before).isKeyword("OVER") || tokens.get(before).isKeyword("AS") && !query);
                return window ? i : -1;
            }
        }
        return -1;
    }

    /**
     * Whether {@code order} sits in a named window, WINDOW name AS (ORDER BY ...), that an OVER (name RANGE ...)
     * extends: DuckDB takes one sort key for a RANGE frame.
     */
    private boolean namedWindowExtendedByRange(int order, int depth) {
        int open = windowSpecificationOpen(order, depth);
        int as = open < 0 ? -1 : previousSignificantBefore(open);
        int name = as < 0 ? -1 : previousSignificantBefore(as);
        if (name < 0 || !tokens.get(as).isKeyword("AS")
                || tokens.get(name).kind != Kind.IDENT && tokens.get(name).kind != Kind.QIDENT) {
            return false;
        }
        String window = tokens.get(name).identifierText();
        for (int i = 0; i < tokens.size(); i++) {
            int paren = tokens.get(i).isKeyword("OVER") ? nextSignificant(i + 1, tokens.size()) : -1;
            int ref = paren < 0 ? -1 : nextSignificant(paren + 1, tokens.size());
            int frame = ref < 0 ? -1 : nextSignificant(ref + 1, tokens.size());
            if (frame >= 0 && tokens.get(paren).isPunct("(")
                    && (tokens.get(ref).kind == Kind.IDENT || tokens.get(ref).kind == Kind.QIDENT)
                    && tokens.get(ref).identifierText().equalsIgnoreCase(window)
                    && tokens.get(frame).isKeyword("RANGE")) {
                return true;
            }
        }
        return false;
    }

    /**
     * For a query-level ORDER BY, each select alias with the tokens of its expression, so the NaN key reads the
     * selected value as the sort key does rather than a source column of the same name; empty in a window.
     */
    private Map<String, List<Token>> selectAliasExpressions(int order, int depth) {
        int select = windowSpecificationOpen(order, depth) >= 0 ? -1 : precedingSelect(order, depth);
        List<int[]> items = select < 0 || leavesDepth(select, order, depth) ? null : selectItems(select);
        if (items == null) {
            return Map.of();
        }
        Map<String, List<Token>> aliases = new HashMap<>();
        for (int[] item : items) {
            String name = outputName(item[0], item[1]);
            if (name == null) {
                continue;
            }
            int itemDepth = parenthesisDepth(item[0]);
            int as = -1;
            for (int i = item[0]; i < item[1]; i++) {
                if (parenthesisDepth(i) == itemDepth && tokens.get(i).isKeyword("AS")) {
                    as = i;
                }
            }
            int first = nextSignificant(item[0], item[1]);
            int last = previousSignificantBefore(as < 0 ? item[1] : as);
            int beforeLast = as < 0 && last > first ? previousSignificantBefore(last) : -1;
            if (beforeLast >= first && !tokens.get(beforeLast).isPunct(".")
                    && (tokens.get(last).kind == Kind.IDENT || tokens.get(last).kind == Kind.QIDENT)
                    && tokens.get(last).identifierText().equalsIgnoreCase(name)) {
                // An implicit alias, x * 2 `y`: the expression ends before it.
                last = beforeLast;
            }
            if (first >= 0 && last >= first) {
                aliases.putIfAbsent(name.toLowerCase(Locale.ROOT), new ArrayList<>(tokens.subList(first, last + 1)));
            }
        }
        return aliases;
    }

    private void addNanSortKey(int start, int end, Map<String, List<Token>> aliases) {
        int last = previousSignificantBefore(end);
        String nulls = "";
        if (last > start && (tokens.get(last).isKeyword("FIRST") || tokens.get(last).isKeyword("LAST"))) {
            int before = previousSignificantBefore(last);
            if (before > start && tokens.get(before).isKeyword("NULLS")) {
                nulls = " NULLS " + tokens.get(last).upper();
                last = previousSignificantBefore(before);
            }
        }
        String direction = "";
        if (last > start && (tokens.get(last).isKeyword("ASC") || tokens.get(last).isKeyword("DESC"))) {
            direction = tokens.get(last).isKeyword("DESC") ? " DESC" : "";
            last = previousSignificantBefore(last);
        }
        int first = nextSignificant(start, end);
        if (first < 0 || last < first || first == last && tokens.get(first).kind == Kind.NUMBER) {
            return;
        }
        List<Token> key = new ArrayList<>(tokens.subList(first, last + 1));
        if (first == last && (tokens.get(first).kind == Kind.IDENT || tokens.get(first).kind == Kind.QIDENT)) {
            List<Token> aliased = aliases.get(tokens.get(first).identifierText().toLowerCase(Locale.ROOT));
            if (aliased != null && aliased.stream().anyMatch(token -> token.isKeyword("OVER")
                    || token.kind == Kind.IDENT && VOLATILE_FUNCTIONS.contains(token.upper()))) {
                // A window function, or a call that draws a new value each time it runs: a copy would sort by
                // another value than the one selected.
                return;
            }
            if (aliased != null) {
                key = new ArrayList<>(aliased);
            }
        }
        if (key.stream().anyMatch(token -> token.kind == Kind.POSITIONAL_PARAM)) {
            return;
        }
        List<Token> flag = new ArrayList<>();
        flag.add(Token.raw("(CASE WHEN ("));
        flag.addAll(key);
        flag.add(Token.raw(") IS NULL THEN NULL WHEN typeof("));
        flag.addAll(key);
        flag.add(Token.raw(") IN ('DOUBLE', 'FLOAT') AND isnan(TRY_CAST(CAST(("));
        flag.addAll(key);
        flag.add(Token.raw(") AS VARCHAR) AS DOUBLE)) THEN 0 ELSE 1 END)" + direction + nulls + ", "));
        tokens.addAll(first, flag);
    }

    private boolean followsSetOperator(int select) {
        int before = significantBefore(select);
        return before >= 0 && (tokens.get(before).isKeyword("UNION") || tokens.get(before).isKeyword("INTERSECT")
                || tokens.get(before).isKeyword("EXCEPT") || tokens.get(before).isKeyword("ALL")
                || tokens.get(before).isKeyword("DISTINCT"));
    }

    /** Whether the parenthesis depth drops below {@code depth} between {@code from} and {@code to}. */
    private boolean leavesDepth(int from, int to, int depth) {
        for (int i = from; i < to; i++) {
            if (parenthesisDepth(i) < depth) {
                return true;
            }
        }
        return false;
    }

    /**
     * Replaces each {@code alias.field} between {@code start} and {@code end} with the field's expression; the tail
     * of a qualified path such as {@code t.s.n} is a column of {@code t}, not the alias, and is left alone. In
     * ORDER BY, DuckDB reads a bare name as a select alias and an integer literal as a column position, so the
     * expression is wrapped in {@code list_extract([...], 1)}, which keeps its value and type. There, as in
     * BigQuery, a field the alias does not have is an error rather than a column of a table of the same name. It
     * is recorded so the engine can name the struct's type before the query runs; the {@code error(...)} left in
     * its place is the fallback for a path that does not check.
     */
    private void replaceStructAliasFields(int start, int end, Map<String, Map<String, List<Token>>> aliases,
                                          boolean orderBy) {
        boolean[] nestedQuery = nestedQueryPositions(start, end);
        boolean[] inlined = inlinedFieldPositions(start, end);
        for (int i = end - 1; i >= start; i--) {
            if (nestedQuery[i] || inlined[i]) {
                continue;
            }
            Token alias = tokens.get(i);
            int before = previousSignificantBefore(i);
            if (before >= start && tokens.get(before).isPunct(".")) {
                continue;
            }
            int dot = nextSignificant(i + 1, end);
            int field = dot < 0 ? -1 : nextSignificant(dot + 1, end);
            if ((alias.kind != Kind.IDENT && alias.kind != Kind.QIDENT) || dot < 0 || field < 0
                    || !tokens.get(dot).isPunct(".")
                    || (tokens.get(field).kind != Kind.IDENT && tokens.get(field).kind != Kind.QIDENT)) {
                continue;
            }
            Map<String, List<Token>> fields = aliases.get(alias.identifierText().toLowerCase(Locale.ROOT));
            String fieldKey = tokens.get(field).identifierText().toLowerCase(Locale.ROOT);
            Set<List<String>> anonymousFields = fields == null
                    ? Set.of()
                    : anonymousStructFields.getOrDefault(fields, Set.of());
            List<Token> expression = fields == null || anonymousFields.contains(List.of(fieldKey))
                    ? null
                    : fields.get(fieldKey);
            if (fields != null && expression == null && orderBy) {
                missingStructFields.add(new MissingStructField(alias.identifierText(),
                        tokens.get(field).identifierText(), parenthesisDepth(start) == 0, Set.copyOf(anonymousFields)));
                String message = "Field name " + tokens.get(field).identifierText() + " does not exist in STRUCT";
                expression = List.of(Token.raw("error('" + message.replace("'", "''") + "')"));
            }
            if (expression == null) {
                continue;
            }
            List<Token> replacement = new ArrayList<>();
            replacement.add(Token.raw(orderBy ? "list_extract([" : "("));
            replacement.addAll(expression);
            replacement.add(Token.raw(orderBy ? "], 1)" : ")"));
            tokens.subList(i, field + 1).clear();
            tokens.addAll(i, replacement);
        }
    }

    /**
     * Marks tokens inside a field expression an earlier pass already inlined, so that a field reading a source
     * struct of the same name ({@code STRUCT(s.n * -1 AS n) AS s}) is not expanded a second time.
     */
    private boolean[] inlinedFieldPositions(int start, int end) {
        boolean[] inlined = new boolean[end];
        int depth = 0;
        for (int i = start; i < end; i++) {
            Token token = tokens.get(i);
            if (token.kind == Kind.RAW && (token.text.equals("(") || token.text.equals("list_extract(["))) {
                depth++;
            } else if (token.kind == Kind.RAW && (token.text.equals(")") || token.text.equals("], 1)"))) {
                depth--;
            }
            inlined[i] = depth > 0;
        }
        return inlined;
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
        } else if (tokens.get(open).isPunct("(") && plainSelectAsStruct(open) >= 0) {
            close = matchingParen(open);
            fields = googleSqlStructFields(plainSelectAsStruct(open), close);
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

    /**
     * The STRUCT keyword of a scalar {@code (SELECT AS STRUCT expr AS name, ...)} with no FROM or other clause,
     * whose fields read the enclosing row and can be inlined like {@code STRUCT(...)}; -1 for anything else. A
     * field that calls an aggregate or carries {@code OVER} outside a nested query of its own would change the
     * outer query if inlined (BigQuery
     * rejects the aggregate there: {@code Aggregate function COUNT(*) not allowed in SELECT without FROM clause}),
     * so such a subquery is not read either.
     */
    private int plainSelectAsStruct(int open) {
        int close = matchingParen(open);
        int select = nextSignificant(open + 1, close);
        int as = select < 0 ? -1 : nextSignificant(select + 1, close);
        int struct = as < 0 ? -1 : nextSignificant(as + 1, close);
        if (struct < 0 || !tokens.get(select).isKeyword("SELECT") || !tokens.get(as).isKeyword("AS")
                || !tokens.get(struct).isKeyword("STRUCT")) {
            return -1;
        }
        int depth = parenthesisDepth(struct);
        for (int i = struct + 1; i < close; i++) {
            Token token = tokens.get(i);
            if (token.isPunct("(") && matchingParen(i) < close && isQuery(i + 1, matchingParen(i))) {
                // A nested query keeps its own aggregates and windows when the field is copied.
                i = matchingParen(i);
                continue;
            }
            if (parenthesisDepth(i) == depth && token.kind == Kind.IDENT
                    && (token.isKeyword("FROM") || CLAUSE_END_KEYWORDS.contains(token.upper()))) {
                return -1;
            }
            int next = nextSignificant(i + 1, close);
            if (token.isKeyword("OVER") || token.kind == Kind.IDENT && STRUCT_STAR_AGGREGATES.contains(token.upper())
                    && next >= 0 && tokens.get(next).isPunct("(")) {
                return -1;
            }
        }
        return struct;
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

    /**
     * For a field written as an untyped {@code STRUCT(...)}, adds the paths of its anonymous fields as name segments,
     * such as [_field_1, _field_1], so the missing-field message can write them by type at any depth. Segments keep a
     * quoted name holding a dot apart from a nested path.
     */
    private void addNestedAnonymousFields(int start, int end, String field, Set<List<String>> anonymous) {
        int keyword = nextSignificant(start, end);
        int open = keyword < 0 ? -1 : nextSignificant(keyword + 1, end);
        if (open < 0 || !tokens.get(keyword).isKeyword("STRUCT") || !tokens.get(open).isPunct("(")) {
            return;
        }
        int close = matchingParen(open);
        if (close < 0 || close >= end || nextSignificant(close + 1, end) >= 0) {
            return;
        }
        for (List<String> path : anonymousStructFields.getOrDefault(googleSqlStructFields(open, close), Set.of())) {
            List<String> nested = new ArrayList<>(List.of(field));
            nested.addAll(path);
            anonymous.add(List.copyOf(nested));
        }
    }

    private Map<String, List<Token>> googleSqlStructFields(int open, int close) {
        Map<String, List<Token>> fields = new HashMap<>();
        int position = 1;
        Set<List<String>> anonymous = new HashSet<>();
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
            int field = as < 0 ? implicitStructFieldName(start, end) : nextSignificant(as + 1, end);
            if (as < 0 && field < 0) {
                // An anonymous field, named by position as renderStruct names it, for star expansion.
                List<Token> anonymousExpression = new ArrayList<>(tokens.subList(start, end));
                while (!anonymousExpression.isEmpty() && anonymousExpression.getLast().kind == Kind.SPACE) {
                    anonymousExpression.removeLast();
                }
                fields.put("_field_" + position, anonymousExpression);
                anonymous.add(List.of("_field_" + position));
                addNestedAnonymousFields(start, end, "_field_" + position, anonymous);
                position++;
                start = nextSignificant(end + 1, close);
                continue;
            }
            if (field < 0 || (tokens.get(field).kind != Kind.IDENT && tokens.get(field).kind != Kind.QIDENT)) {
                return Map.of();
            }
            int expressionEnd = as < 0 ? end : as;
            List<Token> expression = new ArrayList<>(tokens.subList(start, expressionEnd));
            while (!expression.isEmpty() && expression.getLast().kind == Kind.SPACE) {
                expression.removeLast();
            }
            String name = as < 0 ? pathSegments(tokens.get(field)).getLast() : tokens.get(field).identifierText();
            fields.put(name.toLowerCase(Locale.ROOT), expression);
            addNestedAnonymousFields(start, expressionEnd, name.toLowerCase(Locale.ROOT), anonymous);
            position++;
            start = nextSignificant(end + 1, close);
        }
        if (!anonymous.isEmpty()) {
            anonymousStructFields.put(fields, anonymous);
        }
        return fields;
    }


    /**
     * The token naming a STRUCT field written without {@code AS}: as in BigQuery, a path such as {@code t.n} is
     * named by its last part. Any other expression makes an anonymous field, so -1 is returned.
     */
    private int implicitStructFieldName(int start, int end) {
        int last = -1;
        boolean expectName = true;
        for (int i = start; i < end; i++) {
            Token token = tokens.get(i);
            if (token.kind == Kind.SPACE) {
                continue;
            }
            if (expectName && (token.kind == Kind.IDENT || token.kind == Kind.QIDENT)) {
                last = i;
            } else if (expectName || !token.isPunct(".")) {
                return -1;
            }
            expectName = !expectName;
        }
        return expectName ? -1 : last;
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

    private static String unquoteAlias(String alias) {
        if (alias.length() >= 2 && alias.startsWith("\"") && alias.endsWith("\"")) {
            return alias.substring(1, alias.length() - 1).replace("\"\"", "\"");
        }
        if (alias.length() >= 2 && alias.startsWith("`") && alias.endsWith("`")) {
            return alias.substring(1, alias.length() - 1);
        }
        return alias;
    }

    /** The lower-cased name a named select item gives its column: its alias or the column it reads. */
    private String outputName(int start, int end) {
        String name = outputNameText(start, end);
        return name == null ? null : name.toLowerCase(Locale.ROOT);
    }

    private String outputNameText(int start, int end) {
        List<Token> item = significant(start, end);
        if (item.isEmpty()) {
            return null;
        }
        List<Token> path = parenthesizedPath(item);
        Token last = path != null ? path.getLast() : item.getLast();
        if (last.kind == Kind.RAW) {
            // nameAnonymousColumns appends " AS <name>" as a single raw token.
            String text = last.text.trim();
            if (text.regionMatches(true, 0, "AS ", 0, 3)) {
                return unquoteAlias(text.substring(3).trim());
            }
            return null;
        }
        if (last.kind != Kind.IDENT && last.kind != Kind.QIDENT) {
            return null;
        }
        String name = last.identifierText();
        return name.substring(name.lastIndexOf('.') + 1);
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

            String parameterReference = scalarUdfParameterAlias(t, i, start, end);
            if (parameterReference != null) {
                out.append(parameterReference);
                i++;
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

    private String scalarUdfParameterAlias(Token token, int index, int start, int end) {
        if (scalarUdfParameterAliases.isEmpty() || token.kind != Kind.IDENT && token.kind != Kind.QIDENT) {
            return null;
        }
        String name = token.identifierText().toLowerCase(Locale.ROOT);
        String alias = scalarUdfParameterAliases.get(name);
        if (alias == null || scalarUdfSubqueryAliases(start, end, index).contains(name)) {
            return null;
        }
        int previous = previousSignificant(index - 1);
        int next = nextSignificant(index + 1, end);
        if (previous >= 0 && (tokens.get(previous).isPunct(".") || tokens.get(previous).isKeyword("AS"))) {
            return null;
        }
        if ((next >= 0 && tokens.get(next).isPunct("(")) || isDatePartSyntax(index, end)
                || isIntervalPartSyntax(index)) {
            return null;
        }
        return alias;
    }

    /** FROM aliases in a scalar subquery shadow parameters declared by the enclosing SQL UDF. */
    private Set<String> scalarUdfSubqueryAliases(int start, int end, int index) {
        if (!scalarUdfBody || !isQuery(start, end)) {
            return Set.of();
        }
        int segmentStart = start;
        int segmentEnd = end;
        int depth = 0;
        for (int i = start; i < end; i++) {
            Token token = tokens.get(i);
            if (token.isPunct("(")) {
                depth++;
            } else if (token.isPunct(")")) {
                depth--;
            } else if (depth == 0 && (token.isKeyword("UNION") || token.isKeyword("INTERSECT")
                    || token.isKeyword("EXCEPT"))) {
                if (i < index) {
                    segmentStart = i + 1;
                } else {
                    segmentEnd = i;
                    break;
                }
            }
        }

        Set<String> aliases = new HashSet<>();
        boolean inFrom = false;
        depth = 0;
        for (int i = segmentStart; i < segmentEnd; i++) {
            Token token = tokens.get(i);
            if (token.isPunct("(")) {
                depth++;
                continue;
            }
            if (token.isPunct(")")) {
                depth--;
                continue;
            }
            if (depth != 0 || token.kind == Kind.SPACE) {
                continue;
            }
            if (token.isKeyword("FROM")) {
                inFrom = true;
                continue;
            }
            if (inFrom && token.kind == Kind.IDENT && CLAUSE_END_KEYWORDS.contains(token.upper())) {
                inFrom = false;
                continue;
            }
            if (inFrom && token.isKeyword("AS")) {
                int alias = nextSignificant(i + 1, segmentEnd);
                if (alias >= 0 && (tokens.get(alias).kind == Kind.IDENT
                        || tokens.get(alias).kind == Kind.QIDENT)) {
                    aliases.add(tokens.get(alias).identifierText().toLowerCase(Locale.ROOT));
                }
                continue;
            }
            if (inFrom && (token.kind == Kind.IDENT || token.kind == Kind.QIDENT)
                    && (token.kind == Kind.QIDENT || !NON_ALIAS_KEYWORDS.contains(token.upper()))) {
                int previous = previousSignificant(i - 1);
                boolean followsParenthesizedSource = false;
                if (previous >= segmentStart && tokens.get(previous).isPunct(")")) {
                    int open = matchingOpen(previous, "(", ")");
                    int callee = previousSignificant(open - 1);
                    followsParenthesizedSource = open >= segmentStart
                            && (isQuery(open + 1, previous)
                            || callee >= segmentStart && tokens.get(callee).isKeyword("UNNEST"));
                }
                boolean followsTableName = scalarUdfTableSourceEndsAt(previous, segmentStart);
                if (followsParenthesizedSource || followsTableName) {
                    aliases.add(token.identifierText().toLowerCase(Locale.ROOT));
                }
            }
        }
        return aliases;
    }

    private boolean scalarUdfTableSourceEndsAt(int last, int start) {
        if (last < start || tokens.get(last).kind != Kind.IDENT && tokens.get(last).kind != Kind.QIDENT) {
            return false;
        }
        int first = last;
        while (true) {
            int dot = previousSignificant(first - 1);
            int segment = previousSignificant(dot - 1);
            if (dot < start || segment < start || !tokens.get(dot).isPunct(".")
                    || tokens.get(segment).kind != Kind.IDENT && tokens.get(segment).kind != Kind.QIDENT) {
                break;
            }
            first = segment;
        }
        int introducer = previousSignificant(first - 1);
        return introducer >= start && (tokens.get(introducer).isKeyword("FROM")
                || tokens.get(introducer).isKeyword("JOIN") || tokens.get(introducer).isPunct(","));
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
        return starExclusionList(star, open, close, null);
    }

    private String starExclusionList(int star, int open, int close, ProjectionSource correlatedSource) {
        ProjectionSource source = correlatedSource != null ? correlatedSource : projectionSourceForStar(star);
        // Only the automatically hidden names are filtered: a source that cannot carry _TABLE_SUFFIX drops it,
        // while a name the query asked to exclude is always excluded.
        List<String> names = new ArrayList<>();
        for (String hidden : presentStarHiddenColumns(star)) {
            addHiddenExclusion(names, hidden, source);
        }
        if (correlatedSource != null) {
            for (String hidden : presentSourceHiddenColumns(correlatedSource)) {
                addHiddenExclusion(names, hidden, source);
            }
        }
        for (Token token : significant(open + 1, close)) {
            if (token.kind == Kind.IDENT || token.kind == Kind.QIDENT) {
                names.add(token.identifierText());
            }
        }
        return " EXCLUDE (" + names.stream()
                .distinct().map(DuckTypes::quoteIdentifier).collect(Collectors.joining(", ")) + ")";
    }

    private static void addHiddenExclusion(List<String> names, String hidden, ProjectionSource source) {
        if (!hidden.equals("_TABLE_SUFFIX") || source != null && source.wildcardTable()) {
            names.add(hidden);
        }
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
        boolean temporary = segments.size() == 1 && temporaryTables.contains(segments.getFirst())
                || segments.size() == 2 && segments.getFirst().equalsIgnoreCase("_SESSION")
                && temporaryTables.contains(segments.getLast());
        boolean direct = !temporary && (segments.size() > 1 || !cteVisibleAt(segments.getFirst(), source));
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
        int first = nextNonSpace(start, end);
        if (first < 0) {
            return false;
        }
        if (startsQuery(tokens.get(first))) {
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
        if (previous >= 0 && (tokens.get(previous).isKeyword("STRUCT") || tokens.get(previous).isKeyword("VALUE"))) {
            int as = previousSignificantBefore(previous);
            if (as >= 0 && tokens.get(as).isKeyword("AS") && previousSignificantBefore(as) == select) {
                return true;
            }
        }
        return previous == select || previous >= 0 && (tokens.get(previous).isPunct(".")
                || tokens.get(previous).isPunct(",") || tokens.get(previous).isKeyword("DISTINCT")
                || tokens.get(previous).isKeyword("ALL"));
    }

    /** Renders a table path in a FROM/JOIN position plus its optional alias. */
    private int renderTablePath(int i, int end, StringBuilder out, Set<String> rangeVariables) {
        if (scalarUdfBody) {
            scalarUdfNamedTableSource = true;
        }
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
            String name = segments.getLast();
            out.append(DuckTypes.quoteIdentifier(name));
            rangeVariables.add(name.toLowerCase(Locale.ROOT));
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
        // One qualifier names a project only for project-level views; for the others it is a dataset,
        // which resolves in the job's project even when it is spelled like the default dataset's project.
        boolean inJobProject = qualifiers.size() == 1
                ? !InformationSchema.regionOnly(view) || !qualifiers.getFirst().equals(projectId)
                : qualifiers.isEmpty() && InformationSchema.regionOnly(view);
        if (inJobProject && !jobProjectId.equals(projectId)) {
            throw invalidQuery("Cross-project queries are not supported by the floci BigQuery emulator: "
                    + jobProjectId + "." + String.join(".", segments));
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
            if (temporaryTables.contains(name)) {
                return null;
            }
            if (defaultDatasetId == null || defaultDatasetId.isBlank()) {
                throw invalidQuery("Table name \"" + name
                        + "\" missing dataset while no default dataset is set in the request.");
            }
            return new TableRef(defaultDatasetId, name);
        }
        if (segments.size() == 2) {
            if (segments.getFirst().equalsIgnoreCase("_SESSION")) {
                String name = segments.get(1);
                if (temporaryTables.contains(name)) {
                    return null;
                }
                throw GcpException.notFound("Not found: Dataset " + sessionDatasetProject()
                        + ":_SESSION was not found");
            }
            if (!jobProjectId.equals(projectId)) {
                throw invalidQuery("Cross-project queries are not supported by the floci BigQuery emulator: "
                        + jobProjectId + "." + String.join(".", segments));
            }
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
            case "ARRAY_SLICE" -> args(a, 3, name, "array_slice(" + at(a, 0) + ", " + sliceOffset(at(a, 1)) + ", "
                    + sliceOffset(at(a, 2)) + ")");
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
            case "GENERATE_TIMESTAMP_ARRAY" -> generateTimestampArray(a);
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
            case "REGEXP_EXTRACT_ALL" -> args(a, 2, name, "regexp_extract_all(" + at(a, 0) + ", " + at(a, 1) + ", "
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
                    "(" + timestampArithmeticValue(temporalOperand(at(a, 0), name)) + " + " + at(a, 1) + ")");
            case "TIMESTAMP_SUB" -> args(a, 2, name,
                    "(" + timestampArithmeticValue(temporalOperand(at(a, 0), name)) + " - " + at(a, 1) + ")");
            case "DATETIME_ADD", "TIME_ADD" -> args(a, 2, name,
                    "(" + temporalOperand(at(a, 0), name) + " + " + at(a, 1) + ")");
            case "DATETIME_SUB", "TIME_SUB" -> args(a, 2, name,
                    "(" + temporalOperand(at(a, 0), name) + " - " + at(a, 1) + ")");
            case "DATE_ADD" -> args(a, 2, name,
                    "CAST(" + temporalOperand(at(a, 0), name) + " + " + at(a, 1) + " AS DATE)");
            case "DATE_SUB" -> args(a, 2, name,
                    "CAST(" + temporalOperand(at(a, 0), name) + " - " + at(a, 1) + " AS DATE)");
            case "TIMESTAMP_DIFF", "DATETIME_DIFF", "DATE_DIFF", "TIME_DIFF" -> args(a, 3, name,
                    dateDiff(temporalOperand(at(a, 0), name), temporalOperand(at(a, 1), name), at(a, 2)));
            case "TIMESTAMP_TRUNC" -> renderTimestampTrunc(a, name);
            case "DATETIME_TRUNC" -> exactArgs(a, 2, name,
                    dateTrunc(temporalOperand(at(a, 0), name), at(a, 1), false, "TIMESTAMP"));
            // DATE_TRUNC is overloaded in BigQuery and returns the type of its first argument.
            // In particular, a TIMESTAMP input must remain a TIMESTAMP rather than becoming a
            // DATE. DuckDB's cast_to_type preserves that runtime type for column expressions too.
            case "DATE_TRUNC" -> renderDateTrunc(a, name);
            case "FORMAT_TIMESTAMP", "FORMAT_DATETIME", "FORMAT_DATE", "FORMAT_TIME" -> args(a, 2, name,
                    // DuckDB's strftime takes no TIME, so a TIME is formatted on a placeholder date it never prints.
                    formatCall(name, name.equals("FORMAT_TIME")
                            ? "(DATE '1970-01-01' + " + formatValue(at(a, 1), name) + ")"
                            : formatValue(at(a, 1), name), at(a, 0)));
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

    /**
     * {@code GENERATE_TIMESTAMP_ARRAY(start, end, INTERVAL n part)}: every timestamp from start to end, both
     * included. Unlike the date form the step is required. The series runs over TIMESTAMPTZ so the elements
     * stay BigQuery TIMESTAMPs, with the same NULL and zero-step handling as {@link #generateDateArray}.
     */
    private static String generateTimestampArray(List<String> a) {
        if (a.size() != 3) {
            throw invalidQuery("No matching signature for function GENERATE_TIMESTAMP_ARRAY with " + a.size()
                    + " arguments");
        }
        String step = a.get(2);
        return "(CASE WHEN (" + step + ") = INTERVAL 0 DAY THEN error('GENERATE_ARRAY step cannot be 0.')"
                + " ELSE CAST(generate_series(CAST(" + a.get(0) + " AS TIMESTAMPTZ), CAST(" + a.get(1)
                + " AS TIMESTAMPTZ), " + step + ") AS TIMESTAMPTZ[])"
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
    private static final String SHARED_ELEMENTS = "AaBbdHhIjMmpSTUuVWwXyZ";

    /** BigQuery composite elements, spelled with elements DuckDB knows; valid for strptime too. */
    private static final Map<String, String> COMPOSITE_ELEMENTS = Map.of(
            "%F", "%Y-%m-%d", "%D", "%m/%d/%y", "%x", "%m/%d/%y", "%R", "%H:%M", "%r", "%I:%M:%S %p",
            "%E4Y", "%Y");

    /** Elements DuckDB lacks or renders differently, emulated in SQL by {@link #emulatedElement}. */
    private static final List<String> EMULATED_ELEMENTS = List.of(
            "%C", "%F", "%G", "%Y", "%c", "%e", "%g", "%J", "%k", "%l", "%P", "%Q", "%s", "%E*S");

    /** Every element a format read from a column may use, each rendered with a constant format. */
    private static final List<String> PER_ROW_ELEMENTS = perRowElements();

    private static List<String> perRowElements() {
        List<String> elements = new ArrayList<>(List.of("%%", "%t", "%n", "%z", "%Ez"));
        for (char c : SHARED_ELEMENTS.toCharArray()) {
            elements.add("%" + c);
        }
        elements.addAll(COMPOSITE_ELEMENTS.keySet());
        elements.addAll(EMULATED_ELEMENTS);
        return List.copyOf(new LinkedHashSet<>(elements));
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
                || function.equals("FORMAT_DATE") && element.matches("%E\\dS");
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
        // FORMAT_DATE prints a one-digit %E<n>S as written and renders zero seconds for two or more digits.
        String fractionPattern = function.equals("FORMAT_DATE") ? "%E[0-9][0-9]+S" : "%E[0-9]+S";
        String fraction = "CASE WHEN regexp_full_match(" + FORMAT_ELEMENT + ", '" + fractionPattern + "') THEN "
                + fractionalSeconds(rowValue, "CASE WHEN length(" + digits + ") > 2 THEN "
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
        if (composite != null && !EMULATED_ELEMENTS.contains(element)) {
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
            // BigQuery prints the year without leading zeros (year 99 is 99, its century 0); DuckDB pads %Y and %G.
            case "%C" -> "CAST(year(" + value + ") // 100 AS VARCHAR)";
            case "%Y" -> "CAST(year(" + value + ") AS VARCHAR)";
            case "%G" -> "CAST(isoyear(" + value + ") AS VARCHAR)";
            case "%F" -> "(CAST(year(" + value + ") AS VARCHAR) || strftime(" + value + ", '-%m-%d'))";
            case "%c" -> "(strftime(" + value + ", '%a %b ') || " + emulatedElement(value, "%e")
                    + " || strftime(" + value + ", ' %H:%M:%S ') || CAST(year(" + value + ") AS VARCHAR))";
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

    /**
     * BigQuery coerces a STRING literal or STRING parameter passed to a date/time function to the
     * function's type ({@code DATE_ADD(@d, INTERVAL 1 DAY)} with {@code @d} a STRING). DuckDB cannot
     * pick an overload for a string plus an interval, so such an operand gets an explicit cast. Redundant
     * parentheses around the literal are looked through, and a NULL STRING parameter becomes a typed NULL.
     */
    private static String temporalOperand(String operand, String function) {
        String literal = operand.strip();
        while (wrappedInParentheses(literal)) {
            literal = literal.substring(1, literal.length() - 1).strip();
        }
        if (literal.startsWith("CAST(") && literal.endsWith(" AS VARCHAR)")) {
            literal = literal.substring("CAST(".length(), literal.length() - " AS VARCHAR)".length());
        }
        if (!isStringLiteral(literal) && !(literal.equals("NULL") && operand.contains(" AS VARCHAR)"))) {
            return operand;
        }
        String type;
        if (function.startsWith("DATE_")) {
            type = "DATE";
        } else if (function.startsWith("DATETIME_")) {
            type = "TIMESTAMP";
        } else if (function.startsWith("TIMESTAMP_")) {
            type = "TIMESTAMPTZ";
        } else {
            type = "TIME";
        }
        return "CAST(" + literal + " AS " + type + ")";
    }

    /**
     * The value of a {@code FORMAT_*} call. BigQuery coerces a STRING literal or parameter to the function's type,
     * as {@link #temporalOperand} does for the date functions, and a bare NULL (in any case, with or without
     * redundant parentheses) is a NULL of that type.
     */
    private static String formatValue(String value, String function) {
        String type = function.substring("FORMAT_".length());
        String bare = value.strip();
        while (wrappedInParentheses(bare)) {
            bare = bare.substring(1, bare.length() - 1).strip();
        }
        if (bare.equalsIgnoreCase("NULL")) {
            String duckType = switch (type) {
                case "DATETIME" -> "TIMESTAMP";
                case "TIMESTAMP" -> "TIMESTAMPTZ";
                default -> type;
            };
            return "CAST(NULL AS " + duckType + ")";
        }
        return temporalOperand(value, type + "_");
    }

    /** True when {@code sql} is one parenthesized expression: its first parenthesis closes at its last character. */
    private static boolean wrappedInParentheses(String sql) {
        if (sql.length() < 2 || sql.charAt(0) != '(' || sql.charAt(sql.length() - 1) != ')') {
            return false;
        }
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')') {
                depth--;
                if (depth == 0 && i < sql.length() - 1) {
                    return false;
                }
            }
        }
        return depth == 0;
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
        String input = dateTruncValue(temporalOperand(at(arguments, 0), "TIMESTAMP_TRUNC"), "TIMESTAMPTZ");
        String zone = arguments.size() == 3 ? at(arguments, 2) : DuckTypes.quoteLiteral("UTC");
        String local = "timezone(" + zone + ", " + input + ")";
        String truncated = dateTrunc(local, at(arguments, 1), false, null);
        return "timezone(" + zone + ", " + truncated + ")";
    }

    private static String renderDateTrunc(List<String> arguments, String name) {
        if (arguments.size() == 2) {
            return dateTrunc(temporalOperand(at(arguments, 0), "DATE_TRUNC"), at(arguments, 1), true, "DATE");
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
            } else if (commas == 1 && depth == 0
                    && (t.kind == Kind.STRING || t.kind == Kind.NAMED_PARAM || t.kind == Kind.POSITIONAL_PARAM)) {
                // The first literal or parameter of the pattern argument, which may sit in an expression such
                // as CASE WHEN ... THEN r'a([0-9])' ... END.
                String pattern = switch (t.kind) {
                    case STRING -> t.value;
                    case NAMED_PARAM -> QueryParameters.stringLiteralValue(parameters.named(t.value));
                    default -> QueryParameters.stringLiteralValue(t.value);
                };
                return pattern != null && regexHasCaptureGroup(pattern);
            }
        }
        return false;
    }

    /**
     * Whether an RE2 pattern has a capturing group: an unescaped {@code (} outside a character class and outside
     * a {@code \Q...\E} quote that is not {@code (?...)}, or a named group, {@code (?P<name>...)} or
     * {@code (?<name>...)}.
     */
    static boolean regexHasCaptureGroup(String pattern) {
        int length = pattern.length();
        for (int p = 0; p < length; p++) {
            char c = pattern.charAt(p);
            if (c == '\\' && p + 1 < length && pattern.charAt(p + 1) == 'Q') {
                // Everything up to \E, or to the end of the pattern, is literal.
                int end = pattern.indexOf("\\E", p + 2);
                p = end < 0 ? length : end + 1;
            } else if (c == '\\') {
                p++;
            } else if (c == '[') {
                int q = p + 1;
                if (q < length && pattern.charAt(q) == '^') {
                    q++;
                }
                if (q < length && pattern.charAt(q) == ']') {
                    // A ] right after [ or [^ is a literal member of the class.
                    q++;
                }
                while (q < length && pattern.charAt(q) != ']') {
                    int posixEnd = pattern.startsWith("[:", q) ? pattern.indexOf(":]", q + 2) : -1;
                    if (posixEnd >= 0) {
                        // A POSIX class such as [:alpha:] ends with its own ], not the class's.
                        q = posixEnd + 2;
                    } else {
                        q += pattern.charAt(q) == '\\' ? 2 : 1;
                    }
                }
                p = q;
            } else if (c == '(') {
                if (p + 1 >= length || pattern.charAt(p + 1) != '?' || pattern.startsWith("(?P<", p)
                        || pattern.startsWith("(?<", p) && !pattern.startsWith("(?<=", p)
                        && !pattern.startsWith("(?<!", p)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * An {@code ARRAY_SLICE} offset for DuckDB's {@code array_slice}: GoogleSQL counts a non-negative offset from
     * zero and DuckDB from one, while a negative offset counts back from the last element in both. The offset is
     * bound once through a one-element lambda, so a volatile one such as {@code RAND()} is read once, and capped
     * below the largest INT64 before the shift: BigQuery clamps an end past the array, where the addition would
     * overflow.
     */
    private static String sliceOffset(String offset) {
        return "list_extract(list_transform([" + offset + "], _floci_slice_offset -> CASE WHEN _floci_slice_offset >= 0"
                + " THEN least(_floci_slice_offset, 9223372036854775806) + 1 ELSE _floci_slice_offset END), 1)";
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
                List<Token> expression = significant(open + 1, k);
                String expr = render(open + 1, k).trim();
                List<Token> targetType = significant(k + 1, close);
                String type = translateType(targetType);
                if ("VARCHAR".equals(type)) {
                    if (isFloatLiteral(expression)) {
                        // BigQuery types a decimal or exponent literal as FLOAT64; DuckDB types it as DECIMAL,
                        // where zero renders as "0.0" and negative zero loses its sign.
                        return renderStringCast(function, "CAST(" + expr + " AS DOUBLE)");
                    }
                    if (!isDefinitelyNonFloatExpression(expression)) {
                        return renderStringCast(function, expr);
                    }
                }
                return function + "(" + expr + " AS " + type + ")";
            }
        }
        return function + "(" + render(open + 1, close) + ")";
    }

    private static boolean rendersAsNumber(String duckType) {
        return "DOUBLE".equals(duckType) || "FLOAT".equals(duckType) || duckType.startsWith("DECIMAL");
    }

    private static boolean isFloatLiteral(List<Token> expression) {
        if (expression.size() == 2
                && (expression.getFirst().isPunct("-") || expression.getFirst().isPunct("+"))) {
            return isFloatLiteral(expression.subList(1, expression.size()));
        }
        return expression.size() == 1 && expression.getFirst().kind == Kind.NUMBER
                && expression.getFirst().text.matches("(?i).*[.e].*");
    }

    private boolean isDefinitelyNonFloatExpression(List<Token> expression) {
        if (expression.isEmpty()) {
            return false;
        }
        Token first = expression.getFirst();
        if (expression.size() == 1) {
            if (first.kind == Kind.STRING || first.kind == Kind.BYTES) {
                return true;
            }
            if (first.kind == Kind.NUMBER) {
                return !first.text.matches("(?i).*[.e].*");
            }
            return false;
        }
        if (first.kind == Kind.IDENT && Set.of("DATE", "TIME", "DATETIME", "TIMESTAMP", "STRING", "BYTES",
                "BOOL", "BOOLEAN", "INT64", "INTEGER", "JSON")
                .contains(first.upper()) && expression.get(1).kind == Kind.STRING) {
            return true;
        }
        if ((first.isKeyword("CAST") || first.isKeyword("SAFE_CAST")) && expression.get(1).isPunct("(")) {
            int close = matchingParenthesis(expression, 1);
            int as = topLevelToken(expression, 2, close, "AS");
            if (close == expression.size() - 1 && as >= 0) {
                List<Token> castType = expression.subList(as + 1, close);
                return castType.isEmpty() || !rendersAsNumber(translateType(castType));
            }
        }
        if (first.kind == Kind.IDENT && expression.size() > 2 && expression.get(1).isPunct("(")) {
            int close = matchingParenthesis(expression, 1);
            if (close == expression.size() - 1 && first.isKeyword("MOD")) {
                // MOD returns the wider of its arguments, so the result is only non-float when all of them are.
                List<List<Token>> modArguments = callArguments(expression, 1, close);
                return !modArguments.isEmpty()
                        && modArguments.stream().allMatch(this::isDefinitelyNonFloatExpression);
            }
        }
        int depth = 0;
        for (int index = 0; index < expression.size(); index++) {
            Token token = expression.get(index);
            if (token.isPunct("(")) {
                depth++;
            } else if (token.isPunct(")")) {
                depth--;
            } else if (depth == 0 && index > 0 && index < expression.size() - 1
                    && (token.isPunct("+") || token.isPunct("-") || token.isPunct("*"))) {
                return isDefinitelyNonFloatExpression(expression.subList(0, index))
                        && isDefinitelyNonFloatExpression(expression.subList(index + 1, expression.size()));
            }
        }
        return false;
    }

    private static List<List<Token>> callArguments(List<Token> expression, int open, int close) {
        List<List<Token>> arguments = new ArrayList<>();
        int depth = 0;
        int start = open + 1;
        for (int index = open + 1; index < close; index++) {
            Token token = expression.get(index);
            if (token.isPunct("(") || token.isPunct("[")) {
                depth++;
            } else if (token.isPunct(")") || token.isPunct("]")) {
                depth--;
            } else if (depth == 0 && token.isPunct(",")) {
                arguments.add(expression.subList(start, index));
                start = index + 1;
            }
        }
        arguments.add(expression.subList(start, close));
        return arguments;
    }

    private static int matchingParenthesis(List<Token> expression, int open) {
        int depth = 0;
        for (int index = open; index < expression.size(); index++) {
            if (expression.get(index).isPunct("(")) {
                depth++;
            } else if (expression.get(index).isPunct(")") && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static int topLevelToken(List<Token> expression, int start, int end, String keyword) {
        int depth = 0;
        for (int index = start; index < end; index++) {
            Token token = expression.get(index);
            if (token.isPunct("(")) {
                depth++;
            } else if (token.isPunct(")")) {
                depth--;
            } else if (depth == 0 && token.isKeyword(keyword)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Renders a numeric value as BigQuery renders it as STRING: float zero is {@code "0"} (unsigned), and a
     * decimal keeps only its significant fraction digits. DuckDB renders zero floats as {@code "0.0"} or
     * {@code "-0.0"}, and its DECIMAL, which backs {@code NUMERIC}/{@code BIGNUMERIC}, pads the scale
     * ({@code "12.300000000"}).
     *
     * The operand stays in its own query scope, held once in a single-element list, because a derived table
     * would give an aggregate a single implicit row ({@code COUNT(*)} would be 1) and would drop a window
     * function's partition, while an uncorrelated call such as {@code RAND()} would be shared by every row.
     * The list holds the value once, so it is evaluated once per row.
     *
     * The lambda name is fixed because DuckDB matches a select item to a {@code GROUP BY} item by its text:
     * numbering the name per call would make {@code SELECT CAST(i AS STRING) ... GROUP BY CAST(i AS STRING)}
     * group by a column instead of the expression. The operand sits outside the lambda body, so nested
     * wrappers cannot shadow the name.
     */
    private static String renderStringCast(String function, String expression) {
        String value = DuckTypes.quoteIdentifier("_floci_cast_input");
        String text = function + "(" + value + " AS VARCHAR)";
        return "list_extract(list_transform([" + expression + "], " + value + " -> CASE"
                + " WHEN typeof(" + value + ") IN ('DOUBLE', 'FLOAT') AND " + value + " = 0 THEN '0'"
                + " WHEN typeof(" + value + ") LIKE 'DECIMAL%' AND " + text + " LIKE '%.%'"
                + " THEN rtrim(rtrim(" + text + ", '0'), '.')"
                + " ELSE " + text + " END), 1)";
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
        if (laterSetOperationItem(significantBefore(open), close)) {
            List<String> values = new ArrayList<>();
            for (int[] r : ranges) {
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
                values.add(render(r[0], asIndex >= 0 ? asIndex : r[1]).trim());
            }
            return "ROW(" + String.join(", ", values) + ")";
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
     * Whether the STRUCT(...) from {@code keyword} to {@code close} is a whole select item of a query after a set
     * operator. BigQuery names the fields of a set operation's STRUCT column after its first query and matches
     * later queries by position, while DuckDB unions STRUCT values by field name; an unnamed ROW(...) takes the
     * first query's names by position.
     */
    private boolean laterSetOperationItem(int keyword, int close) {
        if (keyword < 0 || !tokens.get(keyword).isKeyword("STRUCT")) {
            return false;
        }
        int start = keyword;
        int end = close;
        while (true) {
            // An item written in parentheses, SELECT (STRUCT(2 AS q)), is the same item.
            int open = significantBefore(start);
            int shut = nextSignificant(end + 1, tokens.size());
            if (open < 0 || shut < 0 || !tokens.get(open).isPunct("(") || matchingParen(open) != shut) {
                break;
            }
            start = open;
            end = shut;
        }
        int depth = parenthesisDepth(start);
        int select = precedingSelect(start, depth);
        if (select < 0 || leavesDepth(select, start, depth)) {
            return false;
        }
        int before = significantBefore(select);
        while (before >= 0 && tokens.get(before).isPunct("(")) {
            before = significantBefore(before);
        }
        if (before < 0 || !(tokens.get(before).isKeyword("UNION") || tokens.get(before).isKeyword("INTERSECT")
                || tokens.get(before).isKeyword("EXCEPT") || tokens.get(before).isKeyword("ALL")
                || tokens.get(before).isKeyword("DISTINCT"))) {
            return false;
        }
        List<int[]> items = selectItems(select);
        for (int[] item : items == null ? List.<int[]>of() : items) {
            if (nextSignificant(item[0], item[1]) != start) {
                continue;
            }
            int after = nextSignificant(end + 1, item[1]);
            return after < 0 || tokens.get(after).isKeyword("AS")
                    || isName(tokens.get(after)) && nextSignificant(after + 1, item[1]) < 0;
        }
        return false;
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
            if (t.isPunct("(") || t.isPunct("[") || t.isPunct("{")) {
                depth++;
            } else if (t.isPunct(")") || t.isPunct("]") || t.isPunct("}")) {
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

    /** The next token that is not a space, including a raw token such as a rewritten select list. */
    private int nextNonSpace(int from, int end) {
        for (int k = from; k < end; k++) {
            if (tokens.get(k).kind != Kind.SPACE) {
                return k;
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

        /**
         * A STRING parameter value shaped like a DATE, DATETIME or TIMESTAMP literal: an uppercase {@code T}
         * and {@code Z}, {@code UTC}, a numeric offset or a time zone name such as {@code America/Los_Angeles} or
         * {@code EST5EDT} (which DuckDB reads through ICU, loaded by the session's {@code SET TimeZone}). A
         * lowercase {@code t} or {@code z} is left out: DuckDB cannot parse it.
         */
        private static final Pattern TEMPORAL_STRING = Pattern.compile(
                "\\d{4}-\\d{1,2}-\\d{1,2}([ T]\\d{1,2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?)?"
                        + "\\s*(Z|UTC|[+-]\\d{1,2}(:?\\d{2})?|(?!z$)[A-Za-z][A-Za-z0-9_+-]*(/[A-Za-z0-9_+-]+)*)?");

        static QueryParameters none() {
            return new QueryParameters(List.of(), null);
        }

        private static final Pattern STRING_LITERAL = Pattern.compile(
                "(?:CAST\\()?'((?:[^']|'')*)'(?: AS VARCHAR\\))?", Pattern.DOTALL);

        /** The value of a STRING parameter as {@link #literal} renders it, or null for any other literal. */
        static String stringLiteralValue(String literal) {
            Matcher matcher = literal == null ? null : STRING_LITERAL.matcher(literal);
            return matcher != null && matcher.matches() ? matcher.group(1).replace("''", "'") : null;
        }

        /** Whether any parameter is a FLOAT64, alone or inside an ARRAY or STRUCT type. */
        boolean hasFloat() {
            return parameters.stream().anyMatch(p -> isFloatType(p.get("parameterType")));
        }

        private static boolean isFloatType(Object type) {
            if (!(type instanceof Map<?, ?> map)) {
                return false;
            }
            if ("FLOAT64".equals(map.get("type")) || "FLOAT".equals(map.get("type"))
                    || isFloatType(map.get("arrayType"))) {
                return true;
            }
            return map.get("structTypes") instanceof List<?> fields
                    && fields.stream().anyMatch(f -> f instanceof Map<?, ?> field && isFloatType(field.get("type")));
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
            if (value.containsKey("value") && value.get("value") == null) {
                return "CAST(NULL AS " + duckTypeOf(type) + ")";
            }
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
                    // BigQuery coerces a STRING parameter to DATE, DATETIME or TIMESTAMP when compared with
                    // one. An untyped DuckDB string literal casts implicitly the same way, a VARCHAR does not.
                    case "STRING" -> TEMPORAL_STRING.matcher(text).matches() ? DuckTypes.quoteLiteral(text)
                            : "CAST(" + DuckTypes.quoteLiteral(text) + " AS " + duckType + ")";
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
