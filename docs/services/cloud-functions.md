# Cloud Functions

floci-gcp emulates the Cloud Functions v2 control plane over REST JSON using Google's published protobuf types.

| Config | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_CLOUDFUNCTIONS_ENABLED` | `true` | Enable/disable Cloud Functions |

## Supported API Surface

| Operation | Path |
|---|---|
| Create function | `POST /v2/projects/{project}/locations/{location}/functions` |
| List functions | `GET /v2/projects/{project}/locations/{location}/functions` |
| Get function | `GET /v2/projects/{project}/locations/{location}/functions/{function}` |
| Delete function | `DELETE /v2/projects/{project}/locations/{location}/functions/{function}` |
| Generate upload URL | `POST /v2/projects/{project}/locations/{location}/functions:generateUploadUrl` |

Create and delete return completed `google.longrunning.Operation` resources immediately. Operations can be read, listed, waited on, and deleted under `/v2/projects/{project}/locations/{location}/operations`.

## Behavior

Cloud Functions resources are metadata only. Creating a function synthesizes `ACTIVE` state, default `GEN_2` environment when omitted, URL, timestamps, and Cloud Run service references in `serviceConfig`.

`generateUploadUrl` returns an upload URL backed by the existing GCS XML `PUT /{bucket}/{object}` object path and includes a `storageSource` in the response. Uploaded source archives are stored as inert GCS metadata; no function build or runtime is executed. Cloud Storage must be enabled for source upload URL generation.

`validateOnly=true` returns a successful completed operation without storing or deleting resources. Validate-only operations are not retained for later operation get/list calls.

## SDK Usage

Cloud Functions clients should use the HTTP JSON transport, an explicit endpoint, and no credentials:

```java
FunctionServiceSettings settings = FunctionServiceSettings.newHttpJsonBuilder()
    .setEndpoint("http://localhost:4588")
    .setCredentialsProvider(NoCredentialsProvider.create())
    .build();
```

## Not Implemented

- Runtime invocation
- Function updates
- Download URL generation
- Runtime listing

## CTF fork

When IAM enforcement is enabled (`floci-gcp.services.iam.enforcement-enabled`):

- REST Cloud Functions calls require a registered Bearer token and a matching project allow-policy binding.
- `IamPermissionMapper` maps Cloud Functions v2 REST paths to `cloudfunctions.functions.*` permissions:
  - create, get, list, delete
  - `generateUploadUrl` maps to `cloudfunctions.functions.sourceCodeSet`
- `roles/cloudfunctions.developer` and `roles/cloudfunctions.admin` both grant the Stage 0 mapped control-plane permissions above.
- Operator root (`FLOCI_GCP_AUTH_ROOT_SERVICE_ACCOUNT` / `FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN`) bypasses IAM evaluation.

Regression: `CloudFunctionsIamEnforcementIntegrationTest`.
