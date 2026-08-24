package io.floci.gcp.services.iam;

import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.IAMPolicyGrpc;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import com.google.iam.v1.TestIamPermissionsRequest;
import com.google.iam.v1.TestIamPermissionsResponse;
import io.floci.gcp.core.common.GcpGrpcController;
import io.grpc.stub.StreamObserver;
import org.jboss.logging.Logger;

/**
 * Shared {@code google.iam.v1.IAMPolicy} gRPC service for APIs that declare IAM
 * as a service-config mixin instead of on their own gRPC services (e.g. Pub/Sub).
 * Dispatches on the resource name in the request, so one binding serves every
 * such service on the single emulator port.
 *
 * <p>Policies are stored and returned, never enforced. {@code testIamPermissions}
 * echoes the requested permissions for existing resources and returns an empty
 * set for missing ones, matching the real API's fail-open behavior.
 */
public class IamPolicyGrpcController extends IAMPolicyGrpc.IAMPolicyImplBase {

    private static final Logger LOG = Logger.getLogger(IamPolicyGrpcController.class);

    private final IamService iamService;

    public IamPolicyGrpcController(IamService iamService) {
        this.iamService = iamService;
    }

    @Override
    public void getIamPolicy(GetIamPolicyRequest request, StreamObserver<Policy> responseObserver) {
        LOG.debugf("getIamPolicy resource=%s", request.getResource());
        try {
            Policy policy = IamPolicyCodec.toProtoPolicy(iamService.getPolicy(request.getResource()));
            responseObserver.onNext(policy);
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("getIamPolicy failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void setIamPolicy(SetIamPolicyRequest request, StreamObserver<Policy> responseObserver) {
        LOG.debugf("setIamPolicy resource=%s", request.getResource());
        try {
            Policy policy = IamPolicyCodec.toProtoPolicy(iamService.setPolicy(
                    request.getResource(), IamPolicyCodec.toStoredPolicy(request.getPolicy())));
            responseObserver.onNext(policy);
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("setIamPolicy failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }

    @Override
    public void testIamPermissions(TestIamPermissionsRequest request,
            StreamObserver<TestIamPermissionsResponse> responseObserver) {
        LOG.debugf("testIamPermissions resource=%s", request.getResource());
        try {
            responseObserver.onNext(TestIamPermissionsResponse.newBuilder()
                    .addAllPermissions(iamService.testPermissions(
                            request.getResource(), request.getPermissionsList()))
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.warnf("testIamPermissions failed: %s", e.getMessage());
            GcpGrpcController.grpcError(responseObserver, e);
        }
    }
}
