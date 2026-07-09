package io.floci.gcp.services.bigquery.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JobStatus {

    /** One of PENDING, RUNNING, DONE. */
    private String state;
    private ErrorProto errorResult;
    private List<ErrorProto> errors;

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public ErrorProto getErrorResult() { return errorResult; }
    public void setErrorResult(ErrorProto errorResult) { this.errorResult = errorResult; }

    public List<ErrorProto> getErrors() { return errors; }
    public void setErrors(List<ErrorProto> errors) { this.errors = errors; }
}
