# Cloud Run

floci-gcp emulates the Cloud Run Admin API v2 control plane over REST JSON using Google's published protobuf types.

| Config | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_CLOUDRUN_ENABLED` | `true` | Enable/disable Cloud Run |
| `FLOCI_GCP_SERVICES_CLOUDRUN_MOCK` | `false` | Mock mode: control plane only, no Docker-backed image-based service execution |
| `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_DEFAULT_PORT` | `8080` | Container port used when the service template omits a port |
| `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_STARTUP_TIMEOUT` | `240s` | Time to wait for the container TCP port to become reachable |
| `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_REQUEST_TIMEOUT` | `300s` | Default invocation proxy timeout |
| `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_OPERATION_TIMEOUT` | `300s` | Maximum time for asynchronous Cloud Run execution operations before their LRO fails |
| `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_CLEANUP_TIMEOUT` | `15s` | Maximum time to wait for best-effort Docker cleanup after an operation is already resolved |
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

Image-based service execution runs by default (`FLOCI_GCP_SERVICES_CLOUDRUN_MOCK=false`); set `mock=true` to keep services metadata-only: no Docker containers and no execution-mode template validation. In execution mode, image-based service creation starts one Docker container for the created revision, injects `PORT`, `K_SERVICE`, `K_REVISION`, and `K_CONFIGURATION`, waits for the ingress TCP port, and returns a deterministic app-root invocation URL on the floci-gcp front door:

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

## Not Implemented

- Source builds and buildpacks
- WorkerPools
- Traffic splitting
- Autoscaling and scale-to-zero
- Sidecars, non-GCS volumes, secrets, startup probes
- Cloud Functions execution
- IAM invocation enforcement

## Jobs

Jobs run batch work to completion. floci-gcp implements the Cloud Run Admin API v2 `jobs` resource with its `executions` and `tasks` over REST JSON. gcloud is not supported: `gcloud run jobs` uses the v1 Knative API, which floci-gcp does not serve for Cloud Run.

### Supported API Surface

| Operation | Path |
|---|---|
| Create job | `POST /v2/projects/{project}/locations/{location}/jobs?jobId={id}` |
| List jobs | `GET /v2/projects/{project}/locations/{location}/jobs` |
| Get job | `GET /v2/projects/{project}/locations/{location}/jobs/{job}` |
| Update job | `PATCH /v2/projects/{project}/locations/{location}/jobs/{job}` |
| Delete job | `DELETE /v2/projects/{project}/locations/{location}/jobs/{job}` |
| Run job | `POST /v2/projects/{project}/locations/{location}/jobs/{job}:run` |
| Get IAM policy | `GET /v2/projects/{project}/locations/{location}/jobs/{job}:getIamPolicy` |
| Set IAM policy | `POST /v2/projects/{project}/locations/{location}/jobs/{job}:setIamPolicy` |
| Test IAM permissions | `POST /v2/projects/{project}/locations/{location}/jobs/{job}:testIamPermissions` |
| List executions | `GET /v2/projects/{project}/locations/{location}/jobs/{job}/executions` |
| Get execution | `GET /v2/projects/{project}/locations/{location}/jobs/{job}/executions/{execution}` |
| Delete execution | `DELETE /v2/projects/{project}/locations/{location}/jobs/{job}/executions/{execution}` |
| Cancel execution | `POST /v2/projects/{project}/locations/{location}/jobs/{job}/executions/{execution}:cancel` |
| List tasks | `GET /v2/projects/{project}/locations/{location}/jobs/{job}/executions/{execution}/tasks` |
| Get task | `GET /v2/projects/{project}/locations/{location}/jobs/{job}/executions/{execution}/tasks/{task}` |

`{job}` may be `-` when listing executions, and `{job}` and `{execution}` may each be `-` when listing tasks. Lists page with `pageSize` and `pageToken`. Jobs are listed by name, executions newest first, tasks by execution and index.

Create accepts `jobId` and `validateOnly`. Update replaces the job with the request body (there is no `updateMask`) and accepts `validateOnly` and `allowMissing`; a patch that changes nothing keeps the generation. Delete accepts `validateOnly` and `etag`. Run and cancel take `validateOnly`, `etag` and, for run, `overrides` in the request body. `validateOnly` returns a completed operation and stores nothing.

### Defaults and Naming

A created job gets `launchStage: GA`, `template.taskCount: 1`, `template.template.maxRetries: 3`, `template.template.timeout: 600s`, `template.template.executionEnvironment: EXECUTION_ENVIRONMENT_GEN2`, and container resource limits `cpu: 1000m` and `memory: 512Mi`. `client` and `clientVersion` are preserved. The job reports `generation`, `observedGeneration`, `executionCount`, `latestCreatedExecution` (name, create and completion time, completion status, delete time) and a `Ready` terminal condition with an empty `conditions` list. A duplicate `jobId` fails with `409 ALREADY_EXISTS` and `Resource '{id}' already exists.`, also with `validateOnly=true`. A missing job, execution or task fails with `404 NOT_FOUND` and `Resource '{id}' of kind 'JOB' in region '{location}' in project '{project}' does not exist.` (kind `EXECUTION` or `TASK` respectively).

Executions are named `{job}-{5 lowercase alphanumerics}` and tasks `{execution}-task{index}`. `parallelism` defaults to the task count; a larger value is accepted as given. `startExecutionToken` and `runExecutionToken` on create or update start an execution named `{job}-{token}` when the token differs from the stored one; the operation completes when the execution is created (start token) or finished (run token), and re-sending the same token starts nothing.

`jobs:run` overrides replace `taskCount` and `timeout` for that execution. A container override is matched by `name`; when no container has that name and the template has a single container the override applies to it, otherwise the request fails with `400 INVALID_ARGUMENT`. Override `args` replace the container args, `env` merges by name, and `clearArgs` removes the args. The effective template is stored on the execution.

### Execution Semantics

`jobs:run` returns a long-running operation whose metadata and response are the Execution. It is not bounded by `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_OPERATION_TIMEOUT`; a run is bounded by the task timeout plus the cleanup grace, over every retry and every wave of `parallelism`, plus the startup timeout for image preparation. The operation outcome follows GCP:

| Outcome | Operation |
|---|---|
| Every task succeeded | `response` is the Execution |
| A task exited non-zero on its last attempt | `error {code: 10, message: "Task {task} failed with exit code: {n} and message: The container exited with an error."}` |
| A task timed out on its last attempt | `error {code: 4, message: "Task {task} failed with exit code: 0 and message: The configured timeout was reached."}` |
| A task container disappeared on its last attempt without a stop request | `error {code: 13, message: "Task {task} failed with message: The task container stopped unexpectedly."}` |
| The execution was cancelled, deleted or its job deleted | `response` is the Execution, whose `Completed` condition is `CONDITION_FAILED` with `Cancelled by user.` |

Executions carry the `Started`, `Completed`, `ContainerReady` and `ResourcesAvailable` conditions with the GCP messages and measured durations, and `runningCount`, `succeededCount`, `failedCount`, `cancelledCount` and `retriedCount`. An execution is failed only after every task is terminal. Each task reports `retried` and `lastAttemptResult`: `status: {}` on success, `{code: 10, message: "The container exited with an error."}` with `exitCode` for a non-zero exit, `{code: 4, message: "The configured timeout was reached."}` for a timeout, `{code: 13, message: "The task container stopped unexpectedly."}` for a container that was removed or lost outside the emulator's control, and `{code: 1, message: "Cancelled by user."}` for a cancelled task. An unexpectedly stopped container is a failed attempt and is retried like a non-zero exit.

Cancelling a running execution stops its containers and never starts its pending tasks; the cancel operation completes with the Execution. Cancelling an execution that is not running fails with `400 FAILED_PRECONDITION` and `Execution '{id}' cannot be cancelled because it is not running.` Deleting a running execution cancels it first. A cancel or delete that arrives while the run bound is already stopping the execution does not replace that failure. Deleting a job cancels its running executions and removes the job, its executions and its tasks. Several executions of one job may run at the same time.

In mock mode (`FLOCI_GCP_SERVICES_CLOUDRUN_MOCK=true`) `jobs:run` creates the execution and its tasks already succeeded, with exit code 0 and timestamps set, and the operation is done with the Execution.

In execution mode each task attempt runs as a fresh Docker container. At most `parallelism` containers run at once and tasks start in index order. Non-zero exits and timeouts are retried up to `maxRetries` with `CLOUD_RUN_TASK_ATTEMPT` incremented. The per-attempt timeout sends SIGTERM and kills the container after `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_CLEANUP_TIMEOUT`. Container stdout and stderr are written to the emulator log at debug level (category `io.floci.gcp.services.cloudrun.CloudRunJobsRuntime`), and the container is removed once its exit code is captured. GCS volumes are materialized per attempt as for services, and writable volumes are written back before the attempt's outcome is recorded. Execution mode applies the same template constraints as services, with the same messages: one container, GCS volumes only, at most one port, an image, no env `valueSource`.

Each task container receives:

| Variable | Value |
|---|---|
| `CLOUD_RUN_JOB` | Job ID |
| `CLOUD_RUN_EXECUTION` | Execution ID |
| `CLOUD_RUN_TASK_INDEX` | Task index, from 0 |
| `CLOUD_RUN_TASK_ATTEMPT` | Attempt number, from 0 |
| `CLOUD_RUN_TASK_COUNT` | Task count of the execution |

`PORT` and the `K_*` variables are not set for job tasks.

Every change to an execution and its tasks is applied by a single per-execution coordinator, so task exits, cancel, execution delete, job delete and startup reconciliation cannot interleave. When the emulator stops, running task containers are removed. On the next start with persistent storage, executions and tasks that were still running are marked failed with `The emulator restarted before the execution completed.` (tasks: `The emulator restarted before the task completed.`), and their pending run operations fail with code 10. The run, delete and job operations of an execution that was deleted while its containers were still stopping complete with the cancelled Execution, as the delete path does. Job task containers left behind by an emulator process that did not stop cleanly (same `floci_emulator` and `floci_namespace` labels) are removed at startup.

### Deviations from GCP

- `etag` is accepted on delete, run and cancel but never validated, which matches the observed GCP behavior.
- Deletion is immediate. There is no soft delete or 30-day retention, and `showDeleted` is accepted and ignored; `deleteTime` and `expireTime` appear only on the delete operation's Execution or Job.
- `validateOnly` returns a completed operation that cannot be fetched later. GCP returns an unfinished operation name that also cannot be fetched.
- `serviceAccount` is not defaulted, as for services; GCP fills in the project's default compute service account.
- Container images are stored as given, and unnamed job containers stay unnamed; GCP resolves the execution image to a digest.
- Executions do not emit the GCP `Retry` condition or `logUri`.
- The `TASK` kind in the task `404` message was not observed on GCP; the job and execution messages were.
- A task container that stops without a stop request (removed outside the emulator) fails its attempt with code 13; GCP has no equivalent observable case.
- IAM policies use the same codec as services, so the initial `ACAB` etag is returned as the base64 of its UTF-8 bytes.
- gcloud is not supported.

