# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/floci-io/floci-gcp/compare/0.2.0...HEAD
[0.2.0]: https://github.com/floci-io/floci-gcp/compare/0.1.0...0.2.0
[0.1.0]: https://github.com/floci-io/floci-gcp/releases/tag/0.1.0
