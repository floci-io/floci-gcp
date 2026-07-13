package io.floci.gcp.core.common;

import jakarta.enterprise.context.RequestScoped;

/**
 * Holds per-request values extracted from the incoming request.
 * Populated by {@link ProjectContextFilter} before any handler runs.
 */
@RequestScoped
public class RequestContext {

    private String projectId;
    private String principalEmail;
    private String accessToken;
    private boolean operatorRoot;

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getPrincipalEmail() {
        return principalEmail;
    }

    public void setPrincipalEmail(String principalEmail) {
        this.principalEmail = principalEmail;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public boolean isOperatorRoot() {
        return operatorRoot;
    }

    public void setOperatorRoot(boolean operatorRoot) {
        this.operatorRoot = operatorRoot;
    }
}
