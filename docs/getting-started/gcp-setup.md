# GCP CLI & SDK Setup

floci-gcp accepts all requests unconditionally — no real GCP credentials are needed. GCP SDKs automatically skip credential validation when `*_EMULATOR_HOST` environment variables are set.

## Environment Variables

Set these in your shell before running any GCP SDK or CLI code:

```bash
export PUBSUB_EMULATOR_HOST=localhost:4588
export FIRESTORE_EMULATOR_HOST=localhost:4588
export DATASTORE_EMULATOR_HOST=localhost:4588
export STORAGE_EMULATOR_HOST=http://localhost:4588
export SECRET_MANAGER_EMULATOR_HOST=localhost:4588
```

Add them to your shell profile (`.bashrc` / `.zshrc`) to persist across sessions.

## gcloud CLI

```bash
# Set default project (no real GCP project required)
gcloud config set project floci-local

# Pub/Sub
gcloud pubsub topics create my-topic
gcloud pubsub subscriptions create my-sub --topic=my-topic
gcloud pubsub topics publish my-topic --message="hello"
gcloud pubsub subscriptions pull my-sub --auto-ack

# Cloud Storage
gcloud storage buckets create gs://my-bucket
echo "hello" | gcloud storage cp - gs://my-bucket/hello.txt
gcloud storage ls gs://my-bucket
```

## SDK Configuration

### Java (GCP SDK for Java)

```java
// Pub/Sub
ManagedChannel channel = ManagedChannelBuilder
    .forTarget("localhost:4588")
    .usePlaintext()
    .build();

CredentialsProvider noCredentials = NoCredentialsProvider.create();

TopicAdminClient topicClient = TopicAdminClient.create(
    TopicAdminSettings.newBuilder()
        .setTransportChannelProvider(
            FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel)))
        .setCredentialsProvider(noCredentials)
        .build());

SubscriberStubSettings subscriberSettings = SubscriberStubSettings.newBuilder()
    .setTransportChannelProvider(
        FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel)))
    .setCredentialsProvider(noCredentials)
    .build();
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
db.collection("users").add(Map.of("name", "Alice")).get();
```

```java
// Datastore
DatastoreOptions options = DatastoreOptions.newBuilder()
    .setHost("http://localhost:4588")
    .setProjectId("floci-local")
    .setCredentials(NoCredentials.getInstance())
    .build();

Datastore datastore = options.getService();
```

```java
// Secret Manager
SecretManagerServiceClient client = SecretManagerServiceClient.create(
    SecretManagerServiceSettings.newBuilder()
        .setTransportChannelProvider(
            FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel)))
        .setCredentialsProvider(noCredentials)
        .build());
```

### Python (google-cloud)

```python
import os

# Pub/Sub
os.environ["PUBSUB_EMULATOR_HOST"] = "localhost:4588"
from google.cloud import pubsub_v1

publisher = pubsub_v1.PublisherClient()
topic_path = publisher.topic_path("floci-local", "my-topic")
publisher.create_topic(request={"name": topic_path})

future = publisher.publish(topic_path, b"hello from floci-gcp")
future.result()
```

```python
# Firestore
import os
os.environ["FIRESTORE_EMULATOR_HOST"] = "localhost:4588"
from google.cloud import firestore

db = firestore.Client(project="floci-local")
db.collection("users").add({"name": "Alice", "age": 30})
docs = db.collection("users").stream()
for doc in docs:
    print(doc.to_dict())
```

```python
# Cloud Storage
import os
os.environ["STORAGE_EMULATOR_HOST"] = "http://localhost:4588"
from google.cloud import storage

client = storage.Client(project="floci-local")
bucket = client.bucket("my-bucket")
client.create_bucket(bucket)

blob = bucket.blob("hello.txt")
blob.upload_from_string("hello from floci-gcp")
```

### Node.js

```javascript
// Pub/Sub
process.env.PUBSUB_EMULATOR_HOST = "localhost:4588";
import { PubSub } from "@google-cloud/pubsub";

const pubsub = new PubSub({ projectId: "floci-local" });
await pubsub.createTopic("my-topic");

const [subscription] = await pubsub.topic("my-topic")
    .createSubscription("my-sub");

const [messages] = await subscription.pull({ maxMessages: 1 });
console.log(messages);
```

## Project ID

floci-gcp uses project ID `floci-local` as the default project when none is specified. Resources created in one project are fully isolated from another.

Change the default with `FLOCI_GCP_DEFAULT_PROJECT_ID`:

```bash
FLOCI_GCP_DEFAULT_PROJECT_ID=my-project
```

See [Multi-Project Isolation](../configuration/multi-project.md) for full details.
