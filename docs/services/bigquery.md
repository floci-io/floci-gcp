# BigQuery

floci-gcp emulates the BigQuery v2 REST API (the surface the `google-cloud-bigquery` SDKs and
the Discovery document define): datasets, tables with schemas, `tabledata.insertAll`,
`tabledata.list`, and query jobs that run **GoogleSQL on an embedded DuckDB engine**. The
Storage Read/Write gRPC API is not implemented yet.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_BIGQUERY_ENABLED` | `true` | Enable/disable BigQuery |
| `FLOCI_GCP_SERVICES_BIGQUERY_MOCK` | `false` | When `true`, queries run on a small built-in SQL subset and no Docker container is started |
| `FLOCI_GCP_SERVICES_BIGQUERY_DUCK_DEFAULT_IMAGE` | `floci/floci-duck:latest` | Image of the SQL engine sidecar |
| `FLOCI_GCP_SERVICES_BIGQUERY_DUCK_URL` | _(none)_ | Use an already running floci-duck instead of starting one |
| `FLOCI_GCP_SERVICES_BIGQUERY_DUCK_CALLBACK_URL` | _(derived)_ | Base URL the sidecar reads staged rows from. Only needed when `DUCK_URL` points somewhere the resolved docker host cannot reach, such as another machine |

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
- **Jobs / queries**: `jobs.query`, `jobs.insert` (QUERY only), `jobs.get`, `jobs.list`,
  `jobs.cancel`, `jobs.delete`, `getQueryResults`. Query results are materialized into a hidden
  anonymous table (`_floci_anon.anon_<jobId>`) referenced as the job's
  `configuration.query.destinationTable`, which is how the SDK's `Job.getQueryResults()` reads
  rows.
- **Query parameters**: `parameterMode` `NAMED` (`@name`) and `POSITIONAL` (`?`), with scalar,
  `ARRAY` and `STRUCT` parameter types.
- **Dry runs**: `dryRun` on `jobs.query` or `configuration.dryRun` on `jobs.insert` validates the
  query and returns its result schema (`statistics.query.schema` on the job) and
  `totalBytesProcessed`, without running it or persisting a job. An invalid query in a dry run is
  an HTTP 400.
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

- DML (`INSERT`, `UPDATE`, `DELETE`, `MERGE`), DDL (`CREATE TABLE`, ...), multi-statement
  scripts, user `destinationTable` and write dispositions are not supported yet; they fail with
  `invalidQuery`.
- Legacy SQL (`useLegacySql: true`) is rejected. A request that omits `useLegacySql` runs as
  GoogleSQL (the API default is legacy SQL, but every SDK sends `false`).
- `UNNEST ... WITH OFFSET`, `INFORMATION_SCHEMA`, wildcard tables, `FOR SYSTEM_TIME AS OF`,
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
