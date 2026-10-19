package io.floci.gcp.core.common;

import com.google.datastore.v1.LookupRequest;
import com.google.pubsub.v1.Topic;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.common.vertx.VertxContext;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

@QuarkusTest
class ProjectContextGrpcInterceptorTest {

    private final List<String> seen = new ArrayList<>();

    @Inject
    Vertx vertx;

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
        new ProjectContextGrpcInterceptor("fallback")
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

    @SuppressWarnings("unchecked")
    private ServerCall.Listener<Topic> intercept(Metadata headers) {
        ServerCallHandler<Topic, Topic> next = (call, metadata) -> new ServerCall.Listener<>() {
            @Override
            public void onMessage(Topic message) {
                seen.add(currentProject());
            }

            @Override
            public void onHalfClose() {
                seen.add(currentProject());
            }
        };
        return new ProjectContextGrpcInterceptor("fallback").interceptCall(mock(ServerCall.class), headers, next);
    }

    private static String currentProject() {
        return Arc.container().instance(RequestContext.class).get().getProjectId();
    }
}
