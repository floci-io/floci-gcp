package io.floci.gcp.services.iam.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * IAM v1 custom {@code Role}. A null stage is {@code ALPHA}, the enum's zero value, which the API omits
 * from responses; a false {@code deleted} is omitted likewise.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class StoredRole {

    public static final String ALPHA = "ALPHA";
    public static final String DISABLED = "DISABLED";

    private String name;
    private String title;
    private String description;
    private List<String> includedPermissions = new ArrayList<>();
    private String stage;
    private String etag;
    private Boolean deleted;

    public StoredRole() {}

    public StoredRole copy() {
        StoredRole copy = new StoredRole();
        copy.name = name;
        copy.title = title;
        copy.description = description;
        copy.includedPermissions = new ArrayList<>(includedPermissions);
        copy.stage = stage;
        copy.etag = etag;
        copy.deleted = deleted;
        return copy;
    }

    /** Same role without {@code includedPermissions}, for the {@code BASIC} list view. */
    public StoredRole basicView() {
        StoredRole copy = copy();
        copy.includedPermissions = new ArrayList<>();
        return copy;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getIncludedPermissions() { return includedPermissions; }
    public void setIncludedPermissions(List<String> includedPermissions) {
        this.includedPermissions = includedPermissions != null ? new ArrayList<>(includedPermissions) : new ArrayList<>();
    }

    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = ALPHA.equals(stage) ? null : stage; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }

    public Boolean getDeleted() { return deleted; }
    public void setDeleted(Boolean deleted) { this.deleted = Boolean.TRUE.equals(deleted) ? Boolean.TRUE : null; }

    public boolean softDeleted() {
        return Boolean.TRUE.equals(deleted);
    }

    /** Deleted and disabled roles stay in policies but contribute no permissions. */
    public boolean grantsPermissions() {
        return !softDeleted() && !DISABLED.equals(stage);
    }
}
