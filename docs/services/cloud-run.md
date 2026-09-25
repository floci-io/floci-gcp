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
| `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_MAX_WORKER_INSTANCES` | `1` | Maximum replica containers run per worker pool (see [Worker Pools](#worker-pools)) |

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
| Delete revision | `DELETE /v2/projects/{project}/locations/{location}/services/{service}/revisions/{revision}` |

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

Deleting a service revision that is the service's `latestReadyRevision` or is named in its `trafficStatuses` fails with `400 FAILED_PRECONDITION` and `Revision "{revision}" cannot be directly deleted because it is actively serving.`. Other revisions are removed and the completed operation returns the revision with `deleteTime` and `expireTime` set. Runtime containers are not touched by revision delete; retired service revisions have no running container.

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

Executions are named `{job}-{5 lowercase alphanumerics}` and tasks `{execution}-task{index}`. `parallelism` defaults to the task count; a larger value is accepted as given. `startExecutionToken` and `runExecutionToken` on create or update start an execution named `{job}-{token}` when the token differs from the stored one; the operation completes when the execution is created (start token) or finished (run token), and re-sending the same token starts nothing. A token is rejected with `400 INVALID_ARGUMENT` when the job ID and the token together are 63 characters or more, or when `{job}-{token}` is not lowercase letters, digits and hyphens starting and ending with a letter or digit.

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

Cancelling a running execution stops its containers and never starts its pending tasks; the cancel operation completes with the Execution. Cancelling an execution that is not running fails with `400 FAILED_PRECONDITION` and `Execution '{id}' cannot be cancelled because it is not running.` Deleting a running execution cancels it first. A cancel or delete that arrives while the run bound is already stopping the execution does not replace that failure. Deleting a job cancels its running executions and removes the job, its executions and its tasks. Until that cleanup finishes, creating or upserting a job with the same name fails with `409 ABORTED` and `Job '{id}' is being deleted.` Several executions of one job may run at the same time.

In mock mode (`FLOCI_GCP_SERVICES_CLOUDRUN_MOCK=true`) `jobs:run` creates the execution and its tasks already succeeded, with exit code 0 and timestamps set, and the operation is done with the Execution.

In execution mode each task attempt runs as a fresh Docker container. At most `parallelism` containers run at once and tasks start in index order. Non-zero exits and timeouts are retried up to `maxRetries` with `CLOUD_RUN_TASK_ATTEMPT` incremented. The per-attempt timeout sends SIGTERM and kills the container after `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_CLEANUP_TIMEOUT`. Container stdout and stderr are written to the emulator log at debug level (category `io.floci.gcp.services.cloudrun.CloudRunJobsRuntime`), and the container is removed once its exit code is captured. GCS volumes are materialized per attempt as for services, and writable volumes are written back before the attempt's outcome is recorded. Unlike services, the write-back merges instead of mirroring, so parallel tasks writing the same bucket keep each other's outputs: a file is uploaded only when it is new or its content changed since the attempt's snapshot, and an object is deleted only when the attempt removed it and its generation is unchanged since the snapshot. When two attempts change the same object, the last write-back wins. If the write-back of an attempt that exited 0 fails, the attempt is a failed attempt and is retried, then fails with code 13 and `The task's GCS volume could not be written back: {detail}.`; the materialized volumes are removed either way. Execution mode applies the same template constraints as services, with the same messages: one container, GCS volumes only, at most one port, an image, no env `valueSource`.

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
- The execution token character rule is inferred from the execution naming; only the length limit is documented in `job.proto`.
- Recreating a job while its executions are still being cleaned up fails with `409 ABORTED`; the GCP behavior was not observed.
- A task container that stops without a stop request (removed outside the emulator) fails its attempt with code 13; GCP has no equivalent observable case.
- IAM policies use the same codec as services, so the initial `ACAB` etag is returned as the base64 of its UTF-8 bytes.
- gcloud is not supported.

## Worker Pools

Worker pools run pull-based workloads (queue consumers, Kafka or Pub/Sub pullers) that serve no HTTP traffic. floci-gcp implements the Cloud Run Admin API v2 `workerPools` resource and its revisions over REST JSON. gcloud is not supported: `gcloud run worker-pools` reads through the v1 Knative API and writes through gRPC, neither of which floci-gcp serves for Cloud Run.

### Supported API Surface

| Operation | Path |
|---|---|
| Create worker pool | `POST /v2/projects/{project}/locations/{location}/workerPools?workerPoolId={id}` |
| List worker pools | `GET /v2/projects/{project}/locations/{location}/workerPools` |
| Get worker pool | `GET /v2/projects/{project}/locations/{location}/workerPools/{pool}` |
| Update worker pool | `PATCH /v2/projects/{project}/locations/{location}/workerPools/{pool}` |
| Delete worker pool | `DELETE /v2/projects/{project}/locations/{location}/workerPools/{pool}` |
| Get IAM policy | `GET /v2/projects/{project}/locations/{location}/workerPools/{pool}:getIamPolicy` |
| Set IAM policy | `POST /v2/projects/{project}/locations/{location}/workerPools/{pool}:setIamPolicy` |
| Test IAM permissions | `POST /v2/projects/{project}/locations/{location}/workerPools/{pool}:testIamPermissions` |
| List revisions | `GET /v2/projects/{project}/locations/{location}/workerPools/{pool}/revisions` |
| Get revision | `GET /v2/projects/{project}/locations/{location}/workerPools/{pool}/revisions/{revision}` |
| Delete revision | `DELETE /v2/projects/{project}/locations/{location}/workerPools/{pool}/revisions/{revision}` |

Create accepts `workerPoolId` and `validateOnly`. Update accepts `updateMask`, `validateOnly`, `allowMissing` (creates the pool when it does not exist) and `forceNewRevision`; without `updateMask` every updatable field is replaced from the body, which is what the Terraform provider sends. Delete accepts `validateOnly` and `etag`. List accepts `pageSize` and `pageToken`; `showDeleted` is accepted and ignored. Creating a pool whose ID already exists fails with `409 ALREADY_EXISTS` and `Resource '{id}' already exists.`.

### Resource Behavior

- Defaults: `scaling.manualInstanceCount` is `1` when unset, every template container gets `resources.limits` `cpu: 1000m` and `memory: 512Mi` when those keys are missing, `launchStage` is `GA`, and an empty `instanceSplits` becomes one `INSTANCE_SPLIT_ALLOCATION_TYPE_LATEST` split at 100%. `template.serviceAccount` is stored as given, as for services. `customAudiences` is dropped, because GCP ignores it.
- Revisions are named `{pool}-{5-digit counter}-{3 random lowercase alphanumerics}`, for example `orders-00002-jw2`. A revision is created on create, whenever the template changes (compared after defaults are applied), and whenever `forceNewRevision=true`. Any other update, such as scaling, labels or splits, only increments `generation`.
- A revision copies the template: unnamed containers are named `{last image path segment}-{1-based index}` (`busybox-1`), `executionEnvironment` is `EXECUTION_ENVIRONMENT_GEN2`, and the `service` field is not set. Images are stored as given; they are not resolved to digests.
- `instanceSplitStatuses` mirrors `instanceSplits` and always names a concrete revision: a `LATEST` split names `latestCreatedRevision`. `REVISION` splits must reference an existing revision of the same pool and the percentages must add up to 100, otherwise the update fails with `400 INVALID_ARGUMENT`.
- The pool carries `latestCreatedRevision`, `latestReadyRevision`, `terminalCondition {type: Ready}` with no top-level `conditions`, `generation`, `observedGeneration` and an `etag`.
- A revision named in `instanceSplitStatuses` is serving. Serving revisions carry `Ready`, `Active`, `ResourcesAvailable`, `ContainerReady` and `MinInstancesProvisioned` conditions with the GCP messages (for example `Deploying revision succeeded in 0.42s.`) and `scalingStatus.desiredMinInstanceCount` equal to the pool's `manualInstanceCount`. Other revisions are retired: `Active` is `CONDITION_FAILED` with `Revision retired.` and `revisionReason: RETIRED`, and `scalingStatus` is cleared.
- Deleting a serving revision fails with `400 FAILED_PRECONDITION` and `Revision "{revision}" cannot be directly deleted because it is actively serving.`. Deleting any other revision returns a completed operation whose response is the revision with `deleteTime` and `expireTime` set.
- Revisions are listed newest first. Listing the revisions of a pool that does not exist returns an empty response, as GCP does after a pool is deleted.
- IAM get, set and test delegate to the shared IAM service exactly as for services.
- Deleting a pool removes the pool, all of its revisions and its IAM policy immediately. The operation response carries `deleteTime` and `expireTime`.

In mock mode (`FLOCI_GCP_SERVICES_CLOUDRUN_MOCK=true`) worker pools are metadata only: every operation is returned completed, the pool is ready at once and no container is started.

### Execution

With execution enabled (`FLOCI_GCP_SERVICES_CLOUDRUN_MOCK=false`, the default) floci-gcp runs worker pool replicas as Docker containers.

- Only one revision runs: the one with the largest percent in `instanceSplitStatuses` (the first on ties). The split between several revisions is metadata only.
- The serving revision runs `scaling.manualInstanceCount` replicas, capped by `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_MAX_WORKER_INSTANCES` (default `1`). A larger request is clamped with a warning in the emulator log; the pool and revision metadata still report the requested count. `manualInstanceCount: 0` stops every replica.
- Each replica receives the container's own `env` plus `CLOUD_RUN_WORKER_POOL` (the pool ID) and `CLOUD_RUN_REVISION` (the revision ID), which win on conflict. No `PORT` or `K_*` variables are injected and no port is published.
- Create, update and delete return pending operations. While the containers converge, the pool has `reconciling: true` and `terminalCondition` `CONDITION_RECONCILING` with `Deploying Revision.` (new revision) or `Provisioning revision instances to process workloads.` (scaling, splits or other fields). The operation completes once the containers match the latest committed state. If a container cannot be started the operation fails with code 13, the pool's `terminalCondition` becomes `CONDITION_FAILED` with the Docker error, and the previously running containers are left in place. Operations fail with `DEADLINE_EXCEEDED` after `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_OPERATION_TIMEOUT`.
- When the serving revision changes, the new revision's replicas are started before the old revision's replicas are stopped.
- Replicas are stopped with SIGTERM and killed after `FLOCI_GCP_SERVICES_CLOUDRUN_EXECUTION_CLEANUP_TIMEOUT` (kept below `FLOCI_GCP_DOCKER_API_TIMEOUT`). Deleting a pool stops all of its replicas before the delete operation completes.
- The template must satisfy the same execution-mode constraints as services: exactly one container, GCS volumes only (no `mountOptions`), at most one container port, an image, and no env `valueSource`. Each replica gets its own snapshot of a GCS volume; writable volumes are written back to the bucket when that replica stops, so with several replicas the last one stopped wins.
- A replica container that exits is replaced on the next update of the pool, not automatically. Replica containers are removed when the emulator shuts down, and with persistent storage a restored pool starts its replicas again on its next update.

Terraform and OpenTofu can manage `google_cloud_run_v2_worker_pool` with the `cloud_run_v2_custom_endpoint` shown for services; set `deletion_protection = false` so the resource can be destroyed.

### Deviations from GCP

- Only the revision with the largest split percent runs containers; the split is metadata only.
- The worker pool revision name format (`{pool}-00001-abc`) differs from the one floci-gcp uses for services (`{service}-00001`, no random suffix).
- `etag` is a random UUID and is never validated on update or delete.
- There is no soft delete: `showDeleted` is ignored and `deleteTime` and `expireTime` appear only on the delete operation response.
- While reconciling, GCP keeps reporting the previous `instanceSplitStatuses`; floci-gcp reports the new statuses at once.
- `template.revision` (a caller-chosen revision name) is ignored. Revisions have no `logUri` or `creator`, and GCP's transient `Retry` and `InstanceShutDown` conditions are not emitted.
- Scaling fields newer than the Cloud Run v2 protos floci-gcp is built on (`scalingMode`, `minInstanceCount`, `maxInstanceCount`) are dropped.
- The `400 INVALID_ARGUMENT` messages for invalid `instanceSplits` are floci-gcp's own.
- gcloud is not supported.

### SDK Usage

```java
WorkerPoolsSettings settings = WorkerPoolsSettings.newHttpJsonBuilder()
    .setEndpoint("http://localhost:4588")
    .setCredentialsProvider(NoCredentialsProvider.create())
    .build();
```

`RevisionsClient` lists and deletes worker pool revisions with a `projects/{project}/locations/{location}/workerPools/{pool}` parent.
