package io.floci.gcp.services.bigquery.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Persisted state of an application-created Storage Write stream, keyed by its full resource name
 * ({@code projects/{p}/datasets/{d}/tables/{t}/streams/{s}}). Timestamps are ISO-8601 instants.
 * {@code uncommitted} holds the rows of a PENDING stream not yet committed, or of a BUFFERED stream
 * not yet flushed, already normalized against the table schema.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StoredWriteStream {

    private String name;
    private String projectId;
    private String datasetId;
    private String tableId;
    private String type;
    private String created;
    private String finalized;
    private String committed;
    private long rowCount;
    private long flushed;
    private List<Map<String, Object>> uncommitted = new ArrayList<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String datasetId) { this.datasetId = datasetId; }

    public String getTableId() { return tableId; }
    public void setTableId(String tableId) { this.tableId = tableId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getCreated() { return created; }
    public void setCreated(String created) { this.created = created; }

    public String getFinalized() { return finalized; }
    public void setFinalized(String finalized) { this.finalized = finalized; }

    public String getCommitted() { return committed; }
    public void setCommitted(String committed) { this.committed = committed; }

    public long getRowCount() { return rowCount; }
    public void setRowCount(long rowCount) { this.rowCount = rowCount; }

    public long getFlushed() { return flushed; }
    public void setFlushed(long flushed) { this.flushed = flushed; }

    public List<Map<String, Object>> getUncommitted() { return uncommitted; }
    public void setUncommitted(List<Map<String, Object>> uncommitted) { this.uncommitted = uncommitted; }
}
