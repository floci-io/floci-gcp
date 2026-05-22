<p align="center">
  <img src="floci_banner.svg" alt="floci-gcp"/>
</p>

<p align="center">
  <a href="https://github.com/hectorvent/floci-gcp/releases/latest"><img src="https://img.shields.io/github/v/release/hectorvent/floci-gcp?label=latest%20release&color=blue" alt="Latest Release"></a>
  <a href="https://github.com/hectorvent/floci-gcp/actions/workflows/release.yml"><img src="https://img.shields.io/github/actions/workflow/status/hectorvent/floci-gcp/release.yml?label=build" alt="Build Status"></a>
  <a href="https://hub.docker.com/r/floci/floci-gcp"><img src="https://img.shields.io/docker/pulls/floci/floci-gcp?label=docker%20pulls" alt="Docker Pulls"></a>
  <a href="https://hub.docker.com/r/floci/floci-gcp"><img src="https://img.shields.io/docker/image-size/floci/floci-gcp/latest?label=image%20size" alt="Docker Image Size"></a>
  <a href="https://opensource.org/licenses/MIT"><img src="https://img.shields.io/badge/license-MIT-green" alt="License: MIT"></a>
  <a href="https://github.com/hectorvent/floci-gcp/stargazers"><img src="https://img.shields.io/github/stars/hectorvent/floci-gcp?style=flat" alt="GitHub Stars"></a>
</p>

<p align="center">
  <strong>A free, open-source local GCP emulator.</strong><br/>
  No account. No feature gates. Just&nbsp;<code>docker compose up</code>.
</p>

<p align="center">
  <em>Named after <a href="https://en.wikipedia.org/wiki/Cirrocumulus_floccus">floccus</a> — the cloud formation that looks exactly like popcorn.</em>
</p>

---

## Table of Contents

- [Why floci-gcp?](#why-floci-gcp)
- [Quick Start](#quick-start)
- [Supported Services](#supported-services)
- [Persistence & Storage Modes](#persistence--storage-modes)
- [Multi-Project Isolation](#multi-project-isolation)
- [SDK Integration](#sdk-integration)
- [Compatibility Testing](#compatibility-testing)
- [Configuration](#configuration)
- [Contributors](#contributors)
- [License](#license)

---

## Why floci-gcp?

GCP's official emulators are fragmented — each service ships its own binary, runs on a different port, and requires separate setup. floci-gcp unifies them under a single port with a single `docker compose up`.

| | floci-gcp | GCP official emulators |
|---|:---:|:---:|
| Single port for all services | ✅ | ❌ |
| gRPC + REST on the same port | ✅ | ❌ |
| No GCP account required | ✅ | ✅ |
| Pub/Sub | ✅ | ✅ |
| Firestore | ✅ | ✅ |
| Datastore | ✅ | ✅ |
| Cloud Storage (GCS) | ✅ | ⚠️ Limited |
| Secret Manager | ✅ | ❌ |
| IAM | ✅ | ❌ |
| Managed Kafka | ✅ | ❌ |
| Native binary | ✅ | ❌ |

---

## Quick Start

```yaml
# docker-compose.yml
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
docker compose up
```

Or run directly with Docker:

```bash
docker run -d --name floci-gcp \
  -p 4588:4588 \
  floci/floci-gcp:latest
```

All services are available at `http://localhost:4588`. Credentials are not validated.

Point GCP SDKs at the emulator using the standard emulator environment variables:

```bash
export PUBSUB_EMULATOR_HOST=localhost:4588
export FIRESTORE_EMULATOR_HOST=localhost:4588
export DATASTORE_EMULATOR_HOST=localhost:4588
export STORAGE_EMULATOR_HOST=http://localhost:4588
export SECRET_MANAGER_EMULATOR_HOST=localhost:4588
```

---

## Supported Services

| Service | Protocol | Notable features |
|---|---|---|
| **Cloud Storage (GCS)** | REST XML + REST JSON | Buckets, objects, multipart upload, versioning, pre-signed URLs |
| **Pub/Sub** | gRPC + REST | Topics, subscriptions, publish, pull, push delivery, snapshots |
| **Firestore** | gRPC | Documents, collections, queries, transactions, real-time listeners |
| **Datastore** | gRPC | Entities, queries, transactions, indexes |
| **Secret Manager** | gRPC + REST | Secrets, versions, access, IAM bindings |
| **IAM** | REST | Service accounts, keys, policy bindings |
| **Managed Kafka** | REST | Clusters, topics, consumer groups (Redpanda-backed) |

---

## Persistence & Storage Modes

floci-gcp supports flexible storage modes. Configure globally via `FLOCI_GCP_STORAGE_MODE`.

| Mode | Behavior | Best for | Durability |
|:---:|---|---|:---:|
| **`memory`** | **(Default)** Entirely in-RAM. Lost on container stop. | Speed, CI pipelines | ❌ None |
| **`persistent`** | Every write goes directly to disk synchronously. | Durable local dev | ✅ Good |
| **`hybrid`** | In-memory with async flush every 5 seconds. | Balance of speed and safety | ✅ Good |
| **`wal`** | Write-Ahead Log. Every mutation written to disk immediately. | Maximum durability | 💎 Highest |

> [!TIP]
> Use **`memory`** for fast CI pipelines. Use **`hybrid`** for local development when you want state preserved across restarts.

---

## Multi-Project Isolation

GCP resource names follow `projects/{project}/...`. floci-gcp uses the project ID as the multi-tenancy boundary — resources created in one project are invisible to another.

The project ID is resolved in this order:
1. URL path segment `projects/{project}/...`
2. `x-goog-request-params` header (`project=...`)
3. `FLOCI_GCP_DEFAULT_PROJECT_ID` fallback (default: `floci-local`)

```bash
# Two projects, full isolation
PUBSUB_EMULATOR_HOST=localhost:4588 gcloud pubsub topics create my-topic --project=project-a
PUBSUB_EMULATOR_HOST=localhost:4588 gcloud pubsub topics create my-topic --project=project-b
```

---

## SDK Integration

<details>
<summary><strong>Java (GCP SDK)</strong></summary>

```java
// Pub/Sub
TransportChannelProvider channelProvider = ManagedChannelBuilder
    .forTarget("localhost:4588")
    .usePlaintext()
    .build()
    .newChannelProvider();

CredentialsProvider credentialsProvider = NoCredentialsProvider.create();

TopicAdminClient topicAdminClient = TopicAdminClient.create(
    TopicAdminSettings.newBuilder()
        .setTransportChannelProvider(channelProvider)
        .setCredentialsProvider(credentialsProvider)
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

</details>

<details>
<summary><strong>Python (google-cloud)</strong></summary>

```python
import os
os.environ["PUBSUB_EMULATOR_HOST"] = "localhost:4588"

from google.cloud import pubsub_v1

publisher = pubsub_v1.PublisherClient()
project_path = "projects/floci-local"
topic_path = publisher.topic_path("floci-local", "my-topic")

publisher.create_topic(request={"name": topic_path})
future = publisher.publish(topic_path, b"hello from floci-gcp")
future.result()
print("Published message")
```

```python
import os
os.environ["FIRESTORE_EMULATOR_HOST"] = "localhost:4588"

from google.cloud import firestore

db = firestore.Client(project="floci-local")
db.collection("users").add({"name": "Alice", "age": 30})
docs = db.collection("users").stream()
for doc in docs:
    print(doc.to_dict())
```

</details>

<details>
<summary><strong>Node.js</strong></summary>

```javascript
import { PubSub } from "@google-cloud/pubsub";

process.env.PUBSUB_EMULATOR_HOST = "localhost:4588";

const pubsub = new PubSub({ projectId: "floci-local" });

await pubsub.createTopic("my-topic");
const [subscription] = await pubsub.topic("my-topic")
    .createSubscription("my-sub");

const [messages] = await subscription.pull({ maxMessages: 1 });
console.log(messages);
```

</details>

<details>
<summary><strong>Bash (gcloud CLI)</strong></summary>

```bash
export PUBSUB_EMULATOR_HOST=localhost:4588

gcloud config set project floci-local
gcloud pubsub topics create my-topic
gcloud pubsub subscriptions create my-sub --topic=my-topic
gcloud pubsub topics publish my-topic --message="hello from floci-gcp"
gcloud pubsub subscriptions pull my-sub --auto-ack
```

</details>

---

## Compatibility Testing

The `./compatibility-tests/` directory provides SDK-based integration tests for validating real-world GCP SDK compatibility.

```bash
# Run all compatibility tests
docker compose -f docker-compose-test.yml up --build
```

Java-based tests using the GCP SDK for Java are preferred for management-plane API validation.

---

## Configuration

All settings are overridable via environment variables (`FLOCI_GCP_` prefix).

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_PORT` | `4588` | Port for all services (gRPC + REST) |
| `FLOCI_GCP_DEFAULT_PROJECT_ID` | `floci-local` | Default GCP project ID |
| `FLOCI_GCP_BASE_URL` | `http://localhost:4588` | Base URL returned in service responses |
| `FLOCI_GCP_HOSTNAME` | *(unset)* | Hostname to use in returned URLs when running inside Docker Compose |
| `FLOCI_GCP_STORAGE_MODE` | `memory` | Storage mode: `memory` · `persistent` · `hybrid` · `wal` |
| `FLOCI_GCP_STORAGE_PERSISTENT_PATH` | `./data` | Directory for persisted state |
| `FLOCI_GCP_SERVICES_GCS_ENABLED` | `true` | Enable/disable Cloud Storage |
| `FLOCI_GCP_SERVICES_PUBSUB_ENABLED` | `true` | Enable/disable Pub/Sub |
| `FLOCI_GCP_SERVICES_FIRESTORE_ENABLED` | `true` | Enable/disable Firestore |
| `FLOCI_GCP_SERVICES_DATASTORE_ENABLED` | `true` | Enable/disable Datastore |
| `FLOCI_GCP_SERVICES_IAM_ENABLED` | `true` | Enable/disable IAM |
| `FLOCI_GCP_SERVICES_SECRETMANAGER_ENABLED` | `true` | Enable/disable Secret Manager |
| `FLOCI_GCP_SERVICES_KAFKA_ENABLED` | `true` | Enable/disable Managed Kafka |
| `FLOCI_GCP_SERVICES_KAFKA_MOCK` | `false` | Use mock mode (no Docker; returns ACTIVE immediately) |
| `FLOCI_GCP_DNS_EXTRA_SUFFIXES` | *(unset)* | Extra DNS suffixes for embedded DNS (comma-separated) |

**Multi-container Docker Compose:** Set `FLOCI_GCP_HOSTNAME` to the service name so returned URLs resolve correctly from other containers:

```yaml
services:
  floci-gcp:
    image: floci/floci-gcp:latest
    ports:
      - "4588:4588"
    environment:
      FLOCI_GCP_HOSTNAME: floci-gcp
      FLOCI_GCP_BASE_URL: http://floci-gcp:4588
  my-app:
    environment:
      PUBSUB_EMULATOR_HOST: floci-gcp:4588
      FIRESTORE_EMULATOR_HOST: floci-gcp:4588
    depends_on:
      - floci-gcp
```

---

## Contributors

<a href="https://github.com/hectorvent/floci-gcp/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=hectorvent/floci-gcp" />
</a>

---

## License

MIT — use it however you want.
