package io.floci.gcp.services.iam;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.credentials.GcsAuthorizationService;
import io.floci.gcp.services.gcs.GcsIamAuthorizationAdapter;
import io.floci.gcp.services.iam.authorization.IamAuthorizationService;
import io.floci.gcp.services.iam.authorization.IamPermissionCheck;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/** Applies existing GCS CAB checks before optional IAM allow-policy enforcement. */
@ApplicationScoped
public class GcsIamAuthorizationService {

    private static final String DENIED_MESSAGE = "IAM policy does not allow this GCS operation";

    private final GcsAuthorizationService cabAuthorization;
    private final IamAuthorizationService authorization;
    private final GcsIamAuthorizationAdapter adapter;

    @Inject
    public GcsIamAuthorizationService(GcsAuthorizationService cabAuthorization,
            IamAuthorizationService authorization, GcsIamAuthorizationAdapter adapter) {
        this.cabAuthorization = cabAuthorization;
        this.authorization = authorization;
        this.adapter = adapter;
    }

    public void requireBucketPermission(String authorizationHeader, String bucket, String permission) {
        cabAuthorization.rejectDownscopedToken(authorizationHeader);
        requireBucketIamPermission(authorizationHeader, bucket, permission);
    }

    public <T> T withBucketPermission(String authorizationHeader, String bucket, String permission,
            Supplier<T> action) {
        cabAuthorization.rejectDownscopedToken(authorizationHeader);
        if (!authorization.enabled()) {
            return authorization.withPolicyLocks(IamResource.gcsBucket(bucket), action);
        }

        while (true) {
            IamResource resource = adapter.bucketResource(bucket);
            LockedResult<T> result = authorization.withPolicyLocks(resource, () -> {
                if (!resource.equals(adapter.bucketResource(bucket))) {
                    return new LockedResult<>(true, null);
                }
                authorization.authorize(
                        authorizationHeader, adapter, new IamPermissionCheck(permission, resource));
                return new LockedResult<>(false, action.get());
            });
            if (!result.retry()) {
                return result.value();
            }
        }
    }

    public void requireObjectRead(String authorizationHeader, String bucket, String object) {
        cabAuthorization.requireObjectRead(authorizationHeader, bucket, object);
        requireObjectIamPermission(authorizationHeader, bucket, object, "storage.objects.get");
    }

    public void requireObjectList(String authorizationHeader, String bucket, String prefix) {
        cabAuthorization.requireObjectList(authorizationHeader, bucket, prefix);
        requireBucketIamPermission(authorizationHeader, bucket, "storage.objects.list");
    }

    public void requireObjectWrite(String authorizationHeader, String bucket, String object, String permission) {
        cabAuthorization.requireObjectWrite(authorizationHeader, bucket, object);
        requireObjectIamPermission(authorizationHeader, bucket, object, permission);
    }

    /**
     * Holds the applicable policy locks around authorization and mutation. The replacement
     * callback uses an immutable permission snapshot, so it does not acquire policy locks from
     * inside the storage critical section.
     */
    public <T> T authorizeObjectCreate(String authorizationHeader, String bucket, String object,
            Function<OverwriteAuthorization, T> mutation) {
        return withObjectPolicyLocks(List.of(bucket), () -> {
            requireObjectWrite(authorizationHeader, bucket, object, "storage.objects.create");
            Runnable requireObjectDelete = deferredObjectDelete(authorizationHeader, bucket, object);
            return mutation.apply(new OverwriteAuthorization(isAllowed(requireObjectDelete)));
        });
    }

    public <T> T authorizeMultipartWrite(String authorizationHeader, String bucket, String object,
            Supplier<T> mutation) {
        return withObjectPolicyLocks(List.of(bucket), () -> {
            requireMultipartWrite(authorizationHeader, bucket, object);
            return mutation.get();
        });
    }

    public <T> T authorizeMultipartCompletion(String authorizationHeader, String bucket, String object,
            Function<Runnable, T> mutation) {
        return withObjectPolicyLocks(List.of(bucket), () -> {
            requireMultipartWrite(authorizationHeader, bucket, object);
            return mutation.apply(deferredObjectDelete(authorizationHeader, bucket, object));
        });
    }

    public <T> T authorizeMultipartAbort(String authorizationHeader, String bucket, String object,
            Supplier<T> mutation) {
        return withObjectPolicyLocks(List.of(bucket), () -> {
            cabAuthorization.requireObjectDelete(authorizationHeader, bucket, object);
            requireObjectIamPermission(
                    authorizationHeader, bucket, object, "storage.multipartUploads.abort");
            return mutation.get();
        });
    }

    public void requireMultipartList(String authorizationHeader, String bucket, String prefix) {
        cabAuthorization.requireObjectList(authorizationHeader, bucket, prefix);
        requireBucketIamPermission(authorizationHeader, bucket, "storage.multipartUploads.list");
    }

    public void requireMultipartListParts(String authorizationHeader, String bucket, String object) {
        cabAuthorization.requireObjectRead(authorizationHeader, bucket, object);
        requireObjectIamPermission(
                authorizationHeader, bucket, object, "storage.multipartUploads.listParts");
    }

    private void requireMultipartWrite(String authorizationHeader, String bucket, String object) {
        cabAuthorization.requireObjectWrite(authorizationHeader, bucket, object);
        requireObjectIamPermission(
                authorizationHeader, bucket, object, "storage.multipartUploads.create");
        requireObjectIamPermission(authorizationHeader, bucket, object, "storage.objects.create");
    }

    public void requireObjectDelete(String authorizationHeader, String bucket, String object) {
        cabAuthorization.requireObjectDelete(authorizationHeader, bucket, object);
        requireObjectIamPermission(authorizationHeader, bucket, object, "storage.objects.delete");
    }

    public <T> T authorizeObjectRestore(String authorizationHeader, String bucket, String object,
            Function<Runnable, T> mutation) {
        return withObjectPolicyLocks(List.of(bucket), () -> {
            cabAuthorization.requireObjectWrite(authorizationHeader, bucket, object);
            requireObjectIamPermission(authorizationHeader, bucket, object, "storage.objects.restore");
            requireObjectIamPermission(authorizationHeader, bucket, object, "storage.objects.create");
            return mutation.apply(deferredObjectDelete(authorizationHeader, bucket, object));
        });
    }

    public <T> T authorizeObjectCompose(String authorizationHeader, String bucket, List<String> sources,
            String destination, Function<Runnable, T> mutation) {
        return withObjectPolicyLocks(List.of(bucket), () -> {
            for (String source : sources) {
                requireObjectRead(authorizationHeader, bucket, source);
            }
            requireObjectWrite(
                    authorizationHeader, bucket, destination, "storage.objects.create");
            return mutation.apply(deferredObjectDelete(authorizationHeader, bucket, destination));
        });
    }

    public <T> T authorizeObjectCopy(String authorizationHeader, String sourceBucket, String sourceObject,
            String destinationBucket, String destinationObject, Function<Runnable, T> mutation) {
        return withObjectPolicyLocks(List.of(sourceBucket, destinationBucket), () -> {
            requireObjectRead(authorizationHeader, sourceBucket, sourceObject);
            requireObjectWrite(
                    authorizationHeader, destinationBucket, destinationObject, "storage.objects.create");
            return mutation.apply(deferredObjectDelete(
                    authorizationHeader, destinationBucket, destinationObject));
        });
    }

    public <T> T authorizeObjectMove(String authorizationHeader, String bucket, String sourceObject,
            String destinationObject, Function<Runnable, T> mutation) {
        return withObjectPolicyLocks(List.of(bucket), () -> {
            requireObjectMoveSource(authorizationHeader, bucket, sourceObject);
            requireObjectWrite(
                    authorizationHeader, bucket, destinationObject, "storage.objects.create");
            return mutation.apply(deferredObjectDelete(authorizationHeader, bucket, destinationObject));
        });
    }

    public List<String> testBucketPermissions(
            String authorizationHeader, String bucket, List<String> permissions) {
        cabAuthorization.rejectDownscopedToken(authorizationHeader);
        if (!authorization.enabled()) {
            return permissions;
        }
        IamResource resource = adapter.bucketResource(bucket);
        return authorization.testPermissions(authorizationHeader, adapter, resource, permissions);
    }

    private void requireObjectMoveSource(String authorizationHeader, String bucket, String object) {
        cabAuthorization.requireObjectRead(authorizationHeader, bucket, object);
        cabAuthorization.requireObjectDelete(authorizationHeader, bucket, object);
        if (isAllowed(() -> requireObjectIamPermission(
                authorizationHeader, bucket, object, "storage.objects.move"))) {
            return;
        }
        requireObjectIamPermission(authorizationHeader, bucket, object, "storage.objects.get");
        requireObjectIamPermission(authorizationHeader, bucket, object, "storage.objects.delete");
    }

    private Runnable deferredObjectDelete(String authorizationHeader, String bucket, String object) {
        cabAuthorization.requireObjectDelete(authorizationHeader, bucket, object);
        if (!authorization.enabled()) {
            return () -> { };
        }
        boolean allowed = isAllowed(() -> requireObjectIamPermission(
                authorizationHeader, bucket, object, "storage.objects.delete"));
        return allowed ? () -> { } : deniedPermission();
    }

    private static Runnable deniedPermission() {
        return () -> {
            throw GcpException.permissionDenied(DENIED_MESSAGE);
        };
    }

    private static boolean isAllowed(Runnable requirePermission) {
        try {
            requirePermission.run();
            return true;
        } catch (GcpException exception) {
            if (exception.getHttpStatus() == 403) {
                return false;
            }
            throw exception;
        }
    }

    private <T> T withObjectPolicyLocks(List<String> buckets, Supplier<T> action) {
        if (!authorization.enabled()) {
            List<IamResource> resources = buckets.stream().map(IamResource::gcsBucket).toList();
            return authorization.withPolicyLocks(resources, action);
        }

        while (true) {
            List<IamResource> resources = buckets.stream().map(adapter::bucketResource).toList();
            LockedResult<T> result = authorization.withPolicyLocks(resources, () -> {
                List<IamResource> current = buckets.stream().map(adapter::bucketResource).toList();
                if (!resources.equals(current)) {
                    return new LockedResult<>(true, null);
                }
                return new LockedResult<>(false, action.get());
            });
            if (!result.retry()) {
                return result.value();
            }
        }
    }

    private void requireBucketIamPermission(String authorizationHeader, String bucket, String permission) {
        if (!authorization.enabled()) {
            return;
        }
        IamResource resource = adapter.bucketResource(bucket);
        authorization.authorize(
                authorizationHeader, adapter, new IamPermissionCheck(permission, resource));
    }

    private void requireObjectIamPermission(
            String authorizationHeader, String bucket, String object, String permission) {
        if (!authorization.enabled()) {
            return;
        }
        IamResource resource = adapter.objectResource(bucket, object);
        authorization.authorize(
                authorizationHeader, adapter, new IamPermissionCheck(permission, resource));
    }

    /** Opening-time authorization snapshot carried by a mutation into its storage lock. */
    public record OverwriteAuthorization(boolean allowed) {
        public void require() {
            if (!allowed) {
                throw GcpException.permissionDenied(DENIED_MESSAGE);
            }
        }
    }

    private record LockedResult<T>(boolean retry, T value) {}
}
