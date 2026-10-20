# IAM Service Account Credentials

floci-gcp emulates the [IAM Service Account Credentials](https://cloud.google.com/iam/docs/reference/credentials/rest) API (`iamcredentials.googleapis.com` v1) with REST JSON, supporting `generateAccessToken`, `signBlob` and `signJwt` for service-account impersonation flows (for example `ImpersonatedCredentials` in the GCP SDKs, which call `signBlob` to make GCS signed URLs).

The endpoint returns an opaque emulator token (`floci-gcp-impersonated-<uuid>`)
that the supported IAM-enforcing services resolve to a service-account identity.
With `FLOCI_GCP_SERVICES_IAM_AUTHORIZATION_MODE=enforce`, a recognized caller needs
`iam.serviceAccounts.getAccessToken` on the target account, including applicable
project policies. Signing requires `iam.serviceAccounts.signBlob` or
`iam.serviceAccounts.signJwt` respectively. See the [method and role inventory](iam.md#service-account-enforcement).
Anonymous/external credentials bypass this evaluation, including bootstrap token
issuance. Tokens are local emulator credentials and cannot be used with real GCP.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_IAMCREDENTIALS_ENABLED` | `true` | Enable or disable the IAM Credentials service |

## Endpoint

```
POST /v1/projects/-/serviceAccounts/{serviceAccount}:generateAccessToken
```

Request body:

| Field | Behavior |
|---|---|
| `scope` | Required. Must include at least one of `https://www.googleapis.com/auth/cloud-platform` or `https://www.googleapis.com/auth/devstorage.read_write`; a request containing neither is rejected with `INVALID_ARGUMENT` |
| `lifetime` | Optional `"<n>s"` duration string. Defaults to `3600s`; values above 3600s are capped; non-positive values are rejected |
| `delegates` | Empty or omitted for direct requests. Non-empty chains are unsupported and fail with `FAILED_PRECONDITION` for evaluated callers in enforce mode; bypass/disabled callers retain the previous ignored behavior |

Response: `{ "accessToken": "...", "expireTime": "..." }`.

```
POST /v1/projects/-/serviceAccounts/{serviceAccount}:signBlob
POST /v1/projects/-/serviceAccounts/{serviceAccount}:signJwt
```

Both sign with the service account's Google-managed RSA-2048 key (see [Sign Blob](iam.md#sign-blob-v4-signed-urls)); the account must exist, or the call fails with `PERMISSION_DENIED`.

| Method | Request | Response |
|---|---|---|
| `signBlob` | `payload`: base64 bytes | `{ "keyId", "signedBlob" }`, an RSASSA-PKCS1-v1_5 SHA-256 signature |
| `signJwt` | `payload`: a JSON object of JWT claims; an `exp` claim must be an integer timestamp, not in the past and at most 12 hours ahead | `{ "keyId", "signedJwt" }`, an RS256 JWT whose header carries `kid` |

IAM v1 `serviceAccounts.signBlob` uses the same path when the project is `-`; a request that sends `bytesToSign` instead of `payload` is answered in the IAM v1 shape. Because that path is served here, it returns `UNAVAILABLE` while the IAM Credentials service is disabled.

## Quick Start

```java
ImpersonatedCredentials credentials = ImpersonatedCredentials.newBuilder()
    .setSourceCredentials(sourceCredentials)
    .setTargetPrincipal("my-sa@floci-local.iam.gserviceaccount.com")
    .setScopes(List.of("https://www.googleapis.com/auth/cloud-platform"))
    .setLifetime(3600)
    .setIamEndpointOverride("http://localhost:4588")
    .build();
```

## Not Yet Supported

- `generateIdToken`
- Scope-based authorization of the vended token (the supported IAM evaluator checks identity and policies, not OAuth scopes)
- Delegation chains (only direct impersonation is IAM-enforced)
