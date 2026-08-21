package io.floci.gcp.services.iam;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.credentials.GcsAuthorizationService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

/** Applies existing GCS CAB checks before optional IAM allow-policy enforcement. */
@ApplicationScoped
public class GcsIamAuthorizationService {

    private static final String DENIED_MESSAGE = "IAM policy does not allow this GCS operation";

    private final GcsAuthorizationService cabAuthorization;
    private final EmulatorConfig config;
    private final IamService iamService;
    private final IamPrincipalResolver principalResolver;
    private final IamPolicyEvaluator policyEvaluator;

    @Inject
    public GcsIamAuthorizationService(GcsAuthorizationService cabAuthorization, EmulatorConfig config,
            IamService iamService, IamPrincipalResolver principalResolver, IamPolicyEvaluator policyEvaluator) {
        this.cabAuthorization = cabAuthorization;
        this.config = config;
        this.iamService = iamService;
        this.principalResolver = principalResolver;
        this.policyEvaluator = policyEvaluator;
    }

    public void requireBucketPermission(String authorization, String bucket, String permission) {
        cabAuthorization.rejectDownscopedToken(authorization);
        if (config.services().iam().authorizationMode() == EmulatorConfig.IamAuthorizationMode.DISABLED) {
            return;
        }

        IamResource resource = IamResource.gcsBucket(bucket);
        try {
            IamPrincipalResolver.Resolution resolution = principalResolver.resolve(authorization);
            IamPolicy policy = IamPolicyNormalizer.normalize(iamService.getPolicy(resource.policyResource()));
            if (!resolution.downscoped() && policyEvaluator.isAllowed(resolution.principal(), permission, resource,
                    Map.of(resource.policyResource(), policy))) {
                return;
            }
        } catch (RuntimeException e) {
            // A malformed persisted policy or unusable principal must never become a grant.
        }
        throw GcpException.permissionDenied(DENIED_MESSAGE);
    }
}
