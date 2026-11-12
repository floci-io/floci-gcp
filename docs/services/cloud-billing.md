# Cloud Billing

floci-gcp serves a small stub of the Cloud Billing v1 API (`cloudbilling.googleapis.com`)
over REST JSON. It covers what the Terraform `google_project` resource and
`google_billing_project_info` call to link a project to a billing account. There are no
charges, budgets or billing account resources.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_CLOUDBILLING_ENABLED` | `true` | Enable/disable the Cloud Billing stub |

## Endpoints

| Method | Path | Notes |
|---|---|---|
| `GET` | `/v1/projects/{project}/billingInfo` | `ProjectBillingInfo` for the project |
| `PUT` | `/v1/projects/{project}/billingInfo` | Links `billingAccountName`; empty or absent unlinks |
| `GET` | `/v1/billingAccounts` | Lists the default account and every account a project is linked to; `pageSize`, `pageToken` |
| `GET` | `/v1/billingAccounts/{account}` | Any well-formed account ID returns an open `BillingAccount`; a malformed one is `400` |
| `GET` | `/v1/billingAccounts/{account}/projects` | `ProjectBillingInfo` of the projects linked to the account; `pageSize`, `pageToken` |
| `POST` | `/v1/billingAccounts/{account}:testIamPermissions` | Returns every requested permission |

`ProjectBillingInfo` has the shape:

```json
{
  "name": "projects/my-project/billingInfo",
  "projectId": "my-project",
  "billingAccountName": "billingAccounts/000000-AAAAAA-BBBBBB",
  "billingEnabled": true
}
```

`billingAccountName` must be empty or `billingAccounts/{id}`; anything else returns
`400 INVALID_ARGUMENT`. `billingEnabled` is true whenever an account is linked. The link is
stored on the project in the [project registry](resource-manager.md), so it persists with
the configured storage mode and follows the same strict-mode rules.

## Scope and deviations

- Billing accounts are not stored. `billingAccounts.list` returns `billingAccounts/000000-000000-000000`
  ("My Billing Account", the name GCP gives a new account) plus every account a project links to,
  each `open`, with `currencyCode` `USD` and empty `masterBillingAccount` and `parent`;
  `billingAccounts.get` returns any well-formed ID (`XXXXXX-XXXXXX-XXXXXX`) as an open account.
- A malformed account ID returns `400 INVALID_ARGUMENT`, as in GCP. GCP answers `403` (get) or
  `404` (projects) for an account the caller cannot see; floci has no unknown accounts, so it
  does not.
- Any `billingAccounts/{id}` is accepted when linking a project, and
  `testIamPermissions` grants everything so Terraform's
  `billing.resourceAssociations.create` pre-check passes.
- Billing account IAM policies, creating billing accounts, services and SKUs are not implemented.

## IAM enforcement

With `FLOCI_GCP_SERVICES_IAM_AUTHORIZATION_MODE=enforce`, `GET .../billingInfo` checks
`resourcemanager.projects.get` on the project for Floci-issued service-account tokens, as the
Cloud Billing API states for `GetProjectBillingInfo`. `PUT .../billingInfo` and billing-account
`testIamPermissions` are not evaluated: GCP requires ownership of both the project and the
billing account for the update without naming the permission it checks, and billing accounts
are not modelled. See [IAM](iam.md#opt-in-enforcement).

## Quick Start

=== "Terraform"

    ```hcl
    provider "google" {
      cloud_billing_custom_endpoint = "http://localhost:4588/v1/"
    }

    resource "google_billing_project_info" "app" {
      project         = "my-app-project"
      billing_account = "000000-AAAAAA-BBBBBB"
    }
    ```

=== "curl"

    ```bash
    curl -X PUT http://localhost:4588/v1/projects/my-project/billingInfo \
      -H 'Content-Type: application/json' \
      -d '{"billingAccountName":"billingAccounts/000000-AAAAAA-BBBBBB"}'
    ```
