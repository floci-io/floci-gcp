# Compute Engine

Compute Engine uses its native REST v1 paths under `/compute/v1/projects/{project}`
on port 4588. Configure SDK endpoints explicitly and use synthetic credentials.
The emulator does not execute virtual machines or forward network traffic.

Set `FLOCI_GCP_SERVICES_COMPUTE_ENABLED=false` to disable the service.
`FLOCI_GCP_SERVICES_COMPUTE_OPERATION_DELAY_MS` defaults to 50 ms.
`FLOCI_GCP_SERVICES_COMPUTE_REGIONS` defaults to `us-central1,europe-west1`;
each configured region has synthetic zones `a`, `b`, and `c`.
Machine, accelerator and disk catalogs are a small deterministic fixture catalog,
not an assertion of current Google availability, quotas or pricing.

Resources and operations share a project-scoped checkpoint through StorageFactory.
Normal writes are validated against a private snapshot before committing. Operation
completion is advanced when the project is read, using persisted timestamps, so
polling and restart recovery do not depend on a request-scoped background thread.
Resource metadata changes are committed when the operation is accepted; lifecycle
status becomes final at operation completion. No guest progress is measured.

## Networking and operations

Custom-mode networks, regional subnetworks, firewall rules, and global/regional
IPv4 addresses support insert/get/list/delete. Firewalls also support updates.
Subnet overlap, scope, references, firewall ports and dependent-resource deletion
are validated. External addresses come from the documentation-only 203.0.113.0/24
range. Auto-mode VPCs, IPv6, Shared VPC and cross-project references are unsupported.

Global, regional and zonal operations support get/list/wait/delete. A wait may
return an unfinished operation, which clients must continue polling. Nonzero UUID
request IDs deduplicate mutations. Operation responses use Compute's `status`,
`targetLink` and scope fields, not `google.longrunning.Operation`.

Inventory supports `maxResults`, opaque `pageToken`, name ordering and descending
creation time. Filters support equality/inequality/existence on name, id, status,
family and labels, combined with AND. Other expressions fail explicitly. Tokens
are bound to project, collection, scope, filters and ordering. Inventory is not a
snapshot across concurrent writes.

Public interfaces follow the [Compute REST reference](https://docs.cloud.google.com/compute/docs/reference/rest/v1).
Credential acceptance retains the emulator's existing auth bypass; resource
isolation is not IAM enforcement.
