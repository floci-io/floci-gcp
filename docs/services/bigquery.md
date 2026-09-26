# BigQuery

floci-gcp emulates the BigQuery v2 REST API (the surface the `google-cloud-bigquery` SDKs and
the Discovery document define): datasets, tables with schemas, `tabledata.insertAll`,
`tabledata.list`, and query jobs that run **GoogleSQL on an embedded DuckDB engine**, plus the
**Storage Read and Write APIs** over gRPC on the same port.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_BIGQUERY_ENABLED` | `true` | Enable/disable BigQuery |
| `FLOCI_GCP_SERVICES_BIGQUERY_MOCK` | `false` | When `true`, queries run on a small built-in SQL subset and no Docker container is started |
| `FLOCI_GCP_SERVICES_BIGQUERY_DUCK_DEFAULT_IMAGE` | `floci/floci-duck:latest` | Image of the SQL engine sidecar |
| `FLOCI_GCP_SERVICES_BIGQUERY_DUCK_URL` | _(none)_ | Use an already running floci-duck instead of starting one |
| `FLOCI_GCP_SERVICES_BIGQUERY_DUCK_CALLBACK_URL` | _(derived)_ | Base URL the sidecar reads staged rows from. Only needed when `DUCK_URL` points somewhere the resolved docker host cannot reach, such as another machine |
| `FLOCI_GCP_SERVICES_BIGQUERY_UPLOAD_SESSION_IDLE_TIMEOUT_SECONDS` | `604800` | Idle time before an unfinished resumable load-job upload is dropped with its buffered bytes. Lower it on long-lived instances |
| `FLOCI_GCP_SERVICES_BIGQUERY_UPLOAD_SESSION_SWEEP_INTERVAL_SECONDS` | `3600` | Interval between sweeps for expired upload sessions; `0` disables the sweeper |

The SQL engine is the [floci-duck](https://github.com/floci-io/floci-duck) sidecar, the same one
floci (AWS) uses for Athena. floci-gcp starts it through the host Docker daemon on the first
query and stops it on shutdown. It joins `FLOCI_GCP_SERVICES_DOCKER_NETWORK` when that is set,
and it reads table rows back from floci-gcp over HTTP, so the sidecar must be able to reach the
emulator (the same requirement as the Cloud Run, Cloud SQL and GKE sidecars).

## Endpoint

BigQuery has **no `*_EMULATOR_HOST` convention in the Java SDK**: point the client at floci-gcp
with `setHost` and disable credentials:

```java
BigQuery bigquery = BigQueryOptions.newBuilder()
    .setHost("http://localhost:4588")
    .setProjectId("floci-local")
    .setCredentials(NoCredentials.getInstance())
    .build().getService();
```

Python:

```python
from google.api_core.client_options import ClientOptions
from google.auth.credentials import AnonymousCredentials
from google.cloud import bigquery

client = bigquery.Client(project="floci-local", credentials=AnonymousCredentials(),
                         client_options=ClientOptions(api_endpoint="http://localhost:4588"))
```

REST paths live under `/bigquery/v2/projects/{project}/...`.

## Scope

- **Datasets**: insert, get, list, patch/update, delete (`deleteContents` honored; deleting a
  non-empty dataset without it returns 400 `resourceInUse`). Duplicate create returns 409 `duplicate`.
- **Tables**: insert, get, list, patch/update, delete. Schemas accept standard or legacy type
  names and are normalized to legacy names (`INT64`→`INTEGER`, `FLOAT64`→`FLOAT`, `BOOL`→`BOOLEAN`,
  `STRUCT`→`RECORD`) with `NULLABLE` as the default mode, matching SDK round-trips.
- **tabledata.insertAll**: schema-validated per row: HTTP 200 always, failures reported via
  `insertErrors` (unknown fields honor `ignoreUnknownValues`; `skipInvalidRows=false` inserts
  nothing and marks valid rows `stopped`; missing `REQUIRED` fields and uncoercible values are
  rejected with reason `invalid`).
- **tabledata.list**: `{f:[{v:"..."}]}` row encoding (scalars as strings, `REPEATED` as arrays of
  `{v}`, `RECORD` as nested `{f:[...]}`), `maxResults`/`pageToken`/`startIndex` paging.
  `maxResults=0` returns zero rows (the SDK's `Job.waitFor()` contract); a `pageToken` wins over
  `startIndex` (the SDK sends both when auto-paging).
- **Jobs / queries**: `jobs.query`, `jobs.insert` (QUERY and LOAD), `jobs.get`, `jobs.list`,
  `jobs.cancel`, `jobs.delete`, `getQueryResults`. Query results are materialized into a hidden
  anonymous table (`_floci_anon.anon_<jobId>`) referenced as the job's
  `configuration.query.destinationTable`, which is how the SDK's `Job.getQueryResults()` reads
  rows.
- **Query parameters**: `parameterMode` `NAMED` (`@name`) and `POSITIONAL` (`?`), with scalar,
  `ARRAY` and `STRUCT` parameter types.
- **Dry runs**: `dryRun` on `jobs.query` or `configuration.dryRun` on `jobs.insert` validates the
  query and returns its result schema (`statistics.query.schema` on the job) and
  `totalBytesProcessed`, without running it or persisting a job. An invalid query in a dry run is
  an HTTP 400. DML and DDL dry runs resolve every table the statement reads or writes, and DML is
  bound against those tables, so a dry run fails wherever the real run would.
- **Destination tables**: `jobs.insert` honors `configuration.query.destinationTable` with
  `createDisposition` (`CREATE_IF_NEEDED` default, `CREATE_NEVER`) and `writeDisposition`
  (`WRITE_EMPTY` default, `WRITE_TRUNCATE`, `WRITE_TRUNCATE_DATA`, `WRITE_APPEND`). Failures are
  reported in the job status (`duplicate`, `notFound`); any other disposition value is an
  HTTP 400 (`invalid`).
- **Views**: logical and materialized views, created with `tables.insert` (`view.query`) or
  DDL. The view query is validated and its schema derived on creation; views are expanded at
  query time, including views over views. A materialized view is evaluated on read, like a
  logical view.
- **Timestamp output**: `formatOptions.useInt64Timestamp` and
  `formatOptions.timestampOutputFormat` (`FLOAT64`, `INT64`, `ISO8601_STRING`) are honored on
  `jobs.query`, `getQueryResults` and `tabledata.list`. The default is epoch seconds, which is
  what the SDKs parse.

## Table and dataset metadata

Every client-writable Table and Dataset property of the BigQuery v2 API is stored and returned
as sent, so SDK and Terraform round-trips keep partitioning, clustering, expiration, collation,
rounding mode, encryption configuration, constraints, resource tags and view definitions.
`PATCH` merges the fields in the request (an explicit `null` clears one); `PUT` replaces them all,
except `linkedDatasetSource`: the reference says it "cannot be updated once it is set", so an
attempt to change it on either verb is ignored rather than rejected. With `updateMode=UPDATE_ACL`
neither verb touches these fields at all.

Server-side behavior driven by these fields:

- New tables inherit the dataset's `defaultTableExpirationMs` as `expirationTime`, and new
  time-partitioned tables inherit `defaultPartitionExpirationMs` as
  `timePartitioning.expirationMs` (and then no table expiration). An explicit value on the table
  wins. `defaultTableExpirationMs: 0` in a dataset `PATCH` clears the default.
- A table past its `expirationTime` is deleted when it is next read or listed.
- Output fields are filled in: dataset `type` (`LINKED` with a `linkedDatasetSource`, `EXTERNAL`
  with an `externalDatasetReference`, otherwise `DEFAULT`), `location` (`US` when not given) and
  `maxTimeTravelHours` (`168` when not set); table `location` (the dataset's),
  `numLongTermBytes` and `selfLink`. Tables created with `view`, `materializedView` or
  `externalDataConfiguration` get the matching `type`.
- Validation: `defaultTableExpirationMs` of at least one hour, `maxTimeTravelHours` from 48 to
  168, an `expirationTime` that parses as an int64, `timePartitioning.type` one of
  `DAY`/`HOUR`/`MONTH`/`YEAR` with a positive `expirationMs` and a `field` that exists in the
  schema, and not both time and range partitioning. Violations return 400 with reason `invalid`,
  and a rejected update leaves the stored resource unchanged.

Partitioning and clustering are metadata only: queries do not prune partitions and
`requirePartitionFilter` is not enforced.

## GoogleSQL support

Queries are translated to DuckDB SQL and executed on the sidecar, so most of GoogleSQL's
`SELECT` surface works:

- `JOIN` (all kinds), `GROUP BY`, `HAVING`, `ORDER BY`, `LIMIT`/`OFFSET`, `DISTINCT`,
  `UNION`/`INTERSECT`/`EXCEPT` (`ALL` and `DISTINCT`), subqueries, `WITH` (CTEs), window
  functions, `QUALIFY`, `UNNEST` (including `FROM t, t.array_column AS x` and `x IN UNNEST(...)`),
  `SELECT * EXCEPT (...)` and `SELECT * REPLACE (...)`.
- GoogleSQL literals: double-quoted and triple-quoted strings, raw strings (`r'...'`), bytes
  literals, `TIMESTAMP`/`DATETIME`/`DATE`/`TIME`/`NUMERIC`/`JSON` typed literals.
- `CAST`/`SAFE_CAST` with GoogleSQL type names, including `ARRAY<...>` and `STRUCT<...>`.
- Functions rewritten to their DuckDB equivalents: `SAFE_DIVIDE`, `IEEE_DIVIDE`, `DIV`, `IF`,
  `COUNTIF`, `LOGICAL_AND`/`LOGICAL_OR`, `ARRAY_LENGTH`, `ARRAY_REVERSE`, `GENERATE_ARRAY`,
  `SPLIT`, `FORMAT`, `TO_JSON_STRING`, `JSON_VALUE`/`JSON_EXTRACT_SCALAR`,
  `JSON_QUERY`/`JSON_EXTRACT`, `REGEXP_CONTAINS`, `REGEXP_EXTRACT`, `REGEXP_REPLACE`,
  `CURRENT_TIMESTAMP`/`CURRENT_DATE`/`CURRENT_DATETIME`, `UNIX_SECONDS`/`UNIX_MILLIS`/`UNIX_MICROS`/`UNIX_DATE`,
  `TIMESTAMP_SECONDS`/`TIMESTAMP_MILLIS`/`TIMESTAMP_MICROS`, `TIMESTAMP_ADD`/`TIMESTAMP_SUB`,
  `DATE_ADD`/`DATE_SUB`, `*_DIFF`, `*_TRUNC`, `FORMAT_TIMESTAMP`/`FORMAT_DATE`, `PARSE_TIMESTAMP`/`PARSE_DATE`,
  `DATE(...)`/`DATETIME(...)`/`TIMESTAMP(...)`, `EXTRACT` (including `DAYOFWEEK`, 1 = Sunday) and
  `STRUCT(... AS name)`. Functions with the same name and meaning in both dialects (`COALESCE`,
  `IFNULL`, `CONCAT`, `LOWER`, `STRING_AGG`, `ARRAY_AGG`, `ROUND`, and many more) pass through.
- Unaliased expressions in the select list are named `f0_`, `f1_`, ... like BigQuery names them.
- Result schemas carry real types: `INTEGER`, `FLOAT`, `NUMERIC`, `BOOLEAN`, `STRING`, `BYTES`,
  `DATE`, `TIME`, `DATETIME`, `TIMESTAMP`, `JSON`, `RECORD` (with nested fields) and `REPEATED`
  arrays.

## INFORMATION_SCHEMA

Queries can read these views, generated from the emulator's dataset and table metadata, with the
columns BigQuery documents:

| View | Qualifiers |
|---|---|
| `SCHEMATA` | `[project.]INFORMATION_SCHEMA.SCHEMATA` (US region), ``[project.]`region-x`.INFORMATION_SCHEMA.SCHEMATA`` |
| `TABLES`, `COLUMNS`, `COLUMN_FIELD_PATHS`, `TABLE_OPTIONS`, `VIEWS` | `[project.]dataset.INFORMATION_SCHEMA.VIEW` or ``[project.]`region-x`.INFORMATION_SCHEMA.VIEW`` |

- A region qualifier covers every dataset whose location matches (`region-us` matches `US`); a
  dataset qualifier for a missing dataset is a 404. Without a qualifier, the request's
  `defaultDataset` is used. View names are case-sensitive, as in BigQuery.
- `data_type` uses GoogleSQL names (`INT64`, `ARRAY<STRING>`, `STRUCT<name STRING, ...>`);
  `COLUMN_FIELD_PATHS` has one row per nested field path (`customer.name`).
- `ddl` is a `CREATE TABLE` / `CREATE VIEW` / `CREATE SCHEMA` statement in BigQuery's format.
- `TABLE_OPTIONS` reports `description`, `friendly_name` and `labels` as GoogleSQL literals
  (`"text"`, `[STRUCT("key", "value")]`).
- Columns describing features the emulator does not model (clones, snapshots, replicas, identity
  and generated columns, policy tags) are present and `NULL`, `NO` or empty. `COLUMNS` omits
  `async_generation_status` and `data_policies`; `COLUMN_FIELD_PATHS` omits `data_policies`.
- Other views (`JOBS`, `PARTITIONS`, `ROUTINES`, `TABLE_STORAGE`, ...) are not emulated.

## Storage Read API

`google.cloud.bigquery.storage.v1.BigQueryRead` is served over gRPC on the emulator port
(`CreateReadSession`, `ReadRows`, `SplitReadStream`), which is what `to_dataframe()` /
`to_arrow()` use in Python when `google-cloud-bigquery-storage` is installed, and what
`BigQueryReadClient`, Spark and Beam connectors call. Point the client at the emulator with a
plaintext channel, for example in Python:

```python
import grpc
from google.cloud import bigquery_storage_v1
from google.cloud.bigquery_storage_v1.services.big_query_read.transports import BigQueryReadGrpcTransport

storage = bigquery_storage_v1.BigQueryReadClient(
    transport=BigQueryReadGrpcTransport(channel=grpc.insecure_channel("localhost:4588")))
df = client.query("SELECT ...").to_dataframe(bqstorage_client=storage)
```

When `google-cloud-bigquery-storage` is installed, the Python client's `to_dataframe()` and
`to_arrow()` create a Storage Read client for Google's real endpoint unless you pass one:
use `bqstorage_client=storage` as above, or `create_bqstorage_client=False` to read over REST.

- **Formats**: `ARROW` (Arrow IPC schema and record batch messages, from the DuckDB engine) and
  `AVRO` (Avro schema plus binary-encoded row blocks of up to 1000 rows). Types follow BigQuery's
  mapping: `TIMESTAMP` is a UTC microsecond timestamp, `DATETIME` a timestamp without time zone,
  `NUMERIC` `decimal(38, 9)`, `DATE` `date32`; in Avro, nullable columns are unions with `null`,
  `NUMERIC` is `bytes`/`decimal(38, 9)`, `BIGNUMERIC` `decimal(77, 38)`, `TIMESTAMP`
  `timestamp-micros`.
- **Read options**: `selected_fields` (top-level fields, returned in table order) and
  `row_restriction` (a GoogleSQL filter, run through the SQL engine). The restriction must be a
  single predicate over the table being read: subqueries, `UNION`, other tables, `;` and comments
  are rejected with `INVALID_ARGUMENT`.
- A session snapshots the table when it is created and has **one stream**; `SplitReadStream`
  returns an empty response ("the original stream can no longer be split"). `ReadRows` honors
  `offset` (for `ARROW`, at record batch boundaries), sends the schema in its first response and
  reports `row_count` and progress. Sessions expire after 6 hours. Each session holds a snapshot
  in memory, so at most 256 are kept; creating one past that drops the oldest, whose stream then
  returns `NOT_FOUND`.
- Query results can be read from their anonymous destination table, as the SDKs do.
- Tables are read from the project in `read_session.table`.

Not supported: views, nested `selected_fields` (`struct.field`), `table_modifiers.snapshot_time`,
Arrow buffer compression, `sample_percentage`, and `ARROW` in mock mode (use `AVRO`).
`BIGNUMERIC` columns come back as `decimal(38, 9)` in Arrow.

## Storage Write API

`google.cloud.bigquery.storage.v1.BigQueryWrite` is served over gRPC on the emulator port
(`CreateWriteStream`, `AppendRows`, `GetWriteStream`, `FinalizeWriteStream`,
`BatchCommitWriteStreams`, `FlushRows`). This is what Java's `JsonStreamWriter` and
`StreamWriter`, Python's `AppendRowsStream`, Go's `managedwriter`, and the Beam and Dataflow
BigQuery sinks use. In Java, give the client a plaintext channel:

```java
BigQueryWriteClient client = BigQueryWriteClient.create(BigQueryWriteSettings.newBuilder()
        .setTransportChannelProvider(InstantiatingGrpcChannelProvider.newBuilder()
                .setEndpoint("localhost:4588")
                .setChannelConfigurator(builder -> builder.usePlaintext())
                .build())
        .setCredentialsProvider(NoCredentialsProvider.create())
        .build());
try (JsonStreamWriter writer = JsonStreamWriter.newBuilder(
        "projects/my-project/datasets/ds/tables/t", client).build()) {
    writer.append(new JSONArray().put(new JSONObject().put("name", "ada"))).get();
}
```

- **Stream types**:
  - The `_default` stream (`.../tables/{table}/streams/_default` or `.../tables/{table}/_default`)
    and `COMMITTED` streams make rows visible as soon as each append is acknowledged.
  - `PENDING` streams hold their rows until `FinalizeWriteStream` and then
    `BatchCommitWriteStreams`, which commits all listed streams atomically. It reports
    `stream_errors` (`STREAM_NOT_FOUND`, `INVALID_STREAM_TYPE`, `INVALID_STREAM_STATE`,
    `STREAM_ALREADY_COMMITTED`) and commits nothing when any stream fails.
  - `BUFFERED` streams make rows visible up to the offset given to `FlushRows`.
- **Offsets**: on application-created streams, an append with an `offset` must match the next
  row of the stream. A lower offset returns `ALREADY_EXISTS` (`OFFSET_ALREADY_EXISTS`), a higher
  one `OUT_OF_RANGE` (`OFFSET_OUT_OF_RANGE`), with the "expected offset N, received M" message the
  Java client parses. The `_default` stream rejects offsets.
- **Rows** are `proto_rows` with a `writer_schema` (a self-contained `DescriptorProto`) on the
  first request of a connection or when the destination changes. They are decoded with proto2
  semantics, so an unset field is a missing value (`NULL`).
  - Fields match columns by name, case-insensitively, or by the `column_name` field option that
    the client libraries use for column names that aren't valid proto identifiers.
  - Field types follow BigQuery's supported protocol buffer types. For example: `DATE` is
    days since the epoch or a string; `DATETIME` and `TIME` are `CivilTimeEncoder`-packed `int64`
    or a string; `TIMESTAMP` is epoch microseconds or `google.protobuf.Timestamp`; `NUMERIC` and
    `BIGNUMERIC` are `BigDecimalByteStringEncoder` bytes, a number or a string.
- **Errors**: rejected appends come back as in-stream error responses with a `StorageError`
  detail, and the connection stays open.
  - A proto field with no column is `SCHEMA_MISMATCH_EXTRA_FIELDS`.
  - An incompatible field type is `INVALID_ARGUMENT`.
  - A row that fails validation (for example, a missing `REQUIRED` field) rejects the whole
    request with `row_errors`.
  - Appending to a finalized stream is `STREAM_FINALIZED`.
- `GetWriteStream` returns the table schema with the `FULL` view, and the stream's location.

Not supported: `arrow_rows` (returns `UNIMPLEMENTED`; send `proto_rows`), column default values
(`DEFAULT_VALUE` missing values are `NULL`, since table schemas carry no default expressions),
`RANGE` columns, and partition decorators. Stream state, including rows not yet committed or
flushed, is kept in the configured storage mode, so with persistent storage a PENDING or BUFFERED
stream survives a restart. The emulator's gRPC request limit is 4 MiB, below
BigQuery's 10 MB `AppendRows` limit.

## Load jobs

`jobs.insert` with `configuration.load` loads data into a table, synchronously:

- **Sources**: `sourceUris` in floci's Cloud Storage (`gs://bucket/object`, with one `*`
  wildcard after the bucket), or a media upload to `/upload/bigquery/v2/projects/{project}/jobs`
  with `uploadType=multipart` or `uploadType=resumable` (what the SDKs' `load_table_from_file`,
  `load_table_from_dataframe` and `bigquery.writer(...)` use).
- **Formats**: `CSV` (the default), `NEWLINE_DELIMITED_JSON` and `PARQUET`.
- **Schema**: the job's `schema`, else the destination table's, else auto-detection
  (`autodetect: true`, CSV and JSON) or the Parquet file's own schema. Auto-detection infers
  `INTEGER`, `FLOAT`, `BOOLEAN`, `TIMESTAMP`, `DATE`, `TIME`, `STRING`, and `RECORD` / `REPEATED`
  for JSON; CSV header names have invalid characters replaced with underscores, and a CSV without
  a header gets generic names such as `string_field_0`. `skipLeadingRows` follows BigQuery's
  auto-detection rules (unset: detect a header; `0`: no header; `N`: skip `N-1` rows and detect a
  header in row `N`).
- **CSV options**: `skipLeadingRows`, `fieldDelimiter` (including `\t`), `quote`,
  `allowJaggedRows`, `nullMarker`, `encoding` (`UTF-8`, `ISO-8859-1`) and `maxBadRecords`. Without
  a `nullMarker`, an empty `STRING` value stays an empty string and empty values of other types
  are `NULL`.
- **JSON with a schema** is validated row by row exactly like `tabledata.insertAll`
  (`ignoreUnknownValues`, `maxBadRecords`, and a precise `badRecords` count); it needs no SQL
  engine, so it also works in mock mode.
- **Dispositions**: `writeDisposition` (`WRITE_APPEND` by default for loads, `WRITE_TRUNCATE`,
  `WRITE_TRUNCATE_DATA`, `WRITE_EMPTY`) and `createDisposition`.
- **Results**: `statistics.load` reports `inputFiles`, `inputFileBytes`, `outputRows`,
  `outputBytes` and `badRecords`. Data, schema and destination errors are reported in the DONE
  job's `status.errorResult` (`invalid`, `notFound`, `duplicate`).

Not supported: `AVRO`, `ORC` and `DATASTORE_BACKUP` sources, Hive partitioning,
`schemaUpdateOptions`, the `/resumable/upload/...` path variant, and non-GCS sources. For CSV,
`maxBadRecords` greater than zero skips unparsable rows without counting them.

## DML and DDL

| Statement | Notes |
|---|---|
| `INSERT [INTO] t [(cols)] VALUES ... / SELECT ...` | `numDmlAffectedRows`, `dmlStats.insertedRowCount` |
| `UPDATE t SET ... [FROM ...] WHERE ...` | `WHERE` is required, as in BigQuery |
| `DELETE [FROM] t WHERE ...` | `WHERE` is required |
| `MERGE [INTO] t USING s ON ... WHEN [NOT] MATCHED ...` | `dmlStats` splits inserted, updated and deleted rows |
| `TRUNCATE TABLE t` | `dmlStats.deletedRowCount` |
| `CREATE [OR REPLACE] TABLE [IF NOT EXISTS] t (col TYPE [NOT NULL] [OPTIONS(description=...)], ...)` | Column types keep their exact BigQuery type (`BIGNUMERIC`, `GEOGRAPHY`, `ARRAY<...>`, `STRUCT<...>`); `PARTITION BY`, `CLUSTER BY` and table `OPTIONS` are accepted and ignored |
| `CREATE [OR REPLACE] TABLE [IF NOT EXISTS] t AS SELECT ...` | Schema and rows come from the query |
| `CREATE [OR REPLACE] [MATERIALIZED] VIEW [IF NOT EXISTS] v AS SELECT ...` | |
| `DROP TABLE / VIEW / MATERIALIZED VIEW [IF EXISTS]` | Dropping a view with `DROP TABLE` (or the reverse) is rejected |
| `CREATE SCHEMA [IF NOT EXISTS] d`, `DROP SCHEMA [IF EXISTS] d [CASCADE \| RESTRICT]` | Datasets; a non-empty dataset needs `CASCADE`. `CASCADE`/`RESTRICT` are rejected on `DROP TABLE`/`VIEW` |

DDL jobs report `statistics.query.statementType`, `ddlOperationPerformed` (`CREATE`, `REPLACE`,
`SKIP`, `DROP`) and `ddlTargetTable` / `ddlTargetDataset`. DML and DDL results have no rows, so
`getQueryResults` returns `totalRows: "0"` and the SDKs return an empty result.

DML needs a floci-duck image that supports `followup_sql` (see
[floci-duck](https://github.com/floci-io/floci-duck)). Table, dataset and plain `CREATE TABLE` /
`DROP` / `TRUNCATE` statements run without the SQL engine, so they also work in mock mode.

Errors: invalid SQL via `jobs.query` returns HTTP 400 with reason `invalidQuery`; via
`jobs.insert` it returns an HTTP 200 `DONE` job carrying `status.errorResult` (and
`getQueryResults` on that job returns HTTP 400), matching real job semantics. Error messages
come from DuckDB, so their wording differs from BigQuery's.

## Mock mode

With `FLOCI_GCP_SERVICES_BIGQUERY_MOCK=true` no container is started and queries run on a small
built-in subset, useful where Docker is not available:

```
query     := SELECT selection FROM table_ref [WHERE predicate {AND predicate}] [LIMIT int] [;]
selection := * | COUNT ( * ) | column {, column}
table_ref := [project .] dataset . table | table        (backtick-quoted forms accepted)
predicate := column = literal
literal   := 'string' | "string" | integer | float | TRUE | FALSE
```

- An unqualified `table` requires the request's `defaultDataset`.
- Typed equality per column: `INTEGER`/`FLOAT`/`BOOLEAN`/`STRING`; comparing with a mismatched
  literal type → 400 `invalidQuery` ("No matching signature for operator =").
- `COUNT(*)` returns a single `INTEGER` column named `f0_` and respects `WHERE`.
- **Anything else** (JOIN, GROUP BY, ORDER BY, OR, `!=`, `<`, functions, aliases, subqueries,
  DML/DDL, `= NULL`), and any query with parameters, fails fast with 400 and reason
  `invalidQuery` naming the construct: never silent divergence.

## Type support

| Legacy type | Accepted `insertAll` values | Wire encoding |
|---|---|---|
| `STRING` | string, number, boolean | as-is |
| `INTEGER` (`INT64`) | integral number, decimal string | decimal string |
| `FLOAT` (`FLOAT64`) | number, decimal string | decimal string |
| `BOOLEAN` (`BOOL`) | boolean, `"true"`/`"false"` | `"true"`/`"false"` |
| `RECORD` (`STRUCT`) | object (validated against nested fields) | `{f:[...]}` |
| any + `REPEATED` mode | array of the base type | array of `{v}` |
| other (`TIMESTAMP`, `DATE`, ...) | stored/echoed textually | as-is |

## Deviations from real BigQuery

- Multi-statement scripts, temporary tables, `ALTER TABLE` / `ALTER VIEW`, `CREATE FUNCTION` /
  `PROCEDURE`, `EXPORT DATA` and `LOAD DATA` are not supported yet; they fail with `invalidQuery`.
- DML statements are applied atomically per statement by reading and rewriting the whole target
  table; there is no fine-grained DML or streaming-buffer interaction.
- Table-level `PARTITION BY`, `CLUSTER BY` and `OPTIONS` in DDL are not stored.
- Legacy SQL (`useLegacySql: true`) is rejected. A request that omits `useLegacySql` runs as
  GoogleSQL (the API default is legacy SQL, but every SDK sends `false`).
- `SELECT * FROM UNNEST(array)` expands `STRUCT` elements into columns; with an alias
  (`UNNEST(array) AS x`) the element is one column `x`.
- `UNNEST ... WITH OFFSET`, INFORMATION_SCHEMA views other than the six above, wildcard tables, `FOR SYSTEM_TIME AS OF`,
  `GEOGRAPHY` functions, BigQuery ML and remote functions are not emulated.
- `ARRAY_AGG(... IGNORE NULLS)`, `SAFE.`-prefixed functions and GoogleSQL `WEEK` boundaries
  (Sunday-based) follow DuckDB's behavior.
- `NUMERIC` and `BIGNUMERIC` are computed as `DECIMAL(38, 9)`.
- `TIMESTAMP` values nested inside `RECORD` or `REPEATED` columns must be stored as ISO-8601
  strings to be queryable; top-level `TIMESTAMP` columns accept epoch seconds too.
- `totalBytesProcessed` is an estimate from the size of the tables a query reads.
- No `insertId` de-duplication (real BigQuery is best-effort anyway).
- Jobs always complete synchronously (`jobComplete=true`, state `DONE`), with no PENDING/RUNNING phase.
- Cross-project table references are rejected.
- Anonymous result tables persist until `jobs.delete`; they are hidden from dataset listings.

## Quick smoke (curl)

```bash
B=http://localhost:4588/bigquery/v2/projects/demo
curl -sX POST $B/datasets -H 'Content-Type: application/json' \
  -d '{"datasetReference":{"datasetId":"ds1"}}'
curl -sX POST $B/datasets/ds1/tables -H 'Content-Type: application/json' \
  -d '{"tableReference":{"tableId":"t1"},"schema":{"fields":[{"name":"name","type":"STRING"},{"name":"age","type":"INT64"}]}}'
curl -sX POST $B/datasets/ds1/tables/t1/insertAll -H 'Content-Type: application/json' \
  -d '{"rows":[{"json":{"name":"ana","age":30}},{"json":{"name":"bo","age":41}}]}'
curl -sX POST $B/queries -H 'Content-Type: application/json' \
  -d '{"query":"SELECT name, age FROM ds1.t1 WHERE age > @min ORDER BY age DESC","useLegacySql":false,
       "queryParameters":[{"name":"min","parameterType":{"type":"INT64"},"parameterValue":{"value":"18"}}]}'
```
