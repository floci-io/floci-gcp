package io.floci.gcp.services.iam;

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
    private final GcsIamAuthorizationService iamAuthorizationService;
    private final IamConditionEvaluator conditionEvaluator;

    @Inject
    public IamBucketPolicyService(IamService iamService, GcsService gcsService,
            GcsIamAuthorizationService iamAuthorizationService,
            IamConditionEvaluator conditionEvaluator) {
        this.iamService = iamService;
        this.gcsService = gcsService;
        this.iamAuthorizationService = iamAuthorizationService;
        this.conditionEvaluator = conditionEvaluator;
    }

    public StoredPolicy getPolicy(String bucket) {
        return iamService.getPolicy(IamResource.gcsBucket(bucket).policyResource());
    }

    public StoredPolicy setPolicy(String bucket, String authorization, StoredPolicy storedPolicy) {
        return iamAuthorizationService.withBucketPermission(
                authorization, bucket, "storage.buckets.setIamPolicy", () -> {
                    gcsService.getBucket(bucket);
                    IamPolicy policy = IamPolicyNormalizer.normalize(storedPolicy);
                    validateConditions(bucket, policy);
                    return iamService.setPolicy(IamResource.gcsBucket(bucket).policyResource(), storedPolicy);
                });
    }

    public List<String> testPermissions(String bucket, String authorization, List<String> requestedPermissions) {
        return iamAuthorizationService.testBucketPermissions(authorization, bucket, requestedPermissions);
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

    static boolean uniformBucketLevelAccessEnabled(Object iamConfiguration) {
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
