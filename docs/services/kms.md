# Cloud KMS

floci-gcp emulates Google Cloud KMS over gRPC and REST using the real
`google.cloud.kms.v1.KeyManagementService` protocol. Crypto operations are backed by real local
cryptography (AES-256-GCM for symmetric keys; RSA/EC for asymmetric keys), so encrypt/decrypt and
sign/verify round-trips behave like GCP — including binding ciphertext to the key version, so a
cross-key decrypt fails as it would in production.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_KMS_ENABLED` | `true` | Enable/disable Cloud KMS |

## Endpoint

Cloud KMS has **no `*_EMULATOR_HOST` convention**. Point the client at floci-gcp by overriding the
API endpoint / transport channel and disabling credentials:

- **gRPC** (Java/Python/Go/Node): build the client with a plaintext channel to `localhost:4588`
  and anonymous/no credentials (see Quick Start below).
- **REST / Terraform**: set the provider's `kms_custom_endpoint` to `http://localhost:4588/v1/`.

## Supported algorithms

| Purpose | Algorithm | Operations |
|---|---|---|
| `ENCRYPT_DECRYPT` | `GOOGLE_SYMMETRIC_ENCRYPTION` (AES-256-GCM) | `Encrypt`, `Decrypt` |
| `ASYMMETRIC_SIGN` | `EC_SIGN_P256_SHA256`, `RSA_SIGN_PKCS1_2048_SHA256` | `AsymmetricSign`, `GetPublicKey` |
| `ASYMMETRIC_DECRYPT` | `RSA_DECRYPT_OAEP_2048_SHA256` | `AsymmetricDecrypt`, `GetPublicKey` |

## Quick Start

=== "Java"

    ```java
    KeyManagementServiceClient client = KeyManagementServiceClient.create(
        KeyManagementServiceSettings.newBuilder()
            .setTransportChannelProvider(
                InstantiatingGrpcChannelProvider.newBuilder()
                    .setEndpoint("localhost:4588")
                    .setChannelConfigurator(b -> b.usePlaintext())
                    .build())
            .setCredentialsProvider(NoCredentialsProvider.create())
            .build());

    LocationName location = LocationName.of("floci-local", "us-central1");
    client.createKeyRing(location, "my-keyring", KeyRing.newBuilder().build());

    KeyRingName keyRing = KeyRingName.of("floci-local", "us-central1", "my-keyring");
    CryptoKey key = client.createCryptoKey(keyRing, "my-key",
        CryptoKey.newBuilder()
            .setPurpose(CryptoKey.CryptoKeyPurpose.ENCRYPT_DECRYPT)
            .build());

    CryptoKeyName keyName = CryptoKeyName.parse(key.getName());
    EncryptResponse enc = client.encrypt(keyName, ByteString.copyFromUtf8("secret"));
    DecryptResponse dec = client.decrypt(keyName, enc.getCiphertext());
    System.out.println(dec.getPlaintext().toStringUtf8());
    ```

=== "Python"

    ```python
    import grpc
    from google.cloud import kms
    from google.cloud.kms_v1.services.key_management_service.transports.grpc import (
        KeyManagementServiceGrpcTransport,
    )

    transport = KeyManagementServiceGrpcTransport(
        channel=grpc.insecure_channel("localhost:4588")
    )
    client = kms.KeyManagementServiceClient(transport=transport)

    location = "projects/floci-local/locations/us-central1"
    client.create_key_ring(request={"parent": location, "key_ring_id": "my-keyring", "key_ring": {}})

    key_ring = f"{location}/keyRings/my-keyring"
    key = client.create_crypto_key(request={
        "parent": key_ring,
        "crypto_key_id": "my-key",
        "crypto_key": {"purpose": kms.CryptoKey.CryptoKeyPurpose.ENCRYPT_DECRYPT},
    })

    enc = client.encrypt(request={"name": key.name, "plaintext": b"secret"})
    dec = client.decrypt(request={"name": key.name, "ciphertext": enc.ciphertext})
    print(dec.plaintext.decode())
    ```

=== "Node.js"

    ```javascript
    import { KeyManagementServiceClient } from "@google-cloud/kms";
    import * as grpc from "@grpc/grpc-js";

    const client = new KeyManagementServiceClient({
        servicePath: "localhost",
        port: 4588,
        sslCreds: grpc.credentials.createInsecure(),
    });

    const location = "projects/floci-local/locations/us-central1";
    await client.createKeyRing({ parent: location, keyRingId: "my-keyring", keyRing: {} });

    const keyRing = `${location}/keyRings/my-keyring`;
    const [key] = await client.createCryptoKey({
        parent: keyRing,
        cryptoKeyId: "my-key",
        cryptoKey: { purpose: "ENCRYPT_DECRYPT" },
    });

    const [enc] = await client.encrypt({ name: key.name, plaintext: Buffer.from("secret") });
    const [dec] = await client.decrypt({ name: key.name, ciphertext: enc.ciphertext });
    console.log(Buffer.from(dec.plaintext).toString());
    ```

=== "Terraform"

    ```hcl
    provider "google" {
      project             = "floci-local"
      region              = "us-central1"
      kms_custom_endpoint = "http://localhost:4588/v1/"
    }

    resource "google_kms_key_ring" "example" {
      name     = "my-keyring"
      location = "us-central1"
    }

    resource "google_kms_crypto_key" "example" {
      name     = "my-key"
      key_ring = google_kms_key_ring.example.id
      purpose  = "ENCRYPT_DECRYPT"
    }
    ```

## Key Versions

`CreateCryptoKey` with `ENCRYPT_DECRYPT` auto-creates version `1` and marks it primary.
`Encrypt` uses the primary version unless a specific version is named; `Decrypt` automatically
selects the version that produced the ciphertext. Rotate by calling `CreateCryptoKeyVersion` then
`UpdateCryptoKeyPrimaryVersion`. Version state transitions:

- Enable/disable via `UpdateCryptoKeyVersion` (`state` = `ENABLED` / `DISABLED`).
- `DestroyCryptoKeyVersion` moves a version to `DESTROY_SCHEDULED`; `RestoreCryptoKeyVersion`
  returns it to `DISABLED`. Crypto operations on a non-`ENABLED` version fail with
  `FAILED_PRECONDITION`.

## Integrity (CRC32C)

Requests may include `*_crc32c` checksums (e.g. `plaintext_crc32c`); when present, floci-gcp
verifies them and sets the corresponding `verified_*_crc32c` response flag (a mismatch yields
`INVALID_ARGUMENT`). Responses always populate output checksums such as `ciphertext_crc32c`.

## Supported Operations

- `CreateKeyRing`, `GetKeyRing`, `ListKeyRings`
- `CreateCryptoKey`, `GetCryptoKey`, `ListCryptoKeys`, `UpdateCryptoKey`, `UpdateCryptoKeyPrimaryVersion`
- `CreateCryptoKeyVersion`, `GetCryptoKeyVersion`, `ListCryptoKeyVersions`, `UpdateCryptoKeyVersion`
- `DestroyCryptoKeyVersion`, `RestoreCryptoKeyVersion`
- `Encrypt`, `Decrypt`
- `GetPublicKey`, `AsymmetricSign`, `AsymmetricDecrypt`
- `GenerateRandomBytes`

## Not Yet Supported

`RawEncrypt`/`RawDecrypt`, `MacSign`/`MacVerify`, import jobs, RSA-PSS signing, post-quantum
algorithms, HSM/EXTERNAL protection levels, and IAM policy storage.