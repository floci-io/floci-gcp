# Adding an enforcing service

IAM enforcement uses one decision point, `IamAuthorizationService`, behind REST
and gRPC transport adapters. The gRPC interceptor is installed in
`GrpcServerManager.bind`, which is the actual Vert.x service registration path.
The global-interceptor annotation also covers Quarkus-managed registrations.
An annotation alone does not cover the dynamically bound controllers.

Resource Manager projects, Cloud Run v2 services and revisions, Secret Manager
secrets and versions, Pub/Sub, Cloud Scheduler jobs, IAM service-account/key management and direct access-token
issuance, IAM project and organization custom-role management, and GCS bucket/object
operations are integrated. Bindings to stored custom roles grant their `includedPermissions`
through every adapter except in GCS bucket policies; see [Custom roles](services/iam.md#custom-roles). IAM policy methods also
use REST and the shared IAM gRPC mixin; the [IAM inventory](services/iam.md#service-account-enforcement)
lists exact methods, permission targets, role slices, and local condition limits. Cloud Run
service-policy methods use REST and the shared IAM gRPC API. Secret Manager secret
policy methods work over REST and gRPC. GCS invokes the shared evaluator from its
service-level authorization path so JSON filtering does not bypass its
lock-sensitive lifecycle coordination. Cloud Logging GCS export uses the same GCS service path
with the persisted sink writer as a trusted internal principal. It uses no request credential
bypass; sink management caller authorization is a separate integration.

## Service extension contract

1. Add an `@ApplicationScoped` implementation of `IamAuthorizationAdapter` in the
   owning service package. Constructor injection is supported.
2. Declare the REST controller classes and fully qualified gRPC service names.
   Duplicate transport ownership fails registration. The shared IAM gRPC mixin
   dispatches through the adapter's resource factory.
3. Translate each REST handler and protobuf request to an `IamOperation`. Both
   transports must use the same `checks` table. An unfamiliar operation must throw
   `unsupported(...)`. Explicit permission-free methods may return an empty list.
   gRPC services override `grpcOperation(String, Message)` and read request fields
   through typed protobuf getters. The default rejects unmapped gRPC operations.
4. Return every required `IamPermissionCheck`, including checks on other resources.
   Create/list operations usually check the parent; verify the exact upstream
   contract. Do not guess permissions from method names. Each check is conjunctive.
5. Implement `resource` with exact accepted names, service/type/name CEL attributes,
   and the existing policy storage key. Return empty for resources owned by other
   adapters. Use `IamResource.projectChild` for project inheritance; do not expose
   internal policy keys to CEL. Versioned resources can have a parent policy key.
6. Contribute a finite predefined role map and exact `basicRoles` slices. Basic-role
   permissions are explicitly contributed by each service, never inferred by the
   shared catalog. Do not use wildcard permission grants. Bindings with roles outside
   the finite catalog that are not active custom roles applying to the policy's project or
   organization are skipped as complete, non-granting bindings before condition
   validation; they must not block grants from known bindings or policy recovery.
7. Reuse `IamService` policy storage and its existing resource-existence resolvers.
   `testPermissions` routes registered resources through the evaluator. Override
   `validatePolicy` for service-specific binding constraints; it runs on policy
   writes and before evaluation in enforce mode and must not perform I/O.
   A service using different policy storage needs a reviewed integration before
   advertising enforcement; registering a resource factory alone is insufficient.
8. Add the service to the documentation and coverage matrix. Startup coverage is
   derived from registered adapters. Update the explicit exclusions in the startup
   message when expanding beyond the current scope.

The current REST transport adapter buffers and restores JSON request bodies. It
is not an XML, multipart, raw-media, or HTTP/protobuf authorization parser. Add an
appropriate transport adapter for those formats while retaining the shared decision
point. Likewise, streams that address resources after their initial message need
explicit state handling and tests. Pub/Sub StreamingPull retains the first
subscription name, checks every message and delivery, and rejects a repeated
subscription field in later messages.
GCS intentionally does not register its REST controllers with that filter. Its
authorization service calls the same evaluator after applying GCS-specific
credential handling. XML, media, upload, and service-layer paths use that entry
point instead of relying on the JSON filter.

## Required tests

- Allowed operation, forbidden permission on the same resource, ungranted sibling,
  condition exclusion, project inheritance, and cross-project isolation.
- Independent REST and gRPC negative assertions, plus the same requests with the
  feature disabled. Cover each advertised transport with real clients.
- `testIamPermissions`, policy-mutation escalation, unknown resources/operations,
  malformed/expired credentials, and unsupported CEL constructs.
- An uncatalogued role by itself denies normally, a known grant still succeeds beside
  an uncatalogued role for the same caller, and the policy remains recoverable.
- Multi-resource operations, streaming entry points, and side-effect absence after
  denial, where applicable.
- Inventory controller methods so adding a handler cannot silently bypass the map.
- Temporarily bypass the service's authorization call and run its negative tests.
  Record that they failed with successful requests where denials were expected,
  restore the implementation, and rerun the passing suite.

The root integration suite exercises generated GCP gRPC clients and REST JSON in
both modes. `compatibility-tests/sdk-test-java` also contains `IamEnforcementTest`
using the official Resource Manager v1 REST SDK and generated IAM v1 gRPC client.
It expects disabled mode by default. Against
an enforcing server set `FLOCI_GCP_IAM_TEST_ENFORCEMENT=true` in the test process.
This test-only variable does not enable server enforcement. The Java compatibility CI job
runs the default suite and then this test against a separate enforcing native emulator.

## Storage and concurrency

Policies use the global IAM store under the exact resource key; service stores
use project routing. GCS retains the lifecycle invariant from its owning service:
the IAM policy lock is acquired before the bucket mutation lock, and bucket creation
holds the bucket lock through initial-policy persistence and durable rollback.
Bucket policy mutation acquires the bucket and owning-project policy locks in global
stripe order, revalidates bucket ownership, and holds both locks through authorization
and the policy write. Object mutations acquire every applicable bucket and owning-project
policy lock in the same global order before entering the storage mutation lock. The
shared REST filter and gRPC interceptor do not hold a lock
across authorization and the subsequent service mutation. Other service integrations
must use the service-level ordered-lock entry point when that consistency is required.
Cloud Logging holds its sink lock before acquiring the ordered destination policy locks
and GCS bucket/object locks, serializing delivery with sink configuration changes.
Secret Manager holds the secret lock before the ordered project and secret policy
locks across authorization and the secret or version operation.
Custom-role definitions are serialized among themselves but not with these policy
locks, and evaluation reads them without a lock. Each read sees the latest stored
definition, so a role edit, disable, delete, or undelete applies to reads after it is
stored, and one decision that overlaps the change can observe both versions. A role
activated between those reads has its condition evaluated without prior validation; an
unsupported condition then fails closed in the evaluator rather than with
`INVALID_ARGUMENT`.

## Upstream evidence

The Resource Manager v1, Cloud Run v2, and Secret Manager v1 protocol and role
contracts are defined by these upstream references:

- [Resource Manager v1 project get](https://docs.cloud.google.com/resource-manager/reference/rest/v1/projects/get)
  [project policy constraints](https://docs.cloud.google.com/resource-manager/reference/rest/v1/projects/setIamPolicy),
  and [Resource Manager roles](https://docs.cloud.google.com/iam/docs/roles-permissions/resourcemanager).
- [IAM resource attributes](https://docs.cloud.google.com/iam/docs/conditions-resource-attributes),
  [expression protobuf](https://github.com/googleapis/googleapis/blob/master/google/type/expr.proto), and
  [Google API errors](https://google.aip.dev/193).
- [Cloud Run role permissions](https://docs.cloud.google.com/iam/docs/roles-permissions/run)
  and [Cloud Run service IAM methods](https://docs.cloud.google.com/run/docs/reference/rest/v2/projects.locations.services).
- [Cloud Run v2 service proto](https://github.com/googleapis/googleapis/blob/master/google/cloud/run/v2/service.proto),
  [revision proto](https://github.com/googleapis/googleapis/blob/master/google/cloud/run/v2/revision.proto),
  and [REST discovery document](https://run.googleapis.com/$discovery/rest?version=v2).
- [Secret Manager v1 service proto](https://github.com/googleapis/googleapis/blob/master/google/cloud/secretmanager/v1/service.proto)
  and [Secret Manager access control](https://docs.cloud.google.com/secret-manager/docs/access-control).

The REST client implementation is in the [Resource Manager v1 Java SDK source](https://github.com/googleapis/google-api-java-client-services/blob/main/clients/google-api-services-cloudresourcemanager/v1/2.0.0/com/google/api/services/cloudresourcemanager/CloudResourceManager.java).
The Cloud Run client HTTP JSON mappings are in the
[Java service stub](https://github.com/googleapis/google-cloud-java/blob/main/java-run/google-cloud-run/src/main/java/com/google/cloud/run/v2/stub/HttpJsonServicesStub.java)
and [revision stub](https://github.com/googleapis/google-cloud-java/blob/main/java-run/google-cloud-run/src/main/java/com/google/cloud/run/v2/stub/HttpJsonRevisionsStub.java).
The shared policy transport uses the [IAM v1 policy proto](https://github.com/googleapis/googleapis/blob/master/google/iam/v1/iam_policy.proto).

Denial codes and permission requirements follow these contracts. Exact production
message text, error metadata, revocation timing, and alias resolution are not
compatibility guarantees.

### IAM service-account evidence

- [IAM v1 discovery document](https://iam.googleapis.com/$discovery/rest?version=v1)
  and [IAM admin proto](https://github.com/googleapis/googleapis/blob/master/google/iam/admin/v1/iam.proto).
- [IAM roles and permissions](https://docs.cloud.google.com/iam/docs/roles-permissions/iam),
  including the basic-role membership listed for each checked permission.
- [Credentials v1 discovery document](https://iamcredentials.googleapis.com/$discovery/rest?version=v1)
  and [Credentials proto](https://github.com/googleapis/googleapis/blob/master/google/iam/credentials/v1/iamcredentials.proto).
- [Credentials signBlob](https://docs.cloud.google.com/iam/docs/reference/credentials/rest/v1/projects.serviceAccounts/signBlob)
  and [signJwt](https://docs.cloud.google.com/iam/docs/reference/credentials/rest/v1/projects.serviceAccounts/signJwt)
  permission references.
- [IAM REST Java SDK source](https://github.com/googleapis/google-api-java-client-services/blob/main/clients/google-api-services-iam/v1/2.0.0/com/google/api/services/iam/v1/Iam.java)
  and [Credentials HTTP JSON stub](https://github.com/googleapis/google-cloud-java/blob/main/java-iamcredentials/google-cloud-iamcredentials/src/main/java/com/google/cloud/iam/credentials/v1/stub/HttpJsonIamCredentialsStub.java).

`IamServiceAccountAuthorizationServiceTest` inventories the implemented handlers
and permission targets. The service-account integration tests exercise denial
state, conditions, policy escalation, alias resolution, role slices, and disabled
controls. `IamServiceAccountIamEnforcementTest` exercises official IAM REST,
Credentials HTTP JSON, and generated shared IAM policy gRPC clients in both modes.
The compatibility workflow runs it against its isolated enforcing native server.
Local evidence does not establish production IAM condition or Organization Policy
compatibility.

### IAM custom-role evidence

- [IAM admin proto](https://github.com/googleapis/googleapis/blob/master/google/iam/admin/v1/iam.proto)
  `Role`, `RoleView`, `Role.RoleLaunchStage`, and the create, get, list, update, delete, and
  undelete request messages and HTTP bindings, and the
  [IAM v1 discovery document](https://iam.googleapis.com/$discovery/rest?version=v1).
- Method permissions on [projects.roles](https://docs.cloud.google.com/iam/docs/reference/rest/v1/projects.roles)
  and [organizations.roles](https://docs.cloud.google.com/iam/docs/reference/rest/v1/organizations.roles),
  and the role slices in [IAM roles and permissions](https://docs.cloud.google.com/iam/docs/roles-permissions/iam).
- [Understanding custom roles](https://docs.cloud.google.com/iam/docs/understanding-custom-roles) and
  [creating custom roles](https://docs.cloud.google.com/iam/docs/creating-custom-roles) for scope,
  limits, disabled and deleted behavior, and role ID reuse.
- [IAM REST Java SDK source](https://github.com/googleapis/google-api-java-client-services/blob/main/clients/google-api-services-iam/v1/2.0.0/com/google/api/services/iam/v1/Iam.java)
  `Projects.Roles` and `Organizations.Roles` request builders.

`IamRoleAuthorizationServiceTest` inventories the role handlers and permission targets.
`IamCustomRoleEnforceIntegrationTest` and `IamCustomRoleDisabledIntegrationTest` share one
contract covering management denials without mutation, conditions, Pub/Sub REST and gRPC
grants, filtered `testIamPermissions`, cross-project and cross-organization bindings, and
revocation. `IamCustomRoleIamEnforcementTest` repeats the management, Pub/Sub grant, filtering,
boundary, and revocation paths with the official IAM, Resource Manager, and Pub/Sub
REST clients and the generated Pub/Sub gRPC client. It also covers resource-name
conditions on project and organization role management and on custom-role grants over
Pub/Sub REST and gRPC, including unchanged resources after denied mutations and the
same requests with enforcement disabled.

The following were checked against live GCP (IAM v1 REST, one organization and one
project under a folder): basic-role
`iam.roles.*` membership from `gcloud iam roles describe`; error codes and messages for
duplicate IDs, reused deleted IDs, stale etags, deleted-role updates and deletes,
undeleting an active role, invalid role IDs, titles, descriptions, stages, views, update
masks, role names, page sizes, permission counts, and wrongly typed fields; malformed and
non-string etags; byte-based length limits; error precedence for stale etags on deleted
roles; unmasked patch replacement; per-method permissions and targets for a caller holding
`roles/iam.roleViewer`; exact custom-role grants and `testIamPermissions` filtering on a
Pub/Sub topic; revocation timing; organization-role management permissions and targets
with and without `roles/iam.organizationRoleViewer`; and policy-write rejection of
another project's role and of new grants to deleted roles. GCP also allowed an
organization role in a project under a folder, which Floci does not model.

The IAM v1 Java REST client sends an empty form content type for bodyless policy
reads and deletes. The matching IAM REST handlers accept that empty-body path as
well as JSON; payload-bearing operations retain their JSON contract. This behavior
is exercised by the unmodified official SDK request builders above; the shared
[Google Java request builder](https://github.com/googleapis/google-api-java-client/blob/main/google-api-client/src/main/java/com/google/api/client/googleapis/services/AbstractGoogleClientRequest.java)
uses empty content for bodyless custom POST methods.

### Cloud Scheduler evidence

- [Scheduler v1 proto](https://github.com/googleapis/googleapis/blob/master/google/cloud/scheduler/v1/cloudscheduler.proto)
  and [HTTP JSON SDK stub](https://github.com/googleapis/google-cloud-java/blob/main/java-scheduler/google-cloud-scheduler/src/main/java/com/google/cloud/scheduler/v1/stub/HttpJsonCloudSchedulerStub.java).
- REST [create](https://docs.cloud.google.com/scheduler/docs/reference/rest/v1/projects.locations.jobs/create),
  [list](https://docs.cloud.google.com/scheduler/docs/reference/rest/v1/projects.locations.jobs/list),
  and [resume](https://docs.cloud.google.com/scheduler/docs/reference/rest/v1/projects.locations.jobs/resume)
  define parent targets and the `cloudscheduler.jobs.enable` permission.
- [Scheduler access control and role permissions](https://docs.cloud.google.com/scheduler/docs/access-control).

`SchedulerIamAuthorizationAdapterServiceTest` inventories all eight handlers and
RPCs. `SchedulerIamEnforcementTest` uses the official Java client over REST and
gRPC, with restricted emulator-issued credentials, positive controls, denial state
assertions, observed Pub/Sub delivery after permitted/denied runs, JSON null
normalization, domain-scoped project names, and disabled-mode controls. The compatibility workflow includes it
in the isolated enforcing native run. See the [Scheduler inventory](services/scheduler.md#iam-enforcement)
for local condition semantics and unsupported targets.
