# Pub/Sub

floci-gcp emulates Google Cloud Pub/Sub over gRPC using the real `google.pubsub.v1` protocol.
It also exposes the Pub/Sub REST v1 JSON surface used by tools such as Terraform.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_PUBSUB_ENABLED` | `true` | Enable/disable Pub/Sub |

## Emulator Variable

```bash
export PUBSUB_EMULATOR_HOST=localhost:4588
```

GCP Pub/Sub SDK clients use this variable to route requests to floci-gcp instead of `pubsub.googleapis.com`.

## REST / Terraform Endpoint

Terraform's Google provider can use the REST endpoint with:

```hcl
provider "google" {
  pubsub_custom_endpoint = "http://localhost:4588/v1/"
}
```

The REST surface supports topic and subscription create/read/list/update/delete, publish, pull, and acknowledge:

- `PUT /v1/projects/{project}/topics/{topic}`
- `GET /v1/projects/{project}/topics/{topic}`
- `GET /v1/projects/{project}/topics`
- `PATCH /v1/projects/{project}/topics/{topic}?updateMask=...`
- `DELETE /v1/projects/{project}/topics/{topic}`
- `POST /v1/projects/{project}/topics/{topic}:publish`
- `PUT /v1/projects/{project}/subscriptions/{subscription}`
- `GET /v1/projects/{project}/subscriptions/{subscription}`
- `GET /v1/projects/{project}/subscriptions`
- `PATCH /v1/projects/{project}/subscriptions/{subscription}?updateMask=...`
- `DELETE /v1/projects/{project}/subscriptions/{subscription}`
- `POST /v1/projects/{project}/subscriptions/{subscription}:pull`
- `POST /v1/projects/{project}/subscriptions/{subscription}:acknowledge`

## Quick Start

=== "gcloud CLI"

    ```bash
    export PUBSUB_EMULATOR_HOST=localhost:4588
    gcloud config set project floci-local

    # Create topic and subscription
    gcloud pubsub topics create my-topic
    gcloud pubsub subscriptions create my-sub --topic=my-topic

    # Publish a message
    gcloud pubsub topics publish my-topic --message="hello from floci-gcp"

    # Pull messages
    gcloud pubsub subscriptions pull my-sub --auto-ack --limit=10
    ```

=== "Java"

    ```java
    ManagedChannel channel = ManagedChannelBuilder
        .forTarget("localhost:4588")
        .usePlaintext()
        .build();

    TransportChannelProvider channelProvider =
        FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel));
    CredentialsProvider credentialsProvider = NoCredentialsProvider.create();

    // Create topic
    TopicAdminClient topicAdminClient = TopicAdminClient.create(
        TopicAdminSettings.newBuilder()
            .setTransportChannelProvider(channelProvider)
            .setCredentialsProvider(credentialsProvider)
            .build());

    topicAdminClient.createTopic(TopicName.of("floci-local", "my-topic"));

    // Create subscription
    SubscriptionAdminClient subscriptionAdminClient = SubscriptionAdminClient.create(
        SubscriptionAdminSettings.newBuilder()
            .setTransportChannelProvider(channelProvider)
            .setCredentialsProvider(credentialsProvider)
            .build());

    subscriptionAdminClient.createSubscription(
        SubscriptionName.of("floci-local", "my-sub"),
        TopicName.of("floci-local", "my-topic"),
        PushConfig.getDefaultInstance(),
        10);

    // Publish
    Publisher publisher = Publisher.newBuilder(TopicName.of("floci-local", "my-topic"))
        .setChannelProvider(channelProvider)
        .setCredentialsProvider(credentialsProvider)
        .build();

    PubsubMessage message = PubsubMessage.newBuilder()
        .setData(ByteString.copyFromUtf8("hello from floci-gcp"))
        .build();

    publisher.publish(message).get();

    // Pull
    SubscriberStubSettings subscriberSettings = SubscriberStubSettings.newBuilder()
        .setTransportChannelProvider(channelProvider)
        .setCredentialsProvider(credentialsProvider)
        .build();

    try (SubscriberStub subscriber = GrpcSubscriberStub.create(subscriberSettings)) {
        PullRequest pullRequest = PullRequest.newBuilder()
            .setMaxMessages(10)
            .setSubscription(SubscriptionName.of("floci-local", "my-sub").toString())
            .build();

        PullResponse response = subscriber.pullCallable().call(pullRequest);
        response.getReceivedMessagesList().forEach(msg ->
            System.out.println(msg.getMessage().getData().toStringUtf8()));
    }
    ```

=== "Python"

    ```python
    import os
    os.environ["PUBSUB_EMULATOR_HOST"] = "localhost:4588"

    from google.cloud import pubsub_v1

    project_id = "floci-local"

    # Create topic
    publisher = pubsub_v1.PublisherClient()
    topic_path = publisher.topic_path(project_id, "my-topic")
    publisher.create_topic(request={"name": topic_path})

    # Create subscription
    subscriber = pubsub_v1.SubscriberClient()
    sub_path = subscriber.subscription_path(project_id, "my-sub")
    subscriber.create_subscription(request={
        "name": sub_path,
        "topic": topic_path,
    })

    # Publish
    future = publisher.publish(topic_path, b"hello from floci-gcp")
    future.result()

    # Pull
    response = subscriber.pull(request={"subscription": sub_path, "max_messages": 10})
    for msg in response.received_messages:
        print(msg.message.data.decode())
    ```

## Push Subscriptions

floci-gcp supports push subscriptions — it delivers messages to an HTTP endpoint you configure:

```java
subscriptionAdminClient.createSubscription(
    SubscriptionName.of("floci-local", "my-sub"),
    TopicName.of("floci-local", "my-topic"),
    PushConfig.newBuilder()
        .setPushEndpoint("http://my-app:8080/pubsub/push")
        .build(),
    0);
```

Messages are delivered as HTTP POST requests to the configured endpoint.

## Snapshots

Create and restore snapshots to replay messages:

```java
// Create snapshot
snapshotAdminClient.createSnapshot(
    SnapshotName.of("floci-local", "my-snapshot"),
    SubscriptionName.of("floci-local", "my-sub"));

// Seek to snapshot (replay messages from snapshot point)
subscriptionAdminClient.seek(SeekRequest.newBuilder()
    .setSubscription(SubscriptionName.of("floci-local", "my-sub").toString())
    .setSnapshot(SnapshotName.of("floci-local", "my-snapshot").toString())
    .build());
```

## Supported Operations

**Publisher:**

- `CreateTopic`
- `UpdateTopic`
- `DeleteTopic`
- `GetTopic`
- `ListTopics`
- `ListTopicSubscriptions`
- `Publish`

**Subscriber:**

- `CreateSubscription`
- `UpdateSubscription`
- `DeleteSubscription`
- `GetSubscription`
- `ListSubscriptions`
- `Pull`
- `StreamingPull`
- `Acknowledge`
- `ModifyAckDeadline`
- `ModifyPushConfig`
- `CreateSnapshot`
- `GetSnapshot`
- `ListSnapshots`
- `UpdateSnapshot`
- `DeleteSnapshot`
- `Seek`
