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

IAM policy methods are served for topics, subscriptions, and snapshots:

- `GET /v1/projects/{project}/topics/{topic}:getIamPolicy`
- `POST /v1/projects/{project}/topics/{topic}:setIamPolicy`
- `POST /v1/projects/{project}/topics/{topic}:testIamPermissions`
- `GET /v1/projects/{project}/subscriptions/{subscription}:getIamPolicy`
- `POST /v1/projects/{project}/subscriptions/{subscription}:setIamPolicy`
- `POST /v1/projects/{project}/subscriptions/{subscription}:testIamPermissions`
- `GET /v1/projects/{project}/snapshots/{snapshot}:getIamPolicy`
- `POST /v1/projects/{project}/snapshots/{snapshot}:setIamPolicy`
- `POST /v1/projects/{project}/snapshots/{snapshot}:testIamPermissions`

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

## Subscription Filters

A subscription can declare a `filter` so it only receives messages whose attributes match it.
Non-matching messages are never delivered to that subscription — as in GCP, which acknowledges
them automatically on your behalf. Subscriptions without a filter receive every message published
to the topic.

```java
subscriptionAdminClient.createSubscription(Subscription.newBuilder()
    .setName(SubscriptionName.of("floci-local", "invoices").toString())
    .setTopic(TopicName.of("floci-local", "events").toString())
    .setAckDeadlineSeconds(10)
    .setFilter("attributes.event_type = \"ocr-invoice\"")
    .build());
```

```python
subscriber.create_subscription(request={
    "name": sub_path,
    "topic": topic_path,
    "filter": 'attributes.event_type = "ocr-invoice"',
})
```

### Supported syntax

| Form | Example | Matches |
|---|---|---|
| Attribute exists | `attributes:name` | messages that carry a `name` attribute |
| Quoted key | `attributes:"iana.org/language_tag"` | keys with characters other than hyphens, underscores or alphanumerics |
| Equality | `attributes.name = "com"` | `name` is exactly `com` |
| Inequality | `attributes.name != "com"` | `name` differs from `com`, **including when the attribute is absent** |
| Prefix | `hasPrefix(attributes.name, "co")` | `name` starts with `co` |
| Conjunction | `attributes:a AND attributes.b = "1"` | both operands match |
| Disjunction | `attributes.a = "1" OR attributes.a = "2"` | either operand matches |
| Negation | `NOT attributes:a` / `-attributes:a` | operand does not match |

Rules that mirror GCP:

- Keys and values are **case-sensitive**; `AND`, `OR` and `NOT` must be **uppercase**.
- `NOT` has the highest precedence; `-` is a unary alias for it.
- `AND` and `OR` **cannot be combined without parentheses**. `a AND b OR c` is a syntax error;
  write `a AND (b OR c)`.
- `hasPrefix` is the only function — there is no regular-expression support.
- String literals may contain unicode, hexadecimal and octal escape sequences, for example
  `attributes:"みんな"`. Escapes outside a string literal are invalid.
- A filter must be at most **256 bytes**.

An unparseable filter, or one over the byte limit, is rejected with `INVALID_ARGUMENT` at creation
time, rather than being accepted and silently ignored.

### The filter is immutable

As in GCP, the filter is a property of the subscription that cannot change after creation. A
`subscriptions.patch` that names `filter` in its update mask is rejected with `INVALID_ARGUMENT`,
whatever value it carries — GCP rejects on the presence of the field in the mask, not on whether the
value differs, so restating the current filter fails too. A patch that does not name `filter` in its
mask succeeds and leaves the filter untouched, even when the request body carries one — the update
mask governs, so clients that echo a whole subscription back keep working.

GCP requires an update mask on `subscriptions.patch`; floci-gcp also accepts a patch without one and
treats it as replacing every field. On that path a body carrying the subscription's current filter is
accepted and the filter is left alone, a body carrying a different one is rejected, and a body
omitting it leaves the filter in place rather than clearing it.

To change a filter, follow the same path as in GCP: snapshot the subscription, create a new one with
the desired filter, `Seek` to the snapshot, move subscribers over, then delete the old subscription.

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

## IAM Policies

IAM policies on topics, subscriptions, and snapshots are **stored and returned,
never enforced**. `setIamPolicy` followed by `getIamPolicy` returns exactly the
bindings that were set — including `condition` blocks, which are stored verbatim
and never evaluated — but no request is ever denied because of a policy. Do not
build authorization tests on top of the emulator.

Policy semantics:

- `getIamPolicy` on an existing resource with no policy returns an empty policy
  with the well-known etag `ACAB`, matching GCP.
- `getIamPolicy` / `setIamPolicy` against a resource that does not exist fail
  with `NOT_FOUND`.
- The etag rotates on every write. A `setIamPolicy` carrying a stale etag fails
  with `ABORTED` (HTTP 409), so read-modify-write flows such as Terraform's
  `google_pubsub_topic_iam_member` behave as they do against real GCP. Omitting
  the etag performs a blind write.
- Deleting a resource deletes its policy; recreating the same name starts empty.
- `testIamPermissions` echoes the requested permissions without consulting
  stored bindings, and performs no existence check (the real API fails open for
  missing resources).
- Schemas are not implemented, so schema IAM paths are not served.

Over gRPC these methods are served by the standalone `google.iam.v1.IAMPolicy`
service (Pub/Sub declares IAM as a service-config mixin), which the Pub/Sub
SDKs call transparently via `TopicAdminClient` / `SubscriptionAdminClient`.

```java
// Grant a worker publish access to a topic, then read it back
TopicName topic = TopicName.of("floci-local", "my-topic");
Policy policy = topicAdminClient.getIamPolicy(GetIamPolicyRequest.newBuilder()
    .setResource(topic.toString()).build());
Policy updated = topicAdminClient.setIamPolicy(SetIamPolicyRequest.newBuilder()
    .setResource(topic.toString())
    .setPolicy(policy.toBuilder()
        .addBindings(Binding.newBuilder()
            .setRole("roles/pubsub.publisher")
            .addMembers("serviceAccount:worker@floci-local.iam.gserviceaccount.com"))
        .build())
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

**IAM (`google.iam.v1.IAMPolicy` mixin — stored, never enforced):**

- `GetIamPolicy`
- `SetIamPolicy`
- `TestIamPermissions` (echoes requested permissions)
