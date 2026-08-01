# GKE (Kubernetes Engine)

floci-gcp emulates the Google Kubernetes Engine control plane (`container.googleapis.com`,
ClusterManager v1) over REST JSON. Cluster lifecycle is backed by real
[k3s](https://k3s.io/) containers (`rancher/k3s`) started through the host Docker daemon,
or a lightweight mock mode.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_GKE_ENABLED` | `true` | Enable/disable GKE |
| `FLOCI_GCP_SERVICES_GKE_MOCK` | `false` | Mock mode: clusters return `RUNNING` immediately without starting a k3s container |
| `FLOCI_GCP_SERVICES_GKE_DEFAULT_IMAGE` | `rancher/k3s:latest` | Image used for real clusters |
| `FLOCI_GCP_SERVICES_GKE_API_SERVER_BASE_PORT` | `6550` | Start of the host port range for k3s API servers |
| `FLOCI_GCP_SERVICES_GKE_API_SERVER_MAX_PORT` | `6599` | End of the host port range |
| `FLOCI_GCP_SERVICES_GKE_ENDPOINT_MODE` | `host` | How the cluster endpoint is advertised to `kubectl` (`host` for a reachable `host:port`) |
| `FLOCI_GCP_SERVICES_GKE_KEEP_RUNNING_ON_SHUTDOWN` | `false` | Leave spawned k3s containers running after floci-gcp shuts down |
| `FLOCI_GCP_SERVICES_GKE_DOCKER_NETWORK` | _(none)_ | Overrides `FLOCI_GCP_SERVICES_DOCKER_NETWORK` for GKE/k3s sidecars only |

## Routing: how clients reach GKE

On real GCP, `container.googleapis.com` and other APIs share the canonical
`/v1/projects/.../clusters` path and are told apart only by hostname. floci-gcp serves
everything on one port, where that path also belongs to Managed Kafka, so GKE mounts under a
`/container` prefix and a routing filter maps clients onto it two ways:

- **Host mode (SDKs, Terraform/OpenTofu):** point the client endpoint host at `container.*` (e.g.
  `http://container.localhost:4588`). The first DNS label `container` triggers a rewrite of
  `/v1/...` to `/container/v1/...`. `container.localhost` resolves to loopback out of the box on
  most systems (systemd-resolved's synthetic `*.localhost` wildcard on Linux; no `/etc/hosts` edit
  needed on macOS either).
- **Path mode (gcloud / direct):** call the `/container/v1/...` prefix directly, or set a
  custom endpoint base of `<endpoint>/container/v1/`.

!!! warning "Terraform/OpenTofu must use host mode, not path mode"
    The `hashicorp/google` provider's GKE client resolves its base URL through a
    `RemoveBasePathVersion` helper that strips the *last path segment* of any custom endpoint,
    on the assumption it is a stray API version suffix (`.../v1/`). Given a path-prefixed
    endpoint like `<endpoint>/container/`, it incorrectly strips `container` too, so
    `container_custom_endpoint = "<endpoint>/container/"` silently resolves to bare `<endpoint>/`
    and every GKE call 404s or is routed to whatever other service happens to own that raw
    `/v1/projects/.../locations/.../clusters` path (Managed Kafka, in this codebase). Set
    `container_custom_endpoint = "http://container.localhost:4588/"` (host mode) instead. Not yet
    wired into `compatibility-tests/compat-terraform`'s CI suite — that suite's floci-gcp
    container is reached as `floci-gcp:4588` on the shared Docker network, so a `container.*`
    host override needs a network alias (or `/etc/hosts` entry) for `container.floci-gcp` in
    `compatibility.yml`, not just the `*.localhost` wildcard resolution local dev gets for free.
    Verified manually against `./mvnw quarkus:dev` instead; see the PR description for the exact
    commands.

!!! note "SDK transport"
    The Cloud Client libraries default to gRPC, which the REST-only emulator does not serve
    for GKE. Build the client with the HttpJson transport
    (`ClusterManagerSettings.newHttpJsonBuilder()`) and a `container.*` endpoint host.

## Quick Start

=== "REST API"

    ```bash
    # Create a cluster (path mode)
    curl -X POST \
      "http://localhost:4588/container/v1/projects/floci-local/locations/us-central1/clusters" \
      -H "Content-Type: application/json" \
      -d '{"cluster":{"name":"my-cluster"}}'

    # Get / list clusters
    curl "http://localhost:4588/container/v1/projects/floci-local/locations/us-central1/clusters/my-cluster"
    curl "http://localhost:4588/container/v1/projects/floci-local/locations/us-central1/clusters"

    # Delete a cluster
    curl -X DELETE \
      "http://localhost:4588/container/v1/projects/floci-local/locations/us-central1/clusters/my-cluster"
    ```

=== "gcloud"

    ```bash
    export CLOUDSDK_API_ENDPOINT_OVERRIDES_CONTAINER="http://localhost:4588/container/"
    # gcloud preflights an API-enablement check; point serviceusage at the emulator and
    # disable the enable-API prompt (floci-gcp ignores auth).
    export CLOUDSDK_API_ENDPOINT_OVERRIDES_SERVICEUSAGE="http://localhost:4588/"
    export CLOUDSDK_CORE_SHOULD_PROMPT_TO_ENABLE_API=false

    gcloud container clusters create my-cluster --region=us-central1 --async
    gcloud container clusters list --region=us-central1
    ```

## kubectl Access

Real (non-mock) clusters run an actual k3s API server, so the native
`gcloud container clusters get-credentials` + `kubectl` flow works end to end:

```bash
gcloud container clusters get-credentials my-cluster --region=us-central1
kubectl get nodes
```

`get-credentials` writes a kubeconfig that authenticates with the GCP access token produced by
the `gke-gcloud-auth-plugin`. k3s forwards any bearer token it does not recognise to floci-gcp's
token-authentication webhook, which — since floci-gcp does not validate credentials — accepts any
non-empty token and maps it to `cluster-admin`. The cluster endpoint is advertised as a reachable
`host:port` (`FLOCI_GCP_SERVICES_GKE_ENDPOINT_MODE=host`, the default) so the kubeconfig server URL
resolves correctly.

This flow requires a real cluster — it does not apply in mock mode, where no API server is started.

## Mock Mode

Set `FLOCI_GCP_SERVICES_GKE_MOCK=true` to create clusters in memory that report `RUNNING`
immediately without starting a k3s container. Useful for CI and for tools that provision a
cluster but never connect to its API server.

## Node Pools

Node pools are modeled as real, independent resources (not a static field on the cluster):
`CreateCluster` provisions a `default-pool` node pool from the request's top-level
`initialNodeCount`/`nodeConfig` (or from an explicit `nodePools[]` list), and it can be deleted
and replaced with separately-managed pools — the standard
`remove_default_node_pool = true` + standalone `google_container_node_pool` pattern used by
Terraform, OpenTofu, and Pulumi all work end to end.

`config`, `autoscaling`, `management`, `upgradeSettings` and `placementPolicy` are stored
verbatim from the create/update request and echoed back unchanged on every read — floci-gcp does
not run real node VMs or enforce autoscaling/repair/upgrade behavior, so there is nothing to act
on semantically. This keeps every field a real client sends round-tripping consistently for
Terraform's plan/refresh diff, without hand-modeling the full `NodeConfig` proto surface.

!!! warning "`node_count` drifts on every `terraform plan` unless you set `ignore_node_count_changes`"
    Real GKE's Terraform provider computes the live `node_count` value by querying **Compute
    Engine Instance Group Manager** target sizes for the node pool's `instanceGroupUrls` — not
    from any field the NodePool API itself returns. floci-gcp does not emulate Compute Engine, so
    `instanceGroupUrls` is always empty and the provider reads `node_count` back as `0`, which it
    then wants to "correct" on every plan. Set `ignore_node_count_changes = true` on
    `google_container_node_pool` resources to skip that IGM-based read entirely and use
    `initial_node_count` as the source of truth instead (the provider's own documented escape
    hatch for exactly this situation).

## Supported Operations

- `CreateCluster`, `GetCluster`, `ListClusters`, `DeleteCluster`, `UpdateCluster`
- `SetResourceLabels`, `SetMasterAuth`, `SetNetworkPolicy`, `SetAddonsConfig`,
  `SetLoggingService`, `SetMonitoringService`, `SetLocations`, `SetLegacyAbac`,
  `SetMaintenancePolicy`, `StartIPRotation`, `CompleteIPRotation`
- `CreateNodePool`, `GetNodePool`, `ListNodePools`, `DeleteNodePool`, `UpdateNodePool`
- `SetNodePoolAutoscaling`, `SetNodePoolManagement`, `SetNodePoolSize`,
  `CompleteNodePoolUpgrade`, `RollbackNodePoolUpgrade`
- `GetServerConfig`, `GetJSONWebKeys`, `ListUsableSubnetworks`,
  `CheckAutopilotCompatibility`, `FetchClusterUpgradeInfo`, `FetchNodePoolUpgradeInfo`
- `GetOperation` / `ListOperations`

This is the full `container.v1` `ClusterManager` RPC surface except
`CancelOperation` (operations are always synchronous/`DONE`, so there is
nothing in flight to cancel).

The five read-only RPCs above report honest stub data rather than fabricated
analysis, since floci-gcp has no real infrastructure behind them to inspect:

- `GetJSONWebKeys` returns an empty key set — floci-gcp accepts any bearer
  token (no real credential validation), so there is no real signing key to
  expose, and a fabricated one would misleadingly imply verifiable tokens are
  possible.
- `ListUsableSubnetworks` returns the single synthetic "default"
  network/subnetwork every cluster in this emulator already defaults to —
  floci-gcp does not emulate Compute Engine/VPC, so there is no real
  subnetwork inventory.
- `CheckAutopilotCompatibility` always reports no issues — there is no real
  policy engine to evaluate a cluster's configuration against.
- `FetchClusterUpgradeInfo`/`FetchNodePoolUpgradeInfo` report the current
  version as both current and target with nothing pending — floci-gcp has a
  single fixed master/node version, so there is never a real upgrade path.

**Autopilot mode** (`autopilot.enabled`) and **Fleet/Anthos registration**
(`fleet`) are not semantically modeled — floci-gcp does not run a real
Autopilot node-management control loop or register with a real Fleet — but
both round-trip correctly through `extraConfig` like every other config
block the emulator doesn't act on, so tooling that merely checks
`cluster.autopilot.enabled` or `cluster.fleet.project` works correctly.

All mutations return a synchronous, `DONE` Operation (no real long-running operation lifecycle) —
consistent across every RPC above, not just cluster create/delete as before.

`StartIPRotation`/`CompleteIPRotation` acknowledge the request (bumping the cluster's
fingerprint/etag) rather than performing a real dual-certificate rotation window — there is no
live client traffic to migrate off an old certificate in this emulator.
`CompleteNodePoolUpgrade`/`RollbackNodePoolUpgrade` validate the node pool exists and return the
operation; there is no real node-version upgrade in flight to complete or roll back, since node
pools don't run real node VMs. `GetServerConfig` reports this emulator's single supported
master/node version across all three release channels (`RAPID`/`REGULAR`/`STABLE`) — there is no
real multi-version fleet behind it.

## Limitations

- Operations resolve synchronously (no real long-running operation lifecycle, no `CancelOperation`).
- Node IAM is not modeled.
- Real Autopilot node management and Compute Engine-backed node scaling (`node_count` read-back —
  see the warning above) are out of scope; floci-gcp does not run real node VMs, so Autopilot mode
  and Fleet registration round-trip as config but are not semantically enforced.
- Cluster-level config blocks the emulator doesn't act on semantically (network policy
  enforcement, binary authorization, private cluster networking, etc.) are stored and echoed back
  verbatim rather than enforced — see the `config`/`autoscaling`/`management` note under Node
  Pools above, which applies at the cluster level too (`extraConfig`).
