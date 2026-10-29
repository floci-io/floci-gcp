package io.floci.gcp.services.bigquery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.docker.DockerHostResolver;
import io.floci.gcp.services.bigquery.model.Table;
import io.floci.gcp.services.bigquery.model.TableFieldSchema;
import io.floci.gcp.services.bigquery.model.TableSchema;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs GoogleSQL on the floci-duck sidecar. Each query:
 * <ol>
 *   <li>translates the SQL to DuckDB ({@link SqlDialectTranslator});</li>
 *   <li>stages every referenced table in a fresh in-memory DuckDB, reading its rows from
 *       floci-gcp's internal NDJSON route ({@link BigQueryInternalController});</li>
 *   <li>runs the query once; floci-duck returns the result column types and lossless
 *       values in the same response.</li>
 * </ol>
 * Dry runs only need the types, so they run {@code DESCRIBE}. Against a floci-duck image
 * that does not report columns, the engine falls back to {@code DESCRIBE} plus a query
 * whose columns are cast so their values survive the legacy JSON encoding.
 */
@ApplicationScoped
public class DuckSqlEngine implements BigQuerySqlEngine {

    private static final Logger LOG = Logger.getLogger(DuckSqlEngine.class);

    private final DuckClient client;
    private final DockerHostResolver dockerHostResolver;
    private final EmulatorConfig config;
    private final ObjectMapper mapper;

    @Inject
    public DuckSqlEngine(DuckClient client, DockerHostResolver dockerHostResolver, EmulatorConfig config,
                         ObjectMapper mapper) {
        this.client = client;
        this.dockerHostResolver = dockerHostResolver;
        this.config = config;
        this.mapper = mapper;
    }

    private record Column(String name, DuckTypes.DuckType type, TableFieldSchema field,
                          DuckTypes.Projection projection) {}

    @Override
    public Result execute(Request request, Tables tables) {
        SqlDialectTranslator.ScriptTranslation translation = SqlDialectTranslator.translateScript(request.sql(),
                request.projectId(), request.jobProjectId(), request.defaultDatasetId(),
                new SqlDialectTranslator.QueryParameters(request.queryParameters(), request.parameterMode()),
                ref -> columnNames(tables, ref), ref -> columnFields(tables, ref));

        String flociEndpoint = flociEndpoint();
        Staging staging = stage(request.projectId(), translation.tables(), translation.informationSchema(),
                translation.needsUnicodeCaseFold(), tables, flociEndpoint);
        String sql = translation.sql();
        String setup = staging.setup() + translation.setupSql();
        String emptySetup = staging.emptySetup() + translation.setupSql();
        rejectMissingStructFields(translation, sql, emptySetup, flociEndpoint);
        if (request.dryRun()) {
            List<Map<String, Object>> described;
            try {
                described = describeRows(sql, emptySetup, flociEndpoint);
            } catch (GcpException e) {
                throw orderByFieldFailure(translation, emptySetup, flociEndpoint, e);
            }
            return new Result(schemaOf(columns(described)), List.of(), "SELECT", staging.bytesProcessed(),
                    duplicateNames(translation, described));
        }

        DuckClient.DuckResult result;
        try {
            result = run(sql, setup, flociEndpoint, null);
        } catch (GcpException e) {
            throw orderByFieldFailure(translation, emptySetup, flociEndpoint, e);
        }
        if (result.columns() == null || hasDuplicateNames(result.columns())) {
            // A duplicate output name arrives as repeated keys in the JSON rows, so the values are read
            // again with positional aliases once the schema has BigQuery's names.
            List<Map<String, Object>> described = describeRows(sql, emptySetup, flociEndpoint);
            List<Column> columns = columns(described);
            return new Result(schemaOf(columns),
                    rejectNullArrayElements(columns, fetch(sql, setup, flociEndpoint, columns)), "SELECT",
                    staging.bytesProcessed(), duplicateNames(translation, described));
        }
        List<Column> columns = columns(result.columns().stream()
                .map(c -> Map.<String, Object>of("column_name", c.name(), "column_type", c.type()))
                .toList());
        return new Result(schemaOf(columns), rejectNullArrayElements(columns, decodeRows(result.rows(), columns)),
                "SELECT", staging.bytesProcessed(), translation.duplicateOutputNames());
    }

    /**
     * The select-list names a statement wrote more than once. The translator resolves them through nested
     * queries, which a schema read cannot once a star has been expanded; the described names are a fallback
     * for a shape the translator could not resolve.
     */
    private static List<String> duplicateNames(SqlDialectTranslator.ScriptTranslation translation,
                                               List<Map<String, Object>> described) {
        return translation.duplicateOutputNames().isEmpty()
                ? duplicateOutputNames(described) : translation.duplicateOutputNames();
    }

    /**
     * BigQuery fails {@code ORDER BY s.zz}, where the STRUCT select alias {@code s} has no field {@code zz}, while
     * it analyzes the query: {@code Field name zz does not exist in STRUCT<n INT64>}. The struct's type comes from
     * describing the query, so this fails before a row is read, and an empty input fails too. When the reference
     * belongs to a nested query, or the alias is not a STRUCT column of the result, the type is left out: a result
     * column of the same name may be another struct.
     */
    private void rejectMissingStructFields(SqlDialectTranslator.ScriptTranslation translation, String sql,
                                           String setup, String flociEndpoint) {
        if (translation.missingStructFields().isEmpty()) {
            return;
        }
        // The translator rewrites from the end of the query, so the first reference is recorded last.
        SqlDialectTranslator.MissingStructField missing = translation.missingStructFields().getLast();
        String type = "STRUCT";
        List<Column> columns = missing.outermost() ? columns(describeRows(sql, setup, flociEndpoint)) : List.of();
        for (Column column : columns) {
            TableFieldSchema field = column.field();
            if (column.name().equalsIgnoreCase(missing.alias()) && "RECORD".equals(field.getType())
                    && !"REPEATED".equals(field.getMode())) {
                type = structType(field, missing.anonymousFields(), List.of());
                break;
            }
        }
        throw SqlDialectTranslator.invalidQuery("Field name " + missing.field() + " does not exist in " + type);
    }

    /**
     * A STRUCT column's GoogleSQL type as BigQuery writes it in an error: an anonymous field by its type alone, at
     * any depth; {@code anonymousFields} holds their paths as name segments below {@code prefix}. A name that is not a
     * plain identifier is quoted, as in {@code `m._field_1` INT64}.
     */
    private static final Pattern PLAIN_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static String structType(TableFieldSchema field, Set<List<String>> anonymousFields, List<String> prefix) {
        List<String> parts = new ArrayList<>();
        for (TableFieldSchema child : field.getFields() != null ? field.getFields() : List.<TableFieldSchema>of()) {
            List<String> path = new ArrayList<>(prefix);
            path.add(child.getName().toLowerCase(Locale.ROOT));
            boolean struct = ("RECORD".equals(child.getType()) || "STRUCT".equals(child.getType()))
                    && !"REPEATED".equals(child.getMode());
            String childType = struct
                    ? structType(child, anonymousFields, path)
                    : InformationSchema.dataType(child);
            String name = PLAIN_IDENTIFIER.matcher(child.getName()).matches()
                    ? child.getName() : "`" + child.getName().replace("`", "\\`") + "`";
            parts.add(anonymousFields.contains(path) ? childType : name + " " + childType);
        }
        return "STRUCT<" + String.join(", ", parts) + ">";
    }

    /**
     * DuckDB fails to bind {@code ORDER BY s.zz} in its own words when {@code s} is a STRUCT column it reads from
     * a source (passed through under an alias, by a star, or named by the first query of a set operation) and has
     * no field {@code zz}. BigQuery names the struct's type: {@code Field name zz does not exist in STRUCT<n INT64>},
     * or {@code Cannot access field zz on a value with type INT64} past a scalar field. The type comes from
     * describing the query without its ORDER BY; any other failure is returned unchanged.
     */
    private GcpException orderByFieldFailure(SqlDialectTranslator.ScriptTranslation translation, String setup,
                                             String flociEndpoint, GcpException failure) {
        SqlDialectTranslator.OrderByFieldPaths orderBy = translation.orderByFieldPaths();
        if (orderBy == null || failure.getMessage() == null || !failure.getMessage().startsWith("Binder Error")) {
            return failure;
        }
        List<Column> columns;
        try {
            columns = columns(describeRows(orderBy.sqlWithoutOrderBy(), setup, flociEndpoint));
        } catch (GcpException e) {
            LOG.debugf("Could not describe the query without its ORDER BY: %s", e.getMessage());
            return failure;
        }
        for (List<String> path : orderBy.paths()) {
            TableFieldSchema field = null;
            for (Column column : columns) {
                if (column.name().equalsIgnoreCase(path.getFirst())) {
                    field = column.field();
                    break;
                }
            }
            for (int i = 1; field != null && i < path.size(); i++) {
                if ("REPEATED".equals(field.getMode())) {
                    break;
                }
                if (!"RECORD".equals(field.getType())) {
                    return SqlDialectTranslator.invalidQuery("Cannot access field " + path.get(i)
                            + " on a value with type " + InformationSchema.dataType(field));
                }
                TableFieldSchema child = null;
                List<TableFieldSchema> children = field.getFields() != null ? field.getFields() : List.of();
                for (TableFieldSchema candidate : children) {
                    if (candidate.getName().equalsIgnoreCase(path.get(i))) {
                        child = candidate;
                        break;
                    }
                }
                if (child == null) {
                    return SqlDialectTranslator.invalidQuery("Field name " + path.get(i) + " does not exist in "
                            + InformationSchema.dataType(field));
                }
                field = child;
            }
        }
        return failure;
    }

    /**
     * BigQuery lets an array hold NULL elements while the query runs, but fails the query when a
     * result row would contain one, naming the field and then each field that encloses it.
     */
    private static List<Map<String, Object>> rejectNullArrayElements(List<Column> columns,
                                                                      List<Map<String, Object>> rows) {
        List<TableFieldSchema> fields = columns.stream().map(Column::field).toList();
        for (Map<String, Object> row : rows) {
            String path = nullElementPath(fields, row, "");
            if (path != null) {
                StringBuilder message = new StringBuilder("Array cannot have a null element");
                String enclosing = path;
                while (true) {
                    message.append("; error in writing field ").append(enclosing);
                    int dot = enclosing.lastIndexOf('.');
                    if (dot < 0) {
                        break;
                    }
                    enclosing = enclosing.substring(0, dot);
                }
                throw SqlDialectTranslator.invalidQuery(message.toString());
            }
        }
        return rows;
    }

    /** The dotted path of the first array holding a NULL element in {@code record}, or null. */
    private static String nullElementPath(List<TableFieldSchema> fields, Map<?, ?> record, String prefix) {
        for (TableFieldSchema field : fields) {
            String path = prefix + field.getName();
            Object value = record.get(field.getName());
            List<?> values = List.of();
            if ("REPEATED".equals(field.getMode()) && value instanceof List<?> list) {
                values = list;
                for (Object element : list) {
                    if (element == null) {
                        return path;
                    }
                }
            } else if (value != null) {
                values = List.of(value);
            }
            if (field.getFields() != null) {
                for (Object element : values) {
                    if (element instanceof Map<?, ?> nested) {
                        String found = nullElementPath(field.getFields(), nested, path + ".");
                        if (found != null) {
                            return found;
                        }
                    }
                }
            }
        }
        return null;
    }

    @Override
    public DuckClient.ArrowIpc executeArrow(Request request, Tables tables) {
        SqlDialectTranslator.ScriptTranslation translation = SqlDialectTranslator.translateScript(request.sql(),
                request.projectId(), request.jobProjectId(), request.defaultDatasetId(),
                new SqlDialectTranslator.QueryParameters(request.queryParameters(), request.parameterMode()),
                ref -> columnNames(tables, ref), ref -> columnFields(tables, ref));
        String flociEndpoint = flociEndpoint();
        Staging staging = stage(request.projectId(), translation.tables(), translation.informationSchema(),
                translation.needsUnicodeCaseFold(), tables, flociEndpoint);
        rejectMissingStructFields(translation, translation.sql(), staging.emptySetup() + translation.setupSql(),
                flociEndpoint);
        DuckClient.ArrowIpc arrow;
        try {
            arrow = client.query(translation.sql(), staging.setup() + translation.setupSql(),
                    flociEndpoint, null, true).arrow();
        } catch (DuckClient.DuckSqlException e) {
            throw orderByFieldFailure(translation, staging.emptySetup(), flociEndpoint,
                    SqlDialectTranslator.invalidQuery(queryErrorMessage(e.getMessage())));
        }
        if (arrow == null) {
            throw GcpException.failedPrecondition("ARROW reads need a floci-duck image that returns Arrow IPC;"
                    + " update " + config.services().bigquery().duck().defaultImage());
        }
        return arrow;
    }

    @Override
    public DmlResult executeDml(Request request, Tables tables) {
        SqlDialectTranslator.ScriptTranslation script = SqlDialectTranslator.translateScript(request.sql(),
                request.projectId(), request.jobProjectId(), request.defaultDatasetId(),
                new SqlDialectTranslator.QueryParameters(request.queryParameters(), request.parameterMode()),
                ref -> columnNames(tables, ref));
        SqlDialectTranslator.Statement statement = script.finalStatement();
        if (!statement.isDml()) {
            throw SqlDialectTranslator.invalidQuery("A DML script must end with INSERT, UPDATE, DELETE or MERGE.");
        }
        SqlDialectTranslator.TableRef target = statement.target();
        Table targetTable = tables.table(target.datasetId(), target.tableId());
        if (targetTable.viewQuery() != null) {
            throw SqlDialectTranslator.invalidQuery("Cannot run " + statement.statementType() + " on view "
                    + request.projectId() + ":" + target.datasetId() + "." + target.tableId());
        }
        if (BigQueryService.isIngestionTimePartitioned(targetTable)) {
            script = SqlDialectTranslator.translateScript(request.sql(),
                    request.projectId(), request.jobProjectId(), request.defaultDatasetId(),
                    new SqlDialectTranslator.QueryParameters(request.queryParameters(), request.parameterMode()),
                    ref -> dmlColumnNames(tables, ref, target, targetTable));
            statement = script.finalStatement();
        }
        String flociEndpoint = flociEndpoint();
        Staging staging = stage(request.projectId(), script.tables(), script.informationSchema(),
                script.needsUnicodeCaseFold(), tables, flociEndpoint);
        String stagedTarget = DuckTypes.quoteIdentifier(target.datasetId()) + "."
                + DuckTypes.quoteIdentifier(target.tableId());
        String setup = dmlSetup(staging.setup() + script.setupSql(), stagedTarget, targetTable);
        if (request.dryRun()) {
            // EXPLAIN binds the statement against the staged tables without running it, so a missing
            // column or a malformed clause fails the dry run just as it would fail the real one.
            run("EXPLAIN " + script.sql(), dmlSetup(staging.emptySetup() + script.setupSql(), stagedTarget, targetTable),
                    flociEndpoint, null);
            return new DmlResult(0, 0, 0, 0, List.of(), staging.bytesProcessed());
        }
        String followup = "SELECT * FROM " + stagedTarget;
        DuckClient.DuckResult result = run(script.sql(), setup, flociEndpoint, followup);
        if (result.followup() == null || result.followup().columns() == null) {
            throw GcpException.failedPrecondition("DML needs a floci-duck image that supports follow-up"
                    + " statements; update " + config.services().bigquery().duck().defaultImage());
        }

        List<TableFieldSchema> targetFields = targetTable.getSchema() != null
                && targetTable.getSchema().getFields() != null ? targetTable.getSchema().getFields() : List.of();
        return decodeDmlResult(statement, result, targetFields, staging.bytesProcessed()).dmlResult();
    }

    @Override
    public TemporaryDmlResult executeTemporaryDml(Request request, Tables tables, String temporaryTable,
                                                   List<TableFieldSchema> targetFields) {
        SqlDialectTranslator.ScriptTranslation script = SqlDialectTranslator.translateScript(request.sql(),
                request.projectId(), request.defaultDatasetId(),
                new SqlDialectTranslator.QueryParameters(request.queryParameters(), request.parameterMode()));
        SqlDialectTranslator.Statement statement = script.finalStatement();
        if (!statement.isDml()) {
            throw SqlDialectTranslator.invalidQuery("A temporary table mutation must end with DML.");
        }
        String flociEndpoint = flociEndpoint();
        Staging staging = stage(request.projectId(), script.tables(), script.informationSchema(),
                script.needsUnicodeCaseFold(), tables, flociEndpoint);
        String followup = "SELECT * FROM " + DuckTypes.quoteIdentifier(temporaryTable);
        DuckClient.DuckResult result = run(script.sql(), staging.setup() + script.setupSql(), flociEndpoint, followup);
        if (result.followup() == null || result.followup().columns() == null) {
            throw GcpException.failedPrecondition("Temporary DML needs a floci-duck image that supports follow-up"
                    + " statements; update " + config.services().bigquery().duck().defaultImage());
        }
        return decodeDmlResult(statement, result, targetFields, staging.bytesProcessed());
    }

    private static TemporaryDmlResult decodeDmlResult(SqlDialectTranslator.Statement statement,
                                                        DuckClient.DuckResult result,
                                                        List<TableFieldSchema> targetFields,
                                                        long totalBytesProcessed) {
        long inserted = 0;
        long updated = 0;
        long deleted = 0;
        long affected;
        if (statement.kind() == SqlDialectTranslator.StatementKind.MERGE) {
            for (Map<String, Object> row : result.rows()) {
                switch (String.valueOf(row.get("merge_action"))) {
                    case "INSERT" -> inserted++;
                    case "UPDATE" -> updated++;
                    case "DELETE" -> deleted++;
                    default -> {
                        // merge_action is always one of the three
                    }
                }
            }
            affected = inserted + updated + deleted;
        } else {
            affected = rowCount(result);
            switch (statement.kind()) {
                case INSERT -> inserted = affected;
                case UPDATE -> updated = affected;
                default -> deleted = affected;
            }
        }

        List<Column> columns = new ArrayList<>();
        for (DuckClient.DuckColumn duckColumn : result.followup().columns()) {
            DuckTypes.DuckType type = DuckTypes.parse(duckColumn.type());
            TableFieldSchema field = targetFields.stream()
                    .filter(f -> f.getName().equalsIgnoreCase(duckColumn.name()))
                    .findFirst()
                    .orElseGet(() -> DuckTypes.toField(duckColumn.name(), type));
            columns.add(new Column(field.getName(), type, field, DuckTypes.projection(type)));
        }
        List<Map<String, Object>> tableRows = new ArrayList<>();
        for (Map<String, Object> typedRow : result.followup().rows()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (Column column : columns) {
                Object value = typedRow.containsKey(column.name()) ? typedRow.get(column.name())
                        : typedRow.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(column.name()))
                                .map(Map.Entry::getValue).findFirst().orElse(null);
                row.put(column.name(), DuckTypes.decodeTyped(value, column.type(), column.field()));
            }
            tableRows.add(row);
        }
        // BigQuery fails a DML statement that would store a NULL array element, and writes nothing.
        rejectNullArrayElements(columns, tableRows);
        validateRequiredFields(targetFields, tableRows);
        DmlResult dmlResult = new DmlResult(affected, inserted, updated, deleted, tableRows, totalBytesProcessed);
        return new TemporaryDmlResult(schemaOf(columns), dmlResult);
    }

    private static void validateRequiredFields(List<TableFieldSchema> fields, List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            validateRequiredFields(fields, row, "");
        }
    }

    private static void validateRequiredFields(List<TableFieldSchema> fields, Map<?, ?> row, String prefix) {
        for (TableFieldSchema field : fields) {
            String path = prefix + field.getName();
            Object value = row.get(field.getName());
            if (value == null && "REQUIRED".equals(field.getMode())) {
                throw SqlDialectTranslator.invalidQuery("NULL value for required field " + path);
            }
            if (value == null || field.getFields() == null) {
                continue;
            }
            if ("REPEATED".equals(field.getMode()) && value instanceof List<?> values) {
                for (Object element : values) {
                    if (element instanceof Map<?, ?> nested) {
                        validateRequiredFields(field.getFields(), nested, path + ".");
                    }
                }
            } else if (value instanceof Map<?, ?> nested) {
                validateRequiredFields(field.getFields(), nested, path + ".");
            }
        }
    }

    // ── Load jobs ─────────────────────────────────────────────────────────────

    private static final Pattern GENERIC_CSV_COLUMN = Pattern.compile("column\\d+");
    /** A column the translator left anonymous in a nested select list (see {@code nameAnonymousColumns}). */
    private static final Pattern ANONYMOUS_COLUMN = Pattern.compile("\\$col\\d+");

    @Override
    public Result readFiles(LoadSource source) {
        String flociEndpoint = flociEndpoint();
        StringBuilder urls = new StringBuilder("[");
        for (int i = 0; i < source.fileIds().size(); i++) {
            if (i > 0) {
                urls.append(", ");
            }
            urls.append(DuckTypes.quoteLiteral(flociEndpoint + "/_floci-gcp/bigquery/projects/"
                    + encode(source.projectId()) + "/load-files/" + source.fileIds().get(i)));
        }
        urls.append(']');

        String sql;
        List<TableFieldSchema> schema = source.schema();
        switch (source.format()) {
            case "CSV" -> sql = schema != null ? csvWithSchema(urls.toString(), source, schema)
                    : "SELECT * FROM read_csv(" + urls + csvOptions(source) + headerOptions(source)
                    + ", auto_detect = true)";
            case "NEWLINE_DELIMITED_JSON" -> sql = "SELECT * FROM read_json(" + urls
                    + ", format = 'newline_delimited'" + (source.ignoreBadRecords() ? ", ignore_errors = true" : "")
                    + ")";
            case "PARQUET" -> sql = "SELECT * FROM read_parquet(" + urls + ")";
            default -> throw loadError("Unsupported source format " + source.format());
        }

        DuckClient.DuckResult result;
        try {
            result = client.query(sql, SESSION_SETUP, flociEndpoint);
        } catch (DuckClient.DuckSqlException e) {
            throw loadError("Error while reading data, error message: " + queryErrorMessage(e.getMessage()));
        }
        if (result.columns() == null) {
            throw GcpException.failedPrecondition("Load jobs need a floci-duck image that reports column types;"
                    + " update " + config.services().bigquery().duck().defaultImage());
        }

        boolean autodetected = schema == null && !source.format().equals("PARQUET");
        boolean genericNames = source.format().equals("CSV") && autodetected
                && result.columns().stream().allMatch(c -> GENERIC_CSV_COLUMN.matcher(c.name()).matches());
        List<String> sourceNames = new ArrayList<>();
        List<Column> columns = new ArrayList<>();
        for (int i = 0; i < result.columns().size(); i++) {
            DuckClient.DuckColumn duckColumn = result.columns().get(i);
            DuckTypes.DuckType type = DuckTypes.parse(duckColumn.type());
            TableFieldSchema field = fieldFor(schema, duckColumn.name(), i);
            if (field == null) {
                field = DuckTypes.toField(duckColumn.name(), type);
                if (autodetected && "DATETIME".equals(field.getType())) {
                    field.setType("TIMESTAMP"); // auto-detection infers TIMESTAMP, DATE and TIME, never DATETIME
                }
                field.setName(genericNames ? genericName(field, i) : sanitize(duckColumn.name()));
            }
            sourceNames.add(duckColumn.name());
            columns.add(new Column(field.getName(), type, field, DuckTypes.projection(type)));
        }

        List<Map<String, Object>> rows = new ArrayList<>(result.rows().size());
        for (Map<String, Object> typedRow : result.rows()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                Column column = columns.get(i);
                row.put(column.name(), DuckTypes.decodeTyped(typedRow.get(sourceNames.get(i)), column.type(),
                        column.field()));
            }
            rows.add(row);
        }
        return new Result(schemaOf(columns), rows, "LOAD", 0);
    }

    /** CSV with a known schema: every column is read with its staging type, then converted. */
    private static String csvWithSchema(String urls, LoadSource source, List<TableFieldSchema> schema) {
        StringBuilder columns = new StringBuilder();
        StringBuilder projection = new StringBuilder();
        List<String> keepEmpty = new ArrayList<>();
        for (int i = 0; i < schema.size(); i++) {
            TableFieldSchema field = schema.get(i);
            if ("RECORD".equals(field.getType()) || "REPEATED".equals(field.getMode())) {
                throw loadError("CSV load jobs cannot load nested or repeated field " + field.getName());
            }
            String column = DuckTypes.quoteIdentifier(field.getName());
            boolean text = DuckTypes.stagedAsText(field);
            if (i > 0) {
                columns.append(", ");
                projection.append(", ");
            }
            columns.append(DuckTypes.quoteLiteral(field.getName())).append(": ")
                    .append(DuckTypes.quoteLiteral(text ? "VARCHAR" : DuckTypes.duckType(field)));
            projection.append(text ? DuckTypes.convertStagedText(field, column) + " AS " + column : column);
            if ("STRING".equals(field.getType())) {
                keepEmpty.add(DuckTypes.quoteLiteral(field.getName()));
            }
        }
        String nulls = source.nullMarker() != null
                ? ", nullstr = " + DuckTypes.quoteLiteral(source.nullMarker())
                // Without a null marker, an empty STRING value stays an empty string.
                : keepEmpty.isEmpty() ? "" : ", force_not_null = [" + String.join(", ", keepEmpty) + "]";
        int skip = source.skipLeadingRows() != null ? source.skipLeadingRows() : 0;
        return "SELECT " + projection + " FROM read_csv(" + urls + csvOptions(source) + ", header = false, skip = "
                + skip + ", columns = {" + columns + "}" + nulls + ")";
    }

    private static String csvOptions(LoadSource source) {
        StringBuilder options = new StringBuilder();
        String delimiter = source.fieldDelimiter();
        if (delimiter != null && !delimiter.isEmpty()) {
            if (delimiter.equals("\\t") || delimiter.equalsIgnoreCase("tab")) {
                delimiter = "\t";
            }
            options.append(", delim = ").append(DuckTypes.quoteLiteral(delimiter));
        }
        if (source.quote() != null) {
            String quote = source.quote().isEmpty() ? "" : source.quote().substring(0, 1);
            options.append(", quote = ").append(DuckTypes.quoteLiteral(quote))
                    .append(", escape = ").append(DuckTypes.quoteLiteral(quote));
        }
        if (source.allowJaggedRows()) {
            options.append(", null_padding = true");
        }
        if (source.ignoreBadRecords()) {
            options.append(", ignore_errors = true");
        }
        if (source.encoding() != null && source.encoding().equalsIgnoreCase("ISO-8859-1")) {
            options.append(", encoding = 'latin-1'");
        }
        return options.toString();
    }

    /**
     * skipLeadingRows with auto-detection: unset detects a header in the first row, 0 means
     * no header, N skips N-1 rows and detects a header in row N.
     */
    private static String headerOptions(LoadSource source) {
        Integer skip = source.skipLeadingRows();
        if (skip == null) {
            return "";
        }
        if (skip == 0) {
            return ", header = false";
        }
        return ", skip = " + (skip - 1);
    }

    private static TableFieldSchema fieldFor(List<TableFieldSchema> schema, String name, int index) {
        if (schema == null) {
            return null;
        }
        return schema.stream().filter(f -> f.getName().equalsIgnoreCase(name)).findFirst()
                .orElse(index < schema.size() ? schema.get(index) : null);
    }

    /** Auto-detected columns of a header-less CSV get generic names such as {@code string_field_0}. */
    private static String genericName(TableFieldSchema field, int index) {
        String type = switch (field.getType()) {
            case "INTEGER" -> "int64";
            case "FLOAT" -> "double";
            case "BOOLEAN" -> "bool";
            default -> field.getType().toLowerCase(Locale.ROOT);
        };
        return type + "_field_" + index;
    }

    /** "spaces will be replaced with underscores": header names are made valid column names. */
    private static String sanitize(String name) {
        String cleaned = name.trim().replaceAll("[^A-Za-z0-9_]", "_");
        if (cleaned.isEmpty() || Character.isDigit(cleaned.charAt(0))) {
            cleaned = "_" + cleaned;
        }
        return cleaned;
    }

    private static GcpException loadError(String message) {
        return GcpException.invalidArgument(message).withReason("invalid");
    }

    private static List<Map<String, Object>> decodeRows(List<Map<String, Object>> typedRows, List<Column> columns) {
        List<Map<String, Object>> rows = new ArrayList<>(typedRows.size());
        for (Map<String, Object> typedRow : typedRows) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (Column column : columns) {
                row.put(column.field().getName(), DuckTypes.decodeTyped(typedRow.get(column.name()), column.type(),
                        column.field()));
            }
            rows.add(row);
        }
        return rows;
    }

    private record Staging(String setup, String emptySetup, long bytesProcessed) {}

    /**
     * Ends the trusted part of the setup. Staging reads table rows from floci-gcp over HTTP, but the
     * caller's SQL must not: DuckDB's file and URL functions would let a query read another
     * project's rows from the internal route, or anything else the sidecar can reach. Once this
     * runs, the session can no longer touch files or URLs, and the setting cannot be changed back.
     * It also stops DuckDB from loading extensions, autoloading included, so an extension a query
     * needs has to be loaded in the trusted part, before this (the {@code SET TimeZone} that opens
     * the setup is what loads ICU today).
     */
    static final String LOCK_EXTERNAL_ACCESS = "SET enable_external_access = false;\nSET lock_configuration = true;\n";

    /**
     * Opens every session: UTC for TIMESTAMP values, and BigQuery's default NULL order, first for an ascending
     * sort and last for a descending one, which also applies inside {@code OVER (...)} and ordered aggregates.
     */
    static final String SESSION_SETUP = "SET TimeZone = 'UTC';\n"
            + "SET default_null_order = 'nulls_first_on_asc_last_on_desc';\n";

    /**
     * Setup SQL creating every referenced table in DuckDB. Views are expanded: their own
     * references are staged first, then the view is created from its translated query. Views come
     * from callers' SQL, so they are created after {@link #LOCK_EXTERNAL_ACCESS}, with the tables.
     */
    private Staging stage(String projectId, Set<SqlDialectTranslator.TableRef> refs, Tables tables,
                          String flociEndpoint) {
        return stage(projectId, refs, Set.of(), false, tables, flociEndpoint);
    }

    private Staging stage(String projectId, Set<SqlDialectTranslator.TableRef> refs,
                          Set<InformationSchema.Ref> informationSchema, boolean needsUnicodeCaseFold,
                          Tables tables, String flociEndpoint) {
        StringBuilder setup = new StringBuilder(SESSION_SETUP);
        StringBuilder emptySetup = new StringBuilder(SESSION_SETUP);
        StringBuilder views = new StringBuilder();
        long[] bytes = {0};
        boolean[] unicodeCaseFold = {needsUnicodeCaseFold};
        Set<String> schemas = new HashSet<>();
        Set<SqlDialectTranslator.TableRef> staged = new HashSet<>();
        for (SqlDialectTranslator.TableRef ref : refs) {
            stageOne(projectId, ref, tables, flociEndpoint, setup, emptySetup, views, schemas, staged,
                    new LinkedHashSet<>(), unicodeCaseFold, bytes);
        }
        if (!informationSchema.isEmpty()) {
            setup.append("CREATE SCHEMA IF NOT EXISTS ").append(DuckTypes.quoteIdentifier(InformationSchema.SCHEMA))
                    .append(";\n");
            emptySetup.append("CREATE SCHEMA IF NOT EXISTS ").append(DuckTypes.quoteIdentifier(InformationSchema.SCHEMA))
                    .append(";\n");
        }
        for (InformationSchema.Ref ref : informationSchema) {
            boolean empty = tables.informationSchema(ref).isEmpty();
            StringBuilder url = new StringBuilder(flociEndpoint).append("/_floci-gcp/bigquery/projects/")
                    .append(encode(projectId)).append("/information-schema/").append(ref.view()).append("?");
            if (ref.dataset() != null) {
                url.append("dataset=").append(encode(ref.dataset()));
            } else {
                url.append("region=").append(encode(ref.region()));
            }
            String tableTarget = DuckTypes.quoteIdentifier(InformationSchema.SCHEMA) + "." + DuckTypes.quoteIdentifier(ref.stagedName());
            setup.append(stageRows(tableTarget, InformationSchema.columns(ref.view()), empty, url.toString())).append('\n');
            emptySetup.append(stageRows(tableTarget, InformationSchema.columns(ref.view()), true, url.toString())).append('\n');
        }
        if (unicodeCaseFold[0]) {
            setup.append(UnicodeCaseFold.setupSql());
            emptySetup.append(UnicodeCaseFold.setupSql());
        }
        setup.append(LOCK_EXTERNAL_ACCESS).append(views);
        emptySetup.append(LOCK_EXTERNAL_ACCESS).append(views);
        return new Staging(setup.toString(), emptySetup.toString(), bytes[0]);
    }

    private void stageOne(String projectId, SqlDialectTranslator.TableRef ref, Tables tables, String flociEndpoint,
                          StringBuilder setup, StringBuilder emptySetup, StringBuilder views, Set<String> schemas,
                          Set<SqlDialectTranslator.TableRef> staged, Set<SqlDialectTranslator.TableRef> expanding,
                          boolean[] unicodeCaseFold, long[] bytes) {
        if (staged.contains(ref)) {
            return;
        }
        if (!expanding.add(ref)) {
            throw SqlDialectTranslator.invalidQuery("View " + projectId + ":" + ref.datasetId() + "." + ref.tableId()
                    + " references itself");
        }
        if (ref.tableId().endsWith("*")) {
            stageWildcard(projectId, ref, tables, flociEndpoint, setup, emptySetup, views, schemas, staged, expanding,
                    unicodeCaseFold, bytes);
            return;
        }
        Table table = tables.table(ref.datasetId(), ref.tableId());
        String viewQuery = table.viewQuery();
        if (schemas.add(ref.datasetId())) {
            setup.append("CREATE SCHEMA IF NOT EXISTS ").append(DuckTypes.quoteIdentifier(ref.datasetId()))
                    .append(";\n");
            emptySetup.append("CREATE SCHEMA IF NOT EXISTS ").append(DuckTypes.quoteIdentifier(ref.datasetId()))
                    .append(";\n");
        }
        if (viewQuery != null) {
            SqlDialectTranslator.Translation view = SqlDialectTranslator.translate(viewQuery, projectId, projectId,
                    ref.datasetId(), SqlDialectTranslator.QueryParameters.none(),
                    dependency -> columnNames(tables, dependency), dependency -> columnFields(tables, dependency));
            if (!view.duplicateOutputNames().isEmpty()) {
                throw SqlDialectTranslator.invalidQuery("Duplicate column name '"
                        + view.duplicateOutputNames().getFirst() + "' in definition of view '"
                        + ref.datasetId() + "." + ref.tableId() + "'");
            }
            unicodeCaseFold[0] |= view.needsUnicodeCaseFold();
            for (SqlDialectTranslator.TableRef dependency : view.tables()) {
                stageOne(projectId, dependency, tables, flociEndpoint, setup, emptySetup, views, schemas, staged,
                        expanding, unicodeCaseFold, bytes);
            }
            // Dependencies are appended first, so views stay in an order DuckDB can create them in.
            views.append("CREATE VIEW ").append(DuckTypes.quoteIdentifier(ref.datasetId())).append('.')
                    .append(DuckTypes.quoteIdentifier(ref.tableId())).append(" AS ").append(view.sql()).append(";\n");
        } else {
            List<Map<String, Object>> rows = tables.rows(ref.datasetId(), ref.tableId());
            bytes[0] += estimateBytes(rows);
            setup.append(stageTable(projectId, ref, table, rows.isEmpty(), flociEndpoint)).append('\n');
            emptySetup.append(stageTable(projectId, ref, table, true, flociEndpoint)).append('\n');
        }
        staged.add(ref);
        expanding.remove(ref);
    }

    /** Expands a BigQuery terminal-prefix wildcard into a temporary DuckDB view. */
    private void stageWildcard(String projectId, SqlDialectTranslator.TableRef wildcard, Tables tables,
                               String flociEndpoint, StringBuilder setup, StringBuilder emptySetup,
                               StringBuilder views, Set<String> schemas, Set<SqlDialectTranslator.TableRef> staged,
                               Set<SqlDialectTranslator.TableRef> expanding, boolean[] unicodeCaseFold,
                               long[] bytes) {
        String prefix = wildcard.tableId().substring(0, wildcard.tableId().length() - 1);
        List<Table> candidates = tables.listTables(wildcard.datasetId()).stream()
                .filter(table -> table.getTableReference() != null
                        && table.getTableReference().getTableId() != null
                        && table.getTableReference().getTableId().startsWith(prefix))
                .sorted(Comparator.comparing(table -> table.getTableReference().getTableId()))
                .toList();
        if (candidates.stream().anyMatch(table -> "VIEW".equalsIgnoreCase(table.getType())
                || "MATERIALIZED_VIEW".equalsIgnoreCase(table.getType()))) {
            throw SqlDialectTranslator.invalidQuery("Wildcard table " + wildcard.datasetId() + "."
                    + wildcard.tableId() + " matched a view");
        }
        List<Table> matches = candidates.stream()
                .filter(table -> table.getType() == null || "TABLE".equalsIgnoreCase(table.getType()))
                .toList();
        if (matches.isEmpty()) {
            throw GcpException.notFound("Not found: Table " + projectId + ":" + wildcard.datasetId() + "."
                    + wildcard.tableId() + ".");
        }

        for (Table match : matches) {
            String tableId = match.getTableReference().getTableId();
            stageOne(projectId, new SqlDialectTranslator.TableRef(wildcard.datasetId(), tableId), tables,
                    flociEndpoint, setup, emptySetup, views, schemas, staged, expanding, unicodeCaseFold, bytes);
        }
        String ddl = wildcardView(wildcard, matches);
        setup.append(ddl).append('\n');
        emptySetup.append(ddl).append('\n');
        staged.add(wildcard);
        expanding.remove(wildcard);
    }

    private static String wildcardView(SqlDialectTranslator.TableRef wildcard, List<Table> matches) {
        String dataset = DuckTypes.quoteIdentifier(wildcard.datasetId());
        StringBuilder ddl = new StringBuilder("CREATE VIEW ").append(dataset).append('.')
                .append(DuckTypes.quoteIdentifier(wildcard.tableId())).append(" AS ");
        String prefix = wildcard.tableId().substring(0, wildcard.tableId().length() - 1);
        Table schemaTable = matches.stream().max(Comparator
                        .comparingLong(DuckSqlEngine::creationTime)
                        .thenComparing(table -> table.getTableReference().getTableId()))
                .orElseThrow();
        List<TableFieldSchema> columns = stagingFields(schemaTable);
        int[] generatedAliases = {0};
        for (int i = 0; i < matches.size(); i++) {
            if (i > 0) {
                ddl.append(" UNION ALL ");
            }
            Table match = matches.get(i);
            String tableId = match.getTableReference().getTableId();
            String suffix = tableId.substring(prefix.length());
            Map<String, TableFieldSchema> sourceFields = new LinkedHashMap<>();
            stagingFields(match).forEach(field -> sourceFields.put(field.getName().toLowerCase(Locale.ROOT), field));
            ddl.append("SELECT ");
            for (int column = 0; column < columns.size(); column++) {
                if (column > 0) {
                    ddl.append(", ");
                }
                TableFieldSchema expected = columns.get(column);
                TableFieldSchema source = sourceFields.get(expected.getName().toLowerCase(Locale.ROOT));
                if (source == null) {
                    if (!"NULLABLE".equals(fieldMode(expected))) {
                        throw SqlDialectTranslator.invalidQuery("Wildcard table schema mismatch: field "
                                + expected.getName() + " is missing from " + tableId);
                    }
                    ddl.append("CAST(NULL AS ").append(DuckTypes.duckType(expected)).append(")");
                } else {
                    if (!wildcardFieldsCompatible(expected, source)) {
                        throw SqlDialectTranslator.invalidQuery("Wildcard table schema mismatch for field "
                                + expected.getName() + " in " + tableId);
                    }
                    ddl.append(wildcardFieldProjection(expected, source,
                            DuckTypes.quoteIdentifier(source.getName()), generatedAliases));
                }
                ddl.append(" AS ").append(DuckTypes.quoteIdentifier(expected.getName()));
            }
            ddl.append(", ").append(DuckTypes.quoteLiteral(suffix))
                    .append(" AS ").append(DuckTypes.quoteIdentifier("_TABLE_SUFFIX"))
                    .append(" FROM ").append(dataset).append('.')
                    .append(DuckTypes.quoteIdentifier(tableId));
        }
        return ddl.append(';').toString();
    }

    private static String wildcardFieldProjection(TableFieldSchema expected, TableFieldSchema source,
                                                   String expression, int[] generatedAliases) {
        if (!"RECORD".equalsIgnoreCase(expected.getType())) {
            return expression;
        }
        if ("REPEATED".equals(fieldMode(expected))) {
            String item = "_floci_wildcard_item_" + generatedAliases[0]++;
            return "list_transform(" + expression + ", " + item + " -> "
                    + wildcardStructProjection(expected, source, item, generatedAliases) + ")";
        }
        return "CASE WHEN " + expression + " IS NULL THEN NULL ELSE "
                + wildcardStructProjection(expected, source, expression, generatedAliases) + " END";
    }

    private static String wildcardStructProjection(TableFieldSchema expected, TableFieldSchema source,
                                                    String expression, int[] generatedAliases) {
        List<TableFieldSchema> expectedChildren = expected.getFields() != null ? expected.getFields() : List.of();
        List<TableFieldSchema> sourceChildren = source.getFields() != null ? source.getFields() : List.of();
        Map<String, TableFieldSchema> sourceByName = new LinkedHashMap<>();
        sourceChildren.forEach(field -> sourceByName.put(field.getName().toLowerCase(Locale.ROOT), field));
        List<String> fields = new ArrayList<>(expectedChildren.size());
        for (TableFieldSchema expectedChild : expectedChildren) {
            TableFieldSchema sourceChild = sourceByName.get(expectedChild.getName().toLowerCase(Locale.ROOT));
            String value;
            if (sourceChild == null) {
                value = "CAST(NULL AS " + DuckTypes.duckType(expectedChild) + ")";
            } else {
                String child = expression + "." + DuckTypes.quoteIdentifier(sourceChild.getName());
                value = wildcardFieldProjection(expectedChild, sourceChild, child, generatedAliases);
            }
            fields.add(DuckTypes.quoteIdentifier(expectedChild.getName()) + " := " + value);
        }
        return "struct_pack(" + String.join(", ", fields) + ")";
    }

    private static long creationTime(Table table) {
        try {
            return Long.parseLong(table.getCreationTime());
        } catch (NumberFormatException | NullPointerException ignored) {
            // Tables without a valid creation time sort before tables with one.
            return Long.MIN_VALUE;
        }
    }

    private static boolean wildcardFieldsCompatible(TableFieldSchema expected, TableFieldSchema actual) {
        if (!String.valueOf(expected.getType()).equalsIgnoreCase(String.valueOf(actual.getType()))
                || !fieldMode(expected).equals(fieldMode(actual))) {
            return false;
        }
        List<TableFieldSchema> expectedChildren = expected.getFields() != null ? expected.getFields() : List.of();
        List<TableFieldSchema> actualChildren = actual.getFields() != null ? actual.getFields() : List.of();
        Map<String, TableFieldSchema> actualByName = new LinkedHashMap<>();
        actualChildren.forEach(field -> actualByName.put(field.getName().toLowerCase(Locale.ROOT), field));
        for (TableFieldSchema expectedChild : expectedChildren) {
            TableFieldSchema actualChild = actualByName.get(expectedChild.getName().toLowerCase(Locale.ROOT));
            if (actualChild == null && !"NULLABLE".equals(fieldMode(expectedChild))) {
                return false;
            }
            if (actualChild != null && !wildcardFieldsCompatible(expectedChild, actualChild)) {
                return false;
            }
        }
        return true;
    }

    private static String fieldMode(TableFieldSchema field) {
        return field.getMode() == null ? "NULLABLE" : field.getMode().toUpperCase(Locale.ROOT);
    }

    /** Lower-cased column names of a table, or null when it does not exist or has no schema. */
    private static Set<String> columnNames(Tables tables, SqlDialectTranslator.TableRef ref) {
        List<TableFieldSchema> fields = columnFields(tables, ref);
        if (fields == null) {
            return null;
        }
        Set<String> names = new HashSet<>();
        for (TableFieldSchema field : fields) {
            names.add(field.getName().toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private static List<TableFieldSchema> columnFields(Tables tables, SqlDialectTranslator.TableRef ref) {
        Table table;
        try {
            table = tables.table(ref.datasetId(), ref.tableId());
        } catch (GcpException e) {
            if (e.getHttpStatus() == 404) {
                // Missing sources stay unknown until staging reports the table's not-found error.
                return null;
            }
            throw e;
        }
        if (table == null || table.getSchema() == null || table.getSchema().getFields() == null) {
            return null;
        }
        return stagingFields(table);
    }

    private static Set<String> dmlColumnNames(Tables tables, SqlDialectTranslator.TableRef ref,
                                               SqlDialectTranslator.TableRef target, Table targetTable) {
        Set<String> names = columnNames(tables, ref);
        if (names == null || !ref.equals(target) || !BigQueryService.isIngestionTimePartitioned(targetTable)) {
            return names;
        }
        Set<String> dmlNames = new HashSet<>(names);
        dmlNames.add(BigQueryService.DML_ORIGINAL_PARTITION_TIME.toLowerCase(Locale.ROOT));
        dmlNames.add(BigQueryService.DML_ORIGINAL_UNPARTITIONED.toLowerCase(Locale.ROOT));
        return dmlNames;
    }

    private static String dmlSetup(String setup, String stagedTarget, Table targetTable) {
        if (!BigQueryService.isIngestionTimePartitioned(targetTable)) {
            return setup;
        }
        String originalPartition = DuckTypes.quoteIdentifier(BigQueryService.DML_ORIGINAL_PARTITION_TIME);
        String originalUnpartitioned = DuckTypes.quoteIdentifier(BigQueryService.DML_ORIGINAL_UNPARTITIONED);
        String partitionTime = DuckTypes.quoteIdentifier(BigQueryService.PARTITION_TIME);
        return setup + "\nALTER TABLE " + stagedTarget + " ADD COLUMN " + originalPartition + " TIMESTAMP;\n"
                + "ALTER TABLE " + stagedTarget + " ADD COLUMN " + originalUnpartitioned + " BOOLEAN;\n"
                + "UPDATE " + stagedTarget + " SET " + originalPartition + " = " + partitionTime + ", "
                + originalUnpartitioned + " = " + partitionTime + " IS NULL;";
    }

    private static TableSchema schemaOf(List<Column> columns) {
        return new TableSchema(columns.stream().map(Column::field).toList());
    }

    private List<Map<String, Object>> describeRows(String sql, String setup, String flociEndpoint) {
        return run("DESCRIBE " + sql, setup, flociEndpoint, null).rows();
    }

    /** The select-list names written more than once, before BigQuery's suffixes are applied. */
    private static List<String> duplicateOutputNames(List<Map<String, Object>> described) {
        List<String> duplicates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> row : described) {
            String name = String.valueOf(row.get("column_name"));
            if (ANONYMOUS_COLUMN.matcher(name).matches()) {
                continue;
            }
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                duplicates.add(name);
            }
        }
        return duplicates;
    }

    /**
     * Result columns from {@code column_name}/{@code column_type} pairs. BigQuery suffixes a duplicate
     * output name ({@code b, b_1}), so the columns take the same names. Anonymous columns a {@code *}
     * brought up from a nested select list are named {@code f0_}, {@code f1_}, ... in output order, as
     * BigQuery names them, skipping names other columns of the result already have; a duplicate among
     * those names is an error, as BigQuery reports it.
     */
    private static List<Column> columns(List<Map<String, Object>> described) {
        List<String> sourceNames = new ArrayList<>(described.size());
        List<DuckTypes.DuckType> types = new ArrayList<>(described.size());
        Set<String> named = new HashSet<>();
        Set<String> anonymousSeen = new HashSet<>();
        Set<String> duplicateAnonymous = new LinkedHashSet<>();
        for (Map<String, Object> row : described) {
            String name = String.valueOf(row.get("column_name"));
            sourceNames.add(name);
            types.add(DuckTypes.parse(String.valueOf(row.get("column_type"))));
            if (ANONYMOUS_COLUMN.matcher(name).matches()) {
                if (!anonymousSeen.add(name.toLowerCase(Locale.ROOT))) {
                    duplicateAnonymous.add(name);
                }
            } else {
                named.add(name.toLowerCase(Locale.ROOT));
            }
        }
        if (!duplicateAnonymous.isEmpty()) {
            throw SqlDialectTranslator.invalidQuery("Duplicate column names in the result are not supported."
                    + " Found duplicate(s): " + String.join(", ", duplicateAnonymous));
        }
        List<String> outputs = new ArrayList<>(sourceNames);
        int anonymous = 0;
        for (int i = 0; i < outputs.size(); i++) {
            if (!ANONYMOUS_COLUMN.matcher(outputs.get(i)).matches()) {
                continue;
            }
            while (named.contains("f" + anonymous + "_")) {
                anonymous++;
            }
            outputs.set(i, "f" + anonymous++ + "_");
        }
        List<String> names = SqlDialectTranslator.bigQueryOutputNames(outputs);
        List<Column> columns = new ArrayList<>(described.size());
        for (int i = 0; i < sourceNames.size(); i++) {
            DuckTypes.DuckType type = types.get(i);
            columns.add(new Column(sourceNames.get(i), type, DuckTypes.toField(names.get(i), type),
                    DuckTypes.projection(type)));
        }
        return columns;
    }

    private static boolean hasDuplicateNames(List<DuckClient.DuckColumn> columns) {
        Set<String> seen = new HashSet<>();
        for (DuckClient.DuckColumn column : columns) {
            if (!seen.add(column.name().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private List<Map<String, Object>> fetch(String sql, String setup, String flociEndpoint, List<Column> columns) {
        if (columns.isEmpty()) {
            return List.of();
        }
        StringBuilder select = new StringBuilder("SELECT ");
        StringBuilder aliases = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            String alias = DuckTypes.quoteIdentifier("c" + i);
            if (i > 0) {
                select.append(", ");
                aliases.append(", ");
            }
            select.append(DuckTypes.project(columns.get(i).projection(), alias)).append(" AS ").append(alias);
            aliases.append(alias);
        }
        select.append(" FROM (").append(sql).append(") AS \"_q\"(").append(aliases).append(')');

        List<Map<String, Object>> raw = run(select.toString(), setup, flociEndpoint, null).rows();
        List<Map<String, Object>> rows = new ArrayList<>(raw.size());
        for (Map<String, Object> rawRow : raw) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                Column column = columns.get(i);
                row.put(column.field().getName(), DuckTypes.decode(rawRow.get("c" + i), column.projection(), column.type(),
                        column.field()));
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * The affected-row count DuckDB reports for INSERT, UPDATE and DELETE. The column name and
     * type come from the sidecar, so neither is assumed: a missing, null or non-numeric value
     * means the image does not report counts rather than a reason to fail the statement.
     */
    private static long rowCount(DuckClient.DuckResult result) {
        if (result.rows().isEmpty()) {
            return 0;
        }
        Object count = result.rows().getFirst().get("Count");
        return count instanceof Number number ? number.longValue() : 0;
    }

    private DuckClient.DuckResult run(String sql, String setup, String flociEndpoint, String followupSql) {
        try {
            return client.query(sql, setup, flociEndpoint, followupSql);
        } catch (DuckClient.DuckSqlException e) {
            throw engineFailure(e.getMessage());
        }
    }

    /**
     * Not every failure the sidecar reports is a problem with the caller's SQL. Staging fetches the
     * rows over HTTP, so an unreachable callback URL or an out-of-memory arrives on the same channel
     * as a syntax error. Reporting those as invalidQuery tells the caller their query is wrong and
     * stops SDKs retrying something that is really an infrastructure fault.
     */
    private static GcpException engineFailure(String message) {
        String text = message == null ? "" : message;
        if (text.startsWith("IO Error") || text.contains("HTTP Error") || text.contains("Connection Error")) {
            return GcpException.unavailable("The BigQuery SQL engine could not read the staged table data: "
                    + text);
        }
        if (text.contains("Out of Memory Error")) {
            return GcpException.internal("The BigQuery SQL engine ran out of memory: " + text);
        }
        return SqlDialectTranslator.invalidQuery(queryErrorMessage(text));
    }

    private static final Pattern COLUMN_LIST_MISS = Pattern.compile(
            "Column \"?(?<column>[^\"]+)\"? in (?:EXCLUDE|REPLACE) list not found in FROM clause");
    private static final Pattern COLUMN_LIST_KIND = Pattern.compile(" in (EXCLUDE|REPLACE) list not found");
    private static final Pattern COLUMN_IN_BOTH_LISTS = Pattern.compile(
            "Column \"?(?<column>[^\"]+)\"? cannot occur in both EXCLUDE and REPLACE list");
    private static final Pattern EMPTY_STAR_PROJECTION = Pattern.compile(
            "SELECT list is empty after resolving \\* expressions");

    /** DuckDB reports a star-list failure in its own words; BigQuery names the same failure differently. */
    static String queryErrorMessage(String message) {
        Matcher miss = COLUMN_LIST_MISS.matcher(message);
        Matcher kind = COLUMN_LIST_KIND.matcher(message);
        if (miss.find() && kind.find()) {
            String clause = kind.group(1).equals("EXCLUDE") ? "EXCEPT" : "REPLACE";
            return "Column " + miss.group("column") + " in SELECT * " + clause + " list does not exist";
        }
        if (EMPTY_STAR_PROJECTION.matcher(message).find()) {
            return "SELECT .* expands to zero columns after applying EXCEPT";
        }
        Matcher both = COLUMN_IN_BOTH_LISTS.matcher(message);
        if (both.find()) {
            return "Column " + both.group("column") + " cannot occur in both SELECT * EXCEPT and REPLACE";
        }
        return message;
    }

    /**
     * {@code CREATE TABLE} for one referenced table. Rows are read over HTTP from floci-gcp so
     * the request stays small; columns {@code insertAll} stores as text are converted in SQL.
     */
    String stageTable(String projectId, SqlDialectTranslator.TableRef ref, Table table, boolean empty,
                      String flociEndpoint) {
        List<TableFieldSchema> fields = stagingFields(table);
        String target = DuckTypes.quoteIdentifier(ref.datasetId()) + "." + DuckTypes.quoteIdentifier(ref.tableId());
        if (fields.isEmpty()) {
            throw SqlDialectTranslator.invalidQuery("Table " + projectId + ":" + ref.datasetId() + "."
                    + ref.tableId() + " has no schema.");
        }
        String url = flociEndpoint + "/_floci-gcp/bigquery/projects/" + encode(projectId)
                + "/datasets/" + encode(ref.datasetId()) + "/tables/" + encode(ref.tableId()) + "/rows.ndjson";
        return stageRows(target, fields, empty, url);
    }

    private static List<TableFieldSchema> stagingFields(Table table) {
        List<TableFieldSchema> fields = new ArrayList<>(table.getSchema() != null && table.getSchema().getFields() != null
                ? table.getSchema().getFields() : List.of());
        if (BigQueryService.isIngestionTimePartitioned(table)) {
            if (BigQueryService.hasPartitionDatePseudocolumn(table)) {
                fields.add(field(BigQueryService.PARTITION_DATE, "DATE"));
            }
            fields.add(field(BigQueryService.PARTITION_TIME, "TIMESTAMP"));
        }
        return fields;
    }

    private static TableFieldSchema field(String name, String type) {
        TableFieldSchema field = new TableFieldSchema();
        field.setName(name);
        field.setType(type);
        return field;
    }

    /** {@code CREATE TABLE target} from NDJSON rows at {@code url}, or empty with the given columns. */
    private static String stageRows(String target, List<TableFieldSchema> fields, boolean empty, String url) {
        if (empty) {
            StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(target).append(" (");
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) {
                    ddl.append(", ");
                }
                ddl.append(DuckTypes.quoteIdentifier(fields.get(i).getName())).append(' ')
                        .append(DuckTypes.duckType(fields.get(i)));
            }
            return ddl.append(");").toString();
        }

        StringBuilder projection = new StringBuilder();
        StringBuilder columns = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            TableFieldSchema field = fields.get(i);
            String column = DuckTypes.quoteIdentifier(field.getName());
            boolean text = DuckTypes.stagedAsText(field);
            if (i > 0) {
                projection.append(", ");
                columns.append(", ");
            }
            String staged = text ? DuckTypes.convertStagedText(field, column)
                    : DuckTypes.convertNestedStagedBytes(field, column);
            projection.append(staged).append(" AS ").append(column);
            columns.append(DuckTypes.quoteLiteral(field.getName())).append(": ")
                    .append(DuckTypes.quoteLiteral(text ? "VARCHAR" : DuckTypes.duckType(field)));
        }
        return "CREATE TABLE " + target + " AS SELECT " + projection + " FROM read_json("
                + DuckTypes.quoteLiteral(url) + ", format = 'newline_delimited', columns = {" + columns + "});";
    }

    private long estimateBytes(List<Map<String, Object>> rows) {
        long total = 0;
        for (Map<String, Object> row : rows) {
            total += estimateValue(row);
        }
        return total;
    }

    private long estimateValue(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof String str) {
            long count = 0;
            for (int i = 0, len = str.length(); i < len; i++) {
                char c = str.charAt(i);
                if (c <= 0x7F) {
                    count++;
                } else if (c <= 0x7FF) {
                    count += 2;
                } else if (Character.isHighSurrogate(c)) {
                    count += 4;
                    i++;
                } else {
                    count += 3;
                }
            }
            return count;
        }
        if (value instanceof Number) {
            return 8;
        }
        if (value instanceof Boolean) {
            return 1;
        }
        if (value instanceof Map<?, ?> map) {
            long size = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                size += estimateValue(entry.getKey()) + estimateValue(entry.getValue());
            }
            return size;
        }
        if (value instanceof List<?> list) {
            long size = 0;
            for (Object item : list) {
                size += estimateValue(item);
            }
            return size;
        }
        return 16;
    }

    /**
     * floci-gcp's base URL as reachable from the sidecar. The resolved docker host is right for a
     * sidecar this process started, but a pre-configured one may sit somewhere that alias does not
     * reach, so an explicit callback URL wins when it is set.
     */
    String flociEndpoint() {
        String configured = config.services().bigquery().duck().callbackUrl().orElse("");
        if (!configured.isBlank()) {
            return configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
        }
        return "http://" + dockerHostResolver.resolve() + ":" + config.port();
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
