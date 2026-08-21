package io.floci.gcp.services.iam;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.gcs.GcsService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/** Coordinates bucket IAM policy validation and the limited authorization query surface. */
@ApplicationScoped
public class IamBucketPolicyService {

    private final IamService iamService;
    private final GcsService gcsService;
    private final EmulatorConfig config;
    private final IamConditionEvaluator conditionEvaluator;
    private final IamPrincipalResolver principalResolver;
    private final IamPolicyEvaluator policyEvaluator;

    @Inject
    public IamBucketPolicyService(IamService iamService, GcsService gcsService, EmulatorConfig config,
            IamConditionEvaluator conditionEvaluator, IamPrincipalResolver principalResolver,
            IamPolicyEvaluator policyEvaluator) {
        this.iamService = iamService;
        this.gcsService = gcsService;
        this.config = config;
        this.conditionEvaluator = conditionEvaluator;
        this.principalResolver = principalResolver;
        this.policyEvaluator = policyEvaluator;
    }

    public StoredPolicy getPolicy(String bucket) {
        gcsService.getBucket(bucket);
        return iamService.getPolicy(IamResource.gcsBucket(bucket).policyResource());
    }

    public StoredPolicy setPolicy(String bucket, StoredPolicy storedPolicy) {
        gcsService.getBucket(bucket);
        IamPolicy policy = IamPolicyNormalizer.normalize(storedPolicy);
        validateConditions(bucket, policy);
        return iamService.setPolicy(IamResource.gcsBucket(bucket).policyResource(), storedPolicy);
    }

    public List<String> testPermissions(String bucket, String authorization, List<String> requestedPermissions) {
        gcsService.getBucket(bucket);
        if (config.services().iam().authorizationMode() == EmulatorConfig.IamAuthorizationMode.DISABLED) {
            return requestedPermissions;
        }

        IamPrincipalResolver.Resolution resolution = principalResolver.resolve(authorization);
        if (resolution.downscoped()) {
            return List.of();
        }
        IamPolicy policy;
        try {
            policy = IamPolicyNormalizer.normalize(getPolicy(bucket));
        } catch (RuntimeException e) {
            return List.of();
        }
        IamResource resource = IamResource.gcsBucket(bucket);
        Map<String, IamPolicy> policies = Map.of(resource.policyResource(), policy);
        return requestedPermissions.stream()
                .filter(permission -> policyEvaluator.isAllowed(resolution.principal(), permission, resource, policies))
                .toList();
    }

    /** Rejects the UBLA transition that would leave an existing conditional policy invalid. */
    public void validateIamConfigurationUpdate(String bucket, Map<String, Object> patch) {
        if (setsUniformBucketLevelAccessDisabled(patch) && hasConditionalBindings(getPolicy(bucket))) {
            throw GcpException.invalidArgument(
                    "Cannot disable uniform bucket-level access while IAM Conditions are configured");
        }
    }

    private void validateConditions(String bucket, IamPolicy policy) {
        for (IamBinding binding : policy.bindings()) {
            IamCondition condition = binding.condition();
            if (condition == null) {
                continue;
            }
            if (!uniformBucketLevelAccessEnabled(bucket)) {
                throw GcpException.invalidArgument(
                        "Uniform bucket-level access must be enabled for IAM Conditions");
            }
            if (binding.members().contains("allUsers") || binding.members().contains("allAuthenticatedUsers")) {
                throw GcpException.invalidArgument("IAM Conditions cannot be used with public IAM members");
            }
            if (isBasicRole(binding.role())) {
                throw GcpException.invalidArgument("IAM Conditions cannot be used with basic roles");
            }
            conditionEvaluator.validate(condition);
        }
    }

    private boolean uniformBucketLevelAccessEnabled(String bucket) {
        Object iamConfiguration = gcsService.getBucket(bucket).getIamConfiguration();
        if (!(iamConfiguration instanceof Map<?, ?> iamConfigurationMap)) {
            return false;
        }
        Object uniformBucketLevelAccess = iamConfigurationMap.get("uniformBucketLevelAccess");
        if (!(uniformBucketLevelAccess instanceof Map<?, ?> uniformBucketLevelAccessMap)) {
            return false;
        }
        return Boolean.TRUE.equals(uniformBucketLevelAccessMap.get("enabled"));
    }

    private static boolean hasConditionalBindings(StoredPolicy policy) {
        try {
            return IamPolicyNormalizer.normalize(policy).bindings().stream()
                    .anyMatch(binding -> binding.condition() != null);
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static boolean setsUniformBucketLevelAccessDisabled(Map<String, Object> patch) {
        if (patch == null || !(patch.get("iamConfiguration") instanceof Map<?, ?> iamConfiguration)) {
            return false;
        }
        if (!(iamConfiguration.get("uniformBucketLevelAccess") instanceof Map<?, ?> uniformBucketLevelAccess)) {
            return false;
        }
        return Boolean.FALSE.equals(uniformBucketLevelAccess.get("enabled"));
    }

    private static boolean isBasicRole(String role) {
        return "roles/owner".equals(role) || "roles/editor".equals(role) || "roles/viewer".equals(role);
    }
}
