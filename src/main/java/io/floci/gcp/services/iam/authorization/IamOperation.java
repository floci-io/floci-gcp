package io.floci.gcp.services.iam.authorization;

/** Transport-independent operation and its primary and optional secondary resource. */
public record IamOperation(String name, String resource, String relatedResource) {
    public IamOperation(String name, String resource) {
        this(name, resource, "");
    }
}
