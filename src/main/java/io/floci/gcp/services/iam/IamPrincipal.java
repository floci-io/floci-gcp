package io.floci.gcp.services.iam;

import java.util.Set;

/** Canonical principal identity used for IAM allow-binding membership checks. */
public record IamPrincipal(String member, Set<String> principalSets) {

    private static final IamPrincipal ANONYMOUS = new IamPrincipal(null, Set.of());

    public IamPrincipal(String member) {
        this(member, Set.of());
    }

    public static IamPrincipal anonymous() {
        return ANONYMOUS;
    }

    public static IamPrincipal serviceAccount(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("service account email must not be blank");
        }
        return new IamPrincipal("serviceAccount:" + email, Set.of());
    }

    public static IamPrincipal federated(String principal, Set<String> principalSets) {
        if (principal == null || principal.isBlank()) {
            throw new IllegalArgumentException("federated principal must not be blank");
        }
        return new IamPrincipal(principal, principalSets == null ? Set.of() : Set.copyOf(principalSets));
    }

    public boolean isAuthenticated() {
        return member != null;
    }

    public IamPrincipal {
        if (member != null && member.isBlank()) {
            throw new IllegalArgumentException("principal member must not be blank");
        }
        principalSets = principalSets == null ? Set.of() : Set.copyOf(principalSets);
    }
}
