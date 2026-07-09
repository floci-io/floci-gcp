package io.floci.gcp.services.bigquery.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JobReference {

    private String projectId;
    private String jobId;
    private String location;

    public JobReference() {}

    public JobReference(String projectId, String jobId, String location) {
        this.projectId = projectId;
        this.jobId = jobId;
        this.location = location;
    }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
}
