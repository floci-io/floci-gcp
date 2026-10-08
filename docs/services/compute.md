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

## Instances and disks

Zonal instances support insert/get/list/aggregatedList/start/stop/reset/delete,
setMachineType, setMetadata, setTags, setLabels, addAccessConfig/deleteAccessConfig,
attachDisk/detachDisk and setDiskAutoDelete. A stopped VM has status TERMINATED;
reset does not imply guest health. One IPv4 NIC and one boot disk are required.
Machine changes require a stopped VM. Ephemeral external addresses are released
on stop; reserved addresses remain associated.

Zonal disks support insert/get/list/aggregatedList/delete/resize/update/setLabels.
Attached disks cannot be deleted. Deleting an instance retains disks unless their
attachment enables autoDelete. Disk sizes and source sizes are validated. Only
supported Hyperdisk types accept provisioned IOPS/throughput; unsupported knobs
fail. Performance limits are basic API checks, not complete machine/disk quota
or size-ratio emulation. Multi-writer disks and regional disks are unsupported.

## Images and snapshots

Global images support sources from disks, images and snapshots, family lookup,
labels, inventory and deletion. Global standard snapshots support disk sources,
labels, inventory and deletion. Source identity and size survive source deletion;
restored disks cannot be smaller than their source. Images do not acquire invented
backing snapshots. Copying a snapshot means restoring a disk and snapshotting it.
Guest disk bytes and regional/instant snapshots are not emulated.

### Public images

A small synthetic catalog of Google-published images is served read-only from the
well-known image projects, so `debian-cloud/debian-12`, `ubuntu-os-cloud/ubuntu-2404-lts-amd64`
and similar references work from any project:

| Project | Families |
|---|---|
| `debian-cloud` | `debian-11`, `debian-12`, `debian-12-arm64`, `debian-13` |
| `ubuntu-os-cloud` | `ubuntu-2004-lts`, `ubuntu-2204-lts`, `ubuntu-2204-lts-arm64`, `ubuntu-2404-lts-amd64`, `ubuntu-2404-lts-arm64`, `ubuntu-minimal-2404-lts-amd64` |
| `cos-cloud` | `cos-stable`, `cos-beta`, `cos-dev`, `cos-117-lts` |
| `rocky-linux-cloud` | `rocky-linux-8`, `rocky-linux-9` |
| `centos-cloud` | `centos-stream-9` |
| `rhel-cloud` | `rhel-8`, `rhel-9` |

`images.get`, `images.list` and `images.getFromFamily` work against those projects.
`instances.insert` (`initializeParams.sourceImage`) and `disks.insert` (`sourceImage`)
accept a full URL, `projects/{p}/global/images/{name}` or
`projects/{p}/global/images/family/{family}` pointing at a public image from any project;
the stored disk records the resolved image URL. Each family has one image with a fixed
name and creation date. Public image projects are read-only (`images.insert` returns 403),
and references to other projects' images remain unsupported (400).

## IAM policies

Instances, disks, images, snapshots and subnetworks support `getIamPolicy` (GET),
`setIamPolicy` (POST, with the policy under `policy`) and `testIamPermissions` (POST) on
the resource path, for example `zones/{zone}/instances/{name}/setIamPolicy` and
`global/images/{name}/getIamPolicy`. Policies are stored in the shared IAM policy store,
use etag concurrency checks, and are discarded when the resource is deleted. They are
not enforced against Compute requests. This backs Terraform
`google_compute_instance_iam_member` and the other `google_compute_*_iam_*` resources
for these collections.

## Global external application load balancer

Zonal GCE_VM_IP_PORT endpoint groups support endpoint attach/detach/list. Global
HTTP health checks, EXTERNAL_MANAGED backend services, URL maps, HTTP target proxies
and TCP forwarding rules support configuration and ordered cleanup. Backend and
URL-map updates require current fingerprints. Path matchers, host rules, pathRules
and session-affinity configuration are validated; actual request routing, health
checks, TLS certificates, regional load balancing and advanced routeRules are not
implemented. Endpoint membership is stored separately from the public NEG resource.

### Address ownership and release

Global external HTTP forwarding rules can share a PREMIUM IPv4 address when
their TCP ports do not overlap. Ownership uses complete resource links, and
deleting one rule preserves every other owner. A forwarding rule's IP address
is immutable; changing it requires replacing the rule.

Deleting an in-use address reservation through the API releases the reservation,
not the IP attached to its instance or forwarding rule. The attached address
remains unavailable to the allocator until the consumer releases it. This follows
[Google's API release semantics](https://docs.cloud.google.com/vpc/docs/reserve-static-external-ip-address#release_ip),
which differ from the Cloud console's restriction on releasing in-use addresses.
