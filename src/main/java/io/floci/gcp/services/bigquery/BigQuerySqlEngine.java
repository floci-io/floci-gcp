package io.floci.gcp.services.bigquery;

import io.floci.gcp.services.bigquery.model.Table;
import io.floci.gcp.services.bigquery.model.TableSchema;

import java.util.List;
import java.util.Map;

/**
 * Executes the SQL of a query job. {@link DuckSqlEngine} runs GoogleSQL on the floci-duck
 * sidecar; {@link InMemorySqlEngine} is the Docker-free fallback used when
 * {@code floci-gcp.services.bigquery.mock} is true.
 */
interface BigQuerySqlEngine {

    record Request(String projectId, String sql, String defaultDatasetId,
                   List<Map<String, Object>> queryParameters, String parameterMode, boolean dryRun) {}

    /** Result rows are in the stored representation {@link RowCodec} encodes onto the wire. */
    record Result(TableSchema schema, List<Map<String, Object>> rows, String statementType,
                  long totalBytesProcessed) {}

    /** Read access to the request project's tables; lookups of missing tables throw 404. */
    interface Tables {
        Table table(String datasetId, String tableId);

        List<Map<String, Object>> rows(String datasetId, String tableId);
    }

    /**
     * Outcome of {@code INSERT}/{@code UPDATE}/{@code DELETE}/{@code MERGE}: the counts, plus the
     * target table's complete contents after the statement, in stored representation.
     */
    record DmlResult(long affectedRows, long insertedRows, long updatedRows, long deletedRows,
                     List<Map<String, Object>> tableRows, long totalBytesProcessed) {}

    Result execute(Request request, Tables tables);

    DmlResult executeDml(Request request, Tables tables);
}
