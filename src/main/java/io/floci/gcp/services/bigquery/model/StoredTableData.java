package io.floci.gcp.services.bigquery.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Internal store of rows ingested via {@code tabledata.insertAll}, keyed by
 * {@code datasetId/tableId}. Each row is the raw {@code json} object (column → value)
 * from the insert request; conversion to the wire {@code {f:[{v}]}} shape happens at
 * read time against the table schema.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StoredTableData {

    private List<Map<String, Object>> rows = new ArrayList<>();

    public List<Map<String, Object>> getRows() { return rows; }
    public void setRows(List<Map<String, Object>> rows) { this.rows = rows; }
}
