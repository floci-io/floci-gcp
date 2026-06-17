"""Cloud KMS integration tests using google-cloud-kms."""

import hashlib

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.asymmetric.utils import Prehashed


LOCATION = "us-central1"


@pytest.fixture
def key_ring(kms_client, project_id, unique_name):
    location_path = f"projects/{project_id}/locations/{LOCATION}"
    key_ring_id = f"kr-{unique_name}"
    kms_client.create_key_ring(
        request={"parent": location_path, "key_ring_id": key_ring_id, "key_ring": {}}
    )
    return f"{location_path}/keyRings/{key_ring_id}"


def _create_key(kms_client, key_ring, key_id, purpose, algorithm=None):
    crypto_key = {"purpose": purpose}
    if algorithm:
        crypto_key["version_template"] = {"algorithm": algorithm}
    return kms_client.create_crypto_key(
        request={"parent": key_ring, "crypto_key_id": key_id, "crypto_key": crypto_key}
    )


def test_symmetric_encrypt_decrypt_round_trip(kms_client, key_ring, unique_name):
    from google.cloud import kms

    key = _create_key(kms_client, key_ring, f"sym-{unique_name}",
                      kms.CryptoKey.CryptoKeyPurpose.ENCRYPT_DECRYPT)

    plaintext = b"envelope-encryption-payload"
    enc = kms_client.encrypt(request={"name": key.name, "plaintext": plaintext})
    assert enc.ciphertext != plaintext

    dec = kms_client.decrypt(request={"name": key.name, "ciphertext": enc.ciphertext})
    assert dec.plaintext == plaintext
    assert dec.used_primary is True


def test_decrypt_with_wrong_key_fails(kms_client, key_ring, unique_name):
    from google.cloud import kms
    from google.api_core import exceptions

    k1 = _create_key(kms_client, key_ring, f"a-{unique_name}",
                     kms.CryptoKey.CryptoKeyPurpose.ENCRYPT_DECRYPT)
    k2 = _create_key(kms_client, key_ring, f"b-{unique_name}",
                     kms.CryptoKey.CryptoKeyPurpose.ENCRYPT_DECRYPT)

    enc = kms_client.encrypt(request={"name": k1.name, "plaintext": b"secret"})
    with pytest.raises(exceptions.GoogleAPICallError):
        kms_client.decrypt(request={"name": k2.name, "ciphertext": enc.ciphertext})


def test_ec_sign_and_verify_with_public_key(kms_client, key_ring, unique_name):
    from google.cloud import kms
    from cryptography.hazmat.primitives.asymmetric import ec

    key = _create_key(kms_client, key_ring, f"ec-{unique_name}",
                      kms.CryptoKey.CryptoKeyPurpose.ASYMMETRIC_SIGN,
                      kms.CryptoKeyVersion.CryptoKeyVersionAlgorithm.EC_SIGN_P256_SHA256)
    version_name = f"{key.name}/cryptoKeyVersions/1"

    data = b"ecdsa-message"
    digest = hashlib.sha256(data).digest()
    sign_response = kms_client.asymmetric_sign(
        request={"name": version_name, "digest": {"sha256": digest}}
    )

    pub_pem = kms_client.get_public_key(request={"name": version_name}).pem
    public_key = serialization.load_pem_public_key(pub_pem.encode())
    public_key.verify(sign_response.signature, data, ec.ECDSA(hashes.SHA256()))


def test_rsa_pkcs1_sign_and_verify_with_public_key(kms_client, key_ring, unique_name):
    from google.cloud import kms

    key = _create_key(kms_client, key_ring, f"rsasign-{unique_name}",
                      kms.CryptoKey.CryptoKeyPurpose.ASYMMETRIC_SIGN,
                      kms.CryptoKeyVersion.CryptoKeyVersionAlgorithm.RSA_SIGN_PKCS1_2048_SHA256)
    version_name = f"{key.name}/cryptoKeyVersions/1"

    data = b"rsa-message"
    digest = hashlib.sha256(data).digest()
    sign_response = kms_client.asymmetric_sign(
        request={"name": version_name, "digest": {"sha256": digest}}
    )

    pub_pem = kms_client.get_public_key(request={"name": version_name}).pem
    public_key = serialization.load_pem_public_key(pub_pem.encode())
    public_key.verify(sign_response.signature, digest, padding.PKCS1v15(), Prehashed(hashes.SHA256()))


def test_rsa_oaep_asymmetric_decrypt(kms_client, key_ring, unique_name):
    from google.cloud import kms

    key = _create_key(kms_client, key_ring, f"rsadec-{unique_name}",
                      kms.CryptoKey.CryptoKeyPurpose.ASYMMETRIC_DECRYPT,
                      kms.CryptoKeyVersion.CryptoKeyVersionAlgorithm.RSA_DECRYPT_OAEP_2048_SHA256)
    version_name = f"{key.name}/cryptoKeyVersions/1"

    pub_pem = kms_client.get_public_key(request={"name": version_name}).pem
    public_key = serialization.load_pem_public_key(pub_pem.encode())
    plaintext = b"asymmetric-secret"
    ciphertext = public_key.encrypt(
        plaintext,
        padding.OAEP(mgf=padding.MGF1(algorithm=hashes.SHA256()), algorithm=hashes.SHA256(), label=None),
    )

    response = kms_client.asymmetric_decrypt(request={"name": version_name, "ciphertext": ciphertext})
    assert response.plaintext == plaintext


def test_generate_random_bytes(kms_client, project_id):
    from google.cloud import kms

    location_path = f"projects/{project_id}/locations/{LOCATION}"
    response = kms_client.generate_random_bytes(
        request={
            "location": location_path,
            "length_bytes": 32,
            "protection_level": kms.ProtectionLevel.SOFTWARE,
        }
    )
    assert len(response.data) == 32
