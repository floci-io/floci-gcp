package io.floci.gcp.core.common;

import com.google.datastore.v1.LookupRequest;
import com.google.pubsub.v1.Topic;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

@QuarkusTest
class ProjectContextGrpcInterceptorTest {

    private final List<String> seen = new ArrayList<>();

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
