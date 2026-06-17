#!/usr/bin/env bats
# Terraform Compatibility Tests for floci-gcp

setup_file() {
    load 'test_helper/common-setup'

    cd "$TF_DIR"

    echo "# === Terraform GCP Compatibility Test ===" >&3
    echo "# Endpoint : $FLOCI_ENDPOINT"              >&3
    echo "# Project  : $FLOCI_PROJECT"               >&3

    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true

    echo "# --- terraform init ---" >&3
    run terraform init -input=false -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform init failed: $output" >&3
        return 1
    fi

    echo "# --- terraform validate ---" >&3
    run terraform validate -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform validate failed: $output" >&3
        return 1
    fi

    echo "# --- terraform plan ---" >&3
    run terraform plan \
        -var="endpoint=${FLOCI_ENDPOINT}" \
        -var="project=${FLOCI_PROJECT}" \
        -input=false -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform plan failed: $output" >&3
        return 1
    fi

    echo "# --- terraform apply ---" >&3
    run terraform apply \
        -var="endpoint=${FLOCI_ENDPOINT}" \
        -var="project=${FLOCI_PROJECT}" \
        -input=false -auto-approve -no-color
    if [ "$status" -ne 0 ]; then
        echo "# terraform apply failed: $output" >&3
        return 1
    fi
}

teardown_file() {
    load 'test_helper/common-setup'
    cd "$TF_DIR"
    echo "# --- terraform destroy ---" >&3
    terraform destroy \
        -var="endpoint=${FLOCI_ENDPOINT}" \
        -var="project=${FLOCI_PROJECT}" \
        -input=false -auto-approve -no-color || true
    rm -rf .terraform .terraform.lock.hcl terraform.tfstate* 2>/dev/null || true
}

setup() {
    load 'test_helper/common-setup'
    cd "$TF_DIR"
}

# ── GCS Spot Checks ───────────────────────────────────────────────────────────

@test "Terraform: GCS bucket created" {
    run gcp_curl "${FLOCI_ENDPOINT}/storage/v1/b/floci-compat-bucket"
    assert_success
    assert_output --partial '"name":"floci-compat-bucket"'
}

@test "Terraform: GCS bucket has label" {
    result=$(gcp_curl "${FLOCI_ENDPOINT}/storage/v1/b/floci-compat-bucket")
    [[ "$result" == *'"env"'* ]]
}

@test "Terraform: GCS object uploaded" {
    run gcp_curl "${FLOCI_ENDPOINT}/storage/v1/b/floci-compat-bucket/o/README.txt"
    assert_success
    assert_output --partial '"name":"README.txt"'
}

@test "Terraform: GCS object content-type is text/plain" {
    result=$(gcp_curl "${FLOCI_ENDPOINT}/storage/v1/b/floci-compat-bucket/o/README.txt")
    [[ "$result" == *'"contentType":"text/plain"'* ]]
}

@test "Terraform: GCS bucket lists object" {
    run gcp_curl "${FLOCI_ENDPOINT}/storage/v1/b/floci-compat-bucket/o"
    assert_success
    assert_output --partial '"name":"README.txt"'
}

# ── IAM Spot Checks ───────────────────────────────────────────────────────────

@test "Terraform: IAM service account created" {
    email=$(terraform output -raw service_account_email 2>/dev/null)
    [ -n "$email" ]
    run gcp_curl "${FLOCI_ENDPOINT}/v1/projects/${FLOCI_PROJECT}/serviceAccounts/${email}"
    assert_success
    assert_output --partial "floci-compat-sa"
}

@test "Terraform: IAM service account email in state" {
    email=$(terraform output -raw service_account_email 2>/dev/null)
    [[ "$email" == *"floci-compat-sa"* ]]
}

# ── Secret Manager Spot Checks ────────────────────────────────────────────────

@test "Terraform: Secret Manager secret created" {
    run gcp_curl "${FLOCI_ENDPOINT}/v1/projects/${FLOCI_PROJECT}/secrets/floci-compat-secret"
    assert_success
    assert_output --partial '"name"'
    assert_output --partial 'floci-compat-secret'
}

@test "Terraform: Secret Manager secret has automatic replication" {
    result=$(gcp_curl "${FLOCI_ENDPOINT}/v1/projects/${FLOCI_PROJECT}/secrets/floci-compat-secret")
    [[ "$result" == *'"automatic"'* ]]
}

@test "Terraform: Secret Manager secret version created" {
    secret_name=$(terraform output -raw secret_name 2>/dev/null)
    [ -n "$secret_name" ]
    run gcp_curl "${FLOCI_ENDPOINT}/v1/projects/${FLOCI_PROJECT}/secrets/floci-compat-secret/versions"
    assert_success
    assert_output --partial '"versions"'
}

@test "Terraform: Secret Manager version state is ENABLED" {
    result=$(gcp_curl "${FLOCI_ENDPOINT}/v1/projects/${FLOCI_PROJECT}/secrets/floci-compat-secret/versions/1")
    [[ "$result" == *'"state":"ENABLED"'* ]]
}

@test "Terraform: Secret Manager secret listed in project" {
    run gcp_curl "${FLOCI_ENDPOINT}/v1/projects/${FLOCI_PROJECT}/secrets"
    assert_success
    assert_output --partial 'floci-compat-secret'
}

# ── Cloud SQL Spot Checks ────────────────────────────────────────────────────

@test "Terraform: Cloud SQL PostgreSQL instance created" {
    instance=$(terraform output -raw sql_instance_name 2>/dev/null)
    [ -n "$instance" ]
    run gcp_curl "${FLOCI_ENDPOINT}/sql/v1beta4/projects/${FLOCI_PROJECT}/instances/${instance}"
    assert_success
    assert_output --partial '"kind":"sql#instance"'
    assert_output --partial '"databaseVersion":"POSTGRES_15"'
    assert_output --partial '"state":"RUNNABLE"'
}

@test "Terraform: Cloud SQL database created" {
    instance=$(terraform output -raw sql_instance_name 2>/dev/null)
    database=$(terraform output -raw sql_database_name 2>/dev/null)
    [ -n "$instance" ]
    [ -n "$database" ]
    run gcp_curl "${FLOCI_ENDPOINT}/sql/v1beta4/projects/${FLOCI_PROJECT}/instances/${instance}/databases/${database}"
    assert_success
    assert_output --partial '"kind":"sql#database"'
    assert_output --partial '"name":"appdb"'
}

@test "Terraform: Cloud SQL user created" {
    instance=$(terraform output -raw sql_instance_name 2>/dev/null)
    user=$(terraform output -raw sql_user_name 2>/dev/null)
    [ -n "$instance" ]
    [ -n "$user" ]
    run gcp_curl "${FLOCI_ENDPOINT}/sql/v1beta4/projects/${FLOCI_PROJECT}/instances/${instance}/users/${user}"
    assert_success
    assert_output --partial '"kind":"sql#user"'
    assert_output --partial '"name":"app"'
}

# ── Cloud KMS Spot Checks ─────────────────────────────────────────────────────

@test "Terraform: KMS key ring created" {
    run gcp_curl "${FLOCI_ENDPOINT}/v1/projects/${FLOCI_PROJECT}/locations/${FLOCI_REGION:-us-central1}/keyRings/floci-compat-keyring"
    assert_success
    assert_output --partial 'floci-compat-keyring'
}

@test "Terraform: KMS crypto key created" {
    run gcp_curl "${FLOCI_ENDPOINT}/v1/projects/${FLOCI_PROJECT}/locations/${FLOCI_REGION:-us-central1}/keyRings/floci-compat-keyring/cryptoKeys/floci-compat-key"
    assert_success
    assert_output --partial 'floci-compat-key'
    assert_output --partial '"purpose":"ENCRYPT_DECRYPT"'
}

@test "Terraform: KMS crypto key has a primary version" {
    result=$(gcp_curl "${FLOCI_ENDPOINT}/v1/projects/${FLOCI_PROJECT}/locations/${FLOCI_REGION:-us-central1}/keyRings/floci-compat-keyring/cryptoKeys/floci-compat-key/cryptoKeyVersions")
    [[ "$result" == *'"state":"ENABLED"'* ]]
}

# ── State Integrity ───────────────────────────────────────────────────────────

@test "Terraform: all ten resources tracked in state" {
    count=$(terraform state list 2>/dev/null | wc -l | tr -d ' ')
    [ "$count" -ge 10 ]
}
