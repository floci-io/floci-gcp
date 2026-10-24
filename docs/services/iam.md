# IAM

floci-gcp emulates Google Cloud IAM over REST JSON and the shared IAM policy gRPC mixin.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_IAM_ENABLED` | `true` | Enable/disable IAM |
| `FLOCI_GCP_SERVICES_IAM_AUTHORIZATION_MODE` | `disabled` | Set to `enforce` to evaluate allow policies for the supported services below |
| `FLOCI_GCP_SERVICES_IAM_BOOTSTRAP_ADMIN_MEMBER` | unset | Optional IAM member granted `roles/storage.admin` on each newly created bucket |

## Opt-in enforcement

Set `FLOCI_GCP_SERVICES_IAM_AUTHORIZATION_MODE=enforce`, or
`floci-gcp.services.iam.authorization-mode: enforce` in YAML. The default is
`disabled`: stored policies do not restrict requests. Startup logs always report
which mode is active and the supported service list.

| Surface | IAM enforcement in `enforce` mode |
|---|---|
| Resource Manager v1 project metadata and project policies | REST JSON; project policies also use the shared IAM gRPC mixin |
| Cloud Run v2 services and revisions | REST JSON service and revision operations; service-policy methods also use the shared IAM gRPC API. See [Cloud Run IAM enforcement](cloud-run.md#iam-enforcement-for-services) |
| GCS buckets | Supported REST bucket metadata, IAM-policy, retention-lock, storage-layout, and notification operations; bucket `testIamPermissions` filters its response |
| GCS objects | Supported REST JSON and XML reads, writes, updates, deletes, listing, compose, copy, rewrite, move, restore, resumable uploads, and XML multipart uploads |
| Pub/Sub | REST JSON and gRPC topic, subscription, snapshot, and policy methods; `testIamPermissions` filters its response |
| Cloud Scheduler | REST JSON and gRPC job create/get/list/update/delete/pause/resume/run; project policy inheritance. See [Scheduler IAM enforcement](scheduler.md#iam-enforcement) |
| Secret Manager | REST JSON and gRPC secret and version operations; see [Secret Manager IAM enforcement](secret-manager.md#iam-enforcement) |
| IAM service accounts and keys | REST JSON service-account CRUD, key management, IAM policy methods over REST and shared IAM gRPC, `signBlob`, and IAM Credentials `generateAccessToken`, `signBlob`, and `signJwt` |
| GCS ACLs and GCS gRPC | **Not IAM-enforced.** Existing downscoped-token CAB checks still apply |
| IAM custom roles, IAM Credentials `generateIdToken`, Cloud Run jobs, worker pools, instances, invocation and operations, all other services | **Not IAM-enforced**, including any policies those services store |

Valid **Floci-issued service-account tokens** activate IAM evaluation on every
supported surface. Mint one through the existing IAM Credentials
`generateAccessToken` endpoint and pass it as `Authorization: Bearer TOKEN` or
through your SDK credentials provider.
For plaintext gRPC, Google credentials may refuse an insecure channel before the
request reaches the emulator; use a fixed authorization header with the SDK's
no-credentials provider, or use the emulator's TLS transport.
Anonymous requests and external credentials bypass Resource Manager, Cloud Run,
Secret Manager, Pub/Sub, and IAM service-account evaluation.
GCS evaluation treats those callers as anonymous so `allUsers` policies
work. In enforce mode, an unknown or expired Floci-issued token receives
`UNAUTHENTICATED`; an evaluated caller without a matching grant receives
`403 PERMISSION_DENIED` over REST or `PERMISSION_DENIED` over gRPC. GCS applies
the Credential Access Boundary before IAM and retains the source principal for
IAM evaluation of valid downscoped credentials. Downscoped tokens cannot expand
their boundary into project or bucket management permissions.

This is a local permission-testing tool, **not an authentication/security boundary**.
Anonymous or external callers can still mint emulator credentials because those
identities bypass policy evaluation. A test using such credentials can pass
without exercising IAM.

The framework supports child-to-project inheritance and multiple required permission
checks. Bucket evaluation includes the owning project's policy; the project cannot
be inferred from the `projects/_/buckets/...` resource name and is read from the
stored bucket metadata. Pub/Sub resources, Cloud Run services, and Secret Manager
secrets inherit their project policy. Project bindings do not authorize or restrict
other services without a registered adapter.
`testIamPermissions` evaluates each requested permission for the same caller and
resource; missing resources keep the existing empty-result behavior. Disabled
mode and bypass callers retain the existing permission echo.

Bucket policies inherit to objects. Restore requires `storage.objects.restore`
and `storage.objects.create`, plus `storage.objects.delete` when it replaces a live
object. Move accepts `storage.objects.move` on the source, or both
`storage.objects.get` and `storage.objects.delete`, and requires
`storage.objects.create` on the destination. Resumable-upload permissions are
established when the session is opened; the session URI then acts as the
authorization token for status queries and chunks, including requests without an
`Authorization` header.

XML multipart upload IDs are not authentication tokens. Every initiate, part upload,
completion, list, list-parts, and abort request evaluates its documented
`storage.multipartUploads.*` permission. Uploading parts and completing an upload
also require `storage.objects.create`; completion requires `storage.objects.delete`
when it replaces a live object.

For a downscoped token derived from a Floci-issued IAM Credentials impersonated
token, Floci preserves the source service-account identity. Object requests
first satisfy the token's Credential Access Boundary (CAB), then satisfy the
bucket policy for that service account. Consequently, a bucket policy cannot
extend a CAB grant, and a CAB cannot extend a bucket-policy grant. A downscoped
token from an external source credential has no named IAM identity and, in
`enforce` mode, can match only an `allUsers` binding.

### Bucket enforcement bootstrap

When Floci can resolve a new bucket's caller from a valid Floci-issued
impersonated token, it persists a bucket-level `roles/storage.admin` binding for
that service-account member. This lets the creator manage the bucket after the
emulator is started with `authorization-mode: enforce`.

For callers without a resolvable identity, set
`FLOCI_GCP_SERVICES_IAM_BOOTSTRAP_ADMIN_MEMBER` to an IAM member such as
`serviceAccount:admin@example.iam.gserviceaccount.com`. That member receives
`roles/storage.admin` on every subsequently created bucket. Treat this setting
as an administrator credential: it is deliberately powerful, and an `allUsers`
value makes every new bucket publicly manageable. The setting accepts only
`serviceAccount:` members, `allAuthenticatedUsers`, or `allUsers`.

### Supported roles and conditions

The finite catalog includes the implemented-operation permissions from:

- `roles/browser` and `roles/resourcemanager.projectIamAdmin`.
- `roles/owner`, `roles/editor`, and `roles/viewer`, restricted to the declared
  service permissions. Editor/viewer permit project metadata and policy reads,
  but not project policy writes.

In enforce mode, project policies cannot contain `allUsers` or
`allAuthenticatedUsers`. Such bindings produce `INVALID_ARGUMENT` on policy writes
and when evaluating previously stored project policies. Disabled mode retains
permissive policy storage.

The finite catalog also contains `roles/storage.objectViewer`,
`roles/storage.objectCreator`, `roles/storage.objectUser`, `roles/storage.objectAdmin`, and
`roles/storage.admin` for the explicitly enforced bucket and object permissions.
Secret Manager roles, permission targets, and inheritance are described in the
[Secret Manager guide](secret-manager.md#iam-enforcement).
Uncatalogued roles grant no permissions and do not block known
bindings, including when they apply to the caller. Missing resource/operation
mappings on an enforcing adapter produce a named `FAILED_PRECONDITION`. The shared
`google.iam.v1.IAMPolicy` gRPC mixin enforces project, service-account, Cloud Run
service, Secret Manager secret, and Pub/Sub topic, subscription, and snapshot policy resources. It rejects resource kinds without
a registered mapping for recognized callers in enforce mode.
Those policy APIs support anonymous setup calls. Unsupported CEL expressions produce
`INVALID_ARGUMENT`; they are not silently treated as a policy denial.

Version 3 bindings use the existing restricted IAM Conditions profile, including
resource service/type/name, string comparisons and prefixes, logical operators,
and request-time comparison. The CEL standard library is not enabled.
The catalog and condition profile are subsets of Google Cloud IAM.

Cloud Logging GCS sinks authorize fresh object creation as their persisted writer principal,
including bucket/project policy grants and supported conditions. They do not use the logging
caller's credentials. See [GCS export](logging.md#gcs-export). Sink management authorization
is not implemented.

### Service-account enforcement

The following inventory covers the implemented IAM v1 REST methods. Links point
at the exact Google API method contract. `P` is `projects/{project}`; `SA` is
`P/serviceAccounts/{email}`; `K` is `SA/keys/{key}`.

| Method | Required permission | Authorization target | Implemented transport |
|---|---|---|---|
| [create](https://docs.cloud.google.com/iam/reference/rest/v1/projects.serviceAccounts/create) | `iam.serviceAccounts.create` | P | REST POST |
| [list](https://docs.cloud.google.com/iam/reference/rest/v1/projects.serviceAccounts/list) | `iam.serviceAccounts.list` | P | REST GET |
| [get](https://docs.cloud.google.com/iam/reference/rest/v1/projects.serviceAccounts/get) | `iam.serviceAccounts.get` | SA | REST GET |
| [update](https://docs.cloud.google.com/iam/reference/rest/v1/projects.serviceAccounts/update), [patch](https://docs.cloud.google.com/iam/reference/rest/v1/projects.serviceAccounts/patch) | `iam.serviceAccounts.update` | SA | REST PUT, PATCH |
| [delete](https://docs.cloud.google.com/iam/reference/rest/v1/projects.serviceAccounts/delete) | `iam.serviceAccounts.delete` | SA | REST DELETE |
| [keys.create](https://docs.cloud.google.com/iam/reference/rest/v1/projects.serviceAccounts.keys/create) | `iam.serviceAccountKeys.create` | SA | REST POST |
| [keys.list](https://docs.cloud.google.com/iam/reference/rest/v1/projects.serviceAccounts.keys/list) | `iam.serviceAccountKeys.list` | SA | REST GET |
| [keys.get](https://docs.cloud.google.com/iam/reference/rest/v1/projects.serviceAccounts.keys/get) | `iam.serviceAccountKeys.get` | K | REST GET |
| [keys.delete](https://docs.cloud.google.com/iam/reference/rest/v1/projects.serviceAccounts.keys/delete) | `iam.serviceAccountKeys.delete` | K | REST DELETE |
| [getIamPolicy](https://docs.cloud.google.com/iam/reference/rest/v1/projects.serviceAccounts/getIamPolicy) | `iam.serviceAccounts.getIamPolicy` | SA | REST POST, shared IAM v1 gRPC |
| [setIamPolicy](https://docs.cloud.google.com/iam/reference/rest/v1/projects.serviceAccounts/setIamPolicy) | `iam.serviceAccounts.setIamPolicy` | SA | REST POST, shared IAM v1 gRPC |
| [testIamPermissions](https://docs.cloud.google.com/iam/reference/rest/v1/projects.serviceAccounts/testIamPermissions) | No prerequisite permission; filters requested permissions through the evaluator | SA | REST POST, shared IAM v1 gRPC |
| [signBlob](https://docs.cloud.google.com/iam/reference/rest/v1/projects.serviceAccounts/signBlob) | `iam.serviceAccounts.signBlob` | SA | IAM v1 REST POST |
| [generateAccessToken](https://docs.cloud.google.com/iam/docs/reference/credentials/rest/v1/projects.serviceAccounts/generateAccessToken) | `iam.serviceAccounts.getAccessToken` | SA | IAM Credentials v1 REST POST |
| [Credentials signBlob](https://docs.cloud.google.com/iam/docs/reference/credentials/rest/v1/projects.serviceAccounts/signBlob) | `iam.serviceAccounts.signBlob` | SA | IAM Credentials v1 REST POST |
| [Credentials signJwt](https://docs.cloud.google.com/iam/docs/reference/credentials/rest/v1/projects.serviceAccounts/signJwt) | `iam.serviceAccounts.signJwt` | SA | IAM Credentials v1 REST POST |

Service-account policies and project policies participate in each account/key
check. Key operations use the account's policy and owning project, with the key
name as the evaluation target for get/delete. Account IDs, unique IDs, and the
`projects/-` wildcard resolve through the existing service-account resolver to a
canonical email policy key. Authorization resolves names without migrating
policies. When the canonical email policy is absent, it reads legacy
account-ID, unique-ID, and wildcard-address policies without changing their keys. An existing canonical policy takes
precedence, including an empty policy. Policy methods may subsequently adopt
legacy policy keys using the existing migration path.

Creation and deletion clear the account's direct policies, including legacy aliases.
Account updates, policy writes, migration, and these lifecycle transitions use
ordered policy locks, so an update cannot republish a deleted account and a
replacement cannot inherit its previous direct policy. Policy reads and writes require
an existing account. Permission tests on missing accounts addressed by project ID return
an empty permission list, following the shared
[IAM policy contract](https://github.com/googleapis/googleapis/blob/master/google/iam/v1/iam_policy.proto).
This implements the local resource boundary;
[Google treats a recreated service account as a separate identity](https://docs.cloud.google.com/iam/docs/service-accounts-delete-undelete).
Floci's email-based principal matching does not reproduce Google's deleted-principal
and undelete lifecycle.

The [Google IAM roles and permissions reference](https://docs.cloud.google.com/iam/docs/roles-permissions/iam)
defines these finite role slices for the implemented permissions:

| Role | Supported IAM permission slice |
|---|---|
| `roles/iam.serviceAccountAdmin` | Service-account create, get, list, update, delete, getIamPolicy, setIamPolicy |
| `roles/iam.serviceAccountKeyAdmin` | Key create, get, list, delete; service-account get, list |
| `roles/iam.serviceAccountUser` | Service-account get, list; `actAs` is not an implemented operation |
| `roles/iam.serviceAccountTokenCreator` | Service-account get, list, getAccessToken, signBlob, signJwt |
| `roles/viewer` | Service-account get, list, getIamPolicy; key get, list |
| `roles/editor` | Viewer slice plus service-account create, update, delete and key create, delete |
| `roles/owner` | Editor slice plus service-account setIamPolicy |

Each listed predefined IAM role also contributes `resourcemanager.projects.get`
for the supported Resource Manager adapter. Basic roles do not grant
`getAccessToken`, `signBlob`, or `signJwt`. These are explicit slices of Google's roles,
not the full production role catalog.

The local evaluator exposes `resource.service = 'iam.googleapis.com'`,
`resource.type = 'iam.googleapis.com/ServiceAccount'` (or `iam.googleapis.com/Key`),
and canonical email-based `resource.name` values. Create/list use the project
attributes. Supported equality, prefix, and logical conditions use the shared
[condition subset](#supported-roles-and-conditions). Google does not document these IAM resource
attributes in its [supported resource attribute table](https://docs.cloud.google.com/iam/docs/conditions-resource-attributes).
Treat this as local scope-testing behavior, not proof that the same expression
or alias representation works in production. Exact GCP condition evaluation,
error messages/metadata, and revocation timing have not been verified live.

Authorization happens in the shared REST filter or gRPC interceptor before the
controller invokes an operation. Denied management requests must leave accounts,
keys, and policies unchanged; denied credential requests must not issue a token
or signing response. The existing filters do not hold policy locks across the
subsequent mutation, so they provide no atomic policy-revocation guarantee.

IAM custom-role management is not implemented by this API surface. Custom-role
creation, update, deletion, and effective permissions defined by custom roles
remain unsupported. Service-account enable, disable, undelete, key upload,
enable/disable, IAM v1 signJwt, and Credentials generateIdToken
are also unimplemented. Only the shared IAM policy gRPC mixin is exposed;
IAM admin and IAM Credentials gRPC services are not implemented. Non-empty
credential delegation chains fail with `FAILED_PRECONDITION` for evaluated
callers; disabled and bypass callers retain the existing permissive behavior.
IAM permissions alone do not define a fixed list of roles a caller may grant;
Organization Policy constraints for limiting grants are unsupported.

OAuth `/token`, STS exchanges, and metadata credentials retain their existing
emulator behavior. OAuth's stub exchange does not authenticate a service-account
JWT; its token is not a recognized service-account identity. Anonymous/external
credential bypass makes this emulator a local testing tool, not a security
boundary. IAM v1 `signBlob` returns the `keyId` and `signature` fields required by
its contract. IAM v1 and Credentials signing share the emulator's per-account
RSA key described in [Sign Blob](#sign-blob-v4-signed-urls). This is a local key,
not a Google-managed production credential.

### Remaining limitations

- Object-list conditions authorize the bucket-level `storage.objects.list`
  permission but do not filter returned objects.
- GCS gRPC and ACL operations are not restricted by IAM allow policies. Signed-URL
  identity is also outside the evaluator: because the emulator does not
  cryptographically verify V4 signatures, signed URLs are treated as anonymous and
  can access only resources granted to `allUsers` in enforce mode.
- Conditional GCS bindings require UBLA. A bucket update cannot disable, remove, or
  partially clear UBLA while conditional bindings remain configured. The full UBLA
  lifecycle is not implemented.
- Organization/folder inheritance, deny policies, principal access boundaries,
  custom roles, group/domain membership expansion, workforce/workload identities,
  and organization policy constraints are not implemented.
- Service-account address aliases resolve to canonical email policy names. Other
  resource names are evaluated as requested. Project ID/number aliases are not
  canonicalized to equivalent numeric resource names.
- Only declared operation permissions are checked. Dependent service-agent,
  `actAs`, push-authentication, BigQuery export, CMEK, and other cross-service
  grants are not validated. This emulator cannot prove those configurations work
  in GCP.
- Shared REST/gRPC interceptors do not hold policy locks through business mutations.
  GCS object operations and Logging GCS export use service-level locks through
  publication; other integrations must provide their own consistency boundary.
- The emulator does not validate that a role is grantable on the policy resource.
  Unsupported permissions in a known role are absent from the catalog.

See [Adding an enforcing service](../iam-enforcement.md) for the shared extension
contract, test requirements, and upstream evidence.

## Quick Start

=== "gcloud CLI"

    ```bash
    gcloud config set project floci-local

    # Create a service account
    gcloud iam service-accounts create my-sa \
        --display-name="My Service Account"

    # List service accounts
    gcloud iam service-accounts list

    # Create a key
    gcloud iam service-accounts keys create key.json \
        --iam-account=my-sa@floci-local.iam.gserviceaccount.com

    # Delete a key
    gcloud iam service-accounts keys delete KEY_ID \
        --iam-account=my-sa@floci-local.iam.gserviceaccount.com
    ```

=== "REST API"

    ```bash
    # Create service account
    curl -X POST http://localhost:4588/v1/projects/floci-local/serviceAccounts \
      -H "Content-Type: application/json" \
      -d '{"accountId":"my-sa","serviceAccount":{"displayName":"My SA"}}'

    # List service accounts
    curl http://localhost:4588/v1/projects/floci-local/serviceAccounts

    # Get service account
    curl http://localhost:4588/v1/projects/floci-local/serviceAccounts/my-sa@floci-local.iam.gserviceaccount.com

    # Delete service account
    curl -X DELETE http://localhost:4588/v1/projects/floci-local/serviceAccounts/my-sa@floci-local.iam.gserviceaccount.com
    ```

## Service Accounts

Service accounts follow the GCP naming convention:

```
projects/{project}/serviceAccounts/{account}@{project}.iam.gserviceaccount.com
```

The `-` wildcard can replace the project ID in any service account or key name
(`projects/-/serviceAccounts/{EMAIL_ADDRESS|UNIQUE_ID}`), which is what Terraform sends.
The owning project is resolved from the account, and resource names in responses carry
the real project. As on GCP, a missing account addressed through `-` returns
`403 PERMISSION_DENIED` instead of `404 NOT_FOUND`.

## Service Account Keys

```bash
# Create key
curl -X POST \
  http://localhost:4588/v1/projects/floci-local/serviceAccounts/my-sa@floci-local.iam.gserviceaccount.com/keys \
  -H "Content-Type: application/json" \
  -d '{}'

# List keys
curl http://localhost:4588/v1/projects/floci-local/serviceAccounts/my-sa@floci-local.iam.gserviceaccount.com/keys
```

## IAM Policy Bindings

```bash
# Grant Secret Manager access to a service account
gcloud secrets add-iam-policy-binding my-secret \
    --member="serviceAccount:my-sa@floci-local.iam.gserviceaccount.com" \
    --role="roles/secretmanager.secretAccessor"
```

## Sign Blob (V4 Signed URLs)

The IAM `SignBlob` endpoint is used by the GCS SDK to generate V4 pre-signed URLs:

```java
URL signedUrl = storage.signUrl(
    BlobInfo.newBuilder("my-bucket", "hello.txt").build(),
    15, TimeUnit.MINUTES,
    Storage.SignUrlOption.withV4Signature());
```

`SignBlob` (`{"bytesToSign": "<base64>"}`) returns `{"keyId", "signature"}`: an RSASSA-PKCS1-v1_5 SHA-256 signature made with the service account's Google-managed RSA-2048 key. Each account gets one such key the first time it signs, kept until the account is deleted, and the same key signs for the [IAM Credentials](iam-credentials.md) `signBlob` and `signJwt` methods.

The signing key is not returned by `ListServiceAccountKeys` (real IAM lists it as a `SYSTEM_MANAGED` key), so its public half cannot be read back and signatures cannot be verified against it locally. Nothing in floci-gcp verifies them: GCS checks a signed URL's expiry, not its signature.

## Supported Operations

- `CreateServiceAccount`
- `GetServiceAccount`
- `ListServiceAccounts`
- `UpdateServiceAccount`
- `PatchServiceAccount`
- `DeleteServiceAccount`
- `CreateServiceAccountKey` (real RSA-2048 key pair; returns JSON key file)
- `GetServiceAccountKey`
- `ListServiceAccountKeys`
- `DeleteServiceAccountKey`
- `GetIamPolicy`
- `SetIamPolicy`
- `TestIamPermissions`
- `SignBlob`

## Related: Service Account Impersonation

`generateAccessToken` (the `iamcredentials.googleapis.com` API used by `ImpersonatedCredentials`) is provided by the separate [IAM Credentials service](iam-credentials.md), toggled with `FLOCI_GCP_SERVICES_IAMCREDENTIALS_ENABLED`.
