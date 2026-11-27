# Security Token Service (STS)

floci-gcp implements the OAuth 2.0 token-exchange endpoint (`POST /v1/token`)
for two Google Cloud STS workflows:

1. **Credential Access Boundary (CAB) downscoping** (`DownscopedCredentials`):
   accepts a source access token and a CAB `options` payload, and returns an
   emulator-managed downscoped access token.
2. **Workload Identity Federation** (external subject tokens): accepts an
   external OIDC JWT (`urn:ietf:params:oauth:token-type:jwt` or
   `urn:ietf:params:oauth:token-type:id_token`) or an AWS signed
   `GetCallerIdentity` request (`urn:ietf:params:aws:token-type:aws4_request`)
   targeting an active Workload Identity Pool Provider, evaluates CEL
   `attributeMapping` and `attributeCondition`, and returns an emulator-managed
   federated access token.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_STS_ENABLED` | `true` | Enable/disable STS token exchange |

## Endpoint

| Method | Path | Content types |
|---|---|---|
| `POST` | `/v1/token` | `application/x-www-form-urlencoded`, `application/json` |

JSON requests accept both standard OAuth `snake_case` field names
(`grant_type`, `subject_token_type`, `subject_token`, `requested_token_type`,
`audience`, `scope`, `options`) and Google REST `camelCase` equivalents
(`grantType`, `subjectTokenType`, `subjectToken`, `requestedTokenType`,
`audience`, `scope`, `options`).

Validation failures return HTTP 400 with the OAuth error fields `error` and
`error_description` (such as `invalid_request`, `invalid_target`,
`invalid_grant`).

### Credential Access Boundary exchange

| Field | Required value |
|---|---|
| `grant_type` | `urn:ietf:params:oauth:grant-type:token-exchange` |
| `subject_token_type` | `urn:ietf:params:oauth:token-type:access_token` |
| `requested_token_type` | `urn:ietf:params:oauth:token-type:access_token` |
| `subject_token` | The source access token |
| `options` | A JSON-encoded GCS Credential Access Boundary |

### External subject-token exchange (Workload Identity Federation)

| Field | Required value |
|---|---|
| `grant_type` | `urn:ietf:params:oauth:grant-type:token-exchange` |
| `subject_token_type` | `urn:ietf:params:oauth:token-type:jwt`, `urn:ietf:params:oauth:token-type:id_token`, or `urn:ietf:params:aws:token-type:aws4_request` |
| `requested_token_type` | `urn:ietf:params:oauth:token-type:access_token` |
| `audience` | `//iam.googleapis.com/projects/{projectNumber}/locations/global/workloadIdentityPools/{poolId}/providers/{providerId}` (an `https://` prefix or project ID is also accepted) |
| `subject_token` | Signed OIDC JWT or URL-encoded/raw JSON AWS SigV4 `GetCallerIdentity` request |
| `scope` | Optional OAuth scope echoed in the response when present |

The target pool and provider must exist, be `ACTIVE`, and not be `disabled`.

#### OIDC / JWT verification

When `subject_token_type` is `urn:ietf:params:oauth:token-type:jwt` or
`urn:ietf:params:oauth:token-type:id_token`, the target provider must be an
OIDC provider:

- `iss` must match `oidc.issuerUri`.
- `aud` (string or array) must match `oidc.allowedAudiences`, or when
  `allowedAudiences` is empty, `https://iam.googleapis.com/<providerName>` or
  `//iam.googleapis.com/<providerName>`.
- `exp` must be present and in the future; `nbf` (if present) must not be in
  the future; `iat` (if present) must not be in the future (300-second clock
  skew leeway).
- The JWS signature (`RS256`, `RS384`, `RS512`, `ES256`, `ES384`, `ES512`) is
  verified against keys configured in the provider's inline `oidc.jwksJson`.
  `alg: none` and missing `oidc.jwksJson` are rejected.

#### AWS `aws4_request` verification

When `subject_token_type` is `urn:ietf:params:aws:token-type:aws4_request`, the
target provider must be an AWS provider:

- `subject_token` must be a URL-encoded or raw JSON object describing a `POST`
  request to `https://sts[.<region>].amazonaws.com/?Action=GetCallerIdentity&Version=2011-06-15`.
- `host` must match the request URL host; `x-amz-date` must be within 15
  minutes of current UTC time; `x-goog-cloud-target-resource` must match the
  requested `audience`.
- `Authorization` must be a well-formed `AWS4-HMAC-SHA256` SigV4 header scoped
  to `sts/aws4_request` with a 64-hex signature and required signed headers
  (`host`, `x-amz-date`, `x-goog-cloud-target-resource`).
- The caller's AWS account ID must match `aws.accountId`.

#### Attribute mapping, conditions, and federated principals

Provider `attributeMapping` and `attributeCondition` are evaluated with CEL
against `assertion`, `attribute`, and `google` (including GCP's
`string.extract(template)` helper for AWS assumed-role ARN parsing).
`google.subject` must evaluate to a non-empty string of at most 127 UTF-8
bytes. For AWS providers without an explicit `attributeMapping`,
`google.subject` defaults to `assertion.arn`.

A successful exchange mints a one-hour federated access token
(`floci-gcp-federated-...`) carrying:

- Principal: `principal://iam.googleapis.com/projects/{projectNumber}/locations/global/workloadIdentityPools/{poolId}/subject/{google.subject}`
- Principal sets:
  - `principalSet://iam.googleapis.com/projects/{projectNumber}/locations/global/workloadIdentityPools/{poolId}/*`
  - `principalSet://.../attribute.{name}/{value}` for each mapped `attribute.*`
  - `principalSet://.../group/{group}` for each entry in `google.groups`

In IAM `enforce` mode, federated tokens are evaluated against service-account
and resource IAM policies (including `roles/iam.workloadIdentityUser` for
service-account impersonation via IAM Credentials and direct resource access
such as GCS).

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

Tokens exchanged from arbitrary external source credentials or via Workload
Identity Federation have a one-hour lifetime. When a CAB downscoping exchange
uses an unexpired Floci-issued IAM Credentials impersonated token or a
Floci-issued federated token as its source, the new downscoped token inherits
that source token's expiration time and principal (including federated
principal sets). A subsequent GCS object request must satisfy both the CAB and
that principal's bucket IAM policy when IAM enforcement is enabled. Unknown or
expired Floci-issued source tokens are rejected with `invalid_grant`.

## Scope and deviations

- **Intentionally simulated OIDC key discovery**: floci-gcp does not perform
  outbound HTTP requests to fetch `/.well-known/openid-configuration` or remote
  JWKS URLs. OIDC providers must supply their verification keys inline in
  `oidc.jwksJson`; tokens targeting an OIDC provider without `jwksJson` are
  rejected.
- **Intentionally simulated AWS SigV4 verification**: floci-gcp does not call
  live AWS STS (`sts.amazonaws.com`) to verify the HMAC signature against AWS
  IAM credentials. It validates the signed `GetCallerIdentity` request
  envelope, timestamp freshness, `x-goog-cloud-target-resource` binding, SigV4
  header format, and AWS account match locally. For local simulation, if
  `x-amz-security-token` contains an AWS ARN (`arn:aws:...:account:...`) or a
  12-digit account ID, floci-gcp derives `assertion.account`, `assertion.arn`,
  and `assertion.userid` from it; otherwise it synthesizes a deterministic
  caller identity under the provider's configured `aws.accountId`.
- SAML and X.509 external subject-token exchanges are not implemented.
- Only the documented GCS CAB resource, permission, and prefix-expression
  subset is accepted.
- Non-floci access-token source credentials in CAB downscoping are not
  validated, matching the emulator's general credential-bypass behavior.
- Recursive downscoping is not supported. A downscoped token issued by
  floci-gcp cannot be used as the source for another exchange and is rejected
  with `invalid_grant`. The emulator does not intersect the existing and
  requested boundaries, so replacing the existing boundary could broaden the
  token's authority. The exact Google STS behavior and error for this request
  have not been verified.
- A downscoped token minted by floci-gcp is enforced only for GCS requests:
  the request must match one of the token's CAB rules. Other credentials,
  including Floci-issued OAuth and impersonated tokens, remain accepted without
  credential validation.
- STS has no separate IAM-policy surface. CAB is an upper bound on the source
  credential's GCS authority, not an authorization policy for token exchange.
