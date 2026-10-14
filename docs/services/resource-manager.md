# Resource Manager

The implemented Resource Manager v1 surface exposes project metadata and project
IAM policy operations over REST JSON. Projects are synthesized from their IDs;
project creation/deletion, organizations, and folders are not implemented.

## IAM enforcement

Set `FLOCI_GCP_SERVICES_IAM_AUTHORIZATION_MODE=enforce` to enforce project metadata
reads and policy reads/writes for Floci-issued service-account tokens.
`testIamPermissions` evaluates the caller. Project policies also use the shared
IAM gRPC mixin. Project metadata has no gRPC endpoint.

Service adapters can use the shared framework to inherit project policies.
Pub/Sub does not support IAM enforcement. Secret Manager secrets and versions
inherit applicable project bindings.

Default `disabled` mode stores policies without restricting requests. Anonymous
and external credentials bypass Resource Manager IAM in both modes. Project grants
can authorize supported GCS bucket and object operations and Secret Manager
operations through inheritance. They do not enforce permissions on Pub/Sub or
other unsupported services.
Organization and folder inheritance and project ID/number alias equivalence are not implemented.
See [IAM enforcement and limitations](iam.md#opt-in-enforcement).
