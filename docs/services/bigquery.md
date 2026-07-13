# BigQuery (Phase 1)

floci-gcp emulates the BigQuery v2 REST API (the surface the `google-cloud-bigquery` SDK and
the Discovery document define) for **metadata, streaming inserts, and a limited SQL subset**:
datasets, tables with schemas, `tabledata.insertAll`, `tabledata.list`, and query jobs. A real
GoogleSQL engine (Phase 2) and the Storage Read/Write gRPC API (Phase 3) are out of scope.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_BIGQUERY_ENABLED` | `true` | Enable/disable BigQuery |

## Endpoint

BigQuery has **no `*_EMULATOR_HOST` convention in the Java SDK** — point the client at floci-gcp
with `setHost` and disable credentials:

```java
BigQuery bigquery = BigQueryOptions.newBuilder()
    .setHost("http://localhost:4588")
    .setProjectId("floci-local")
    .setCredentials(NoCredentials.getInstance())
    .build().getService();
```

REST paths live under `/bigquery/v2/projects/{project}/...`.

## Scope

- **Datasets**: insert, get, list, patch/update, delete (`deleteContents` honored; deleting a
  non-empty dataset without it → 400 `resourceInUse`). Duplicate create → 409 `duplicate`.
- **Tables**: insert, get, list, patch/update, delete. Schemas accept standard or legacy type
  names and are normalized to legacy names (`INT64`→`INTEGER`, `FLOAT64`→`FLOAT`, `BOOL`→`BOOLEAN`,
  `STRUCT`→`RECORD`) with `NULLABLE` as the default mode, matching SDK round-trips.
- **tabledata.insertAll**: schema-validated per row — HTTP 200 always, failures reported via
  `insertErrors` (unknown fields honor `ignoreUnknownValues`; `skipInvalidRows=false` inserts
  nothing and marks valid rows `stopped`; missing `REQUIRED` fields and uncoercible values are
  rejected with reason `invalid`).
- **tabledata.list**: `{f:[{v:"..."}]}` row encoding (scalars as strings, `REPEATED` as arrays of
  `{v}`, `RECORD` as nested `{f:[...]}`), `maxResults`/`pageToken`/`startIndex` paging.
  `maxResults=0` returns zero rows (the SDK's `Job.waitFor()` contract); a `pageToken` wins over
  `startIndex` (the SDK sends both when auto-paging).
- **Jobs / queries**: `jobs.query` (fast path, always answers `jobComplete=true` synchronously),
  `jobs.insert` (QUERY only), `jobs.get`, `jobs.list`, `jobs.cancel`, `jobs.delete`,
  `getQueryResults`. Query results are materialized into a hidden anonymous table
  (`_floci_anon.anon_<jobId>`) referenced as the job's `configuration.query.destinationTable`,
  which is how the SDK's `Job.getQueryResults()` reads rows.

## Supported SQL

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
  DML/DDL, `= NULL`) fails fast with 400 and reason `invalidQuery` naming the construct — never
  silent divergence.
- Invalid SQL via `jobs.query` → HTTP 400; via `jobs.insert` → HTTP 200 `DONE` job carrying
  `status.errorResult` (and `getQueryResults` on that job → HTTP 400), matching real job semantics.

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

- No `insertId` de-duplication (real BigQuery is best-effort anyway).
- Jobs always complete synchronously (`jobComplete=true`, state `DONE`) — no PENDING/RUNNING phase.
- `useLegacySql` is ignored; the SQL subset above is the only dialect.
- `WHERE` on `TIMESTAMP`/`RECORD`/`REPEATED` columns is not supported.
- Cross-project table references are rejected.
- Anonymous result tables persist until `jobs.delete`; they are hidden from dataset listings.
- `dryRun` is not special-cased; Storage Read/Write API (gRPC) is not implemented (Phase 3).

## Quick smoke (curl)

```bash
B=http://localhost:4588/bigquery/v2/projects/demo
curl -sX POST $B/datasets -H 'Content-Type: application/json' \
  -d '{"datasetReference":{"datasetId":"ds1"}}'
curl -sX POST $B/datasets/ds1/tables -H 'Content-Type: application/json' \
  -d '{"tableReference":{"tableId":"t1"},"schema":{"fields":[{"name":"name","type":"STRING"},{"name":"age","type":"INT64"}]}}'
curl -sX POST $B/datasets/ds1/tables/t1/insertAll -H 'Content-Type: application/json' \
  -d '{"rows":[{"json":{"name":"ana","age":30}}]}'
curl -sX POST $B/queries -H 'Content-Type: application/json' \
  -d '{"query":"SELECT name FROM ds1.t1 WHERE age = 30"}'
```

## CTF fork

When IAM enforcement is enabled (`floci-gcp.services.iam.enforcement-enabled`):

- REST BigQuery calls require a registered Bearer token and a matching project allow-policy binding.
- `IamPermissionMapper` maps BigQuery REST paths to `bigquery.datasets.*`, `bigquery.tables.*`
  (including `getData` / `updateData` for tabledata), and `bigquery.jobs.*`.
- `roles/bigquery.dataViewer` grants dataset/table read and `getData` only.
- `roles/bigquery.jobUser` grants `bigquery.jobs.create` only.
- `roles/bigquery.admin` grants the broad BigQuery permissions used by the CTF mapper.
- Operator root (`FLOCI_GCP_AUTH_ROOT_SERVICE_ACCOUNT` / `FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN`)
  bypasses IAM evaluation.

Regression: `BigQueryIamEnforcementIntegrationTest`.
