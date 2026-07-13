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

## kubectl Access

Real (non-mock) clusters run an actual k3s API server, so the native
`gcloud container clusters get-credentials` + `kubectl` flow works end to end:

```bash
gcloud container clusters get-credentials my-cluster --region=us-central1
kubectl get nodes
```

`get-credentials` writes a kubeconfig that authenticates with the GCP access token produced by
the `gke-gcloud-auth-plugin`. k3s forwards any bearer token it does not recognise to floci-gcp's
token-authentication webhook. With CTF auth gates off, any non-empty token maps to
`cluster-admin`. With gates on, see [CTF fork](#ctf-fork) below. The cluster endpoint is
advertised as a reachable `host:port` (`FLOCI_GCP_SERVICES_GKE_ENDPOINT_MODE=host`, the default) so
the kubeconfig server URL resolves correctly.

This flow requires a real cluster. It does not apply in mock mode, where no API server is started.

## CTF fork

When `FLOCI_GCP_AUTH_VALIDATE_TOKENS=true` or IAM enforcement is enabled
(`FLOCI_GCP_SERVICES_IAM_ENFORCEMENT_ENABLED=true`):

- The GKE token-authentication webhook (`/_floci-gcp/gke/token-webhook`) rejects blank and
  unregistered tokens (`TokenReview.status.authenticated=false`). Failures do not distinguish
  timing or error detail beyond that boolean.
- A token must match the operator root access token (`OperatorRootAuth`) or be registered in
  `TokenRegistry`. The authenticated username is the service-account email.
- **Least privilege groups:** operator root receives `system:masters` (cluster-admin). Registered
  player tokens receive `system:authenticated` only (no `system:masters`). Bind RBAC in the
  cluster if players need kubectl access beyond the default authenticated subject.
- The webhook path stays reachable even when `FLOCI_GCP_CTF_HIDE_INTERNAL_ENDPOINTS` hides other
  `/_floci-gcp/*` routes, and it is not gated by the HTTP Bearer `TokenValidationFilter` (k3s
  sends the credential in `TokenReview.spec.token`).

### Residual token-oracle risk

The webhook must remain callable from k3s for `get-credentials` / kubectl. Callers who can reach
the emulator HTTP port can POST `TokenReview` bodies and learn whether a guessed bearer value is
registered (`authenticated=true`) or not. Network-isolate the emulator from participants when
possible. A shared-secret header gate (`floci-gcp.ctf.gke-webhook-shared-secret` /
`X-Floci-Gke-Webhook-Token`) was considered, but wiring that into the auto-generated k3s webhook
kubeconfig is non-trivial, so it is not implemented. Treat the open webhook as residual CTF risk.

Control-plane REST under `/container/v1` (and host-routed `container.*` equivalents) is **not**
mapped in `IamPermissionMapper`. With
`FLOCI_GCP_SERVICES_IAM_STRICT_ENFORCEMENT_ENABLED=true`, unmapped actions are denied for
non-operator principals. Provision cluster lifecycle APIs with the operator root pair, or turn
strict mode off for local GKE control-plane drills.

When both auth gates are off, behavior matches upstream: any non-empty token authenticates as
`floci:gcp-iam` with `system:masters`.

Regression: `GkeTokenWebhookCtfIntegrationTest`.

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
