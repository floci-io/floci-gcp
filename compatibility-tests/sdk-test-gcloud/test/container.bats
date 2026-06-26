#!/usr/bin/env bats
# GKE (gcloud container) integration tests.
# Regional clusters use the projects/*/locations/* path the emulator implements.

setup_file() {
    load 'test_helper/common-setup'
    export CLUSTER="$(unique_name gcloud-gke)"
    gcloud container clusters create "$CLUSTER" \
        --region="${FLOCI_GCP_LOCATION}" --async >/dev/null 2>&1
}

teardown_file() {
    load 'test_helper/common-setup'
    gcloud container clusters delete "$CLUSTER" \
        --region="${FLOCI_GCP_LOCATION}" --async --quiet >/dev/null 2>&1 || true
}

setup() {
    load 'test_helper/common-setup'
}

@test "container: cluster appears in list" {
    run gcloud_cmd container clusters list --region="${FLOCI_GCP_LOCATION}" \
        --format="value(name)"
    assert_success
    assert_output --partial "$CLUSTER"
}

@test "container: describe reports a status" {
    run gcloud_cmd container clusters describe "$CLUSTER" \
        --region="${FLOCI_GCP_LOCATION}" --format="value(status)"
    assert_success
    assert_output --regexp "PROVISIONING|RUNNING"
}

@test "container: describe returns the cluster name" {
    run gcloud_cmd container clusters describe "$CLUSTER" \
        --region="${FLOCI_GCP_LOCATION}" --format="value(name)"
    assert_success
    assert_output --partial "$CLUSTER"
}
