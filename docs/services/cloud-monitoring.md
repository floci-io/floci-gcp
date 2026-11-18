# Cloud Monitoring

floci-gcp emulates Google Cloud Monitoring (the Metrics API) over gRPC and REST using the real
`google.monitoring.v3.MetricService` protocol. Define **metric descriptors**, write **time series**
data points, and read them back: useful for exercising custom-metric ingestion and queries without
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
- Log-based metrics: creating a Cloud Logging metric publishes its `logging.googleapis.com/user/<name>`
  descriptor here, and matching log writes add `DELTA` points (see [Log-based metrics](logging.md#log-based-metrics)).
- Uptime checks: `UptimeCheckService` configuration and a prober that writes `uptime_check/*` series (see [Uptime checks](#uptime-checks)).
- `TypedValue` point values (`bool`, `int64`, `double`, `string`, `distribution`) are preserved round-trip.
- `CreateTimeSeries` writes the valid series of a request even when others fail. It then answers
  `INVALID_ARGUMENT` with a `CreateTimeSeriesSummary` in the error details, over gRPC and REST. A
  second entry for the same series in one request fails with `Duplicate TimeSeries encountered`.
- Filters follow the [monitoring filter syntax](https://cloud.google.com/monitoring/api/v3/filters):
  `=`, `!=`, `:`, numeric ordering, `starts_with`, `ends_with`, `has_substring`, `one_of`,
  `monitoring.regex.full_match`, `AND` (optional), `OR` (binds tighter) and parentheses. A filter
  that cannot be read, or names an object the method does not take, answers `INVALID_ARGUMENT`.
  `ListMonitoredResourceDescriptors` takes `resource.type` and `resource.labels:KEY`.
- Pagination (`pageSize` / `pageToken` / `nextPageToken`) on all list methods. `ListMetricDescriptors`
  clamps the page size to 10,000; `ListTimeSeries` clamps to 100,000, where under the `FULL` view the
  page size limits the total number of points (a series may span pages) and under `HEADERS` it limits
  the number of series.

## Alerting configuration

`AlertPolicyService` and `NotificationChannelService` are served on gRPC and REST JSON
(`/v3/projects/{project}/alertPolicies`, `/notificationChannels`, `/notificationChannelDescriptors`).
Enabled policies are evaluated in the background; see [Alert evaluation](#alert-evaluation).

Notification channels:

- The server assigns `name` (`projects/{project}/notificationChannels/{id}`) and `creationRecord`,
  appends a `mutationRecords` entry on every update, and sets `enabled` to true when it is unset.
- Descriptors cover the types `channel-descriptors list` returns: `email`, `pubsub`, `slack`,
  `webhook_basicauth`, `webhook_tokenauth`, `pagerduty`, `sms`, `google_chat`, and the deprecated
  `campfire` and `hipchat`. Labels are checked against the documented keys (`email_address`;
  `topic`; `auth_token`, `channel_name`; `url`, `username`, `password`; `url`; `service_key`).
  `sms` and `google_chat` publish no label schema here and accept any labels.
- `verificationStatus` cannot be set on create or update. `sms` channels start `UNVERIFIED`; other
  types leave it unset. `:sendVerificationCode` writes a `G-NNNNNN` code to the **emulator log**
  (real Monitoring delivers it to the channel); `:verify` accepts it, and `:getVerificationCode`
  works only on a verified channel, returning a code that verifies an equivalent channel elsewhere.
- Deleting a channel an alert policy references fails with `FAILED_PRECONDITION` unless `force=true`,
  which also removes it from those policies.

Alert policies:

- The server assigns `name`, a `conditions[].name` for every condition without one, and
  `creationRecord`/`mutationRecord`. `enabled` defaults to true and `documentation.mimeType` to
  `text/markdown`, the only accepted value.
- From one to six conditions, each with exactly one condition type; a combiner is required with more
  than one. A log match, MQL, PromQL or SQL condition must be the only condition, and a log match
  policy must use `OR`. Absence conditions need a `duration` of at least 120s; `autoClose` at least
  30 minutes. Condition filters are stored as written and only checked for presence.
- Without `updateMask` the policy is replaced: conditions that keep their name keep it, unnamed ones
  are added, missing ones are removed. With a mask, each listed field is replaced (REST takes the
  camelCase mask Terraform sends).
- Notification channel references are checked for shape only, not existence.
- `ListAlertPolicies` and `ListNotificationChannels` take filters over `display_name`, `type`,
  `labels.*`, `user_labels.*` and similar fields, and report `totalSize`.

Semantics follow [alerting policies in JSON](https://docs.cloud.google.com/monitoring/alerts/policies-in-json),
[the channels API](https://docs.cloud.google.com/monitoring/alerts/using-channels-api) and
[alerting concepts](https://docs.cloud.google.com/monitoring/alerts/concepts-indepth).

## Alert evaluation

With `floci-gcp.services.monitoring.alerting-enabled` (default `true`), enabled policies are
evaluated every `evaluation-interval-seconds` (default 30; 1 in the test profile). Threshold and
absence conditions are read through `ListTimeSeries` in the policy's project, with the condition's
first aggregation. Log match, MQL, PromQL and SQL conditions are stored but not evaluated, and a
policy that has one is skipped.

- Threshold: a series violates when its newest point compares true and the run of violating points
  ending there spans at least `duration` ("A condition resets its retest window each time a
  measurement ... doesn't satisfy the condition"). `trigger.count` (default 1) or `trigger.percent`
  of the series must violate.
- Absence: a series that reported in the last 25 hours but not within `duration` is absent; when no
  series ever reported, one absence alert opens for the policy. A policy younger than `duration`
  never reports absence.
- Combiner: `OR` (and a single condition) opens alerts for each met condition; `AND` only when every
  condition is met; `AND_WITH_MATCHING_RESOURCE` only for resources that violate every condition.
- Alerts: "only one open alert for each time series in an alerting policy". An alert closes when its
  series no longer violates; when a series has no data, `EVALUATION_MISSING_DATA_INACTIVE` closes
  it and the other settings keep it open. With a nonzero `duration`,
  `EVALUATION_MISSING_DATA_ACTIVE` also opens an alert for a series that reported in the last 25
  hours but has no point within `duration`. `alertStrategy.autoClose` (default seven days) closes an
  alert once its series has had no data for that long (plus 24 hours under
  `EVALUATION_MISSING_DATA_ACTIVE`); fresh data keeps an ongoing breach open. Alerts of a deleted or
  disabled policy close without a notification when that duration expires.
- Notifications go out when an alert opens and when it closes, as the version 1.2 JSON packet
  (`incident.state` `open`/`closed`, `started_at`/`ended_at` in epoch seconds, `observed_value`,
  `threshold_value`, `condition`, `documentation`, `policy_name` and so on). `webhook_tokenauth`
  POSTs to its `url` (which carries `auth_token`), `webhook_basicauth` adds an `Authorization: Basic`
  header, and `pubsub` publishes the packet to its `topic`. Email, SMS, Slack, PagerDuty and the
  other types are written to the emulator log. Disabled and `UNVERIFIED` channels are skipped. A
  failed delivery is logged and not retried.

Alerts are served by the REST-only Alerts API (`projects.alerts` in the
[Monitoring v3 discovery document](https://monitoring.googleapis.com/$discovery/rest?version=v3);
there is no gRPC counterpart): `GET /v3/projects/{project}/alerts` (newest `openTime` first, 50 per
page by default and 1000 at most, `orderBy` on `open_time`/`close_time`, filters on `state`,
`open_time`, `close_time`, `policy.name`, `policy.display_name`, `resource.type` and `metric.type`,
for example `state="OPEN"`) and `GET /v3/projects/{project}/alerts/{alert}`. An alert has `name`,
`state` (`OPEN` or `CLOSED`), `openTime`, `closeTime`, `policy` (a snapshot of its name, display
name, severity and user labels), `resource` and `metric`.

Deviations: renotification (`notificationChannelStrategy.renotifyInterval`), notification rate
limits, snoozes, incident acknowledgement, the `url` console link and `metadata` labels are not
emulated; alerts open and close at evaluation ticks, not continuously. Semantics follow
[alerting concepts](https://docs.cloud.google.com/monitoring/alerts/concepts-indepth),
[incidents](https://docs.cloud.google.com/monitoring/alerts/incidents-events) and
[notification options](https://docs.cloud.google.com/monitoring/support/notification-options).

## Uptime checks

`UptimeCheckService` is served on gRPC and REST JSON (`/v3/projects/{project}/uptimeCheckConfigs`
and `/v3/uptimeCheckIps`).

- Create assigns `name` (`projects/{project}/uptimeCheckConfigs/{display-name-slug}-{id}`) and fills
  `period` (default `60s`; only `60s`, `300s`, `600s` and `900s` are accepted), the HTTP method
  (`GET`), path (`/`) and port (80, or 443 with `useSsl`). `timeout` is required, from 1 to 60s. A
  check needs a monitored resource, resource group or synthetic monitor, and an HTTP or TCP check;
  an `uptime_url` resource needs a `host` label.
- Without `updateMask` the configuration is replaced (the monitored resource cannot change); with
  one, only `display_name`, `http_check`, `tcp_check`, `timeout`, `content_matchers`,
  `selected_regions` and `user_labels` may be listed.
- `ListUptimeCheckIps` returns four placeholder checker locations with the address `127.0.0.1`;
  the emulator has no regional checkers.

With `floci-gcp.services.monitoring.uptime-checks-enabled` (default `true`), a prober looks every
`uptime-tick-interval-seconds` (default 10) for checks whose `period` has elapsed and runs them
against their `uptime_url` host: an HTTP(S) request with the configured method, headers, basic
authentication, body and content type, or a TCP connect. HTTPS certificates are verified only with
`validateSsl`. A response passes when its status is accepted (`acceptedResponseStatusCodes`, by
value or class, 2xx by default) and every content matcher (`CONTAINS_STRING`, `NOT_CONTAINS_STRING`,
`MATCHES_REGEX`, `NOT_MATCHES_REGEX`) holds over the first MiB of the body; a timeout or
connection failure fails it.

Each run writes, under the check's project and on its monitored resource (with `project_id` added
when missing), the GAUGE series the
[GCP metrics list](https://docs.cloud.google.com/monitoring/api/metrics_gcp_i_o) defines:
`monitoring.googleapis.com/uptime_check/check_passed` (BOOL), `request_latency` (DOUBLE, ms), and for
HTTP checks `http_status` (STRING) and, when content matchers are set, `content_mismatch` (BOOL),
labelled `check_id` (the check's ID) and `checker_location` (`local`). Alert policies can watch these
series like any other.

Deviations: checks on resources other than `uptime_url` are stored but not probed; there is one
checker instead of one per region; JSON path matchers, `pingConfig`, service agent authentication,
`time_until_ssl_cert_expires` and synthetic monitors are not run. Semantics follow
[uptime checks](https://docs.cloud.google.com/monitoring/uptime-checks).

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
  unioned: existing label keys are never removed. `BOOL`/`STRING` value types are only valid with
  `GAUGE`. The metric type must be domain-prefixed (e.g. `custom.googleapis.com/...`).
- `DeleteMetricDescriptor` only accepts user-created metrics (`custom.googleapis.com/` or
  `external.googleapis.com/` prefixes).
- `ListTimeSeries` requires a filter naming a single metric type (`metric.type = "..."`) and an
  `interval.endTime`; the read interval is half-open `(startTime, endTime]`.

### Documented deviations from real GCP

- Alignment emits points only for buckets that contain data: no interpolation of empty periods for
  `ALIGN_DELTA`/`ALIGN_RATE`.
- `DISTRIBUTION` and `STRING` values cannot be aggregated.
- A missing read `interval.startTime` is treated as unbounded (real GCP defaults it to the end time).
- `CreateTimeSeries`'s top-level failure message is modeled on the API's behaviour, not on a documented wording.
- A filter's `group.id` matches nothing, as groups are not modeled, and does not require alignment.
- `project` in a filter is compared with the project named in the request, so a project number
  matches only when the request names the project by number.
- `monitoring.regex.full_match` uses Java regular expressions rather than RE2.

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

### Organization and folder time-series reads

The gRPC `ListTimeSeries` method accepts `organizations/{organizationId}` and
`folders/{folderId}` as well as project names. The emulator does not model
hierarchy membership, so organization and folder reads return an empty result
without a next-page token. Project reads remain isolated by their request
project. Descriptor operations and time-series writes still require a project.
