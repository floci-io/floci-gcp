package io.floci.gcp.services.bigquery.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /**
     * Rows written through a partition decorator, keyed by its canonical suffix. {@link #rows}
     * remains the undecorated row collection so persisted data from older versions stays readable.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, List<Map<String, Object>>> partitionRows = new LinkedHashMap<>();

    /**
     * Storage Write streams: how many of each stream's rows are in {@code rows}, keyed by the full
     * stream name. Written in the same put as the rows, so after a crash a reloaded stream can tell
     * what already landed and a retry does not apply it twice.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Long> streamRows = new LinkedHashMap<>();

    public List<Map<String, Object>> getRows() { return rows; }
    public void setRows(List<Map<String, Object>> rows) { this.rows = rows; }

    public Map<String, List<Map<String, Object>>> getPartitionRows() { return partitionRows; }
    public void setPartitionRows(Map<String, List<Map<String, Object>>> partitionRows) {
        this.partitionRows = partitionRows;
    }

    public Map<String, Long> getStreamRows() { return streamRows; }
    public void setStreamRows(Map<String, Long> streamRows) { this.streamRows = streamRows; }
}
