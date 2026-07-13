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
- `HeadObject`
- `PatchObject` (update metadata: `contentType`, `contentDisposition`, `contentEncoding`, `contentLanguage`, custom metadata)
- `ComposeObject` (concatenate up to 32 source objects)
- Pre-signed GET/PUT URLs (V4 signature via IAM `SignBlob`)

**Object ACLs (REST JSON):**

- `ListObjectAcl` / `CreateObjectAcl`
- `GetObjectAcl` / `UpdateObjectAcl` / `DeleteObjectAcl`

**Conditional requests (preconditions):**

- `ifGenerationMatch` / `ifGenerationNotMatch`
- `ifMetagenerationMatch` / `ifMetagenerationNotMatch`
- Returns HTTP 412 on precondition failure

## CTF fork

When IAM enforcement is enabled (`floci-gcp.services.iam.enforcement-enabled`):

- REST JSON and upload/download GCS calls require a registered Bearer token and a matching project allow-policy binding.
- `IamPermissionMapper` maps GCS paths to `storage.buckets.*` and `storage.objects.*` permissions (create, get, list, getIamPolicy, setIamPolicy for buckets; create, get, list, update, delete for objects).
- Object `PATCH` maps to `storage.objects.update` (create-only roles cannot mutate metadata via PATCH).
- `roles/storage.objectViewer` grants `storage.objects.get` and `storage.objects.list`.
- `roles/storage.objectAdmin` grants object create, get, list, update, and delete.
- `roles/storage.admin` grants the broad bucket and object permissions used by the CTF mapper (including update).
- Operator root (`FLOCI_GCP_AUTH_ROOT_SERVICE_ACCOUNT` / `FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN`) bypasses IAM evaluation.
- Signed URL note: V4 pre-signed URL generation still uses IAM `SignBlob` (stub signature for local use). Serving a signed URL still goes through the mapped GCS object paths when enforcement is on, so the caller or the signed request path must satisfy the same storage permissions unless operator root is used.

Regression: `GcsIamEnforcementIntegrationTest`.
