package io.floci.gcp.services.iam.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class StoredServiceAccount {

    private String name;
    private String projectId;
    private String uniqueId;
    private String email;
    private String displayName;
    private String description;
    private String createTime;
    private String etag;

    public StoredServiceAccount() {}

    public StoredServiceAccount(String name, String projectId, String uniqueId, String email,
            String displayName, String description, String createTime, String etag) {
        this.name = name;
        this.projectId = projectId;
        this.uniqueId = uniqueId;
        this.email = email;
        this.displayName = displayName;
        this.description = description;
        this.createTime = createTime;
        this.etag = etag;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getUniqueId() { return uniqueId; }
    public void setUniqueId(String uniqueId) { this.uniqueId = uniqueId; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }
}
