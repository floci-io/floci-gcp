# Security Token Service (STS)

floci-gcp implements the OAuth 2.0 token-exchange endpoint used by Google
`DownscopedCredentials`. The endpoint accepts a source access token and a
Credential Access Boundary (CAB), then returns an emulator-managed downscoped
access token.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_STS_ENABLED` | `true` | Enable/disable STS token exchange |

## Endpoint

| Method | Path | Content type |
|---|---|---|
| `POST` | `/v1/token` | `application/x-www-form-urlencoded` |

The supported exchange requires these form fields:

| Field | Required value |
|---|---|
| `grant_type` | `urn:ietf:params:oauth:grant-type:token-exchange` |
| `subject_token_type` | `urn:ietf:params:oauth:token-type:access_token` |
| `requested_token_type` | `urn:ietf:params:oauth:token-type:access_token` |
| `subject_token` | The source access token |
| `options` | A JSON-encoded GCS Credential Access Boundary |

Validation failures use the OAuth error fields `error` and
`error_description`.

## Credential Access Boundary support

The CAB parser supports Cloud Storage bucket resources and these permissions:

- `inRole:roles/storage.legacyObjectReader`
- `inRole:roles/storage.objectViewer`
- `inRole:roles/storage.legacyBucketWriter`

`availabilityCondition` is optional. Without it, the rule applies to the whole
bucket. When present, the condition can restrict access to one object prefix
using `resource.name.startsWith(...)`,
`api.getAttribute('storage.googleapis.com/objectListPrefix', '').startsWith(...)`,
or both expressions joined with `||`. Both expressions must identify the same
prefix.

## Token lifetime

Tokens exchanged from arbitrary external source credentials have a one-hour
lifetime. When the source is an unexpired impersonated or downscoped token
issued by floci-gcp, the new token cannot outlive that source token. Unknown or
expired floci-gcp source tokens are rejected with `invalid_grant`.

## Scope and deviations

- Only access-token-to-access-token exchange is implemented.
- Only the documented GCS CAB resource, permission, and prefix-expression
  subset is accepted.
- Non-floci source credentials are not validated, matching the emulator's
  general credential-bypass behavior.
