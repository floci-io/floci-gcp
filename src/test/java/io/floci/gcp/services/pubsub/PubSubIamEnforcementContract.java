package io.floci.gcp.services.pubsub;

import com.google.iam.v1.Binding;
import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.IAMPolicyGrpc;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import com.google.iam.v1.TestIamPermissionsRequest;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.CreateSnapshotRequest;
import com.google.pubsub.v1.DeleteSubscriptionRequest;
import com.google.pubsub.v1.DeleteTopicRequest;
import com.google.pubsub.v1.DetachSubscriptionRequest;
import com.google.pubsub.v1.GetSubscriptionRequest;
import com.google.pubsub.v1.GetTopicRequest;
import com.google.pubsub.v1.ModifyAckDeadlineRequest;
import com.google.pubsub.v1.ModifyPushConfigRequest;
import com.google.pubsub.v1.PublisherGrpc;
import com.google.pubsub.v1.PublishRequest;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.PushConfig;
import com.google.pubsub.v1.PullRequest;
import com.google.pubsub.v1.SeekRequest;
import com.google.pubsub.v1.SubscriberGrpc;
import com.google.pubsub.v1.Subscription;
import com.google.pubsub.v1.StreamingPullRequest;
import com.google.pubsub.v1.StreamingPullResponse;
import com.google.pubsub.v1.Topic;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class PubSubIamEnforcementContract {
    @Inject
    PubSubService service;
    @Inject
    IamService policies;
    @Inject
    CredentialTokenService tokens;
    @TestHTTPResource
    URI endpoint;

    String project;
    String member;
    String credential;
    ManagedChannel channel;
    Channel caller;

    abstract boolean enforced();

    @BeforeEach
    void setup() {
        project = "projects/pubsub-iam-" + UUID.randomUUID().toString().substring(0, 8);
        String email = "reader@" + project.substring("projects/".length()) + ".iam.gserviceaccount.com";
        member = "serviceAccount:" + email;
        credential = "Bearer " + tokens.mintImpersonatedToken(email, Instant.now().plusSeconds(300)).getTokenValue();
        channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), credential);
        caller = ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    @AfterEach
    void closeChannel() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void projectPolicyConditionScopesTopicCreationOnRestAndGrpcPaths() {
        String allowed = project + "/topics/allowed";
        String excluded = project + "/topics/excluded";
        grant(project, "roles/pubsub.admin", "resource.name == '" + allowed + "'");

        given().header("Authorization", credential).contentType("application/json").body(Map.of())
                .put("/v1/" + allowed).then().statusCode(200);
        PublisherGrpc.PublisherBlockingStub publisher = PublisherGrpc.newBlockingStub(caller)
                .withDeadlineAfter(5, TimeUnit.SECONDS);
        publisher.getTopic(GetTopicRequest.newBuilder().setTopic(allowed).build());

        if (enforced()) {
            given().header("Authorization", credential).contentType("application/json").body(Map.of())
                    .put("/v1/" + excluded).then().statusCode(403)
                    .body("error.status", equalTo("PERMISSION_DENIED"));
            StatusRuntimeException error = assertThrows(StatusRuntimeException.class,
                    () -> publisher.createTopic(Topic.newBuilder().setName(excluded).build()));
            assertEquals(Status.Code.PERMISSION_DENIED, error.getStatus().getCode());
            assertThrows(GcpException.class, () -> service.getTopic(excluded));
        } else {
            publisher.createTopic(Topic.newBuilder().setName(excluded).build());
        }
    }

    @Test
    void subscriberCannotReadOrConsumeAnIndependentlyScopedSubscription() {
        String topic = project + "/topics/source";
        String first = project + "/subscriptions/first";
        String second = project + "/subscriptions/second";
        service.createTopic(topic);
        service.createSubscription(first, topic, 10);
        service.createSubscription(second, topic, 10);
        grant(first, "roles/pubsub.subscriber", null);

        SubscriberGrpc.SubscriberBlockingStub subscriber = SubscriberGrpc.newBlockingStub(caller)
                .withDeadlineAfter(5, TimeUnit.SECONDS);
        subscriber.pull(PullRequest.newBuilder().setSubscription(first).setMaxMessages(1).build());
        given().urlEncodingEnabled(false).header("Authorization", credential).contentType("application/json")
                .body(Map.of("maxMessages", 1)).post("/v1/" + first + ":pull")
                .then().statusCode(200);
        if (enforced()) {
            StatusRuntimeException error = assertThrows(StatusRuntimeException.class,
                    () -> subscriber.pull(PullRequest.newBuilder().setSubscription(second).setMaxMessages(1).build()));
            assertEquals(Status.Code.PERMISSION_DENIED, error.getStatus().getCode());
            given().urlEncodingEnabled(false).header("Authorization", credential).contentType("application/json")
                    .body(Map.of("maxMessages", 1)).post("/v1/" + second + ":pull")
                    .then().statusCode(403).body("error.status", equalTo("PERMISSION_DENIED"));
            given().urlEncodingEnabled(false).header("Authorization", credential).contentType("application/json")
                    .body(Map.of("ackIds", List.of("unknown"), "ackDeadlineSeconds", 10))
                    .post("/v1/" + second + ":modifyAckDeadline")
                    .then().statusCode(403).body("error.status", equalTo("PERMISSION_DENIED"));
        } else {
            subscriber.pull(PullRequest.newBuilder().setSubscription(second).setMaxMessages(1).build());
            given().urlEncodingEnabled(false).header("Authorization", credential).contentType("application/json")
                    .body(Map.of("maxMessages", 1)).post("/v1/" + second + ":pull")
                    .then().statusCode(200);
        }

        List<String> requested = List.of("pubsub.subscriptions.consume", "pubsub.subscriptions.delete");
        List<String> expected = enforced() ? List.of("pubsub.subscriptions.consume") : requested;
        assertEquals(expected, IAMPolicyGrpc.newBlockingStub(caller).withDeadlineAfter(5, TimeUnit.SECONDS)
                .testIamPermissions(TestIamPermissionsRequest.newBuilder().setResource(first)
                        .addAllPermissions(requested).build()).getPermissionsList());
        given().urlEncodingEnabled(false).header("Authorization", credential).contentType("application/json")
                .body(Map.of("permissions", requested))
                .post("/v1/" + first + ":testIamPermissions")
                .then().statusCode(200).body("permissions", equalTo(expected));
    }

    @Test
    void subscriptionCreationRequiresParentAndTopicPermissions() {
        String topic = project + "/topics/source";
        String subscription = project + "/subscriptions/new";
        service.createTopic(topic);
        grant(project, "roles/pubsub.admin", "resource.name == '" + project + "'");

        SubscriberGrpc.SubscriberBlockingStub subscriber = SubscriberGrpc.newBlockingStub(caller)
                .withDeadlineAfter(5, TimeUnit.SECONDS);
        Subscription request = Subscription.newBuilder().setName(subscription).setTopic(topic).build();
        if (enforced()) {
            StatusRuntimeException error = assertThrows(StatusRuntimeException.class,
                    () -> subscriber.createSubscription(request));
            assertEquals(Status.Code.PERMISSION_DENIED, error.getStatus().getCode());
            given().header("Authorization", credential).contentType("application/json")
                    .body(Map.of("topic", topic)).put("/v1/" + subscription)
                    .then().statusCode(403);
            assertThrows(GcpException.class,
                    () -> service.getSubscription(subscription));
        }
        grant(topic, "roles/pubsub.subscriber", null);
        subscriber.createSubscription(request);
        service.getSubscription(subscription);
        if (enforced()) {
            assertEquals(Status.Code.PERMISSION_DENIED, assertThrows(StatusRuntimeException.class,
                    () -> subscriber.getSubscription(GetSubscriptionRequest.newBuilder()
                            .setSubscription(subscription).build())).getStatus().getCode());
        }
    }

    @Test
    void publisherAndSubscriberRolesAllowMessagingButCannotDeleteResources() {
        String topic = project + "/topics/messaging";
        String subscription = project + "/subscriptions/messaging";
        service.createTopic(topic);
        service.createSubscription(subscription, topic, 10);
        grant(topic, "roles/pubsub.publisher", null);
        grant(subscription, "roles/pubsub.subscriber", null);

        PublisherGrpc.PublisherBlockingStub publisher = PublisherGrpc.newBlockingStub(caller)
                .withDeadlineAfter(5, TimeUnit.SECONDS);
        publisher.publish(PublishRequest.newBuilder().setTopic(topic).addMessages(
                PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("message"))).build());
        given().urlEncodingEnabled(false).header("Authorization", credential)
                .contentType("application/json")
                .body(Map.of("messages", List.of(Map.of("data", "bWVzc2FnZQ=="))))
                .post("/v1/" + topic + ":publish").then().statusCode(200);

        if (enforced()) {
            StatusRuntimeException topicError = assertThrows(StatusRuntimeException.class,
                    () -> publisher.deleteTopic(DeleteTopicRequest.newBuilder().setTopic(topic).build()));
            assertEquals(Status.Code.PERMISSION_DENIED, topicError.getStatus().getCode());
            given().header("Authorization", credential).delete("/v1/" + topic)
                    .then().statusCode(403).body("error.status", equalTo("PERMISSION_DENIED"));
            service.getTopic(topic);

            SubscriberGrpc.SubscriberBlockingStub subscriber = SubscriberGrpc.newBlockingStub(caller)
                    .withDeadlineAfter(5, TimeUnit.SECONDS);
            StatusRuntimeException subscriptionError = assertThrows(StatusRuntimeException.class,
                    () -> subscriber.deleteSubscription(DeleteSubscriptionRequest.newBuilder()
                            .setSubscription(subscription).build()));
            assertEquals(Status.Code.PERMISSION_DENIED, subscriptionError.getStatus().getCode());
            given().header("Authorization", credential).delete("/v1/" + subscription)
                    .then().statusCode(403).body("error.status", equalTo("PERMISSION_DENIED"));
            service.getSubscription(subscription);
        }
    }

    @Test
    void snapshotCreationRequiresProjectAndSourceSubscriptionPermissions() {
        String topic = project + "/topics/snapshots";
        String subscription = project + "/subscriptions/snapshots";
        String snapshot = project + "/snapshots/point-in-time";
        service.createTopic(topic);
        service.createSubscription(subscription, topic, 10);
        grant(project, "roles/pubsub.admin", "resource.name == '" + project + "'");

        SubscriberGrpc.SubscriberBlockingStub subscriber = SubscriberGrpc.newBlockingStub(caller)
                .withDeadlineAfter(5, TimeUnit.SECONDS);
        CreateSnapshotRequest request = CreateSnapshotRequest.newBuilder()
                .setName(snapshot).setSubscription(subscription).build();
        if (enforced()) {
            StatusRuntimeException error = assertThrows(StatusRuntimeException.class,
                    () -> subscriber.createSnapshot(request));
            assertEquals(Status.Code.PERMISSION_DENIED, error.getStatus().getCode());
            assertThrows(GcpException.class, () -> service.getSnapshot(snapshot));
        }
        grant(subscription, "roles/pubsub.subscriber", null);
        assertEquals(snapshot, subscriber.createSnapshot(request).getName());
    }

    @Test
    void detachingSubscriptionRequiresPermissionOnItsTopic() {
        String topic = project + "/topics/detach";
        String subscription = project + "/subscriptions/detach";
        service.createTopic(topic);
        service.createSubscription(subscription, topic, 10);
        grant(subscription, "roles/pubsub.admin", null);
        PublisherGrpc.PublisherBlockingStub publisher = PublisherGrpc.newBlockingStub(caller)
                .withDeadlineAfter(5, TimeUnit.SECONDS);
        DetachSubscriptionRequest request = DetachSubscriptionRequest.newBuilder()
                .setSubscription(subscription).build();

        if (enforced()) {
            assertEquals(Status.Code.PERMISSION_DENIED, assertThrows(StatusRuntimeException.class,
                    () -> publisher.detachSubscription(request)).getStatus().getCode());
            assertFalse(service.getSubscription(subscription).isDetached());
        }
        grant(topic, "roles/pubsub.admin", null);
        publisher.detachSubscription(request);
        assertTrue(service.getSubscription(subscription).isDetached());
    }

    @Test
    void seekingToSnapshotRequiresPermissionOnSubscriptionAndSnapshot() {
        String topic = project + "/topics/seek";
        String subscription = project + "/subscriptions/seek";
        String snapshot = project + "/snapshots/seek";
        service.createTopic(topic);
        service.createSubscription(subscription, topic, 10);
        service.createSnapshot(snapshot, subscription, null);
        grant(subscription, "roles/pubsub.subscriber", null);
        SubscriberGrpc.SubscriberBlockingStub subscriber = SubscriberGrpc.newBlockingStub(caller)
                .withDeadlineAfter(5, TimeUnit.SECONDS);
        SeekRequest request = SeekRequest.newBuilder().setSubscription(subscription)
                .setSnapshot(snapshot).build();

        if (enforced()) {
            assertEquals(Status.Code.PERMISSION_DENIED, assertThrows(StatusRuntimeException.class,
                    () -> subscriber.seek(request)).getStatus().getCode());
            given().urlEncodingEnabled(false).header("Authorization", credential).contentType("application/json")
                    .body(Map.of("snapshot", snapshot)).post("/v1/" + subscription + ":seek")
                    .then().statusCode(403).body("error.status", equalTo("PERMISSION_DENIED"));
        }
        grant(snapshot, "roles/pubsub.subscriber", null);
        subscriber.seek(request);
        given().urlEncodingEnabled(false).header("Authorization", credential).contentType("application/json")
                .body(Map.of("snapshot", snapshot)).post("/v1/" + subscription + ":seek")
                .then().statusCode(200);
    }

    @Test
    void basicViewerCanSeekToSnapshotButPubsubViewerCannot() {
        String topic = project + "/topics/viewer-seek";
        String subscription = project + "/subscriptions/viewer-seek";
        String snapshot = project + "/snapshots/viewer-seek";
        service.createTopic(topic);
        service.createSubscription(subscription, topic, 10);
        service.createSnapshot(snapshot, subscription, null);
        grant(subscription, "roles/pubsub.subscriber", null);
        SubscriberGrpc.SubscriberBlockingStub subscriber = SubscriberGrpc.newBlockingStub(caller)
                .withDeadlineAfter(5, TimeUnit.SECONDS);
        SeekRequest request = SeekRequest.newBuilder().setSubscription(subscription)
                .setSnapshot(snapshot).build();

        grant(project, "roles/viewer", null);
        subscriber.seek(request);

        grant(project, "roles/pubsub.viewer", null);
        if (enforced()) {
            assertEquals(Status.Code.PERMISSION_DENIED, assertThrows(StatusRuntimeException.class,
                    () -> subscriber.seek(request)).getStatus().getCode());
        } else {
            subscriber.seek(request);
        }
    }

    @Test
    void pushConfigAndAckDeadlineUseDifferentSubscriptionPermissions() {
        String topic = project + "/topics/config";
        String subscription = project + "/subscriptions/config";
        service.createTopic(topic);
        service.createSubscription(subscription, topic, 10);
        grant(subscription, "roles/pubsub.subscriber", null);
        SubscriberGrpc.SubscriberBlockingStub subscriber = SubscriberGrpc.newBlockingStub(caller)
                .withDeadlineAfter(5, TimeUnit.SECONDS);
        service.publish(topic, List.of(PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8("lease")).build()));
        String ackId = subscriber.pull(PullRequest.newBuilder().setSubscription(subscription)
                .setMaxMessages(1).build()).getReceivedMessages(0).getAckId();
        ModifyPushConfigRequest pushRequest = ModifyPushConfigRequest.newBuilder()
                .setSubscription(subscription)
                .setPushConfig(PushConfig.newBuilder().setPushEndpoint("https://example.com/push"))
                .build();
        ModifyAckDeadlineRequest ackRequest = ModifyAckDeadlineRequest.newBuilder()
                .setSubscription(subscription).addAckIds(ackId).setAckDeadlineSeconds(10).build();

        subscriber.modifyAckDeadline(ackRequest);
        given().urlEncodingEnabled(false).header("Authorization", credential).contentType("application/json")
                .body(Map.of("ackIds", List.of(ackId), "ackDeadlineSeconds", 10))
                .post("/v1/" + subscription + ":modifyAckDeadline").then().statusCode(200);
        if (enforced()) {
            assertEquals(Status.Code.PERMISSION_DENIED, assertThrows(StatusRuntimeException.class,
                    () -> subscriber.modifyPushConfig(pushRequest)).getStatus().getCode());
            assertNull(service.getSubscription(subscription).getPushEndpoint());
        }
        grant(subscription, "roles/pubsub.admin", null);
        subscriber.modifyPushConfig(pushRequest);
        assertEquals("https://example.com/push", service.getSubscription(subscription).getPushEndpoint());
    }

    @Test
    void topicPolicyMethodsRespectTopicScopeAndDeniedWriteDoesNotMutatePolicy() {
        String allowed = project + "/topics/allowed-policy";
        String excluded = project + "/topics/excluded-policy";
        service.createTopic(allowed);
        service.createTopic(excluded);
        grant(project, "roles/pubsub.admin", "resource.name == '" + allowed + "'");

        IAMPolicyGrpc.IAMPolicyBlockingStub iam = IAMPolicyGrpc.newBlockingStub(caller)
                .withDeadlineAfter(5, TimeUnit.SECONDS);
        Policy updated = Policy.newBuilder().addBindings(Binding.newBuilder()
                .setRole("roles/pubsub.publisher").addMembers(member)).build();
        iam.setIamPolicy(SetIamPolicyRequest.newBuilder().setResource(allowed).setPolicy(updated).build());
        assertEquals("roles/pubsub.publisher", iam.getIamPolicy(GetIamPolicyRequest.newBuilder()
                .setResource(allowed).build()).getBindings(0).getRole());
        given().urlEncodingEnabled(false).header("Authorization", credential)
                .get("/v1/" + allowed + ":getIamPolicy")
                .then().statusCode(200).body("bindings[0].role", equalTo("roles/pubsub.publisher"));
        given().urlEncodingEnabled(false).header("Authorization", credential)
                .contentType("application/json")
                .body(Map.of("policy", Map.of("bindings", List.of(Map.of(
                        "role", "roles/pubsub.publisher", "members", List.of(member))))))
                .post("/v1/" + allowed + ":setIamPolicy")
                .then().statusCode(200);

        if (enforced()) {
            StatusRuntimeException error = assertThrows(StatusRuntimeException.class,
                    () -> iam.setIamPolicy(SetIamPolicyRequest.newBuilder()
                            .setResource(excluded).setPolicy(updated).build()));
            assertEquals(Status.Code.PERMISSION_DENIED, error.getStatus().getCode());
            given().urlEncodingEnabled(false).header("Authorization", credential)
                    .contentType("application/json")
                    .body(Map.of("policy", Map.of("bindings", List.of(Map.of(
                            "role", "roles/pubsub.publisher", "members", List.of(member))))))
                    .post("/v1/" + excluded + ":setIamPolicy")
                    .then().statusCode(403).body("error.status", equalTo("PERMISSION_DENIED"));
            assertEquals(0, policies.getPolicy(excluded).getBindings().size());
        } else {
            iam.setIamPolicy(SetIamPolicyRequest.newBuilder().setResource(excluded).setPolicy(updated).build());
        }
    }

    @Test
    void streamingPullRejectsRepeatedSubscriptionAfterAnAuthorizedFirstMessage() throws InterruptedException {
        if (!enforced()) {
            return;
        }
        String topic = project + "/topics/stream";
        String first = project + "/subscriptions/stream-first";
        service.createTopic(topic);
        service.createSubscription(first, topic, 10);
        grant(first, "roles/pubsub.subscriber", null);

        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        StreamObserver<StreamingPullResponse> responses = new StreamObserver<>() {
            @Override
            public void onNext(StreamingPullResponse response) {
            }

            @Override
            public void onError(Throwable failure) {
                error.set(failure);
                closed.countDown();
            }

            @Override
            public void onCompleted() {
                closed.countDown();
            }
        };
        StreamObserver<StreamingPullRequest> requests = SubscriberGrpc.newStub(caller)
                .withDeadlineAfter(5, TimeUnit.SECONDS).streamingPull(responses);
        requests.onNext(StreamingPullRequest.newBuilder().setSubscription(first).build());
        requests.onNext(StreamingPullRequest.getDefaultInstance());
        requests.onNext(StreamingPullRequest.newBuilder().setSubscription(first).build());
        assertTrue(closed.await(5, TimeUnit.SECONDS));
        assertEquals(Status.Code.INVALID_ARGUMENT, Status.fromThrowable(error.get()).getCode());
    }

    @Test
    void streamingPullDeniesFirstMessageWithoutConsumingQueuedMessages() throws InterruptedException {
        if (!enforced()) {
            return;
        }
        String topic = project + "/topics/stream-denied";
        String subscription = project + "/subscriptions/stream-denied";
        service.createTopic(topic);
        service.createSubscription(subscription, topic, 10);
        service.publish(topic, List.of(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("queued")).build()));

        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        StreamObserver<StreamingPullRequest> requests = streamingRequests(closed, error);
        requests.onNext(StreamingPullRequest.newBuilder().setSubscription(subscription).build());

        assertTrue(closed.await(5, TimeUnit.SECONDS));
        assertEquals(Status.Code.PERMISSION_DENIED, Status.fromThrowable(error.get()).getCode());
        assertEquals(1, service.pull(subscription, 1).size());
    }

    @Test
    void streamingPullChecksEachMessageAfterPolicyRevocation() throws InterruptedException {
        if (!enforced()) {
            return;
        }
        String topic = project + "/topics/stream-revoked";
        String subscription = project + "/subscriptions/stream-revoked";
        service.createTopic(topic);
        service.createSubscription(subscription, topic, 10);
        grant(subscription, "roles/pubsub.subscriber", null);
        List<PubsubMessage> messages = new ArrayList<>();
        PubsubMessage initial = PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("initial")).build();
        for (int i = 0; i < 1000; i++) {
            messages.add(initial);
        }
        messages.add(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("queued")).build());
        service.publish(topic, messages);

        CountDownLatch closed = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        StreamObserver<StreamingPullRequest> requests = streamingRequests(closed, error, delivered);
        requests.onNext(StreamingPullRequest.newBuilder().setSubscription(subscription).build());
        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        policies.setPolicy(subscription, new StoredPolicy());
        requests.onNext(StreamingPullRequest.getDefaultInstance());

        assertTrue(closed.await(5, TimeUnit.SECONDS));
        assertEquals(Status.Code.PERMISSION_DENIED, Status.fromThrowable(error.get()).getCode());
        assertEquals("queued", service.pull(subscription, 1).getFirst().getMessage().getData().toStringUtf8());
    }

    @Test
    void streamingPullStopsListenerDeliveryAfterPolicyRevocation() throws InterruptedException {
        if (!enforced()) {
            return;
        }
        String topic = project + "/topics/listener-revoked";
        String subscription = project + "/subscriptions/listener-revoked";
        service.createTopic(topic);
        service.createSubscription(subscription, topic, 10);
        grant(subscription, "roles/pubsub.subscriber", null);
        service.publish(topic, List.of(PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8("initial")).build()));

        CountDownLatch closed = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        StreamObserver<StreamingPullRequest> requests = streamingRequests(closed, error, delivered);
        requests.onNext(StreamingPullRequest.newBuilder().setSubscription(subscription).build());
        assertTrue(delivered.await(5, TimeUnit.SECONDS));

        policies.setPolicy(subscription, new StoredPolicy());
        service.publish(topic, List.of(PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8("queued")).build()));

        assertTrue(closed.await(5, TimeUnit.SECONDS));
        assertEquals(Status.Code.PERMISSION_DENIED, Status.fromThrowable(error.get()).getCode());
        assertEquals("queued", service.pull(subscription, 1).getFirst().getMessage().getData().toStringUtf8());
    }

    private StreamObserver<StreamingPullRequest> streamingRequests(CountDownLatch closed,
            AtomicReference<Throwable> error) {
        return streamingRequests(closed, error, null);
    }

    private StreamObserver<StreamingPullRequest> streamingRequests(CountDownLatch closed,
            AtomicReference<Throwable> error, CountDownLatch delivered) {
        StreamObserver<StreamingPullResponse> responses = new StreamObserver<>() {
            @Override
            public void onNext(StreamingPullResponse response) {
                if (delivered != null) {
                    delivered.countDown();
                }
            }

            @Override
            public void onError(Throwable failure) {
                error.set(failure);
                closed.countDown();
            }

            @Override
            public void onCompleted() {
                closed.countDown();
            }
        };
        return SubscriberGrpc.newStub(caller).withDeadlineAfter(5, TimeUnit.SECONDS).streamingPull(responses);
    }

    private void grant(String resource, String role, String expression) {
        StoredPolicy policy = new StoredPolicy();
        if (expression == null) {
            policy.setBindings(List.of(Map.of("role", role, "members", List.of(member))));
        } else {
            policy.setVersion(3);
            policy.setBindings(List.of(Map.of("role", role, "members", List.of(member),
                    "condition", Map.of("title", "scope", "expression", expression))));
        }
        policies.setPolicy(resource, policy);
    }
}
