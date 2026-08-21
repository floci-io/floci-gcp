package io.floci.gcp.services.iam;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.iam.model.StoredPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Coordinates bucket lifecycle operations with IAM policy state,
 * acquiring the IAM policy lock before GCS bucket mutation locks.
 */
@ApplicationScoped
public class IamBucketLifecycleService {

    private final IamService iamService;
    private final IamBucketPolicyBootstrapService bootstrapService;

    @Inject
    public IamBucketLifecycleService(IamService iamService,
                                     IamBucketPolicyBootstrapService bootstrapService) {
        this.iamService = iamService;
        this.bootstrapService = bootstrapService;
    }

    public void registerBucketResolver(Consumer<String> requireBucketExists) {
        iamService.registerPolicyResourceResolver("buckets/*",
                resource -> requireBucketExists.accept(resource.substring("buckets/".length())));
    }

    public <T> T createBucket(String bucket, String authorization, Supplier<T> createBucket) {
        StoredPolicy initialPolicy = bootstrapService.initialBucketPolicy(authorization);
        return iamService.createResourceAndPolicy(policyResource(bucket), initialPolicy, createBucket);
    }

    public boolean deleteBucketIfEmpty(String bucket, BooleanSupplier deleteBucket) {
        return iamService.deleteResourceAndPolicyIf(policyResource(bucket), deleteBucket);
    }

    public <T> T updateBucket(String bucket, Supplier<T> updateBucket) {
        return iamService.withPolicyLock(policyResource(bucket), updateBucket);
    }

    public void validateIamConfiguration(String bucket, Map<String, Object> iamConfiguration) {
        if (!uniformBucketLevelAccessEnabled(iamConfiguration)
                && hasConditionalBindings(iamService.getPolicy(policyResource(bucket)))) {
            throw GcpException.invalidArgument(
                    "Cannot disable uniform bucket-level access while IAM Conditions are configured");
        }
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

    private static boolean hasConditionalBindings(StoredPolicy policy) {
        try {
            return IamPolicyNormalizer.normalize(policy).bindings().stream()
                    .anyMatch(binding -> binding.condition() != null);
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static String policyResource(String bucket) {
        return IamResource.gcsBucket(bucket).policyResource();
    }
}
