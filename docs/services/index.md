# Services Overview

floci-gcp emulates GCP services on a single port (`4588`). All services use real GCP wire protocols — your existing GCP SDK calls and gcloud CLI commands work without modification.

## Service Matrix

| Service | Protocol | Endpoint |
|---|---|---|
| [Cloud Storage (GCS)](gcs.md) | REST XML (objects) + REST JSON (management) | `/{bucket}/{object}`, `/storage/v1/b/{bucket}` |
| [Pub/Sub](pubsub.md) | gRPC + REST JSON | `google.pubsub.v1.Publisher`, `google.pubsub.v1.Subscriber`, `/v1/projects/{project}/topics` |
| [Firestore](firestore.md) | gRPC | `google.firestore.v1.Firestore` |
| [Datastore](datastore.md) | HTTP/protobuf | `/v1/projects/{project}:{method}` |
| [Secret Manager](secret-manager.md) | gRPC | `google.cloud.secretmanager.v1.SecretManagerService` |
| [Cloud Logging](logging.md) | gRPC + REST JSON | `google.logging.v2.LoggingServiceV2`, `/v2/entries:write`, `/v2/entries:list` |
| [Cloud KMS](kms.md) | gRPC + REST JSON | `google.cloud.kms.v1.KeyManagementService`, `/v1/projects/{project}/locations/{location}/keyRings` |
| [IAM](iam.md) | REST JSON | `/v1/projects/{project}/serviceAccounts` |
| [IAM Credentials](iam-credentials.md) | REST JSON | `/v1/projects/-/serviceAccounts/{sa}:generateAccessToken` |
| [Security Token Service (STS)](sts.md) | REST JSON | `/v1/token` |
| [Managed Kafka](managed-kafka.md) | REST JSON | `/v1/projects/{project}/locations/{location}/clusters` |
| [GKE (Kubernetes Engine)](gke.md) | REST JSON | `container.*` host or `/container/v1/projects/{project}/locations/{location}/clusters` |
| [Cloud SQL for PostgreSQL](cloud-sql-postgres.md) | REST JSON | `/v1/projects/{project}/instances` |
| [Cloud Run](cloud-run.md) | REST JSON | `/v2/projects/{project}/locations/{location}/services` |
| [Cloud Functions](cloud-functions.md) | REST JSON | `/v2/projects/{project}/locations/{location}/functions` |
| [Cloud Tasks](cloud-tasks.md) | gRPC | `google.cloud.tasks.v2.CloudTasks` |
| [Cloud Scheduler](scheduler.md) | gRPC + REST JSON | `google.cloud.scheduler.v1.CloudScheduler`, `/v1/projects/{project}/locations/{location}/jobs` |
| [Cloud Monitoring](cloud-monitoring.md) | gRPC + REST JSON | `google.monitoring.v3.MetricService`, `/v3/projects/{project}` |
| [Service Usage](service-usage.md) | REST JSON | `/v1/projects/{project}/services` |
| [Resource Manager](service-usage.md#cloud-resource-manager-companion) | REST JSON | `/v1/projects/{projectId}` (minimal `projects.get`) |
| [Eventarc](eventarc.md) | REST JSON | `/v1/projects/{project}/locations/{location}/triggers` |
| [Firebase Auth](firebase-auth.md) | REST JSON | `/identitytoolkit.googleapis.com/v1/accounts:*`, `/securetoken.googleapis.com/v1/token` |
| [BigQuery (Phase 1)](bigquery.md) | REST JSON | `/bigquery/v2/projects/{project}` |

## Single-Port Design

All services — gRPC and REST — are available on port **4588** via ALPN negotiation:

- `http2=true` — enables HTTP/2 support
- `grpc.server.use-separate-server=false` — gRPC and REST share the same port

Clients using plain HTTP/1.1 are served REST endpoints. Clients using HTTP/2 (gRPC) are served gRPC endpoints. No separate ports or proxy configuration is required.

### TLS listener

The same gRPC + REST surface is also served over TLS on port **4589**, for clients that
refuse plaintext connections (for example Go clients built on `google-cloud-go`, such as the
OpenTelemetry Collector's `googlecloudmonitoring` receiver). In Docker the entrypoint
generates a self-signed certificate on first start; fetch it from
`GET http://localhost:4588/_floci-gcp/tls/cert` and add it to the client's trust store. The
generated certificate covers `localhost`, `floci-gcp`, `host.docker.internal`, and the
loopback IPs; add more names via `FLOCI_GCP_TLS_EXTRA_SANS`, disable the listener with
`FLOCI_GCP_TLS_ENABLED=false`, or supply your own PEM pair via `FLOCI_GCP_TLS_CERTIFICATE` /
`FLOCI_GCP_TLS_CERTIFICATE_KEY` (outside Docker the listener only opens when these are set).

## Common Setup

Before calling any service, set the appropriate emulator environment variable:

```bash
export PUBSUB_EMULATOR_HOST=localhost:4588
export FIRESTORE_EMULATOR_HOST=localhost:4588
export DATASTORE_EMULATOR_HOST=localhost:4588
export STORAGE_EMULATOR_HOST=http://localhost:4588
export SECRET_MANAGER_EMULATOR_HOST=localhost:4588
```

GCP SDKs automatically bypass credential validation when these variables are set. Some REST management SDKs, including Cloud Run and Cloud Functions, do not have emulator environment variables; configure their client endpoint explicitly as `http://localhost:4588` and use no credentials.

For gcloud CLI:

```bash
gcloud config set project floci-local
```

## Auth Bypass

floci-gcp does not cryptographically validate credentials. Requests with no credential, external credentials, and Floci-issued OAuth or impersonated tokens are accepted. The exception is a Floci-issued downscoped token, whose GCS requests are evaluated against its Credential Access Boundary (CAB). This otherwise matches the behavior of GCP official emulators when `*_EMULATOR_HOST` is set.

## Multi-Project Isolation

All resources are namespaced by GCP project ID. Resources in `project-a` are invisible to `project-b`. See [Multi-Project Isolation](../configuration/multi-project.md).
