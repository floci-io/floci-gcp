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
| GCS buckets | Supported REST bucket metadata, IAM-policy, retention-lock, storage-layout, and notification operations; bucket `testIamPermissions` filters its response |
| GCS objects | Supported REST JSON and XML reads, writes, updates, deletes, listing, compose, copy, rewrite, move, restore, resumable uploads, and XML multipart uploads |
| Pub/Sub | **Not IAM-enforced.** Service enforcement is deferred |
| Secret Manager | **Not IAM-enforced.** Service enforcement is deferred |
| GCS ACLs and GCS gRPC | **Not IAM-enforced.** Existing downscoped-token CAB checks still apply |
| IAM service-account/key management, IAM Credentials, Cloud Run, all other services | **Not IAM-enforced**, including any policies those services store |

Valid **Floci-issued service-account tokens** activate IAM evaluation on every
supported surface. Mint one through the existing IAM Credentials
`generateAccessToken` endpoint and pass it as `Authorization: Bearer TOKEN` or
through your SDK credentials provider.
For plaintext gRPC, Google credentials may refuse an insecure channel before the
request reaches the emulator; use a fixed authorization header with the SDK's
no-credentials provider, or use the emulator's TLS transport.
Anonymous requests and external credentials bypass Resource Manager evaluation.
GCS bucket evaluation treats those callers as anonymous so `allUsers` policies
work. In enforce mode, an unknown or expired Floci-issued token receives
`UNAUTHENTICATED`; an evaluated caller without a matching grant receives
`403 PERMISSION_DENIED` over REST or `PERMISSION_DENIED` over gRPC. GCS applies
the Credential Access Boundary before IAM. Source-principal handoff is deferred,
so downscoped credentials cannot use enforce mode or expand their boundary into
project or bucket management permissions.

This is a local permission-testing tool, **not an authentication/security boundary**.
Token minting and impersonation remain unrestricted. A test using anonymous or
external credentials can still pass without exercising IAM.

The framework supports child-to-project inheritance and multiple required permission
checks. Bucket evaluation includes the owning project's policy; the project cannot
be inferred from the `projects/_/buckets/...` resource name and is read from the
stored bucket metadata. Project bindings do not authorize or restrict Pub/Sub,
Secret Manager, or other services without a registered adapter.
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
  project permissions. Editor/viewer permit project metadata and policy reads,
  but not project policy writes.

In enforce mode, project policies cannot contain `allUsers` or
`allAuthenticatedUsers`. Such bindings produce `INVALID_ARGUMENT` on policy writes
and when evaluating previously stored project policies. Disabled mode retains
permissive policy storage.

The finite catalog also contains `roles/storage.objectViewer`,
`roles/storage.objectCreator`, `roles/storage.objectAdmin`, and
`roles/storage.admin` for the explicitly enforced bucket and object permissions.
An unsupported role in a binding that applies to the caller produces
`FAILED_PRECONDITION` naming the role and policy. Bindings for other callers do
not block evaluation. Missing resource/operation mappings on an enforcing adapter
also produce a named `FAILED_PRECONDITION`. The shared `google.iam.v1.IAMPolicy`
gRPC mixin enforces project policy resources and rejects resource kinds without a registered mapping for recognized
callers in enforce mode, including Pub/Sub policy calls routed through that mixin.
Those policy APIs support anonymous setup calls. Unsupported CEL expressions produce
`INVALID_ARGUMENT`; they are not silently treated as a policy denial.

Version 3 bindings use the existing restricted IAM Conditions profile, including
resource service/type/name, string comparisons and prefixes, logical operators,
and request-time comparison. The CEL standard library is not enabled.
The catalog and condition profile are subsets of Google Cloud IAM.

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
- Resource names are evaluated as requested. Project ID/number aliases are not
  canonicalized to equivalent numeric resource names.
- Only declared operation permissions are checked. Dependent service-agent,
  `actAs`, push-authentication, BigQuery/Storage export, CMEK, and other cross-service
  grants are not validated. This emulator cannot prove those configurations work
  in GCP.
- Policy reads and business mutations are separate operations. There is no atomic
  policy-change/resource-mutation transaction; this is not a revocation-timing test.
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

`SignBlob` accepts the bytes to sign and returns a stub signature, which is sufficient for local development.

## Supported Operations

- `CreateServiceAccount`
- `GetServiceAccount`
- `ListServiceAccounts`
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
