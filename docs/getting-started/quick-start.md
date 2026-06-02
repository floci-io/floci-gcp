# Quick Start

This guide gets floci-gcp running and verifies that GCP SDK and gcloud CLI commands work against it in under five minutes.

## Step 1 — Start floci-gcp

=== "Docker Compose"

    ```yaml title="docker-compose.yml"
    services:
      floci-gcp:
        image: floci/floci-gcp:latest
        ports:
          - "4588:4588"
        volumes:
          - ./data:/app/data
        environment:
          FLOCI_GCP_HOSTNAME: floci-gcp
          FLOCI_GCP_BASE_URL: http://floci-gcp:4588
    ```

    ```bash
    docker compose up -d
    ```

=== "Docker"

    ```bash
    docker run -d --name floci-gcp \
      -p 4588:4588 \
      floci/floci-gcp:latest
    ```

=== "Build from source"

    ```bash
    git clone https://github.com/floci-io/floci-gcp.git
    cd floci-gcp
    ./mvnw quarkus:dev   # hot reload, port 4588
    ```

## Step 2 — Configure GCP emulator environment variables

GCP SDKs automatically skip credential validation when these emulator variables are set:

```bash
export PUBSUB_EMULATOR_HOST=localhost:4588
export FIRESTORE_EMULATOR_HOST=localhost:4588
export DATASTORE_EMULATOR_HOST=localhost:4588
export STORAGE_EMULATOR_HOST=http://localhost:4588
export SECRET_MANAGER_EMULATOR_HOST=localhost:4588
```

Add these to your shell profile (`.bashrc` / `.zshrc`) to persist across sessions.

## Step 3 — Verify the Setup

Run a few quick smoke tests using the gcloud CLI:

```bash
gcloud config set project floci-local

# Pub/Sub — create a topic and publish a message
gcloud pubsub topics create my-topic
gcloud pubsub subscriptions create my-sub --topic=my-topic
gcloud pubsub topics publish my-topic --message="hello from floci-gcp"
gcloud pubsub subscriptions pull my-sub --auto-ack

# Cloud Storage — create a bucket and upload a file
gcloud storage buckets create gs://my-bucket
echo "hello floci-gcp" | gcloud storage cp - gs://my-bucket/hello.txt
gcloud storage ls gs://my-bucket
```

## Step 4 — Use in Your Application

Point your GCP SDK clients at floci-gcp:

=== "Java"

    ```java
    // Pub/Sub
    ManagedChannel channel = ManagedChannelBuilder
        .forTarget("localhost:4588")
        .usePlaintext()
        .build();

    TopicAdminClient topicAdminClient = TopicAdminClient.create(
        TopicAdminSettings.newBuilder()
            .setTransportChannelProvider(
                FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel)))
            .setCredentialsProvider(NoCredentialsProvider.create())
            .build());

    topicAdminClient.createTopic(TopicName.of("floci-local", "my-topic"));
    ```

    ```java
    // Cloud Storage
    Storage storage = StorageOptions.newBuilder()
        .setHost("http://localhost:4588")
        .setProjectId("floci-local")
        .setCredentials(NoCredentials.getInstance())
        .build()
        .getService();

    storage.create(BucketInfo.of("my-bucket"));
    storage.create(BlobInfo.newBuilder("my-bucket", "hello.txt").build(),
        "hello from floci-gcp".getBytes());
    ```

    ```java
    // Firestore
    FirestoreOptions options = FirestoreOptions.newBuilder()
        .setHost("localhost:4588")
        .setProjectId("floci-local")
        .setCredentials(NoCredentials.getInstance())
        .build();

    Firestore db = options.getService();
    db.collection("users").add(Map.of("name", "Alice"));
    ```

=== "Python"

    ```python
    import os
    os.environ["PUBSUB_EMULATOR_HOST"] = "localhost:4588"

    from google.cloud import pubsub_v1

    publisher = pubsub_v1.PublisherClient()
    topic_path = publisher.topic_path("floci-local", "my-topic")
    publisher.create_topic(request={"name": topic_path})

    future = publisher.publish(topic_path, b"hello from floci-gcp")
    future.result()
    ```

    ```python
    import os
    os.environ["FIRESTORE_EMULATOR_HOST"] = "localhost:4588"

    from google.cloud import firestore

    db = firestore.Client(project="floci-local")
    db.collection("users").add({"name": "Alice", "age": 30})
    ```

=== "Node.js"

    ```javascript
    import { PubSub } from "@google-cloud/pubsub";

    process.env.PUBSUB_EMULATOR_HOST = "localhost:4588";

    const pubsub = new PubSub({ projectId: "floci-local" });
    await pubsub.createTopic("my-topic");
    ```

=== "gcloud CLI"

    ```bash
    export PUBSUB_EMULATOR_HOST=localhost:4588

    gcloud config set project floci-local
    gcloud pubsub topics create my-topic
    gcloud pubsub subscriptions create my-sub --topic=my-topic
    gcloud pubsub topics publish my-topic --message="hello from floci-gcp"
    gcloud pubsub subscriptions pull my-sub --auto-ack
    ```

## Next Steps

- [Environment variables reference](../configuration/environment-variables.md)
- [Docker Compose multi-container setup](../configuration/docker-compose.md)
- [Multi-project isolation](../configuration/multi-project.md)
- [Browse per-service documentation](../services/index.md)
