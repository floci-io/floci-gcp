# gcloud CLI compatibility tests

BATS tests that exercise the **`gcloud` CLI** against floci-gcp, mirroring the
`sdk-test-awscli` suite in the AWS emulator. Verifies that unmodified `gcloud`
commands work against the emulator for the services it can reach over REST.

## How gcloud talks to floci-gcp

Unlike the GCP SDKs, the `gcloud` CLI does **not** honour `*_EMULATOR_HOST` and
always requires a credential. `test/test_helper/common-setup.bash` configures it via
environment only (no `gcloud config` mutation):

- `CLOUDSDK_AUTH_ACCESS_TOKEN` — any non-empty value; satisfies gcloud's
  active-account check. floci-gcp ignores the token.
- `CLOUDSDK_API_ENDPOINT_OVERRIDES_<SERVICE>` — routes each service's API to the
  emulator. Storage's override includes the version path (`/storage/v1/`); the
  others take the bare base URL.

## Coverage

| Service | gcloud surface |
|---|---|
| Cloud Storage | `storage buckets create/list`, `storage cp/cat/ls/rm` |
| Secret Manager | `secrets create/list/versions add/versions access` |
| Cloud KMS | `kms keyrings create/list`, `kms keys create/list/describe` |
| IAM | `iam service-accounts create/list` |
| Cloud Scheduler | `scheduler jobs create/list/describe/pause/resume/run` |

## Known limitations (gcloud-surfaced gaps)

`gcloud` enforces stricter client-side integrity/lookups than the SDK suites, which
surfaces a few emulator gaps. These operations are intentionally not asserted here
(tracked for follow-up); the underlying data paths are covered by the SDK suites:

- **Pub/Sub** — gRPC-only in the emulator; `gcloud pubsub` (REST) cannot reach it,
  so there is no Pub/Sub coverage in this suite.
- **Cloud KMS encrypt/decrypt** — gcloud verifies request/response CRC32C; the
  emulator does not yet return matching CRC32C fields, so gcloud reports
  "corrupted in-transit". (`KmsTest` covers the encrypt/decrypt data path.)
- **Secret Manager `versions add`** — gcloud's payload CRC32C check makes the command
  exit non-zero; the version is still created correctly (the test asserts the
  round-tripped payload via `versions access`).
- **IAM `service-accounts describe`/`delete` by email** — return `NOT_FOUND` even
  though create + list work.

## Running

```bash
# Against a running emulator (just):
just test-gcloud

# Or as the CI container does:
docker build -t compat-sdk-test-gcloud .
docker run --rm --network compat-net \
  -e FLOCI_GCP_ENDPOINT=http://floci-gcp:4588 -e FLOCI_GCP_PROJECT=test-project \
  -v "$(pwd)/test-results:/results" compat-sdk-test-gcloud
```
