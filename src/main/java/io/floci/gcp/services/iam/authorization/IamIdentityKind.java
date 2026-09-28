package io.floci.gcp.services.iam.authorization;

/** Credential classifications that an enforcing service can handle independently. */
public enum IamIdentityKind {
    ANONYMOUS,
    EXTERNAL,
    FLOCI_SERVICE_ACCOUNT,
    FLOCI_DOWNSCOPED
}
