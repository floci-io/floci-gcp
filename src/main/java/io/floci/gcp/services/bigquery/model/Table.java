package io.floci.gcp.services.bigquery.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Table {

    private String kind = "bigquery#table";
    private String id;
    private String etag;
    private String selfLink;
    private TableReference tableReference;
    private String friendlyName;
    private String description;
    private String type = "TABLE";
    private TableSchema schema;
    private Map<String, String> labels;
    private String numRows;
    private String numBytes;
    private String creationTime;
    private String lastModifiedTime;
    private String location;
    private String numLongTermBytes;
    /**
     * Writable {@code Table} fields the emulator stores verbatim but does not model (partitioning,
     * clustering, expiration, collation, ...). Keys are filtered by {@code BigQueryMetadata}.
     */
    @JsonIgnore
    private final Map<String, Object> extra = new LinkedHashMap<>();

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }

    public String getSelfLink() { return selfLink; }
    public void setSelfLink(String selfLink) { this.selfLink = selfLink; }

    public TableReference getTableReference() { return tableReference; }
    public void setTableReference(TableReference tableReference) { this.tableReference = tableReference; }

    public String getFriendlyName() { return friendlyName; }
    public void setFriendlyName(String friendlyName) { this.friendlyName = friendlyName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public TableSchema getSchema() { return schema; }
    public void setSchema(TableSchema schema) { this.schema = schema; }

    public Map<String, String> getLabels() { return labels; }
    public void setLabels(Map<String, String> labels) { this.labels = labels; }

    public String getNumRows() { return numRows; }
    public void setNumRows(String numRows) { this.numRows = numRows; }

    public String getNumBytes() { return numBytes; }
    public void setNumBytes(String numBytes) { this.numBytes = numBytes; }

    public String getCreationTime() { return creationTime; }
    public void setCreationTime(String creationTime) { this.creationTime = creationTime; }

    public String getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(String lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getNumLongTermBytes() { return numLongTermBytes; }
    public void setNumLongTermBytes(String numLongTermBytes) { this.numLongTermBytes = numLongTermBytes; }

    @JsonAnyGetter
    public Map<String, Object> getExtra() { return extra; }

    @JsonAnySetter
    public void setExtra(String key, Object value) { extra.put(key, value); }

    /**
     * View definitions live in {@code extra} with every other writable Table property, so a PATCH
     * or PUT of {@code view} round-trips through the same path as the rest. These read that one
     * source rather than adding a second copy as typed fields.
     */
    @JsonIgnore
    public Map<String, Object> viewDefinition() {
        Object definition = extra.get("view") != null ? extra.get("view") : extra.get("materializedView");
        return definition instanceof Map<?, ?> map ? asStringMap(map) : null;
    }

    /** The GoogleSQL of a logical or materialized view, or null for other table types. */
    @JsonIgnore
    public String viewQuery() {
        Map<String, Object> definition = viewDefinition();
        return definition != null && definition.get("query") instanceof String q ? q : null;
    }

    @JsonIgnore
    public boolean isMaterializedView() {
        return extra.get("view") == null && extra.get("materializedView") != null;
    }

    public void setViewDefinition(Map<String, Object> definition) {
        extra.put("view", definition);
    }

    public void setMaterializedViewDefinition(Map<String, Object> definition) {
        extra.put("materializedView", definition);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
