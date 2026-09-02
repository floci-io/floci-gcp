package io.floci.gcp.services.gcs.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** Metadata half of a Cloud Storage HMAC key. The secret is returned only once, on create. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GcsHmacKey {

    private String kind = "storage#hmacKeyMetadata";
    private String id;
    private String accessId;
    private String projectId;
    private String serviceAccountEmail;
    private String state;
    private String timeCreated;
    private String updated;
    private String etag;
    private String selfLink;

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getAccessId() { return accessId; }
    public void setAccessId(String accessId) { this.accessId = accessId; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getServiceAccountEmail() { return serviceAccountEmail; }
    public void setServiceAccountEmail(String serviceAccountEmail) { this.serviceAccountEmail = serviceAccountEmail; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getTimeCreated() { return timeCreated; }
    public void setTimeCreated(String timeCreated) { this.timeCreated = timeCreated; }

    public String getUpdated() { return updated; }
    public void setUpdated(String updated) { this.updated = updated; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }

    public String getSelfLink() { return selfLink; }
    public void setSelfLink(String selfLink) { this.selfLink = selfLink; }
}
