package io.floci.gcp.services.iam;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Objects;

/** Pure IAM allow-policy evaluator with no HTTP, storage, or token parsing dependency. */
@ApplicationScoped
public class IamPolicyEvaluator {

    private final IamRoleCatalog roleCatalog;
    private final IamResourceHierarchy resourceHierarchy;
    private final IamConditionEvaluator conditionEvaluator;

    @Inject
    public IamPolicyEvaluator(IamRoleCatalog roleCatalog, IamResourceHierarchy resourceHierarchy,
            IamConditionEvaluator conditionEvaluator) {
        this.roleCatalog = roleCatalog;
        this.resourceHierarchy = resourceHierarchy;
        this.conditionEvaluator = conditionEvaluator;
    }

    public boolean isAllowed(IamPrincipal principal, String permission, IamResource resource,
            Map<String, IamPolicy> policies) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(policies, "policies");

        for (String policyResource : resourceHierarchy.policyResourcesFor(resource)) {
            IamPolicy policy = policies.get(policyResource);
            if (policy == null) {
                continue;
            }
            for (IamBinding binding : policy.bindings()) {
                if (isValidForEvaluation(policy, binding) && matches(principal, binding)
                        && roleCatalog.grants(binding.role(), permission)
                        && (binding.condition() == null || conditionEvaluator.matches(binding.condition(), resource))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matches(IamPrincipal principal, IamBinding binding) {
        return binding.members().contains("allUsers")
                || (principal.isAuthenticated() && binding.members().contains("allAuthenticatedUsers"))
                || (principal.member() != null && binding.members().contains(principal.member()));
    }

    private static boolean isValidForEvaluation(IamPolicy policy, IamBinding binding) {
        if (binding.condition() == null) {
            return true;
        }
        return policy.version() == 3
                && !binding.members().contains("allUsers")
                && !binding.members().contains("allAuthenticatedUsers");
    }
}
