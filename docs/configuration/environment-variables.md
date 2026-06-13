# Environment Variables Reference

floci-gcp is configured entirely through environment variables. Every setting maps to a `FLOCI_GCP_*` variable, so when you run the published Docker image you never need to write or mount an `application.yml`.

Variable names follow the config path, uppercased with dots and dashes replaced by underscores — e.g. `floci-gcp.services.gcs.enabled` becomes `FLOCI_GCP_SERVICES_GCS_ENABLED`.

---

## Global

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_PORT` | `4588` | Port for all services (gRPC + REST, single port) |
| `FLOCI_GCP_BASE_URL` | `http://localhost:4588` | Base URL embedded in service responses (GCS object URLs, pre-signed URLs, etc.) |
| `FLOCI_GCP_HOSTNAME` | _(none)_ | Overrides only the hostname part of `FLOCI_GCP_BASE_URL`. Set to the Compose/container service name so other containers can reach floci-gcp by DNS |
| `FLOCI_GCP_DEFAULT_PROJECT_ID` | `floci-local` | Default GCP project ID used when no project is specified in the request |
| `FLOCI_GCP_MAX_REQUEST_SIZE` | `512` | Maximum request body size, in **megabytes** (applies to uploads, e.g. GCS objects) |

---

## Storage

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_STORAGE_MODE` | `memory` | Global storage backend: `memory`, `persistent`, `hybrid`, or `wal` |
| `FLOCI_GCP_STORAGE_PERSISTENT_PATH` | `./data` | Container-side directory for persistent and hybrid storage |
| `FLOCI_GCP_STORAGE_HOST_PERSISTENT_PATH` | `./data` | Host-side path that maps to the persistent directory. Used when floci-gcp spawns sidecar containers (Docker-in-Docker) that need to bind-mount the same data |
| `FLOCI_GCP_STORAGE_PRUNE_VOLUMES_ON_DELETE` | `false` | Remove the backing volume/data when a resource is deleted |
| `FLOCI_GCP_STORAGE_WAL_COMPACTION_INTERVAL_MS` | `30000` | How often (ms) WAL compaction runs. Only applies when `FLOCI_GCP_STORAGE_MODE=wal` |

See [Storage Modes](./storage.md) for a full explanation of each mode.

---

## DNS

floci-gcp's embedded DNS server runs inside the container and resolves GCS virtual-hosted style URLs to floci-gcp's container IP. It only activates when running inside Docker.

| Built-in suffix | Covers |
|---|---|
| `localhost.floci.io` | `localhost.floci.io` and `*.localhost.floci.io` (e.g. `my-bucket.localhost.floci.io`) |

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_DNS_EXTRA_SUFFIXES` | _(none)_ | Comma-separated list of additional hostname suffixes to resolve to floci-gcp's container IP |

---

## Services

Each service can be toggled independently. All are enabled by default.

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_GCS_ENABLED` | `true` | Cloud Storage (GCS) |
| `FLOCI_GCP_SERVICES_PUBSUB_ENABLED` | `true` | Pub/Sub |
| `FLOCI_GCP_SERVICES_FIRESTORE_ENABLED` | `true` | Firestore |
| `FLOCI_GCP_SERVICES_DATASTORE_ENABLED` | `true` | Datastore |
| `FLOCI_GCP_SERVICES_SECRETMANAGER_ENABLED` | `true` | Secret Manager |
| `FLOCI_GCP_SERVICES_IAM_ENABLED` | `true` | IAM |
| `FLOCI_GCP_SERVICES_CLOUDTASKS_ENABLED` | `true` | Cloud Tasks |
| `FLOCI_GCP_SERVICES_KAFKA_ENABLED` | `true` | Managed Service for Apache Kafka |
| `FLOCI_GCP_SERVICES_CLOUDSQL_ENABLED` | `true` | Cloud SQL for PostgreSQL |
| `FLOCI_GCP_SERVICES_CLOUDRUN_ENABLED` | `true` | Cloud Run |
| `FLOCI_GCP_SERVICES_CLOUDFUNCTIONS_ENABLED` | `true` | Cloud Functions |

### Sidecar containers

Some services (e.g. Managed Kafka) start real sidecar containers via the host Docker daemon. These variables control how those containers are networked.

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_DOCKER_NETWORK` | _(none)_ | Shared Docker network attached to spawned sidecar containers so floci-gcp and your SDKs can reach them by name. Set this to your Compose/CI network |

### Managed Kafka

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_KAFKA_MOCK` | `false` | When `true`, emulate the Kafka control plane only — no Redpanda broker container is started |
| `FLOCI_GCP_SERVICES_KAFKA_DEFAULT_IMAGE` | `redpandadata/redpanda:latest` | Broker image used for spawned Kafka clusters |
| `FLOCI_GCP_SERVICES_KAFKA_DOCKER_NETWORK` | _(none)_ | Overrides `FLOCI_GCP_SERVICES_DOCKER_NETWORK` for Kafka sidecars only |

---

## Initialization Hooks

Control the shell environment used to run initialization hook scripts.

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_INIT_HOOKS_SHELL_EXECUTABLE` | `/bin/sh` | Shell used to execute init hook scripts |
| `FLOCI_GCP_INIT_HOOKS_TIMEOUT_SECONDS` | `30` | Max time a single hook may run before it is killed |
| `FLOCI_GCP_INIT_HOOKS_SHUTDOWN_GRACE_PERIOD_SECONDS` | `2` | Grace period given to hook processes on shutdown |

---

## Docker Daemon

These variables control the Docker daemon used by floci-gcp's embedded DNS and sidecar container management.

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_DOCKER_DOCKER_HOST` | `unix:///var/run/docker.sock` | Docker daemon socket path or TCP address |
| `FLOCI_GCP_DOCKER_DOCKER_CONFIG_PATH` | _(none)_ | Path to a directory containing Docker's `config.json` for registry auth |
| `FLOCI_GCP_DOCKER_LOG_MAX_SIZE` | `10m` | Log rotation max size for spawned containers |
| `FLOCI_GCP_DOCKER_LOG_MAX_FILE` | `3` | Number of rotated log files to keep |

---

## Logging

floci-gcp uses standard [Quarkus logging](https://quarkus.io/guides/logging) — also driven by environment variables. The default level is `INFO`; services log operation-level events at `DEBUG` and full request/response payloads at `TRACE`.

Enable `TRACE` for a single service by setting its category level (note the double underscores around the category):

```bash
# Pub/Sub: log publish/pull bodies
QUARKUS_LOG_CATEGORY__IO_FLOCI_GCP_SERVICES_PUBSUB__LEVEL=TRACE

# Firestore: log read/write operations
QUARKUS_LOG_CATEGORY__IO_FLOCI_GCP_SERVICES_FIRESTORE__LEVEL=TRACE
```

Set the global level with `QUARKUS_LOG_LEVEL` (e.g. `QUARKUS_LOG_LEVEL=DEBUG`).
