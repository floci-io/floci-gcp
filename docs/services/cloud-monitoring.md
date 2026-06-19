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

- Metric descriptors: `CreateMetricDescriptor`, `GetMetricDescriptor`, `ListMetricDescriptors`, `DeleteMetricDescriptor`.
- Monitored resource descriptors: `ListMonitoredResourceDescriptors`, `GetMonitoredResourceDescriptor`.
- Time series: `CreateTimeSeries` (write points) and `ListTimeSeries` (read back over a time interval).
- `TypedValue` point values (`bool`, `int64`, `double`, `string`, `distribution`) are preserved round-trip.

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
