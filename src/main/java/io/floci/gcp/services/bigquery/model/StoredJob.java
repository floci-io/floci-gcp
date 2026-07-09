package io.floci.gcp.services.bigquery.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Internal persisted representation of a query job, keyed by {@code jobId}. Result rows
 * are materialized into a hidden anonymous table ({@code destinationDatasetId} /
 * {@code destinationTableId}) so {@code getQueryResults} and the SDK's
 * {@code Job.getQueryResults()} (which reads {@code tabledata.list} on the job's
 * destination table) share one row store.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StoredJob {

    private String jobId;
    private String projectId;
    private String location;
    private String query;
    /** PENDING, RUNNING, or DONE. */
    private String state;
    private String destinationDatasetId;
    private String destinationTableId;
    private long totalRows;
    private String creationTime;
    private String errorReason;
    private String errorMessage;

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getDestinationDatasetId() { return destinationDatasetId; }
    public void setDestinationDatasetId(String destinationDatasetId) { this.destinationDatasetId = destinationDatasetId; }

    public String getDestinationTableId() { return destinationTableId; }
    public void setDestinationTableId(String destinationTableId) { this.destinationTableId = destinationTableId; }

    public long getTotalRows() { return totalRows; }
    public void setTotalRows(long totalRows) { this.totalRows = totalRows; }

    public String getCreationTime() { return creationTime; }
    public void setCreationTime(String creationTime) { this.creationTime = creationTime; }

    public String getErrorReason() { return errorReason; }
    public void setErrorReason(String errorReason) { this.errorReason = errorReason; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public boolean failed() {
        return errorReason != null;
    }
}
