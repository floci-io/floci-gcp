package io.floci.gcp.services.bigquery.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A CEL expression, as carried by {@code Dataset.access[].condition}.
 *
 * <p>All four fields are optional and free text. The emulator stores them and
 * returns them unchanged; it does not parse the expression or evaluate the
 * condition, so an entry whose condition is false still grants nothing and
 * denies nothing here. That matches the rest of {@code access[]}, which is
 * storage fidelity rather than authorization.
 *
 * @see <a href="https://cloud.google.com/bigquery/docs/reference/rest/v2/datasets#expr">Expr</a>
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Expr {

    private String expression;
    private String title;
    private String description;
    private String location;

    public Expr() {}

    public Expr(String expression, String title, String description, String location) {
        this.expression = expression;
        this.title = title;
        this.description = description;
        this.location = location;
    }

    public String getExpression() { return expression; }
    public void setExpression(String expression) { this.expression = expression; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
}
