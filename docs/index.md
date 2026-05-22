# floci-gcp

<p align="center">
  <img src="assets/floci.png" alt="floci-gcp" width="300" />
</p>

<p align="center"><em>Light, fluffy, and always free — GCP Local Emulator</em></p>

---

floci-gcp is a fast, free, and open-source local GCP emulator built for developers who need reliable GCP services in development and CI without cost, complexity, or account setup.

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

## Why floci-gcp?

**No account required.** No auth tokens, no sign-ups, no telemetry. Pull the image and start building.

**Single port.** All GCP services — gRPC and REST — on port `4588` via ALPN negotiation. No per-service setup.

**No feature gates.** Every feature is available to everyone — no community-edition restrictions.

**No CI restrictions.** Run in your CI pipeline with zero limitations. No credits, no quotas, no paid tiers.

**Truly open source.** MIT licensed. Fork it, extend it, embed it.

## Quick Start

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

Point your GCP SDKs at the emulator:

```bash
export PUBSUB_EMULATOR_HOST=localhost:4588
export FIRESTORE_EMULATOR_HOST=localhost:4588
export DATASTORE_EMULATOR_HOST=localhost:4588
export STORAGE_EMULATOR_HOST=http://localhost:4588
export SECRET_MANAGER_EMULATOR_HOST=localhost:4588
export GOOGLE_CLOUD_PROJECT=floci-local
```

All GCP services are immediately available at `http://localhost:4588`. Credentials are not validated.

[Get started →](getting-started/quick-start.md){ .md-button .md-button--primary }
[View services →](services/index.md){ .md-button }
