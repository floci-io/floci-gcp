# IAM

floci-gcp emulates Google Cloud IAM over REST JSON and the shared IAM policy gRPC mixin.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_IAM_ENABLED` | `true` | Enable/disable IAM |
| `FLOCI_GCP_SERVICES_IAM_AUTHORIZATION_MODE` | `disabled` | Set to `enforce` to evaluate allow policies for the supported services below |

## Opt-in enforcement

Set `FLOCI_GCP_SERVICES_IAM_AUTHORIZATION_MODE=enforce`, or
`floci-gcp.services.iam.authorization-mode: enforce` in YAML. The default is
`disabled`: stored policies do not restrict requests. Startup logs always report
which mode is active and the supported service list.

| Surface | IAM enforcement in `enforce` mode |
|---|---|
| Resource Manager v1 project metadata and project policies | REST JSON; project policies also use the shared IAM gRPC mixin |
| Pub/Sub | **Not IAM-enforced.** |
| Secret Manager | **Not IAM-enforced.** |
| GCS | **Not IAM-enforced.** Existing downscoped-token CAB checks still apply |
| IAM service-account/key management, IAM Credentials, Cloud Run, all other services | **Not IAM-enforced**, including any policies those services store |

Only valid **Floci-issued service-account tokens** activate IAM evaluation. Mint
one through the existing IAM Credentials `generateAccessToken` endpoint and pass
it as `Authorization: Bearer TOKEN` or through your SDK credentials provider.
For plaintext gRPC, Google credentials may refuse an insecure channel before the
request reaches the emulator; use a fixed authorization header with the SDK's
no-credentials provider, or use the emulator's TLS transport.
Anonymous requests and external credentials retain the existing bypass, including
in `enforce` mode. Use anonymous setup requests to create resources and seed
policies, then use the issued token for permission assertions. In enforce mode, an unknown or
expired Floci-issued token receives `UNAUTHENTICATED`; a recognized caller without
a matching grant receives `403 PERMISSION_DENIED` over REST or
`PERMISSION_DENIED` over gRPC. Downscoped tokens are rejected on the enforced
non-GCS services; they cannot expand their CAB into other services.

This is a local permission-testing tool, **not an authentication/security boundary**.
Token minting and impersonation remain unrestricted. A test using anonymous or
external credentials can still pass without exercising IAM.

The framework supports child-to-project inheritance and multiple required permission
checks through service adapters. Only project resources are registered; project
bindings do not authorize or restrict Pub/Sub, Secret Manager, or GCS operations.
`testIamPermissions` evaluates each requested permission for the same caller and
resource; missing resources keep the existing empty-result behavior. Disabled
mode and bypass callers retain the existing permission echo.

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

The existing GCS role catalog remains available to the evaluator but does not
activate GCS IAM enforcement. Unsupported roles in an applicable policy produce
`FAILED_PRECONDITION` naming the role and policy, even if another binding would
grant access. Missing resource/operation mappings on an enforcing adapter also
produce a named `FAILED_PRECONDITION`. The shared `google.iam.v1.IAMPolicy`
gRPC mixin also rejects resource kinds without a registered mapping for recognized
callers in enforce mode, including Pub/Sub policy calls routed through that mixin.
Those policy APIs support anonymous setup calls. Unsupported CEL expressions produce
`INVALID_ARGUMENT`; they are not silently treated as a policy denial.

Version 3 bindings use the existing restricted IAM Conditions profile, including
resource service/type/name, string comparisons and prefixes, logical operators,
and request-time comparison. The CEL standard library is not enabled.
The catalog and condition profile are subsets of Google Cloud IAM.

### Remaining limitations

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
