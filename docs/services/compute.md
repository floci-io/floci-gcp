# Compute Engine

Compute Engine uses its native REST v1 paths under `/compute/v1/projects/{project}`
on port 4588. Configure SDK endpoints explicitly and use synthetic credentials.
By default the emulator does not execute virtual machines or forward network traffic.
With `FLOCI_GCP_SERVICES_COMPUTE_MOCK=false` each instance runs as a Docker container;
see [Instance containers](#instance-containers).

Set `FLOCI_GCP_SERVICES_COMPUTE_ENABLED=false` to disable the service.
`FLOCI_GCP_SERVICES_COMPUTE_OPERATION_DELAY_MS` defaults to 50 ms.
Regions and zones come from the bundled [location catalog](locations.md): all 43 regions and
their 130 real zones (for example `us-central1-f` and `europe-west1-d`). To serve fewer regions,
set `FLOCI_GCP_SERVICES_COMPUTE_REGIONS` to a comma-separated list, such as
`us-central1,europe-west1`. Each listed region keeps its real zones from the catalog. Unset or
empty serves the whole catalog. Any region or zone outside that set returns `404`.
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
range. Auto-mode VPCs, IPv6, Shared VPC and cross-project references are unsupported,
except [public images](#public-images).

Global, regional and zonal operations support get/list/wait/delete. A wait may
return an unfinished operation, which clients must continue polling. Nonzero UUID
request IDs deduplicate mutations. Operation responses use Compute's `status`,
`targetLink` and scope fields, not `google.longrunning.Operation`.

Inventory supports `maxResults`, opaque `pageToken`, name ordering and descending
creation time. Filters support equality/inequality/existence on name, id, status,
family and labels, combined with AND. Other expressions fail explicitly. Tokens
are bound to project, collection, scope, filters and ordering. Inventory is not a
snapshot across concurrent writes.

`projects.get` returns the project's Compute resource: its number as `id`, the
default Compute service account, and `commonInstanceMetadata`.
`projects.setCommonInstanceMetadata` replaces that metadata through a global
operation. It needs the current fingerprint, so a missing or stale one fails with
412 (`conditionNotMet`). Keys must match `[a-zA-Z0-9-_]{1,128}`, values must be
strings of at most 256 KiB, and keys must be unique. The other project methods
(usage export, default network tier, Shared VPC, moves) are unsupported.

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

The public image projects `debian-cloud`, `ubuntu-os-cloud`, `rocky-linux-cloud`,
`centos-cloud` and `cos-cloud` serve a small read-only catalog, with one current
image per family. Any project can boot from these images, or copy them into an
image of its own:

| Project | Families |
|---|---|
| `debian-cloud` | `debian-13`, `debian-13-arm64`, `debian-12`, `debian-12-arm64`, `debian-11` |
| `ubuntu-os-cloud` | `ubuntu-2404-lts-amd64`, `ubuntu-2404-lts-arm64`, `ubuntu-2204-lts`, `ubuntu-2204-lts-arm64` |
| `rocky-linux-cloud` | `rocky-linux-9`, `rocky-linux-8` |
| `centos-cloud` | `centos-stream-9` |
| `cos-cloud` | `cos-stable`, `cos-121-lts` |

`images.get`, `images.list` (including `filter`) and `images.getFromFamily` work in
those projects. A boot disk created from a family
(`projects/debian-cloud/global/images/family/debian-12`) records the concrete image
(`debian-12-bookworm-v20261001`) in `sourceImage`, as GCP does. Image names follow
Google's naming, so Terraform's `image = "debian-cloud/debian-12"` resolves and
shows no diff on later plans. Writes into a public project, and reads of anything
other than its images, return 403. Image versions are fixed: there are no deprecated
or older images, licenses or guest OS features.

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

## Instance containers

Set `FLOCI_GCP_SERVICES_COMPUTE_MOCK=false` to back every instance with a Docker
container. This mode is opt-in, and it needs the Docker socket, as GKE and Cloud
Run do. The API stays the same in both modes; only these behaviours change:

| API call | Container |
|---|---|
| `instances.insert` | Pulls the boot image's Docker image (status `PROVISIONING`), then creates and starts the container (`STAGING`). The operation reaches `DONE` and the instance `RUNNING` once the container is up |
| `instances.stop` | `docker stop`, then `TERMINATED`. The stopped container is kept, as a stopped VM keeps its boot disk |
| `instances.start` | `docker start` of the kept container, or a new one if it is gone |
| `instances.reset` | `docker restart` |
| `instances.setMachineType` | Removes the stopped container, so the next start creates one with the new limits |
| `instances.delete` | `docker rm -f` |

The container runs the boot image's Docker image with an idle init process, so
nothing in it runs until you exec into it (`docker exec -it <container> bash`).
Startup scripts, the metadata server and SSH come in later releases. The
container is named `floci-gcp-compute-<project>-<zone>-<instance>` and carries
the [resource identity labels](../configuration/docker-compose.md#resource-identity-labels)
with `io.floci.service=compute`. With `FLOCI_GCP_SERVICES_COMPUTE_INSTANCE_RESOURCE_LIMITS`
on (the default), it is limited to the machine type's vCPUs and memory.

**Images.** The boot disk's source image picks the Docker image. A bundled
catalog matches on the image's `family` first, then on its name prefix:
`debian-12` maps to `debian:12`, `ubuntu-2204-lts` to `ubuntu:22.04`,
`rocky-linux-9` to `rockylinux:9`, and so on. Anything else runs
`FLOCI_GCP_SERVICES_COMPUTE_DEFAULT_IMAGE` (`debian:12`), with a warning in the log.
To use your own mapping, point `FLOCI_GCP_SERVICES_COMPUTE_IMAGE_CATALOG_PATH` at a
JSON file in the same format as `compute/image-catalog.json`:

```json
{"images": [{"family": "golden", "prefixes": ["golden-"], "dockerImage": "registry.local/golden:1"}]}
```

**Failures.** If Docker is unreachable, the pull fails, or the container is not
up within `FLOCI_GCP_SERVICES_COMPUTE_LAUNCH_TIMEOUT` (5 minutes), the instance
still reaches `RUNNING`, without a container, and a warning is logged. The API
never fails because of Docker.

**Restarts.** On shutdown, floci-gcp stops the running instance containers. On
startup it starts the containers of `RUNNING` instances again, keeps those of
`TERMINATED` instances, and removes those whose instance was deleted. It only
touches containers that carry its own `io.floci.owner` label, so several
emulators can share one Docker daemon.

