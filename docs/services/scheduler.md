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
  HTTP request), `AppEngineHttpTarget` (recorded: there is no App Engine backend to dispatch to).
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


## IAM enforcement

With `floci-gcp.services.iam.authorization-mode=enforce`, REST JSON and gRPC
requests made with emulator-issued service-account credentials evaluate the
project allow policy before reading, mutating, or dispatching a job. Disabled
mode preserves credential bypass. Anonymous and external credentials remain
bootstrap callers, consistent with the shared IAM framework.

| Method | Permission | Authorization target |
|--------|------------|----------------------|
| CreateJob | `cloudscheduler.jobs.create` | Location parent |
| ListJobs | `cloudscheduler.jobs.list` | Location parent |
| GetJob | `cloudscheduler.jobs.get` | Job |
| UpdateJob | `cloudscheduler.jobs.update` | Job |
| DeleteJob | `cloudscheduler.jobs.delete` | Job |
| PauseJob | `cloudscheduler.jobs.pause` | Job |
| ResumeJob | `cloudscheduler.jobs.enable` | Job |
| RunJob | `cloudscheduler.jobs.run` | Job |

Supported predefined-role slices: `roles/cloudscheduler.admin` grants all eight
permissions above, `roles/cloudscheduler.viewer` grants get/list, and
`roles/cloudscheduler.jobRunner` grants run. Basic owner/editor roles contribute
all eight and viewer contributes get/list. These slices cover implemented job
operations, not every permission in the upstream roles.

The local evaluator supports the shared `resource.name`, `resource.type`, and
`resource.service` conditions, including name equality and `startsWith`. Job
names are `projects/{project}/locations/{location}/jobs/{job}` with local type
`cloudscheduler.googleapis.com/Job`. Create/list evaluate the location parent
`projects/{project}/locations/{location}` with local type
`cloudscheduler.googleapis.com/Location`, not the prospective job name. A job
prefix condition therefore cannot grant create or list. A location grant permits
creating any job under that location; it does not enforce a job-name prefix.
Policies are inherited from `projects/{project}`. Job-level policy methods and
Scheduler `testIamPermissions` are not implemented. Calls to the shared IAM mixin
on Scheduler jobs fail closed for restricted credentials in enforce mode.

OAuth/OIDC HTTP target configuration and token delivery are not implemented.
Restricted callers configuring these targets in enforce mode receive
`FAILED_PRECONDITION` before mutation, rather than an act-as guarantee. Target
service authorization, background-dispatch identity, `fullView` redaction,
custom-role evaluation, and organization/folder policy inheritance are outside
this enforcement surface. REST updates authorize a nonempty body name when supplied,
before the controller validates agreement with the URL name. Omitted, null, and
empty names use the URL name. Conflicting names are rejected without mutation in
both modes. Explicit null OAuth/OIDC fields mean unset, matching protobuf JSON.
Domain-scoped project identifiers, including a colon, retain their full project
policy key and condition name.

The local condition attributes are emulator semantics. They do not establish
that production Cloud Scheduler supports the same condition attributes or
resource-name restrictions. No real-GCP IAM Conditions or Organization Policy
behavior is proven by these tests.
