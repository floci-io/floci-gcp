# IAM Service Account Credentials

floci-gcp emulates the [IAM Service Account Credentials](https://cloud.google.com/iam/docs/reference/credentials/rest) API (`iamcredentials.googleapis.com` v1) with REST JSON, supporting `generateAccessToken` for service-account impersonation flows (for example `ImpersonatedCredentials` in the GCP SDKs).

Since the emulator accepts all requests without credential validation, impersonation is **shape-only**: the endpoint returns a wire-compatible response, but the vended token is an opaque stub (`floci-gcp-impersonated-<uuid>`) that no other emulated service validates.

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
| `scope` | Required. Must include `https://www.googleapis.com/auth/cloud-platform` or `https://www.googleapis.com/auth/devstorage.read_write`; other scopes are rejected with `INVALID_ARGUMENT` |
| `lifetime` | Optional `"<n>s"` duration string. Defaults to `3600s`; values above 3600s are capped; non-positive values are rejected |
| `delegates` | Accepted and ignored (no delegation chain is enforced) |

Response: `{ "accessToken": "...", "expireTime": "..." }`.

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

- `generateIdToken`, `signBlob`, `signJwt` on this surface (IAM's own `signBlob` lives in the [IAM service](iam.md))
- Scope-based authorization of the vended token (tokens are never validated)
- Delegation chains (`delegates` is ignored)
