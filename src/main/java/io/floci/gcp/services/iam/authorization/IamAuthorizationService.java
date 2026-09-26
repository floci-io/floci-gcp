package io.floci.gcp.services.iam.authorization;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.iam.IamBinding;
import io.floci.gcp.services.iam.IamConditionEvaluator;
import io.floci.gcp.services.iam.IamPolicy;
import io.floci.gcp.services.iam.IamPolicyEvaluator;
import io.floci.gcp.services.iam.IamPolicyNormalizer;
import io.floci.gcp.services.iam.IamPrincipal;
import io.floci.gcp.services.iam.IamPrincipalResolver;
import io.floci.gcp.services.iam.IamResource;
import io.floci.gcp.services.iam.IamResourceHierarchy;
import io.floci.gcp.services.iam.IamRoleCatalog;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.quarkus.arc.Arc;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Single decision point shared by every supported transport and testIamPermissions. */
@ApplicationScoped
public class IamAuthorizationService {
    private static final Logger LOG = Logger.getLogger(IamAuthorizationService.class);

    private final EmulatorConfig config;
    private final IamPrincipalResolver principals;
    private final IamPolicyEvaluator evaluator;
    private final IamResourceHierarchy hierarchy;
    private final IamRoleCatalog roles;
    private final IamConditionEvaluator conditions;
    private final IamService policies;
    private final IamAuthorizationRegistry registry;
    private final IamRequestIdentity identity;

    @Inject
    public IamAuthorizationService(EmulatorConfig config, IamPrincipalResolver principals,
            IamPolicyEvaluator evaluator, IamResourceHierarchy hierarchy, IamRoleCatalog roles,
            IamConditionEvaluator conditions, IamService policies, IamAuthorizationRegistry registry,
            IamRequestIdentity identity) {
        this.config = config;
        this.principals = principals;
        this.evaluator = evaluator;
        this.hierarchy = hierarchy;
        this.roles = roles;
        this.conditions = conditions;
        this.policies = policies;
        this.registry = registry;
        this.identity = identity;
    }

    public boolean enabled() {
        return config.services().iam().authorizationMode() == EmulatorConfig.IamAuthorizationMode.ENFORCE;
    }

    void onStart(@Observes StartupEvent event) {
        LOG.warnf("IAM authorization mode=%s; supported services=%s; Pub/Sub, Secret Manager, GCS and all other services are NOT IAM-enforced. "
                        + "Anonymous and external-token requests bypass IAM; use Floci-issued service-account tokens. "
                        + "Existing GCS Credential Access Boundary checks remain active.",
                config.services().iam().authorizationMode(), registry.adapters().stream()
                        .map(IamAuthorizationAdapter::serviceName).sorted().toList());
    }

    public boolean applies(String authorization) {
        if (!enabled()) {
            return false;
        }
        IamPrincipalResolver.Resolution resolution = principals.resolve(authorization);
        return resolution.downscoped() || resolution.principal().isAuthenticated();
    }

    public void authorize(String authorization, IamAuthorizationAdapter adapter, IamOperation operation) {
        if (!enabled()) {
            return;
        }
        IamPrincipalResolver.Resolution resolution = principals.resolve(authorization);
        if (resolution.downscoped()) {
            throw GcpException.permissionDenied("Downscoped tokens are only supported by the GCS Credential Access Boundary");
        }
        if (!resolution.principal().isAuthenticated()) {
            return;
        }
        for (IamPermissionCheck check : adapter.checks(operation)) {
            if (!allowed(resolution.principal(), check.permission(), check.resource())) {
                throw GcpException.permissionDenied("Permission " + check.permission()
                        + " denied on " + check.resource().name());
            }
        }
    }

    public void validatePolicyWrite(String resource, StoredPolicy policy) {
        if (enabled()) {
            registry.resource(resource).ifPresent(adapter ->
                    adapter.validatePolicy(resource, IamPolicyNormalizer.normalize(policy)));
        }
    }

    public List<String> testPermissions(String resource, List<String> requested) {
        String authorization = currentAuthorization();
        if (!enabled() || registry.resource(resource).isEmpty()) {
            return requested;
        }
        IamPrincipalResolver.Resolution resolution = principals.resolve(authorization);
        if (resolution.downscoped()) {
            return List.of();
        }
        if (!resolution.principal().isAuthenticated()) {
            return requested;
        }
        IamResource target = registry.resource(resource).orElseThrow().requireResource(resource);
        Map<String, IamPolicy> policyMap = loadPolicies(target);
        return requested.stream().filter(permission -> evaluator.isAllowed(
                resolution.principal(), permission, target, policyMap)).toList();
    }

    private String currentAuthorization() {
        if (Boolean.TRUE.equals(IamGrpcAuthorizationInterceptor.GRPC_REQUEST.get())) {
            return IamGrpcAuthorizationInterceptor.AUTHORIZATION.get();
        }
        return Arc.container().requestContext().isActive() ? identity.authorization() : null;
    }

    private boolean allowed(IamPrincipal principal, String permission, IamResource resource) {
        return evaluator.isAllowed(principal, permission, resource, loadPolicies(resource));
    }

    private Map<String, IamPolicy> loadPolicies(IamResource resource) {
        Map<String, IamPolicy> result = new LinkedHashMap<>();
        for (String key : hierarchy.policyResourcesFor(resource)) {
            IamPolicy policy = IamPolicyNormalizer.normalize(policies.policyForEvaluation(key));
            registry.resource(key).ifPresent(adapter -> adapter.validatePolicy(key, policy));
            for (IamBinding binding : policy.bindings()) {
                if (!roles.contains(binding.role())) {
                    throw GcpException.failedPrecondition("IAM enforcement does not support role "
                            + binding.role() + " in policy " + key);
                }
                if (binding.condition() != null) {
                    conditions.validate(binding.condition());
                }
            }
            result.put(key, policy);
        }
        return result;
    }
}
