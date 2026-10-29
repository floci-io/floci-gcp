# Multi-Project Isolation

GCP resource names follow `projects/{project}/...`. floci-gcp uses the GCP project ID as the multi-tenancy boundary: resources created in one project are invisible to another.

## How It Works

Every GCP resource name is scoped to a project:

```
projects/my-project/topics/my-topic
projects/my-project/subscriptions/my-sub
projects/my-project/databases/(default)/documents/...
projects/my-project/secrets/my-secret
```

floci-gcp keeps projects apart in one of two ways, depending on the service:

- **By resource name.** Most services store each resource under a key that names its project,
  normally the full resource name, which starts with `projects/{project}/`. Pub/Sub, Firestore,
  Datastore, Secret Manager, Cloud KMS, Cloud Tasks, Cloud Scheduler, Cloud Run, GKE, Eventarc,
  Managed Kafka, IAM and Cloud Logging work this way. GKE operations are the exception: they
  are stored by operation ID and matched to a project through their `selfLink`.
- **By request project.** BigQuery, Cloud SQL, Cloud Monitoring and Cloud Functions resolve the
  project of each request and prefix every stored key with it. BigQuery Storage Write API
  streams are stored under their full name instead.
- **Cloud Storage** follows GCP: bucket names are global, so a name taken in one project cannot
  be created in another. Each bucket records the project that owns it, and listing buckets
  returns only those of the project you ask for.

Either way, two requests that name different projects see independent data. A project does not
have to be created first: any project ID you use exists.

## Project ID Resolution Order

For REST requests floci-gcp resolves the project in this order:

1. URL path segment: `projects/{project}/...`
2. `project` query parameter (for example `GET /storage/v1/b?project=my-project`)
3. `x-goog-request-params` header: `project=...`
4. `FLOCI_GCP_DEFAULT_PROJECT_ID` fallback (default: `floci-local`)

On REST requests, a project number in any of these positions resolves to the ID of the
project it belongs to. Projects named in a request are registered on first use and then
show up in the [Resource Manager](../services/resource-manager.md) API.

gRPC requests resolve the project in this order: the request message's own target
(`projects/{project}/topics/{topic}`, or a `project_id` field), then the `x-goog-request-params`
routing header, then the default project. Over gRPC a project number is not yet mapped to its
project ID.

## Strict Mode

By default any project ID works. Set `FLOCI_GCP_PROJECTS_STRICT=true` to make only the
default project and projects created with `POST /v1/projects` exist. REST requests for any
other project then return `403 PERMISSION_DENIED`, as GCP does for a project you cannot
see. gRPC requests are not checked. See
[Resource Manager: strict mode](../services/resource-manager.md#strict-mode).

## Working with Multiple Projects

```bash
export PUBSUB_EMULATOR_HOST=localhost:4588

# Create the same topic name in two different projects: fully isolated
gcloud pubsub topics create my-topic --project=project-a
gcloud pubsub topics create my-topic --project=project-b

# List topics in each project independently
gcloud pubsub topics list --project=project-a
gcloud pubsub topics list --project=project-b
```

## Default Project

The default project ID is `floci-local`. Change it with:

```bash
FLOCI_GCP_DEFAULT_PROJECT_ID=my-project
```

Or via Docker Compose:

```yaml
environment:
  FLOCI_GCP_DEFAULT_PROJECT_ID: my-project
```

## SDK Examples

=== "Java"

    ```java
    // Pub/Sub: specify project explicitly
    TopicName topicA = TopicName.of("project-a", "my-topic");
    TopicName topicB = TopicName.of("project-b", "my-topic");

    topicAdminClient.createTopic(topicA);
    topicAdminClient.createTopic(topicB);
    // These are fully independent: listing topics for project-a won't show project-b
    ```

=== "Python"

    ```python
    from google.cloud import pubsub_v1

    publisher = pubsub_v1.PublisherClient()

    topic_a = publisher.topic_path("project-a", "my-topic")
    topic_b = publisher.topic_path("project-b", "my-topic")

    publisher.create_topic(request={"name": topic_a})
    publisher.create_topic(request={"name": topic_b})
    ```

=== "gcloud CLI"

    ```bash
    export PUBSUB_EMULATOR_HOST=localhost:4588

    gcloud pubsub topics create my-topic --project=project-a
    gcloud pubsub topics create my-topic --project=project-b

    # Each project sees only its own resources
    gcloud pubsub topics list --project=project-a
    gcloud pubsub topics list --project=project-b
    ```

## Project IDs and Project Numbers

Every project has a stable 12-digit project number derived from its ID, so the same ID always
gets the same number and no setup is needed. Resource Manager reports it
(`GET /v1/projects/{project}`), Cloud Storage uses it in bucket resources (`projectNumber`,
`projects/{projectNumber}`), and Firebase Auth uses it in `securetoken` responses.

Cloud Storage accepts either the ID or the number when listing buckets. Other services expect
the project ID in resource names.

## The `-` Wildcard

Where GCP documents `-` as a wildcard, floci-gcp accepts it:

| Request | Meaning |
|---|---|
| `projects/-/serviceAccounts/{EMAIL_ADDRESS\|UNIQUE_ID}` (IAM) | The project that owns the account. A missing account returns `403 PERMISSION_DENIED`, as on GCP. |
| `projects/{project}/locations/-/clusters` and `.../operations` (GKE) | Every location. |
| `projects/{project}/locations/-/functions` (Cloud Functions) | Every location. |
| `.../services/-/revisions` (Cloud Run) | Every service. Listing services with `locations/-` is rejected with `INVALID_ARGUMENT`, as on GCP. |

## Persistence

In the `persistent`, `hybrid` and `wal` [storage modes](storage.md) the project is saved with
every resource (in its name, as a key prefix, or as the owner of a bucket), so projects stay
isolated across restarts.

## Opt-in Modes

By default floci-gcp is lenient: any project exists, any location is accepted, and no policy is
evaluated. These settings make it stricter. All are off by default.

| Setting | Environment variable | Effect |
|---|---|---|
| `floci-gcp.locations.strict` | `FLOCI_GCP_LOCATIONS_STRICT` | Reject locations that are not in the catalog with `INVALID_ARGUMENT`. See [Locations](../services/locations.md). |
| `floci-gcp.services.iam.authorization-mode=enforce` | `FLOCI_GCP_SERVICES_IAM_AUTHORIZATION_MODE` | Evaluate stored IAM allow policies for the services that support it. See [IAM](../services/iam.md#opt-in-enforcement). |

## Known Gaps

- No organizations or folders: every project stands alone, and there are no organization policies.
- Services that store by resource name keep the project as the request names it, so
  `projects/{number}/...` and `projects/{id}/...` address different data there. A number
  maps to the ID only for the request project on REST (the services that store by request
  project, Cloud Storage bucket listing), for Service Usage enablement, and for project IAM
  policies.
- Project numbers are derived from the ID. They are stable, but they are not the numbers real
  GCP would assign.
