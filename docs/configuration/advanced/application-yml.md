# application.yml Reference

!!! note "Source builds only"
    This page is for users who build floci-gcp from source or mount a custom `application.yml` into the container. **If you run the published Docker image, you don't need this file** — all settings are configured through `FLOCI_GCP_*` environment variables. See the [Environment Variables Reference](../environment-variables.md) for the complete list.

All settings can be provided as YAML (in `src/main/resources/application.yml`) or overridden via environment variables using the `FLOCI_GCP_` prefix with dots and dashes replaced by underscores.

## URL Configuration

floci-gcp generates absolute URLs for certain response fields (GCS object URLs, pre-signed URLs). Two settings control the hostname embedded in those URLs:

| Setting | Env variable | Default | Description |
|---|---|---|---|
| `floci-gcp.base-url` | `FLOCI_GCP_BASE_URL` | `http://localhost:4588` | Full base URL used to build response URLs |
| `floci-gcp.hostname` | `FLOCI_GCP_HOSTNAME` | _(none)_ | Override only the hostname in `base-url`. Useful in Docker Compose where `localhost` is unreachable from other containers |

When `floci-gcp.hostname` is set it replaces just the host portion of `base-url`, leaving the scheme and port unchanged. Setting `FLOCI_GCP_HOSTNAME: floci-gcp` is equivalent to changing `base-url` from `http://localhost:4588` to `http://floci-gcp:4588`.

**Example — Docker Compose multi-container setup:**

```yaml
environment:
  FLOCI_GCP_HOSTNAME: floci-gcp   # matches the compose service name
  FLOCI_GCP_BASE_URL: http://floci-gcp:4588
```

See [Docker Compose — Multi-container networking](../docker-compose.md#multi-container-networking) for a full example.

## Full Reference

The block below mirrors `src/main/resources/application.yml`.

```yaml
floci-gcp:
  port: 4588
  max-request-size: 512
  base-url: "http://localhost:4588"  # Used to build GCS object URLs and pre-signed URLs
  # hostname: ""                     # When set, overrides the host in base-url for multi-container Docker
  default-project-id: floci-local

  storage:
    mode: memory                      # memory | persistent | hybrid | wal
    persistent-path: ./data
    host-persistent-path: ./data
    prune-volumes-on-delete: false
    wal:
      compaction-interval-ms: 30000

  dns:
    # Extra hostname suffixes resolved to floci-gcp's container IP by the embedded DNS server.
    # Via env var (comma-separated): FLOCI_GCP_DNS_EXTRA_SUFFIXES=custom.internal,other.domain
    # extra-suffixes:
    #   - custom.internal

  docker:
    log-max-size: "10m"
    log-max-file: "3"
    docker-host: unix:///var/run/docker.sock
    docker-config-path: ""

  services:
    gcs:
      enabled: true

    pubsub:
      enabled: true

    firestore:
      enabled: true

    datastore:
      enabled: true

    secretmanager:
      enabled: true

    iam:
      enabled: true

    kafka:
      enabled: true
      mock: false
      default-image: "redpandadata/redpanda:latest"

    cloudsql:
      enabled: true

    cloudtasks:
      enabled: true

    cloudrun:
      enabled: true

    cloudfunctions:
      enabled: true
```

## Disabling Services

Set `enabled: false` for any service you don't need:

```yaml
floci-gcp:
  services:
    datastore:
      enabled: false
    iam:
      enabled: false
```

Via environment variable:

```bash
FLOCI_GCP_SERVICES_DATASTORE_ENABLED=false
FLOCI_GCP_SERVICES_IAM_ENABLED=false
```

## Logging

floci-gcp uses standard [Quarkus logging](https://quarkus.io/guides/logging). The default effective level is `INFO`. Services log operation-level events at `DEBUG` and full request/response payloads at `TRACE`.

**Enable TRACE for a service via environment variables:**

```bash
# Pub/Sub: log publish/pull bodies
QUARKUS_LOG_CATEGORY__IO_FLOCI_GCP_SERVICES_PUBSUB__LEVEL=TRACE

# Firestore: log read/write operations
QUARKUS_LOG_CATEGORY__IO_FLOCI_GCP_SERVICES_FIRESTORE__LEVEL=TRACE
```

**Or in `application.yml`:**

```yaml
quarkus:
  log:
    category:
      "io.floci.gcp.services.pubsub":
        level: TRACE
      "io.floci.gcp.services.firestore":
        level: TRACE
```
