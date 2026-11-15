# Cloud Billing Budgets

floci-gcp serves the Cloud Billing Budget API v1 (`billingbudgets.googleapis.com`) over REST
JSON: budgets are created, read, listed, updated and deleted under a billing account. Spend is
not tracked, so budgets never cross a threshold and no notification is published.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_BILLINGBUDGETS_ENABLED` | `true` | Enable/disable the Cloud Billing Budget API |

## Endpoints

| Method | Path | Notes |
|---|---|---|
| `POST` | `/v1/billingAccounts/{account}/budgets` | Creates a budget with a generated UUID ID |
| `GET` | `/v1/billingAccounts/{account}/budgets` | `pageSize` (default and maximum 100), `pageToken`, `scope` |
| `GET` | `/v1/billingAccounts/{account}/budgets/{budget}` | |
| `PATCH` | `/v1/billingAccounts/{account}/budgets/{budget}` | `updateMask`; without one, only fields with non-default values change |
| `DELETE` | `/v1/billingAccounts/{account}/budgets/{budget}` | Returns `{}`, also for a budget that does not exist |

## Behavior

- A created budget gets the defaults GCP returns: `budgetFilter.creditTypesTreatment`
  `INCLUDE_ALL_CREDITS`, `budgetFilter.calendarPeriod` `MONTH` when no period is set, an empty
  `notificationsRule`, and `spendBasis` `CURRENT_SPEND` on each threshold rule without one.
- `etag` is a new number on every create and update. A stale `etag` in an update is accepted, as
  in GCP.
- In an update mask, `name` and `etag` are accepted and ignored; an unknown field is
  `400 Invalid update mask: <field>`. Top-level fields and paths under `budget_filter` and
  `notifications_rule` are accepted, in snake_case or camelCase.
- `ListBudgets` returns budgets in creation order. An empty page is `{}`, and `nextPageToken` is
  left out on the last page. A malformed `pageToken` is `400 INVALID_ARGUMENT`.
- `scope=projects/{project}` returns the budgets whose `budgetFilter.projects` contains that exact
  value. A budget with no project filter is not returned for a project scope, as in GCP.
- The billing account must be well formed (`XXXXXX-XXXXXX-XXXXXX`), otherwise
  `400 Invalid billing account name`. A malformed or unknown budget ID is `404 NOT_FOUND`.

Validation errors, with the messages GCP returns:

| Request | Error |
|---|---|
| No `amount`, or both `specifiedAmount` and `lastPeriodAmount` | `400 Request contains an invalid argument.` |
| `specifiedAmount.currencyCode` other than the account currency (`USD`, see [Cloud Billing](cloud-billing.md)) | `400 Request contains an invalid argument.` |
| `displayName` longer than 60 characters | `400 Budget display name ... exceeds length limit 60` |
| Both `calendarPeriod` and `customPeriod` | `400` oneof `usage_period` already set |
| `lastPeriodAmount` with `customPeriod` | `400 Last Period Amount cannot be set with Custom Period.` |
| A `FORECASTED_SPEND` threshold with `customPeriod` | `400 Threshold percent based on forecast spend cannot be set with Custom Period.` |
| Negative `thresholdPercent` | `400 Threshold percent must be non-negative: <value>` |
| `pubsubTopic` without `schemaVersion` `1.0` | `400 Invalid schema version: <value>` |

## Scope and deviations

- REST JSON only. The gRPC `BudgetService` is not served.
- Spend is not computed, so threshold rules never fire and nothing is published to
  `notificationsRule.pubsubTopic`. The topic is stored as given and not checked for existence.
- Project IDs in `budgetFilter.projects` are stored as given; GCP may rewrite them to project
  numbers.
- IAM is not evaluated for budget calls with
  `FLOCI_GCP_SERVICES_IAM_AUTHORIZATION_MODE=enforce`.
