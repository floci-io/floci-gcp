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
  - Quarkus 3.38.3
  - JUnit 5
  - RestAssured
  - Jackson
  - quarkus-grpc (gRPC + HTTP/2 via ALPN on the same port)

Sources of truth: `src/main/resources/application.yml` for the port, `pom.xml` for versions. Update this list when you change either.

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

| Protocol | Services | Transport | Implementation |
|----------|----------|-----------|----------------|
| gRPC | Pub/Sub, Firestore, Datastore, Secret Manager, Cloud Tasks, Cloud Scheduler, Cloud KMS, Cloud Logging, Cloud Monitoring, IAM, GCS, BigQuery (Storage Read API) | HTTP/2 + proto3 | Generated `*Grpc.*ImplBase` subclass (Datastore implements `BindableService` directly) + `GcpGrpcController.grpcError` |
| REST JSON | 22 services under `src/main/java/io/floci/gcp/services/`; of the gRPC services above, only Firestore and Cloud Tasks have no JAX-RS controller | HTTP/1.1 or HTTP/2 | JAX-RS |
| HTTP/protobuf | Datastore | HTTP/1.1 or HTTP/2 | JAX-RS, `application/x-protobuf` |
| REST XML | GCS (object operations) | HTTP/1.1 or HTTP/2 | JAX-RS + `XmlBuilder` |

Sources of truth: classes extending `*Grpc.*ImplBase` or implementing `BindableService` for the gRPC row, `@Path`-annotated controllers for the HTTP rows. Update this table when you add or remove either.

Treat feature support as transport-specific. A service exposing both gRPC and HTTP does not imply that every operation works over both transports.

### Single-port design

Both gRPC and REST are served on port **4588** via ALPN negotiation:

- `quarkus.http.http2=true`
- `quarkus.grpc.server.use-separate-server=false`

Source of truth: `src/main/resources/application.yml`. Update this section when you change the port or the HTTP/2 settings.

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
- gRPC services use pre-compiled stubs from `grpc-google-cloud-*` artifacts. Do not introduce raw `.proto` codegen
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

Source of truth: `StorageFactory` and `EmulatorConfig`. Update this list when you add or remove a mode.

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

### Layout

Each subdirectory is a self-contained suite with its own `Dockerfile`:

- `sdk-test-java`: GCP SDK for Java. **Default / reference suite**; preferred for management-plane validation when it covers the affected API.
- `sdk-test-node`: GCP SDK for Node.js
- `sdk-test-python`: GCP SDK for Python
- `sdk-test-go`: GCP SDK for Go
- `sdk-test-rust`: official Rust client
- `sdk-test-gcloud`: gcloud CLI (bats-based)
- `compat-terraform`: Terraform `hashicorp/google` provider (bats-based)
- `compat-opentofu`: OpenTofu `hashicorp/google` provider (bats-based)

Use another official SDK or gcloud suite when it better represents the changed client path.

`compatibility-tests/justfile` provides per-suite recipes (`just test-java`, `just test-terraform`, …) for running a suite locally against a running floci-gcp instance.

Sources of truth: the `compatibility-tests/` subdirectories and `matrix.test` in `.github/workflows/compatibility.yml`. Update this list when you add or remove a suite.

### How suites run in CI

`.github/workflows/compatibility.yml` builds the floci-gcp image once, then runs one matrix job per suite:

1. Start floci-gcp as a container on a shared Docker network (`compat-net`), reachable as `http://floci-gcp:4588`.
2. Build the suite image from `compatibility-tests/<suite>`.
3. `docker run` the suite against the emulator with `/results` mounted.
4. Each suite writes JUnit XML to `/results`, consumed by the test-summary step; emulator logs are dumped on failure.

Every suite receives the same endpoint variables: `FLOCI_GCP_ENDPOINT`, `FLOCI_ENDPOINT`, `FLOCI_HOST` and `FLOCI_PROJECT`. The SDK suites and `sdk-test-gcloud` read `FLOCI_GCP_ENDPOINT`; only `compat-terraform` and `compat-opentofu` read the other three.

Source of truth: `.github/workflows/compatibility.yml`. Update this section when you change the network, the results mount, or the endpoint variables.

### Adding a suite to CI

1. Give the suite directory a `Dockerfile` whose entrypoint runs the tests and writes JUnit XML to `/results`.
2. Add the directory name to the `matrix.test` list in `compatibility.yml`.
3. Keep test output visible: do not silence the runner (e.g. avoid `mvn test -q`); a hanging test must be diagnosable from the streamed log.

### IaC suites (Terraform / OpenTofu)

- Configure each google provider `*_custom_endpoint` with the base path required by that endpoint's provider contract. Do not apply one path rule to every service. For example, Storage uses `${var.endpoint}/storage/v1/`, Secret Manager uses `${var.endpoint}/v1/`, and IAM intentionally uses the versionless `${var.endpoint}/`. Follow the checked-in Terraform and OpenTofu provider configurations when adding coverage.
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

### General

- Use constructor injection
- Prefer self-explanatory code over comments
- Avoid unnecessary comments
- Always use braces in conditionals
- Never leave a `catch` block empty. Four `catch (... ignored) {}` blocks predate this rule
  (`ProjectAwareStorageBackend`, `GcsUploadController`, and two in `CloudTasksController`); they
  name the variable correctly but carry no comment, and are stragglers rather than the pattern.
  If an exception is intentionally tolerated, log it with
  enough context to diagnose it later. When swallowing really is correct and logging would be
  noise, name the variable `ignored` or `expected` and say in a comment why it is safe. A bare
  `catch (Exception e) {}` is never acceptable.
- Follow existing project patterns
- Use modern Java features only when they improve clarity

### Types and names

- **Do not use `var`. Write the explicit type.** floci-gcp reproduces GCP wire contracts, so the
  concrete type at a call site is usually the thing under review: whether a value is a
  `LinkedHashMap` or a `Map`, a generated protobuf type or floci's own model, is exactly what a
  reviewer needs to see. This covers local declarations, enhanced-for
  (`for (TableFieldSchema field : fields)`), classic for-init, and try-with-resources. The one
  exception is a record deconstruction pattern (`case Node(var left, var right) ->`), where
  naming the component types is pure noise.
- **Import the classes you use. Do not write fully-qualified names inline.**
  `new ArrayList<>()`, never `new java.util.ArrayList<>()`. The only reason to qualify inline is
  a genuine name collision inside one file: import the type used more often, qualify the other,
  and leave a short comment naming the clash. The real example in this repo is `io.grpc.Status`
  qualified inline in `FirestoreController` and `EventarcService`, both of which import
  `com.google.rpc.Status`. Generated protobuf types collide with floci's own models often enough
  that this comes up whenever a service grows a gRPC surface beside its REST one.

### Imports

- No wildcard imports in `src/main`. Static wildcards stay fine in tests, where
  `Assertions.*`, `Mockito.*` and `Matchers.*` are the established idiom.
- Import order: non-`java`/`javax` imports alphabetically, then `java.*` and `javax.*` last.
  This is the IntelliJ default layout and what most of the tree already uses.

### Conventions the codebase already follows

Written down so they stay true. New code should match them without thinking. A violation you
find in the tree is a straggler, not a precedent.

Counts below are measured on `src/main` at 206719d, with the command that produced them, so they
can be re-run rather than trusted:

```bash
git grep -hoE '(^|[^A-Za-z_."])var[[:space:]]+[A-Za-z_]' 206719d -- 'src/main/*.java' | wc -l   # 56, in 17 files
git grep -c '^import .*\.\*;' 206719d -- 'src/main/*.java' | wc -l                              # 31 wildcard imports
git grep -lP '^\t' 206719d -- 'src/main/*.java' | wc -l                                         # 19 tab-indented files
git grep -nE '(^|[^a-zA-Z0-9_."])java(x)?\.[a-z]+\.[A-Z]' 206719d -- 'src/main/*.java' \
  | grep -v ':import ' | wc -l                                                                  # 76 lines, 21 files
```

The `var` and wildcard counts are being taken to zero file by file. `src/test` is out of scope
for now, at 127 uses of `var` and 41 wildcard imports. Of the 76 inline fully-qualified names, 29
are `java.lang.Object` in `GcsGrpcMapper`.

- 4-space indentation, K&R braces. Do not indent with a tab. This one is aspirational rather than
  already true: 19 files still use tab indentation, `CredentialAccessBoundaryParser` (196 lines)
  and `GcsAuthorizationService` (94) worst among them.
- JBoss Logging, in a field named `LOG` (79 of the 80 files that log; `CloudSqlContainerDataPlane`
  injects an instance `log` so a test can capture it). Both parameterized forms are in use: 459
  `...f()` calls and 155 `...v()`. Prefer `...f()` in new code, and never concatenate strings in a
  log call: there are none, keep it that way.
- No `printStackTrace`, anywhere, and no `System.out` or `System.err` in `src/main`. There are
  none today. A test may print a failure repro just before failing, which is the only good
  reason to print from a test: an assertion message usually says it better.
- `java.time` for everything floci owns. `Calendar` and `SimpleDateFormat` appear nowhere and
  must not be introduced. A `Date` survives only at a third-party boundary that forces one;
  convert there with `Date.from(instant)` and keep `java.time` on floci's side of it.
- Constructor injection in `src/main`. Field injection is fine in tests.
- `Optional` as a return type, and rarely as a field: two exist, a cache in
  `CurrentContainerNetworkResolver` and the injected `appVersion` in `EmulatorLifecycle`. Do not
  add more, and prefer not to take one as a parameter, which `ContainerBuilder.resolveImage` and
  `normalizeImageRegistryBase` still do.
- Switch expressions over switch statements. Pattern-matching `instanceof` over
  cast-after-check.
- `GcpException` for domain errors, mapped by `GcpExceptionMapper` (REST) and
  `GcpGrpcController.grpcError` (gRPC).
- `final` on service fields, but not on locals or parameters.

### Tests

These describe `src/test`. `compatibility-tests` is a separate module with the opposite idiom,
AssertJ rather than JUnit assertions. Follow the module you are in.

- Unit tests are `*ServiceTest`, integration tests `*IntegrationTest` (see Testing Rules).
- Name test methods as a camelCase sentence (`publishMessageRoundTripsThroughPull`) or as
  `method_scenario_expectation`. `testX` names are not the pattern to copy.

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

The proto definitions for each gRPC service are the authoritative source for request/response shapes and field semantics.

Pre-compiled stub artifacts used (do not add raw `.proto` codegen), all under `com.google.api.grpc`:
- `grpc-google-cloud-pubsub-v1`
- `grpc-google-cloud-firestore-v1`
- `grpc-google-cloud-secretmanager-v1`
- `grpc-google-cloud-tasks-v2`
- `grpc-google-cloud-scheduler-v1`
- `grpc-google-cloud-kms-v1`
- `grpc-google-cloud-logging-v2`
- `grpc-google-cloud-monitoring-v3`
- `grpc-google-cloud-storage-v2`
- `grpc-google-cloud-bigquerystorage-v1`
- `grpc-google-iam-v1`
- `proto-google-cloud-datastore-v1` (protos only; Datastore implements `BindableService` directly)
- `proto-google-cloud-eventarc-v1`
- `proto-google-cloud-functions-v2`
- `proto-google-cloud-run-v2`
- `proto-google-cloud-service-usage-v1`
- `proto-google-common-protos`

Source of truth: the `com.google.api.grpc` dependencies in `pom.xml`. Update this list when you add or remove one.
