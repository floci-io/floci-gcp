package io.floci.gcp.services.bigquery.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/** A row in BigQuery's tabledata/query wire format: {@code {f:[{v:value}]}}. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TableRow {

    private List<TableCell> f;

    public TableRow() {}

    public TableRow(List<TableCell> f) {
        this.f = f;
    }

    public List<TableCell> getF() { return f; }
    public void setF(List<TableCell> f) { this.f = f; }
}
