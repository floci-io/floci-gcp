package io.floci.gcp.services.iam;

import java.util.List;

/** A normalized IAM allow policy used by the future evaluator. */
public record IamPolicy(int version, List<IamBinding> bindings, String etag) {

    public IamPolicy {
        bindings = List.copyOf(bindings);
    }
}
