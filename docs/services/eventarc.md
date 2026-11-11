# Eventarc

floci-gcp emulates the [Eventarc](https://cloud.google.com/eventarc) API (`eventarc.googleapis.com` v1) with REST JSON: trigger CRUD plus in-process event delivery. When a Pub/Sub message is published or a GCS object event fires inside the emulator, matching triggers receive a CloudEvents HTTP request at their destination.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_EVENTARC_ENABLED` | `true` | Enable or disable the Eventarc service |

## Endpoint

REST JSON under `/v1/projects/{project}/locations/{location}`:

| Operation | Method and path |
|---|---|
| Create trigger | `POST /triggers?triggerId=...` (supports `validateOnly`) |
| List triggers | `GET /triggers` (with `pageSize`/`pageToken`) |
| Get trigger | `GET /triggers/{id}` |
| Update trigger | `PATCH /triggers/{id}` (supports `updateMask`, `allowMissing`, `validateOnly`) |
| Delete trigger | `DELETE /triggers/{id}` (supports `allowMissing`, `validateOnly`) |
| List providers | `GET /providers`: returns `storage.googleapis.com` and `pubsub.googleapis.com` |
| List channels | `GET /channels`: always empty (stub) |
| List operations | `GET /operations` (with `pageSize`/`pageToken`) |
| Get operation | `GET /operations/{id}` |
| Delete operation | `DELETE /operations/{id}` |
| Cancel operation | `POST /operations/{id}:cancel` |

Mutations return completed `google.longrunning.Operation` responses, which the operations routes above serve back. They list and return only Eventarc's own operations, not the Cloud Run or Cloud Functions operations floci keeps in the same store. Every Eventarc operation is created done, so cancel succeeds without changing it. Managed Kafka serves its operations on the same `/v1` path. A list never mixes the two APIs: it is Eventarc's unless the host is `managedkafka.googleapis.com`, so the Eventarc SDK, which cannot unpack Managed Kafka metadata, lists through floci's own endpoint as before.

## Event Delivery

Matching triggers receive a CloudEvents **binary-mode** HTTP POST (`ce-id`, `ce-source`, `ce-specversion: 1.0`, `ce-type`, `ce-time` headers):

- **Pub/Sub**: event type `google.cloud.pubsub.topic.v1.messagePublished`, source `//pubsub.googleapis.com/{topic}`, body in push-delivery format (`message` with base64 `data`, `attributes`, `messageId`, `publishTime`).
- **Cloud Storage**: source `//storage.googleapis.com/projects/_/buckets/{bucket}`, body is the object metadata JSON.

Supported destinations: **Cloud Run services** (resolved through the emulator's Cloud Run URL routing, honoring `path`) and **HTTP endpoints** (`httpEndpoint.uri`).

## Quick Start

=== "Java"

    ```java
    EventarcClient client = EventarcClient.create(
        EventarcSettings.newHttpJsonBuilder()
            .setEndpoint("http://localhost:4588")
            .setCredentialsProvider(NoCredentialsProvider.create())
            .build());

    client.createTriggerAsync(CreateTriggerRequest.newBuilder()
            .setParent("projects/my-project/locations/us-central1")
            .setTriggerId("pubsub-trigger")
            .setTrigger(Trigger.newBuilder()
                .addEventFilters(EventFilter.newBuilder()
                    .setAttribute("type")
                    .setValue("google.cloud.pubsub.topic.v1.messagePublished"))
                .setDestination(Destination.newBuilder()
                    .setCloudRun(CloudRun.newBuilder()
                        .setService("hello-run").setRegion("us-central1")))
                .setTransport(Transport.newBuilder()
                    .setPubsub(Pubsub.newBuilder()
                        .setTopic("projects/my-project/topics/my-topic"))))
            .build())
        .get();
    ```

=== "REST"

    ```bash
    curl -X POST \
      'http://localhost:4588/v1/projects/my-project/locations/us-central1/triggers?triggerId=gcs-trigger' \
      -H 'Content-Type: application/json' \
      -d '{
        "eventFilters": [
          {"attribute": "type", "value": "google.cloud.storage.object.v1.finalized"},
          {"attribute": "bucket", "value": "my-bucket"}
        ],
        "destination": {"cloudRun": {"service": "hello-run", "region": "us-central1"}}
      }'
    ```

## Not Yet Supported

- GKE, Workflows, and Cloud Functions destinations (logged and dropped)
- `match-path-pattern` operators: event filters use exact-value matching (a bare `topic` value resolves to `projects/{trigger project}/topics/{name}`; `bucket` falls back to last-segment matching)
- Delivery retries and dead-lettering: delivery is fire-and-forget; failures are logged, not surfaced
- Channels and third-party providers (stubs)
- `operations.list` ignores `filter` and `returnPartialSuccess`, and the `-` location wildcard lists nothing

A trigger with no `eventFilters` never matches. A Pub/Sub trigger fires only for the topic named in `transport.pubsub.topic` (a bare name resolves against the trigger's project), even if its filters do not match. The topic must be in the same project as the trigger; a transport topic in another project never fires. Without a transport topic, it fires only when an explicit `topic` event filter matches the published topic in the same project. floci does not provision an Eventarc-managed topic, so a `messagePublished` trigger with neither never fires.
