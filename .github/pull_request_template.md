## Summary

<!-- What does this PR do? Link any related issues with "Closes #N" -->

## Type of change

- [ ] Bug fix (`fix:`)
- [ ] New feature (`feat:`)
- [ ] Breaking change (`feat!:` or `fix!:`)
- [ ] Docs / chore

## GCP Compatibility

<!-- Identify the service, API version, operation, and transport affected. -->
<!-- For new features: which canonical protocol documentation, GCP SDK version, and gcloud CLI version were used? -->
<!-- For bug fixes: what was incorrect, and which transport-specific documentation or live GCP observation establishes the expected behavior? -->
<!-- If upstream behavior was not verified, explain why and do not claim exact compatibility. -->

## Concurrency and persistence

<!-- If applicable: state the invariant, competing mutation paths, and lock order. Describe deterministic interleaving coverage and any storage migration or collision analysis. -->

## Checklist

- [ ] `./mvnw test` passes locally
- [ ] New or updated integration test added
- [ ] GCP compatibility evidence provided, or not applicable with explanation
- [ ] Concurrency and storage invariants reviewed, or not applicable
- [ ] Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/)
