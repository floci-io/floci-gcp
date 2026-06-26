package io.floci.gcp.services.gke.operations;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Models {@code google.container.v1.Operation}. Timestamps are RFC3339 strings
 * (proto field type {@code string}), not epoch numbers, so the HttpJson SDK /
 * gcloud / Terraform parse them correctly.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class StoredOperation {

    private String name;
    private OperationType operationType;
    private String status;
    private String zone;
    private String location;
    private String targetLink;
    private String selfLink;
    private String startTime;
    private String endTime;

    public StoredOperation() {
    }

    public StoredOperation(
            String name,
            OperationType operationType,
            String status,
            String location,
            String targetLink,
            String selfLink,
            String startTime,
            String endTime) {

        this.name = name;
        this.operationType = operationType;
        this.status = status;
        this.zone = location;
        this.location = location;
        this.targetLink = targetLink;
        this.selfLink = selfLink;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public String getName() {
        return name;
    }

    public OperationType getOperationType() {
        return operationType;
    }

    public String getStatus() {
        return status;
    }

    public String getZone() {
        return zone;
    }

    public String getLocation() {
        return location;
    }

    public String getTargetLink() {
        return targetLink;
    }

    public String getSelfLink() {
        return selfLink;
    }

    public String getStartTime() {
        return startTime;
    }

    public String getEndTime() {
        return endTime;
    }
}
