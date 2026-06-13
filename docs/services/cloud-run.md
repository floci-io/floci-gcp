# Cloud Run

floci-gcp emulates the Cloud Run Admin API v2 control plane over REST JSON using Google's published protobuf types.

| Config | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_CLOUDRUN_ENABLED` | `true` | Enable/disable Cloud Run |
| `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_ENABLED` | `false` | Enable experimental image-based service execution |
| `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_RUNTIME` | `docker` | Runtime used for service execution. Only Docker is supported |
| `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_DEFAULT_PORT` | `8080` | Container port used when the service template omits a port |
| `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_STARTUP_TIMEOUT` | `240s` | Time to wait for the container TCP port to become reachable |
| `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_REQUEST_TIMEOUT` | `300s` | Default invocation proxy timeout |
| `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_CONTAINER_NAME_PREFIX` | `floci-cloudrun` | Prefix for Docker containers created for Cloud Run execution |

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

By default Cloud Run services are metadata only. Creating a service synthesizes the service URL, timestamps, etag, ready condition, traffic status, latest revision fields, and one read-only revision. No container image is pulled and no request-serving runtime is started.

Execution is experimental and opt-in with `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_ENABLED=true`. In execution mode, image-based service creation starts one Docker container for the created revision, injects `PORT`, `K_SERVICE`, `K_REVISION`, and `K_CONFIGURATION`, waits for the ingress TCP port, and returns a same-port invocation URL:

```text
http://localhost:4588/run/v2/projects/{project}/locations/{location}/services/{service}
```

While the create operation is pending, the stored service has `Ready=CONDITION_PENDING`, `reconciling=true`, and no `latestReadyRevision`. After the runtime port becomes reachable, the operation completes and the service is updated with `Ready=CONDITION_SUCCEEDED` and the ready revision name. If runtime startup fails, the operation fails and both the service and created revision are updated with `Ready=CONDITION_FAILED`, `reconciling=false`, and the startup error message.

PATCH accepts a Cloud Run v2 `Service` body and an optional `updateMask` query parameter. Template-changing updates create a new revision. With execution enabled, the previous ready revision remains invokable while the replacement container starts; after the replacement is ready, `latestReadyRevision` moves to the new revision and older runtime containers for that service are stopped.

The invocation proxy forwards HTTP methods, trailing paths, query strings, request bodies, safe headers, and `X-Forwarded-*` headers to the latest ready revision. Missing services return `404`, services without a ready runtime return `503`, runtime connection failures return `502`, and proxy timeouts return `504`.

`validateOnly=true` returns a successful completed operation without storing or deleting resources. Validate-only operations are not retained for later operation get/list calls.

## SDK Usage

Cloud Run clients should use the HTTP JSON transport, an explicit endpoint, and no credentials:

```java
ServicesSettings settings = ServicesSettings.newHttpJsonBuilder()
    .setEndpoint("http://localhost:4588")
    .setCredentialsProvider(NoCredentialsProvider.create())
    .build();
```

## Not Implemented

- Source builds and buildpacks
- Jobs
- WorkerPools
- Traffic splitting
- Autoscaling and scale-to-zero
- Sidecars, volumes, secrets, startup probes
- Cloud Functions execution
- IAM invocation enforcement
