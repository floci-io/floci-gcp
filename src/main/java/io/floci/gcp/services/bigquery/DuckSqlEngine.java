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
        StringBuilder setup = new StringBuilder("SET TimeZone = 'UTC';\n");
        Set<String> schemas = new HashSet<>();
        long bytesProcessed = 0;
        for (SqlDialectTranslator.TableRef ref : translation.tables()) {
            Table table = tables.table(ref.datasetId(), ref.tableId());
            List<Map<String, Object>> rows = tables.rows(ref.datasetId(), ref.tableId());
            bytesProcessed += estimateBytes(rows);
            if (schemas.add(ref.datasetId())) {
                setup.append("CREATE SCHEMA IF NOT EXISTS ").append(DuckTypes.quoteIdentifier(ref.datasetId()))
                        .append(";\n");
            }
            setup.append(stageTable(request.projectId(), ref, table, rows.isEmpty(), flociEndpoint)).append('\n');
        }

        String sql = translation.sql();
        String setupSql = setup.toString();
        if (request.dryRun()) {
            List<Column> columns = describe(sql, setupSql, flociEndpoint);
            return new Result(schemaOf(columns), List.of(), "SELECT", bytesProcessed);
        }

        DuckClient.DuckResult result = run(sql, setupSql, flociEndpoint);
        if (result.columns() == null) {
            List<Column> columns = describe(sql, setupSql, flociEndpoint);
            return new Result(schemaOf(columns), fetch(sql, setupSql, flociEndpoint, columns), "SELECT",
                    bytesProcessed);
        }
        List<Column> columns = columns(result.columns().stream()
                .map(c -> Map.<String, Object>of("column_name", c.name(), "column_type", c.type()))
                .toList());
        List<Map<String, Object>> rows = new ArrayList<>(result.rows().size());
        for (Map<String, Object> typedRow : result.rows()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (Column column : columns) {
                row.put(column.name(), DuckTypes.decodeTyped(typedRow.get(column.name()), column.type(),
                        column.field()));
            }
            rows.add(row);
        }
        return new Result(schemaOf(columns), rows, "SELECT", bytesProcessed);
    }

    private static TableSchema schemaOf(List<Column> columns) {
        return new TableSchema(columns.stream().map(Column::field).toList());
    }

    private List<Column> describe(String sql, String setup, String flociEndpoint) {
        return columns(run("DESCRIBE " + sql, setup, flociEndpoint).rows());
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

        List<Map<String, Object>> raw = run(select.toString(), setup, flociEndpoint).rows();
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

    private DuckClient.DuckResult run(String sql, String setup, String flociEndpoint) {
        try {
            return client.query(sql, setup, flociEndpoint);
        } catch (DuckClient.DuckSqlException e) {
            throw SqlDialectTranslator.invalidQuery(e.getMessage());
        }
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

    /** floci-gcp's base URL as reachable from inside the floci-duck container. */
    private String flociEndpoint() {
        return "http://" + dockerHostResolver.resolve() + ":" + config.port();
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
