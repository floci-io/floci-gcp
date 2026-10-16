# Resource Manager

floci-gcp emulates the Cloud Resource Manager v1 projects API
(`cloudresourcemanager.googleapis.com/v1`) over REST JSON. This is the API the Terraform
`google_project` resource and the `google_projects` and `google_project_ancestry` data
sources use, and the one IaC providers call to look up a project before they manage
project-scoped resources.

Projects live in a shared project registry. It maps project IDs to project numbers,
records name, labels, parent, lifecycle state and billing account, and persists with the configured storage
mode.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_RESOURCEMANAGER_ENABLED` | `true` | Enable/disable the Resource Manager API |
| `FLOCI_GCP_PROJECTS_STRICT` | `false` | Only known projects exist (see [Strict mode](#strict-mode)) |

## Endpoints

| Method | Path | Notes |
|---|---|---|
| `POST` | `/v1/projects` | Create. Returns an Operation that is already `done` |
| `GET` | `/v1/projects/{projectIdOrNumber}` | Get by project ID or project number |
| `PUT` | `/v1/projects/{projectId}` | Update `name`, `labels` and `parent` |
| `DELETE` | `/v1/projects/{projectId}` | Moves the project to `DELETE_REQUESTED` |
| `POST` | `/v1/projects/{projectId}:undelete` | Moves it back to `ACTIVE` |
| `GET` | `/v1/projects?filter=&pageSize=&pageToken=` | List |
| `POST` | `/v1/projects/{projectId}:getAncestry` | The project, then its parent if set |
| `GET` | `/v1/operations/cp.{id}` | Project-creation operation |
| `POST` | `/v1/projects/{projectId}:getIamPolicy` | Project IAM policy |
| `POST` | `/v1/projects/{projectId}:setIamPolicy` | |
| `POST` | `/v1/projects/{projectId}:testIamPermissions` | |

### Create

`projectId` must be 6 to 30 lowercase letters, digits or hyphens, start with a letter and
not end with a hyphen. When `name` is set it must be 4 to 30 letters, digits, hyphens,
quotes, spaces or exclamation points. `parent` must be `{"type": "organization" | "folder",
"id": "..."}`. Creating an ID that was already created returns `409 ALREADY_EXISTS`.

The response is an Operation named `operations/cp.{projectNumber}` with `done: true`. Its
`metadata` is a `ProjectCreationStatus` (`gettable`, `ready`) and its `response` is the
Project, both carrying the `@type` URLs the Google clients expect.

### Lifecycle

`DELETE` only marks the project `DELETE_REQUESTED`. The project stays visible to get and
list, updates fail with `FAILED_PRECONDITION`, and `:undelete` restores it. The emulator
never purges it.

### List filter

The filter follows the v1 syntax. It takes space-separated `field:value` terms that are
OR'd together and matched case-insensitively. `*` is a wildcard, so `name:how*` is a prefix
match and `labels.color:*` matches any project with that label. The supported fields are
`name`, `id`, `labels.<key>`, `parent.type`, `parent.id` and `lifecycleState`. Any other
field returns `400 INVALID_ARGUMENT`.

## Projects you never created

By default, every project ID that appears in a request is registered as an `ACTIVE`
project on first use. This covers Resource Manager calls and any REST request that names a
project in its path, `project` query parameter or `x-goog-request-params` header. These
projects report the same values as before the registry existed: `name` equals the ID, the
project number is stable, and `createTime` is a fixed per-project timestamp. You can still
`POST /v1/projects` an ID that was only used implicitly; the create adopts it and keeps its
project number.

A project number resolves to the project it belongs to on Resource Manager. On REST requests to other services, the request's project context also maps the
number to the ID, so services that scope storage by that context (BigQuery, Cloud SQL,
Cloud Functions, Cloud Monitoring, GCS HMAC keys) see the same data under either form.
Services that build keys from the resource name in the path still treat
`projects/{number}` as a separate name. The default project's number always resolves. A number that
belongs to no known project returns `403 PERMISSION_DENIED` with `The caller does not have
permission`, which is how GCP answers for a project you cannot see.

## Strict mode

With `FLOCI_GCP_PROJECTS_STRICT=true`, only the default project
(`FLOCI_GCP_DEFAULT_PROJECT_ID`) and projects created through `POST /v1/projects` exist. A
REST request for any other project, whether it is a Resource Manager call, a project IAM
policy call or a request to another service, returns:

```json
{"error": {"code": 403, "message": "The caller does not have permission", "status": "PERMISSION_DENIED"}}
```

The wildcard projects `-` and `_` are never checked.

## IAM enforcement

Set `FLOCI_GCP_SERVICES_IAM_AUTHORIZATION_MODE=enforce` to enforce project metadata
reads and policy reads/writes for Floci-issued service-account tokens.
`testIamPermissions` evaluates the caller. Project policies also use the shared
IAM gRPC mixin. Project metadata has no gRPC endpoint.

Update, delete and undelete check `resourcemanager.projects.update`, `.delete` and
`.undelete`. `roles/owner` holds all three, `roles/editor` and
`roles/resourcemanager.projectMover` hold update, and `roles/resourcemanager.projectDeleter`
holds delete.

Service adapters can use the shared framework to inherit project policies.
Pub/Sub does not support IAM enforcement. Secret Manager secrets and versions
inherit applicable project bindings.

Default `disabled` mode stores policies without restricting requests. Anonymous
and external credentials bypass Resource Manager IAM in both modes. Project grants
can authorize supported GCS bucket and object operations and Secret Manager
operations through inheritance. They do not enforce permissions on Pub/Sub or
other unsupported services.
Organization and folder inheritance and project ID/number alias equivalence are not implemented.
See [IAM enforcement and limitations](iam.md#opt-in-enforcement).

## Scope and deviations

- Strict mode and number-to-ID mapping apply to REST requests only. gRPC services take
  the project from the full resource name and do not consult the registry, so strict mode
  does not reject unknown projects over gRPC.
- Deleted projects are never purged, so a deleted project ID cannot be reused, just as in
  GCP.
- `getAncestry` returns the project and its direct parent only. Folders and organizations
  are not modelled, so the chain above the parent is unknown.
- The creation operation is rebuilt from the current project each time it is read, so it
  reflects later updates.
- Resource Manager v3, folders, organizations, tags and org policies are not implemented.

## Quick Start

=== "Terraform"

    ```hcl
    provider "google" {
      resource_manager_custom_endpoint = "http://localhost:4588/v1/"
      cloud_billing_custom_endpoint    = "http://localhost:4588/v1/"
    }

    resource "google_project" "app" {
      project_id      = "my-app-project"
      name            = "My App Project"
      deletion_policy = "DELETE"
      labels          = { env = "dev" }
    }
    ```

    `google_project` reads the project's billing info on every refresh, so the provider also
    needs the [Cloud Billing](cloud-billing.md) endpoint. Export a fake token first:
    `export GOOGLE_OAUTH_ACCESS_TOKEN=fake-token`.

=== "curl"

    ```bash
    curl -X POST http://localhost:4588/v1/projects \
      -H 'Content-Type: application/json' \
      -d '{"projectId":"my-app-project","name":"My App Project"}'

    curl http://localhost:4588/v1/projects/my-app-project
    curl 'http://localhost:4588/v1/projects?filter=labels.env:dev'
    ```

See also [Cloud Billing](cloud-billing.md) for project billing links.
