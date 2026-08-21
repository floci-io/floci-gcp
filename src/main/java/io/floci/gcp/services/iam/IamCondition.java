package io.floci.gcp.services.iam;

/**
 * A validated IAM allow-binding condition. CEL compilation is intentionally deferred to
 * {@link IamConditionEvaluator} in the evaluator slice.
 */
public record IamCondition(String title, String expression, String description) {
}
