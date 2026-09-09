Guidance for AI coding agents working in the floci-gcp repository.

This file defines repository-specific operating rules for autonomous or semi-autonomous coding agents. Follow these instructions unless a maintainer explicitly tells you otherwise.

---

## Project Overview

floci-gcp is a Java-based local GCP emulator built on Quarkus.

Its goal is full GCP SDK and gcloud CLI compatibility through real GCP wire protocols, not convenience APIs or simplified abstractions.

Read `README.md` and the relevant page under `docs/services/` before changing a service. They describe the current user-facing feature and protocol surface.

### Sources of truth

Do not copy changing repository inventories into this file. Resolve current facts from their owning sources:

- `pom.xml`: Java, Quarkus, dependency, plugin, and build versions
- `src/main/resources/application.yml`: effective runtime defaults, ports, and service configuration
- `src/test/resources/application.yml`: test-specific configuration
- `README.md` and `docs/`: supported user-facing services, protocols, features, and setup
- `src/main/java/` and tests: implemented behavior and transport-specific limitations
- `.github/workflows/`: current CI, release, and compatibility jobs
- `compatibility-tests/`: current compatibility suites and their invocation requirements

Documentation is not a substitute for implementation evidence. When changing or reviewing a factual claim, confirm it against the owning configuration, implementation, tests, or workflow. Update the user-facing documentation when behavior changes.

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

- `EmulatorConfig`: `@ConfigMapping(prefix = "floci-gcp")` SmallRye Config interface
- `ServiceRegistry`
- `StorageBackend` + `StorageFactory`
- `GcpException` + `GcpExceptionMapper`
- `GcpGrpcController`: shared gRPC error-mapping helper (static `grpcError`); not a base class
- `ProjectContextFilter`: extracts GCP project ID from request path or headers
- `RequestContext`: `@RequestScoped` holder for the current project ID
- `GcpResourceNames`: utilities for parsing and building GCP resource name strings
- `EmulatorLifecycle`
- `XmlBuilder` + `XmlParser`: used by GCS (REST XML)

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

Before changing protocol behavior, error handling, or response shapes, read and follow [Protocol Compatibility and Upstream Evidence](CONTRIBUTING.md#protocol-compatibility-and-upstream-evidence). Do not infer behavior across gRPC, REST JSON, REST XML, or HTTP/protobuf transports.

Before changing resource lifecycles, parent deletion, concurrency, locking, or storage-key formats, read and follow [Concurrency and Storage Invariants](CONTRIBUTING.md#concurrency-and-storage-invariants). State the invariant and inventory every competing mutation path before editing.

- Determine a service's current transports from its controllers, tests, and service documentation.
- Treat feature support as transport-specific. A service exposing both gRPC and HTTP does not imply that every operation works over both transports.
- Use generated gRPC service bases where the available stubs support them. Follow an existing `BindableService` implementation when they do not.
- Implement HTTP APIs with the repository's existing JAX-RS patterns.
- Use `XmlBuilder` and `XmlParser` for GCS XML behavior.
- Read `pom.xml` for the current pre-compiled GCP stub dependencies. Do not add raw `.proto` code generation.

### Single-port design

gRPC and HTTP APIs share the configured Quarkus listener through ALPN negotiation. Read `application.yml` for the effective port and HTTP/2 settings. Preserve the shared-listener design unless the task explicitly changes it.

The relevant Quarkus settings include:

- `quarkus.http.http2=true`
- `quarkus.grpc.server.use-separate-server=false`

### Auth bypass

GCP SDKs skip credential checks when `*_EMULATOR_HOST` environment variables are set. floci-gcp does not cryptographically validate credentials: requests with no credential, external credentials, and emulator-issued OAuth or impersonated tokens are accepted. The exception is an emulator-issued downscoped token, whose GCS requests are evaluated against its Credential Access Boundary (CAB).

### Project ID as multi-tenancy key

GCP resource names follow `projects/{project}/...`. The project ID is the multi-tenancy boundary. All storage keys are namespaced by project ID via `ProjectAwareStorageBackend`.

Resolution order in `ProjectContextFilter`:
1. URL path segment `projects/{project}/...`
2. `x-goog-request-params` header (`project=...`)
3. `EmulatorConfig.defaultProjectId()` fallback

### Important exceptions

- GCS uses REST XML for object operations and REST JSON for bucket management; keep them aligned
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

Read `EmulatorConfig`, `StorageFactory`, and `application.yml` for the current storage modes and defaults.

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

`EmulatorConfig` is a `@ConfigMapping(prefix = "floci-gcp")` SmallRye Config interface. Nested config groups are inner interfaces. Defaults use `@WithDefault`. Do **not** use `@ApplicationScoped` + `@ConfigProperty` for config. Use `@ConfigMapping` instead.

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

Compatibility tests live in `./compatibility-tests/` and validate floci-gcp against real GCP tooling (SDK clients and Infrastructure-as-Code providers), not just handcrafted HTTP.

Inspect `compatibility-tests/` for the current suite inventory and `.github/workflows/compatibility.yml` for the authoritative CI matrix, environment variables, container topology, and execution commands. Do not assume that every language suite uses the same runner or endpoint variables.

The Java SDK suite is the preferred reference for management-plane validation when it covers the affected API. Use another official SDK or gcloud suite when it better represents the changed client path.

### Adding a suite to CI

1. Make the suite self-contained under `compatibility-tests/<suite>/` and provide a `Dockerfile` whose entrypoint runs the suite and writes results in the format consumed by `.github/workflows/compatibility.yml`.
2. Add the suite to the matrix in `.github/workflows/compatibility.yml`.
3. Keep test output visible. A hanging test must be diagnosable from the streamed log.

### IaC suites (Terraform / OpenTofu)

- Configure the google provider with `*_custom_endpoint` values pointing each service at the emulator. **Custom endpoints must include the API version**, e.g. `secret_manager_custom_endpoint = "${var.endpoint}/v1/"` and `storage_custom_endpoint = "${var.endpoint}/storage/v1/"`. Omitting the version makes the provider hit an unversioned path and the emulator returns `405`/`404`.
- Auth is bypassed with a fake `GOOGLE_OAUTH_ACCESS_TOKEN`; the emulator ignores it.
- A provider resource is testable only when the provider can target an implemented compatible HTTP API. Do not infer IaC compatibility merely because a service exposes some HTTP or gRPC transport.

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
- gRPC flows use `GcpGrpcController.grpcError(observer, t)` → `StatusRuntimeException`
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
   - Controller (extends the generated `*Grpc.*ImplBase`, or implements `BindableService`, for gRPC; JAX-RS resource for REST)
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
(`boolean mock();`) that keeps the service metadata-only without Docker.
mirroring `kafka.mock`, `cloudsql.mock`, and `cloudrun.mock` (env var
`FLOCI_GCP_SERVICES_<SVC>_MOCK`). Gate every container interaction in the
service layer on `!mock()` (keep the Docker driver/manager class free of the
flag). Do not add a separate `enabled`-style opt-in for the container path: the
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

## Documentation Style

- No em-dashes anywhere, in any content. Use colons, commas, or periods.

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
- Releases are cut from `main` via the "Release Cut" workflow (`workflow_dispatch`
  on `.github/workflows/release-cut.yml`), which runs semantic-release: it bumps
  `pom.xml`, writes `CHANGELOG.md`, commits, tags, and creates the GitHub Release
- `release/x.y.x` branches are retired for now
- Tags still trigger the publishing workflows (`release.yml`)

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
- Using `@ApplicationScoped` + `@ConfigProperty` for config. Use `@ConfigMapping` interfaces instead
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

Don't try to look into jars from `~/.m2/repository`: they are not source code. Refer to the actual GCP SDK source code for accurate behavior and protocol details.

The proto definitions for each gRPC service are the authoritative source for request/response shapes and field semantics. Read `pom.xml` for the current pre-compiled stub artifacts, and do not add raw `.proto` code generation.
