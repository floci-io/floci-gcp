Guidance for AI coding agents working in the floci-gcp repository.

This file defines repository-specific operating rules for autonomous or semi-autonomous coding agents. Follow these instructions unless a maintainer explicitly tells you otherwise.

---

## Project Overview

floci-gcp is a Java-based local GCP emulator built on Quarkus.

Its goal is full GCP SDK and gcloud CLI compatibility through real GCP wire protocols, not convenience APIs or simplified abstractions.

floci-gcp acts as an open-source alternative to the GCP-provided emulators, unified under a single port.

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

1. Preserve GCP protocol compatibility
2. Match GCP SDK and gcloud CLI behavior
3. Reuse existing floci-gcp patterns
4. Prefer correctness over convenience
5. Keep changes narrow and testable

Critical rules:

- Do not introduce custom endpoint shapes
- Do not change request or response formats for convenience
- Do not perform broad refactors unless the task explicitly requires them
- Keep behavior aligned with GCP expectations and existing floci-gcp conventions

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

### Auth bypass

GCP SDKs skip credential checks when `*_EMULATOR_HOST` environment variables are set. floci-gcp does not validate credentials; it accepts all requests unconditionally.

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

When adding storage-related behavior:

1. Update `EmulatorConfig`
2. Update main `application.yml`
3. Update test `application.yml`
4. Wire through `StorageFactory`
5. Verify lifecycle integration

---

## Configuration Rules

Configuration lives under `floci-gcp.*`.

`EmulatorConfig` is a `@ConfigMapping(prefix = "floci-gcp")` SmallRye Config interface. Do **not** use `@ApplicationScoped` + `@ConfigProperty` for config — use `@ConfigMapping` instead.

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

---

## Compatibility Project

Compatibility test suite: `./compatibility-tests/`

Guidelines:

- Prefer GCP SDK clients over raw HTTP for management-plane validation
- Use this suite when changes may affect real SDK behavior
- If the suite is unavailable locally, state that limitation explicitly

Default module: `sdk-test-java` (GCP SDK for Java)

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

Pre-compiled stub artifacts used (do not add raw `.proto` codegen):
- `com.google.api.grpc:grpc-google-cloud-pubsub-java`
- `com.google.api.grpc:grpc-google-cloud-firestore-v1-java`
- `com.google.api.grpc:grpc-google-cloud-datastore-v1-java`
- `com.google.api.grpc:grpc-google-cloud-secretmanager-v1-java`
- `com.google.api.grpc:proto-google-common-protos`
