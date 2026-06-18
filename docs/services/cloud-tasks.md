# Cloud Tasks

floci-gcp emulates Google Cloud Tasks over gRPC using the real
`google.cloud.tasks.v2.CloudTasks` protocol. It implements the control plane for queues and tasks —
creating queues, enqueuing tasks, listing, pausing/resuming, and purging — with state tracked in the
configured storage backend.

!!! note "Control plane only"
    floci-gcp **stores and tracks** queues and tasks but does **not dispatch** them. `RunTask`
    increments the task's `dispatchCount` and returns the task; it does not deliver the HTTP or
    App Engine request to its target. Use this to validate enqueue/management flows, not real task
    execution.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_CLOUDTASKS_ENABLED` | `true` | Enable/disable Cloud Tasks |

## Endpoint

Cloud Tasks has **no `*_EMULATOR_HOST` convention**. Point the client at floci-gcp by overriding the
transport channel to `localhost:4588` over plaintext and disabling credentials (see Quick Start).

## Quick Start

=== "Java"

    ```java
    CloudTasksClient client = CloudTasksClient.create(
        CloudTasksSettings.newBuilder()
            .setTransportChannelProvider(
                InstantiatingGrpcChannelProvider.newBuilder()
                    .setEndpoint("localhost:4588")
                    .setChannelConfigurator(b -> b.usePlaintext())
                    .build())
            .setCredentialsProvider(NoCredentialsProvider.create())
            .build());

    LocationName parent = LocationName.of("floci-local", "us-central1");
    Queue queue = client.createQueue(parent, Queue.newBuilder()
        .setName(QueueName.of("floci-local", "us-central1", "my-queue").toString())
        .build());

    Task task = client.createTask(queue.getName(), Task.newBuilder()
        .setHttpRequest(HttpRequest.newBuilder()
            .setUrl("https://example.com/handler")
            .setHttpMethod(HttpMethod.POST)
            .setBody(ByteString.copyFromUtf8("payload"))
            .build())
        .build());

    client.runTask(task.getName());
    ```

=== "Python"

    ```python
    import grpc
    from google.cloud import tasks_v2
    from google.cloud.tasks_v2.services.cloud_tasks.transports.grpc import (
        CloudTasksGrpcTransport,
    )

    transport = CloudTasksGrpcTransport(channel=grpc.insecure_channel("localhost:4588"))
    client = tasks_v2.CloudTasksClient(transport=transport)

    parent = "projects/floci-local/locations/us-central1"
    queue = client.create_queue(request={
        "parent": parent,
        "queue": {"name": f"{parent}/queues/my-queue"},
    })

    task = client.create_task(request={
        "parent": queue.name,
        "task": {
            "http_request": {
                "url": "https://example.com/handler",
                "http_method": tasks_v2.HttpMethod.POST,
                "body": b"payload",
            }
        },
    })

    client.run_task(request={"name": task.name})
    ```

=== "Node.js"

    ```javascript
    import { CloudTasksClient } from "@google-cloud/tasks";
    import * as grpc from "@grpc/grpc-js";

    const client = new CloudTasksClient({
        servicePath: "localhost",
        port: 4588,
        sslCreds: grpc.credentials.createInsecure(),
    });

    const parent = "projects/floci-local/locations/us-central1";
    const [queue] = await client.createQueue({
        parent,
        queue: { name: `${parent}/queues/my-queue` },
    });

    const [task] = await client.createTask({
        parent: queue.name,
        task: {
            httpRequest: {
                url: "https://example.com/handler",
                httpMethod: "POST",
                body: Buffer.from("payload"),
            },
        },
    });

    await client.runTask({ name: task.name });
    ```

## Queues

`CreateQueue` stores a queue under `projects/{project}/locations/{location}/queues/{queue}`. The
following fields are honored:

- **Rate limits** — `max_dispatches_per_second`, `max_concurrent_dispatches`
- **Retry config** — `max_attempts`
- **State** — `PauseQueue` / `ResumeQueue` toggle the queue state; `PurgeQueue` clears its tasks

`UpdateQueue` applies the same rate-limit and retry fields.

## Tasks

`CreateTask` accepts both task target shapes:

- **HTTP target** (`http_request`) — `url`, `http_method`, `headers`, `body`
- **App Engine target** (`app_engine_http_request`) — `relative_uri`, `http_method`, `headers`, `body`

`schedule_time` is stored when provided. Tasks with neither target default to an HTTP-typed task.

## Supported Operations

- `ListQueues`, `GetQueue`, `CreateQueue`, `UpdateQueue`, `DeleteQueue`
- `PurgeQueue`, `PauseQueue`, `ResumeQueue`
- `ListTasks`, `GetTask`, `CreateTask`, `DeleteTask`, `RunTask`
- `GetIamPolicy`, `SetIamPolicy`, `TestIamPermissions` (accepted but not persisted/enforced — see below)

## Not Yet Supported

- **Actual task dispatch** — `RunTask` and queue processing do not deliver requests to HTTP or
  App Engine targets; tasks are tracked, not executed.
- **IAM enforcement** — `GetIamPolicy` returns an empty policy, `SetIamPolicy` echoes the request,
  and `TestIamPermissions` echoes the requested permissions; nothing is stored or enforced.
- Automatic retry/backoff scheduling, `BufferTask`, and queue-level routing overrides.
