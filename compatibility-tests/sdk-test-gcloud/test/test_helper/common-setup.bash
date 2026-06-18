#!/usr/bin/env bash
# Common setup for gcloud CLI bats tests against floci-gcp.

# Load bats-support and bats-assert (local lib/ dir or BATS_LIB_PATH for Docker).
_COMMON_SETUP_DIR="${BATS_TEST_DIRNAME}"
if [[ -d "${_COMMON_SETUP_DIR}/../../lib/bats-support" ]]; then
    load "${_COMMON_SETUP_DIR}/../../lib/bats-support/load.bash"
    load "${_COMMON_SETUP_DIR}/../../lib/bats-assert/load.bash"
elif [[ -n "${BATS_LIB_PATH:-}" ]]; then
    load "${BATS_LIB_PATH}/bats-support/load.bash"
    load "${BATS_LIB_PATH}/bats-assert/load.bash"
else
    echo "Error: Cannot find bats-support/bats-assert libraries" >&2
    exit 1
fi

# Endpoint + project.
export FLOCI_GCP_ENDPOINT="${FLOCI_GCP_ENDPOINT:-http://localhost:4588}"
export CLOUDSDK_CORE_PROJECT="${FLOCI_GCP_PROJECT:-${CLOUDSDK_CORE_PROJECT:-test-project}}"
export FLOCI_GCP_LOCATION="${FLOCI_GCP_LOCATION:-us-central1}"

# Auth bypass: the gcloud CLI (unlike the GCP SDKs) requires *some* credential and
# does not honour *_EMULATOR_HOST. A fake access token satisfies the active-account
# check; floci-gcp ignores the token entirely.
export CLOUDSDK_AUTH_ACCESS_TOKEN="${CLOUDSDK_AUTH_ACCESS_TOKEN:-floci-fake-token}"
export CLOUDSDK_CORE_DISABLE_PROMPTS=1

# Per-service API endpoint overrides routing gcloud to the emulator. gcloud has no
# single --endpoint-url; each service is overridden by its CLOUDSDK_API_ENDPOINT_OVERRIDES_*
# variable. Storage's override must include the version path (/storage/v1/); the others
# take the bare base URL and the client appends the version.
export CLOUDSDK_API_ENDPOINT_OVERRIDES_STORAGE="${FLOCI_GCP_ENDPOINT}/storage/v1/"
export CLOUDSDK_API_ENDPOINT_OVERRIDES_SECRETMANAGER="${FLOCI_GCP_ENDPOINT}/"
export CLOUDSDK_API_ENDPOINT_OVERRIDES_CLOUDKMS="${FLOCI_GCP_ENDPOINT}/"
export CLOUDSDK_API_ENDPOINT_OVERRIDES_IAM="${FLOCI_GCP_ENDPOINT}/"
export CLOUDSDK_API_ENDPOINT_OVERRIDES_CLOUDSCHEDULER="${FLOCI_GCP_ENDPOINT}/"

# Run a gcloud command (stderr folded into stdout so asserts can match either).
gcloud_cmd() {
    gcloud "$@" 2>&1
}

# Extract a JSON value with jq (empty string on miss).
json_get() {
    local json="$1"
    local path="$2"
    echo "$json" | jq -r "$path" 2>/dev/null || echo ""
}

# Unique resource name for a test run.
unique_name() {
    local prefix="${1:-test}"
    echo "${prefix}-$(date +%s)-$$"
}
