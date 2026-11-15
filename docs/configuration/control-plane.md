# Control Plane Endpoints

floci-gcp serves a small set of emulator endpoints for test harnesses, health checks and the web
console. Every Floci emulator (AWS, Azure, GCP, OCI) serves the same set under the same `/_floci`
prefix, so one client works against all four. They are not Google APIs.

## Endpoints

| Endpoint | Answers |
|---|---|
| `GET /_floci/health` | `200` JSON: `status` (`UP`), `cloud` (`gcp`), `version`, and `services`, an object of every service as `running` or `available` |
| `GET /_floci/info` | `200` JSON: the emulator manifest (see below) |
| `GET /_floci/init` | `200` JSON: init hook state, `{"completed": {"boot", "start", "ready", "shutdown"}, "scripts": {...}}`. `completed.ready` is the readiness signal for hook-aware waits |
| `POST /_floci/state/reset` | `200` `{"status": "OK"}` after wiping all emulator state. `POST /_floci/state/nuke` is an alias |
| `GET /_floci/tls-cert` | `200` `application/x-pem-file` with the certificate to trust, or `404` JSON `{"tlsEnabled", "error", "message"}` when TLS is off or the certificate is not ready yet. See [TLS / HTTPS](advanced/tls.md) |

An unknown path under `/_floci` answers `404` JSON `{"error": "NotFound", "path": "<path>"}`. It never
reaches Cloud Storage, so a mistyped health check does not turn into a bucket lookup. The control
plane answers on every host, including a Cloud Run invocation host.

### Health

```bash
curl http://localhost:4588/_floci/health
```

```json
{
  "status": "UP",
  "cloud": "gcp",
  "services": {"pubsub": "running", "gcs": "running"},
  "version": "0.12.0"
}
```

The Docker image's `HEALTHCHECK` probes `/_floci/health`.

### Manifest

`GET /_floci/info` returns the Floci Console Contract v2 manifest, which a web console reads to
describe the emulator it runs against. A reader must ignore keys it does not know.

```json
{
  "manifest_version": 1,
  "console_contract": 2,
  "cloud": "gcp",
  "emulator": "floci-gcp",
  "version": "0.12.0",
  "port": 4588,
  "defaultProject": "floci-local",
  "endpoint": "http://localhost:4588",
  "health_url": "/_floci/health",
  "tls": {"enabled": false},
  "services": {"gcs": "enabled", "pubsub": "enabled"},
  "defaults": {"project_id": "floci-local"},
  "ui": {"enabled": false}
}
```

`services` lists the same services as health, as `enabled` or `disabled`. `port` and
`defaultProject` are the keys this endpoint returned before the manifest; they stay.

## Legacy paths

These paths answer exactly as before and are never removed:

| Path | Same as |
|---|---|
| `GET /health` | `GET /_floci/health` |
| `/_floci-gcp/health`, `/info`, `/init`, `/tls-cert`, `/state/reset`, `/state/nuke` | the same endpoint under `/_floci` |

An unknown path under `/_floci-gcp` also answers the JSON `404`.

The internal routes that floci-gcp's own sidecars call stay under `/_floci-gcp` only:
`/_floci-gcp/bigquery/*` (read by the DuckDB engine) and `/_floci-gcp/gke/token-webhook` (called
by the GKE cluster's API server). They are not part of the shared contract.
