package io.floci.gcp.core.common;

import com.google.cloud.secretmanager.v1.GetSecretRequest;
import com.google.cloud.secretmanager.v1.SecretManagerServiceGrpc;
import com.google.firestore.v1.CommitRequest;
import com.google.firestore.v1.Document;
import com.google.firestore.v1.FirestoreGrpc;
import com.google.firestore.v1.Write;
import com.google.pubsub.v1.GetTopicRequest;
import com.google.pubsub.v1.PublisherGrpc;
import com.google.pubsub.v1.StreamingPullRequest;
import com.google.pubsub.v1.StreamingPullResponse;
import com.google.pubsub.v1.SubscriberGrpc;
import com.google.pubsub.v1.Topic;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(GrpcProjectStrictIntegrationTest.StrictProjectsProfile.class)
class GrpcProjectStrictIntegrationTest {

    public static class StrictProjectsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.projects.strict", "true");
        }
    }

    private static final String UNKNOWN = "grpc-strict-unknown";

    @TestHTTPResource
    URI endpoint;

    private ManagedChannel channel;

    @BeforeEach
    void openChannel() {
        channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
    }

    @AfterEach
    void closeChannel() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    private PublisherGrpc.PublisherBlockingStub publisher() {
        return PublisherGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    private static void assertPermissionDenied(StatusRuntimeException error) {
        assertEquals(Status.Code.PERMISSION_DENIED, error.getStatus().getCode());
        assertEquals(ProjectRegistry.PERMISSION_DENIED_MESSAGE, error.getStatus().getDescription());
    }

    @Test
    void pubSubRejectsAnUnknownProjectIdAndKeepsNothing() {
        Topic topic = Topic.newBuilder().setName("projects/" + UNKNOWN + "/topics/t").build();

        assertPermissionDenied(assertThrows(StatusRuntimeException.class, () -> publisher().createTopic(topic)));
        given().when().get("/v1/projects/" + UNKNOWN).then().statusCode(403);
    }

    @Test
    void pubSubRejectsAnUnknownProjectNumber() {
        Topic topic = Topic.newBuilder().setName("projects/990000000149/topics/t").build();

        assertPermissionDenied(assertThrows(StatusRuntimeException.class, () -> publisher().createTopic(topic)));
    }

    @Test
    void secretManagerRejectsAnUnknownProject() {
        GetSecretRequest request = GetSecretRequest.newBuilder()
                .setName("projects/" + UNKNOWN + "/secrets/s").build();

        assertPermissionDenied(assertThrows(StatusRuntimeException.class,
                () -> SecretManagerServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
                        .getSecret(request)));
    }

    @Test
    void aStreamingCallIsRejectedOnItsFirstMessage() throws Exception {
        CompletableFuture<Throwable> closed = new CompletableFuture<>();
        StreamObserver<StreamingPullRequest> requests = SubscriberGrpc.newStub(channel)
                .streamingPull(new StreamObserver<StreamingPullResponse>() {
                    @Override
                    public void onNext(StreamingPullResponse response) {
                        closed.completeExceptionally(new AssertionError("Unexpected response"));
                    }

                    @Override
                    public void onError(Throwable t) {
                        closed.complete(t);
                    }

                    @Override
                    public void onCompleted() {
                        closed.completeExceptionally(new AssertionError("Unexpected completion"));
                    }
                });
        requests.onNext(StreamingPullRequest.newBuilder()
                .setSubscription("projects/" + UNKNOWN + "/subscriptions/s")
                .setStreamAckDeadlineSeconds(10)
                .build());

        Throwable error = closed.get(10, TimeUnit.SECONDS);
        assertEquals(Status.Code.PERMISSION_DENIED, Status.fromThrowable(error).getCode());
    }

    @Test
    void theRoutingHeaderIsCheckedWhenTheMessageNamesNoProject() {
        Metadata headers = new Metadata();
        headers.put(RequestTarget.ROUTING_HEADER, "topic=projects%2F" + UNKNOWN + "%2Ftopics%2Ft");
        PublisherGrpc.PublisherBlockingStub routed = publisher()
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        assertPermissionDenied(assertThrows(StatusRuntimeException.class,
                () -> routed.getTopic(GetTopicRequest.getDefaultInstance())));
    }

    @Test
    void aCreatedProjectIsServedByIdAndByNumber() {
        String project = "grpc-strict-created";
        given().contentType("application/json")
                .body("{\"projectId\":\"" + project + "\",\"name\":\"Created\"}")
                .when().post("/v1/projects")
                .then().statusCode(200);

        String name = "projects/" + project + "/topics/t";
        assertEquals(name, publisher().createTopic(Topic.newBuilder().setName(name).build()).getName());

        assertEquals(name, publisher().getTopic(GetTopicRequest.newBuilder()
                .setTopic("projects/" + ProjectNumbers.of(project) + "/topics/t").build()).getName());
    }

    @Test
    void theDefaultProjectIsServed() {
        String name = "projects/test-project/topics/grpc-strict-default";

        assertEquals(name, publisher().createTopic(Topic.newBuilder().setName(name).build()).getName());
    }

    @Test
    void aFirestoreCommitWithoutARoutingHeaderIsCheckedByItsDatabase() {
        String database = "projects/" + UNKNOWN + "/databases/(default)";
        StatusRuntimeException error = assertThrows(StatusRuntimeException.class,
                () -> FirestoreGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
                        .commit(CommitRequest.newBuilder().setDatabase(database)
                                .addWrites(Write.newBuilder().setUpdate(Document.newBuilder()
                                        .setName(database + "/documents/c/d")))
                                .build()));
        assertPermissionDenied(error);
    }
}
