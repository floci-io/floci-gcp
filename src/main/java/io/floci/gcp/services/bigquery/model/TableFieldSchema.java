package io.floci.gcp.services.bigquery.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TableFieldSchema {

    private String name;
    private String type;
    private String mode;
    private String description;
    private List<TableFieldSchema> fields;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<TableFieldSchema> getFields() { return fields; }
    public void setFields(List<TableFieldSchema> fields) { this.fields = fields; }
}
