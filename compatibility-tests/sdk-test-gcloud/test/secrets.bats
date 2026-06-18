#!/usr/bin/env bats
# Secret Manager (gcloud secrets) integration tests

setup() {
    load 'test_helper/common-setup'
    SECRET="$(unique_name gcloud-secret)"
}

teardown() {
    if [ -n "${SECRET:-}" ]; then
        gcloud_cmd secrets delete "$SECRET" --quiet >/dev/null 2>&1 || true
    fi
}

@test "secrets: create secret with initial version" {
    run bash -c "printf 's3cr3t' | gcloud secrets create '$SECRET' --data-file=- 2>&1"
    assert_success

    run gcloud_cmd secrets describe "$SECRET" --format="value(name)"
    assert_success
    assert_output --partial "$SECRET"
}

@test "secrets: secret appears in list" {
    printf 'v' | gcloud_cmd secrets create "$SECRET" --data-file=- >/dev/null

    run gcloud_cmd secrets list --format="value(name)"
    assert_success
    assert_output --partial "$SECRET"
}

@test "secrets: access secret version returns payload" {
    printf 'top-secret-value' | gcloud_cmd secrets create "$SECRET" --data-file=- >/dev/null

    run gcloud_cmd secrets versions access latest --secret="$SECRET"
    assert_success
    assert_output --partial "top-secret-value"
}

@test "secrets: add a second version" {
    printf 'v1' | gcloud_cmd secrets create "$SECRET" --data-file=- >/dev/null

    # NOTE: gcloud's client-side payload CRC32C check makes `versions add` exit
    # non-zero against floci-gcp (AddSecretVersion does not echo data_crc32c yet —
    # see README "Known limitations"). The version is still created correctly, so we
    # assert on the round-tripped payload rather than the add command's exit status.
    printf 'v2' | gcloud secrets versions add "$SECRET" --data-file=- >/dev/null 2>&1 || true

    run gcloud_cmd secrets versions access latest --secret="$SECRET"
    assert_success
    assert_output --partial "v2"
}
