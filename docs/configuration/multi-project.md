# Multi-Project Isolation

GCP resource names follow `projects/{project}/...`. floci-gcp uses the GCP project ID as the multi-tenancy boundary — resources created in one project are invisible to another.

## How It Works

Every GCP resource name is scoped to a project:

```
projects/my-project/topics/my-topic
projects/my-project/subscriptions/my-sub
projects/my-project/databases/(default)/documents/...
projects/my-project/secrets/my-secret
```

floci-gcp extracts the project ID from each request and uses it to namespace all storage keys via `ProjectAwareStorageBackend`. Two requests with different project IDs see completely independent data sets.

## Project ID Resolution Order

floci-gcp resolves the project ID in this order:

1. URL path segment — `projects/{project}/...`
2. `x-goog-request-params` header — `project=...`
3. `FLOCI_GCP_DEFAULT_PROJECT_ID` fallback (default: `floci-local`)

## Working with Multiple Projects

```bash
export PUBSUB_EMULATOR_HOST=localhost:4588

# Create the same topic name in two different projects — fully isolated
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
    // Pub/Sub — specify project explicitly
    TopicName topicA = TopicName.of("project-a", "my-topic");
    TopicName topicB = TopicName.of("project-b", "my-topic");

    topicAdminClient.createTopic(topicA);
    topicAdminClient.createTopic(topicB);
    // These are fully independent — listing topics for project-a won't show project-b
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
