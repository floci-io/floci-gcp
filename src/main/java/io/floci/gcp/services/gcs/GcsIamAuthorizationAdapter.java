package io.floci.gcp.services.gcs;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.gcp.services.gcs.model.GcsBucket;
import io.floci.gcp.services.iam.IamResource;
import io.floci.gcp.services.iam.authorization.IamAuthorizationAdapter;
import io.floci.gcp.services.iam.authorization.IamIdentityKind;
import io.floci.gcp.services.iam.authorization.IamOperation;
import io.floci.gcp.services.iam.authorization.IamPermissionCheck;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** GCS resource mapping for the shared evaluator. Enforcement remains in the service path. */
@ApplicationScoped
public class GcsIamAuthorizationAdapter implements IamAuthorizationAdapter {

    private final Instance<GcsService> services;

    @Inject
    public GcsIamAuthorizationAdapter(Instance<GcsService> services) {
        this.services = services;
    }

    @Override
    public String serviceName() {
        return "gcs";
    }

    @Override
    public Set<Class<?>> restControllers() {
        return Set.of();
    }

    @Override
    public Set<String> grpcServices() {
        return Set.of();
    }

    @Override
    public IamOperation restOperation(String method, Map<String, String> path, JsonNode body) {
        throw unsupported("REST operation " + method);
    }

    @Override
    public IamOperation grpcOperation(String method, JsonNode request) {
        throw unsupported("gRPC operation " + method);
    }

    @Override
    public List<IamPermissionCheck> checks(IamOperation operation) {
        throw unsupported("operation " + operation.name());
    }

    @Override
    public Optional<IamResource> resource(String name) {
        return Optional.empty();
    }

    public IamResource bucketResource(String bucket) {
        GcsBucket stored = services.get().getBucket(bucket);
        return IamResource.gcsBucket(bucket, stored.getProjectId());
    }

    public IamResource objectResource(String bucket, String object) {
        GcsBucket stored = services.get().getBucket(bucket);
        return IamResource.gcsObject(bucket, object, stored.getProjectId());
    }

    @Override
    public Map<String, Set<String>> roles() {
        return Map.of();
    }

    @Override
    public boolean requiresPolicyEvaluation(IamIdentityKind identityKind) {
        return true;
    }

}
