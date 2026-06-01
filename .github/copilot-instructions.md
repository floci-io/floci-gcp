# Copilot Instructions for Pull Request Review

Review pull requests in the floci-gcp repository with GCP compatibility as the primary concern.

floci-gcp is a Java-based local GCP emulator built on Quarkus. Its goal is to match GCP SDK and gcloud CLI behavior through real GCP wire protocols (gRPC and REST), not convenience APIs or custom abstractions.

## Review Priorities

Evaluate changes in this order:

1. Preserve GCP protocol compatibility
2. Match GCP SDK and gcloud CLI behavior
3. Reuse existing floci-gcp patterns
4. Prefer correctness over convenience
5. Keep changes focused and testable

## What to Flag

Raise concerns when a PR introduces any of the following without strong justification:

- Non-GCP endpoint shapes or resource name formats
- Request or response format changes made for convenience
- Broad refactors unrelated to the PR goal
- New service patterns where an existing floci-gcp pattern should be reused
- Direct storage implementation usage instead of `StorageFactory`
- Use of `@ConfigMapping` (this project uses `@ApplicationScoped` + `@ConfigProperty`)

## Architecture Expectations

floci-gcp follows a layered design:

- Controllers / handlers parse GCP protocol input (gRPC or REST) and produce GCP-compatible responses
- Services contain business logic and should throw `GcpException`
- Models hold domain data

Core infrastructure commonly relevant in reviews:

- `EmulatorConfig` — `@ApplicationScoped` with `@ConfigProperty`, prefix `floci-gcp.*`
- `ServiceRegistry`
- `StorageFactory`
- `GcpException` + `GcpExceptionMapper`
- `GcpGrpcController` — base class for gRPC service implementations
- `ProjectContextFilter` — extracts GCP project ID from request path/headers
- `RequestContext` — `@RequestScoped` holder for the current project ID
- `GcpResourceNames` — resource name parsing and construction utilities
- `EmulatorLifecycle`

Check that controllers stay thin, business logic remains in services, and new changes fit existing repository patterns.

## Protocol Review Rules

floci-gcp implements real GCP wire protocols. Review protocol-affecting changes carefully.

- gRPC services (Pub/Sub, Firestore, Datastore, Secret Manager) must use proto3 wire format via pre-compiled `grpc-google-cloud-*-java` stubs
- REST XML services (GCS object operations) must use `XmlBuilder` and match GCS XML response shapes
- REST JSON services (GCS management, IAM) must return GCP-style JSON
- Both gRPC and REST are served on the same port (4588) via ALPN — do not split them onto separate ports

Pay extra attention to these cases:

- GCS has both REST XML (object API) and REST JSON (bucket management) — keep both aligned
- Project ID extraction must follow the `projects/{project}/...` resource naming convention
- Auth bypass via `*_EMULATOR_HOST` env vars means floci-gcp must not validate credentials
- Management APIs should be validated with GCP SDK clients, not only handcrafted HTTP

## XML and JSON Rules

Flag PRs that:

- Return JSON errors that do not follow GCP error structure: `{"error": {"code": N, "message": "...", "status": "..."}}`
- Return gRPC errors that do not map to the correct `io.grpc.Status` code via `GcpException`
- Change controller return types in ways that may break reflection or native-image compatibility

## Config and Storage Review

When a PR changes configuration or persistence behavior, verify the change is wired consistently.

Check for updates to:

- `EmulatorConfig` (new `@ConfigProperty` field + accessor method)
- main `application.yml`
- test `application.yml`
- `StorageFactory`
- lifecycle hooks when relevant

Supported storage modes include:

- `memory`
- `persistent`
- `hybrid`
- `wal`

Storage keys are namespaced by GCP project ID via `ProjectAwareStorageBackend`. Treat repository YAML as the source of truth for runtime behavior unless the PR explicitly changes configuration semantics.

## Testing Expectations

Expect automated coverage for changes that affect:

- request parsing
- response shape
- error handling
- persistence semantics
- resource name generation
- service enablement

Prefer:

- GCP SDK-based validation over raw HTTP-only testing
- integration tests for compatibility-sensitive behavior
- existing naming conventions such as `*ServiceTest.java` and `*IntegrationTest.java`

If behavior changes without automated coverage, call that out explicitly.

## Review Checklist

When analyzing a PR, check:

- Is the change focused?
- Does it preserve GCP-compatible wire behavior?
- Does it reuse an existing floci-gcp pattern?
- Are controllers thin and services responsible for domain logic?
- Are `GcpException` and existing error-mapping patterns used correctly?
- Are config and YAML updates complete?
- Are storage changes wired through `StorageFactory`?
- Are tests added or updated where compatibility is affected?
- Are docs updated when user-facing behavior changes?

## How to Write Feedback

Write review comments that are:

- specific
- repository-aware
- grounded in GCP compatibility risk

Use severity when helpful:

- `high`: likely breaks GCP SDK / CLI compatibility or protocol behavior
- `medium`: inconsistent with floci-gcp architecture, wiring, or testing expectations
- `low`: maintainability, clarity, or minor convention issue

Prefer comments that explain:

- what is risky
- why it matters in floci-gcp
- which existing pattern should be followed instead

## If Behavior Is Unclear

Use this fallback order:

1. Prefer GCP behavior
2. Then existing floci-gcp behavior
3. Then compatibility test expectations

If correctness would require a broader architectural change, call out the tradeoff instead of suggesting blind refactoring.
