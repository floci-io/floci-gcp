package io.floci.gcp.services.secretmanager;

import com.google.cloud.secretmanager.v1.*;
import com.google.iam.v1.*;
import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;
import io.floci.gcp.core.common.GcpGrpcController;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.services.secretmanager.model.StoredSecret;
import io.floci.gcp.services.secretmanager.model.StoredSecretVersion;
import io.grpc.stub.StreamObserver;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;

public class SecretManagerController extends SecretManagerServiceGrpc.SecretManagerServiceImplBase {

    private static final Logger LOG = Logger.getLogger(SecretManagerController.class);

    private final SecretManagerService service;

    SecretManagerController(SecretManagerService service) {
        this.service = service;
    }

    @Override
    public void createSecret(CreateSecretRequest request, StreamObserver<Secret> responseObserver) {
        LOG.infof("createSecret parent=%s secretId=%s", request.getParent(), request.getSecretId());
        try {
            String project = extractProject(request.getParent());
            String replicationType = request.getSecret().getReplication().hasUserManaged() ? "user_managed" : "automatic";
            StoredSecret stored = service.createSecret(project, request.getSecretId(), replicationType);
            responseObserver.onNext(toProto(stored));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("createSecret failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void getSecret(GetSecretRequest request, StreamObserver<Secret> responseObserver) {
        LOG.debugf("getSecret name=%s", request.getName());
        try {
            StoredSecret stored = service.getSecret(request.getName());
            responseObserver.onNext(toProto(stored));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("getSecret failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void listSecrets(ListSecretsRequest request, StreamObserver<ListSecretsResponse> responseObserver) {
        LOG.debugf("listSecrets parent=%s", request.getParent());
        try {
            String project = extractProject(request.getParent());
            List<StoredSecret> all = service.listSecrets(project);
            PageToken.Page<StoredSecret> page = PageToken.paginate(all,
                    request.getPageSize(), request.getPageToken());
            ListSecretsResponse.Builder response = ListSecretsResponse.newBuilder();
            for (StoredSecret s : page.items()) {
                response.addSecrets(toProto(s));
            }
            if (page.nextPageToken() != null) {
                response.setNextPageToken(page.nextPageToken());
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("listSecrets failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void updateSecret(UpdateSecretRequest request, StreamObserver<Secret> responseObserver) {
        LOG.debugf("updateSecret name=%s", request.getSecret().getName());
        try {
            StoredSecret stored = service.updateSecret(request.getSecret().getName());
            responseObserver.onNext(toProto(stored));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("updateSecret failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void deleteSecret(DeleteSecretRequest request, StreamObserver<Empty> responseObserver) {
        LOG.infof("deleteSecret name=%s", request.getName());
        try {
            service.deleteSecret(request.getName());
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("deleteSecret failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void addSecretVersion(AddSecretVersionRequest request, StreamObserver<SecretVersion> responseObserver) {
        LOG.infof("addSecretVersion parent=%s", request.getParent());
        try {
            byte[] payload = request.getPayload().getData().toByteArray();
            Long crc32c = request.getPayload().hasDataCrc32C() ? request.getPayload().getDataCrc32C() : null;
            StoredSecretVersion version = service.addSecretVersion(request.getParent(), payload, crc32c);
            responseObserver.onNext(toVersionProto(version));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("addSecretVersion failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void getSecretVersion(GetSecretVersionRequest request, StreamObserver<SecretVersion> responseObserver) {
        LOG.debugf("getSecretVersion name=%s", request.getName());
        try {
            StoredSecretVersion version = service.getSecretVersion(request.getName());
            responseObserver.onNext(toVersionProto(version));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("getSecretVersion failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void listSecretVersions(ListSecretVersionsRequest request,
            StreamObserver<ListSecretVersionsResponse> responseObserver) {
        LOG.debugf("listSecretVersions parent=%s", request.getParent());
        try {
            List<StoredSecretVersion> all = service.listSecretVersions(request.getParent());
            PageToken.Page<StoredSecretVersion> page = PageToken.paginate(all,
                    request.getPageSize(), request.getPageToken());
            ListSecretVersionsResponse.Builder response = ListSecretVersionsResponse.newBuilder();
            for (StoredSecretVersion v : page.items()) {
                response.addVersions(toVersionProto(v));
            }
            if (page.nextPageToken() != null) {
                response.setNextPageToken(page.nextPageToken());
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("listSecretVersions failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void accessSecretVersion(AccessSecretVersionRequest request,
            StreamObserver<AccessSecretVersionResponse> responseObserver) {
        LOG.debugf("accessSecretVersion name=%s", request.getName());
        try {
            StoredSecretVersion version = service.accessSecretVersion(request.getName());
            SecretPayload.Builder payloadBuilder = SecretPayload.newBuilder()
                    .setData(ByteString.copyFrom(version.getPayload()));
            if (version.getDataCrc32c() != null) {
                payloadBuilder.setDataCrc32C(version.getDataCrc32c());
            }
            responseObserver.onNext(AccessSecretVersionResponse.newBuilder()
                    .setName(version.getName())
                    .setPayload(payloadBuilder.build())
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("accessSecretVersion failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void disableSecretVersion(DisableSecretVersionRequest request,
            StreamObserver<SecretVersion> responseObserver) {
        LOG.infof("disableSecretVersion name=%s", request.getName());
        try {
            StoredSecretVersion version = service.disableSecretVersion(request.getName());
            responseObserver.onNext(toVersionProto(version));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("disableSecretVersion failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void enableSecretVersion(EnableSecretVersionRequest request,
            StreamObserver<SecretVersion> responseObserver) {
        LOG.infof("enableSecretVersion name=%s", request.getName());
        try {
            StoredSecretVersion version = service.enableSecretVersion(request.getName());
            responseObserver.onNext(toVersionProto(version));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("enableSecretVersion failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void destroySecretVersion(DestroySecretVersionRequest request,
            StreamObserver<SecretVersion> responseObserver) {
        LOG.infof("destroySecretVersion name=%s", request.getName());
        try {
            StoredSecretVersion version = service.destroySecretVersion(request.getName());
            responseObserver.onNext(toVersionProto(version));
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("destroySecretVersion failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void setIamPolicy(SetIamPolicyRequest request, StreamObserver<Policy> responseObserver) {
        LOG.debugf("setIamPolicy resource=%s", request.getResource());
        responseObserver.onNext(request.getPolicy());
        responseObserver.onCompleted();
    }

    @Override
    public void getIamPolicy(GetIamPolicyRequest request, StreamObserver<Policy> responseObserver) {
        LOG.debugf("getIamPolicy resource=%s", request.getResource());
        responseObserver.onNext(Policy.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void testIamPermissions(TestIamPermissionsRequest request,
            StreamObserver<TestIamPermissionsResponse> responseObserver) {
        LOG.debugf("testIamPermissions resource=%s", request.getResource());
        responseObserver.onNext(TestIamPermissionsResponse.newBuilder()
                .addAllPermissions(request.getPermissionsList())
                .build());
        responseObserver.onCompleted();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static Secret toProto(StoredSecret stored) {
        Secret.Builder builder = Secret.newBuilder()
                .setName(stored.getName())
                .setCreateTime(toTimestamp(stored.getCreateTime()));
        if ("user_managed".equals(stored.getReplicationType())) {
            builder.setReplication(Replication.newBuilder()
                    .setUserManaged(Replication.UserManaged.getDefaultInstance())
                    .build());
        } else {
            builder.setReplication(Replication.newBuilder()
                    .setAutomatic(Replication.Automatic.getDefaultInstance())
                    .build());
        }
        return builder.build();
    }

    private static SecretVersion toVersionProto(StoredSecretVersion stored) {
        SecretVersion.Builder builder = SecretVersion.newBuilder()
                .setName(stored.getName())
                .setCreateTime(toTimestamp(stored.getCreateTime()))
                .setState(SecretVersion.State.valueOf(stored.getState()));
        if (stored.getDestroyTime() != null) {
            builder.setDestroyTime(toTimestamp(stored.getDestroyTime()));
        }
        return builder.build();
    }

    private static Timestamp toTimestamp(String isoTime) {
        if (isoTime == null) {
            return Timestamp.getDefaultInstance();
        }
        try {
            Instant instant = Instant.parse(isoTime);
            return Timestamp.newBuilder()
                    .setSeconds(instant.getEpochSecond())
                    .setNanos(instant.getNano())
                    .build();
        } catch (Exception e) {
            return Timestamp.getDefaultInstance();
        }
    }

    private static String extractProject(String parent) {
        if (parent.startsWith("projects/")) {
            return parent.substring("projects/".length());
        }
        return parent;
    }
}
