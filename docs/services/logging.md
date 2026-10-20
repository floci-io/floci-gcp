# Cloud Logging

floci-gcp emulates Google Cloud Logging over gRPC and REST using the real
`google.logging.v2.LoggingServiceV2` and sink-management methods of
`google.logging.v2.ConfigServiceV2`. The primary use case is **asserting what the app
under test logged**: write structured entries, then `ListLogEntries` back with a filter and verify
the payload, severity, and resource.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_LOGGING_ENABLED` | `true` | Enable/disable Cloud Logging |

## Endpoint

Cloud Logging has **no `*_EMULATOR_HOST` convention**. Point the client at floci-gcp by overriding
the API endpoint / transport channel and disabling credentials:

- **gRPC** (Java/Python/Go/Node): build the v2 client with a plaintext channel to `localhost:4588`
  and anonymous/no credentials (see Quick Start).
- **REST**: `POST http://localhost:4588/v2/entries:write` and `:list`.

## Quick Start

=== "Java"

    ```java
    LoggingClient client = LoggingClient.create(
        LoggingSettings.newBuilder()
            .setTransportChannelProvider(
                InstantiatingGrpcChannelProvider.newBuilder()
                    .setEndpoint("localhost:4588")
                    .setChannelConfigurator(b -> b.usePlaintext())
                    .build())
            .setCredentialsProvider(NoCredentialsProvider.create())
            .build());

    String logName = "projects/floci-local/logs/my-log";
    client.writeLogEntries(WriteLogEntriesRequest.newBuilder()
        .addEntries(LogEntry.newBuilder()
            .setLogName(logName)
            .setResource(MonitoredResource.newBuilder().setType("global"))
            .setSeverity(LogSeverity.ERROR)
            .setTextPayload("something failed")
            .build())
        .build());

    for (LogEntry entry : client.listLogEntries(ListLogEntriesRequest.newBuilder()
            .addResourceNames("projects/floci-local")
            .setFilter("logName=\"" + logName + "\" AND severity>=WARNING")
            .build()).iterateAll()) {
        System.out.println(entry.getTextPayload());
    }
    ```

=== "Python"

    ```python
    import grpc
    from google.cloud import logging_v2
    from google.cloud.logging_v2.services.logging_service_v2.transports.grpc import (
        LoggingServiceV2GrpcTransport,
    )

    transport = LoggingServiceV2GrpcTransport(channel=grpc.insecure_channel("localhost:4588"))
    client = logging_v2.services.logging_service_v2.LoggingServiceV2Client(transport=transport)

    log_name = "projects/floci-local/logs/my-log"
    client.write_log_entries(request={"entries": [{
        "log_name": log_name,
        "resource": {"type": "global"},
        "severity": "ERROR",
        "text_payload": "something failed",
    }]})

    for entry in client.list_log_entries(request={
        "resource_names": ["projects/floci-local"],
        "filter": f'logName="{log_name}" AND severity>=WARNING',
    }):
        print(entry.text_payload)
    ```

=== "Node.js"

    ```javascript
    import { v2 } from "@google-cloud/logging";
    import * as grpc from "@grpc/grpc-js";

    const client = new v2.LoggingServiceV2Client({
        servicePath: "localhost",
        port: 4588,
        sslCreds: grpc.credentials.createInsecure(),
    });

    const logName = "projects/floci-local/logs/my-log";
    await client.writeLogEntries({ entries: [{
        logName,
        resource: { type: "global" },
        severity: "ERROR",
        textPayload: "something failed",
    }]});

    const [entries] = await client.listLogEntries({
        resourceNames: ["projects/floci-local"],
        filter: `logName="${logName}" AND severity>=WARNING`,
    });
    entries.forEach((e) => console.log(e.textPayload));
    ```

## Filter subset

`ListLogEntries` supports a practical subset of the Cloud Logging filter language, combined with
`AND`:

| Field | Operators | Example |
|---|---|---|
| `logName` | `=` | `logName="projects/p/logs/app"` |
| `severity` | `=` `>` `>=` `<` `<=` | `severity>=WARNING` |
| `resource.type` | `=` | `resource.type="gce_instance"` |
| `timestamp` | `=` `>` `>=` `<` `<=` | `timestamp>="2026-01-01T00:00:00Z"` |
| `labels.<key>` | `=` | `labels.env="prod"` |

An empty filter matches all entries in the requested `resourceNames`. `OR`/`NOT`, functions,
regex, and nested parentheses are not supported; unrecognized clauses are ignored.

## Entry semantics

- Request-level `logName`, `resource`, and `labels` are applied to entries that omit them (entry
  labels win over request defaults).
- Missing `insertId` and `timestamp` are filled in; `receiveTimestamp` is always set to now.
- Entries are returned ordered by `timestamp` (ascending by default; `order_by: "timestamp desc"`
  reverses).
- `DeleteLog` removes all entries for a log name (`NOT_FOUND` if the log has no entries).

## Supported Operations

- `WriteLogEntries`
- `ListLogEntries`
- `ListLogs`
- `DeleteLog`
- `CreateSink`, `GetSink`, `ListSinks`, `UpdateSink`, `DeleteSink` over gRPC and REST JSON

## Sink configuration

Sink management accepts `projects/{id}`, `organizations/{id}`, `folders/{id}`, and
`billingAccounts/{id}` parents. Each parent has its own namespace. Parent names are validated
syntactically; an organization, folder, or billing account does not have to be provisioned in a
separate resource catalog.

| Operation | REST method and path |
|---|---|
| Create | `POST /v2/{parent}/sinks` |
| Get | `GET /v2/{parent}/sinks/{sinkId}` |
| List | `GET /v2/{parent}/sinks` |
| Update | `PUT` or `PATCH /v2/{parent}/sinks/{sinkId}` |
| Delete | `DELETE /v2/{parent}/sinks/{sinkId}` |

The `LogSink.name` field is the short sink identifier, such as `workspace-events`. Get, update,
and delete requests identify the sink with the full path, such as
`organizations/123456789/sinks/workspace-events`. List supports `pageSize` and `pageToken`.
Non-positive page sizes are ignored, including when continuing from a page token.

The stored configuration includes the destination, opaque filter text, description, disabled flag,
`includeChildren`, exclusions, and BigQuery options. Destination names can reference Cloud Storage,
Pub/Sub, or BigQuery. Logging project and log-bucket destinations are unsupported. Destination
existence and writer access are checked at delivery time, not when configuring a sink. Filter text
is retained verbatim, including expressions outside the routing subset below.

Updates accept `destination`, `filter`, `description`, `disabled`, `include_children`, `exclusions`,
and `bigquery_options` in the gRPC field mask, including valid nested BigQuery-option paths. REST
uses the corresponding lowerCamelCase mask names, such as `includeChildren`. Omitting the mask
uses the API's compatibility default: `destination,filter,includeChildren`. Fields outside the mask
are preserved, and masked scalar fields can be cleared. Invalid, immutable, and output-only mask
paths fail with `INVALID_ARGUMENT`; a failed update leaves the stored sink unchanged.

Writer identities are stable service-account principals. New unique writers are shared by sinks
under the same parent, following Cloud Logging's resource-shared service-agent model. Project
writers use an emulated project number; folder writers use the `service-folder-{id}` form observed
against live GCP. Organization and billing-account allocation is deterministic emulator behavior,
not a reproduction of Google's account provisioning. Existing persisted writer identities remain
unchanged on reads and ordinary updates. New unique identities are computed directly from the
parent. Deleting a sink does not revoke its writer's IAM grants.

Project sinks use the legacy `serviceAccount:cloud-logs@system.gserviceaccount.com` unless
`uniqueWriterIdentity=true` is supplied. A legacy identity can be upgraded, but a unique project
identity cannot be downgraded; updating it requires `uniqueWriterIdentity=true`. Non-project sinks
always use a unique writer regardless of this flag. Live Cloud Logging v2 folder-sink checks on
2026-10-20 UTC confirmed creation with either flag and updates with `false`, `true`, then `false`
retain the identity over REST and gRPC.

Writers are used directly by the internal IAM evaluator for destination writes. They do not mint
OAuth tokens, inherit the ingestion caller's credentials, or call SignBlob. Sink creation does not
create a user-managed IAM service account or signing key. `customWriterIdentity` is unsupported.
Legacy project identities require Pub/Sub and BigQuery destinations in the same project as the
sink. Legacy Cloud Storage delivery also requires a bucket owned by the sink's project; that
check runs inside the destination policy locks at delivery time.

Sink state uses `cloudlogging-sinks.json` through `StorageFactory` and the configured global storage
mode. Persistent, hybrid, and WAL storage retain configuration, writer identity, and deletion across
restarts. Memory storage retains state only for the process lifetime.

## GCS export

After accepting a non-dry-run `WriteLogEntries` request, both REST and gRPC ingestion select
active Cloud Storage sinks and route matching entries. Request defaults, timestamps, insert IDs,
and labels are applied before routing. Enabled exclusions remove matching entries. Disabled sinks
and disabled exclusions are respected. Deleting a log does not delete objects already exported.
Pub/Sub and BigQuery destinations can be configured but do not receive deliveries.

Each local write produces immutable JSON-array batches of protobuf-JSON `LogEntry` objects,
grouped by log ID and UTC timestamp hour. URL-encoded log IDs are decoded in object paths:
`{logId}/YYYY/MM/DD/HH:00:00_HH:59:59_S{shard}.json`. Entries received in a different hour use
`_A{shard}:{unixTime}.json`. Shards use random numeric identifiers and a create-only generation
precondition, so batches never replace an existing object. Content type is `application/json`;
internal storage fields are excluded.

This emulator delivers promptly instead of waiting for GCP's hourly batches and initial 2-3 hour
latency. Failed deliveries are logged and do not fail accepted Logging writes or block other
sinks. Outage buffering, automatic retries, export metrics, production shard allocation, and
exactly-once delivery are not implemented. A failed batch is not replayed automatically after
permissions are granted; later writes can export normally.

### Routing filters and hierarchy

Routing uses a strict subset independent of the lenient `ListLogEntries` parser:

- `AND`, implicit conjunction (including newlines), `OR`, `NOT`, and parentheses. As in Logging,
  `OR` binds more tightly than `AND`.
- `logName`, `severity`, `resource.type`, `resource.labels.<key>`, `labels.<key>`, `textPayload`,
  `jsonPayload.<path>`, `timestamp`, `receiveTimestamp`, `insertId`, `trace`, and `spanId`.
- Equality/inequality and ordering comparisons, string containment (`:`), and field presence
  (`:*`). JSON numeric fields compare numerically; incompatible object/scalar comparisons and failed
  scalar conversions do not match. Strings use Unicode NFKC case folding for equality, ordering,
  and containment. Containment against numeric, Boolean, and object values does not match.
  Timestamp operands accept RFC 3339 timestamps or ISO 8601 dates (`YYYY-MM-DD`); dates use midnight UTC.
  Quoted label keys and JSON path components are supported, as are JSON arrays. JSON presence
  distinguishes explicit nulls from missing keys. Missing scalar strings use `LogEntry` defaults;
  missing map/JSON fields fail comparisons. Severity presence (`severity:*`) is unsupported because
  the ingestion model does not preserve explicit default-enum presence.

Unsupported expressions, functions, regular expressions, global searches, and unavailable payload
fields stop delivery for the affected sink and produce a diagnostic. This also applies to enabled
exclusions: an unsupported exclusion cannot accidentally allow the entry to escape. Configuration
CRUD still retains these expressions for future support.

A sink receives logs owned by its parent. Project ID/number aliases resolve through the project
registry. For folder and organization sinks, `includeChildren` additionally routes projects with
the matching direct parent recorded by Resource Manager. Billing account relationships do not
establish Logging ancestry.
Nested folder ancestry is not modeled; unrelated or unknown hierarchies never match implicitly.

### Destination IAM

With `iam.authorization-mode=enforce`, grant the returned `writerIdentity`
`roles/storage.objectCreator` on the destination bucket or its owning project. Export checks
`storage.objects.create` against that principal, including supported IAM Conditions and project
policy inheritance. A grant to the logging caller or source project does not grant access to a
bucket owned by another project. Fresh shards need no delete permission. With enforcement disabled,
export follows the emulator's usual IAM bypass.

Sink configuration, selection, and delivery share one lock. Delete, disable, filter, exclusion,
and destination updates wait for any in-flight local delivery; subsequent writes see the changed
configuration. Destination policy locks are held through authorization and object publication in
the existing order: sink lock, IAM policy locks, bucket lock, object lock. A policy revocation is
ordered before or after the publication, never between its permission check and mutation.

Sink management caller authorization remains unsupported and is tracked separately in #404.

The wire contracts follow the [ConfigServiceV2 proto](https://github.com/googleapis/googleapis/blob/master/google/logging/v2/logging_config.proto),
the [Logging v2 REST sink methods](https://cloud.google.com/logging/docs/reference/v2/rest/v2/organizations.sinks),
and the [Logging v2 discovery document](https://logging.googleapis.com/$discovery/rest?version=v2).
Export semantics follow [Cloud Storage routing](https://docs.cloud.google.com/logging/docs/export/storage),
[shared writer identities and destination permissions](https://docs.cloud.google.com/logging/docs/export/configure_export_v2),
and the [Logging query language](https://docs.cloud.google.com/logging/docs/view/logging-query-language).
Live verification covered folder identity behavior and date-only timestamp comparisons at midnight UTC.
Export file bytes and exact validation messages were not verified live.

## Not Yet Supported

`TailLogEntries` (streaming), `protoPayload`/`httpRequest`/`operation`/`sourceLocation`/`split`
fields, monitored-resource-descriptor catalog, log-based metrics (`MetricsServiceV2`), non-GCS sink export,
log buckets and other `ConfigServiceV2` methods, advanced filter operators, and retention/dedup
semantics.
