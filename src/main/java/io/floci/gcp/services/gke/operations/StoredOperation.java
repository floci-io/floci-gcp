package io.floci.gcp.services.gke.operations;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public class StoredOperation {

    private String name;
    private OperationType operationType;
    private String status;

    private String location;
    private String targetLink;
    private String selfLink;

    private Instant startTime;
    private Instant endTime;

    public StoredOperation() {
    }

    public StoredOperation(
            String name,
            OperationType operationType,
            String location,
            String targetLink,
            String selfLink) {

        this.name = name;
        this.operationType = operationType;
        this.location = location;
        this.targetLink = targetLink;
        this.selfLink = selfLink;

        this.status = "DONE";

        this.startTime = Instant.now();
        this.endTime = this.startTime;
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

    public String getLocation() {
        return location;
    }

    public String getTargetLink() {
        return targetLink;
    }

    public String getSelfLink() {
        return selfLink;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }
}