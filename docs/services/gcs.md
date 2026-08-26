# Cloud Storage (GCS)

floci-gcp emulates Google Cloud Storage using the real GCP wire protocols:

- **REST XML** — object operations (upload, download, delete, list objects)
- **REST JSON** — bucket management (create bucket, list buckets, get bucket metadata)

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_GCS_ENABLED` | `true` | Enable/disable Cloud Storage |
| `FLOCI_GCP_BASE_URL` | `http://localhost:4588` | Base URL embedded in object URLs and pre-signed URLs |

## Emulator Variable

```bash
export STORAGE_EMULATOR_HOST=http://localhost:4588
```

GCP Storage SDK clients use this variable to route requests to floci-gcp instead of `storage.googleapis.com`.

## Service Account Authentication

Clients can exercise the normal service-account OAuth flow by setting the
credential's `token_uri` to the emulator:

```json
{
  "type": "service_account",
  "project_id": "floci-local",
  "private_key_id": "test-key",
  "private_key": "-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----\n",
  "client_email": "test@floci-local.iam.gserviceaccount.com",
  "client_id": "123456789",
  "token_uri": "http://localhost:4588/token"
}
```

floci-gcp implements the standard OAuth JWT bearer exchange and returns a
short-lived Bearer token. As with other emulator credentials, JWT signatures
and Bearer tokens are not validated.

## Quick Start

=== "gcloud CLI"

    ```bash
    export STORAGE_EMULATOR_HOST=http://localhost:4588

    # Create a bucket
    gcloud storage buckets create gs://my-bucket

    # Upload an object
    echo "hello from floci-gcp" | gcloud storage cp - gs://my-bucket/hello.txt

    # List objects
    gcloud storage ls gs://my-bucket

    # Download
    gcloud storage cp gs://my-bucket/hello.txt -

    # Delete
    gcloud storage rm gs://my-bucket/hello.txt
    ```

=== "Java"

    ```java
    Storage storage = StorageOptions.newBuilder()
        .setHost("http://localhost:4588")
        .setProjectId("floci-local")
        .setCredentials(NoCredentials.getInstance())
        .build()
        .getService();

    // Create bucket
    Bucket bucket = storage.create(BucketInfo.of("my-bucket"));

    // Upload object
    Blob blob = storage.create(
        BlobInfo.newBuilder("my-bucket", "hello.txt").build(),
        "hello from floci-gcp".getBytes());

    // Download object
    byte[] content = storage.readAllBytes("my-bucket", "hello.txt");

    // List objects
    Page<Blob> blobs = storage.list("my-bucket");
    blobs.iterateAll().forEach(b -> System.out.println(b.getName()));

    // Delete object
    storage.delete("my-bucket", "hello.txt");
    ```

=== "Python"

    ```python
    import os
    os.environ["STORAGE_EMULATOR_HOST"] = "http://localhost:4588"

    from google.cloud import storage

    client = storage.Client(project="floci-local")

    # Create bucket
    bucket = client.bucket("my-bucket")
    client.create_bucket(bucket)

    # Upload object
    blob = bucket.blob("hello.txt")
    blob.upload_from_string("hello from floci-gcp")

    # Download object
    content = blob.download_as_text()

    # List objects
    for b in client.list_blobs("my-bucket"):
        print(b.name)

    # Delete
    blob.delete()
    ```

=== "Node.js"

    ```javascript
    import { Storage } from "@google-cloud/storage";

    const storage = new Storage({
      apiEndpoint: "http://localhost:4588",
      projectId: "floci-local",
    });

    // Create bucket
    await storage.createBucket("my-bucket");

    // Upload object
    await storage.bucket("my-bucket").file("hello.txt")
        .save("hello from floci-gcp");

    // Download
    const [content] = await storage.bucket("my-bucket").file("hello.txt").download();

    // List objects
    const [files] = await storage.bucket("my-bucket").getFiles();
    files.forEach(f => console.log(f.name));
    ```

## Multipart Upload

floci-gcp supports multipart (resumable) upload — the standard GCS mechanism for large objects. The GCP SDK uses this automatically for objects above a threshold.

## Resumable Upload Sessions

`POST /upload/storage/v1/b/{bucket}/o?uploadType=resumable` opens a session and returns
the session URL in the `Location` header. Chunks go to that URL with a `Content-Range`
header, using either `PUT` or `POST` — the Java, Node and Python SDKs send `PUT`, the Go
SDK sends `POST`, and both are handled the same way.

Session behavior matches GCS:

| Request to the session URL | Response |
|---|---|
| Chunk that leaves bytes missing | `308` with `Range: bytes=0-<last received byte>` |
| Chunk that completes the object | `200` with the object metadata |
| Status query (`Content-Range: bytes */<total>` or `bytes */*`) | `308` with the received range, or `200` with the object metadata once complete |
| Chunk already received in full | `308` with the unchanged received range, without appending it again |
| Chunk starting past the received bytes | `503`, so the client re-syncs with a status query |
| Any request after completion | `200` with the stored object metadata |
| Unknown or expired `upload_id` | `404` |

The Go SDK sends `X-GUploader-No-308: yes` because `308` collides with the RFC 7238
"Permanent Redirect" semantics. floci-gcp answers those requests the way GCS does: `200`
with `X-HTTP-Status-Code-Override: 308` and the same `Range` header. Other status codes
are unaffected by the header.

Documented deviations from real GCS:

- GCS requires every non-final chunk to be a multiple of 256 KiB; floci-gcp accepts any
  chunk size.
- GCS answers a chunk `POST` that carries no `uploadType` with `405`; floci-gcp accepts
  it, since the `upload_id` alone identifies the session.
- Completed sessions are remembered for the most recent 1024 uploads rather than for a
  week.

## Object Versioning

Enable versioning on a bucket:

```java
storage.update(BucketInfo.newBuilder("my-bucket")
    .setVersioningEnabled(true)
    .build());
```

Each overwrite creates a new object generation. List all versions:

```java
Page<Blob> versions = storage.list("my-bucket",
    Storage.BlobListOption.versions(true));
```

## Pre-signed URLs

Generate a pre-signed URL for temporary public access:

```java
URL signedUrl = storage.signUrl(
    BlobInfo.newBuilder("my-bucket", "hello.txt").build(),
    15, TimeUnit.MINUTES,
    Storage.SignUrlOption.withV4Signature());
```

Pre-signed URLs are generated using the `FLOCI_GCP_BASE_URL` as the base.

## Virtual-Hosted Style URLs

floci-gcp supports virtual-hosted style GCS URLs:

```
http://my-bucket.localhost.floci.io:4588/hello.txt
```

The embedded DNS server resolves `*.localhost.floci.io` to floci-gcp's container IP when running inside Docker, so virtual-hosted URLs work from sidecar containers without extra DNS configuration.

## Supported Operations

**Bucket management (REST JSON):**

- `CreateBucket` (with `location`, `storageClass`, `versioning`, `lifecycle`, `cors`, `retentionPolicy`)
- `GetBucket`
- `ListBuckets` (with `pageToken` pagination)
- `UpdateBucket` / `PatchBucket`
- `DeleteBucket`
- `GetBucketIamPolicy` / `SetBucketIamPolicy` / `TestBucketIamPermissions`

**Bucket ACLs (REST JSON):**

- `ListBucketAcl` / `CreateBucketAcl`
- `GetBucketAcl` / `UpdateBucketAcl` / `DeleteBucketAcl`
- `ListDefaultObjectAcl` / `CreateDefaultObjectAcl`
- `GetDefaultObjectAcl` / `UpdateDefaultObjectAcl` / `DeleteDefaultObjectAcl`

**Object operations (REST XML + REST JSON):**

- `PutObject` (simple and multipart/resumable upload)
- `GetObject`
- `DeleteObject`
- `ListObjects` (with `pageToken`, `prefix`, `delimiter` pagination)
- `CopyObject`
- `MoveObject`
- `HeadObject`
- `PatchObject` (update metadata: `contentType`, `contentDisposition`, `contentEncoding`, `contentLanguage`, custom metadata)
- `ComposeObject` (concatenate up to 32 source objects)
- Pre-signed GET/PUT URLs (V4 signature via IAM `SignBlob`)
- Batch requests (`/batch/storage/v1`)
- Customer-supplied encryption keys (CSEK)

Object names containing `/`, spaces, `+`, or percent-encoded sequences round-trip correctly — the emulator preserves URI-encoded names exactly as real GCS does.

**Pub/Sub notifications (REST JSON):**

- `CreateNotification` / `ListNotifications` / `GetNotification` / `DeleteNotification` (`/storage/v1/b/{bucket}/notificationConfigs`) — object changes publish to the configured Pub/Sub topic in the local backend

**Object ACLs (REST JSON):**

- `ListObjectAcl` / `CreateObjectAcl`

## IAM allow-policy enforcement

Set `FLOCI_GCP_SERVICES_IAM_AUTHORIZATION_MODE=enforce` to evaluate supported
bucket IAM allow policies for bucket and object operations. The default remains
`disabled`, preserving the emulator's no-auth behavior. See the [IAM service](iam.md)
for supported principals, roles, conditions, bootstrap administration, and
intentional exclusions.
- `GetObjectAcl` / `UpdateObjectAcl` / `DeleteObjectAcl`

**Conditional requests (preconditions):**

- `ifGenerationMatch` / `ifGenerationNotMatch`
- `ifMetagenerationMatch` / `ifMetagenerationNotMatch`
- `ifSourceGenerationMatch` / `ifSourceGenerationNotMatch` for object moves
- `ifSourceMetagenerationMatch` / `ifSourceMetagenerationNotMatch` for object moves
- Returns HTTP 412 on precondition failure
- Enforced atomically on object mutation paths under object locks, with a monotonic generation sequence — concurrent writers with `ifGenerationMatch=0` race safely (exactly one wins)
