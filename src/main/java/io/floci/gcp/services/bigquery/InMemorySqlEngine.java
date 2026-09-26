package io.floci.gcp.services.bigquery;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.bigquery.model.Table;

import java.util.List;

/**
 * Docker-free engine for the SQL subset {@link QueryEngine} understands
 * ({@code SELECT *|columns|COUNT(*) FROM t [WHERE col = literal AND ...] [LIMIT n]}).
 */
final class InMemorySqlEngine implements BigQuerySqlEngine {

    @Override
    public Result execute(Request request, Tables tables) {
        if (request.queryParameters() != null && !request.queryParameters().isEmpty()) {
            throw QueryEngine.invalidQuery("Query parameters need the DuckDB SQL engine; "
                    + "set floci-gcp.services.bigquery.mock to false.");
        }
        QueryEngine.ParsedQuery parsed = QueryEngine.parse(request.sql());
        QueryEngine.TableRef ref = parsed.table();
        if (ref.projectId() != null && !ref.projectId().equals(request.projectId())) {
            throw QueryEngine.invalidQuery("Cross-project queries are not supported by the floci BigQuery emulator: "
                    + ref.projectId() + "." + ref.datasetId() + "." + ref.tableId());
        }
        String datasetId = ref.datasetId() != null ? ref.datasetId() : request.defaultDatasetId();
        if (datasetId == null || datasetId.isBlank()) {
            throw QueryEngine.invalidQuery("Table name \"" + ref.tableId()
                    + "\" missing dataset while no default dataset is set in the request.");
        }
        Table table = tables.table(datasetId, ref.tableId());
        QueryEngine.Result result = QueryEngine.evaluate(parsed, table.getSchema(),
                tables.rows(datasetId, ref.tableId()));
        return new Result(result.schema(), request.dryRun() ? List.of() : result.rows(), "SELECT", 0);
    }

    @Override
    public DuckClient.ArrowIpc executeArrow(Request request, Tables tables) {
        throw GcpException.failedPrecondition("ARROW read sessions need the DuckDB SQL"
                + " engine; set floci-gcp.services.bigquery.mock to false or read with AVRO.");
    }

    @Override
    public Result readFiles(LoadSource source) {
        throw QueryEngine.invalidQuery(source.format() + " load jobs need the DuckDB SQL engine; "
                + "set floci-gcp.services.bigquery.mock to false.").withReason("invalid");
    }

    @Override
    public DmlResult executeDml(Request request, Tables tables) {
        throw QueryEngine.invalidQuery("DML statements need the DuckDB SQL engine; "
                + "set floci-gcp.services.bigquery.mock to false.");
    }
}
