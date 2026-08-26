package io.floci.gcp.services.iam;

/** Canonical principal identity used for IAM allow-binding membership checks. */
public record IamPrincipal(String member) {

    private static final IamPrincipal ANONYMOUS = new IamPrincipal(null);

    public static IamPrincipal anonymous() {
        return ANONYMOUS;
    }

    public static IamPrincipal serviceAccount(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("service account email must not be blank");
        }
        return new IamPrincipal("serviceAccount:" + email);
    }

    public boolean isAuthenticated() {
        return member != null;
    }

    public IamPrincipal {
        if (member != null && member.isBlank()) {
            throw new IllegalArgumentException("principal member must not be blank");
        }
    }
}
