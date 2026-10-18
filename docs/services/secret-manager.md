# Secret Manager

floci-gcp emulates Google Cloud Secret Manager over gRPC and REST using the real `google.cloud.secretmanager.v1` protocol.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_SECRETMANAGER_ENABLED` | `true` | Enable/disable Secret Manager |

## Emulator Variable

```bash
export SECRET_MANAGER_EMULATOR_HOST=localhost:4588
```

The GCP Secret Manager SDK uses this variable to route requests to floci-gcp instead of `secretmanager.googleapis.com`.

## IAM enforcement

With `FLOCI_GCP_SERVICES_IAM_AUTHORIZATION_MODE=enforce`, Floci-issued
service-account credentials are checked on the implemented Secret Manager v1
REST JSON and gRPC methods. Anonymous and external credentials bypass these
checks. The default `disabled` mode does not enforce policies.

| Methods | Permission target |
|---|---|
| Create and list secrets | Project |
| Get, update, delete, and secret IAM policy methods | Secret |
| Add and list versions | Secret |
| Get, access, enable, disable, and destroy versions | Version, inheriting secret and project policies |

Payload access requires `secretmanager.versions.access` separately from version
metadata access. `testIamPermissions` reports only permissions granted to the
caller. Denials return HTTP 403 or gRPC `PERMISSION_DENIED`.

| Role | Implemented permission scope |
|---|---|
| `roles/secretmanager.admin` | All implemented secret, version, payload, and policy methods |
| `roles/secretmanager.editor` | Secret and version management, metadata, and policy reads; excludes payload access and policy writes |
| `roles/secretmanager.viewer` | Secret and version metadata and policy reads |
| `roles/secretmanager.secretAccessor` | Version payload access only |
| `roles/secretmanager.secretVersionAdder` | Add a version to an existing secret |
| `roles/secretmanager.secretVersionManager` | Add, get, list, enable, disable, and destroy versions; excludes payload access |

Project basic roles contribute their Secret Manager permissions.

Version 3 bindings can condition grants on `resource.service`, `resource.type`,
`resource.name`, and `request.time` using the [supported condition expressions](iam.md#supported-roles-and-conditions).
Create checks the parent project, so its `resource.name` is
`projects/{project}`. Version checks use the full version name.

Regional `projects/*/locations/*` paths, v1beta1, and rotation methods are
not implemented. Organization and folder policies are not evaluated.

## Quick Start

=== "gcloud CLI"

    ```bash
    export SECRET_MANAGER_EMULATOR_HOST=localhost:4588
    gcloud config set project floci-local

    # Create a secret
    gcloud secrets create my-secret --replication-policy=automatic

    # Add a version
    echo -n "my-secret-value" | gcloud secrets versions add my-secret --data-file=-

    # Access the latest version
    gcloud secrets versions access latest --secret=my-secret

    # List secrets
    gcloud secrets list
    ```

=== "Java"

    ```java
    ManagedChannel channel = ManagedChannelBuilder
        .forTarget("localhost:4588")
        .usePlaintext()
        .build();

    SecretManagerServiceClient client = SecretManagerServiceClient.create(
        SecretManagerServiceSettings.newBuilder()
            .setTransportChannelProvider(
                FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel)))
            .setCredentialsProvider(NoCredentialsProvider.create())
            .build());

    String projectId = "floci-local";
    String secretId = "my-secret";

    // Create secret
    Secret secret = Secret.newBuilder()
        .setReplication(Replication.newBuilder()
            .setAutomatic(Replication.Automatic.getDefaultInstance()))
        .build();

    Secret createdSecret = client.createSecret(
        ProjectName.of(projectId),
        secretId,
        secret);

    // Add version
    SecretPayload payload = SecretPayload.newBuilder()
        .setData(ByteString.copyFromUtf8("my-secret-value"))
        .build();

    client.addSecretVersion(createdSecret.getName(), payload);

    // Access latest version
    AccessSecretVersionResponse response = client.accessSecretVersion(
        SecretVersionName.of(projectId, secretId, "latest"));

    System.out.println(response.getPayload().getData().toStringUtf8());
    ```

=== "Python"

    ```python
    import os
    os.environ["SECRET_MANAGER_EMULATOR_HOST"] = "localhost:4588"

    from google.cloud import secretmanager

    client = secretmanager.SecretManagerServiceClient()
    project = "projects/floci-local"

    # Create secret
    secret = client.create_secret(request={
        "parent": project,
        "secret_id": "my-secret",
        "secret": {
            "replication": {"automatic": {}},
        },
    })

    # Add version
    version = client.add_secret_version(request={
        "parent": secret.name,
        "payload": {"data": b"my-secret-value"},
    })

    # Access latest version
    response = client.access_secret_version(request={
        "name": f"{project}/secrets/my-secret/versions/latest",
    })

    print(response.payload.data.decode("UTF-8"))
    ```

=== "Node.js"

    ```javascript
    process.env.SECRET_MANAGER_EMULATOR_HOST = "localhost:4588";

    import { SecretManagerServiceClient } from "@google-cloud/secret-manager";

    const client = new SecretManagerServiceClient();
    const projectId = "floci-local";

    // Create secret
    const [secret] = await client.createSecret({
        parent: `projects/${projectId}`,
        secretId: "my-secret",
        secret: { replication: { automatic: {} } },
    });

    // Add version
    await client.addSecretVersion({
        parent: secret.name,
        payload: { data: Buffer.from("my-secret-value") },
    });

    // Access latest version
    const [version] = await client.accessSecretVersion({
        name: `projects/${projectId}/secrets/my-secret/versions/latest`,
    });

    console.log(version.payload.data.toString());
    ```

## Secret Versions

Each `AddSecretVersion` call creates a new version. Version numbers are sequential starting from `1`. The alias `latest` always resolves to the most recently added version.

```bash
# List versions
gcloud secrets versions list my-secret

# Access specific version
gcloud secrets versions access 2 --secret=my-secret

# Disable a version
gcloud secrets versions disable 1 --secret=my-secret

# Destroy a version
gcloud secrets versions destroy 1 --secret=my-secret
```

## IAM Bindings

```bash
# Grant access to a secret
gcloud secrets add-iam-policy-binding my-secret \
    --member="serviceAccount:my-sa@floci-local.iam.gserviceaccount.com" \
    --role="roles/secretmanager.secretAccessor"
```

Secret-level IAM policies persist across `GetIamPolicy` / `SetIamPolicy` calls,
including etag-based read-modify-write flows used by Terraform and OpenTofu.
Deleting a secret clears its versions and policy. Cleanup intent is persisted
before deletion, so interrupted cleanup is resumed before the emulator serves
requests after restart or recreates the same secret name. Policies are stored
but not enforced: a caller that would be denied by IAM in GCP can still access
emulator resources.

## Supported Operations

- `CreateSecret`
- `GetSecret`
- `UpdateSecret`
- `DeleteSecret`
- `ListSecrets`
- `AddSecretVersion`
- `GetSecretVersion`
- `AccessSecretVersion`
- `DisableSecretVersion`
- `EnableSecretVersion`
- `DestroySecretVersion`
- `ListSecretVersions`
- `GetIamPolicy`
- `SetIamPolicy`
- `TestIamPermissions`

## Limitations

- Regional secrets (`projects/{project}/locations/{location}/secrets/...`) are not
  supported. gRPC calls that use a regional parent or name return `UNIMPLEMENTED`,
  and the REST API only serves the global `/v1/projects/{project}/secrets/...` paths.
- gRPC parents and names must match `projects/{project}`,
  `projects/{project}/secrets/{secret}` or
  `projects/{project}/secrets/{secret}/versions/{version}`; anything else returns
  `INVALID_ARGUMENT`.
