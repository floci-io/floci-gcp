package io.floci.gcp.services.bigquery.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A single cell in a {@link TableRow}. The {@code v} value is a scalar (rendered as a
 * string on the wire), a nested {@code {f:[...]}} for RECORD types, or an array for
 * REPEATED fields.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TableCell {

    private Object v;

    public TableCell() {}

    public TableCell(Object v) {
        this.v = v;
    }

    public Object getV() { return v; }
    public void setV(Object v) { this.v = v; }
}
