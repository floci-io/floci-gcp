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
attachDisk/detachDisk, setDiskAutoDelete and getSerialPortOutput (see
[Serial port output](#serial-port-output)). A stopped VM has status TERMINATED;
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

The container runs the boot image's Docker image with an idle init process; the
guest scripts below and anything you start with `docker exec -it <container> bash`
run inside it, and the [metadata server](#metadata-server) is available to it. SSH
comes in a later release. The
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

### Startup and shutdown scripts

On every boot (insert, start and reset), the instance runs `startup-script` and
then `startup-script-url`, as root, as the guest environment's
`google_metadata_script_runner` does. Instance metadata overrides project metadata
(`projects.setCommonInstanceMetadata`) key by key. Each script is copied to
`/var/lib/google/<key>` and run verbatim; one without a shebang runs under
`/bin/sh`. `startup-script-url` is read from floci-gcp's own Cloud Storage
(`gs://BUCKET/FILE` or `https://storage.googleapis.com/BUCKET/FILE`); other URLs are
reported and skipped. The boot's operation does not wait for the scripts, and a
script still running after `FLOCI_GCP_SERVICES_COMPUTE_STARTUP_SCRIPT_TIMEOUT`
(30 minutes) is reported and left running.

`shutdown-script` and `shutdown-script-url` run on stop and delete while the
instance is `STOPPING`, for up to `FLOCI_GCP_SERVICES_COMPUTE_SHUTDOWN_SCRIPT_TIMEOUT`
(90 seconds). Reset is a hard reset and runs none.

### Container VMs

An instance whose metadata has a `gce-container-declaration` runs the declared
container, as Container-Optimized OS does with the container startup agent
(konlet). Terraform's `metadata = { gce-container-declaration = ... }` and
`gcloud compute instances create-with-container` both set it:

```yaml
spec:
  containers:
  - name: web
    image: nginx:1.27
    command: [/docker-entrypoint.sh]
    args: [nginx, -g, daemon off;]
    env:
    - name: MODE
      value: dev
    volumeMounts:
    - name: cache
      mountPath: /var/cache/nginx
  volumes:
  - name: cache
    emptyDir:
      medium: Memory
  restartPolicy: Always
```

On every boot, after the startup scripts, floci-gcp starts the container as a
second Docker container named `floci-gcp-compute-<project>-<zone>-<instance>_klt`,
in the instance container's network (konlet uses the VM's host network), so it
shares the instance's address and the [metadata server](#metadata-server) knows
it as that instance. It reads `image`, `command` (the entrypoint), `args` (the
command), `env`, `stdin`, `tty` and `restartPolicy` (`Always`, the default,
`OnFailure` or `Never`), and mounts `emptyDir` volumes with `medium: Memory` as
tmpfs (read-only when the mount says `readOnly: true`). With
`FLOCI_GCP_SERVICES_COMPUTE_INSTANCE_RESOURCE_LIMITS` on, it gets the machine type's
vCPUs and memory too. `securityContext.privileged` is honoured only with
`FLOCI_GCP_SERVICES_COMPUTE_ALLOW_PRIVILEGED_CONTAINERS=true`: the container would
be privileged on the Docker host, not only inside the instance, so by default it
runs unprivileged and serial port 1 says so. Its output goes to serial port 1, one
line each behind the container's name, after konlet's own lines:

```text
2026-10-24T15:20:01.123Z vm konlet-startup[1]: Configured container 'web' will be started with name 'floci-gcp-compute-p-us-central1-a-vm_klt'.
2026-10-24T15:20:03.456Z vm web[1]: hello
```

As on Compute Engine, the declaration is read at boot: a change made with
`setMetadata` applies at the next start or reset. Stop and delete remove the
container after the shutdown scripts; reset replaces it. A declaration konlet
would refuse (not exactly one container, an unknown restart policy, a volume
mount without its volume) is reported on serial port 1 and starts nothing; the
instance still runs.

**Limitations.** `hostPath` and `gcePersistentDisk` volumes are not mounted (the
serial port says so), and private registry credentials are not fetched from the
instance's service account; the image is pulled as any other floci-gcp image is.

### Serial port output

`instances.getSerialPortOutput` serves serial port 1: the scripts' output and the
runner's progress (and a [container VM](#container-vms)'s output), one line each, in this form:

```text
2026-10-21T16:20:01.123Z vm google_metadata_script_runner[1]: Starting startup scripts.
2026-10-21T16:20:01.456Z vm google_metadata_script_runner[1]: startup-script: hello
2026-10-21T16:20:01.460Z vm google_metadata_script_runner[1]: startup-script exit status 0
```

`start` and `next` are byte positions across everything written, the most recent
1 MiB is kept, a negative `start` reads the last bytes, and bytes that are not
valid UTF-8 come back as `\xNN`. Ports 2 to 4 are empty, and so is port 1 in mock
mode. The output is kept in memory: it does not survive an emulator restart, and
it is dropped when the instance is deleted. Kernel and boot messages are not
emulated.

### Metadata server

floci-gcp serves the GCE metadata server on its main port under
`/computeMetadata/v1`. Every instance container gets `GCE_METADATA_HOST` (and
`GCE_METADATA_IP`) set to the emulator's address, so the Google client libraries
and `curl -H 'Metadata-Flavor: Google' "http://$GCE_METADATA_HOST/computeMetadata/v1/..."`
reach it. The server answers for the instance whose container sent the request,
identified by its container IP address.

| Path | Value |
|---|---|
| `instance/id`, `name`, `hostname`, `description` | From the instance; the hostname is `NAME.ZONE.c.PROJECT.internal` |
| `instance/zone`, `instance/machine-type` | `projects/NUMBER/zones/ZONE`, `projects/NUMBER/machineTypes/TYPE` |
| `instance/image` | The boot disk's source image, `projects/PROJECT/global/images/NAME` |
| `instance/tags` | JSON array; `?alt=text` gives one tag per line |
| `instance/attributes/KEY` | Instance metadata |
| `instance/network-interfaces/0/...` | `ip`, `network`, `gateway`, `subnetmask`, `mac`, `mtu`, `dns-servers`, `access-configs/0/external-ip` |
| `instance/disks/N/...` | `device-name`, `index`, `interface`, `mode`, `type` |
| `instance/service-accounts/{default,EMAIL}/...` | `email`, `aliases`, `scopes`, `token`, `identity?audience=AUD[&format=full]` |
| `project/project-id`, `project/numeric-project-id` | The project ID and number |
| `project/attributes/KEY` | Project metadata (`projects.setCommonInstanceMetadata`) |
| `universe/universe-domain` | `googleapis.com` |

Requests need `Metadata-Flavor: Google` and must not carry `X-Forwarded-For`; such
requests get 403, and unknown keys get 404. A path ending in `/` lists its entries,
and a directory requested without the slash redirects to it. `?recursive=true`
returns JSON with camelCase keys (attribute keys and service account emails keep
their spelling), and with `alt=text` returns `path value` lines. `?alt=json` returns
a single value as JSON. `?wait_for_change=true` blocks until the value's `ETag`
differs from `last_etag`, or for `timeout_sec`
(`FLOCI_GCP_SERVICES_COMPUTE_METADATA_SERVER_DEFAULT_WAIT_TIMEOUT`, 300 seconds,
when absent). It is rejected on the token and on a non-recursive directory.

Tokens are stub access tokens: floci-gcp accepts any credential. Identity tokens
have Google's shape, with the requested `aud` and, with `format=full`, the
`google.compute_engine` claims, but they are not signed by a Google key, so only
code that skips signature verification accepts them. An instance created without
`serviceAccounts` has none, as on GCE.

**Limitations.** `169.254.169.254` and `metadata.google.internal` are not routed;
use `GCE_METADATA_HOST`. Identifying the caller by address needs the container's
own IP to reach floci-gcp. That holds when floci-gcp runs in Docker on the same
network, or directly on a Linux host. With floci-gcp on the host under Docker
Desktop, requests arrive from a proxy address and get 404.

