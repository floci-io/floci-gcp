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
existence and writer access are not checked. Sink filter text is stored without evaluation, including filters outside the
`ListLogEntries` subset above.

Updates accept `destination`, `filter`, `description`, `disabled`, `include_children`, `exclusions`,
and `bigquery_options` in the gRPC field mask, including valid nested BigQuery-option paths. REST
uses the corresponding lowerCamelCase mask names, such as `includeChildren`. Omitting the mask
uses the API's compatibility default: `destination,filter,includeChildren`. Fields outside the mask
are preserved, and masked scalar fields can be cleared. Invalid, immutable, and output-only mask
paths fail with `INVALID_ARGUMENT`; a failed update leaves the stored sink unchanged.

Writer identities are emulator stubs. Organization, folder, and billing-account sinks receive a
generated service-account identity; project sinks use a legacy identity unless
`uniqueWriterIdentity=true` is supplied. The identity remains stable across reads, updates, and
restarts. A legacy project identity can be upgraded to a generated identity, but a generated project
identity cannot be downgraded. These strings do not provision IAM service accounts or credentials,
and do not reproduce Google's service-agent allocation. `customWriterIdentity` is unsupported.
Updating a project sink with a generated identity requires `uniqueWriterIdentity=true`.
Non-project sinks retain their generated identity regardless of this flag. Live Cloud Logging v2
folder-sink checks on 2026-10-20 UTC confirmed this behavior over REST and gRPC: creation with
either flag value and updates with `false`, `true`, then `false` all retained the writer identity.
Legacy project identities require Pub/Sub and BigQuery destinations in the
same project as the sink. Cloud Storage destinations do not encode their owning project; bucket
ownership is not checked.

Sink state uses `cloudlogging-sinks.json` through `StorageFactory` and the configured global storage
mode. Persistent, hybrid, and WAL storage retain configuration, writer identity, and deletion across
restarts. Memory storage retains state only for the process lifetime.

Sink management does not deliver logs to any destination. `WriteLogEntries` does not export to
Cloud Storage, Pub/Sub, BigQuery, or Logging buckets, and `includeChildren`, exclusions, and
BigQuery options have no routing effect. Cloud Logging IAM enforcement is also unsupported,
including when IAM enforcement is enabled for other services.

The wire contracts follow the [ConfigServiceV2 proto](https://github.com/googleapis/googleapis/blob/master/google/logging/v2/logging_config.proto),
the [Logging v2 REST sink methods](https://cloud.google.com/logging/docs/reference/v2/rest/v2/organizations.sinks),
and the [Logging v2 discovery document](https://logging.googleapis.com/$discovery/rest?version=v2).
Exact live-GCP validation messages and identity allocation have not been verified.

## Not Yet Supported

`TailLogEntries` (streaming), `protoPayload`/`httpRequest`/`operation`/`sourceLocation`/`split`
fields, monitored-resource-descriptor catalog, log-based metrics (`MetricsServiceV2`), sink export,
log buckets and other `ConfigServiceV2` methods, advanced filter operators, and retention/dedup
semantics.
