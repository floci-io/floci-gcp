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
| Update function | `PATCH /v2/projects/{project}/locations/{location}/functions/{function}` |
| Delete function | `DELETE /v2/projects/{project}/locations/{location}/functions/{function}` |
| Generate upload URL | `POST /v2/projects/{project}/locations/{location}/functions:generateUploadUrl` |
| Generate download URL | `POST /v2/projects/{project}/locations/{location}/functions/{function}:generateDownloadUrl` |
| Get IAM policy | `GET /v2/projects/{project}/locations/{location}/functions/{function}:getIamPolicy` |
| Set IAM policy | `POST /v2/projects/{project}/locations/{location}/functions/{function}:setIamPolicy` |
| Test IAM permissions | `POST /v2/projects/{project}/locations/{location}/functions/{function}:testIamPermissions` |

List functions accepts `-` as the location (`projects/{project}/locations/-/functions`) to list functions across all locations.

Create, update and delete return completed `google.longrunning.Operation` resources immediately. Operations can be read, listed, waited on, and deleted under `/v2/projects/{project}/locations/{location}/operations`.

## Behavior

Cloud Functions resources are metadata only. Creating a function synthesizes `ACTIVE` state, default `GEN_2` environment when omitted, URL, timestamps, and Cloud Run service references in `serviceConfig`.

`generateUploadUrl` returns an upload URL backed by the existing GCS XML `PUT /{bucket}/{object}` object path and includes a `storageSource` in the response. Uploaded source archives are stored as inert GCS metadata; no function build or runtime is executed. Cloud Storage must be enabled for source upload URL generation.

`generateDownloadUrl` returns a V4-style signed GCS URL (`X-Goog-Expires=1800`) for the function's `buildConfig.source.storageSource` object, pinned to its `generation` when set. The signature is a placeholder because the emulator does not verify it. With IAM authorization in `enforce` mode, GCS treats signed URLs as anonymous, so the source object must be readable by `allUsers` for the download to succeed. A function without a Cloud Storage source returns `FAILED_PRECONDITION`; Cloud Storage must be enabled.

Update takes an optional `updateMask`. With a mask, only the listed paths change, and a listed field missing from the body is cleared. Nested paths such as `buildConfig.runtime` leave sibling fields alone. Without a mask, every settable field is replaced by the body. Fields the API marks output-only keep their stored values. That covers `state`, `stateMessages`, `url`, `createTime`, `satisfiesPzs`, `buildConfig.build`, `buildConfig.sourceProvenance`, `buildConfig.onDeployUpdatePolicy.runtimeVersion`, `serviceConfig.service`, `serviceConfig.uri`, `serviceConfig.revision` and `eventTrigger.trigger`, and create ignores them in the request body too. `updateTime` is set to now, and the environment cannot be changed. An update that changes a settable field of `buildConfig` or `serviceConfig` moves `serviceConfig.revision` to the next number, as a redeploy does; a mask that names only output-only paths leaves it alone. An unknown mask path returns `INVALID_ARGUMENT`.

IAM policies use the shared IAM policy store, with the same etag checks as other resources. A function's policy is deleted with the function, so a function recreated under the same name starts with an empty policy. `getIamPolicy` and `setIamPolicy` on a missing function return `NOT_FOUND`, and `testIamPermissions` returns no permissions. Function policies are stored but not enforced.

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
- Runtime listing
- The Cloud Functions v1 API
