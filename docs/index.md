# floci-gcp

<p align="center">
  <img src="assets/floci.svg" alt="floci-gcp" width="500" />
</p>

<p align="center"><em>Light, fluffy, and always free — GCP Local Emulator</em></p>

---

floci-gcp is a fast, free, and open-source local GCP emulator built for developers who need reliable GCP services in development and CI without cost, complexity, or account setup.

## Supported Services

| Service | Protocol | Notable features |
|---|---|---|
| **Cloud Storage (GCS)** | REST XML + REST JSON | Buckets, objects, multipart upload, object compose, ACLs, bucket IAM, conditional requests, versioning, pre-signed URLs |
| **Pub/Sub** | gRPC + REST | Topics, subscriptions, publish, pull, streaming pull, push delivery, snapshots, seek, subscription filters |
| **Firestore** | gRPC | Documents, collections, queries, field transforms, aggregation, transactions, real-time listeners |
| **Datastore** | HTTP/protobuf | Entities, structured queries, GQL queries, aggregation, transactions |
| **Secret Manager** | gRPC + REST | Secrets, versions, access, disable/enable/destroy, IAM bindings |
| **Cloud Logging** | gRPC + REST | Structured log ingestion (`WriteLogEntries`), read-back (`ListLogEntries`) with filter subset, `ListLogs`, `DeleteLog` |
| **Cloud KMS** | gRPC + REST | Key rings, crypto keys, versions, symmetric encrypt/decrypt, asymmetric sign/decrypt, `GenerateRandomBytes` |
| **IAM** | REST | Service accounts, RSA-2048 keys, policy bindings, SignBlob (V4 signed URLs) |
| **Security Token Service (STS)** | REST | OAuth 2.0 token exchange for GCS Credential Access Boundaries |
| **Managed Kafka** | REST | Clusters, topics, consumer groups (Redpanda-backed or mock mode) |
| **Cloud Run** | REST | Service create/get/list/delete, IAM policy operations, revisions, LRO polling; Docker-backed invocation on by default (mock flag for control plane only) |
| **Cloud Functions** | REST | Function create/get/list/delete, upload URL generation, LRO polling; control plane only |
| **Cloud SQL for PostgreSQL** | REST | Instance lifecycle (Postgres), LRO polling; Docker-backed PostgreSQL data plane on by default (mock flag for control plane only) |
| **Cloud Tasks** | gRPC | Queues (rate limits, retry, pause/resume/purge), tasks (HTTP/App Engine targets), `RunTask`; control plane only |
| **Cloud Scheduler** | gRPC + REST | Cron jobs (Pub/Sub, HTTP, App Engine targets), `Pause`/`Resume`/`RunJob`, unix-cron + time zones; background dispatcher fires due jobs |
| **Cloud Monitoring** | gRPC + REST | Metric descriptors, monitored resource descriptors, time series write (`CreateTimeSeries`) and read (`ListTimeSeries`) |
| **GKE (Kubernetes Engine)** | REST | Cluster and operation APIs (`container.googleapis.com` v1); real k3s clusters via Docker or mock mode |
| **BigQuery (Phase 1)** | REST | Datasets, tables, `insertAll`/`tabledata.list`, query jobs over a SQL subset |
| **Service Usage** | REST | Enable/disable/list project services; backs Terraform `google_project_service` |
| **Firebase Auth (Identity Platform)** | REST | Identity Toolkit v1 sign-up/sign-in, emulator JWTs, admin user CRUD |
| **Eventarc** | REST | Trigger CRUD; delivers CloudEvents from Pub/Sub and GCS events to Cloud Run and HTTP endpoints |
| **IAM Credentials** | REST | `generateAccessToken` for service-account impersonation (shape-only stub tokens) |
| **Resource Manager** | REST | `projects.get` and project IAM policy mixins for provider project lookups |

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
      # Enables Docker-backed services (Cloud Run, Cloud SQL, Kafka, GKE)
      - /var/run/docker.sock:/var/run/docker.sock
    environment:
      FLOCI_GCP_HOSTNAME: floci-gcp
      FLOCI_GCP_BASE_URL: http://floci-gcp:4588
      # Keep state across restarts in the mounted ./data volume
      FLOCI_GCP_STORAGE_MODE: hybrid
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

All GCP services are immediately available at `http://localhost:4588`. Credentials are not cryptographically validated. The exception is a Floci-issued downscoped token, whose GCS requests are evaluated against its Credential Access Boundary (CAB).

[Get started →](getting-started/quick-start.md){ .md-button .md-button--primary }
[View services →](services/index.md){ .md-button }
