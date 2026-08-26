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
        requirePermission(authorization, permission, IamResource.gcsBucket(bucket));
    }

    public void requireObjectRead(String authorization, String bucket, String object) {
        cabAuthorization.requireObjectRead(authorization, bucket, object);
        requirePermission(authorization, "storage.objects.get", IamResource.gcsObject(bucket, object));
    }

    public void requireObjectList(String authorization, String bucket, String prefix) {
        cabAuthorization.requireObjectList(authorization, bucket, prefix);
        requirePermission(authorization, "storage.objects.list", IamResource.gcsBucket(bucket));
    }

    public void requireObjectWrite(String authorization, String bucket, String object, String permission) {
        cabAuthorization.requireObjectWrite(authorization, bucket, object);
        requirePermission(authorization, permission, IamResource.gcsObject(bucket, object));
    }

    public void requireObjectDelete(String authorization, String bucket, String object) {
        cabAuthorization.requireObjectDelete(authorization, bucket, object);
        requirePermission(authorization, "storage.objects.delete", IamResource.gcsObject(bucket, object));
    }

    private void requirePermission(String authorization, String permission, IamResource resource) {
        if (config.services().iam().authorizationMode() == EmulatorConfig.IamAuthorizationMode.DISABLED) {
            return;
        }

		IamPrincipalResolver.Resolution resolution = principalResolver.resolve(authorization);
        try {
            IamPolicy policy = IamPolicyNormalizer.normalize(iamService.getPolicy(resource.policyResource()));
			if (policyEvaluator.isAllowed(resolution.principal(), permission, resource,
                    Map.of(resource.policyResource(), policy))) {
                return;
            }
        } catch (RuntimeException e) {
            // A malformed persisted policy or unusable principal must never become a grant.
        }
        throw GcpException.permissionDenied(DENIED_MESSAGE);
    }
}
