package io.floci.gcp.services.iam;

import com.google.pubsub.v1.StreamingPullRequest;
import com.google.pubsub.v1.StreamingPullResponse;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.iam.authorization.IamAuthorizationRegistry;
import io.floci.gcp.services.iam.authorization.IamAuthorizationService;
import io.floci.gcp.services.iam.authorization.IamGrpcAuthorizationInterceptor;
import io.floci.gcp.services.iam.authorization.IamGrpcDeliveryAuthorization;
import io.floci.gcp.services.iam.authorization.IamOperation;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.floci.gcp.services.pubsub.PubSubIamAuthorizationAdapter;
import io.floci.gcp.services.pubsub.PubSubService;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.protobuf.ProtoUtils;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IamGrpcStreamAuthorizationServiceTest {
    private static final String PROJECT = "projects/stream-lock";
    private static final String SUBSCRIPTION = "projects/stream-lock/subscriptions/sub";
    private static final String CREDENTIAL = "Bearer subscriber";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deliveryHoldingPolicyLockCompletesBeforeRevocation(boolean projectPolicy) throws Exception {
        Fixture fixture = fixture(projectPolicy);
        CountDownLatch delivering = new CountDownLatch(1);
        CountDownLatch finishDelivery = new CountDownLatch(1);
        AtomicInteger delivered = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> delivery = executor.submit(() -> fixture.delivery().run(SUBSCRIPTION, () -> {
                delivering.countDown();
                await(finishDelivery);
                delivered.incrementAndGet();
            }));
            assertTrue(delivering.await(5, TimeUnit.SECONDS));

            CountDownLatch revocationStarted = new CountDownLatch(1);
            Future<?> revocation = executor.submit(() -> {
                revocationStarted.countDown();
                fixture.policies().setPolicy(fixture.policyResource(), new StoredPolicy());
            });
            assertTrue(revocationStarted.await(5, TimeUnit.SECONDS));
            assertBlocked(revocation);

            finishDelivery.countDown();
            delivery.get(5, TimeUnit.SECONDS);
            revocation.get(5, TimeUnit.SECONDS);
            assertEquals(1, delivered.get());
            assertTrue(fixture.policies().getPolicy(fixture.policyResource()).getBindings().isEmpty());
        } finally {
            finishDelivery.countDown();
            shutdown(executor);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void revocationHoldingPolicyLockDeniesWaitingDeliveryBeforeItPulls(boolean projectPolicy) throws Exception {
        Fixture fixture = fixture(projectPolicy);
        CountDownLatch revoking = new CountDownLatch(1);
        CountDownLatch finishRevocation = new CountDownLatch(1);
        AtomicInteger pulled = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> revocation = executor.submit(() -> fixture.policies().withPolicyLock(
                    fixture.policyResource(), () -> {
                        revoking.countDown();
                        await(finishRevocation);
                        fixture.policies().setPolicy(fixture.policyResource(), new StoredPolicy());
                        return null;
                    }));
            assertTrue(revoking.await(5, TimeUnit.SECONDS));

            CountDownLatch deliveryStarted = new CountDownLatch(1);
            Future<?> delivery = executor.submit(() -> {
                deliveryStarted.countDown();
                fixture.delivery().run(SUBSCRIPTION, pulled::incrementAndGet);
            });
            assertTrue(deliveryStarted.await(5, TimeUnit.SECONDS));
            assertBlocked(delivery);

            finishRevocation.countDown();
            revocation.get(5, TimeUnit.SECONDS);
            ExecutionException denied = assertThrows(ExecutionException.class,
                    () -> delivery.get(5, TimeUnit.SECONDS));
            assertTrue(denied.getCause() instanceof GcpException);
            assertEquals(403, ((GcpException) denied.getCause()).getHttpStatus());
            assertEquals(0, pulled.get());
        } finally {
            finishRevocation.countDown();
            shutdown(executor);
        }
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture(boolean projectPolicy) {
        String policyResource = projectPolicy ? PROJECT : SUBSCRIPTION;
        IamService policies = new IamService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>());
        StoredPolicy grant = new StoredPolicy();
        grant.setBindings(List.of(Map.of("role", "roles/pubsub.subscriber",
                "members", List.of("serviceAccount:subscriber@example.test"))));
        policies.setPolicy(policyResource, grant);

        PubSubIamAuthorizationAdapter adapter = new PubSubIamAuthorizationAdapter(mock(PubSubService.class));
        IamAuthorizationService authorization = mock(IamAuthorizationService.class);
        when(authorization.enabled()).thenReturn(true);
        when(authorization.applies(CREDENTIAL, adapter)).thenReturn(true);
        when(authorization.withPolicyLocks(any(IamResource.class), any(Supplier.class)))
                .thenAnswer(invocation -> policies.withPolicyLocks(List.of(PROJECT, SUBSCRIPTION),
                        () -> ((Supplier<?>) invocation.getArgument(1)).get()));
        doAnswer(invocation -> {
            if (policies.getPolicy(policyResource).getBindings().isEmpty()) {
                throw GcpException.permissionDenied("subscription revoked");
            }
            return null;
        }).when(authorization).authorize(eq(CREDENTIAL), eq(adapter), any(IamOperation.class));
        Instance<IamAuthorizationService> instance = mock(Instance.class);
        when(instance.get()).thenReturn(authorization);
        IamGrpcAuthorizationInterceptor interceptor = new IamGrpcAuthorizationInterceptor(instance,
                new IamAuthorizationRegistry(List.of(adapter)));

        ServerCall<StreamingPullRequest, StreamingPullResponse> call = mock(ServerCall.class);
        when(call.getMethodDescriptor()).thenReturn(MethodDescriptor.<StreamingPullRequest, StreamingPullResponse>
                newBuilder().setType(MethodDescriptor.MethodType.BIDI_STREAMING)
                .setFullMethodName("google.pubsub.v1.Subscriber/StreamingPull")
                .setRequestMarshaller(ProtoUtils.marshaller(StreamingPullRequest.getDefaultInstance()))
                .setResponseMarshaller(ProtoUtils.marshaller(StreamingPullResponse.getDefaultInstance()))
                .build());
        AtomicReference<IamGrpcDeliveryAuthorization.Delivery> delivery = new AtomicReference<>();
        ServerCallHandler<StreamingPullRequest, StreamingPullResponse> handler = mock(ServerCallHandler.class);
        when(handler.startCall(any(), any())).thenAnswer(invocation -> {
            delivery.set(IamGrpcDeliveryAuthorization.current());
            return mock(ServerCall.Listener.class);
        });
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), CREDENTIAL);
        ServerCall.Listener<StreamingPullRequest> listener = interceptor.interceptCall(call, headers, handler);
        listener.onMessage(StreamingPullRequest.newBuilder().setSubscription(SUBSCRIPTION).build());
        assertNotNull(delivery.get());
        return new Fixture(policies, policyResource, delivery.get());
    }

    private static void assertBlocked(Future<?> future) {
        assertThrows(TimeoutException.class, () -> future.get(100, TimeUnit.MILLISECONDS));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting test latch", e);
        }
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    private record Fixture(IamService policies, String policyResource,
            IamGrpcDeliveryAuthorization.Delivery delivery) {
    }
}
