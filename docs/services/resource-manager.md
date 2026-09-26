# Resource Manager

The implemented Resource Manager v1 surface exposes project metadata and project
IAM policy operations over REST JSON. Projects are synthesized from their IDs;
project creation/deletion, organizations, and folders are not implemented.

## IAM enforcement

Set `FLOCI_GCP_SERVICES_IAM_AUTHORIZATION_MODE=enforce` to enforce project metadata
reads and policy reads/writes for Floci-issued service-account tokens.
`testIamPermissions` evaluates the caller. Project policies also use the shared
IAM gRPC mixin. Project metadata has no gRPC endpoint.

The reusable framework supports project inheritance for future service adapters.
Pub/Sub and Secret Manager enforcement will be added separately; project bindings
do not restrict their operations in this version.

Default `disabled` mode stores policies without restricting requests. Anonymous
and external credentials bypass IAM in both modes. Project grants do not enforce
permissions on GCS or any other service outside the supported scope. Organization
and folder inheritance and project ID/number alias equivalence are not implemented.
See [IAM enforcement and limitations](iam.md#opt-in-enforcement).
