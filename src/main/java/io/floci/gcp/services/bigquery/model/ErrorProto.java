package io.floci.gcp.services.bigquery.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorProto {

    private String reason;
    private String location;
    private String debugInfo;
    private String message;

    public ErrorProto() {}

    public ErrorProto(String reason, String location, String message) {
        this.reason = reason;
        this.location = location;
        this.message = message;
    }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getDebugInfo() { return debugInfo; }
    public void setDebugInfo(String debugInfo) { this.debugInfo = debugInfo; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
