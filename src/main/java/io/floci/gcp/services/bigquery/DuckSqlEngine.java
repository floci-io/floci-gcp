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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
        SqlDialectTranslator.Translation translation = SqlDialectTranslator.translate(request.sql(),
                request.projectId(), request.defaultDatasetId(),
                new SqlDialectTranslator.QueryParameters(request.queryParameters(), request.parameterMode()));

        String flociEndpoint = flociEndpoint();
        Staging staging = stage(request.projectId(), translation.tables(), tables, flociEndpoint);
        String sql = translation.sql();
        String setupSql = staging.setup();
        if (request.dryRun()) {
            List<Column> columns = describe(sql, setupSql, flociEndpoint);
            return new Result(schemaOf(columns), List.of(), "SELECT", staging.bytesProcessed());
        }

        DuckClient.DuckResult result = run(sql, setupSql, flociEndpoint, null);
        if (result.columns() == null) {
            List<Column> columns = describe(sql, setupSql, flociEndpoint);
            return new Result(schemaOf(columns), fetch(sql, setupSql, flociEndpoint, columns), "SELECT",
                    staging.bytesProcessed());
        }
        List<Column> columns = columns(result.columns().stream()
                .map(c -> Map.<String, Object>of("column_name", c.name(), "column_type", c.type()))
                .toList());
        return new Result(schemaOf(columns), decodeRows(result.rows(), columns), "SELECT",
                staging.bytesProcessed());
    }

    @Override
    public DmlResult executeDml(Request request, Tables tables) {
        SqlDialectTranslator.Statement statement = SqlDialectTranslator.parseStatement(request.sql(),
                request.projectId(), request.defaultDatasetId());
        SqlDialectTranslator.TableRef target = statement.target();
        Table targetTable = tables.table(target.datasetId(), target.tableId());
        if (targetTable.viewQuery() != null) {
            throw SqlDialectTranslator.invalidQuery("Cannot run " + statement.statementType() + " on view "
                    + request.projectId() + ":" + target.datasetId() + "." + target.tableId());
        }
        SqlDialectTranslator.Translation translation = SqlDialectTranslator.translateDml(request.sql(),
                request.projectId(), request.defaultDatasetId(),
                new SqlDialectTranslator.QueryParameters(request.queryParameters(), request.parameterMode()));

        String flociEndpoint = flociEndpoint();
        Staging staging = stage(request.projectId(), translation.tables(), tables, flociEndpoint);
        String followup = "SELECT * FROM " + DuckTypes.quoteIdentifier(target.datasetId()) + "."
                + DuckTypes.quoteIdentifier(target.tableId());
        DuckClient.DuckResult result = run(translation.sql(), staging.setup(), flociEndpoint, followup);
        if (result.followup() == null || result.followup().columns() == null) {
            throw GcpException.failedPrecondition("DML needs a floci-duck image that supports follow-up"
                    + " statements; update " + config.services().bigquery().duck().defaultImage());
        }

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

        List<TableFieldSchema> targetFields = targetTable.getSchema() != null
                && targetTable.getSchema().getFields() != null ? targetTable.getSchema().getFields() : List.of();
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
        return new DmlResult(affected, inserted, updated, deleted, tableRows, staging.bytesProcessed());
    }

    private static List<Map<String, Object>> decodeRows(List<Map<String, Object>> typedRows, List<Column> columns) {
        List<Map<String, Object>> rows = new ArrayList<>(typedRows.size());
        for (Map<String, Object> typedRow : typedRows) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (Column column : columns) {
                row.put(column.name(), DuckTypes.decodeTyped(typedRow.get(column.name()), column.type(),
                        column.field()));
            }
            rows.add(row);
        }
        return rows;
    }

    private record Staging(String setup, long bytesProcessed) {}

    /**
     * Setup SQL creating every referenced table in DuckDB. Views are expanded: their own
     * references are staged first, then the view is created from its translated query.
     */
    private Staging stage(String projectId, Set<SqlDialectTranslator.TableRef> refs, Tables tables,
                          String flociEndpoint) {
        StringBuilder setup = new StringBuilder("SET TimeZone = 'UTC';\n");
        long[] bytes = {0};
        Set<String> schemas = new HashSet<>();
        Set<SqlDialectTranslator.TableRef> staged = new HashSet<>();
        for (SqlDialectTranslator.TableRef ref : refs) {
            stageOne(projectId, ref, tables, flociEndpoint, setup, schemas, staged, new LinkedHashSet<>(), bytes);
        }
        return new Staging(setup.toString(), bytes[0]);
    }

    private void stageOne(String projectId, SqlDialectTranslator.TableRef ref, Tables tables, String flociEndpoint,
                          StringBuilder setup, Set<String> schemas, Set<SqlDialectTranslator.TableRef> staged,
                          Set<SqlDialectTranslator.TableRef> expanding, long[] bytes) {
        if (staged.contains(ref)) {
            return;
        }
        if (!expanding.add(ref)) {
            throw SqlDialectTranslator.invalidQuery("View " + projectId + ":" + ref.datasetId() + "." + ref.tableId()
                    + " references itself");
        }
        Table table = tables.table(ref.datasetId(), ref.tableId());
        String viewQuery = table.viewQuery();
        String ddl;
        if (viewQuery != null) {
            SqlDialectTranslator.Translation view = SqlDialectTranslator.translate(viewQuery, projectId,
                    ref.datasetId(), SqlDialectTranslator.QueryParameters.none());
            for (SqlDialectTranslator.TableRef dependency : view.tables()) {
                stageOne(projectId, dependency, tables, flociEndpoint, setup, schemas, staged, expanding, bytes);
            }
            ddl = "CREATE VIEW " + DuckTypes.quoteIdentifier(ref.datasetId()) + "."
                    + DuckTypes.quoteIdentifier(ref.tableId()) + " AS " + view.sql() + ";";
        } else {
            List<Map<String, Object>> rows = tables.rows(ref.datasetId(), ref.tableId());
            bytes[0] += estimateBytes(rows);
            ddl = stageTable(projectId, ref, table, rows.isEmpty(), flociEndpoint);
        }
        if (schemas.add(ref.datasetId())) {
            setup.append("CREATE SCHEMA IF NOT EXISTS ").append(DuckTypes.quoteIdentifier(ref.datasetId()))
                    .append(";\n");
        }
        setup.append(ddl).append('\n');
        staged.add(ref);
        expanding.remove(ref);
    }

    private static TableSchema schemaOf(List<Column> columns) {
        return new TableSchema(columns.stream().map(Column::field).toList());
    }

    private List<Column> describe(String sql, String setup, String flociEndpoint) {
        return columns(run("DESCRIBE " + sql, setup, flociEndpoint, null).rows());
    }

    /** Result columns from {@code column_name}/{@code column_type} pairs; rejects duplicate names. */
    private static List<Column> columns(List<Map<String, Object>> described) {
        List<Column> columns = new ArrayList<>(described.size());
        Set<String> seen = new LinkedHashSet<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (Map<String, Object> row : described) {
            String name = String.valueOf(row.get("column_name"));
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                duplicates.add(name);
            }
            DuckTypes.DuckType type = DuckTypes.parse(String.valueOf(row.get("column_type")));
            columns.add(new Column(name, type, DuckTypes.toField(name, type), DuckTypes.projection(type)));
        }
        if (!duplicates.isEmpty()) {
            throw SqlDialectTranslator.invalidQuery("Duplicate column names in the result are not supported."
                    + " Found duplicate(s): " + String.join(", ", duplicates));
        }
        return columns;
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
                row.put(column.name(), DuckTypes.decode(rawRow.get("c" + i), column.projection(), column.type(),
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
        return SqlDialectTranslator.invalidQuery(text);
    }

    /**
     * {@code CREATE TABLE} for one referenced table. Rows are read over HTTP from floci-gcp so
     * the request stays small; columns {@code insertAll} stores as text are converted in SQL.
     */
    String stageTable(String projectId, SqlDialectTranslator.TableRef ref, Table table, boolean empty,
                      String flociEndpoint) {
        List<TableFieldSchema> fields = table.getSchema() != null && table.getSchema().getFields() != null
                ? table.getSchema().getFields() : List.of();
        String target = DuckTypes.quoteIdentifier(ref.datasetId()) + "." + DuckTypes.quoteIdentifier(ref.tableId());
        if (fields.isEmpty()) {
            throw SqlDialectTranslator.invalidQuery("Table " + projectId + ":" + ref.datasetId() + "."
                    + ref.tableId() + " has no schema.");
        }
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
            projection.append(text ? DuckTypes.convertStagedText(field, column) + " AS " + column : column);
            columns.append(DuckTypes.quoteLiteral(field.getName())).append(": ")
                    .append(DuckTypes.quoteLiteral(text ? "VARCHAR" : DuckTypes.duckType(field)));
        }
        String url = flociEndpoint + "/_floci-gcp/bigquery/projects/" + encode(projectId)
                + "/datasets/" + encode(ref.datasetId()) + "/tables/" + encode(ref.tableId()) + "/rows.ndjson";
        return "CREATE TABLE " + target + " AS SELECT " + projection + " FROM read_json("
                + DuckTypes.quoteLiteral(url) + ", format = 'newline_delimited', columns = {" + columns + "});";
    }

    private long estimateBytes(List<Map<String, Object>> rows) {
        long total = 0;
        for (Map<String, Object> row : rows) {
            try {
                total += mapper.writeValueAsBytes(row).length;
            } catch (Exception e) {
                throw GcpException.internal("Could not size table rows: " + e.getMessage());
            }
        }
        return total;
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
