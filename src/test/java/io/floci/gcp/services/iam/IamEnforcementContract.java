package io.floci.gcp.services.iam;

import com.google.cloud.secretmanager.v1.AccessSecretVersionRequest;
import com.google.cloud.secretmanager.v1.GetSecretVersionRequest;
import com.google.cloud.secretmanager.v1.SecretManagerServiceGrpc;
import com.google.iam.v1.Binding;
import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.IAMPolicyGrpc;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import com.google.iam.v1.TestIamPermissionsRequest;
import com.google.protobuf.ByteString;
import com.google.protobuf.FieldMask;
import com.google.pubsub.v1.CreateSnapshotRequest;
import com.google.pubsub.v1.GetSnapshotRequest;
import com.google.pubsub.v1.GetSubscriptionRequest;
import com.google.pubsub.v1.GetTopicRequest;
import com.google.pubsub.v1.PublishRequest;
import com.google.pubsub.v1.PublisherGrpc;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.PullRequest;
import com.google.pubsub.v1.Snapshot;
import com.google.pubsub.v1.StreamingPullRequest;
import com.google.pubsub.v1.StreamingPullResponse;
import com.google.pubsub.v1.SubscriberGrpc;
import com.google.pubsub.v1.UpdateSnapshotRequest;
import io.floci.gcp.services.credentials.CredentialAccessBoundaryRule;
import io.floci.gcp.services.credentials.CredentialTokenService;
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
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

/** Same wire requests run with enforcement on and off, so bypass behavior is a tested contract. */
abstract class IamEnforcementContract {
    enum Surface { TOPIC, SUBSCRIPTION, SECRET }

    @Inject
    CredentialTokenService tokens;
    @TestHTTPResource
    URI endpoint;

    String project;
    String member;
    String credential;
    ManagedChannel channel;
    Channel authenticated;

    abstract boolean enforced();

    @BeforeEach
    void setup() {
        project = "projects/iam-" + UUID.randomUUID().toString().substring(0, 8);
        String email = "reader@" + project.substring("projects/".length()) + ".iam.gserviceaccount.com";
        member = "serviceAccount:" + email;
        credential = "Bearer " + tokens.mintImpersonatedToken(email, Instant.now().plusSeconds(300)).getTokenValue();
        channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), credential);
        authenticated = ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    @AfterEach
    void closeChannel() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @EnumSource(Surface.class)
    void narrowResourceGrantAllowsOnlyItsPermissionAndResourceOnBothTransports(Surface surface) {
        String allowed = create(surface, "allowed");
        String sibling = create(surface, "sibling");
        grant(allowed, role(surface), null);
        assertAllowed(surface, allowed);
        assertForbiddenPermission(surface, allowed);
        assertDenied(surface, sibling);
        assertTestPermissions(surface, allowed);
    }

    @ParameterizedTest
    @EnumSource(Surface.class)
    void projectGrantInheritsOnlyInsideItsProjectAndHonorsChildCondition(Surface surface) {
        String allowed = create(surface, "allowed");
        String excluded = create(surface, "excluded");
        String condition = "resource.name == '" + operationResource(surface, allowed) + "'";
        grant(project, role(surface), condition);
        assertAllowed(surface, allowed);
        assertDenied(surface, excluded);
        grant(project, role(surface), null);
        assertAllowed(surface, excluded);
        String original = project;
        project = original + "-other";
        String foreign = create(surface, "foreign");
        project = original;
        assertDenied(surface, foreign);
    }

    @ParameterizedTest
    @EnumSource(Surface.class)
    void resourceConditionCanExcludeTheGrantedResource(Surface surface) {
        String resource = create(surface, "conditional");
        grant(resource, role(surface), "resource.name == 'projects/elsewhere/never'");
        assertDenied(surface, resource);
        grant(resource, role(surface), "resource.name == '" + operationResource(surface, resource) + "'");
        assertAllowed(surface, resource);
    }

    enum Denial { OTHER_PERMISSION, SIBLING, CONDITION }

    static Stream<Arguments> denialCases() {
        return Stream.of(Surface.values()).flatMap(surface -> Stream.of(Denial.values())
                .flatMap(denial -> Stream.of(false, true).map(grpc -> Arguments.of(surface, denial, grpc))));
    }

    @ParameterizedTest
    @MethodSource("denialCases")
    void eachNegativeHasAnIndependentTransportAssertion(Surface surface, Denial denial, boolean grpc) {
        String allowed = create(surface, "allowed");
        String target = denial == Denial.SIBLING ? create(surface, "sibling") : allowed;
        grant(allowed, role(surface), denial == Denial.CONDITION ? "resource.name == 'excluded'" : null);
        if (denial == Denial.OTHER_PERMISSION) {
            if (grpc) {
                grpcDenied(() -> grpcForbiddenPermission(surface, target));
            } else {
                assertRestDenied(request().header("Authorization", credential)
                        .get("/v1/" + operationResource(surface, target)));
            }
        } else if (grpc) {
            grpcDenied(() -> grpcAllowed(surface, target));
        } else {
            assertRestDenied(restAllowed(surface, target));
        }
    }

    @ParameterizedTest
    @EnumSource(Denial.class)
    void eachProjectNegativeHasAnIndependentAssertion(Denial denial) {
        grant(project, "roles/browser", denial == Denial.CONDITION ? "resource.name == 'excluded'" : null);
        Response response = denial == Denial.OTHER_PERMISSION
                ? request().header("Authorization", credential).contentType("application/json")
                        .body(Map.of("policy", policy("roles/browser", null))).post("/v1/" + project + ":setIamPolicy")
                : request().header("Authorization", credential).get("/v1/" + project + (denial == Denial.SIBLING ? "-other" : ""));
        assertRestDenied(response);
    }

    @Test
    void streamingPullRejectsBeforeConsumingMessages() throws InterruptedException {
        String subscription = create(Surface.SUBSCRIPTION, "stream");
        String topic = project + "/topics/source-stream";
        PublisherGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
                .publish(PublishRequest.newBuilder().setTopic(topic)
                        .addMessages(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("stream"))).build());
        CountDownLatch event = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        StreamObserver<StreamingPullRequest> stream = SubscriberGrpc.newStub(authenticated)
                .withDeadlineAfter(5, TimeUnit.SECONDS).streamingPull(new StreamObserver<>() {
                    @Override
                    public void onNext(StreamingPullResponse response) {
                        event.countDown();
                    }
                    @Override
                    public void onError(Throwable error) {
                        failure.set(error);
                        event.countDown();
                    }
                    @Override
                    public void onCompleted() {
                        event.countDown();
                    }
                });
        stream.onNext(StreamingPullRequest.newBuilder().setSubscription(subscription).setStreamAckDeadlineSeconds(10).build());
        assertTrue(event.await(5, TimeUnit.SECONDS), "stream must deliver or reject");
        if (enforced()) {
            assertNotNull(failure.get(), "unbound subscriber must receive a gRPC denial");
            assertEquals(Status.Code.PERMISSION_DENIED, Status.fromThrowable(failure.get()).getCode());
        } else {
            assertNull(failure.get());
        }
        stream.onCompleted();
        grant(subscription, "roles/pubsub.subscriber", null);
        // The rejected stream must not have consumed the message.
        if (enforced()) {
            assertEquals(1, SubscriberGrpc.newBlockingStub(authenticated).withDeadlineAfter(5, TimeUnit.SECONDS)
                    .pull(PullRequest.newBuilder().setSubscription(subscription).setMaxMessages(1).build()).getReceivedMessagesCount());
        }
    }

    @Test
    void subscriptionCreationRequiresBothProjectAndSourceTopicPermissions() {
        String topicProject = project + "-source";
        String topic = topicProject + "/topics/source";
        request().contentType("application/json").body(Map.of()).put("/v1/" + topic).then().statusCode(200);
        grant(project, "roles/pubsub.editor", null);
        String subscription = project + "/subscriptions/cross-project";
        Response result = request().header("Authorization", credential).contentType("application/json")
                .body(Map.of("topic", topic)).put("/v1/" + subscription);
        assertRestDenied(result);
        if (enforced()) {
            request().get("/v1/" + subscription).then().statusCode(404);
        }
        grant(topic, "roles/pubsub.subscriber", null);
        request().header("Authorization", credential).contentType("application/json")
                .body(Map.of("topic", topic)).put("/v1/" + subscription + "-granted").then().statusCode(200);
    }

    @Test
    void projectMetadataAndPoliciesEnforceNarrowRoleAndCondition() {
        grant(project, "roles/browser", null);
        request().header("Authorization", credential).get("/v1/" + project).then().statusCode(200);
        Response denied = request().header("Authorization", credential).contentType("application/json")
                .body(Map.of("policy", policy("roles/browser", null)))
                .post("/v1/" + project + ":setIamPolicy");
        assertRestDenied(denied);
        assertRestDenied(request().header("Authorization", credential).get("/v1/" + project + "-sibling"));
        request().header("Authorization", credential).contentType("application/json")
                .body(Map.of("permissions", List.of("resourcemanager.projects.get", "resourcemanager.projects.setIamPolicy")))
                .post("/v1/" + project + ":testIamPermissions").then().statusCode(200)
                .body("permissions", equalTo(enforced() ? List.of("resourcemanager.projects.get")
                        : List.of("resourcemanager.projects.get", "resourcemanager.projects.setIamPolicy")));
        grant(project, "roles/browser", "resource.name == 'projects/excluded'");
        assertRestDenied(request().header("Authorization", credential).get("/v1/" + project));
    }

    @Test
    void snapshotsEnforceNarrowGrantsConditionsAndProjectInheritance() {
        String subscription = create(Surface.SUBSCRIPTION, "snapshot-source");
        String allowed = project + "/snapshots/allowed";
        String sibling = project + "/snapshots/sibling";
        for (String name : List.of(allowed, sibling)) {
            SubscriberGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
                    .createSnapshot(CreateSnapshotRequest.newBuilder().setName(name).setSubscription(subscription).build());
        }
        grant(allowed, "roles/pubsub.viewer", null);
        Runnable readAllowed = () -> SubscriberGrpc.newBlockingStub(authenticated).withDeadlineAfter(5, TimeUnit.SECONDS)
                .getSnapshot(GetSnapshotRequest.newBuilder().setSnapshot(allowed).build());
        Runnable readSibling = () -> SubscriberGrpc.newBlockingStub(authenticated).withDeadlineAfter(5, TimeUnit.SECONDS)
                .getSnapshot(GetSnapshotRequest.newBuilder().setSnapshot(sibling).build());
        readAllowed.run();
        grpcDenied(() -> SubscriberGrpc.newBlockingStub(authenticated).withDeadlineAfter(5, TimeUnit.SECONDS)
                .updateSnapshot(UpdateSnapshotRequest.newBuilder().setSnapshot(Snapshot.newBuilder().setName(allowed)
                                .putLabels("forbidden", "update"))
                        .setUpdateMask(FieldMask.newBuilder().addPaths("labels")).build()));
        grpcDenied(readSibling);
        assertRestDenied(request().header("Authorization", credential).get("/v1/" + allowed + ":getIamPolicy"));
        TestIamPermissionsRequest permissions = TestIamPermissionsRequest.newBuilder().setResource(allowed)
                .addPermissions("pubsub.snapshots.get").addPermissions("pubsub.snapshots.update").build();
        assertEquals(enforced() ? List.of("pubsub.snapshots.get") : permissions.getPermissionsList(),
                IAMPolicyGrpc.newBlockingStub(authenticated).withDeadlineAfter(5, TimeUnit.SECONDS)
                        .testIamPermissions(permissions).getPermissionsList());
        grant(allowed, "roles/pubsub.viewer", "resource.name == 'excluded'");
        grpcDenied(readAllowed);
        grant(project, "roles/pubsub.viewer", null);
        readAllowed.run();
        readSibling.run();
    }

    @Test
    void policyMutationCannotEscalateTheCallerOverEitherTransport() {
        String topic = create(Surface.TOPIC, "policy");
        grant(topic, "roles/pubsub.publisher", null);
        assertRestDenied(request().header("Authorization", credential).get("/v1/" + topic + ":getIamPolicy"));
        assertRestDenied(request().header("Authorization", credential).contentType("application/json")
                .body(Map.of("policy", policy("roles/pubsub.publisher", null)))
                .post("/v1/" + topic + ":setIamPolicy"));
        grpcDenied(() -> IAMPolicyGrpc.newBlockingStub(authenticated).withDeadlineAfter(5, TimeUnit.SECONDS)
                .getIamPolicy(GetIamPolicyRequest.newBuilder().setResource(topic).build()));
        grpcDenied(() -> IAMPolicyGrpc.newBlockingStub(authenticated).withDeadlineAfter(5, TimeUnit.SECONDS)
                .setIamPolicy(SetIamPolicyRequest.newBuilder().setResource(topic)
                        .setPolicy(Policy.newBuilder().addBindings(Binding.newBuilder()
                                .setRole("roles/pubsub.publisher").addMembers(member))).build()));
    }

    @Test
    void unknownRoleIsDiagnosableOverBothTransports() {
        String topic = create(Surface.TOPIC, "unknown");
        grant(topic, "roles/custom.unimplemented", null);
        Response response = restAllowed(Surface.TOPIC, topic);
        if (enforced()) {
            response.then().statusCode(400).body("error.status", equalTo("FAILED_PRECONDITION"))
                    .body("error.message", containsString("roles/custom.unimplemented"));
            StatusRuntimeException exception = assertThrows(StatusRuntimeException.class, () -> grpcAllowed(Surface.TOPIC, topic));
            assertEquals(Status.Code.FAILED_PRECONDITION, exception.getStatus().getCode());
            assertTrue(exception.getMessage().contains("roles/custom.unimplemented"));
        } else {
            response.then().statusCode(200);
            grpcAllowed(Surface.TOPIC, topic);
        }
    }

    @Test
    void unsupportedConditionsAreDiagnosableAndAnonymousAndExternalCredentialsKeepBypass() {
        String topic = create(Surface.TOPIC, "bypass");
        grant(topic, "roles/pubsub.publisher", "resource.name.matches('.*')");
        if (enforced()) {
            restAllowed(Surface.TOPIC, topic).then().statusCode(400)
                    .body("error.message", containsString("Unsupported IAM condition"));
        } else {
            restAllowed(Surface.TOPIC, topic).then().statusCode(200);
        }
        request().get("/v1/" + topic).then().statusCode(200);
        request().header("Authorization", "Bearer external-token").get("/v1/" + topic).then().statusCode(200);
        PublisherGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
                .getTopic(GetTopicRequest.newBuilder().setTopic(topic).build());
    }

    private static RequestSpecification request() {
        return given().urlEncodingEnabled(false);
    }

    @Test
    void sharedIamMixinRejectsUnmappedResourceKindsLoudly() {
        String unknown = project + "/unimplemented/resource";
        Runnable call = () -> IAMPolicyGrpc.newBlockingStub(authenticated).withDeadlineAfter(5, TimeUnit.SECONDS)
                .getIamPolicy(GetIamPolicyRequest.newBuilder().setResource(unknown).build());
        if (enforced()) {
            StatusRuntimeException exception = assertThrows(StatusRuntimeException.class, call::run);
            assertEquals(Status.Code.FAILED_PRECONDITION, exception.getStatus().getCode());
            assertTrue(exception.getMessage().contains(unknown));
        } else {
            call.run();
        }
    }

    @Test
    void unknownFlociTokenIsUnauthenticatedOverBothTransports() {
        String topic = create(Surface.TOPIC, "invalid-token");
        String invalid = "Bearer floci-gcp-impersonated-missing";
        Response response = request().header("Authorization", invalid).get("/v1/" + topic);
        response.then().statusCode(enforced() ? 401 : 200);
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), invalid);
        Channel invalidChannel = ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers));
        Runnable call = () -> PublisherGrpc.newBlockingStub(invalidChannel).withDeadlineAfter(5, TimeUnit.SECONDS)
                .getTopic(GetTopicRequest.newBuilder().setTopic(topic).build());
        if (enforced()) {
            assertEquals(Status.Code.UNAUTHENTICATED, assertThrows(StatusRuntimeException.class, call::run).getStatus().getCode());
            response.then().body("error.status", equalTo("UNAUTHENTICATED"));
        } else {
            call.run();
        }
    }

    @Test
    void downscopedTokenCannotExpandItsBoundaryIntoPubSub() {
        String topic = create(Surface.TOPIC, "downscoped");
        grant(topic, "roles/owner", null);
        CredentialAccessBoundaryRule rule = new CredentialAccessBoundaryRule("bucket", "", List.of("inRole:roles/storage.objectViewer"));
        for (String source : List.of(credential.substring("Bearer ".length()), "external-token")) {
            String token = tokens.mintDownscopedToken(source, List.of(rule)).token().getTokenValue();
            String originalCredential = credential;
            Channel originalChannel = authenticated;
            credential = "Bearer " + token;
            Metadata headers = new Metadata();
            headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), credential);
            authenticated = ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers));
            try {
                assertDenied(Surface.TOPIC, topic);
            } finally {
                credential = originalCredential;
                authenticated = originalChannel;
            }
        }
    }

    String create(Surface surface, String id) {
        String name = project + switch (surface) {
            case TOPIC -> "/topics/";
            case SUBSCRIPTION -> "/subscriptions/";
            case SECRET -> "/secrets/";
        } + id;
        switch (surface) {
            case TOPIC -> request().contentType("application/json").body(Map.of()).put("/v1/" + name).then().statusCode(200);
            case SUBSCRIPTION -> {
                String topic = project + "/topics/source-" + id;
                request().contentType("application/json").body(Map.of()).put("/v1/" + topic).then().statusCode(200);
                request().contentType("application/json").body(Map.of("topic", topic))
                        .put("/v1/" + name).then().statusCode(200);
            }
            case SECRET -> {
                request().contentType("application/json").queryParam("secretId", id)
                        .body(Map.of("replication", Map.of("automatic", Map.of())))
                        .post("/v1/" + project + "/secrets").then().statusCode(200);
                request().contentType("application/json").body(Map.of("payload", Map.of("data", "c2VjcmV0")))
                        .post("/v1/" + name + ":addVersion").then().statusCode(200);
            }
        }
        return name;
    }

    void grant(String resource, String role, String condition) {
        request().contentType("application/json").body(Map.of("policy", policy(role, condition)))
                .post("/v1/" + resource + ":setIamPolicy").then().statusCode(200);
    }

    Map<String, Object> policy(String role, String condition) {
        Map<String, Object> binding = condition == null ? Map.of("role", role, "members", List.of(member))
                : Map.of("role", role, "members", List.of(member), "condition", Map.of("title", "scope", "expression", condition));
        return Map.of("version", condition == null ? 1 : 3, "bindings", List.of(binding));
    }

    String role(Surface surface) {
        return switch (surface) {
            case TOPIC -> "roles/pubsub.publisher";
            case SUBSCRIPTION -> "roles/pubsub.subscriber";
            case SECRET -> "roles/secretmanager.secretAccessor";
        };
    }

    String operationResource(Surface surface, String resource) {
        return surface == Surface.SECRET ? resource + "/versions/1" : resource;
    }

    Response restAllowed(Surface surface, String resource) {
        return switch (surface) {
            case TOPIC -> request().header("Authorization", credential).contentType("application/json")
                    .body(Map.of("messages", List.of(Map.of("data", "bXNn")))).post("/v1/" + resource + ":publish");
            case SUBSCRIPTION -> request().header("Authorization", credential).contentType("application/json")
                    .body(Map.of("maxMessages", 1)).post("/v1/" + resource + ":pull");
            case SECRET -> request().header("Authorization", credential).get("/v1/" + resource + "/versions/1:access");
        };
    }

    void grpcAllowed(Surface surface, String resource) {
        switch (surface) {
            case TOPIC -> PublisherGrpc.newBlockingStub(authenticated).withDeadlineAfter(5, TimeUnit.SECONDS)
                    .publish(PublishRequest.newBuilder().setTopic(resource)
                            .addMessages(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("msg"))).build());
            case SUBSCRIPTION -> SubscriberGrpc.newBlockingStub(authenticated).withDeadlineAfter(5, TimeUnit.SECONDS)
                    .pull(PullRequest.newBuilder().setSubscription(resource).setMaxMessages(1).build());
            case SECRET -> SecretManagerServiceGrpc.newBlockingStub(authenticated).withDeadlineAfter(5, TimeUnit.SECONDS)
                    .accessSecretVersion(AccessSecretVersionRequest.newBuilder().setName(resource + "/versions/1").build());
        }
    }

    void assertAllowed(Surface surface, String resource) {
        restAllowed(surface, resource).then().statusCode(200);
        grpcAllowed(surface, resource);
    }

    void assertDenied(Surface surface, String resource) {
        assertRestDenied(restAllowed(surface, resource));
        grpcDenied(() -> grpcAllowed(surface, resource));
    }

    void assertForbiddenPermission(Surface surface, String resource) {
        assertRestDenied(request().header("Authorization", credential)
                .get("/v1/" + operationResource(surface, resource)));
        grpcDenied(() -> grpcForbiddenPermission(surface, resource));
    }

    void grpcForbiddenPermission(Surface surface, String resource) {
            switch (surface) {
                case TOPIC -> PublisherGrpc.newBlockingStub(authenticated).withDeadlineAfter(5, TimeUnit.SECONDS)
                        .getTopic(GetTopicRequest.newBuilder().setTopic(resource).build());
                case SUBSCRIPTION -> SubscriberGrpc.newBlockingStub(authenticated).withDeadlineAfter(5, TimeUnit.SECONDS)
                        .getSubscription(GetSubscriptionRequest.newBuilder().setSubscription(resource).build());
                case SECRET -> SecretManagerServiceGrpc.newBlockingStub(authenticated).withDeadlineAfter(5, TimeUnit.SECONDS)
                        .getSecretVersion(GetSecretVersionRequest.newBuilder().setName(resource + "/versions/1").build());
            }
    }

    void assertTestPermissions(Surface surface, String resource) {
        List<String> permissions = switch (surface) {
            case TOPIC -> List.of("pubsub.topics.publish", "pubsub.topics.delete");
            case SUBSCRIPTION -> List.of("pubsub.subscriptions.consume", "pubsub.subscriptions.delete");
            case SECRET -> List.of("secretmanager.versions.access", "secretmanager.secrets.delete");
        };
        List<String> expected = enforced() ? List.of(permissions.getFirst()) : permissions;
        request().header("Authorization", credential).contentType("application/json")
                .body(Map.of("permissions", permissions)).post("/v1/" + resource + ":testIamPermissions")
                .then().statusCode(200).body("permissions", equalTo(expected));
        TestIamPermissionsRequest request = TestIamPermissionsRequest.newBuilder()
                .setResource(resource).addAllPermissions(permissions).build();
        List<String> actual = surface == Surface.SECRET
                ? SecretManagerServiceGrpc.newBlockingStub(authenticated).withDeadlineAfter(5, TimeUnit.SECONDS)
                        .testIamPermissions(request).getPermissionsList()
                : IAMPolicyGrpc.newBlockingStub(authenticated).withDeadlineAfter(5, TimeUnit.SECONDS)
                        .testIamPermissions(request).getPermissionsList();
        assertEquals(expected, actual);
    }

    void assertRestDenied(Response response) {
        response.then().statusCode(enforced() ? 403 : 200);
        if (enforced()) {
            response.then().body("error.status", equalTo("PERMISSION_DENIED"));
        }
    }

    void grpcDenied(Runnable operation) {
        if (enforced()) {
            StatusRuntimeException exception = assertThrows(StatusRuntimeException.class, operation::run);
            assertEquals(Status.Code.PERMISSION_DENIED, exception.getStatus().getCode(), exception.getMessage());
        } else {
            operation.run();
        }
    }
}
