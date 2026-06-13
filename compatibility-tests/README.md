# floci-gcp compatibility tests

Compatibility test suite for [floci-gcp](https://github.com/floci-io/floci-gcp) — a local GCP emulator.

Verifies that standard GCP tooling (SDKs, Terraform, OpenTofu) works correctly against the emulator without modification. Tests run against a live floci-gcp instance and use real GCP SDK clients — no mocks.

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

### IaC suites

| Module | Tool | Framework | Command |
|---|---|---|---|
| [`compat-terraform`](compat-terraform/) | Terraform + GCP provider v7.36 | BATS | `just test-terraform` |
| [`compat-opentofu`](compat-opentofu/) | OpenTofu + GCP provider v7.36 | BATS | `just test-opentofu` |

## Test Coverage

### SDK tests — 190 tests total

| Test class | GCP service | Java | Python | Node | Go |
|---|---|:---:|:---:|:---:|:---:|
| `GcsTest` | Cloud Storage | 5 | 6 | 9 | 9 |
| `PubSubTest` | Pub/Sub | 6 | 4 | 8 | 7 |
| `SecretManagerTest` | Secret Manager | 5 | 5 | 6 | 7 |
| `FirestoreTest` | Firestore | 5 | 5 | 6 | 5 |
| `DatastoreTest` | Datastore | 5 | 5 | 5 | 5 |
| `IamTest` | IAM | 7 | 5 | 7 | 7 |
| `KafkaTest` | Managed Kafka | 11 | 9 | 11 | 11 |
| `CloudSqlAdminTest` | Cloud SQL for PostgreSQL | 4 | 0 | 0 | 0 |
| **Total** | | **48** | **39** | **52** | **51** |

### IaC tests

| Suite | Resources tested |
|---|---|
| `compat-terraform` | GCS bucket (with labels), GCS object, IAM service account, Secret Manager secret/version, Cloud SQL PostgreSQL instance/database/user |
| `compat-opentofu` | GCS bucket (with labels), GCS object, IAM service account, Secret Manager secret/version, Cloud SQL PostgreSQL instance/database/user |

Each IaC suite runs: `init` → `validate` → `plan` → `apply` → BATS spot-checks → `destroy`.

## Prerequisites

- **floci-gcp running** on `http://localhost:4588` (or set `FLOCI_GCP_ENDPOINT`)
- **Java 21+** and **Maven** — for `sdk-test-java`
- **Python 3.9+** — for `sdk-test-python`
- **Node.js 18+** — for `sdk-test-node`
- **Go 1.21+** — for `sdk-test-go`
- **just** — task runner
- **terraform** — for `compat-terraform` BATS tests; use a CLI compatible with `hashicorp/google` v7.36
- **tofu** — for `compat-opentofu` BATS tests; use a CLI compatible with `hashicorp/google` v7.36
- **bats-core** — for IaC BATS tests (`brew install bats-core`)

## Configuration

All modules read from environment variables (see `env.example`):

```bash
FLOCI_GCP_ENDPOINT=http://localhost:4588
FLOCI_GCP_PROJECT=test-project
PUBSUB_EMULATOR_HOST=localhost:4588
FIRESTORE_EMULATOR_HOST=localhost:4588
DATASTORE_EMULATOR_HOST=localhost:4588
STORAGE_EMULATOR_HOST=http://localhost:4588
SECRET_MANAGER_EMULATOR_HOST=localhost:4588
```

| Variable | Service | Format |
|---|---|---|
| `PUBSUB_EMULATOR_HOST` | Pub/Sub | `host:port` |
| `FIRESTORE_EMULATOR_HOST` | Firestore | `host:port` |
| `DATASTORE_EMULATOR_HOST` | Datastore | `host:port` |
| `STORAGE_EMULATOR_HOST` | Cloud Storage | `http://host:port` |
| `SECRET_MANAGER_EMULATOR_HOST` | Secret Manager | `host:port` |

IAM and Managed Kafka have no standard GCP emulator env var — tests connect via `FLOCI_GCP_ENDPOINT` directly.

## Running with Docker

The Java module includes a `Dockerfile` for isolated execution:

```bash
docker build -t floci-gcp-sdk-java sdk-test-java/
docker run --rm --network host floci-gcp-sdk-java
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

## IaC suites — notes

The Terraform and OpenTofu GCP provider does **not** respect `STORAGE_EMULATOR_HOST` or `PUBSUB_EMULATOR_HOST` for resource management. The suites configure explicit custom endpoints in `provider.tf`:

```hcl
provider "google" {
  storage_custom_endpoint        = "${var.endpoint}/storage/v1/"
  iam_custom_endpoint            = "${var.endpoint}/"
  iam_beta_custom_endpoint       = "${var.endpoint}/v1/"
  secret_manager_custom_endpoint = "${var.endpoint}/v1/"
  sql_custom_endpoint            = "${var.endpoint}/sql/v1beta4/"
}
```

Auth is bypassed via `GOOGLE_OAUTH_ACCESS_TOKEN=fake-token-floci-gcp`.

Pub/Sub resources (`google_pubsub_topic`, `google_pubsub_subscription`) are not yet supported — the Terraform provider uses REST while our Pub/Sub is gRPC-only.

## Exit Codes

All test runners exit `0` on full pass and non-zero if any test fails — suitable for CI pipelines.
