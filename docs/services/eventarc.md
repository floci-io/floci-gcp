# Eventarc

floci-gcp emulates Eventarc over REST JSON (`eventarc.googleapis.com` v1). Triggers are stored
in the configured backend. Destinations can target Cloud Run or HTTP endpoints, and Pub/Sub /
GCS-style event filters are accepted for local routing exercises.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_EVENTARC_ENABLED` | `true` | Enable/disable Eventarc |

## Endpoints

| Method | Path |
|---|---|
| `POST` | `/v1/projects/{project}/locations/{location}/triggers?triggerId=...` |
| `GET` | `/v1/projects/{project}/locations/{location}/triggers` |
| `GET` | `/v1/projects/{project}/locations/{location}/triggers/{triggerId}` |
| `PATCH` | `/v1/projects/{project}/locations/{location}/triggers/{triggerId}` |
| `DELETE` | `/v1/projects/{project}/locations/{location}/triggers/{triggerId}` |
| `GET` | `/v1/projects/{project}/locations/{location}/channels` |
| `GET` | `/v1/projects/{project}/locations/{location}/channels/{channelId}` |
| `GET` | `/v1/projects/{project}/locations/{location}/providers` |
| `GET` | `/v1/projects/{project}/locations/{location}/providers/{providerId}` |

Create/update/delete return long-running operations that resolve immediately in the emulator.

## Quick Start

```bash
curl -X POST \
  "http://localhost:4588/v1/projects/floci-local/locations/us-central1/triggers?triggerId=my-trigger" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN" \
  -d '{
    "eventFilters": [
      { "attribute": "type", "value": "google.cloud.storage.object.v1.finalized" },
      { "attribute": "bucket", "value": "my-bucket" }
    ],
    "destination": {
      "httpEndpoint": { "uri": "http://example.com/endpoint" }
    }
  }'

curl -H "Authorization: Bearer $FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN" \
  "http://localhost:4588/v1/projects/floci-local/locations/us-central1/triggers"
```

## Supported Operations

- Trigger create / get / list / update / delete
- Channel get / list (stub list surface)
- Provider get / list (stub list surface)

## Limitations

- Channel and provider mutations are not fully modeled
- Trigger IAM policy RPCs may be mapped for CTF but are not a full Eventarc IAM control plane
- Real multi-region fan-out and managed transport semantics are out of scope

## CTF fork

When IAM enforcement is enabled (`floci-gcp.services.iam.enforcement-enabled`):

- REST Eventarc calls require a registered Bearer token and a matching project allow-policy binding.
- `IamPermissionMapper` maps paths to:
  - `eventarc.triggers.create` / `get` / `list` / `update` / `delete`
  - `eventarc.triggers.getIamPolicy` / `setIamPolicy` (mapped custom methods)
  - `eventarc.channels.get` / `list`
  - `eventarc.providers.get` / `list`
- `roles/eventarc.viewer` grants get and list on triggers, channels, and providers.
- `roles/eventarc.admin` grants the Eventarc permissions used by the CTF mapper (including create/update/delete).
- Operator root (`FLOCI_GCP_AUTH_ROOT_SERVICE_ACCOUNT` / `FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN`)
  bypasses IAM evaluation.

Regression: `EventarcIamEnforcementIntegrationTest`.
