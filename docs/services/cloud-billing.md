# Cloud Billing

floci-gcp implements the Cloud Billing v1 REST API (`cloudbilling.googleapis.com`) on the
single port. It exists so IaC tooling can read a project's billing association: the
Terraform Google provider calls `projects.getBillingInfo` for every `google_project` and
`data.google_project`.

## Supported operations

| Operation | Route |
|---|---|
| `projects.getBillingInfo` | `GET /v1/projects/{project}/billingInfo` |
| `projects.updateBillingInfo` | `PUT /v1/projects/{project}/billingInfo` |
| `billingAccounts.get` | `GET /v1/billingAccounts/{account}` |
| `billingAccounts.list` | `GET /v1/billingAccounts` |
| `billingAccounts.projects.list` | `GET /v1/billingAccounts/{account}/projects` |

## Behavior

- A project with no association reports `billingEnabled: false` and no `billingAccountName`.
- `updateBillingInfo` accepts `billingAccountName` in the form `billingAccounts/XXXXXX-XXXXXX-XXXXXX`
  and returns `billingEnabled: true`. An empty name removes the association. A malformed name
  returns `400 INVALID_ARGUMENT`.
- The emulator has no account directory. Any well-formed account ID is reported as an open
  account; `billingAccounts.list` returns the default account `billingAccounts/000000-000000-000000`
  plus every account that has been associated with a project.
- Not implemented: account creation, sub-accounts, IAM policies on billing accounts, `services.skus`.

## Configuration

| Property | Env var | Default |
|---|---|---|
| `floci-gcp.services.cloudbilling.enabled` | `FLOCI_GCP_SERVICES_CLOUDBILLING_ENABLED` | `true` |

## Terraform

```hcl
provider "google" {
  cloud_billing_custom_endpoint = "http://localhost:4588/v1/"
}
```

The environment variable `GOOGLE_CLOUD_BILLING_CUSTOM_ENDPOINT` is equivalent.
