#!/usr/bin/env bats
# Service Usage (gcloud services) integration tests

setup() {
    load 'test_helper/common-setup'
    SERVICE="run.googleapis.com"
}

teardown() {
    gcloud_cmd services disable "$SERVICE" --force >/dev/null 2>&1 || true
}

@test "services: enable then list --enabled shows the service" {
    run gcloud_cmd services enable "$SERVICE"
    assert_success

    run gcloud_cmd services list --enabled --format="value(config.name)"
    assert_success
    assert_output --partial "$SERVICE"
}

@test "services: batch enable multiple services" {
    run gcloud_cmd services enable pubsub.googleapis.com storage.googleapis.com
    assert_success

    run gcloud_cmd services list --enabled --format="value(config.name)"
    assert_success
    assert_output --partial "pubsub.googleapis.com"
    assert_output --partial "storage.googleapis.com"
}

@test "services: disable removes the service from the enabled list" {
    gcloud_cmd services enable "$SERVICE" >/dev/null

    run gcloud_cmd services disable "$SERVICE" --force
    assert_success

    run gcloud_cmd services list --enabled --format="value(config.name)"
    assert_success
    refute_output --partial "$SERVICE"
}
