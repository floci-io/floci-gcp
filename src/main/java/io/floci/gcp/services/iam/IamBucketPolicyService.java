package io.floci.gcp.services.iam;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.gcs.GcsService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/** Coordinates bucket IAM policy validation and the limited authorization query surface. */
@ApplicationScoped
public class IamBucketPolicyService {

    private static final Logger LOG = Logger.getLogger(IamBucketPolicyService.class);

    private final IamService iamService;
    private final GcsService gcsService;
    private final GcsIamAuthorizationService iamAuthorizationService;
    private final EmulatorConfig config;
    private final IamConditionEvaluator conditionEvaluator;
    private final IamPrincipalResolver principalResolver;
    private final IamPolicyEvaluator policyEvaluator;

    @Inject
    public IamBucketPolicyService(IamService iamService, GcsService gcsService,
            GcsIamAuthorizationService iamAuthorizationService, EmulatorConfig config,
            IamConditionEvaluator conditionEvaluator, IamPrincipalResolver principalResolver,
            IamPolicyEvaluator policyEvaluator) {
        this.iamService = iamService;
        this.gcsService = gcsService;
        this.iamAuthorizationService = iamAuthorizationService;
        this.config = config;
        this.conditionEvaluator = conditionEvaluator;
        this.principalResolver = principalResolver;
        this.policyEvaluator = policyEvaluator;
    }

    public StoredPolicy getPolicy(String bucket) {
        return iamService.getPolicy(IamResource.gcsBucket(bucket).policyResource());
    }

    public StoredPolicy setPolicy(String bucket, String authorization, StoredPolicy storedPolicy) {
        String policyResource = IamResource.gcsBucket(bucket).policyResource();
        return iamService.withPolicyLock(policyResource, () -> {
            iamAuthorizationService.requireBucketPermission(
                    authorization, bucket, "storage.buckets.setIamPolicy");
            gcsService.getBucket(bucket);
            IamPolicy policy = IamPolicyNormalizer.normalize(storedPolicy);
            validateConditions(bucket, policy);
            return iamService.setPolicy(policyResource, storedPolicy);
        });
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
            LOG.warnf(e, "IAM testPermissions failed closed for bucket=%s", bucket);
            return List.of();
        }
        IamResource resource = IamResource.gcsBucket(bucket);
        Map<String, IamPolicy> policies = Map.of(resource.policyResource(), policy);
        return requestedPermissions.stream()
                .filter(permission -> policyEvaluator.isAllowed(resolution.principal(), permission, resource, policies))
                .toList();
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
        return uniformBucketLevelAccessEnabled(gcsService.getBucket(bucket).getIamConfiguration());
    }

    private static boolean uniformBucketLevelAccessEnabled(Object iamConfiguration) {
        if (!(iamConfiguration instanceof Map<?, ?> iamConfigurationMap)) {
            return false;
        }
        Object uniformBucketLevelAccess = iamConfigurationMap.get("uniformBucketLevelAccess");
        if (!(uniformBucketLevelAccess instanceof Map<?, ?> uniformBucketLevelAccessMap)) {
            return false;
        }
        return Boolean.TRUE.equals(uniformBucketLevelAccessMap.get("enabled"));
    }

    private static boolean isBasicRole(String role) {
        return "roles/owner".equals(role) || "roles/editor".equals(role) || "roles/viewer".equals(role);
    }
}
