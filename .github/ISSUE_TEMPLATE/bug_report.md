---
name: Bug report
about: A GCP API call returns wrong behavior or an error
title: '[BUG] '
labels: bug
assignees: ''
---

## Service

<!-- e.g. Pub/Sub, GCS, Firestore, Datastore, Secret Manager -->

## GCP API / Method

<!-- e.g. projects.topics.publish, objects.insert, projects.secrets.get -->

## Expected behavior

<!-- What the real GCP SDK/CLI returns -->

## Actual behavior

<!-- What floci-gcp returns — include the full error message or response body -->

## Reproduction

```bash
# Minimal gcloud CLI or SDK snippet that triggers the issue
# e.g.:
# export PUBSUB_EMULATOR_HOST=localhost:4588
# gcloud pubsub topics create my-topic --project my-project
```

## Environment

- floci-gcp version / image tag:
- GCP SDK version (if applicable):
- How you're running floci-gcp (Docker / native / `mvn quarkus:dev`):
