# floci-gcp

<p align="center">
  <img src="assets/floci.svg" alt="floci-gcp" width="500" />
</p>

<p align="center"><em>Light, fluffy, and always free - GCP Local Emulator</em></p>

---

!!! warning "CTF fork"
    This documentation tree belongs to **floci-gcp-ctf**, a security-hardened fork of
    [floci-gcp](https://github.com/floci-io/floci-gcp). Compose defaults turn on IAM enforcement,
    strict mode, Bearer token validation, and internal endpoint hide. See
    [README.md](../README.md), [AGENTS.md](../AGENTS.md), and
    [IAM CTF hardening](services/iam.md#ctf-hardening).

floci-gcp is a fast, free, and open-source local GCP emulator built for developers who need reliable GCP services in development and CI without cost, complexity, or account setup.

## Supported Services

| Service | Protocol | Notable features |
|---|---|---|
| **Cloud Storage (GCS)** | REST XML + REST JSON | Buckets, objects, multipart upload, object compose, ACLs, bucket IAM, conditional requests, versioning, pre-signed URLs |
| **Pub/Sub** | gRPC + REST | Topics, subscriptions, publish, pull, streaming pull, push delivery, snapshots, seek |
| **Firestore** | gRPC | Documents, collections, queries, field transforms, aggregation, transactions, real-time listeners |
| **Datastore** | HTTP/protobuf + gRPC | Entities, structured queries, GQL queries, aggregation, transactions |
| **Secret Manager** | gRPC | Secrets, versions, access, disable/enable/destroy, IAM bindings |
| **Cloud Logging** | gRPC + REST | Structured log ingestion (`WriteLogEntries`), read-back (`ListLogEntries`) with filter subset, `ListLogs`, `DeleteLog` |
| **Cloud KMS** | gRPC + REST | Key rings, crypto keys, versions, symmetric encrypt/decrypt, asymmetric sign/decrypt, `GenerateRandomBytes` |
| **IAM** | REST | Service accounts, RSA-2048 keys, policy bindings, SignBlob (V4 signed URLs) |
| **Managed Kafka** | REST | Clusters, topics, consumer groups (Redpanda-backed or mock mode) |
| **Cloud Run** | REST | Service create/get/list/delete, IAM policy operations, revisions, LRO polling. Control plane by default, experimental Docker-backed invocation and GCS volume mounts when enabled |
| **Cloud Functions** | REST | Function create/get/list/delete, upload URL generation, LRO polling. Control plane only |
| **Cloud SQL for PostgreSQL** | REST | Instance lifecycle (Postgres), LRO polling. Control plane only |
| **Cloud Tasks** | gRPC + REST | Queues (rate limits, retry, pause/resume/purge), tasks (HTTP/App Engine targets), `RunTask`. Control plane only |
| **Cloud Scheduler** | gRPC + REST | Cron jobs (Pub/Sub, HTTP, App Engine targets), `Pause`/`Resume`/`RunJob`, unix-cron + time zones. Background dispatcher fires due jobs |
| **Cloud Monitoring** | gRPC + REST | Metric descriptors, monitored resource descriptors, time series write (`CreateTimeSeries`) and read (`ListTimeSeries`) |
| **Eventarc** | REST | Trigger CRUD, channels/providers list/get, Cloud Run / Pub/Sub event routing |

## Why floci-gcp?

**No account required.** No auth tokens, no sign-ups, no telemetry. Pull the image and start building.

**Single port.** All GCP services (gRPC and REST) on port `4588` via ALPN negotiation. No per-service setup.

**No feature gates.** Every feature is available to everyone. No community-edition restrictions.

**No CI restrictions.** Run in your CI pipeline with zero limitations. No credits, no quotas, no paid tiers.

**Truly open source.** MIT licensed. Fork it, extend it, embed it.

## Quick Start

This fork's Compose profile enables CTF hardening (IAM enforcement, strict mode, Bearer validation,
hide internals, operator root). Export root credentials, then use the repo
[docker-compose.yml](../docker-compose.yml):

```bash
export FLOCI_GCP_AUTH_ROOT_SERVICE_ACCOUNT="operator@floci-local.iam.gserviceaccount.com"
export FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN="..."
docker compose up -d
```

```yaml title="docker-compose.yml (excerpt)"
services:
  floci-gcp:
    ports:
      - "4588:4588"
    environment:
      FLOCI_GCP_HOSTNAME: floci-gcp
      FLOCI_GCP_BASE_URL: http://floci-gcp:4588
      FLOCI_GCP_SERVICES_IAM_ENFORCEMENT_ENABLED: "true"
      FLOCI_GCP_SERVICES_IAM_STRICT_ENFORCEMENT_ENABLED: "true"
      FLOCI_GCP_AUTH_VALIDATE_TOKENS: "true"
      FLOCI_GCP_CTF_HIDE_INTERNAL_ENDPOINTS: "true"
      FLOCI_GCP_AUTH_ROOT_SERVICE_ACCOUNT: ${FLOCI_GCP_AUTH_ROOT_SERVICE_ACCOUNT}
      FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN: ${FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN}
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

All GCP services are available at `http://localhost:4588`. With this fork's Compose CTF defaults,
Bearer tokens are validated and IAM allow policies are enforced on mapped REST and gRPC calls.
Upstream-style permissive auth (no token / no IAM checks) applies only when those flags are off.

[Get started →](getting-started/quick-start.md){ .md-button .md-button--primary }
[View services →](services/index.md){ .md-button }
