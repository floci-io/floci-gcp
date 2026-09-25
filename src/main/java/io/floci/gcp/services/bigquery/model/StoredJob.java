package io.floci.gcp.services.bigquery.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

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
    private String statementType;
    private String totalBytesProcessed;
    /** Dry runs are never persisted; they carry the result schema instead of rows. */
    private boolean dryRun;
    private TableSchema schema;
    private String numDmlAffectedRows;
    /** {@code DmlStatistics}: insertedRowCount, updatedRowCount, deletedRowCount. */
    private Map<String, String> dmlStats;
    private String ddlOperationPerformed;
    private TableReference ddlTargetTable;
    private DatasetReference ddlTargetDataset;
    /** QUERY (default) or LOAD. */
    private String jobType;
    /** The job's {@code configuration.load}, echoed back on reads. */
    private Map<String, Object> loadConfiguration;
    /** {@code JobStatistics3}: inputFiles, inputFileBytes, outputRows, outputBytes, badRecords. */
    private Map<String, String> loadStatistics;

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

    public String getStatementType() { return statementType; }
    public void setStatementType(String statementType) { this.statementType = statementType; }

    public String getTotalBytesProcessed() { return totalBytesProcessed; }
    public void setTotalBytesProcessed(String totalBytesProcessed) { this.totalBytesProcessed = totalBytesProcessed; }

    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }

    public TableSchema getSchema() { return schema; }
    public void setSchema(TableSchema schema) { this.schema = schema; }

    public String getNumDmlAffectedRows() { return numDmlAffectedRows; }
    public void setNumDmlAffectedRows(String numDmlAffectedRows) { this.numDmlAffectedRows = numDmlAffectedRows; }

    public Map<String, String> getDmlStats() { return dmlStats; }
    public void setDmlStats(Map<String, String> dmlStats) { this.dmlStats = dmlStats; }

    public String getDdlOperationPerformed() { return ddlOperationPerformed; }
    public void setDdlOperationPerformed(String ddlOperationPerformed) { this.ddlOperationPerformed = ddlOperationPerformed; }

    public TableReference getDdlTargetTable() { return ddlTargetTable; }
    public void setDdlTargetTable(TableReference ddlTargetTable) { this.ddlTargetTable = ddlTargetTable; }

    public DatasetReference getDdlTargetDataset() { return ddlTargetDataset; }
    public void setDdlTargetDataset(DatasetReference ddlTargetDataset) { this.ddlTargetDataset = ddlTargetDataset; }

    public String getJobType() { return jobType != null ? jobType : "QUERY"; }
    public void setJobType(String jobType) { this.jobType = jobType; }

    public Map<String, Object> getLoadConfiguration() { return loadConfiguration; }
    public void setLoadConfiguration(Map<String, Object> loadConfiguration) { this.loadConfiguration = loadConfiguration; }

    public Map<String, String> getLoadStatistics() { return loadStatistics; }
    public void setLoadStatistics(Map<String, String> loadStatistics) { this.loadStatistics = loadStatistics; }

    public boolean failed() {
        return errorReason != null;
    }
}
