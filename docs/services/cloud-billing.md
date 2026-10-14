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

- Billing accounts are not modelled. Any `billingAccounts/{id}` is accepted, and
  `testIamPermissions` grants everything so Terraform's
  `billing.resourceAssociations.create` pre-check passes.
- Billing account IAM policies, listing billing accounts and their projects, services and
  SKUs are not implemented.

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
