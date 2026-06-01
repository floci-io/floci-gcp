# Running with Docker

floci-gcp is distributed as a Docker image. All configuration is done through environment variables — no config files or volume-mounted YAML is required.

## Quick Start

```bash
docker run --rm -p 4588:4588 floci/floci-gcp:latest
```

All GCP services are immediately available at `http://localhost:4588`.

## Docker Compose

### Minimal (stateless)

```yaml title="docker-compose.yml"
services:
  floci-gcp:
    image: floci/floci-gcp:latest
    ports:
      - "4588:4588"
    environment:
      FLOCI_GCP_HOSTNAME: floci-gcp
      FLOCI_GCP_BASE_URL: http://floci-gcp:4588
```

### With persistence

```yaml title="docker-compose.yml"
services:
  floci-gcp:
    image: floci/floci-gcp:latest
    ports:
      - "4588:4588"
    volumes:
      - floci-gcp-data:/app/data
    environment:
      FLOCI_GCP_HOSTNAME: floci-gcp
      FLOCI_GCP_BASE_URL: http://floci-gcp:4588
      FLOCI_GCP_STORAGE_MODE: hybrid
      FLOCI_GCP_STORAGE_PERSISTENT_PATH: /app/data

volumes:
  floci-gcp-data:
```

## Multi-container Networking

By default floci-gcp embeds `localhost` in response URLs — for example, GCS object URLs look like `http://localhost:4588/my-bucket/my-object`. This works when your application runs on the same machine, but breaks inside Docker Compose because other containers cannot reach `localhost` of the floci-gcp container.

Set `FLOCI_GCP_HOSTNAME` to the Compose service name and `FLOCI_GCP_BASE_URL` to the full URL so floci-gcp uses that name in every URL it generates:

```yaml title="docker-compose.yml"
services:
  floci-gcp:
    image: floci/floci-gcp:latest
    ports:
      - "4588:4588"
    environment:
      FLOCI_GCP_HOSTNAME: floci-gcp       # (1)
      FLOCI_GCP_BASE_URL: http://floci-gcp:4588

  my-app:
    build: .
    environment:
      PUBSUB_EMULATOR_HOST: floci-gcp:4588
      FIRESTORE_EMULATOR_HOST: floci-gcp:4588
      DATASTORE_EMULATOR_HOST: floci-gcp:4588
      STORAGE_EMULATOR_HOST: http://floci-gcp:4588
      SECRET_MANAGER_EMULATOR_HOST: floci-gcp:4588
    depends_on:
      - floci-gcp
```

1. Must match the Compose service name so other containers can resolve it by DNS.

!!! tip "CI pipelines"
    In GitHub Actions or GitLab CI where both your app and floci-gcp run as `services`, set `FLOCI_GCP_HOSTNAME` to the service name (e.g. `floci-gcp`) and point your GCP SDKs at `floci-gcp:4588`.

## CI Pipeline Example

```yaml title=".github/workflows/test.yml"
services:
  floci-gcp:
    image: floci/floci-gcp:latest
    ports:
      - "4588:4588"

steps:
  - name: Run tests
    env:
      PUBSUB_EMULATOR_HOST: localhost:4588
      FIRESTORE_EMULATOR_HOST: localhost:4588
      DATASTORE_EMULATOR_HOST: localhost:4588
      STORAGE_EMULATOR_HOST: http://localhost:4588
      SECRET_MANAGER_EMULATOR_HOST: localhost:4588
    run: ./mvnw test
```

## Common Environment Variables

| Variable | Default | Purpose |
|---|---|---|
| `FLOCI_GCP_HOSTNAME` | _(none)_ | Hostname embedded in response URLs. Set to the Compose service name in multi-container setups |
| `FLOCI_GCP_BASE_URL` | `http://localhost:4588` | Full base URL for generated URLs |
| `FLOCI_GCP_DEFAULT_PROJECT_ID` | `floci-local` | Default GCP project ID |
| `FLOCI_GCP_STORAGE_MODE` | `memory` | `memory`, `persistent`, `hybrid`, or `wal` |
| `FLOCI_GCP_STORAGE_PERSISTENT_PATH` | `./data` | Directory for persistent storage |

For the complete list see [Environment Variables Reference](./environment-variables.md).
