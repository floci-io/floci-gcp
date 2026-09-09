package io.floci.gcp.services.iam;

import java.util.List;

/** A normalized IAM allow-policy binding. */
public record IamBinding(String role, List<String> members, IamCondition condition) {

    public IamBinding {
        members = List.copyOf(members);
    }
}
