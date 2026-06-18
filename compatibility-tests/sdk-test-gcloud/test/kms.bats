#!/usr/bin/env bats
# Cloud KMS (gcloud kms) integration tests

setup_file() {
    load 'test_helper/common-setup'
    export KEYRING="$(unique_name gcloud-kr)"
    export CRYPTOKEY="$(unique_name gcloud-key)"
    gcloud kms keyrings create "$KEYRING" --location="${FLOCI_GCP_LOCATION}" >/dev/null 2>&1
    gcloud kms keys create "$CRYPTOKEY" --location="${FLOCI_GCP_LOCATION}" \
        --keyring="$KEYRING" --purpose=encryption >/dev/null 2>&1
}

setup() {
    load 'test_helper/common-setup'
}

@test "kms: key ring appears in list" {
    run gcloud_cmd kms keyrings list --location="${FLOCI_GCP_LOCATION}" --format="value(name)"
    assert_success
    assert_output --partial "keyRings/${KEYRING}"
}

@test "kms: crypto key appears in list" {
    run gcloud_cmd kms keys list --location="${FLOCI_GCP_LOCATION}" --keyring="$KEYRING" \
        --format="value(name)"
    assert_success
    assert_output --partial "cryptoKeys/${CRYPTOKEY}"
}

@test "kms: describe crypto key reports ENCRYPT_DECRYPT purpose" {
    run gcloud_cmd kms keys describe "$CRYPTOKEY" --location="${FLOCI_GCP_LOCATION}" \
        --keyring="$KEYRING" --format="value(purpose)"
    assert_success
    assert_output --partial "ENCRYPT_DECRYPT"
}

# NOTE: gcloud kms encrypt/decrypt enforce CRC32C integrity on the request/response;
# floci-gcp does not yet return the matching CRC32C fields, so gcloud rejects the
# round-trip with "corrupted in-transit" (see README "Known limitations"). The
# encrypt/decrypt data path itself is covered by the Java SDK suite (KmsTest).
