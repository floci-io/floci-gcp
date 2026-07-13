# Cloud Run

floci-gcp emulates the Cloud Run Admin API v2 control plane over REST JSON using Google's published protobuf types.

| Config | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_CLOUDRUN_ENABLED` | `true` | Enable/disable Cloud Run |
| `FLOCI_GCP_SERVICES_CLOUDRUN_MOCK` | `false` | Mock mode — control plane only, no Docker-backed image-based service execution |
| `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_DEFAULT_PORT` | `8080` | Container port used when the service template omits a port |
| `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_STARTUP_TIMEOUT` | `240s` | Time to wait for the container TCP port to become reachable |
| `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_REQUEST_TIMEOUT` | `300s` | Default invocation proxy timeout |
| `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_OPERATION_TIMEOUT` | `300s` | Maximum time for asynchronous Cloud Run execution operations before their LRO fails |
| `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_CLEANUP_TIMEOUT` | `15s` | Maximum time to wait for best-effort Docker cleanup after an operation is already resolved |
| `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_CONTAINER_NAME_PREFIX` | `floci-cloudrun` | Prefix for Docker containers created for Cloud Run execution |
| `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_URL_HOST_SUFFIX` | `localhost.floci.io` or `FLOCI_GCP_HOSTNAME` | Host suffix used for generated Cloud Run execution URLs |

## Supported API Surface

| Operation | Path |
|---|---|
| Create service | `POST /v2/projects/{project}/locations/{location}/services` |
| List services | `GET /v2/projects/{project}/locations/{location}/services` |
| Get service | `GET /v2/projects/{project}/locations/{location}/services/{service}` |
| Update service | `PATCH /v2/projects/{project}/locations/{location}/services/{service}` |
| Delete service | `DELETE /v2/projects/{project}/locations/{location}/services/{service}` |
| Get IAM policy | `GET /v2/projects/{project}/locations/{location}/services/{service}:getIamPolicy` |
| Set IAM policy | `POST /v2/projects/{project}/locations/{location}/services/{service}:setIamPolicy` |
| Test IAM permissions | `POST /v2/projects/{project}/locations/{location}/services/{service}:testIamPermissions` |
| List revisions | `GET /v2/projects/{project}/locations/{location}/services/{service}/revisions` |
| Get revision | `GET /v2/projects/{project}/locations/{location}/services/{service}/revisions/{revision}` |

When execution is disabled, create, update, and delete return completed `google.longrunning.Operation` resources immediately. When execution is enabled, create, template-changing update, and delete return pending operations and complete or fail after runtime startup or cleanup. Operations can be read, listed, waited on, and deleted under `/v2/projects/{project}/locations/{location}/operations`.

## Behavior

In mock mode (`FLOCI_GCP_SERVICES_CLOUDRUN_MOCK=true`) Cloud Run services are metadata only. Creating a service synthesizes the service URL, timestamps, etag, ready condition, traffic status, latest revision fields, and one read-only revision. No container image is pulled and no request-serving runtime is started.

Image-based service execution runs by default (`FLOCI_GCP_SERVICES_CLOUDRUN_MOCK=false`); set `mock=true` to keep services metadata-only — no Docker containers and no execution-mode template validation. In execution mode, image-based service creation starts one Docker container for the created revision, injects `PORT`, `K_SERVICE`, `K_REVISION`, and `K_CONFIGURATION`, waits for the ingress TCP port, and returns a deterministic app-root invocation URL on the floci-gcp front door:

```text
http://{service}-{project-token}.{location}.run.localhost.floci.io:4588
```

The URL scheme and port come from `floci-gcp.effectiveBaseUrl()`. The project token is a deterministic SHA-256 based token derived from the project ID. `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_URL_HOST_SUFFIX` can override the host suffix; when it is unset, floci-gcp uses `FLOCI_GCP_HOSTNAME` if configured, otherwise `localhost.floci.io`. Direct Docker runtime container ports are internal implementation details and are not returned in `uri`, `urls[]`, or `trafficStatuses[].uri`.

Generated Cloud Run hosts must route back to the floci-gcp front door. When floci-gcp runs in Docker, the embedded DNS server resolves `*.localhost.floci.io` for other containers on the same Docker network. When running floci-gcp directly on the host with `./mvnw quarkus:dev`, local DNS does not automatically resolve those generated hosts; use the legacy `/run/v2/projects/{project}/locations/{location}/services/{service}` invocation path, configure local DNS, or send the request to `localhost:4588` with the generated `Host` header.

While the create operation is pending, the stored service has `Ready=CONDITION_PENDING`, `reconciling=true`, and no `latestReadyRevision`. After the runtime port becomes reachable, the operation completes and the service is updated with `Ready=CONDITION_SUCCEEDED` and the ready revision name. If runtime startup fails, the operation fails and both the service and created revision are updated with `Ready=CONDITION_FAILED`, `reconciling=false`, and the startup error message.

PATCH accepts a Cloud Run v2 `Service` body and an optional `updateMask` query parameter. Template-changing updates create a new revision. `allow_missing` upsert behavior and etag preconditions are not implemented. With execution enabled, the previous ready revision remains invokable while the replacement container starts; after the replacement is ready, `latestReadyRevision` moves to the new revision and older runtime containers for that service are stopped.

Execution mode supports Cloud Run GCS volumes declared with `template.volumes[].gcs` and mounted with container `volumeMounts`. The referenced bucket must already exist in the floci-gcp GCS emulator. At runtime startup, floci-gcp snapshots the bucket's current live objects into a Docker named volume and mounts that volume into the workload container with Docker `NoCopy` enabled. `readOnly=true` becomes a Docker read-only volume mount. `readOnly=false` allows writes into the mounted volume and syncs regular files back to emulator GCS when the runtime container is stopped during service delete or revision replacement. Writable cleanup mirrors the mounted volume back to the bucket prefix, so bucket objects that are no longer present in the mounted filesystem are deleted. Writes are not live-synced during request handling, so other GCS clients only observe them after runtime cleanup. Snapshot copy uses a short-lived `alpine:3.20` helper container, so Docker must be able to use that image when GCS volumes are mounted.

GCS volume `subPath` is supported as a path inside the materialized bucket root. GCS volume `mountOptions` are rejected. Secret, Cloud SQL, emptyDir, NFS, and other volume sources are still unsupported in execution mode.

Execution-backed create, template-changing update, and delete run on a bounded background executor. `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_OPERATION_TIMEOUT` caps these operations and fails the LRO with `DEADLINE_EXCEEDED` if Docker startup or metadata deletion does not complete in time. Docker API calls made by the shared container lifecycle manager are also capped by `FLOCI_GCP_DOCKER_API_TIMEOUT`; when that timeout is reached, floci-gcp resets its Docker client before later calls. Delete removes service and revision metadata and completes the LRO before stopping runtime containers, so slow Docker cleanup does not keep Terraform replacement destroys pending; container cleanup is best-effort after the resource is gone and capped by `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_CLEANUP_TIMEOUT`.

The invocation proxy accepts both generated host-routed URLs and the legacy prefixed path `/run/v2/projects/{project}/locations/{location}/services/{service}` for compatibility. Host-routed requests preserve the original app path and query string, so `GET $uri/api/database?x=1` reaches the container as `/api/database?x=1`. The proxy forwards HTTP methods, trailing paths, query strings, request bodies, safe headers, and `X-Forwarded-*` headers to the latest ready revision. Missing services return `404`, services without a ready runtime return `503`, runtime connection failures return `502`, and proxy timeouts return `504`.

`validateOnly=true` returns a successful completed operation without storing or deleting resources. Validate-only operations are not retained for later operation get/list calls.

## SDK Usage

Cloud Run clients should use the HTTP JSON transport, an explicit endpoint, and no credentials:

```java
ServicesSettings settings = ServicesSettings.newHttpJsonBuilder()
    .setEndpoint("http://localhost:4588")
    .setCredentialsProvider(NoCredentialsProvider.create())
    .build();
```

## CTF fork

### Container env stripping

Participant-supplied container `env` values are filtered before Docker launch. `ContainerEnvHardening` strips credential and operator bypass keys such as `GOOGLE_APPLICATION_CREDENTIALS`, `GOOGLE_API_KEY`, `GOOGLE_OAUTH_ACCESS_TOKEN`, `GOOGLE_CLOUD_PROJECT`, `GCLOUD_PROJECT`, `GCLOUD_ACCESS_TOKEN`, `CLOUDSDK_AUTH_*`, `CLOUDSDK_CORE_*`, `CLOUDSDK_API_ENDPOINT_OVERRIDES_*`, and `FLOCI_GCP_AUTH_*`. Safe user vars and Cloud Run system vars (`PORT`, `K_SERVICE`, `K_REVISION`, `K_CONFIGURATION`) are kept. See [environment variables (CTF hardening)](../configuration/environment-variables.md#ctf-hardening).

### IAM enforcement

When IAM enforcement is enabled (`floci-gcp.services.iam.enforcement-enabled`):

- REST Cloud Run Admin API calls require a registered Bearer token and a matching project allow-policy binding.
- `IamPermissionMapper` maps Cloud Run v2 REST paths to:
  - `run.services.*` - create, get, list, update, delete, getIamPolicy, setIamPolicy
  - `run.revisions.get` / `run.revisions.list`
  - `run.jobs.*` - create, get, list, update, delete, getIamPolicy, setIamPolicy, run (jobs API not implemented yet, paths are mapped for strict mode)
  - Legacy invoke path `/run/v2/projects/.../services/{service}` maps to `run.routes.invoke`
  - Host-routed invoke (`*.run.*` Host header, including `/` and `/api/...`) maps to `run.routes.invoke`
- Role grants used by the evaluator:
  - `roles/run.admin` - full mapped surface including setIamPolicy and invoke
  - `roles/run.developer` - manage services, jobs, and revisions, but not `setIamPolicy`, not `run.routes.invoke`, and not `run.jobs.run`
  - `roles/run.invoker` - `run.routes.invoke` and `run.jobs.run`
- Developers cannot invoke services. Bind `roles/run.invoker` (or admin) for host-routed or legacy invoke paths.
- Operator root (`FLOCI_GCP_AUTH_ROOT_SERVICE_ACCOUNT` / `FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN`) bypasses IAM evaluation.

Regression: `CloudRunIamEnforcementIntegrationTest`.

## Not Implemented

- Source builds and buildpacks
- Jobs
- WorkerPools
- Traffic splitting
- Autoscaling and scale-to-zero
- Sidecars, non-GCS volumes, secrets, startup probes
- Cloud Functions execution
