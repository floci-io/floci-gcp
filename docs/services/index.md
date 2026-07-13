# Services Overview

floci-gcp emulates GCP services on a single port (`4588`). All services use real GCP wire protocols. Your existing GCP SDK calls and gcloud CLI commands work without modification.

!!! note "CTF fork"
    **floci-gcp-ctf** enables Bearer validation and IAM enforcement by default in Compose.
    See [IAM CTF hardening](iam.md#ctf-hardening), [AGENTS.md](../../AGENTS.md), and
    [environment variables](../configuration/environment-variables.md#ctf-hardening).

## Service Matrix

| Service | Protocol | Endpoint |
|---|---|---|
| [Cloud Storage (GCS)](gcs.md) | REST XML (objects) + REST JSON (management) | `/{bucket}/{object}`, `/storage/v1/b/{bucket}` |
| [Pub/Sub](pubsub.md) | gRPC + REST JSON | `google.pubsub.v1.Publisher`, `google.pubsub.v1.Subscriber`, `/v1/projects/{project}/topics`, `/v1/projects/{project}/subscriptions` |
| [Firestore](firestore.md) | gRPC | `google.firestore.v1.Firestore` |
| [Datastore](datastore.md) | HTTP/protobuf + gRPC | `/v1/projects/{project}:{method}`, `google.datastore.v1.Datastore` |
| [Secret Manager](secret-manager.md) | gRPC | `google.cloud.secretmanager.v1.SecretManagerService` |
| [Cloud Logging](logging.md) | gRPC + REST JSON | `google.logging.v2.LoggingServiceV2`, `/v2/entries:write`, `/v2/entries:list` |
| [Cloud KMS](kms.md) | gRPC + REST JSON | `google.cloud.kms.v1.KeyManagementService`, `/v1/projects/{project}/locations/{location}/keyRings` |
| [IAM](iam.md) | REST JSON | `/v1/projects/{project}/serviceAccounts` |
| [Managed Kafka](managed-kafka.md) | REST JSON | `/v1/projects/{project}/locations/{location}/clusters` |
| [GKE (Kubernetes Engine)](gke.md) | REST JSON | `container.*` host or `/container/v1/projects/{project}/locations/{location}/clusters` |
| [Cloud SQL for PostgreSQL](cloud-sql-postgres.md) | REST JSON | `/v1/projects/{project}/instances` |
| [Cloud Run](cloud-run.md) | REST JSON | `/v2/projects/{project}/locations/{location}/services` |
| [Cloud Functions](cloud-functions.md) | REST JSON | `/v2/projects/{project}/locations/{location}/functions` |
| [Cloud Tasks](cloud-tasks.md) | gRPC + REST JSON | `google.cloud.tasks.v2.CloudTasks`, `/v2/projects/{project}/locations/{location}/queues` |
| [Cloud Scheduler](scheduler.md) | gRPC + REST JSON | `google.cloud.scheduler.v1.CloudScheduler`, `/v1/projects/{project}/locations/{location}/jobs` |
| [Cloud Monitoring](cloud-monitoring.md) | gRPC + REST JSON | `google.monitoring.v3.MetricService`, `/v3/projects/{project}` |
| [Eventarc](eventarc.md) | REST JSON | `/v1/projects/{project}/locations/{location}/triggers` |
| [Service Usage](service-usage.md) | REST JSON | `/v1/projects/{project}/services` (+ minimal Resource Manager `/v1/projects/{projectId}`) |
| [Firebase Auth](firebase-auth.md) | REST JSON | `/identitytoolkit.googleapis.com/v1/accounts:*`, `/securetoken.googleapis.com/v1/token` |
| [BigQuery (Phase 1)](bigquery.md) | REST JSON | `/bigquery/v2/projects/{project}` |

## Single-Port Design

All services (gRPC and REST) are available on port **4588** via ALPN negotiation:

- `http2=true` enables HTTP/2 support
- `grpc.server.use-separate-server=false` means gRPC and REST share the same port

Clients using plain HTTP/1.1 are served REST endpoints. Clients using HTTP/2 (gRPC) are served gRPC endpoints. No separate ports or proxy configuration is required.

## Common Setup

Before calling any service, set the appropriate emulator environment variable:

```bash
export PUBSUB_EMULATOR_HOST=localhost:4588
export FIRESTORE_EMULATOR_HOST=localhost:4588
export DATASTORE_EMULATOR_HOST=localhost:4588
export STORAGE_EMULATOR_HOST=http://localhost:4588
export SECRET_MANAGER_EMULATOR_HOST=localhost:4588
```

GCP SDKs automatically bypass credential validation when these variables are set. Some REST management SDKs, including Cloud Run and Cloud Functions, do not have emulator environment variables. Configure their client endpoint explicitly as `http://localhost:4588` and use no credentials.

For gcloud CLI:

```bash
gcloud config set project floci-local
```

## Auth and IAM (CTF fork)

With this fork's Compose defaults
(`FLOCI_GCP_AUTH_VALIDATE_TOKENS`, `FLOCI_GCP_SERVICES_IAM_ENFORCEMENT_ENABLED`,
`FLOCI_GCP_SERVICES_IAM_STRICT_ENFORCEMENT_ENABLED`):

- Requests need a valid `Authorization: Bearer` token (operator root or `TokenRegistry`)
- Mapped REST and gRPC actions are checked against project IAM allow policies
- Unmapped actions and missing principals are denied under strict mode

When those flags are off, behavior matches upstream floci-gcp: credentials are not validated and
requests are accepted unconditionally (same idea as GCP official emulators with `*_EMULATOR_HOST`).

Details: [IAM CTF hardening](iam.md#ctf-hardening).

## Multi-Project Isolation

All resources are namespaced by GCP project ID. Resources in `project-a` are invisible to `project-b`. See [Multi-Project Isolation](../configuration/multi-project.md).
