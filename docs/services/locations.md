# Locations

Every regional GCP API also serves the `google.cloud.location.Locations` mixin
([`locations.proto`](https://github.com/googleapis/googleapis/blob/master/google/cloud/location/locations.proto)).
floci-gcp answers it from one bundled catalog of real GCP locations: 43 regions and their 130
zones, taken from [Regions and zones](https://cloud.google.com/compute/docs/regions-zones).
The same catalog drives [Compute Engine](compute.md) regions and zones, and the optional strict
location check described below.

## Endpoints

| Protocol | Method | Path / RPC |
|---|---|---|
| REST | `GET` | `/v1/projects/{project}/locations` |
| REST | `GET` | `/v1/projects/{project}/locations/{location}` |
| REST | `GET` | `/v2/projects/{project}/locations` |
| REST | `GET` | `/v2/projects/{project}/locations/{location}` |
| gRPC | `ListLocations`, `GetLocation` | `google.cloud.location.Locations` |

The v1 paths are the ones Cloud KMS, Cloud Scheduler, Eventarc, Managed Kafka, Secret Manager and
Cloud Run v1 use. The v2 paths are the ones Cloud Tasks and Cloud Functions v2 use. Cloud Functions
only defines list, but serving get on v2 as well does no harm.

List returns one entry per region and always includes the `locations` array, even when it is
empty. The Terraform `google_cloud_run_locations` data source needs that array to be present.

```json
{
  "locations": [
    {
      "name": "projects/my-project/locations/us-central1",
      "locationId": "us-central1",
      "displayName": "Council Bluffs",
      "labels": {"cloud.googleapis.com/region": "us-central1"}
    }
  ]
}
```

- `displayName` is the city from the "Location" column of the regions-and-zones table.
- `pageSize` and `pageToken` page through the list, and `nextPageToken` is set while more pages
  remain. `filter` is ignored.
- Get returns `NOT_FOUND` for an id that is not in the list.

### Service-specific metadata

On a single port, the path does not say which API is being called. For REST, floci-gcp reads the
first label of the `Host` header, so this only applies when a client reaches floci-gcp under the
real hostname (for example through the embedded DNS and TLS on port 443):

| Host | Extra behavior |
|---|---|
| `cloudkms.*` | Also lists `global`, `us`, `europe` and `asia`. Each entry has a `google.cloud.kms.v1.LocationMetadata` with `hsmAvailable` and `ekmAvailable` set to false, because the emulator keeps software keys only. |
| `cloudfunctions.*` | Each entry has a `google.cloud.functions.v2.LocationMetadata` with `environments: ["GEN_2"]`. |

The gRPC service is shared by every API on the port. The gRPC bridge does not expose the call
authority, so gRPC responses never carry service metadata and never list the KMS multi-regions.

## Strict location validation

By default floci-gcp accepts any location string, as it always has. Set
`FLOCI_GCP_LOCATIONS_STRICT=true` (`floci-gcp.locations.strict`) to reject unknown locations with
`INVALID_ARGUMENT` (`Invalid location: {location}`). Google does not document the exact error
text, so this wording is floci-gcp's own.

| API | Checked on | Accepted locations |
|---|---|---|
| Cloud Run v2 | service create, service list | catalog regions |
| Cloud Functions v2 | function create, function list | catalog regions |
| Cloud KMS | key ring create, key ring list | catalog regions, `global`, `us`, `europe`, `asia` |
| Cloud Tasks | queue create, queue list | catalog regions |
| Cloud Scheduler | job create, job list | catalog regions |
| Eventarc | trigger create, trigger list | catalog regions |
| Managed Kafka | cluster create, cluster list | catalog regions |
| GKE | cluster create, cluster list | catalog regions and zones |

The `-` list wildcard is never checked. Strict mode does not accept KMS dual-regional locations
such as `nam-eur-asia1` yet, because the bundled reference does not list them. Compute Engine
does not use this setting. It always answers `404` for a region or zone that is not configured.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_LOCATIONS_STRICT` | `false` | Reject unknown locations on regional create and list calls |
| `FLOCI_GCP_SERVICES_COMPUTE_REGIONS` | _(unset)_ | Optional Compute Engine region allow-list. Unset means the full catalog |

The Locations endpoints are always on. They are not tied to any service's `enabled` flag.
