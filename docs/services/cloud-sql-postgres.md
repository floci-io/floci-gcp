# Cloud SQL (PostgreSQL and MySQL)

floci-gcp emulates Cloud SQL Admin API metadata over REST JSON and runs each instance's data plane in
a Docker container: `postgres` for `POSTGRES_*` database versions, `mysql` for `MYSQL_*`. The engine
is chosen per instance from `databaseVersion`; one emulator serves both.

| Config | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_CLOUDSQL_ENABLED` | `true` | Enable/disable Cloud SQL |
| `FLOCI_GCP_SERVICES_CLOUDSQL_MOCK` | `false` | Mock mode: emulate Cloud SQL resources without Docker-backed database instances |
| `FLOCI_GCP_SERVICES_CLOUDSQL_POSTGRES15_IMAGE` | `postgres:15.18-alpine` | Docker image for `POSTGRES_15` instances |
| `FLOCI_GCP_SERVICES_CLOUDSQL_POSTGRES16_IMAGE` | `postgres:16.14-alpine` | Docker image for `POSTGRES_16` instances |
| `FLOCI_GCP_SERVICES_CLOUDSQL_POSTGRES17_IMAGE` | `postgres:17.10-alpine` | Docker image for `POSTGRES_17` instances |
| `FLOCI_GCP_SERVICES_CLOUDSQL_POSTGRES18_IMAGE` | `postgres:18.4-alpine` | Docker image for `POSTGRES_18` instances |
| `FLOCI_GCP_SERVICES_CLOUDSQL_MYSQL80_IMAGE` | `mysql:8.0.46` | Docker image for `MYSQL_8_0` (and `MYSQL_8_0_NN`) instances |
| `FLOCI_GCP_SERVICES_CLOUDSQL_MYSQL84_IMAGE` | `mysql:8.4.11` | Docker image for `MYSQL_8_4` instances |
| `FLOCI_GCP_SERVICES_CLOUDSQL_STARTUP_TIMEOUT_SECONDS` | `90` | Time to wait for engine readiness after container start |

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

Creating an instance accepts PostgreSQL (`POSTGRES_15` to `POSTGRES_18`) and MySQL (`MYSQL_8_0`,
`MYSQL_8_0_NN`, `MYSQL_8_4`) `databaseVersion` values, starts the matching Docker container, stores a
`RUNNABLE` instance resource, seeds the engine's system database metadata (`postgres`; or
`information_schema`, `mysql`, `performance_schema`, `sys`), and returns an immediately completed
`sql#operation`. Any other engine (SQL Server) or version is rejected with `400 INVALID_ARGUMENT`, in
mock mode as well.

`instances.insert` returns only once the engine accepts connections. PostgreSQL is ready in a few
seconds; a MySQL instance initialises its data directory first and typically takes 15 to 25 seconds
on a cold start, which is longer than the 20 second read timeout some Google API clients default to.
Raise the client's read timeout if you see a client-side timeout on `instances.insert` for MySQL
(the operation is only returned once startup completes, so there is nothing to poll before then). `rootPassword` on the request is accepted and never echoed back, as in
the real API, but the emulator keeps the admin login fixed (`postgres`/`postgres`, `root`/`root`).

`tiers.list` and `flags.list` return static metadata (flags carry `appliesTo` for both engines) so SDKs, gcloud, and IaC providers can complete discovery flows without contacting Google Cloud.

`instances.get` and `connect.get` include `ipAddresses[0].ipAddress` plus an emulator-specific
`ipAddresses[0].port` field for local pgjdbc/libpq connections. `connectionName` keeps the normal
Cloud SQL shape (`project:region:instance`) for SDK/Admin API compatibility.

### Zones and location preference

`region` defaults to `us-central1`, or to the region of `settings.locationPreference.zone` when only
the zone is sent. `gceZone` reports `settings.locationPreference.zone` when it is set (on insert,
patch and update), and `<region>-a` otherwise. `secondaryGceZone` reports
`settings.locationPreference.secondaryZone` only for `REGIONAL` instances; `no_secondary_zone`, a
`ZONAL` availability type, or no secondary zone leaves it unset (floci does not pick a failover zone
on its own). `settings.locationPreference` is returned as sent, with `kind: sql#locationPreference`,
so Terraform's `location_preference` block plans clean.

A zone outside the instance region is accepted by default. Set `FLOCI_GCP_LOCATIONS_STRICT=true` to
reject it (and a mismatched `secondaryZone`) with `400 INVALID_ARGUMENT`.

Database and user Admin API operations are synchronized into the backing PostgreSQL server:

- `databases.insert` creates a PostgreSQL database.
- `databases.delete` drops the PostgreSQL database.
- `users.insert` and `users.update` create/update PostgreSQL login roles.
- `users.delete` drops objects owned by the role in known databases, then drops the role.
- Created users receive connect/create privileges on existing and newly created databases.
- `users.list` includes the `postgres` role the instance is provisioned with (`type: BUILT_IN`).
  It cannot be deleted, and a password update on it is acknowledged without changing the server;
  instances persisted by an older floci-gcp gain the entry on the next start.

On a MySQL instance the same operations are synchronized into the backing MySQL server, with the
engine's own identity model:

- Users are host-qualified: `users.insert` without `host` creates `'name'@'%'`, and `users.get`,
  `users.update` and `users.delete` take `?host=` to address a specific identity (omitted means `%`).
  PostgreSQL instances still reject a `host`.
- `users.list` includes the `root@%` account the instance is provisioned with (`type: BUILT_IN`).
  As on Cloud SQL it is an ordinary user: it can be re-passworded, deleted and inserted again,
  which is what the Terraform `google_sql_database_instance` resource does right after creating a
  MySQL instance. The emulator itself connects over the container's Unix socket as
  `root@localhost`, so that one identity cannot be created through the API; `root` at any other
  host is an ordinary user.
- Created users receive `ALL PRIVILEGES` on every existing and later user database; system schemas
  are not granted.
- New databases report `utf8mb4` / `utf8mb4_0900_ai_ci` when the request sets neither `charset` nor
  `collation`. A request naming only one of them has only that one applied and reported; the server
  picks the matching counterpart, which is not read back.
- The four system schemas are listed by `databases.list` and cannot be deleted, like `postgres` on PostgreSQL.

Docker storage follows the global floci-gcp storage policy. In named-volume mode, each instance gets
a stable `floci-gcp-cloudsql-*` volume (with `floci-gcp.docker.resource-namespace` configured, new
volumes are named `floci-gcp-<ns>-cloudsql-*`; volumes created before the name was persisted keep
their original name). `memory` mode, or `floci-gcp.storage.prune-volumes-on-delete=true`,
removes the volume when the instance is deleted; `persistent`, `hybrid`, and `wal` retain volumes by
default. When `floci-gcp.storage.host-persistent-path` is absolute, instance data is bind-mounted under
`{hostPersistentPath}/cloudsql/{project}/{instance}`.

## Limitations

- User passwords are accepted for Admin API compatibility but are not persisted or returned.
- Instance `etag` values are generated but not enforced for optimistic concurrency on updates.
- Operations are retained as metadata after target resources are deleted.
- Host-qualified Cloud SQL users are rejected on PostgreSQL because role sync is name-based.
- `instances.insert` is synchronous, so a slow engine start surfaces as request latency rather than
  as a `PENDING_CREATE` instance with a `RUNNING` operation.

## Not Implemented

- SQL Server instances
- Backups, SSL cert operations, import/export, failover, replicas, and maintenance operations
- IAM policy methods for Cloud SQL resources
