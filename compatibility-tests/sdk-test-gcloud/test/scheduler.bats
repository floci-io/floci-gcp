#!/usr/bin/env bats
# Cloud Scheduler (gcloud scheduler) integration tests

setup() {
    load 'test_helper/common-setup'
    JOB="$(unique_name gcloud-job)"
    LOC="${FLOCI_GCP_LOCATION}"
}

teardown() {
    if [ -n "${JOB:-}" ]; then
        gcloud_cmd scheduler jobs delete "$JOB" --location="$LOC" --quiet >/dev/null 2>&1 || true
    fi
}

@test "scheduler: create http job is ENABLED with schedule" {
    run gcloud_cmd scheduler jobs create http "$JOB" --location="$LOC" \
        --schedule="*/5 * * * *" --uri="http://example.com/hook"
    assert_success

    run gcloud_cmd scheduler jobs describe "$JOB" --location="$LOC" \
        --format="value(state,schedule)"
    assert_success
    assert_output --partial "ENABLED"
    assert_output --partial "*/5 * * * *"
}

@test "scheduler: job appears in list" {
    gcloud_cmd scheduler jobs create http "$JOB" --location="$LOC" \
        --schedule="*/5 * * * *" --uri="http://example.com/hook" >/dev/null

    run gcloud_cmd scheduler jobs list --location="$LOC" --format="value(name)"
    assert_success
    assert_output --partial "${JOB}"
}

@test "scheduler: pause then resume flips state" {
    gcloud_cmd scheduler jobs create http "$JOB" --location="$LOC" \
        --schedule="*/5 * * * *" --uri="http://example.com/hook" >/dev/null

    run gcloud_cmd scheduler jobs pause "$JOB" --location="$LOC"
    assert_success
    run gcloud_cmd scheduler jobs describe "$JOB" --location="$LOC" --format="value(state)"
    assert_output --partial "PAUSED"

    run gcloud_cmd scheduler jobs resume "$JOB" --location="$LOC"
    assert_success
    run gcloud_cmd scheduler jobs describe "$JOB" --location="$LOC" --format="value(state)"
    assert_output --partial "ENABLED"
}

@test "scheduler: run job on demand" {
    gcloud_cmd scheduler jobs create http "$JOB" --location="$LOC" \
        --schedule="*/5 * * * *" --uri="http://example.com/hook" >/dev/null

    run gcloud_cmd scheduler jobs run "$JOB" --location="$LOC"
    assert_success
}
