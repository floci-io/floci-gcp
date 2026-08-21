package io.floci.gcp.services.iam;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.credentials.GcsAuthorizationService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/** Applies existing GCS CAB checks before optional IAM allow-policy enforcement. */
@ApplicationScoped
public class GcsIamAuthorizationService {

    private static final Logger LOG = Logger.getLogger(GcsIamAuthorizationService.class);
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

    /**
     * Holds the policy lock around authorization and mutation. The replacement callback uses
     * an immutable policy and principal snapshot, so it never acquires a policy lock from inside
     * the storage critical section.
     */
    public <T> T authorizeObjectCreate(String authorization, String bucket, String object,
            Function<Runnable, T> mutation) {
        return withObjectPolicyLocks(List.of(bucket), () -> {
            requireObjectWrite(authorization, bucket, object, "storage.objects.create");
            return mutation.apply(deferredObjectDelete(authorization, bucket, object));
        });
    }

    public void requireObjectDelete(String authorization, String bucket, String object) {
        cabAuthorization.requireObjectDelete(authorization, bucket, object);
        requirePermission(authorization, "storage.objects.delete", IamResource.gcsObject(bucket, object));
    }

    public <T> T authorizeObjectRestore(String authorization, String bucket, String object,
            Function<Runnable, T> mutation) {
        return withObjectPolicyLocks(List.of(bucket), () -> {
            cabAuthorization.requireObjectWrite(authorization, bucket, object);
            IamResource resource = IamResource.gcsObject(bucket, object);
            requirePermission(authorization, "storage.objects.restore", resource);
            requirePermission(authorization, "storage.objects.create", resource);
            return mutation.apply(deferredObjectDelete(authorization, bucket, object));
        });
    }

    public <T> T authorizeObjectCompose(String authorization, String bucket, List<String> sources,
            String destination, Function<Runnable, T> mutation) {
        return withObjectPolicyLocks(List.of(bucket), () -> {
            for (String source : sources) {
                requireObjectRead(authorization, bucket, source);
            }
            requireObjectWrite(authorization, bucket, destination, "storage.objects.create");
            return mutation.apply(deferredObjectDelete(authorization, bucket, destination));
        });
    }

    public <T> T authorizeObjectCopy(String authorization, String sourceBucket, String sourceObject,
            String destinationBucket, String destinationObject, Function<Runnable, T> mutation) {
        return withObjectPolicyLocks(List.of(sourceBucket, destinationBucket), () -> {
            requireObjectRead(authorization, sourceBucket, sourceObject);
            requireObjectWrite(authorization, destinationBucket, destinationObject, "storage.objects.create");
            return mutation.apply(deferredObjectDelete(
                    authorization, destinationBucket, destinationObject));
        });
    }

    public <T> T authorizeObjectMove(String authorization, String bucket, String sourceObject,
            String destinationObject, Function<Runnable, T> mutation) {
        return withObjectPolicyLocks(List.of(bucket), () -> {
            requireObjectRead(authorization, bucket, sourceObject);
            requireObjectDelete(authorization, bucket, sourceObject);
            requireObjectWrite(authorization, bucket, destinationObject, "storage.objects.create");
            return mutation.apply(deferredObjectDelete(authorization, bucket, destinationObject));
        });
    }

    private Runnable deferredObjectDelete(String authorization, String bucket, String object) {
        cabAuthorization.requireObjectDelete(authorization, bucket, object);
        IamResource resource = IamResource.gcsObject(bucket, object);
        if (config.services().iam().authorizationMode() == EmulatorConfig.IamAuthorizationMode.DISABLED) {
            return () -> { };
        }

        IamPrincipalResolver.Resolution resolution;
        IamPolicy policy;
        try {
            resolution = principalResolver.resolve(authorization);
            policy = IamPolicyNormalizer.normalize(iamService.getPolicy(resource.policyResource()));
        } catch (GcpException e) {
            if (e.getHttpStatus() == 401 || e.getHttpStatus() == 404) {
                throw e;
            }
            LOG.warnf(e, "IAM policy evaluation failed closed resource=%s permission=%s",
                    resource.policyResource(), "storage.objects.delete");
            return deniedPermission();
        } catch (RuntimeException e) {
            LOG.warnf(e, "IAM policy evaluation failed closed resource=%s permission=%s",
                    resource.policyResource(), "storage.objects.delete");
            return deniedPermission();
        }

        return () -> {
            try {
                if (policyEvaluator.isAllowed(resolution.principal(), "storage.objects.delete", resource,
                        Map.of(resource.policyResource(), policy))) {
                    return;
                }
            } catch (RuntimeException e) {
                LOG.warnf(e, "IAM policy evaluation failed closed resource=%s permission=%s",
                        resource.policyResource(), "storage.objects.delete");
            }
            throw GcpException.permissionDenied(DENIED_MESSAGE);
        };
    }

    private static Runnable deniedPermission() {
        return () -> {
            throw GcpException.permissionDenied(DENIED_MESSAGE);
        };
    }

    private <T> T withObjectPolicyLocks(List<String> buckets, Supplier<T> action) {
        return iamService.withPolicyLocks(
                buckets.stream().map(bucket -> IamResource.gcsBucket(bucket).policyResource()).toList(),
                action);
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
        } catch (GcpException e) {
            if (e.getHttpStatus() == 401 || e.getHttpStatus() == 404) {
                throw e;
            }
            LOG.warnf(e, "IAM policy evaluation failed closed resource=%s permission=%s",
                    resource.policyResource(), permission);
        } catch (RuntimeException e) {
            LOG.warnf(e, "IAM policy evaluation failed closed resource=%s permission=%s",
                    resource.policyResource(), permission);
        }
        throw GcpException.permissionDenied(DENIED_MESSAGE);
    }
}
