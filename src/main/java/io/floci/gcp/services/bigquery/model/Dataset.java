package io.floci.gcp.services.bigquery.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Dataset {

    private String kind = "bigquery#dataset";
    private String id;
    private String etag;
    private String selfLink;
    private DatasetReference datasetReference;
    private String friendlyName;
    private String description;
    private String location;
    private Map<String, String> labels;
    private String creationTime;
    private String lastModifiedTime;

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }

    public String getSelfLink() { return selfLink; }
    public void setSelfLink(String selfLink) { this.selfLink = selfLink; }

    public DatasetReference getDatasetReference() { return datasetReference; }
    public void setDatasetReference(DatasetReference datasetReference) { this.datasetReference = datasetReference; }

    public String getFriendlyName() { return friendlyName; }
    public void setFriendlyName(String friendlyName) { this.friendlyName = friendlyName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public Map<String, String> getLabels() { return labels; }
    public void setLabels(Map<String, String> labels) { this.labels = labels; }

    public String getCreationTime() { return creationTime; }
    public void setCreationTime(String creationTime) { this.creationTime = creationTime; }

    public String getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(String lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }
}
