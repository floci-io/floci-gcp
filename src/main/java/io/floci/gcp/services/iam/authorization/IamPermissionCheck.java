package io.floci.gcp.services.iam.authorization;

import io.floci.gcp.services.iam.IamResource;

import java.util.Objects;

/** One required permission. Every check in an operation must succeed. */
public record IamPermissionCheck(String permission, IamResource resource) {
    public IamPermissionCheck {
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(resource, "resource");
    }
}
