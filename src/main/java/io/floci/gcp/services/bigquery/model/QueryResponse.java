package io.floci.gcp.services.bigquery.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/** Response shape for {@code jobs.query} (POST /queries) and {@code getQueryResults}. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QueryResponse {

    private String kind = "bigquery#queryResponse";
    private JobReference jobReference;
    private Boolean jobComplete;
    private TableSchema schema;
    private List<TableRow> rows;
    private String totalRows;
    private String pageToken;
    private Boolean cacheHit;
    private String totalBytesProcessed;
    private List<ErrorProto> errors;

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public JobReference getJobReference() { return jobReference; }
    public void setJobReference(JobReference jobReference) { this.jobReference = jobReference; }

    public Boolean getJobComplete() { return jobComplete; }
    public void setJobComplete(Boolean jobComplete) { this.jobComplete = jobComplete; }

    public TableSchema getSchema() { return schema; }
    public void setSchema(TableSchema schema) { this.schema = schema; }

    public List<TableRow> getRows() { return rows; }
    public void setRows(List<TableRow> rows) { this.rows = rows; }

    public String getTotalRows() { return totalRows; }
    public void setTotalRows(String totalRows) { this.totalRows = totalRows; }

    public String getPageToken() { return pageToken; }
    public void setPageToken(String pageToken) { this.pageToken = pageToken; }

    public Boolean getCacheHit() { return cacheHit; }
    public void setCacheHit(Boolean cacheHit) { this.cacheHit = cacheHit; }

    public String getTotalBytesProcessed() { return totalBytesProcessed; }
    public void setTotalBytesProcessed(String totalBytesProcessed) { this.totalBytesProcessed = totalBytesProcessed; }

    public List<ErrorProto> getErrors() { return errors; }
    public void setErrors(List<ErrorProto> errors) { this.errors = errors; }
}
