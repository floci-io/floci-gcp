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

## Routing: how clients reach GKE

On real GCP, `container.googleapis.com` and other APIs share the canonical
`/v1/projects/.../clusters` path and are told apart only by hostname. floci-gcp serves
everything on one port, where that path also belongs to Managed Kafka, so GKE mounts under a
`/container` prefix and a routing filter maps clients onto it two ways:

- **Host mode (SDKs):** point the client endpoint host at `container.*` (e.g.
  `http://container.localhost:4588`). The first DNS label `container` triggers a rewrite of
  `/v1/...` to `/container/v1/...`.
- **Path mode (gcloud / direct):** call the `/container/v1/...` prefix directly, or set a
  custom endpoint base of `<endpoint>/container/v1/`.

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

## Mock Mode

Set `FLOCI_GCP_SERVICES_GKE_MOCK=true` to create clusters in memory that report `RUNNING`
immediately without starting a k3s container. Useful for CI and for tools that provision a
cluster but never connect to its API server.

## Supported Operations

- `CreateCluster` (returns a synchronous, `DONE` Operation)
- `GetCluster`
- `ListClusters`
- `DeleteCluster` (returns a `DONE` Operation)
- `GetOperation` / `ListOperations`

## Limitations

- Node pools, autoscaling, upgrades, and cluster IAM are not modeled.
- Operations resolve synchronously (no real long-running operation lifecycle).
- The Terraform/OpenTofu `google_container_cluster` resource is **not** supported: the
  google provider expects a far richer cluster surface (node-pool reconciliation,
  default-pool deletion, many computed fields) than the emulator implements. Use the Java
  SDK (HttpJson) or gcloud instead.
