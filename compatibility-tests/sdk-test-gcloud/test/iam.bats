#!/usr/bin/env bats
# IAM service accounts (gcloud iam service-accounts) integration tests
#
# NOTE: describe/delete by service-account email currently return NOT_FOUND against
# floci-gcp even though create + list work (see README "Known limitations"), so this
# suite covers create + list only.

setup() {
    load 'test_helper/common-setup'
    SA_ID="gcloud-sa-${RANDOM}"
    SA_EMAIL="${SA_ID}@${CLOUDSDK_CORE_PROJECT}.iam.gserviceaccount.com"
}

@test "iam: create service account" {
    run gcloud_cmd iam service-accounts create "$SA_ID" --display-name="gcloud test"
    assert_success
    assert_output --partial "$SA_ID"
}

@test "iam: service account appears in list" {
    gcloud_cmd iam service-accounts create "$SA_ID" --display-name="gcloud test" >/dev/null

    run gcloud_cmd iam service-accounts list --format="value(email)"
    assert_success
    assert_output --partial "$SA_EMAIL"
}
