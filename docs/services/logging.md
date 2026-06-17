# Cloud Logging

floci-gcp emulates Google Cloud Logging over gRPC and REST using the real
`google.logging.v2.LoggingServiceV2` protocol. The primary use case is **asserting what the app
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

## Not Yet Supported

`TailLogEntries` (streaming), `protoPayload`/`httpRequest`/`operation`/`sourceLocation`/`split`
fields, monitored-resource-descriptor catalog, log-based metrics (`MetricsServiceV2`), sinks and
buckets (`ConfigServiceV2`), advanced filter operators, and retention/dedup semantics.
