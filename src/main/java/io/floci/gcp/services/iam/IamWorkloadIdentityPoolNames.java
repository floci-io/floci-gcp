package io.floci.gcp.services.iam;

import io.floci.gcp.core.common.ProjectNumbers;
import io.floci.gcp.core.common.ProjectRegistry;

/**
 * Resource names for the IAM Workload Identity Pools API.
 *
 * <p>The API returns names with the project number, as GCP does, while a policy attached to a
 * resource is stored under the project ID form the caller writes to. Keep the two spellings in one
 * place so the controller, the IAM adapter and the policy resolver cannot drift apart.</p>
 */
final class IamWorkloadIdentityPoolNames {

    static final String LOCATION = "global";
    static final String POOLS = "workloadIdentityPools";
    static final String PROVIDERS = "providers";

    private IamWorkloadIdentityPoolNames() {
    }

    static String pool(String projectIdOrNumber, String poolId) {
        return "projects/" + projectNumber(projectIdOrNumber) + "/locations/" + LOCATION
                + "/" + POOLS + "/" + poolId;
    }

    private static String projectNumber(String projectIdOrNumber) {
        return ProjectRegistry.isNumber(projectIdOrNumber) ? projectIdOrNumber
                : ProjectNumbers.of(projectIdOrNumber);
    }

    static String policyPool(String projectId, String poolId) {
        return "projects/" + projectId + "/locations/" + LOCATION + "/" + POOLS + "/" + poolId;
    }

    static String provider(String projectId, String poolId, String providerId) {
        return pool(projectId, poolId) + "/" + PROVIDERS + "/" + providerId;
    }
}
