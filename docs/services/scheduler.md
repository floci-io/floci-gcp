# Cloud Scheduler

floci-gcp emulates Google Cloud Scheduler over gRPC and REST using the real
`google.cloud.scheduler.v1.CloudScheduler` protocol. Create cron **jobs** that dispatch to
**Pub/Sub**, **HTTP**, or **App Engine** targets; a lightweight background tick fires due jobs and
`RunJob` triggers one immediately.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_SCHEDULER_ENABLED` | `true` | Enable/disable Cloud Scheduler |
| `FLOCI_GCP_SERVICES_SCHEDULER_INVOCATION_ENABLED` | `true` | When `false`, the background dispatcher never auto-fires jobs (`RunJob` still works) |
| `FLOCI_GCP_SERVICES_SCHEDULER_TICK_INTERVAL_SECONDS` | `10` | Interval between dispatcher ticks |

## Endpoint

Cloud Scheduler has **no `*_EMULATOR_HOST` convention**. Point the client at floci-gcp by
overriding the API endpoint / transport channel and disabling credentials:

- **gRPC** (Java/Python/Go/Node): build the v1 client with a plaintext channel to `localhost:4588`
  and anonymous/no credentials (see Quick Start).
- **REST**: `/v1/projects/{project}/locations/{location}/jobs` (+ `:pause`, `:resume`, `:run`).

## Scope

- Jobs: `CreateJob`, `GetJob`, `ListJobs`, `UpdateJob`, `DeleteJob`, `PauseJob`, `ResumeJob`, `RunJob`.
- Targets: `PubsubTarget` (publishes into the local Pub/Sub backend), `HttpTarget` (real outbound
  HTTP request), `AppEngineHttpTarget` (recorded — there is no App Engine backend to dispatch to).
- `schedule` is standard five-field unix-cron, interpreted in `time_zone` (default UTC).
- Output-only `state`, `schedule_time`, `last_attempt_time`, and `status` are populated.

## Quick Start

=== "Java"

    ```java
    CloudSchedulerClient client = CloudSchedulerClient.create(
        CloudSchedulerSettings.newBuilder()
            .setTransportChannelProvider(
                InstantiatingGrpcChannelProvider.newBuilder()
                    .setEndpoint("localhost:4588")
                    .setChannelConfigurator(b -> b.usePlaintext())
                    .build())
            .setCredentialsProvider(NoCredentialsProvider.create())
            .build());

    String parent = "projects/floci-local/locations/us-central1";
    Job job = client.createJob(parent, Job.newBuilder()
        .setName(parent + "/jobs/my-job")
        .setSchedule("*/5 * * * *")
        .setPubsubTarget(PubsubTarget.newBuilder()
            .setTopicName("projects/floci-local/topics/my-topic")
            .setData(ByteString.copyFromUtf8("tick"))
            .build())
        .build());

    // Fire immediately (publishes to the topic now):
    client.runJob(job.getName());
    ```

## Notes

- `RunJob` dispatches synchronously; the background dispatcher fires ENABLED jobs whose cron time
  has passed. `PauseJob` stops auto-firing; `ResumeJob` re-enables it.
- `PubsubTarget.topic_name` must reference an existing Pub/Sub topic in the same emulator.
- `RetryConfig`/`attempt_deadline` are stored and returned but retries are not yet simulated.

## CTF fork

When IAM enforcement is enabled (`floci-gcp.services.iam.enforcement-enabled`):

- REST Cloud Scheduler calls require a registered Bearer token and a matching project allow-policy binding.
- gRPC Cloud Scheduler (`google.cloud.scheduler.v1.CloudScheduler`) is gated by
  `IamEnforcementGrpcInterceptor` with the same `cloudscheduler.jobs.*` permissions via
  `IamGrpcPermissionMapper` (for example `ListJobs` → `cloudscheduler.jobs.list`,
  `ResumeJob` → `cloudscheduler.jobs.enable`, `RunJob` → `cloudscheduler.jobs.run`).
- `IamPermissionMapper` maps Scheduler REST paths to `cloudscheduler.jobs.*` permissions
  (create, get, list, update, delete, pause, enable for resume, run).
- `roles/cloudscheduler.viewer` grants get and list only.
- `roles/cloudscheduler.admin` grants the full mapped Cloud Scheduler job surface.
- Operator root (`FLOCI_GCP_AUTH_ROOT_SERVICE_ACCOUNT` / `FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN`)
  bypasses IAM evaluation.

Regression: `SchedulerIamEnforcementIntegrationTest`, `SchedulerGrpcIamEnforcementIntegrationTest`.
