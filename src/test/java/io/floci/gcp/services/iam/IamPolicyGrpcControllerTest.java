package io.floci.gcp.services.iam;

import com.google.iam.v1.Binding;
import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import com.google.iam.v1.TestIamPermissionsRequest;
import com.google.iam.v1.TestIamPermissionsResponse;
import io.floci.gcp.core.common.GcpException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IamPolicyGrpcControllerTest {

    private IamService iamService;
    private IamPolicyGrpcController controller;

    @BeforeEach
    void setUp() {
        iamService = IamServices.inMemory();
        controller = new IamPolicyGrpcController(iamService);
    }

    @Test
    void setThenGetReturnsSameBindings() {
        Policy requested = Policy.newBuilder()
                .addBindings(Binding.newBuilder()
                        .setRole("roles/pubsub.publisher")
                        .addMembers("serviceAccount:worker@p.iam.gserviceaccount.com"))
                .build();

        RecordingObserver<Policy> setObserver = new RecordingObserver<>();
        controller.setIamPolicy(SetIamPolicyRequest.newBuilder()
                .setResource("projects/p/topics/t")
                .setPolicy(requested)
                .build(), setObserver);
        assertNull(setObserver.error.get());

        RecordingObserver<Policy> getObserver = new RecordingObserver<>();
        controller.getIamPolicy(GetIamPolicyRequest.newBuilder()
                .setResource("projects/p/topics/t")
                .build(), getObserver);

        assertNull(getObserver.error.get());
        Policy read = getObserver.values.get(0);
        assertEquals(1, read.getBindingsCount());
        assertEquals("roles/pubsub.publisher", read.getBindings(0).getRole());
        assertEquals(setObserver.values.get(0).getEtag(), read.getEtag());
    }

    @Test
    void grpcWriteIsVisibleInSharedStore() {
        RecordingObserver<Policy> setObserver = new RecordingObserver<>();
        controller.setIamPolicy(SetIamPolicyRequest.newBuilder()
                .setResource("projects/p/subscriptions/s")
                .setPolicy(Policy.newBuilder()
                        .addBindings(Binding.newBuilder()
                                .setRole("roles/pubsub.subscriber")
                                .addMembers("user:x@example.com")))
                .build(), setObserver);
        assertNull(setObserver.error.get());

        assertEquals("roles/pubsub.subscriber",
                iamService.getPolicy("projects/p/subscriptions/s").getBindings().get(0).get("role"));
    }

    @Test
    void getOnMissingRegisteredResourceIsNotFound() {
        iamService.registerPolicyResourceResolver("projects/*/topics/*", resource -> {
            throw GcpException.notFound("Topic not found: " + resource);
        });

        RecordingObserver<Policy> observer = new RecordingObserver<>();
        controller.getIamPolicy(GetIamPolicyRequest.newBuilder()
                .setResource("projects/p/topics/missing")
                .build(), observer);

        StatusRuntimeException e = (StatusRuntimeException) observer.error.get();
        assertEquals(Status.Code.NOT_FOUND, e.getStatus().getCode());
    }

    @Test
    void staleEtagWriteIsAborted() {
        RecordingObserver<Policy> first = new RecordingObserver<>();
        controller.setIamPolicy(SetIamPolicyRequest.newBuilder()
                .setResource("projects/p/topics/t")
                .setPolicy(Policy.getDefaultInstance())
                .build(), first);
        Policy stale = first.values.get(0);

        RecordingObserver<Policy> second = new RecordingObserver<>();
        controller.setIamPolicy(SetIamPolicyRequest.newBuilder()
                .setResource("projects/p/topics/t")
                .setPolicy(Policy.getDefaultInstance())
                .build(), second);
        assertNull(second.error.get());

        RecordingObserver<Policy> conflicting = new RecordingObserver<>();
        controller.setIamPolicy(SetIamPolicyRequest.newBuilder()
                .setResource("projects/p/topics/t")
                .setPolicy(Policy.newBuilder().setEtag(stale.getEtag()))
                .build(), conflicting);

        StatusRuntimeException e = (StatusRuntimeException) conflicting.error.get();
        assertEquals(Status.Code.ABORTED, e.getStatus().getCode());
    }

    @Test
    void testIamPermissionsEchoesRequestedPermissions() {
        RecordingObserver<TestIamPermissionsResponse> observer = new RecordingObserver<>();
        controller.testIamPermissions(TestIamPermissionsRequest.newBuilder()
                .setResource("projects/p/topics/never-created")
                .addPermissions("pubsub.topics.publish")
                .addPermissions("pubsub.topics.get")
                .build(), observer);

        assertNull(observer.error.get());
        assertEquals(List.of("pubsub.topics.publish", "pubsub.topics.get"),
                observer.values.get(0).getPermissionsList());
    }

    private static final class RecordingObserver<T> implements StreamObserver<T> {
        final List<T> values = new ArrayList<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        boolean completed;

        @Override
        public void onNext(T value) {
            values.add(value);
        }

        @Override
        public void onError(Throwable t) {
            error.set(t);
        }

        @Override
        public void onCompleted() {
            completed = true;
            assertTrue(!values.isEmpty());
        }
    }
}
