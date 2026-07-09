# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.5.0] - 2026-07-09

### Added

- **serviceusage:** Service Usage REST API (`serviceusage.googleapis.com` v1) — `services.enable`/`disable`/`get`/`list` (with `state:` filters)/`batchEnable`/`batchGet`, returning already-done long-running operations; project-namespaced accept-and-succeed state that unblocks Terraform `google_project_service`, Pulumi, and `gcloud services`
- **resourcemanager:** minimal Cloud Resource Manager v1 `projects.get` endpoint — the Terraform/Pulumi Google providers call it on every `google_project_service` read; every project resolves to `ACTIVE` with a stable synthetic project number
- **firebaseauth:** Identity Platform / Firebase Auth REST API (`identitytoolkit.googleapis.com` v1) — wire-compatible with the official Firebase Auth emulator: email/password, anonymous, and custom-token sign-in, admin user CRUD + `batchGet`/`batchDelete`, unsigned emulator-style ID tokens that `firebase-admin` verifies, and `securetoken` refresh with `validSince`-based revocation via `FIREBASE_AUTH_EMULATOR_HOST`
- **bigquery:** BigQuery Phase 1 REST API (`bigquery/v2`) — dataset and table CRUD with schema normalization, schema-validated `tabledata.insertAll`/`tabledata.list`, and query jobs (`jobs.query`, `jobs.insert`, `jobs.get`, `getQueryResults`) over a SQL subset (`SELECT *`/columns/`COUNT(*)`, `WHERE` equality, `LIMIT`, projection) with reason-coded errors and anonymous destination tables
- **eventarc:** Eventarc REST service — trigger CRUD and Cloud Run / Pub/Sub event routing

### Changed

- **monitoring:** closed Cloud Monitoring time-series parity gaps — `ListTimeSeries` aggregation (per-series alignment + cross-series reduction) with pagination, `CreateTimeSeries` GCP validation rules (metric kind/value-type consistency, label and interval checks), extended filter grammar, and gating/point-sequence fixes

### Fixed

- **pubsub:** deliver published messages to already-open `StreamingPull` streams

## [0.4.0] - 2026-06-26

### Added

- **pubsub:** Pub/Sub REST API (`/v1/projects/{project}/...`) alongside the existing gRPC surface — topics (create/get/list/patch/delete/`:publish`) and subscriptions (create/get/list/patch/delete/`:pull`/`:acknowledge`), so the REST-based Pub/Sub clients and `gcloud pubsub` work against the emulator
- **gke:** GKE control plane (`container.googleapis.com`, ClusterManager v1) — cluster and operation lifecycle backed by real `rancher/k3s` clusters via the docker-java API (with a `gke.mock` synthetic fast path), responses following the `google.container.v1` `Cluster`/`Operation` shapes. Reached through a single-port host+path routing filter (`container.*` host for SDKs, `/container/v1` prefix for gcloud/Terraform). Native `kubectl` auth via a token webhook so `gcloud container clusters get-credentials` plus the `gke-gcloud-auth-plugin` work end to end

### Fixed

- **gcs:** resolved `405 Method Not Allowed` during resumable uploads when using a custom endpoint

## [0.3.0] - 2026-06-19

### Added

- **cloudrun:** Cloud Run control plane — service create/get/list/delete, IAM policy operations, revisions, and LRO polling; plus experimental Docker-backed service execution and GCS volume mounts
- **cloudfunctions:** Cloud Functions control plane — function create/get/list/delete, upload URL generation, and LRO polling
- **cloudsql:** Cloud SQL for PostgreSQL — instance lifecycle control plane and a Docker-backed PostgreSQL data plane exposing reachable endpoints across Postgres 15–18 (data-plane SQL is run via in-container `psql`, so no JDBC driver is bundled)
- **logging:** Cloud Logging (gRPC + REST) — `WriteLogEntries`, `ListLogEntries` with a filter subset, `ListLogs`, and `DeleteLog`
- **kms:** Cloud KMS (gRPC + REST) — key rings, crypto keys, versions, symmetric encrypt/decrypt, asymmetric sign/decrypt, and `GenerateRandomBytes`
- **monitoring:** Cloud Monitoring (gRPC + REST) — metric and monitored-resource descriptors, `CreateTimeSeries`, and `ListTimeSeries`
- **scheduler:** Cloud Scheduler (gRPC + REST) — cron jobs (Pub/Sub, HTTP, App Engine targets), `Pause`/`Resume`/`RunJob`, unix-cron with time zones, and a background dispatcher that fires due jobs
- **compat:** gcloud CLI compatibility test suite

### Changed

- **config:** **BREAKING** — standardized a single root-level `mock` flag across container-backed services. Cloud SQL's `floci-gcp.services.cloudsql.data-plane-enabled` is replaced by `floci-gcp.services.cloudsql.mock` (inverted meaning: `mock: false` runs the real Docker-backed data plane), and Cloud Run's execution toggle moves to `floci-gcp.services.cloudrun.mock` (the previous `execution.enabled` flag is removed). Both default to `mock: false` (real containers). Update `FLOCI_GCP_SERVICES_CLOUDSQL_MOCK` and `FLOCI_GCP_SERVICES_CLOUDRUN_MOCK` accordingly.

### Fixed

- **native:** native-image build support for Cloud Run, Cloud Functions, KMS, and Cloud SQL
- **native:** registered Cloud Scheduler and Cloud Monitoring protos for native-image reflection
- **kafka:** seed read-write data volumes from the image (drop `nocopy`) so the Redpanda broker starts on a fresh volume
- **cloudsql:** set the PostgreSQL data mount path dynamically based on the instance's Postgres major version
- **core:** log the enabled-services summary after all services have registered

## [0.2.1] - 2026-06-03

### Fixed

- **gcs:** object compose now works — `POST /storage/v1/b/{bucket}/o/{object}/compose` was misrouted and returned `400 "Unsupported method override"`
- **gcs:** write and metadata-update preconditions are enforced — `ifGenerationMatch`, `ifGenerationNotMatch`, `ifMetagenerationMatch`, `ifMetagenerationNotMatch` now return `412` when not met (`ifGenerationMatch=0` means "only if absent")
- **gcs:** copy and rewrite preserve the source object's custom `metadata` on the destination
- **gcs:** object listings with a `delimiter` include the top-level `prefixes[]` array
- **gcs:** bucket creation accepts a JSON body regardless of the request `Content-Type` (no longer requires `application/json`)
- **gcs:** `gcloud storage cp` uploads work — added the bucket `storageLayout` endpoint (was `405`) and accept single-quoted multipart boundaries sent by the gcloud CLI
- **gcs:** object and bucket timestamps use microsecond precision, avoiding the gcloud CLI "truncating the datetime string" warning

## [0.2.0] - 2026-06-01

### Added

- **gcs:** object holds (temporary and event-based), bucket retention policy with lock, Batch API (`/batch/storage/v1`), Pub/Sub notification configs, object versioning, lifecycle rules, CORS, and V4 signed URLs
- **cloudtasks:** Cloud Tasks v2 gRPC service — queues and tasks
- **firestore:** `orderBy` sorting and `start_at`/`end_at` query cursors (`startAt`/`startAfter`/`endAt`/`endBefore`)
- **pubsub:** `DetachSubscription` — detaches a subscription from its topic and stops delivery
- **iam:** `UpdateServiceAccount` / `PatchServiceAccount` — update `displayName` and `description`
- **core:** legacy `errors[]` array (`domain`, `reason`) in REST error bodies for SDK retry/error inspection
- **compat:** Terraform and OpenTofu compatibility suites run in the CI matrix

### Fixed

- **kafka:** Docker image pull strategy and `/var/run/docker.sock` mounting for Managed Kafka sidecar orchestration

## [0.1.0] - 2026-05-23

### Added

- **core:** single-port HTTP/2 + gRPC via ALPN on port `4588`; gRPC and REST share one port with no split-server config
- **core:** `GcpException` with HTTP and gRPC status code mapping; `GcpExceptionMapper` for JAX-RS error responses
- **core:** `ProjectContextFilter` — extracts GCP project ID from URL path, `x-goog-request-params` header, or `FLOCI_GCP_DEFAULT_PROJECT_ID` fallback
- **core:** `GcpGrpcController` — abstract base class for gRPC service bindings with `GcpException` → `StatusRuntimeException` mapping
- **core:** `GcpResourceNames` — parses and builds `projects/{project}/...` resource name strings
- **core:** `ProjectAwareStorageBackend` — namespaces all storage keys by GCP project ID
- **core:** `GzipRequestFilter` — enables Vert.x server-side HTTP decompression for gzip-encoded request bodies sent by the Google Cloud Java SDK
- **core:** `ServiceRegistry` — tracks enabled services; `ServiceEnabledFilter` rejects requests when a service is disabled
- **storage:** four storage modes: `memory` (default), `persistent`, `hybrid`, `wal`
- **config:** `@ConfigMapping`-based `EmulatorConfig` under `floci-gcp.*`; all settings overridable via `FLOCI_GCP_*` env vars
- **gcs:** Cloud Storage REST API — buckets (create, get, list, patch, delete), objects (upload multipart/resumable/media, download, copy, list, delete), XML and JSON API paths, CRC32C + MD5 checksums
- **gcs:** `PATCH /storage/v1/b/{bucket}` — bucket update endpoint for label and metadata changes
- **gcs:** `labels` field on bucket create and patch (required for Terraform/OpenTofu `google_storage_bucket`)
- **pubsub:** Pub/Sub gRPC service — topics, subscriptions, publish, pull, acknowledge, streaming pull (`StreamingPull`)
- **secretmanager:** Secret Manager gRPC service — secrets, versions, access, disable/enable/destroy version, `versions/latest` resolution
- **firestore:** Firestore gRPC service — documents, collections, queries with filters, transactions, `Listen` streaming
- **datastore:** Datastore REST/JSON service — entities, lookup, runQuery, commit (upsert/insert/update/delete mutations), transactions
- **iam:** IAM REST service — service accounts (create, get, list, patch, delete), `getIamPolicy`, `setIamPolicy`, `testIamPermissions`
- **kafka:** Managed Kafka REST service — clusters, topics, consumer groups (Tier 1 + Tier 2); Redpanda-backed with Docker orchestration; mock mode for CI
- **compat:** SDK compatibility test suites in Java, Python, Node.js, and Go covering all 7 services (186 tests)
- **compat:** Terraform compatibility test suite (`compat-terraform/`) using GCP provider v6
- **compat:** OpenTofu compatibility test suite (`compat-opentofu/`) using GCP provider v6
- **docker:** JVM and native Docker images; `docker-compose.yml` with `/var/run/docker.sock` mount for Managed Kafka container orchestration
- **health:** `/_floci-gcp/health` and `/_floci-gcp/info` endpoints

### Fixed

- **gcs:** multipart upload now uses `?name=` query param as fallback when object name is absent from JSON metadata body — fixes `google_storage_bucket_object` with the Terraform GCP provider

---

[Unreleased]: https://github.com/floci-io/floci-gcp/compare/0.5.0...HEAD
[0.5.0]: https://github.com/floci-io/floci-gcp/compare/0.4.0...0.5.0
[0.4.0]: https://github.com/floci-io/floci-gcp/compare/0.3.0...0.4.0
[0.3.0]: https://github.com/floci-io/floci-gcp/compare/0.2.1...0.3.0
[0.2.1]: https://github.com/floci-io/floci-gcp/compare/0.2.0...0.2.1
[0.2.0]: https://github.com/floci-io/floci-gcp/compare/0.1.0...0.2.0
[0.1.0]: https://github.com/floci-io/floci-gcp/releases/tag/0.1.0
