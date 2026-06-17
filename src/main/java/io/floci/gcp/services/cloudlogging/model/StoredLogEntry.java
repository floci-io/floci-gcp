package io.floci.gcp.services.cloudlogging.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
public class StoredLogEntry {

    private String logName;
    private String insertId;
    private String severity;
    private String textPayload;

    /** Parsed JSON object (Map/List/scalar) for jsonPayload; null when not a JSON entry. */
    private Object jsonPayload;

    private String resourceType;
    private Map<String, String> resourceLabels;
    private Map<String, String> labels;
    private String timestamp;
    private String receiveTimestamp;
    private String trace;
    private String spanId;

    /** Monotonic ingestion order, used for stable sorting and as the storage key suffix. */
    private long sequence;

    public StoredLogEntry() {
        this.severity = "DEFAULT";
    }

    public String getLogName() { return logName; }
    public void setLogName(String logName) { this.logName = logName; }

    public String getInsertId() { return insertId; }
    public void setInsertId(String insertId) { this.insertId = insertId; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getTextPayload() { return textPayload; }
    public void setTextPayload(String textPayload) { this.textPayload = textPayload; }

    public Object getJsonPayload() { return jsonPayload; }
    public void setJsonPayload(Object jsonPayload) { this.jsonPayload = jsonPayload; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public Map<String, String> getResourceLabels() { return resourceLabels; }
    public void setResourceLabels(Map<String, String> resourceLabels) { this.resourceLabels = resourceLabels; }

    public Map<String, String> getLabels() { return labels; }
    public void setLabels(Map<String, String> labels) { this.labels = labels; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public String getReceiveTimestamp() { return receiveTimestamp; }
    public void setReceiveTimestamp(String receiveTimestamp) { this.receiveTimestamp = receiveTimestamp; }

    public String getTrace() { return trace; }
    public void setTrace(String trace) { this.trace = trace; }

    public String getSpanId() { return spanId; }
    public void setSpanId(String spanId) { this.spanId = spanId; }

    public long getSequence() { return sequence; }
    public void setSequence(long sequence) { this.sequence = sequence; }
}
