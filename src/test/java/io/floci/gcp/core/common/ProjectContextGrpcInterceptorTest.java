package io.floci.gcp.core.common;

import com.google.datastore.v1.LookupRequest;
import com.google.protobuf.Message;
import com.google.pubsub.v1.Topic;
import com.google.pubsub.v1.UpdateTopicRequest;
import com.google.storage.v2.Object;
import com.google.storage.v2.RewriteObjectRequest;
import com.google.storage.v2.WriteObjectRequest;
import com.google.storage.v2.WriteObjectSpec;
import io.floci.gcp.core.storage.StorageFactory;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.common.vertx.VertxContext;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@QuarkusTest
class ProjectContextGrpcInterceptorTest {

    private final List<String> seen = new ArrayList<>();

    @Inject
    Vertx vertx;

    @Inject
    ProjectRegistry projects;

    @Test
    void handlerSeesTheProjectTheMessageNames() {
        ServerCall.Listener<Topic> listener = intercept(new Metadata());
        listener.onMessage(Topic.newBuilder().setName("projects/p1/topics/t").build());
        listener.onHalfClose();

        assertEquals(List.of("p1", "p1"), seen);
    }

    @Test
    void routingHeaderIsUsedWhenTheMessageNamesNoProject() {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("x-goog-request-params", Metadata.ASCII_STRING_MARSHALLER),
                "name=projects%2Fp2%2Ftopics%2Ft");
        ServerCall.Listener<Topic> listener = intercept(headers);
        listener.onMessage(Topic.getDefaultInstance());
        listener.onHalfClose();

        assertEquals(List.of("p2", "p2"), seen);
    }

    @Test
    void defaultProjectIsUsedWhenNothingNamesOne() {
        ServerCall.Listener<Topic> listener = intercept(new Metadata());
        listener.onMessage(Topic.getDefaultInstance());
        listener.onHalfClose();

        assertEquals(List.of("fallback", "fallback"), seen);
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectIdFieldIsRead() {
        ServerCallHandler<LookupRequest, Topic> next = (call, headers) -> new ServerCall.Listener<>() {
            @Override
            public void onMessage(LookupRequest message) {
                seen.add(currentProject());
            }
        };
        new ProjectContextGrpcInterceptor("fallback", projects, Set.of())
                .interceptCall(mock(ServerCall.class), new Metadata(), next)
                .onMessage(LookupRequest.newBuilder().setProjectId("p3").build());

        assertEquals(List.of("p3"), seen);
    }

    @Test
    void theCallersProjectIsRestoredAfterEachCallback() {
        String after = RequestScopes.callAs("outer", () -> {
            ServerCall.Listener<Topic> listener = intercept(new Metadata());
            listener.onMessage(Topic.newBuilder().setName("projects/inner/topics/t").build());
            return currentProject();
        });

        assertEquals(List.of("inner"), seen);
        assertEquals("outer", after);
    }

    @Test
    void onTheServerTheProjectLastsForTheCallAndItsScopeEndsWithIt() throws Exception {
        Context call = newCallContext();
        List<ServerCall.Listener<Topic>> listener = new ArrayList<>();
        boolean[] activeAfter = new boolean[1];

        on(call, () -> {
            listener.add(intercept(new Metadata()));
            listener.get(0).onMessage(Topic.newBuilder().setName("projects/p-call/topics/t").build());
        });
        on(call, () -> listener.get(0).onHalfClose());
        on(call, () -> {
            listener.get(0).onComplete();
            activeAfter[0] = Arc.container().requestContext().isActive();
        });

        assertEquals(List.of("p-call", "p-call"), seen);
        assertFalse(activeAfter[0]);
    }

    @Test
    void concurrentCallsKeepTheirOwnProjects() throws Exception {
        Context first = newCallContext();
        Context second = newCallContext();
        List<ServerCall.Listener<Topic>> listeners = new ArrayList<>();

        on(first, () -> listeners.add(intercept(new Metadata())));
        on(second, () -> listeners.add(intercept(new Metadata())));
        on(first, () -> listeners.get(0).onMessage(Topic.newBuilder().setName("projects/p-a/topics/t").build()));
        on(second, () -> listeners.get(1).onMessage(Topic.newBuilder().setName("projects/p-b/topics/t").build()));
        on(first, () -> listeners.get(0).onHalfClose());
        on(second, () -> listeners.get(1).onHalfClose());

        assertEquals(List.of("p-a", "p-b", "p-a", "p-b"), seen);
    }

    @Test
    void aCancelledCallEndsItsScope() throws Exception {
        Context call = newCallContext();
        List<ServerCall.Listener<Topic>> listener = new ArrayList<>();
        boolean[] activeAfter = new boolean[1];

        on(call, () -> listener.add(intercept(new Metadata())));
        on(call, () -> {
            listener.get(0).onCancel();
            activeAfter[0] = Arc.container().requestContext().isActive();
        });

        assertFalse(activeAfter[0]);
    }

    @Test
    void laterStreamMessagesDoNotChangeTheProject() {
        ServerCall.Listener<Topic> listener = intercept(new Metadata());
        listener.onMessage(Topic.newBuilder().setName("projects/first/topics/t").build());
        listener.onMessage(Topic.newBuilder().setName("projects/second/topics/t").build());

        assertEquals(List.of("first", "first"), seen);
    }

    @Test
    void aRegisteredProjectNumberTheMessageNamesResolvesToItsId() {
        String id = "grpc-ctx-message";
        projects.observe(id);
        ServerCall.Listener<Topic> listener = intercept(new Metadata());
        listener.onMessage(Topic.newBuilder().setName("projects/" + ProjectNumbers.of(id) + "/topics/t").build());
        listener.onHalfClose();

        assertEquals(List.of(id, id), seen);
    }

    @Test
    void aRegisteredProjectNumberTheRoutingHeaderNamesResolvesToItsIdFromTheStartOfTheCall() {
        String id = "grpc-ctx-header";
        projects.observe(id);
        List<String> atStart = new ArrayList<>();
        ServerCallHandler<Topic, Topic> next = (call, metadata) -> {
            atStart.add(currentProject());
            return recording();
        };
        ServerCall.Listener<Topic> listener = new ProjectContextGrpcInterceptor("fallback", projects, Set.of())
                .interceptCall(mockCall(), routedTo(ProjectNumbers.of(id)), next);
        listener.onMessage(Topic.getDefaultInstance());

        assertEquals(List.of(id), atStart);
        assertEquals(List.of(id), seen);
    }

    @Test
    void anUnregisteredProjectNumberIsKeptAsGiven() {
        String number = "990000000146";
        ServerCall.Listener<Topic> listener = intercept(new Metadata());
        listener.onMessage(Topic.newBuilder().setName("projects/" + number + "/topics/t").build());

        assertEquals(List.of(number), seen);
        assertTrue(projects.find(number).isEmpty());
    }

    @Test
    void aProjectNamedForTheFirstTimeIsRegisteredSoItsNumberResolvesLater() {
        String id = "grpc-ctx-first-use";
        ServerCall.Listener<Topic> first = intercept(new Metadata());
        first.onMessage(Topic.newBuilder().setName("projects/" + id + "/topics/t").build());
        ServerCall.Listener<Topic> second = intercept(new Metadata());
        second.onMessage(Topic.newBuilder().setName("projects/" + ProjectNumbers.of(id) + "/topics/t").build());

        assertEquals(List.of(id, id), seen);
    }

    @Test
    void strictProjectsRejectAnUnknownProjectIdWithoutRunningTheHandler() {
        assertRejected(Topic.newBuilder().setName("projects/grpc-unknown/topics/t").build(), new Metadata());
    }

    @Test
    void strictProjectsRejectAnUnknownProjectNumberWithoutRunningTheHandler() {
        assertRejected(Topic.newBuilder().setName("projects/990000000147/topics/t").build(), new Metadata());
    }

    @Test
    void strictProjectsRejectAnUnknownProjectInTheRoutingHeaderWhenTheMessageNamesNone() {
        assertRejected(Topic.getDefaultInstance(), routedTo("grpc-unknown"));
    }

    @Test
    void strictProjectsCheckTheMessagesProjectRatherThanTheRoutingHeaders() {
        ServerCall<Topic, Topic> call = mockCall();
        ServerCall.Listener<Topic> listener = new ProjectContextGrpcInterceptor("fallback", strictProjects(), Set.of())
                .interceptCall(call, routedTo("grpc-unknown"), (c, h) -> recording());
        listener.onMessage(Topic.newBuilder().setName("projects/fallback/topics/t").build());

        assertEquals(List.of("fallback"), seen);
        verify(call, times(0)).close(any(), any());
    }

    @Test
    void strictProjectsResolveTheDefaultProjectByIdAndNumber() {
        ServerCall<Topic, Topic> call = mockCall();
        ProjectContextGrpcInterceptor interceptor = new ProjectContextGrpcInterceptor("fallback", strictProjects(), Set.of());
        interceptor.interceptCall(call, new Metadata(), (c, h) -> recording())
                .onMessage(Topic.newBuilder().setName("projects/fallback/topics/t").build());
        interceptor.interceptCall(call, new Metadata(), (c, h) -> recording())
                .onMessage(Topic.newBuilder().setName("projects/" + ProjectNumbers.of("fallback") + "/topics/t").build());

        assertEquals(List.of("fallback", "fallback"), seen);
        verify(call, times(0)).close(any(), any());
    }

    @Test
    void aRejectionAfterTheHandlerClosedTheCallDoesNotCloseItTwice() {
        ServerCall<Topic, Topic> call = mockCall();
        ServerCallHandler<Topic, Topic> closing = (held, metadata) -> new ServerCall.Listener<>() {
            @Override
            public void onMessage(Topic message) {
                held.close(Status.OK, new Metadata());
            }
        };
        ServerCall.Listener<Topic> listener = new ProjectContextGrpcInterceptor("fallback", strictProjects(), Set.of())
                .interceptCall(call, new Metadata(), closing);
        listener.onMessage(Topic.getDefaultInstance());
        listener.onMessage(Topic.newBuilder().setName("projects/grpc-unknown/topics/t").build());

        verify(call, times(1)).close(any(), any());
    }

    /**
     * The interceptor reads a request through its method's proto schema, because in a native image
     * the message's own descriptor is reached by reflection; a hand-built method without a schema
     * would fail every call there while the JVM tests stay green.
     */
    @Test
    void everyBoundGrpcMethodHasAProtoSchema() {
        assertEquals(Set.of(), ProtoResourceFields.methodsWithoutSchema());
    }

    /** A duplicated Vert.x context, which is what the gRPC server gives each call. */
    private Context newCallContext() {
        return VertxContext.createNewDuplicatedContext(vertx.getOrCreateContext());
    }

    private static void on(Context context, Runnable task) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        context.runOnContext(ignored -> {
            try {
                task.run();
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        done.get(5, TimeUnit.SECONDS);
    }

    private void assertRejected(Topic message, Metadata headers) {
        ServerCall<Topic, Topic> call = mockCall();
        ServerCall.Listener<Topic> listener = new ProjectContextGrpcInterceptor("fallback", strictProjects(), Set.of())
                .interceptCall(call, headers, (c, h) -> recording());
        listener.onMessage(message);
        listener.onHalfClose();
        listener.onComplete();

        assertEquals(List.of("cancelled"), seen);
        verify(call, times(1)).close(
                argThat(status -> status.getCode() == Status.Code.PERMISSION_DENIED), any());
    }

    private static ProjectRegistry strictProjects() {
        return new ProjectRegistry(StorageFactory.createInMemory(), "fallback", true);
    }

    private static Metadata routedTo(String project) {
        Metadata headers = new Metadata();
        headers.put(RequestTarget.ROUTING_HEADER, "name=projects%2F" + project + "%2Ftopics%2Ft");
        return headers;
    }

    @SuppressWarnings("unchecked")
    @Test
    void aKeyInAnotherProjectIsNotRegisteredAsAProjectOfTheCall() {
        String keyProject = "grpc-ctx-kms-key";

        runOnMessage(projects, Set.of("storage.googleapis.com"), writeWithKeyIn(keyProject), new Metadata());

        assertTrue(projects.find(keyProject).isEmpty());
    }

    @Test
    void aTopLevelKeyInAnotherProjectIsNotRegisteredEither() {
        String keyProject = "grpc-ctx-kms-top";

        runOnMessage(projects, Set.of("storage.googleapis.com"), RewriteObjectRequest.newBuilder()
                .setDestinationKmsKey(keyName(keyProject)).build(), new Metadata());

        assertTrue(projects.find(keyProject).isEmpty());
    }

    @Test
    void strictProjectsDoNotRejectACallForAKeyInAnotherProject() {
        ServerCall<Message, Message> call = runOnMessage(strictProjects(), Set.of("storage.googleapis.com"),
                writeWithKeyIn("grpc-unknown-key"), new Metadata());

        verify(call, times(0)).close(any(), any());
    }

    @Test
    void strictProjectsStillCheckTheRoutingHeaderWhenTheMessageNamesOnlyAKey() {
        ServerCall<Message, Message> call = runOnMessage(strictProjects(), Set.of("storage.googleapis.com"),
                writeWithKeyIn("fallback"), routedTo("grpc-unknown"));

        verify(call).close(argThat(status -> status.getCode() == Status.Code.PERMISSION_DENIED), any());
    }

    @Test
    void strictProjectsRejectAnUnknownProjectInTheServicesOwnNestedResource() {
        ServerCall<Message, Message> call = runOnMessage(strictProjects(), Set.of("pubsub.googleapis.com"),
                UpdateTopicRequest.newBuilder()
                        .setTopic(Topic.newBuilder().setName("projects/grpc-unknown/topics/t")).build(),
                new Metadata());

        verify(call).close(argThat(status -> status.getCode() == Status.Code.PERMISSION_DENIED), any());
    }

    private static WriteObjectRequest writeWithKeyIn(String project) {
        return WriteObjectRequest.newBuilder().setWriteObjectSpec(WriteObjectSpec.newBuilder()
                .setResource(Object.newBuilder().setBucket("projects/_/buckets/b").setKmsKey(keyName(project))))
                .build();
    }

    private static String keyName(String project) {
        return "projects/" + project + "/locations/l/keyRings/r/cryptoKeys/c";
    }

    @SuppressWarnings("unchecked")
    private static ServerCall<Message, Message> runOnMessage(ProjectRegistry registry, Set<String> domains,
            Message message, Metadata headers) {
        ServerCall<Message, Message> call = mock(ServerCall.class);
        new ProjectContextGrpcInterceptor("fallback", registry, domains)
                .interceptCall(call, headers, (c, h) -> new ServerCall.Listener<Message>() {})
                .onMessage(message);
        return call;
    }

    private static ServerCall<Topic, Topic> mockCall() {
        return mock(ServerCall.class);
    }

    private ServerCall.Listener<Topic> intercept(Metadata headers) {
        return new ProjectContextGrpcInterceptor("fallback", projects, Set.of())
                .interceptCall(mockCall(), headers, (call, metadata) -> recording());
    }

    private ServerCall.Listener<Topic> recording() {
        return new ServerCall.Listener<>() {
            @Override
            public void onMessage(Topic message) {
                seen.add(currentProject());
            }

            @Override
            public void onHalfClose() {
                seen.add(currentProject());
            }

            @Override
            public void onCancel() {
                seen.add("cancelled");
            }
        };
    }

    private static String currentProject() {
        return Arc.container().instance(RequestContext.class).get().getProjectId();
    }
}
