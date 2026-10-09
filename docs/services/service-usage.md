# Service Usage

floci-gcp emulates the Service Usage API (`serviceusage.googleapis.com` v1) over REST JSON.
The API enables and lists a project's GCP services. It is the first thing most IaC
tooling touches: Terraform's `google_project_service` and Pulumi's `gcp.projects.Service`
call it before managing any other resource, and `gcloud services` is built on it.

The emulator is an accept-and-succeed control plane: enabling a service flips it to
`ENABLED` (persisted, project-namespaced), disabling reverses it, and get/list echo that
state. By default there is no API gating or dependency resolution: services work whether or
not they were "enabled". Turn on [enforcement](#enforcing-api-enablement) to make the
enable/disable state matter.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_SERVICEUSAGE_ENABLED` | `true` | Enable/disable Service Usage |
| `FLOCI_GCP_SERVICES_SERVICEUSAGE_ENFORCE` | `false` | Reject calls to APIs that are not enabled in the caller's project |
| `FLOCI_GCP_SERVICES_SERVICEUSAGE_DEFAULT_ENABLED` | GCP default set (below) | APIs treated as enabled in every project until disabled (enforcement only) |
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

## Enforcing API enablement

With `floci-gcp.services.serviceusage.enforce: true` (`FLOCI_GCP_SERVICES_SERVICEUSAGE_ENFORCE=true`)
every call to an emulated API checks the Service Usage state of that API in the caller's
project and fails unless it is `ENABLED`, the way real GCP does. Enforcement is off by
default; with it off nothing changes.

A rejected call gets the `SERVICE_DISABLED` error described in
`google/api/error_reason.proto`:

```json
{
  "error": {
    "code": 403,
    "message": "Cloud Pub/Sub API has not been used in project my-project before or it is disabled. Enable it by visiting https://console.developers.google.com/apis/api/pubsub.googleapis.com/overview?project=my-project then retry. If you enabled this API recently, wait a few minutes for the action to propagate to our systems and retry.",
    "status": "PERMISSION_DENIED",
    "errors": [{"message": "...", "domain": "usageLimits", "reason": "accessNotConfigured"}],
    "details": [{
      "@type": "type.googleapis.com/google.rpc.ErrorInfo",
      "reason": "SERVICE_DISABLED",
      "domain": "googleapis.com",
      "metadata": {"service": "pubsub.googleapis.com", "consumer": "projects/my-project"}
    }]
  }
}
```

gRPC calls fail with `PERMISSION_DENIED`, the same message, and the same `ErrorInfo` in
the status details (`grpc-status-details-bin`), so `ApiException.getReason()` returns
`SERVICE_DISABLED` in the Java client.

The project is taken from the request: the `projects/{project}` path segment or the
`project` query parameter for REST, and the `x-goog-request-params` routing header or the
first request message (`projects/...` resource name, `project_id`) for gRPC. Requests
that name no project (for example GCS object paths `/storage/v1/b/{bucket}/o`) are checked
against `floci-gcp.default-project-id`. The `consumer` metadata and the message carry the
project ID, since the emulator has no real project numbers.

### Default-enabled services

A real new project starts with a set of APIs already enabled. With enforcement on,
the APIs in `floci-gcp.services.serviceusage.default-enabled` count as `ENABLED` in every
project until they are disabled, and `get`/`list` report them that way. The default list
is GCP's documented default set: `analyticshub`, `apptopology`, `bigquery`,
`bigqueryconnection`, `bigquerydatapolicy`, `bigquerydatatransfer`, `bigquerymigration`,
`bigqueryreservation`, `bigquerystorage`, `cloudapis`, `cloudtrace`, `dataform`,
`dataplex`, `datastore`, `logging`, `monitoring`, `servicemanagement`, `serviceusage`,
`sql-component`, `storage-api`, `storage-component`, `storage`, `telemetry`
(all `.googleapis.com`). So Cloud Storage, BigQuery, Datastore, Logging and Monitoring
work out of the box, and everything else must be enabled first:

```bash
gcloud services enable pubsub.googleapis.com secretmanager.googleapis.com --project=my-project
```

### Which APIs are gated

| Emulated service | API checked |
|---|---|
| Pub/Sub (gRPC + REST) | `pubsub.googleapis.com` |
| Secret Manager (gRPC + REST) | `secretmanager.googleapis.com` |
| Cloud Storage (JSON, XML, gRPC) | `storage.googleapis.com` |
| Firestore (gRPC) | `firestore.googleapis.com` |
| Datastore (gRPC + REST) | `datastore.googleapis.com` |
| Cloud Tasks (gRPC) | `cloudtasks.googleapis.com` |
| Cloud Scheduler (gRPC + REST) | `cloudscheduler.googleapis.com` |
| Cloud KMS (gRPC + REST) | `cloudkms.googleapis.com` |
| Cloud Logging (gRPC + REST) | `logging.googleapis.com` |
| Cloud Monitoring (gRPC + REST) | `monitoring.googleapis.com` |
| IAM (REST) | `iam.googleapis.com` |
| Cloud Functions | `cloudfunctions.googleapis.com` |
| Cloud Run (admin API and invocation proxy) | `run.googleapis.com` |
| BigQuery | `bigquery.googleapis.com` |
| Firebase Auth (Identity Toolkit, Secure Token) | `identitytoolkit.googleapis.com` |
| GKE | `container.googleapis.com` |
| Compute Engine | `compute.googleapis.com` |
| Managed Kafka | `managedkafka.googleapis.com` |
| Eventarc | `eventarc.googleapis.com` |
| Cloud SQL | `sqladmin.googleapis.com` |

Never gated: Service Usage itself, Cloud Resource Manager, IAM Credentials, STS, the
OAuth token endpoint, the shared `google.iam.v1.IAMPolicy` gRPC mixin (its caller's API
depends on the resource, so it is left open), the shared long-running operations
endpoints, CORS preflight (`OPTIONS`) requests, and emulator-only endpoints
(`/_floci-gcp/...`, `/emulator/...`).

## Cloud Resource Manager companion

The Terraform/Pulumi Google providers verify a project exists via
`cloudresourcemanager.v1.Projects.GetProject` before reading `google_project_service`.
floci-gcp therefore serves a minimal Cloud Resource Manager v1 surface:

| Method | Path |
|---|---|
| `GET` | `/v1/projects/{projectId}` |
| `POST` | `/v1/projects/{projectId}:getIamPolicy` |
| `POST` | `/v1/projects/{projectId}:setIamPolicy` |
| `POST` | `/v1/projects/{projectId}:testIamPermissions` |

Every project ID resolves to an `ACTIVE` project with a stable synthetic
`projectNumber` (the emulator's multi-tenancy is keyed by project ID; projects are never
created or deleted).

Project-level IAM policies are stored with stable empty-policy and rotating write
etags for Terraform/OpenTofu read-modify-write flows. IAM bindings are not
enforced by the emulator; they do not restrict access to emulated resources.

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
  disabled) for the project, plus the default-enabled set when enforcement is on. Real GCP also lists every public API in the `DISABLED` state;
  the emulator does not ship a catalog of Google APIs.
- `Service.config` carries only the service `name`; real GCP includes title, quota, auth,
  and endpoint configuration.
- `disableDependentServices` and `checkIfServiceHasUsage` are accepted and ignored: there
  is no dependency graph or usage tracking.
- Batch limits match real GCP: 20 services per `batchEnable`, 30 names per `batchGet`,
  page size capped at 200.
