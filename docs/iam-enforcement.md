# Adding an enforcing service

IAM enforcement uses one decision point, `IamAuthorizationService`, behind REST
and gRPC transport adapters. The gRPC interceptor is installed in
`GrpcServerManager.bind`, which is the actual Vert.x service registration path.
The global-interceptor annotation also covers Quarkus-managed registrations.
An annotation alone does not cover the dynamically bound controllers.

Only the Resource Manager project adapter is registered in this change. Pub/Sub
and Secret Manager adapters are follow-up work after this framework is merged.

## Service extension contract

1. Add an `@ApplicationScoped` implementation of `IamAuthorizationAdapter` in the
   owning service package. Constructor injection is supported.
2. Declare the REST controller classes and fully qualified gRPC service names.
   Duplicate transport ownership fails registration. The shared IAM gRPC mixin
   dispatches through the adapter's resource factory.
3. Translate each REST handler and protobuf request to an `IamOperation`. Both
   transports must use the same `checks` table. An unfamiliar operation must throw
   `unsupported(...)`. Explicit permission-free methods may return an empty list.
4. Return every required `IamPermissionCheck`, including checks on other resources.
   Create/list operations usually check the parent; verify the exact upstream
   contract. Do not guess permissions from method names. Each check is conjunctive.
5. Implement `resource` with exact accepted names, service/type/name CEL attributes,
   and the existing policy storage key. Return empty for resources owned by other
   adapters. Use `IamResource.projectChild` for project inheritance; do not expose
   internal policy keys to CEL. Versioned resources can have a parent policy key.
6. Contribute a finite predefined role map and exact `basicRoles` slices. Basic-role
   permissions are explicitly contributed by each service, never inferred by the
   shared catalog. Do not use wildcard permission grants.
7. Reuse `IamService` policy storage and its existing resource-existence resolvers.
   `testPermissions` already routes registered resources through the evaluator.
   A service using different policy storage needs a reviewed integration before
   advertising enforcement; registering a resource factory alone is insufficient.
8. Add the service to the documentation and coverage matrix. Startup coverage is
   derived from registered adapters. Update the explicit exclusions in the startup
   message when expanding beyond the current scope.

The current REST transport adapter buffers and restores JSON request bodies. It
is not an XML, multipart, raw-media, or HTTP/protobuf authorization parser. Add an
appropriate transport adapter for those formats while retaining the shared decision
point. Likewise, streams that address resources after their initial message need
explicit state handling and tests. StreamingPull support is deferred with the Pub/Sub adapter.

## Required tests

- Allowed operation, forbidden permission on the same resource, ungranted sibling,
  condition exclusion, project inheritance, and cross-project isolation.
- Independent REST and gRPC negative assertions, plus the same requests with the
  feature disabled. Cover each advertised transport with real clients.
- `testIamPermissions`, policy-mutation escalation, unknown roles/resources/operations,
  malformed/expired credentials, and unsupported CEL constructs.
- Multi-resource operations, streaming entry points, and side-effect absence after
  denial, where applicable.
- Inventory controller methods so adding a handler cannot silently bypass the map.
- Temporarily bypass the service's authorization call and run its negative tests.
  Record that they failed with successful requests where denials were expected,
  restore the implementation, and rerun the passing suite.

The root integration suite exercises generated GCP gRPC clients and REST JSON in
both modes. `compatibility-tests/sdk-test-java` also contains `IamEnforcementTest`
using the official Resource Manager v1 REST SDK. It expects disabled mode by default. Against
an enforcing server set `FLOCI_GCP_IAM_TEST_ENFORCEMENT=true` in the test process.
This test-only variable does not enable server enforcement. The Java compatibility CI job
runs the default suite and then this test against a separate enforcing native emulator.

## Storage and concurrency

This slice does not change storage keys, resource lifecycles, deletion ordering,
or locking. Policies remain in the existing global IAM store under the exact
resource key; service stores retain their existing project routing. Evaluation
reads each policy under the existing striped policy lock. It does not hold a lock
across authorization and the subsequent service mutation, nor does it promise a
consistent snapshot across concurrent child/project policy updates.

## Upstream evidence

The following sources were consulted for the v1 surfaces and role differences:

- [Resource Manager v1 project get](https://docs.cloud.google.com/resource-manager/reference/rest/v1/projects/get)
  and [Resource Manager roles](https://docs.cloud.google.com/iam/docs/roles-permissions/resourcemanager).
- [IAM resource attributes](https://docs.cloud.google.com/iam/docs/conditions-resource-attributes),
  [expression protobuf](https://github.com/googleapis/googleapis/blob/master/google/type/expr.proto), and
  [Google API errors](https://google.aip.dev/193).

Official client dispatch was also checked in the [Resource Manager v1 Java SDK source](https://github.com/googleapis/google-api-java-client-services/blob/main/clients/google-api-services-cloudresourcemanager/v1/2.0.0/com/google/api/services/cloudresourcemanager/CloudResourceManager.java).
The shared policy transport uses the [IAM v1 policy proto](https://github.com/googleapis/googleapis/blob/master/google/iam/v1/iam_policy.proto).

No live GCP project was used. Denial codes and permission requirements follow these
contracts; exact production message text, error metadata, revocation timing, and
alias resolution have not been verified against live GCP.
