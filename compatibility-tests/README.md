# floci-gcp compatibility tests

Compatibility test suite for [floci-gcp](https://github.com/floci-io/floci-gcp), a local GCP emulator.

Exercises supported operations through standard GCP SDKs, gcloud, Terraform, and OpenTofu after their endpoints are configured for the emulator. Tests run against a live floci-gcp instance. SDK suites use official clients, while CI may enable a service's documented mock mode when a real sidecar data plane is outside that suite's scope.

## Quick Start

```bash
# Install just (task runner)
# macOS: brew install just

# Copy and configure environment
cp env.example .env

# Install dependencies for all SDK suites
just setup

# Run all SDK tests
just test-all

# Run IaC tests (requires terraform and tofu CLIs)
just test-all-iac
```

## Test Suites

### SDK suites

| Module | Language | Framework | Command |
|---|---|---|---|
| [`sdk-test-java`](sdk-test-java/) | Java 21 | JUnit 5 | `just test-java` |
| [`sdk-test-python`](sdk-test-python/) | Python 3 | pytest | `just test-python` |
| [`sdk-test-node`](sdk-test-node/) | Node.js / TypeScript | vitest | `just test-node` |
| [`sdk-test-go`](sdk-test-go/) | Go | go test | `just test-go` |
| [`sdk-test-rust`](sdk-test-rust/) | Rust | cargo test | `just test-rust` |

### CLI suites

| Module | Tool | Framework | Command |
|---|---|---|---|
| [`sdk-test-gcloud`](sdk-test-gcloud/) | gcloud CLI | BATS | `just test-gcloud` |

### IaC suites

| Module | Tool | Framework | Command |
|---|---|---|---|
| [`compat-terraform`](compat-terraform/) | Terraform + GCP provider v7.36 | BATS | `just test-terraform` |
| [`compat-opentofu`](compat-opentofu/) | OpenTofu + GCP provider v7.36 | BATS | `just test-opentofu` |

## Test Coverage

### SDK tests

Exact test counts change frequently. The checked-in suites currently cover:

| Suite | Coverage |
|---|---|
| Java | GCS REST and gRPC, Pub/Sub, Secret Manager, Logging, KMS, Monitoring, Firestore, Datastore, IAM and IAM Credentials, STS, Managed Kafka, GKE, Cloud SQL, Cloud Run, Cloud Functions, Cloud Tasks, Cloud Scheduler, Eventarc, Service Usage, Firebase Auth, BigQuery, and TLS |
| Python | GCS, Pub/Sub, Secret Manager, Logging, KMS, Firestore, Datastore, IAM, and Managed Kafka |
| Node.js | GCS, Pub/Sub, Secret Manager, Logging, KMS, Firestore, Datastore, IAM, and Managed Kafka |
| Go | GCS REST and gRPC, Pub/Sub, Secret Manager, Logging, KMS, Firestore, Datastore, IAM, and Managed Kafka |
| Rust | GCS media upload and download through the official Rust client |

GKE uses the HttpJson transport (the Cloud SDK defaults to gRPC, which the REST-only
emulator does not serve for GKE) and reaches the service via host-based routing
(`container.*`). The gcloud suite also covers GKE (`container.bats`). The
Terraform/OpenTofu `google_container_cluster` resource is not covered because the google provider
expects a richer cluster surface than the emulator implements.

### IaC tests

| Suite | Resources tested |
|---|---|
| `compat-terraform` | GCS bucket and objects, IAM service account and policies, Secret Manager secret/version and IAM, Cloud Run v2 create/update/invoke with a GCS volume mount, Cloud SQL PostgreSQL instance/database/user, KMS key ring and crypto key, Pub/Sub topic/subscription and IAM, Service Usage |
| `compat-opentofu` | GCS bucket and objects, IAM service account and policies, Secret Manager secret/version and IAM, Cloud Run v2 create/update/invoke with a GCS volume mount, Cloud SQL PostgreSQL instance/database/user, KMS key ring and crypto key, Pub/Sub topic/subscription and IAM, Service Usage |

Each IaC suite runs: `init`, `validate`, `plan`, `apply`, BATS spot-checks, then `destroy`.

## Prerequisites

- **floci-gcp running** on `http://localhost:4588` (or set `FLOCI_GCP_ENDPOINT`)
- **Java 21+** and **Maven**: for `sdk-test-java`
- **Python 3.9+**: for `sdk-test-python`
- **Node.js 18+**: for `sdk-test-node`
- **Go 1.21+**: for `sdk-test-go`
- **Rust** and **Cargo**: for `sdk-test-rust`
- **just**: task runner
- **terraform**: for `compat-terraform` BATS tests; use a CLI compatible with `hashicorp/google` v7.36
- **tofu**: for `compat-opentofu` BATS tests; use a CLI compatible with `hashicorp/google` v7.36
- **bats-core**: for IaC BATS tests (`brew install bats-core`)
- **Docker**: for tests of Docker-backed services. The emulator must use `FLOCI_GCP_SERVICES_CLOUDRUN_MOCK=false` for Cloud Run execution, and the compatibility tests run their execution assertions when `FLOCI_GCP_CLOUDRUN_EXECUTION_ENABLED=true`

## Configuration

All modules read from environment variables (see `env.example`):

```bash
FLOCI_GCP_ENDPOINT=http://localhost:4588
FLOCI_GCP_PROJECT=test-project
PUBSUB_EMULATOR_HOST=localhost:4588
FIRESTORE_EMULATOR_HOST=localhost:4588
DATASTORE_EMULATOR_HOST=localhost:4588
STORAGE_EMULATOR_HOST=http://localhost:4588
STORAGE_EMULATOR_HOST_GRPC=localhost:4588
SECRET_MANAGER_EMULATOR_HOST=localhost:4588
# Test-harness flag, not an emulator setting
FLOCI_GCP_CLOUDRUN_EXECUTION_ENABLED=true
```

| Variable | Service | Format |
|---|---|---|
| `PUBSUB_EMULATOR_HOST` | Pub/Sub | `host:port` |
| `FIRESTORE_EMULATOR_HOST` | Firestore | `host:port` |
| `DATASTORE_EMULATOR_HOST` | Datastore | `host:port` |
| `STORAGE_EMULATOR_HOST` | Cloud Storage | `http://host:port` |
| `STORAGE_EMULATOR_HOST_GRPC` | Cloud Storage gRPC v2 | `host:port` |
| `SECRET_MANAGER_EMULATOR_HOST` | Secret Manager | `host:port` |
| `FLOCI_GCP_CLOUDRUN_EXECUTION_ENABLED` | Cloud Run compatibility tests | `true` runs assertions against the Docker-backed runtime; this does not configure the emulator |

IAM, Cloud Logging, Cloud KMS, and Managed Kafka have no standard GCP emulator env var. Tests connect via `FLOCI_GCP_ENDPOINT` directly. Cloud Logging and Cloud KMS may optionally be overridden with `LOGGING_EMULATOR_HOST` and `KMS_EMULATOR_HOST`, respectively.

## TLS

`docker-compose.yml`, `make run`, and CI start floci-gcp with
`FLOCI_GCP_TLS_ENABLED=true`. `make compat-docker` runs against the emulator already
started by `docker compose up -d`, which uses that setting. Because HTTP and HTTPS share
port 4588, suite endpoint settings remain unchanged and clients continue using plaintext
HTTP or gRPC. This makes the whole suite a regression guard for the protocol-sniffing proxy.

On top of that, `sdk-test-java`'s `TlsTest` exercises TLS directly: HTTPS through the GCS
client, gRPC-over-TLS through Pub/Sub and Secret Manager, and a plaintext gRPC call to
prove the plaintext path still works while TLS is on. It derives `https://` from
`FLOCI_GCP_ENDPOINT` itself and fetches the emulator's certificate from
`GET /_floci-gcp/tls-cert` over plain HTTP, so trust is established at runtime. Nothing is
bundled with the suite and no verification is disabled.

`TlsTest` skips itself when that endpoint returns 404, so running against a plaintext
emulator is fine.

Note that the `*_EMULATOR_HOST` variables cannot be used to test TLS: the GCP SDKs call
`usePlaintext()` whenever one is set, regardless of scheme. That is why `TlsTest` builds
its own clients.

## Running with Docker

Every suite includes a Dockerfile for CI and isolated execution. For example:

```bash
docker build -t floci-gcp-sdk-java sdk-test-java/
docker run --rm --network host \
  --env-file env.example \
  --add-host container.localhost.floci.io:127.0.0.1 \
  floci-gcp-sdk-java
```

Build the Go SDK compatibility image, including the Cloud Storage gRPC v2 tests:

```bash
docker build -t floci-gcp-sdk-test-go:gcs-grpc sdk-test-go/
mkdir -p test-results
docker run --rm --network host \
  --env-file env.example \
  -v "$(pwd)/test-results:/results" \
  floci-gcp-sdk-test-go:gcs-grpc
```

On macOS/Windows, use `host.docker.internal`:

```bash
docker run --rm \
  -e FLOCI_GCP_ENDPOINT=http://host.docker.internal:4588 \
  -e PUBSUB_EMULATOR_HOST=host.docker.internal:4588 \
  -e FIRESTORE_EMULATOR_HOST=host.docker.internal:4588 \
  -e DATASTORE_EMULATOR_HOST=host.docker.internal:4588 \
  -e STORAGE_EMULATOR_HOST=http://host.docker.internal:4588 \
  floci-gcp-sdk-java
```

For the Go image on macOS/Windows, also override the gRPC endpoint:

```bash
docker run --rm \
  -e FLOCI_GCP_ENDPOINT=http://host.docker.internal:4588 \
  -e STORAGE_EMULATOR_HOST=http://host.docker.internal:4588 \
  -e STORAGE_EMULATOR_HOST_GRPC=host.docker.internal:4588 \
  -v "$(pwd)/test-results:/results" \
  floci-gcp-sdk-test-go:gcs-grpc
```

## IaC suites: notes

The Terraform and OpenTofu GCP provider does **not** respect `STORAGE_EMULATOR_HOST` or `PUBSUB_EMULATOR_HOST` for resource management. The suites configure explicit custom endpoints in `provider.tf`:

```hcl
provider "google" {
  storage_custom_endpoint        = "${var.endpoint}/storage/v1/"
  iam_custom_endpoint            = "${var.endpoint}/"
  iam_beta_custom_endpoint       = "${var.endpoint}/v1/"
  secret_manager_custom_endpoint = "${var.endpoint}/v1/"
  cloud_run_custom_endpoint      = "${var.endpoint}/v2/"
  cloud_run_v2_custom_endpoint   = "${var.endpoint}/v2/"
  sql_custom_endpoint            = "${var.endpoint}/sql/v1beta4/"
  kms_custom_endpoint            = "${var.endpoint}/v1/"
  pubsub_custom_endpoint         = "${var.endpoint}/v1/"
  service_usage_custom_endpoint  = "${var.endpoint}/v1/"
  resource_manager_custom_endpoint = "${var.endpoint}/v1/"
}
```

Auth is bypassed via `GOOGLE_OAUTH_ACCESS_TOKEN=fake-token-floci-gcp`.

Both IaC suites cover Pub/Sub topics, subscriptions, and IAM through the emulator's REST JSON surface.

## Exit Codes

All test runners exit `0` on full pass and non-zero if any test fails, which is suitable for CI pipelines.
