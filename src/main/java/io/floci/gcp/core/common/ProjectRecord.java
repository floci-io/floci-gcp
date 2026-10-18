package io.floci.gcp.core.common;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProjectRecord {

    public static final String ACTIVE = "ACTIVE";
    public static final String DELETE_REQUESTED = "DELETE_REQUESTED";

    private String projectId;
    private String projectNumber;
    private String name;
    private Map<String, String> labels = new LinkedHashMap<>();
    private String parentType;
    private String parentId;
    private String lifecycleState = ACTIVE;
    private String createTime;
    private String billingAccountName = "";
    private boolean created;

    public ProjectRecord() {}

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getProjectNumber() { return projectNumber; }
    public void setProjectNumber(String projectNumber) { this.projectNumber = projectNumber; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Map<String, String> getLabels() { return labels; }
    public void setLabels(Map<String, String> labels) {
        this.labels = labels != null ? new LinkedHashMap<>(labels) : new LinkedHashMap<>();
    }

    public String getParentType() { return parentType; }
    public void setParentType(String parentType) { this.parentType = parentType; }

    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }

    public String getLifecycleState() { return lifecycleState; }
    public void setLifecycleState(String lifecycleState) { this.lifecycleState = lifecycleState; }

    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }

    public String getBillingAccountName() { return billingAccountName; }
    public void setBillingAccountName(String billingAccountName) {
        this.billingAccountName = billingAccountName != null ? billingAccountName : "";
    }

    /** True for projects made through {@code projects.create}; false for ones registered on first use. */
    public boolean isCreated() { return created; }
    public void setCreated(boolean created) { this.created = created; }

    @JsonIgnore
    public boolean isActive() {
        return ACTIVE.equals(lifecycleState);
    }

    @JsonIgnore
    public boolean hasParent() {
        return parentType != null && !parentType.isEmpty() && parentId != null && !parentId.isEmpty();
    }
}
