# Cloud KMS

floci-gcp emulates Google Cloud KMS over gRPC and REST using the real
`google.cloud.kms.v1.KeyManagementService` protocol. Crypto operations are backed by real local
cryptography (AES-256-GCM for symmetric keys; RSA/EC for asymmetric keys; HMAC for MAC keys), so
encrypt/decrypt, sign/verify and MAC sign/verify round-trips behave like GCP, including binding ciphertext to the key version, so a
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
| `MAC` | `HMAC_SHA256`, `HMAC_SHA1`, `HMAC_SHA384`, `HMAC_SHA512`, `HMAC_SHA224` | `MacSign`, `MacVerify` |

A `MAC` key needs `version_template.algorithm`, like an `ASYMMETRIC_SIGN` key. Each version holds a
random HMAC key the size of its hash output (32 bytes for `HMAC_SHA256`). `MacVerify` returns
`success: false` for a tag that does not match, whatever its length, rather than an error, and sets
`verified_success_integrity` to the same value as `success`.

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

## Key Settings

`CreateCryptoKey` stores `labels`, `rotation_period`, `next_rotation_time` and
`destroy_scheduled_duration`, and every read returns them. A key created without
`destroy_scheduled_duration` reports the 30 day default (`2592000s`). As the proto requires,
`rotation_period` and `next_rotation_time` are accepted only on `ENCRYPT_DECRYPT` keys,
`rotation_period` must be between 24 and 876,000 hours, and `next_rotation_time` must be set
with it; anything else is `INVALID_ARGUMENT`.

`UpdateCryptoKey` applies the fields its update mask names (`labels`, `rotation_period`,
`next_rotation_time`, `version_template.algorithm`); a named field left out of the request is
cleared. Without a mask it applies the fields the request sets.

Deviations:

- Keys are never rotated automatically: `next_rotation_time` is stored and returned, but no
  version is created when it passes.
- `destroy_scheduled_duration` is not validated against a range, and `DestroyCryptoKeyVersion`
  does not use it yet: `destroy_time` is still the time of the call.
- Update mask paths floci does not support, including immutable fields such as
  `destroy_scheduled_duration` and `purpose`, are ignored rather than rejected.
- The `INVALID_ARGUMENT` messages are floci's own; production's wording was not verified.

## Integrity (CRC32C)

Requests may include `*_crc32c` checksums (e.g. `plaintext_crc32c`); when present, floci-gcp
verifies them and sets the corresponding `verified_*_crc32c` response flag (a mismatch yields
`INVALID_ARGUMENT`). Responses always populate output checksums such as `ciphertext_crc32c`.

## Importing Keys

Bring-your-own-key imports follow the
[GCP import flow](https://cloud.google.com/kms/docs/importing-a-key): create an `ImportJob`, wrap
the key material with its public key, and import it into a `CryptoKey`, usually one created with
`import_only` and `skip_initial_version_creation`.

| Import method | Wrapped key format |
|---|---|
| `RSA_OAEP_3072_SHA1_AES_256`, `RSA_OAEP_4096_SHA1_AES_256` | RSA-OAEP (SHA-1, MGF1 SHA-1) of an ephemeral AES-256 key, followed by the target key wrapped with AES-KWP (RFC 5649) |
| `RSA_OAEP_3072_SHA256_AES_256`, `RSA_OAEP_4096_SHA256_AES_256` | Same, with SHA-256 for OAEP and MGF1 |
| `RSA_OAEP_3072_SHA256`, `RSA_OAEP_4096_SHA256` | The target key encrypted directly with RSA-OAEP (SHA-256) |

Target key material is the raw 32 bytes for `GOOGLE_SYMMETRIC_ENCRYPTION` and a PKCS#8 DER
private key for the asymmetric algorithms listed above. The public key of an imported asymmetric
version is derived from the private key.

Behavior to be aware of:

- Import jobs are `ACTIVE` as soon as they are created (no `PENDING_GENERATION` phase) and
  `EXPIRED` three days later; no attestation is returned for `HSM` jobs.
  As on GCP, an expired job no longer returns its `public_key` and reports `expire_event_time`
  instead.
- Imports complete synchronously. Wrapped material that cannot be unwrapped, or that does not fit
  the requested algorithm, produces a version in `IMPORT_FAILED` with `import_failure_reason` set
  rather than an error.
- The first successfully imported version of an `ENCRYPT_DECRYPT` key with no primary becomes
  the primary.
- `CreateCryptoKeyVersion` on an `import_only` key fails with `FAILED_PRECONDITION`.
- Re-importing into an existing version (`crypto_key_version`) is not supported.

## Supported Operations

- `CreateKeyRing`, `GetKeyRing`, `ListKeyRings`
- `CreateCryptoKey`, `GetCryptoKey`, `ListCryptoKeys`, `UpdateCryptoKey`, `UpdateCryptoKeyPrimaryVersion`
- `CreateCryptoKeyVersion`, `GetCryptoKeyVersion`, `ListCryptoKeyVersions`, `UpdateCryptoKeyVersion`
- `DestroyCryptoKeyVersion`, `RestoreCryptoKeyVersion`
- `CreateImportJob`, `GetImportJob`, `ListImportJobs`, `ImportCryptoKeyVersion`
- `Encrypt`, `Decrypt`
- `GetPublicKey`, `AsymmetricSign`, `AsymmetricDecrypt`
- `MacSign`, `MacVerify`
- `GenerateRandomBytes`

## Not Yet Supported

`RawEncrypt`/`RawDecrypt`, re-importing key versions, RSA-PSS signing, post-quantum
algorithms, HSM/EXTERNAL protection levels, and IAM policy storage.
