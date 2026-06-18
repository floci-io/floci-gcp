# Cloud SQL for PostgreSQL

floci-gcp emulates Cloud SQL Admin API metadata for PostgreSQL over REST JSON and runs PostgreSQL
data-plane instances in Docker-backed `postgres` containers.

| Config | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_CLOUDSQL_ENABLED` | `true` | Enable/disable Cloud SQL for PostgreSQL |
| `FLOCI_GCP_SERVICES_CLOUDSQL_DATA_PLANE_ENABLED` | `true` | Start Docker-backed PostgreSQL instances for Cloud SQL resources |
| `FLOCI_GCP_SERVICES_CLOUDSQL_POSTGRES15_IMAGE` | `postgres:15.18-alpine` | Docker image for `POSTGRES_15` instances |
| `FLOCI_GCP_SERVICES_CLOUDSQL_POSTGRES16_IMAGE` | `postgres:16.14-alpine` | Docker image for `POSTGRES_16` instances |
| `FLOCI_GCP_SERVICES_CLOUDSQL_POSTGRES17_IMAGE` | `postgres:17.10-alpine` | Docker image for `POSTGRES_17` instances |
| `FLOCI_GCP_SERVICES_CLOUDSQL_POSTGRES18_IMAGE` | `postgres:18.4-alpine` | Docker image for `POSTGRES_18` instances |
| `FLOCI_GCP_SERVICES_CLOUDSQL_STARTUP_TIMEOUT_SECONDS` | `90` | Time to wait for PostgreSQL readiness after container start |

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

Creating an instance accepts PostgreSQL `databaseVersion` values, starts a matching Docker `postgres`
container, stores a `RUNNABLE` instance resource, creates the default `postgres` database metadata,
and returns an immediately completed `sql#operation`.

`tiers.list` and `flags.list` return static PostgreSQL-oriented metadata so SDKs, gcloud, and IaC providers can complete discovery flows without contacting Google Cloud.

`instances.get` and `connect.get` include `ipAddresses[0].ipAddress` plus an emulator-specific
`ipAddresses[0].port` field for local pgjdbc/libpq connections. `connectionName` keeps the normal
Cloud SQL shape (`project:region:instance`) for SDK/Admin API compatibility.

Database and user Admin API operations are synchronized into the backing PostgreSQL server:

- `databases.insert` creates a PostgreSQL database.
- `databases.delete` drops the PostgreSQL database.
- `users.insert` and `users.update` create/update PostgreSQL login roles.
- `users.delete` drops objects owned by the role in known databases, then drops the role.
- Created users receive connect/create privileges on existing and newly created databases.

Docker storage follows the global floci-gcp storage policy. In named-volume mode, each instance gets
a stable `floci-gcp-cloudsql-*` volume. `memory` mode, or `floci-gcp.storage.prune-volumes-on-delete=true`,
removes the volume when the instance is deleted; `persistent`, `hybrid`, and `wal` retain volumes by
default. When `floci-gcp.storage.host-persistent-path` is absolute, instance data is bind-mounted under
`{hostPersistentPath}/cloudsql/{project}/{instance}`.

## Limitations

- User passwords are accepted for Admin API compatibility but are not persisted or returned.
- Instance `etag` values are generated but not enforced for optimistic concurrency on updates.
- Operations are retained as metadata after target resources are deleted.
- Host-qualified Cloud SQL users are rejected because PostgreSQL role sync is name-based.

## Not Implemented

- Backups, SSL cert operations, import/export, failover, replicas, and maintenance operations
- IAM policy methods for Cloud SQL resources
