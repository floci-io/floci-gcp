# Cloud Monitoring

floci-gcp emulates Google Cloud Monitoring (the Metrics API) over gRPC and REST using the real
`google.monitoring.v3.MetricService` protocol. Define **metric descriptors**, write **time series**
data points, and read them back — useful for exercising custom-metric ingestion and queries without
a real Monitoring backend.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_MONITORING_ENABLED` | `true` | Enable/disable Cloud Monitoring |

## Endpoint

Cloud Monitoring has **no `*_EMULATOR_HOST` convention**. Point the client at floci-gcp by overriding
the API endpoint / transport channel and disabling credentials:

- **gRPC** (Java/Python/Go/Node): build the v3 `MetricServiceClient` with a plaintext channel to
  `localhost:4588` and anonymous/no credentials (see Quick Start).
- **REST**: `/v3/projects/{project}/...` (metric descriptors, monitored resource descriptors, time series).

## Scope

- Metric descriptors: `CreateMetricDescriptor` (upsert), `GetMetricDescriptor`, `ListMetricDescriptors`, `DeleteMetricDescriptor`.
- Monitored resource descriptors: `ListMonitoredResourceDescriptors`, `GetMonitoredResourceDescriptor`.
- Time series: `CreateTimeSeries` (write points) and `ListTimeSeries` (read back over a time interval,
  raw or aggregated).
- `TypedValue` point values (`bool`, `int64`, `double`, `string`, `distribution`) are preserved round-trip.
- Pagination (`pageSize` / `pageToken` / `nextPageToken`) on all list methods. `ListMetricDescriptors`
  clamps the page size to 10,000; `ListTimeSeries` clamps to 100,000, where under the `FULL` view the
  page size limits the total number of points (a series may span pages) and under `HEADERS` it limits
  the number of series.

## Aggregation

`ListTimeSeries` supports a subset of the real API's alignment/reduction:

- **Per-series aligners**: `ALIGN_NONE`, `ALIGN_SUM`, `ALIGN_MEAN`, `ALIGN_MIN`, `ALIGN_MAX`,
  `ALIGN_COUNT`, `ALIGN_DELTA`, `ALIGN_RATE`. Output kinds/types follow the proto:
  `ALIGN_MEAN`/`ALIGN_RATE` → `DOUBLE`, `ALIGN_COUNT` → `INT64`, `ALIGN_DELTA` → kind `DELTA`,
  `ALIGN_RATE` → kind `GAUGE`.
- **Cross-series reducers**: `REDUCE_NONE`, `REDUCE_SUM`, `REDUCE_MEAN`, `REDUCE_MIN`, `REDUCE_MAX`,
  `REDUCE_COUNT`, with `group_by_fields` (`resource.type`, `resource.label.<key>`, `metric.label.<key>`).
- `alignment_period` must be at least 60 seconds and is required whenever an aligner other than
  `ALIGN_NONE` is set; a reducer requires an aligner. Unsupported aligners/reducers are rejected with
  `INVALID_ARGUMENT` rather than silently ignored.
- Alignment buckets are anchored to the request interval's end time: each aligned point's `endTime`
  falls on `interval.endTime - k * alignment_period`.
- Over REST, pass `aggregation.alignmentPeriod` (e.g. `60s`), `aggregation.perSeriesAligner`,
  `aggregation.crossSeriesReducer` and repeated `aggregation.groupByFields` query parameters.

## Validation semantics

Write-path rules matching the documented API behavior:

- `CreateTimeSeries`: at most 200 `TimeSeries` per request; each series carries exactly one point;
  the point's `endTime` must be at most 25 hours in the past and 5 minutes in the future, and must be
  strictly newer than the most recent stored point of the same series (metric type + labels +
  monitored resource). `GAUGE` intervals are point-in-time (`startTime` absent or equal to `endTime`);
  `DELTA`/`CUMULATIVE` intervals require `startTime < endTime`.
- Writing to a nonexistent metric auto-creates its descriptor: the kind must be `GAUGE` (default) or
  `CUMULATIVE`, and the value type is inferred from the point (`BOOL`, `INT64`, `DOUBLE` or
  `DISTRIBUTION`).
- `CreateMetricDescriptor` is an upsert: re-creating an existing type updates it, but labels are
  unioned — existing label keys are never removed. `BOOL`/`STRING` value types are only valid with
  `GAUGE`. The metric type must be domain-prefixed (e.g. `custom.googleapis.com/...`).
- `DeleteMetricDescriptor` only accepts user-created metrics (`custom.googleapis.com/` or
  `external.googleapis.com/` prefixes).
- `ListTimeSeries` requires a filter naming a single metric type (`metric.type = "..."`) and an
  `interval.endTime`; the read interval is half-open `(startTime, endTime]`.

### Documented deviations from real GCP

- `CreateTimeSeries` is all-or-nothing: on any invalid series the whole request fails with
  `INVALID_ARGUMENT` and nothing is written (real GCP writes the valid subset and reports partial
  failures via `CreateTimeSeriesSummary` error details).
- Alignment emits points only for buckets that contain data — no interpolation of empty periods for
  `ALIGN_DELTA`/`ALIGN_RATE`.
- `DISTRIBUTION` and `STRING` values cannot be aggregated.
- A missing read `interval.startTime` is treated as unbounded (real GCP defaults it to the end time).
- A single `CreateTimeSeries` request may carry multiple chronologically ordered points for the same
  series across `TimeSeries` entries (real GCP rejects duplicate series identities per request).

## Quick Start

=== "Java"

    ```java
    MetricServiceClient client = MetricServiceClient.create(
        MetricServiceSettings.newBuilder()
            .setTransportChannelProvider(
                InstantiatingGrpcChannelProvider.newBuilder()
                    .setEndpoint("localhost:4588")
                    .setChannelConfigurator(b -> b.usePlaintext())
                    .build())
            .setCredentialsProvider(NoCredentialsProvider.create())
            .build());

    ProjectName project = ProjectName.of("floci-local");

    // Write a custom-metric data point
    TimeSeries series = TimeSeries.newBuilder()
        .setMetric(Metric.newBuilder().setType("custom.googleapis.com/my_metric").build())
        .addPoints(Point.newBuilder()
            .setInterval(TimeInterval.newBuilder().setEndTime(Timestamps.now()).build())
            .setValue(TypedValue.newBuilder().setDoubleValue(42.0).build())
            .build())
        .build();
    client.createTimeSeries(project, List.of(series));

    // Read it back
    client.listTimeSeries(project,
        "metric.type=\"custom.googleapis.com/my_metric\"",
        interval,
        ListTimeSeriesRequest.TimeSeriesView.FULL);
    ```

## Notes

- Custom metrics (`custom.googleapis.com/*`) are the primary use case; metric and time-series data is
  held in the configured storage backend, namespaced by project ID.
- `ListTimeSeries` reads back data over the requested time interval; combine with the metric/resource
  filter to scope results.

## CTF fork

When IAM enforcement is enabled (`floci-gcp.services.iam.enforcement-enabled`):

- REST Cloud Monitoring calls require a registered Bearer token and a matching project allow-policy binding.
- gRPC Cloud Monitoring (`google.monitoring.v3.MetricService`) is gated by `IamEnforcementGrpcInterceptor`
  with the same `monitoring.timeSeries.*` / `monitoring.metricDescriptors.*` /
  `monitoring.monitoredResourceDescriptors.*` permissions via `IamGrpcPermissionMapper`
  (for example `CreateTimeSeries` → `monitoring.timeSeries.create`,
  `ListTimeSeries` → `monitoring.timeSeries.list`,
  `ListMetricDescriptors` → `monitoring.metricDescriptors.list`).
- `IamPermissionMapper` maps Monitoring REST paths to `monitoring.timeSeries.*`,
  `monitoring.metricDescriptors.*`, and `monitoring.monitoredResourceDescriptors.*`.
- `roles/monitoring.viewer` grants list/get of time series and descriptors.
- `roles/monitoring.metricWriter` grants create time series and descriptor catalog write/list
  (not list time series), matching GCP Metric Writer.
- `roles/monitoring.admin` grants the full Monitoring permission set used by the CTF mapper.
- Operator root (`FLOCI_GCP_AUTH_ROOT_SERVICE_ACCOUNT` / `FLOCI_GCP_AUTH_ROOT_ACCESS_TOKEN`)
  bypasses IAM evaluation.

Regression: `CloudMonitoringIamEnforcementIntegrationTest`, `CloudMonitoringGrpcIamEnforcementIntegrationTest`.
