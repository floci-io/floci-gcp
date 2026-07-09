package io.floci.gcp.services.bigquery.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TableSchema {

    private List<TableFieldSchema> fields;

    public TableSchema() {}

    public TableSchema(List<TableFieldSchema> fields) {
        this.fields = fields;
    }

    public List<TableFieldSchema> getFields() { return fields; }
    public void setFields(List<TableFieldSchema> fields) { this.fields = fields; }
}
