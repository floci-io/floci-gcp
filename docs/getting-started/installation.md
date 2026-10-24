# Installation

floci-gcp can be run as a Docker image or built from source.

## Docker (Recommended)

No installation required beyond Docker itself.

```bash
docker pull floci/floci-gcp:latest
```

### Requirements

- Docker 20.10+
- `docker compose` v2+ (plugin syntax, not standalone `docker-compose`)

## Image Tags

| Tag | Description |
|---|---|
| `latest` | Current stable release (native image: fast startup, low memory) |
| `x.y.z` | Pinned release |
| `nightly` | Latest nightly build (floating) |
| `nightly-mmddyyyy` | Pinned nightly |
| `latest-compat` / `x.y.z-compat` | The same native emulator plus a toolbox: the gcloud CLI (with `bq`), the Python Cloud client libraries and `gcplocal` (see [The compat image](#the-compat-image)) |
| `nightly-compat` / `nightly-mmddyyyy-compat` | Nightly build of the compat image |

## Choosing a tag

```yaml title="docker-compose.yml"
# Standard release: recommended for most use cases
services:
  floci-gcp:
    image: floci/floci-gcp:latest
    ports:
      - "4588:4588"
```

## The compat image

The `-compat` tags run the same native emulator as `latest`, and add tools for talking to it
from inside the container:

- the gcloud CLI with `bq`, running on the image's Python 3.12; `gsutil` is not included, use
  `gcloud storage` instead
- the Python client libraries for Pub/Sub, Cloud Storage, Firestore, Datastore and Secret Manager
- `gcplocal`, which points gcloud and the client libraries at the emulator

```bash
docker run -d --name floci-gcp -p 4588:4588 floci/floci-gcp:latest-compat
docker exec floci-gcp gcplocal storage buckets create gs://my-bucket
docker exec floci-gcp gcplocal secrets list
```

### gcplocal

`gcplocal <command>` runs `gcloud <command>` against the emulator. gcloud ignores the
`*_EMULATOR_HOST` variables, so `gcplocal` sets a per-service endpoint override, a placeholder
access token (floci-gcp never checks credentials) and turns prompts off. `gcplocal env` prints the
same settings as `export` lines, and `gcplocal env --unset` prints the matching `unset` lines:

```bash
eval "$(gcplocal env)"           # configure the current shell
eval "$(gcplocal env --unset)"   # undo it
```

| Variable | Value |
|---|---|
| `FLOCI_GCP_ENDPOINT` | Where the emulator listens; default `http://localhost:4588` |
| `FLOCI_GCP_PROJECT` | Project for gcloud and the libraries; default `floci-local` |
| `PUBSUB_EMULATOR_HOST`, `FIRESTORE_EMULATOR_HOST`, `DATASTORE_EMULATOR_HOST`, `SECRET_MANAGER_EMULATOR_HOST` | The emulator's `host:port` |
| `STORAGE_EMULATOR_HOST` | The emulator endpoint |
| `CLOUDSDK_CORE_PROJECT` | The project above |
| `CLOUDSDK_AUTH_ACCESS_TOKEN` | `floci-fake-token` |
| `CLOUDSDK_CORE_DISABLE_PROMPTS`, `CLOUDSDK_CORE_SHOULD_PROMPT_TO_ENABLE_API` | `1`, `false` |
| `CLOUDSDK_API_ENDPOINT_OVERRIDES_STORAGE` | `<endpoint>/storage/v1/` |
| `CLOUDSDK_API_ENDPOINT_OVERRIDES_CONTAINER` | `<endpoint>/container/` |
| `CLOUDSDK_API_ENDPOINT_OVERRIDES_SECRETMANAGER`, `_CLOUDKMS`, `_IAM`, `_CLOUDSCHEDULER`, `_SERVICEUSAGE` | `<endpoint>/` |

Set `FLOCI_GCP_ENDPOINT` and `FLOCI_GCP_PROJECT` to change the inputs the other values are built
from. Any other variable already set in your environment wins over its default. gcloud reaches floci-gcp only for
the services above, the ones the gcloud compatibility suite covers; set further
`CLOUDSDK_API_ENDPOINT_OVERRIDES_*` variables yourself for other services.

### Creating resources at startup

`gcplocal` also works in [initialization hooks](../configuration/environment-variables.md#initialization-hooks).
A script in `/etc/floci-gcp/init/ready.d/` runs once the emulator is ready, for example to create a
public bucket with CORS:

```sh title="init/ready.d/10-buckets.sh"
#!/bin/sh
set -e
gcplocal storage buckets create gs://my-bucket
printf '[{"origin":["*"],"method":["GET"],"responseHeader":["Content-Type"],"maxAgeSeconds":3600}]' > /tmp/cors.json
gcplocal storage buckets update gs://my-bucket --cors-file=/tmp/cors.json
gcplocal storage buckets add-iam-policy-binding gs://my-bucket \
  --member=allUsers --role=roles/storage.objectViewer
```

```bash
docker run -d -p 4588:4588 -v "$PWD/init/ready.d:/etc/floci-gcp/init/ready.d:ro" floci/floci-gcp:latest-compat
```

Each gcloud command takes a few seconds to start, and a hook is stopped after
`FLOCI_GCP_INIT_HOOKS_TIMEOUT_SECONDS` (30 seconds by default), so raise it for longer scripts.

## Build from Source

### Prerequisites

- Java 25+
- Maven 3.9+
- (Optional) GraalVM Mandrel for native compilation

### Clone and run

```bash
git clone https://github.com/floci-io/floci-gcp.git
cd floci-gcp
./mvnw quarkus:dev          # dev mode with hot reload on port 4588
```

### Build a production JAR

```bash
./mvnw clean package -DskipTests
java -jar target/quarkus-app/quarkus-run.jar
```

### Build a native executable

```bash
./mvnw clean package -Pnative -DskipTests
./target/floci-gcp-*-runner
```

!!! note
    Native compilation requires GraalVM or Mandrel with the `native-image` tool on your PATH. Build time is typically 2–5 minutes.
