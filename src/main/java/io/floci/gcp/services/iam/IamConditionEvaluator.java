package io.floci.gcp.services.iam;

/** Evaluates the deliberately restricted IAM Conditions profile. */
public interface IamConditionEvaluator {

    /** Checks that a condition is supported before its policy is persisted. */
    void validate(IamCondition condition);

    /** Returns {@code true} only when the condition evaluates to Boolean {@code true}. */
    boolean matches(IamCondition condition, IamResource resource);
}
