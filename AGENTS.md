Guidance for AI coding agents working in the floci-gcp-ctf repository.

**floci-gcp-ctf** is a security-hardened fork of [floci-gcp](https://github.com/floci-io/floci-gcp) for CTF and security exercises. It is not stock upstream `floci/floci-gcp:latest`.

Human-readable fork summary: [README.md](./README.md). IAM detail: [docs/services/iam.md](./docs/services/iam.md#ctf-hardening).

This file defines repository-specific operating rules for autonomous or semi-autonomous coding agents. Follow these instructions unless a maintainer explicitly tells you otherwise.

---

## CTF fork overview

| Item | Value |
|---|---|
| Language | Java 25 |
| Framework | Quarkus 3.34.6 |
| Port | 4588 (gRPC + REST, single port) |
| Config prefix | `floci-gcp.*` / `FLOCI_GCP_*` |
| Image tag (local) | build from this repo |

**First principles (override upstream defaults when they conflict):**

1. Preserve CTF hardening on upstream merges: IAM enforcement, strict mode, Bearer token validation, hide internal endpoints.
2. Match GCP semantics where floci-gcp can model them (auth, IAM allow policies, service account APIs).
3. Keep diffs narrow; reuse `GcpException`, `StorageFactory`, protocol controllers.
4. Ship docs and tests with behavior changes (`README.md`, this file, `docs/services/iam.md` when IAM changes).
5. Prioritize Stage 0 CTF surface: token validation, IAM enforcement, operator root, internal route hide.

**Fork-only types:** `TokenValidationFilter`, `IamEnforcementFilter`, `CtfInternalEndpointFilter`, `ContainerEnvHardening`, `TokenRegistry`, `OperatorRootAuth`.

### Wave 3 IAM surface (Firestore / Datastore / Eventarc / Resource Manager / gRPC)

| Service | REST IAM | Notes |
|---------|----------|-------|
| Firestore | gRPC via `IamEnforcementGrpcInterceptor` → `datastore.entities.*` | Same Datastore entity roles (`roles/datastore.viewer` / `user`) |
| Datastore | HTTP `POST /v1/projects/{p}:{method}` and gRPC via interceptor → `datastore.entities.*` | HTTP and gRPC both gated |
| Pub/Sub | REST + gRPC (`Publisher` / `Subscriber`) via interceptor → `pubsub.topics.*` / `pubsub.subscriptions.*` / `pubsub.snapshots.*` | Same roles as REST (`publisher` / `subscriber` / `admin`) |
| Secret Manager | REST + gRPC (`SecretManagerService`) via interceptor → `secretmanager.secrets.*` / `versions.*` | Same roles as REST (`secretAccessor` / `admin`) |
| Eventarc | triggers / channels / providers → `eventarc.*` | `roles/eventarc.viewer`, `roles/eventarc.admin` |
| Resource Manager | `GET /v1/projects/{p}` → `resourcemanager.projects.get`, plus `:getIamPolicy` / `:setIamPolicy` mapped | getIamPolicy/setIamPolicy not fully implemented |
| GKE control plane | `/container/v1` unmapped under strict | Non-operator denied when strict enforcement is on. kubectl webhook: operator root -> `system:masters`, players -> `system:authenticated` only |

See [docs/services/firestore.md](./docs/services/firestore.md#ctf-fork), [datastore.md](./docs/services/datastore.md#ctf-fork), [pubsub.md](./docs/services/pubsub.md#ctf-fork), [secret-manager.md](./docs/services/secret-manager.md#ctf-fork), [eventarc.md](./docs/services/eventarc.md#ctf-fork), [gke.md](./docs/services/gke.md#ctf-fork), and Resource Manager notes in [service-usage.md](./docs/services/service-usage.md#ctf-fork-resource-manager). Regression tests: `DatastoreIamEnforcementIntegrationTest`, `DatastoreGrpcIamEnforcementIntegrationTest`, `FirestoreIamEnforcementIntegrationTest`, `PubSubIamEnforcementIntegrationTest`, `PubSubGrpcIamEnforcementIntegrationTest`, `SecretManagerIamEnforcementIntegrationTest`, `SecretManagerGrpcIamEnforcementIntegrationTest`, `EventarcIamEnforcementIntegrationTest`, `ResourceManagerIamEnforcementIntegrationTest`, `GkeTokenWebhookCtfIntegrationTest`.

### Operator vs participant

| Role | Credentials | Behavior |
|------|-------------|----------|
| **Operator** | `FLOCI_GCP_AUTH_ROOT_SERVICE_ACCOUNT` + `FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN` | Bypasses IAM enforcement for provisioning. Must present matching Bearer token. Resolved via `OperatorRootAuth` only (root token is not in `TokenRegistry`). On GKE webhook: `system:masters`. Never give to players. |
| **Participant** | Token registered in `TokenRegistry` with IAM allow bindings | Subject to Bearer validation + IAM. Missing or unregistered tokens fail when validation is on. On GKE webhook: `system:authenticated` only (not masters). |

**Fail-closed IAM:** When `FLOCI_GCP_SERVICES_IAM_ENFORCEMENT_ENABLED=true`, requests without an authenticated principal are denied (REST and gRPC). Strict mode additionally denies unmapped permissions.

**ContainerEnvHardening:** Strips credential and operator bypass keys from participant container env before Docker launch. Blocked set includes `GOOGLE_OAUTH_ACCESS_TOKEN`, `GOOGLE_CLOUD_PROJECT`, `GCLOUD_PROJECT`, `GOOGLE_APPLICATION_CREDENTIALS`, `GOOGLE_API_KEY`, `GCLOUD_ACCESS_TOKEN`, `FLOCI_GCP_AUTH_*`, and Cloud SDK override prefixes (`CLOUDSDK_AUTH_*`, `CLOUDSDK_CORE_*`, `CLOUDSDK_API_ENDPOINT_OVERRIDES_*`).

Compose enables (do not turn off for CTF): `FLOCI_GCP_SERVICES_IAM_ENFORCEMENT_ENABLED`, `FLOCI_GCP_SERVICES_IAM_STRICT_ENFORCEMENT_ENABLED`, `FLOCI_GCP_AUTH_VALIDATE_TOKENS`, `FLOCI_GCP_CTF_HIDE_INTERNAL_ENDPOINTS` (use `all` to hide `/health` from players).

```bash
export FLOCI_GCP_AUTH_ROOT_SERVICE_ACCOUNT="operator@floci-local.iam.gserviceaccount.com"
export FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN="..."
docker compose up -d
```

---

## Project Overview

floci-gcp is a Java-based local GCP emulator built on Quarkus.

Its goal is full GCP SDK and gcloud CLI compatibility through real GCP wire protocols, not convenience APIs or simplified abstractions.

floci-gcp acts as an open-source alternative to the GCP-provided emulators, unified under a single port. This fork adds Stage 0 auth gates on top of that surface.

- Port: 4588
- Stack:
  - Java 25
  - Quarkus 3.34.6
  - JUnit 5
  - RestAssured
  - Jackson
  - quarkus-grpc (gRPC + HTTP/2 via ALPN on the same port)

---

## First Principles

When making changes, follow these priorities:

1. Preserve CTF hardening (see CTF fork overview above)
2. Preserve GCP protocol compatibility
3. Match GCP SDK and gcloud CLI behavior
4. Reuse existing floci-gcp patterns
5. Prefer correctness over convenience
6. Keep changes narrow and testable

Critical rules:

- Do not introduce custom endpoint shapes
- Do not change request or response formats for convenience
- Do not perform broad refactors unless the task explicitly requires them
- Keep behavior aligned with GCP expectations and existing floci-gcp conventions
- Do not weaken Compose CTF defaults without an explicit maintainer request

---

## Architecture

floci-gcp follows a layered design:

- **Controller / Handler**
  - Parses GCP protocol input (gRPC or REST)
  - Produces GCP-compatible responses

- **Service**
  - Contains business logic
  - Throws `GcpException`

- **Model**
  - Domain objects

### Core Infrastructure

- `EmulatorConfig` — `@ConfigMapping(prefix = "floci-gcp")` SmallRye Config interface
- `ServiceRegistry`
- `StorageBackend` + `StorageFactory`
- `GcpException` + `GcpExceptionMapper`
- `GcpGrpcController` — base class for gRPC service implementations
- `ProjectContextFilter` — extracts GCP project ID from request path or headers
- `RequestContext` — `@RequestScoped` holder for the current project ID
- `GcpResourceNames` — utilities for parsing and building GCP resource name strings
- `EmulatorLifecycle`
- `XmlBuilder` + `XmlParser` — used by GCS (REST XML)

---

## Package Layout

- `io.floci.gcp.config`
- `io.floci.gcp.core.common`
- `io.floci.gcp.core.common.dns`
- `io.floci.gcp.core.common.docker`
- `io.floci.gcp.core.storage`
- `io.floci.gcp.lifecycle`
- `io.floci.gcp.lifecycle.inithook`
- `io.floci.gcp.services.<service>`

Typical service structure:

- `services/<svc>/`
  - `*Controller.java`
  - `*Service.java`
  - `model/`

Rule:
Copy an existing service pattern before introducing a new one.

---

## GCP Protocol Rules

floci-gcp must implement real GCP wire protocols.

| Protocol | Services | Transport | Implementation |
|----------|----------|-----------|----------------|
| gRPC | Pub/Sub, Firestore, Datastore, Secret Manager | HTTP/2 + proto3 | `GcpGrpcController` subclass |
| REST JSON | GCS (management), IAM, Secret Manager (REST) | HTTP/1.1 or HTTP/2 | JAX-RS |
| REST XML | GCS (object operations) | HTTP/1.1 or HTTP/2 | JAX-RS + `XmlBuilder` |

### Single-port design

Both gRPC and REST are served on port **4588** via ALPN negotiation:
- `quarkus.http.http2=true`
- `quarkus.grpc.server.use-separate-server=false`

### Auth and CTF gates

GCP SDKs skip credential checks when `*_EMULATOR_HOST` environment variables are set. Upstream
floci-gcp does not validate credentials. This CTF fork validates Bearer tokens and enforces IAM
when `FLOCI_GCP_AUTH_VALIDATE_TOKENS` / `FLOCI_GCP_SERVICES_IAM_*` are enabled (Compose defaults).

### Project ID as multi-tenancy key

GCP resource names follow `projects/{project}/...`. The project ID is the multi-tenancy boundary. All storage keys are namespaced by project ID via `ProjectAwareStorageBackend`.

Resolution order in `ProjectContextFilter`:
1. URL path segment `projects/{project}/...`
2. `x-goog-request-params` header (`project=...`)
3. `EmulatorConfig.defaultProjectId()` fallback

### Important exceptions

- GCS uses REST XML for object operations and REST JSON for bucket management; keep them aligned
- gRPC services use pre-compiled stubs from `grpc-google-cloud-*-java` artifacts — do not introduce raw `.proto` codegen
- Management APIs should be validated with GCP SDK clients, not only handcrafted HTTP requests

---

## XML / JSON Rules

- Use `XmlBuilder` for XML responses (GCS object API)
- Use `XmlParser` for XML parsing; do not use regex
- JSON errors must follow GCP error structures: `{"error": {"code": 404, "message": "...", "status": "NOT_FOUND"}}`
- gRPC errors must map to `io.grpc.Status` codes via `GcpException.grpcCode()`
- Types returned directly from controllers must remain compatible with native-image reflection requirements

---

## Storage Rules

Supported storage modes:

- `memory`
- `persistent`
- `hybrid`
- `wal`

Rules:

- Always use `StorageFactory`
- Do not instantiate storage implementations directly inside services
- Respect lifecycle hooks for load and flush behavior
- Storage keys are namespaced by GCP project ID via `ProjectAwareStorageBackend`

Important nuance:

`EmulatorConfig` declares `@WithDefault` values, but `application.yml` defines effective runtime behavior. Treat repository YAML as the source of truth unless a task explicitly changes configuration semantics.

When adding storage-related behavior:

1. Update `EmulatorConfig`
2. Update main `application.yml`
3. Update test `application.yml`
4. Wire through `StorageFactory`
5. Verify lifecycle integration

---

## Configuration Rules

Configuration lives under `floci-gcp.*`.

`EmulatorConfig` is a `@ConfigMapping(prefix = "floci-gcp")` SmallRye Config interface. Nested config groups are inner interfaces. Defaults use `@WithDefault`. Do **not** use `@ApplicationScoped` + `@ConfigProperty` for config — use `@ConfigMapping` instead.

When adding config:

1. Add a method (and nested interface if needed) to `EmulatorConfig`
2. Annotate with `@WithDefault` for the default value
3. Add the property to main `application.yml`
4. Add it to test `application.yml` if needed
5. Update documentation if user-facing
6. Follow `FLOCI_GCP_*` environment variable conventions

Critical areas:

- `floci-gcp.base-url`
- `floci-gcp.hostname`
- `floci-gcp.default-project-id`
- `floci-gcp.port`
- persistence paths
- Docker networking

---

## Build & Run

    ./mvnw quarkus:dev
    ./mvnw test
    ./mvnw clean package
    ./mvnw clean package -DskipTests

### Focused tests

    ./mvnw test -Dtest=GcsIntegrationTest
    ./mvnw test -Dtest=PubSubIntegrationTest#publishMessage

### CTF regression tests

Run Stage 0 auth and IAM enforcement coverage (wildcards pick up new classes):

```bash
./mvnw test -Dtest=*IamEnforcement*,*Grpc*Iam*,Ctf*,Token*,GkeTokenWebhookCtf*,ContainerEnvHardening*,HealthIntegrationTest
```

That set includes all `*IamEnforcement*` (REST and gRPC), `Ctf*`, `Token*`,
`GkeTokenWebhookCtf*`, `ContainerEnvHardening*`, and `HealthIntegrationTest`.

---

## Compatibility Project

Compatibility tests live in `./compatibility-tests/` and validate floci-gcp against real GCP tooling — SDK clients and Infrastructure-as-Code providers — not just handcrafted HTTP.

### Layout

Each subdirectory is a self-contained suite with its own `Dockerfile`:

- `sdk-test-java` — GCP SDK for Java. **Default / reference suite**; preferred for management-plane validation.
- `sdk-test-node` — GCP SDK for Node.js
- `sdk-test-python` — GCP SDK for Python
- `sdk-test-go` — GCP SDK for Go
- `sdk-test-gcloud` — gcloud CLI (bats-based)
- `compat-terraform` — Terraform `hashicorp/google` provider (bats-based)
- `compat-opentofu` — OpenTofu `hashicorp/google` provider (bats-based)

`justfile` provides per-suite recipes (`just test-java`, `just test-terraform`, …) for running a suite locally against a running floci-gcp instance.

### How suites run in CI

`.github/workflows/compatibility.yml` builds the floci-gcp image once, then runs one matrix job per suite:

1. Start floci-gcp as a container on a shared Docker network (`compat-net`), reachable as `http://floci-gcp:4588`.
2. Build the suite image from `compatibility-tests/<suite>`.
3. `docker run` the suite against the emulator with `/results` mounted.
4. Each suite writes JUnit XML to `/results`, consumed by the test-summary step; emulator logs are dumped on failure.

The endpoint is passed via env: `FLOCI_GCP_ENDPOINT` for the SDK suites; `FLOCI_ENDPOINT` / `FLOCI_HOST` / `FLOCI_PROJECT` for the bats/IaC suites.

### Adding a suite to CI

1. Give the suite directory a `Dockerfile` whose entrypoint runs the tests and writes JUnit XML to `/results`.
2. Add the directory name to the `matrix.test` list in `compatibility.yml`.
3. Keep test output visible — do not silence the runner (e.g. avoid `mvn test -q`); a hanging test must be diagnosable from the streamed log.

### IaC suites (Terraform / OpenTofu)

- Configure the google provider with `*_custom_endpoint` values pointing each service at the emulator. **Custom endpoints must include the API version** — e.g. `secret_manager_custom_endpoint = "${var.endpoint}/v1/"` and `storage_custom_endpoint = "${var.endpoint}/storage/v1/"`. Omitting the version makes the provider hit an unversioned path and the emulator returns `405`/`404`.
- Auth uses `GOOGLE_OAUTH_ACCESS_TOKEN` (default `fake-token-floci-gcp`). The emulator validates it against `FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN` when `FLOCI_GCP_AUTH_VALIDATE_TOKENS` is on.
- Only REST-exposed services are reachable via Terraform custom endpoints (GCS, IAM, Secret Manager, and others with `*_custom_endpoint`). Pub/Sub exposes REST and gRPC; Firestore remains gRPC-oriented for IaC unless REST transcoding is used.

### Guidelines

- Prefer GCP SDK clients over raw HTTP for management-plane validation.
- Validate any change that may affect real SDK behavior against this suite.
- Java-based tests (`sdk-test-java`) are preferred for management-plane API validation.
- If the suite is unavailable locally, state that limitation explicitly (e.g. in the PR description).

---

## Testing Rules

### Conventions

- Unit tests: `*ServiceTest.java`
- Integration tests: `*IntegrationTest.java`
- Prefer package-private constructors for testability
- Integration tests may use ordered execution when stateful behavior requires it

### Expectations

- Test any behavior affecting GCP compatibility
- Do not rely only on manual HTTP testing
- Prefer SDK-based validation where possible

### When touching protocol behavior

If a change affects request parsing, response shape, error handling, persistence semantics, URL generation, or service enablement:

1. Add or update automated tests
2. Prefer SDK-based verification where possible
3. Check compatibility across alternate protocol paths (gRPC and REST where both exist)
4. Document intentional deviations clearly

---

## Error Handling

- Services should throw `GcpException`
- REST flows use `GcpExceptionMapper` → `{"error": {"code": N, "message": "...", "status": "..."}}`
- gRPC flows use `GcpGrpcController.error(observer, t)` → `StatusRuntimeException`
- Controller return types must remain reflection-safe

---

## Service Implementation Pattern

When adding functionality:

1. Identify the GCP protocol (gRPC or REST)
2. Reuse an existing service pattern
3. Keep controllers thin
4. Use `GcpException` for domain errors
5. Reuse shared utilities (`GcpResourceNames`, `XmlBuilder`, etc.)
6. Update config, storage, docs, and tests together
7. Validate behavior against GCP SDK expectations

---

## Adding a New GCP Service

1. Create a package under `services/`
2. Add:
   - Controller (extends `GcpGrpcController` for gRPC, or JAX-RS resource for REST)
   - Service
   - `model/`
3. Register the service in `ServiceRegistry`
4. Add config to `EmulatorConfig` (enabled flag, storage key)
5. Add YAML config in main and test config files
6. Wire storage through `StorageFactory`
7. Add tests
8. Update documentation

### Services with container sidecars

If the service launches real Docker containers (sidecars / data planes), it
**must** expose a single root-level `mock` flag on its `*ServiceConfig`
(`boolean mock();`) that keeps the service metadata-only without Docker —
mirroring `kafka.mock`, `cloudsql.mock`, and `cloudrun.mock` (env var
`FLOCI_GCP_SERVICES_<SVC>_MOCK`). Gate every container interaction in the
service layer on `!mock()` (keep the Docker driver/manager class free of the
flag). Do not add a separate `enabled`-style opt-in for the container path — the
`mock` flag is the only toggle, defaulting to `false` (`kafka.mock`,
`cloudsql.mock`, `cloudrun.mock` all default `false`). Always set `mock: true`
in `src/test/resources/application.yml` so the suite never starts containers.

---

## Code Style

- Use constructor injection
- Prefer self-explanatory code over comments
- Avoid unnecessary comments
- Always use braces in conditionals
- Follow existing project patterns
- Use modern Java features only when they improve clarity

---

## Logging

- Use JBoss Logging
- Keep logs structured
- Avoid noisy logs in hot paths

---

## Pull Request Guidelines

- Keep changes focused
- Avoid unrelated refactors
- Preserve behavior unless the task explicitly requires change
- Update docs when necessary
- Explain missing tests when behavior changed but no automated coverage was added

Conventional commits:

- `feat:`
- `fix:`
- `perf:`
- `docs:`
- `chore:`

Do not add `Co-Authored-By` trailers for AI tools in commit messages. Keep attribution limited to human contributors.

---

## Release Awareness

- Changes merged into `main` do not automatically imply a stable release
- Release branches define stable release lines
- Tags trigger publishing workflows

Treat release workflows as critical infrastructure.

---

## Agent Workflow

### Before editing

1. Identify service and protocol (gRPC or REST)
2. Locate an existing implementation to mirror
3. Check config impact
4. Check storage impact
5. Check documentation impact
6. Define the minimal useful test plan

### Before finishing

1. Run relevant tests
2. Validate protocol behavior
3. Ensure no custom endpoints were introduced
4. Verify config and docs updates

---

## Common Mistakes

- Creating non-GCP endpoints
- Bypassing `StorageFactory`
- Changing wire formats without tests
- Forgetting YAML updates
- Producing inconsistent resource names (must match `projects/{project}/...` pattern)
- Testing only with raw HTTP (use SDK clients)
- Using `@ApplicationScoped` + `@ConfigProperty` for config — use `@ConfigMapping` interfaces instead
- Introducing unnecessary new patterns

---

## Human Handoff

If behavior is unclear:

1. Prefer GCP behavior
2. Then existing floci-gcp behavior
3. Then compatibility test expectations

If a task would require broad architectural changes, stop and surface the tradeoffs instead of refactoring across services blindly.

---

## GCP SDK Source as Reference

Don't try to look into jars from `~/.m2/repository` — they are not source code. Refer to the actual GCP SDK source code for accurate behavior and protocol details.

The proto definitions for each gRPC service are the authoritative source for request/response shapes and field semantics.

Pre-compiled stub artifacts used (do not add raw `.proto` codegen):
- `com.google.api.grpc:grpc-google-cloud-pubsub-java`
- `com.google.api.grpc:grpc-google-cloud-firestore-v1-java`
- `com.google.api.grpc:grpc-google-cloud-datastore-v1-java`
- `com.google.api.grpc:grpc-google-cloud-secretmanager-v1-java`
- `com.google.api.grpc:proto-google-common-protos`
