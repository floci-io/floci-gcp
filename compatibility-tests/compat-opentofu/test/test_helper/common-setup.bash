#!/usr/bin/env bash
# Common setup for OpenTofu bats tests (floci-gcp)

TOFU_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# Load bats helpers — support local lib/, BATS_LIB_PATH, or system install
if [[ -d "${TOFU_DIR}/../lib/bats-support" ]]; then
    load "${TOFU_DIR}/../lib/bats-support/load"
    load "${TOFU_DIR}/../lib/bats-assert/load"
elif [[ -n "${BATS_LIB_PATH:-}" ]]; then
    load "${BATS_LIB_PATH}/bats-support/load"
    load "${BATS_LIB_PATH}/bats-assert/load"
else
    echo "Error: cannot find bats-support/bats-assert. Install bats-core or set BATS_LIB_PATH." >&2
    exit 1
fi

export FLOCI_ENDPOINT="${FLOCI_ENDPOINT:-http://localhost:4588}"
export FLOCI_HOST="${FLOCI_HOST:-localhost:4588}"
export FLOCI_PROJECT="${FLOCI_PROJECT:-test-project}"

# GCP emulator env vars — respected by the Terraform google provider
export PUBSUB_EMULATOR_HOST="${FLOCI_HOST}"
export STORAGE_EMULATOR_HOST="${FLOCI_ENDPOINT}"
export GOOGLE_CLOUD_PROJECT="${FLOCI_PROJECT}"

# Fake OAuth token — our emulator ignores auth, but OpenTofu needs some value
# to avoid calling Google OAuth endpoints
export GOOGLE_OAUTH_ACCESS_TOKEN="fake-token-floci-gcp"

# OpenTofu variables
export TF_VAR_endpoint="${FLOCI_ENDPOINT}"
export TF_VAR_project="${FLOCI_PROJECT}"

gcp_curl() {
    curl -sf -H "Authorization: Bearer ${GOOGLE_OAUTH_ACCESS_TOKEN}" "$@"
}

cloud_run_curl() {
    local uri="$1"
    local without_scheme="${uri#*://}"
    local authority="${without_scheme%%/*}"
    local host="${authority%%:*}"
    local port="80"
    local floci_host="${FLOCI_HOST%%:*}"
    local floci_ip=""

    if [[ "${authority}" == *:* ]]; then
        port="${authority##*:}"
    fi
    if command -v getent >/dev/null 2>&1; then
        floci_ip="$(getent hosts "${floci_host}" | awk '/^[0-9.]+/ { print $1; exit }')"
    fi
    if [[ -n "${floci_ip}" ]]; then
        curl -fsS --resolve "${host}:${port}:${floci_ip}" "${uri}"
    else
        curl -fsS "${uri}"
    fi
}
