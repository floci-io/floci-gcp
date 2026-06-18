#!/usr/bin/env bats
# Cloud Storage (gcloud storage) integration tests

setup_file() {
    load 'test_helper/common-setup'
    export BUCKET="$(unique_name gcloud-bkt)"
    gcloud storage buckets create "gs://${BUCKET}" >/dev/null 2>&1
}

teardown_file() {
    load 'test_helper/common-setup'
    gcloud storage rm --recursive "gs://${BUCKET}" >/dev/null 2>&1 || true
}

setup() {
    load 'test_helper/common-setup'
}

@test "storage: bucket appears in list" {
    run gcloud_cmd storage buckets list --format="value(name)"
    assert_success
    assert_output --partial "${BUCKET}"
}

@test "storage: upload and read object" {
    local f="${BATS_TEST_TMPDIR}/hello.txt"
    echo "hello floci-gcp" > "$f"

    run gcloud_cmd storage cp "$f" "gs://${BUCKET}/hello.txt"
    assert_success

    run gcloud_cmd storage cat "gs://${BUCKET}/hello.txt"
    assert_success
    assert_output --partial "hello floci-gcp"
}

@test "storage: list objects shows uploaded object" {
    local f="${BATS_TEST_TMPDIR}/data.txt"
    echo "x" > "$f"
    gcloud_cmd storage cp "$f" "gs://${BUCKET}/data.txt" >/dev/null

    run gcloud_cmd storage ls "gs://${BUCKET}/"
    assert_success
    assert_output --partial "gs://${BUCKET}/data.txt"
}

@test "storage: delete object" {
    local f="${BATS_TEST_TMPDIR}/del.txt"
    echo "x" > "$f"
    gcloud_cmd storage cp "$f" "gs://${BUCKET}/del.txt" >/dev/null

    run gcloud_cmd storage rm "gs://${BUCKET}/del.txt"
    assert_success

    run gcloud_cmd storage ls "gs://${BUCKET}/del.txt"
    assert_failure
}
