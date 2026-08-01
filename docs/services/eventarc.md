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
| List providers | `GET /providers` — returns `storage.googleapis.com` and `pubsub.googleapis.com` |
| List channels | `GET /channels` — always empty (stub) |

Mutations return completed `google.longrunning.Operation` responses, readable via the shared operations surface at `/v2/projects/{project}/locations/{location}/operations`.

## Event Delivery

Matching triggers receive a CloudEvents **binary-mode** HTTP POST (`ce-id`, `ce-source`, `ce-specversion: 1.0`, `ce-type`, `ce-time` headers):

- **Pub/Sub** — event type `google.cloud.pubsub.topic.v1.messagePublished`, source `//pubsub.googleapis.com/{topic}`, body in push-delivery format (`message` with base64 `data`, `attributes`, `messageId`, `publishTime`).
- **Cloud Storage** — source `//storage.googleapis.com/projects/_/buckets/{bucket}`, body is the object metadata JSON.

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
                        .setService("hello-run").setRegion("us-central1"))))
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
- `match-path-pattern` operators — event filters use exact-value matching (with a last-segment fallback for `topic`/`bucket` attributes)
- Delivery retries and dead-lettering — delivery is fire-and-forget; failures are logged, not surfaced
- Channels and third-party providers (stubs)

A trigger with no `eventFilters` never matches. A trigger whose `transport.pubsub` topic matches the published topic receives the event even if its filters do not match.
