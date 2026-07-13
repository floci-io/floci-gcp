# IAM

floci-gcp emulates Google Cloud IAM over REST JSON using the real GCP IAM API.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_IAM_ENABLED` | `true` | Enable/disable IAM |

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

## CTF hardening {#ctf-hardening}

Use this profile when floci-gcp backs a capture-the-flag or security exercise and participants must present valid Bearer tokens and IAM allow bindings rather than relying on permissive local defaults.

### Enforcement and strict mode

When `FLOCI_GCP_SERVICES_IAM_ENFORCEMENT_ENABLED=true`, `IamEnforcementFilter` evaluates project IAM allow policies for mapped REST requests. Operator root principals (see below) skip evaluation.

**Fail-closed missing principal:** With enforcement on, a missing authenticated principal is always denied on REST (`IamEnforcementFilter`) and on mapped gRPC methods (`IamEnforcementGrpcInterceptor`). This does not require strict mode.

When `FLOCI_GCP_SERVICES_IAM_STRICT_ENFORCEMENT_ENABLED=true` as well:

- Requests with no Stage 0 IAM permission mapping are denied (no permissive fall-through)

For gRPC, unmapped methods follow the strict-mode rule above.

### gRPC enforcement

The same `enforcement-enabled` / `strict-enforcement-enabled` flags gate gRPC via
`IamEnforcementGrpcInterceptor` (wired in `GrpcServerManager` after Bearer token validation).
`IamGrpcPermissionMapper` maps full method names for these ten surfaces:

| gRPC service | Permission family (examples) |
|---|---|
| Firestore (`google.firestore.v1.Firestore`) | `datastore.entities.*` |
| Datastore (`google.datastore.v1.Datastore`) | `datastore.entities.*` |
| Pub/Sub Publisher / Subscriber | `pubsub.topics.*` / `pubsub.subscriptions.*` / `pubsub.snapshots.*` |
| Secret Manager | `secretmanager.secrets.*` / `secretmanager.versions.*` |
| Cloud Tasks | `cloudtasks.queues.*` / `cloudtasks.tasks.*` |
| Cloud KMS | `cloudkms.keyRings.*` / `cloudkms.cryptoKeys.*` / `cloudkms.cryptoKeyVersions.*` / `cloudkms.locations.generateRandomBytes` |
| Cloud Scheduler | `cloudscheduler.jobs.*` |
| Cloud Logging | `logging.logEntries.*` / `logging.logs.*` |
| Cloud Monitoring | `monitoring.timeSeries.*` / `monitoring.metricDescriptors.*` / `monitoring.monitoredResourceDescriptors.*` |

Other gRPC methods are not in the mapper. Under strict mode they are denied for non-operator principals.

#### gRPC project resolution (CTF)

`IamEnforcementGrpcInterceptor` chooses the project for `getPolicy("projects/{id}")` without buffering the protobuf body:

1. `project=` or `project_id=` in `x-goog-request-params` (URL-decoded)
2. Project segment from `parent=` / `name=` / `topic=` / `subscription=` values in that header (common for Secret Manager and Pub/Sub clients)
3. `FLOCI_GCP_DEFAULT_PROJECT_ID` / `EmulatorConfig.defaultProjectId()` fallback

Empty project values are rejected (`PERMISSION_DENIED`). Prefer sending an accurate `x-goog-request-params` project (or resource name containing `projects/{id}`).

**Residual risk (body vs header mismatch):** The interceptor does not parse the request message. A client can put project `A` in `x-goog-request-params` while the body targets project `B`. Policy evaluation then uses `A`, and the service handler may still operate on `B`. For CTF deployments this is treated as **strict**: body project must match the header (or default) project used for IAM. Closing that gap would require best-effort protobuf inspection or a forwarding listener, which is intentionally out of scope for the current interceptor.

### Bearer token validation

When `FLOCI_GCP_AUTH_VALIDATE_TOKENS=true`, `TokenValidationFilter` requires `Authorization: Bearer <token>` on non-health paths. The token must match the configured operator root access token or a token registered in `TokenRegistry`. Invalid or missing credentials return unauthenticated errors. gRPC uses the matching token interceptor on the same flag.

### Operator root

Set both:

| Variable | Purpose |
|---|---|
| `FLOCI_GCP_AUTH_ROOT_SERVICE_ACCOUNT` | Operator principal email used when the root token matches |
| `FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN` | Bearer token that bypasses IAM enforcement when paired with a non-blank root service account |

Export them on the host before starting Compose. Never give the root pair to participants. The root access token is not registered in `TokenRegistry`. Identity comes from `OperatorRootAuth` only.

```bash
export FLOCI_GCP_AUTH_ROOT_SERVICE_ACCOUNT="operator@floci-local.iam.gserviceaccount.com"
export FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN="..."
docker compose up
```

### Stage 0 mapped permissions

`IamPermissionMapper` maps IAM service account REST routes to:

| HTTP | Path shape | Permission |
|---|---|---|
| `GET` | `/v1/projects/{project}/serviceAccounts` | `iam.serviceAccounts.list` |
| `GET` | `/v1/projects/{project}/serviceAccounts/{id}` | `iam.serviceAccounts.get` |
| `POST` | `/v1/projects/{project}/serviceAccounts` | `iam.serviceAccounts.create` |
| `GET` | `/v1/projects/{project}/serviceAccounts/{id}/keys` | `iam.serviceAccountKeys.list` |
| `POST` | `/v1/projects/{project}/serviceAccounts/{id}/keys` | `iam.serviceAccountKeys.create` |
| `DELETE` | `/v1/projects/{project}/serviceAccounts/{id}/keys/{keyId}` | `iam.serviceAccountKeys.delete` |
| `POST` | `/v1/projects/{project}/serviceAccounts/{id}:signBlob` | `iam.serviceAccounts.signBlob` |

Other IAM routes are unmapped. Under strict enforcement, unmapped calls are denied for non-operator principals.

Related CTF mappings (see service pages):

- GCS object `PATCH` -> `storage.objects.update` ([gcs.md](./gcs.md#ctf-fork))
- Cloud Run `roles/run.developer` does not include invoke. Callers need `roles/run.invoker` for `run.routes.invoke` ([cloud-run.md](./cloud-run.md#ctf-fork))

See also [environment variables (CTF hardening)](../configuration/environment-variables.md#ctf-hardening) and [README.md](../../README.md).
