package io.floci.gcp.services.bigquery.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** Identifies a routine, as referenced from {@code Dataset.access[].routine}. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RoutineReference {

    private String projectId;
    private String datasetId;
    private String routineId;

    public RoutineReference() {}

    public RoutineReference(String projectId, String datasetId, String routineId) {
        this.projectId = projectId;
        this.datasetId = datasetId;
        this.routineId = routineId;
    }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String datasetId) { this.datasetId = datasetId; }

    public String getRoutineId() { return routineId; }
    public void setRoutineId(String routineId) { this.routineId = routineId; }
}
