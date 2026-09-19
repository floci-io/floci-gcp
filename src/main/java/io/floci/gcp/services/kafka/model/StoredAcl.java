package io.floci.gcp.services.kafka.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class StoredAcl {

    @JsonProperty("name")
    private String name;

    @JsonProperty("resourceType")
    @JsonAlias("resource_type")
    private String resourceType;

    @JsonProperty("resourceName")
    @JsonAlias("resource_name")
    private String resourceName;

    @JsonProperty("patternType")
    @JsonAlias("pattern_type")
    private String patternType;

    @JsonProperty("aclEntries")
    @JsonAlias("acl_entries")
    private List<AclEntry> aclEntries = new ArrayList<>();

    @JsonProperty("etag")
    private String etag;

    public StoredAcl() {
    }

    public StoredAcl(String name, String resourceType, String resourceName, String patternType) {
        this.name = name;
        this.resourceType = resourceType;
        this.resourceName = resourceName;
        this.patternType = patternType;
        this.etag = generateEtag();
    }

    public String generateEtag() {
        return UUID.randomUUID().toString();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public String getResourceName() {
        return resourceName;
    }

    public void setResourceName(String resourceName) {
        this.resourceName = resourceName;
    }

    public String getPatternType() {
        return patternType;
    }

    public void setPatternType(String patternType) {
        this.patternType = patternType;
    }

    public List<AclEntry> getAclEntries() {
        return aclEntries;
    }

    public void setAclEntries(List<AclEntry> aclEntries) {
        this.aclEntries = aclEntries != null ? aclEntries : new ArrayList<>();
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }
}
