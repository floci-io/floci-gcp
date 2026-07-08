#!/usr/bin/env bats
# OpenTofu Compatibility Tests for floci-gcp

setup_file() {
    load 'test_helper/common-setup'

    cd "$TOFU_DIR"

    echo "# === OpenTofu GCP Compatibility Test ===" >&3
    echo "# Endpoint : $FLOCI_ENDPOINT"              >&3
    echo "# Project  : $FLOCI_PROJECT"               >&3

    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true

    echo "# --- tofu init ---" >&3
    run tofu init -input=false -no-color
    if [ "$status" -ne 0 ]; then
        echo "# tofu init failed: $output" >&3
        return 1
    fi

    echo "# --- tofu validate ---" >&3
    run tofu validate -no-color
    if [ "$status" -ne 0 ]; then
        echo "# tofu validate failed: $output" >&3
        return 1
    fi

    echo "# --- tofu plan ---" >&3
    run tofu plan \
        -var="endpoint=${FLOCI_ENDPOINT}" \
        -var="project=${FLOCI_PROJECT}" \
        -input=false -no-color
    if [ "$status" -ne 0 ]; then
        echo "# tofu plan failed: $output" >&3
        return 1
    fi

    echo "# --- tofu apply ---" >&3
    run tofu apply \
        -var="endpoint=${FLOCI_ENDPOINT}" \
        -var="project=${FLOCI_PROJECT}" \
        -input=false -auto-approve -no-color
    if [ "$status" -ne 0 ]; then
        echo "# tofu apply failed: $output" >&3
        return 1
    fi
}

teardown_file() {
    load 'test_helper/common-setup'
    cd "$TOFU_DIR"
    echo "# --- tofu destroy ---" >&3
    tofu destroy \
        -var="endpoint=${FLOCI_ENDPOINT}" \
        -var="project=${FLOCI_PROJECT}" \
        -input=false -auto-approve -no-color || true
    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true
}

setup() {
    load 'test_helper/common-setup'
    cd "$TOFU_DIR"
}

# ── GCS Spot Checks ───────────────────────────────────────────────────────────

@test "OpenTofu: GCS bucket created" {
    run gcp_curl "${FLOCI_ENDPOINT}/storage/v1/b/floci-compat-bucket-tofu"
    assert_success
    assert_output --partial '"name":"floci-compat-bucket-tofu"'
}

@test "OpenTofu: GCS bucket has label" {
    result=$(gcp_curl "${FLOCI_ENDPOINT}/storage/v1/b/floci-compat-bucket-tofu")
    [[ "$result" == *'"env"'* ]]
}

@test "OpenTofu: GCS object uploaded" {
    run gcp_curl "${FLOCI_ENDPOINT}/storage/v1/b/floci-compat-bucket-tofu/o/README.txt"
    assert_success
    assert_output --partial '"name":"README.txt"'
}

@test "OpenTofu: GCS object content-type is text/plain" {
    result=$(gcp_curl "${FLOCI_ENDPOINT}/storage/v1/b/floci-compat-bucket-tofu/o/README.txt")
    [[ "$result" == *'"contentType":"text/plain"'* ]]
}

@test "OpenTofu: GCS bucket lists object" {
    run gcp_curl "${FLOCI_ENDPOINT}/storage/v1/b/floci-compat-bucket-tofu/o"
    assert_success
    assert_output --partial '"name":"README.txt"'
}

# ── IAM Spot Checks ───────────────────────────────────────────────────────────

@test "OpenTofu: IAM service account created" {
    email=$(tofu output -raw service_account_email 2>/dev/null)
    [ -n "$email" ]
    run gcp_curl "${FLOCI_ENDPOINT}/v1/projects/${FLOCI_PROJECT}/serviceAccounts/${email}"
    assert_success
    assert_output --partial "floci-compat-sa-tofu"
}

@test "OpenTofu: IAM service account email in state" {
    email=$(tofu output -raw service_account_email 2>/dev/null)
    [[ "$email" == *"floci-compat-sa-tofu"* ]]
}

# ── Secret Manager Spot Checks ────────────────────────────────────────────────

@test "OpenTofu: Secret Manager secret created" {
    run gcp_curl "${FLOCI_ENDPOINT}/v1/projects/${FLOCI_PROJECT}/secrets/floci-compat-secret-tofu"
    assert_success
    assert_output --partial '"name"'
    assert_output --partial 'floci-compat-secret-tofu'
}

@test "OpenTofu: Secret Manager secret has automatic replication" {
    result=$(gcp_curl "${FLOCI_ENDPOINT}/v1/projects/${FLOCI_PROJECT}/secrets/floci-compat-secret-tofu")
    [[ "$result" == *'"automatic"'* ]]
}

@test "OpenTofu: Secret Manager secret version created" {
    secret_name=$(tofu output -raw secret_name 2>/dev/null)
    [ -n "$secret_name" ]
    run gcp_curl "${FLOCI_ENDPOINT}/v1/projects/${FLOCI_PROJECT}/secrets/floci-compat-secret-tofu/versions"
    assert_success
    assert_output --partial '"versions"'
}

@test "OpenTofu: Secret Manager version state is ENABLED" {
    result=$(gcp_curl "${FLOCI_ENDPOINT}/v1/projects/${FLOCI_PROJECT}/secrets/floci-compat-secret-tofu/versions/1")
    [[ "$result" == *'"state":"ENABLED"'* ]]
}

@test "OpenTofu: Secret Manager secret listed in project" {
    run gcp_curl "${FLOCI_ENDPOINT}/v1/projects/${FLOCI_PROJECT}/secrets"
    assert_success
    assert_output --partial 'floci-compat-secret-tofu'
}

# ── Cloud Run Spot Checks ────────────────────────────────────────────────────

@test "OpenTofu: Cloud Run service created" {
    run gcp_curl "${FLOCI_ENDPOINT}/v2/projects/${FLOCI_PROJECT}/locations/us-central1/services/floci-compat-run-tofu"
    assert_success
    assert_output --partial 'floci-compat-run-tofu'
    assert_output --partial '"latestReadyRevision"'
}

@test "OpenTofu: Cloud Run service URI is in state" {
    uri=$(tofu output -raw cloud_run_uri 2>/dev/null)
    [[ "$uri" == http* ]]
}

@test "OpenTofu: Cloud Run service URI is invokable when execution is enabled" {
    if [ "${FLOCI_GCP_CLOUDRUN_EXECUTION_ENABLED:-false}" != "true" ]; then
        skip "Cloud Run execution is not enabled"
    fi

    uri=$(tofu output -raw cloud_run_uri 2>/dev/null)
    [ -n "$uri" ]
    run cloud_run_curl "$uri"
    assert_success
    assert_output --partial "floci-gcp opentofu cloud run gcs volume"
}

@test "OpenTofu: Cloud Run service update uses provider patch path" {
    run tofu apply \
        -var="endpoint=${FLOCI_ENDPOINT}" \
        -var="project=${FLOCI_PROJECT}" \
        -var="cloud_run_label=compat-updated" \
        -var="cloud_run_env_value=updated" \
        -input=false -auto-approve -no-color
    assert_success

    result=$(gcp_curl "${FLOCI_ENDPOINT}/v2/projects/${FLOCI_PROJECT}/locations/us-central1/services/floci-compat-run-tofu")
    [[ "$result" == *'"env":"compat-updated"'* ]]
    [[ "$result" == *'floci-compat-run-tofu/revisions/floci-compat-run-tofu-00002'* ]]
}

@test "OpenTofu: Cloud Run same-name service replacement destroy completes" {
    if [ "${FLOCI_GCP_CLOUDRUN_EXECUTION_ENABLED:-false}" != "true" ]; then
        skip "Cloud Run execution is not enabled"
    fi

    run tofu apply \
        -var="endpoint=${FLOCI_ENDPOINT}" \
        -var="project=${FLOCI_PROJECT}" \
        -var="cloud_run_label=compat-same-name-replaced" \
        -var="cloud_run_env_value=same-name-replaced" \
        -var="cloud_run_replace_token=same-name-replaced" \
        -input=false -auto-approve -no-color
    assert_success

    run gcp_curl "${FLOCI_ENDPOINT}/v2/projects/${FLOCI_PROJECT}/locations/us-central1/services/floci-compat-run-tofu"
    assert_success
    assert_output --partial 'compat-same-name-replaced'

    uri=$(tofu output -raw cloud_run_uri 2>/dev/null)
    run cloud_run_curl "$uri"
    assert_success
    assert_output --partial "floci-gcp opentofu cloud run gcs volume"
}

@test "OpenTofu: Cloud Run service replacement destroy completes" {
    if [ "${FLOCI_GCP_CLOUDRUN_EXECUTION_ENABLED:-false}" != "true" ]; then
        skip "Cloud Run execution is not enabled"
    fi

    run tofu apply \
        -var="endpoint=${FLOCI_ENDPOINT}" \
        -var="project=${FLOCI_PROJECT}" \
        -var="cloud_run_name=floci-compat-run-tofu-replaced" \
        -var="cloud_run_label=compat-replaced" \
        -var="cloud_run_env_value=replaced" \
        -input=false -auto-approve -no-color
    assert_success

    run gcp_curl "${FLOCI_ENDPOINT}/v2/projects/${FLOCI_PROJECT}/locations/us-central1/services/floci-compat-run-tofu"
    assert_failure

    run gcp_curl "${FLOCI_ENDPOINT}/v2/projects/${FLOCI_PROJECT}/locations/us-central1/services/floci-compat-run-tofu-replaced"
    assert_success
    assert_output --partial 'floci-compat-run-tofu-replaced'
}

# ── Cloud SQL Spot Checks ────────────────────────────────────────────────────

@test "OpenTofu: Cloud SQL PostgreSQL instance created" {
    instance=$(tofu output -raw sql_instance_name 2>/dev/null)
    [ -n "$instance" ]
    run gcp_curl "${FLOCI_ENDPOINT}/sql/v1beta4/projects/${FLOCI_PROJECT}/instances/${instance}"
    assert_success
    assert_output --partial '"kind":"sql#instance"'
    assert_output --partial '"databaseVersion":"POSTGRES_18"'
    assert_output --partial '"state":"RUNNABLE"'
}

@test "OpenTofu: Cloud SQL database created" {
    instance=$(tofu output -raw sql_instance_name 2>/dev/null)
    database=$(tofu output -raw sql_database_name 2>/dev/null)
    [ -n "$instance" ]
    [ -n "$database" ]
    run gcp_curl "${FLOCI_ENDPOINT}/sql/v1beta4/projects/${FLOCI_PROJECT}/instances/${instance}/databases/${database}"
    assert_success
    assert_output --partial '"kind":"sql#database"'
    assert_output --partial '"name":"appdb"'
}

@test "OpenTofu: Cloud SQL user created" {
    instance=$(tofu output -raw sql_instance_name 2>/dev/null)
    user=$(tofu output -raw sql_user_name 2>/dev/null)
    [ -n "$instance" ]
    [ -n "$user" ]
    run gcp_curl "${FLOCI_ENDPOINT}/sql/v1beta4/projects/${FLOCI_PROJECT}/instances/${instance}/users/${user}"
    assert_success
    assert_output --partial '"kind":"sql#user"'
    assert_output --partial '"name":"app"'
}

# ── Cloud KMS Spot Checks ─────────────────────────────────────────────────────

@test "OpenTofu: KMS key ring created" {
    run gcp_curl "${FLOCI_ENDPOINT}/v1/projects/${FLOCI_PROJECT}/locations/${FLOCI_REGION:-us-central1}/keyRings/floci-compat-keyring-tofu"
    assert_success
    assert_output --partial 'floci-compat-keyring-tofu'
}

@test "OpenTofu: KMS crypto key created" {
    run gcp_curl "${FLOCI_ENDPOINT}/v1/projects/${FLOCI_PROJECT}/locations/${FLOCI_REGION:-us-central1}/keyRings/floci-compat-keyring-tofu/cryptoKeys/floci-compat-key-tofu"
    assert_success
    assert_output --partial 'floci-compat-key-tofu'
    assert_output --partial '"purpose":"ENCRYPT_DECRYPT"'
}

@test "OpenTofu: KMS crypto key has a primary version" {
    result=$(gcp_curl "${FLOCI_ENDPOINT}/v1/projects/${FLOCI_PROJECT}/locations/${FLOCI_REGION:-us-central1}/keyRings/floci-compat-keyring-tofu/cryptoKeys/floci-compat-key-tofu/cryptoKeyVersions")
    [[ "$result" == *'"state":"ENABLED"'* ]]
}

# ── Service Usage Spot Checks ─────────────────────────────────────────────────

@test "OpenTofu: Service Usage project service enabled" {
    service=$(tofu output -raw enabled_service 2>/dev/null)
    [ "$service" = "run.googleapis.com" ]
    run gcp_curl "${FLOCI_ENDPOINT}/v1/projects/${FLOCI_PROJECT}/services/run.googleapis.com"
    assert_success
    assert_output --partial '"state":"ENABLED"'
}

@test "OpenTofu: Service Usage enabled services list includes run.googleapis.com" {
    run gcp_curl "${FLOCI_ENDPOINT}/v1/projects/${FLOCI_PROJECT}/services?filter=state:ENABLED"
    assert_success
    assert_output --partial 'run.googleapis.com'
}

# ── State Integrity ───────────────────────────────────────────────────────────

@test "OpenTofu: all managed resources tracked in state" {
    count=$(tofu state list 2>/dev/null | wc -l | tr -d ' ')
    [ "$count" -ge 13 ]
}
