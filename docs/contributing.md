# Contributing

floci-gcp is MIT licensed and welcomes contributions of all kinds.

## Ways to Help

- **Bug reports**: open a [GitHub issue](https://github.com/floci-io/floci-gcp/issues/new?template=bug_report.md) with a minimal reproduction
- **Missing API operations**: open a [feature request](https://github.com/floci-io/floci-gcp/issues/new?template=feature_request.md)
- **Pull requests**: new service operations, bug fixes, documentation improvements

## Development Setup

```bash
# Clone
git clone https://github.com/floci-io/floci-gcp.git
cd floci-gcp

# Run in dev mode (hot reload, port 4588)
./mvnw quarkus:dev

# Run all tests
./mvnw test

# Run a specific test
./mvnw test -Dtest=GcsIntegrationTest
./mvnw test -Dtest=PubSubIntegrationTest#publishMessage
```

## Commit Message Format

This project uses [Conventional Commits](https://www.conventionalcommits.org/).

### Format

```
<type>[optional scope]: <description>
```

| Type | Effect |
|---|---|
| `feat` | New feature → minor version bump |
| `fix` | Bug fix → patch version bump |
| `perf` | Performance improvement → patch |
| `docs` | Documentation only → no version bump |
| `chore` | Build/CI/housekeeping → no version bump |
| `refactor` | Code restructure → no version bump |
| `test` | Adding/updating tests → no version bump |
| `ci` | CI workflow changes → no version bump |
| `feat!:` or `BREAKING CHANGE:` | Breaking change → major bump |

### Valid examples ✅

```
feat(pubsub): add StreamingPull support
fix(gcs): correct multipart upload final response
chore: release 1.2.0
feat!: remove legacy endpoint
ci: add conventional commits lint workflow
```

### Invalid examples ❌

```
Add streaming support         # missing type
Feature: add something        # not a valid type
feat : space before colon     # space before colon
FIX(gcs): uppercase type      # type must be lowercase
```

## Adding a New GCP Service

See [AGENTS.md](https://github.com/floci-io/floci-gcp/blob/main/AGENTS.md) for the full architecture guide.

Quick summary:

1. Identify the GCP wire protocol (gRPC or REST)
2. Create `src/main/java/io/floci/gcp/services/<service>/` with a Controller, Service, and `model/` package
3. For gRPC: extend the generated `*Grpc.*ImplBase` and use `GcpGrpcController.grpcError` for shared error mapping; for REST: use JAX-RS `@Path` resources
4. Register the service in `ServiceRegistry`
5. Add config in `EmulatorConfig.java` and `application.yml`
6. Add `*IntegrationTest.java` tests
7. Validate against the GCP SDK client

## Pull Request Checklist

- [ ] `./mvnw test` passes
- [ ] New or updated integration test added
- [ ] Tests validate behavior with GCP SDK clients, not just raw HTTP
- [ ] Commit messages follow Conventional Commits
- [ ] No `Co-Authored-By` trailers for AI tools

## Compatibility Tests

The `./compatibility-tests/` directory contains SDK-based integration tests. Run them before submitting changes that affect GCP protocol behavior:

```bash
cd compatibility-tests
cp env.example .env
just setup
just test-all          # SDK suites (Java, Node, Python, Go, gcloud)
just test-all-iac      # Terraform / OpenTofu
```

If the compatibility test suite is unavailable in your environment, state that explicitly in the PR description.

## Releases

Stable releases ship on the **1st and 3rd Tuesday of each month**. Merging to `main` does not cut a release: the change rides the next train, and reaches the `nightly` image on the next nightly build.

Maintainers cut releases from `main` with the Release Cut workflow, which runs semantic-release over the Conventional Commits since the last tag. That is why the commit type matters: `feat:` and `fix:` move the version, `docs:` and `chore:` do not. `CHANGELOG.md` is generated from those messages and is not edited by hand; a genuine correction goes in a PR carrying the `changelog-edit` label.

## Reporting Security Issues

Do **not** open public issues for security vulnerabilities. Use [GitHub private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing/privately-reporting-a-security-vulnerability) instead.
