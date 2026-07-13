# Service Usage

floci-gcp emulates the Service Usage API (`serviceusage.googleapis.com` v1) over REST JSON —
the API that enables and lists a project's GCP services. It is the first thing most IaC
tooling touches: Terraform's `google_project_service` and Pulumi's `gcp.projects.Service`
call it before managing any other resource, and `gcloud services` is built on it.

The emulator is an accept-and-succeed control plane: enabling a service flips it to
`ENABLED` (persisted, project-namespaced), disabling reverses it, and get/list echo that
state. There is no real API gating or dependency resolution — services work whether or not
they were "enabled".

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_SERVICEUSAGE_ENABLED` | `true` | Enable/disable Service Usage |
| `FLOCI_GCP_SERVICES_RESOURCEMANAGER_ENABLED` | `true` | Enable/disable the Cloud Resource Manager project lookup (see below) |

## Endpoints

| Method | Path |
|---|---|
| `POST` | `/v1/projects/{project}/services/{service}:enable` |
| `POST` | `/v1/projects/{project}/services/{service}:disable` |
| `GET` | `/v1/projects/{project}/services/{service}` |
| `GET` | `/v1/projects/{project}/services` (`?filter=state:ENABLED\|state:DISABLED`) |
| `POST` | `/v1/projects/{project}/services:batchEnable` |
| `GET` | `/v1/projects/{project}/services:batchGet?names=...` |
| `GET` | `/v1/operations/{operation}`, `/v1/operations` |

`enable`, `disable`, and `batchEnable` return an already-completed
`google.longrunning.Operation` (`done: true`) whose `response` carries the proto response
type, so SDK operation futures resolve immediately. Disabling a service that is not
enabled returns `FAILED_PRECONDITION`, matching real GCP.

## Cloud Resource Manager companion

The Terraform/Pulumi Google providers verify a project exists via
`cloudresourcemanager.v1.Projects.GetProject` before reading `google_project_service`.
floci-gcp therefore serves a minimal Cloud Resource Manager v1 surface:

| Method | Path |
|---|---|
| `GET` | `/v1/projects/{projectId}` |

Every project ID resolves to an `ACTIVE` project with a stable synthetic
`projectNumber` (the emulator's multi-tenancy is keyed by project ID; projects are never
created or deleted).

### CTF fork (Resource Manager)

When IAM enforcement is enabled:

- `GET /v1/projects/{projectId}` maps to `resourcemanager.projects.get`.
- Custom methods `getIamPolicy` / `setIamPolicy` on the project path map to
  `resourcemanager.projects.getIamPolicy` / `resourcemanager.projects.setIamPolicy`
  (mapped for CTF; full CRM IAM policy RPCs are not implemented on this controller).
- `roles/browser` grants `resourcemanager.projects.get`.
- `roles/resourcemanager.projectIamAdmin` grants getIamPolicy and setIamPolicy.
- Operator root bypasses IAM evaluation.

Regression: `ResourceManagerIamEnforcementIntegrationTest`.

## Quick Start

=== "Terraform"

    ```hcl
    provider "google" {
      project = "my-project"

      service_usage_custom_endpoint    = "http://localhost:4588/v1/"
      resource_manager_custom_endpoint = "http://localhost:4588/v1/"
    }

    resource "google_project_service" "run" {
      service            = "run.googleapis.com"
      disable_on_destroy = true
    }
    ```

    Export a fake token first: `export GOOGLE_OAUTH_ACCESS_TOKEN=fake-token`.

=== "gcloud"

    ```bash
    export CLOUDSDK_AUTH_DISABLE_CREDENTIALS=true
    export CLOUDSDK_API_ENDPOINT_OVERRIDES_SERVICEUSAGE=http://localhost:4588/

    gcloud services enable run.googleapis.com pubsub.googleapis.com --project=my-project
    gcloud services list --enabled --project=my-project
    gcloud services disable run.googleapis.com --project=my-project --force
    ```

=== "Java"

    ```java
    ServiceUsageClient client = ServiceUsageClient.create(
        ServiceUsageSettings.newHttpJsonBuilder()
            .setEndpoint("http://localhost:4588")
            .setCredentialsProvider(NoCredentialsProvider.create())
            .build());

    client.enableServiceAsync(EnableServiceRequest.newBuilder()
            .setName("projects/my-project/services/run.googleapis.com")
            .build())
        .get();

    Service service = client.getService(GetServiceRequest.newBuilder()
            .setName("projects/my-project/services/run.googleapis.com")
            .build());
    // service.getState() == State.ENABLED
    ```

## Scope and deviations

- `ListServices` returns only services whose state has been tracked (enabled or later
  disabled) for the project. Real GCP also lists every public API in the `DISABLED` state;
  the emulator does not ship a catalog of Google APIs.
- `Service.config` carries only the service `name`; real GCP includes title, quota, auth,
  and endpoint configuration.
- `disableDependentServices` and `checkIfServiceHasUsage` are accepted and ignored — there
  is no dependency graph or usage tracking.
- Batch limits match real GCP: 20 services per `batchEnable`, 30 names per `batchGet`,
  page size capped at 200.

## CTF fork

When IAM enforcement is enabled (`floci-gcp.services.iam.enforcement-enabled`):

- Service Usage REST calls require a registered Bearer token and a matching project
  allow-policy binding.
- `IamPermissionMapper` maps paths to `serviceusage.services.enable`, `disable`, `get`,
  and `list` (`batchEnable` → enable, `batchGet` → get).
- `roles/serviceusage.serviceUsageConsumer` grants get and list only.
- `roles/serviceusage.serviceUsageAdmin` grants enable, disable, get, and list.
- Operator root (`FLOCI_GCP_AUTH_ROOT_SERVICE_ACCOUNT` / `FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN`)
  bypasses IAM evaluation.

Regression: `ServiceUsageIamEnforcementIntegrationTest`.
