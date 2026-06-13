# Cloud SQL for PostgreSQL

floci-gcp emulates the first slice of the Cloud SQL Admin API for PostgreSQL over REST JSON.

| Config | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_CLOUDSQL_ENABLED` | `true` | Enable/disable Cloud SQL for PostgreSQL |

## Supported API Surface

| Operation | Path |
|---|---|
| Create instance | `POST /v1/projects/{project}/instances` |
| List instances | `GET /v1/projects/{project}/instances` |
| Get instance | `GET /v1/projects/{project}/instances/{instance}` |
| Patch instance | `PATCH /v1/projects/{project}/instances/{instance}` |
| Update instance | `PUT /v1/projects/{project}/instances/{instance}` |
| Delete instance | `DELETE /v1/projects/{project}/instances/{instance}` |
| List tiers | `GET /v1/projects/{project}/tiers` |
| List flags | `GET /v1/flags` |
| Get connect settings | `GET /v1/projects/{project}/instances/{instance}/connectSettings` |
| Get operation | `GET /v1/projects/{project}/operations/{operation}` |
| List operations | `GET /v1/projects/{project}/operations` |
| Create database | `POST /v1/projects/{project}/instances/{instance}/databases` |
| List databases | `GET /v1/projects/{project}/instances/{instance}/databases` |
| Get database | `GET /v1/projects/{project}/instances/{instance}/databases/{database}` |
| Update database | `PUT /v1/projects/{project}/instances/{instance}/databases/{database}` |
| Patch database | `PATCH /v1/projects/{project}/instances/{instance}/databases/{database}` |
| Delete database | `DELETE /v1/projects/{project}/instances/{instance}/databases/{database}` |
| Create user | `POST /v1/projects/{project}/instances/{instance}/users` |
| List users | `GET /v1/projects/{project}/instances/{instance}/users` |
| Get user | `GET /v1/projects/{project}/instances/{instance}/users/{user}` |
| Update user | `PUT /v1/projects/{project}/instances/{instance}/users?name={user}` |
| Delete user | `DELETE /v1/projects/{project}/instances/{instance}/users?name={user}` |

The same API surface is also exposed under `/v1beta4/projects/{project}` and the legacy discovery base path `/sql/v1beta4/projects/{project}`.

## Behavior

Cloud SQL resources are metadata only. Creating an instance accepts PostgreSQL `databaseVersion` values, stores a `RUNNABLE` instance resource, creates the default `postgres` database metadata, and returns an immediately completed `sql#operation`.

`tiers.list` and `flags.list` return static PostgreSQL-oriented metadata so SDKs, gcloud, and IaC providers can complete discovery flows without contacting Google Cloud.

No PostgreSQL data-plane container is started yet. Applications cannot connect to the returned `connectionName`; this first slice is intended for Admin API and IaC workflows that need Cloud SQL resource metadata.

## Phase 1 Limitations

- User passwords are accepted for Admin API compatibility but are not persisted or returned.
- Instance `etag` values are generated but not enforced for optimistic concurrency on updates.
- Operations are retained as metadata after target resources are deleted.
- Resource payloads are metadata-only and do not create PostgreSQL databases or roles in a running server.

## Not Implemented

- PostgreSQL server/container runtime
- Backups, SSL cert operations, import/export, failover, replicas, and maintenance operations
- IAM policy methods for Cloud SQL resources
