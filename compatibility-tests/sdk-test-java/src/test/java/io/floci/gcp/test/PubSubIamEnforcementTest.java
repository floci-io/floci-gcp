package io.floci.gcp.test;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.ApiStreamObserver;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.api.services.cloudresourcemanager.CloudResourceManager;
import com.google.api.services.cloudresourcemanager.model.Binding;
import com.google.api.services.cloudresourcemanager.model.Expr;
import com.google.api.services.cloudresourcemanager.model.Policy;
import com.google.api.services.cloudresourcemanager.model.SetIamPolicyRequest;
import com.google.api.services.pubsub.Pubsub;
import com.google.api.services.pubsub.model.PullRequest;
import com.google.api.services.pubsub.model.Subscription;
import com.google.api.services.pubsub.model.TestIamPermissionsRequest;
import com.google.api.services.pubsub.model.Topic;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.cloud.pubsub.v1.SubscriptionAdminSettings;
import com.google.cloud.pubsub.v1.TopicAdminClient;
import com.google.cloud.pubsub.v1.TopicAdminSettings;
import com.google.cloud.pubsub.v1.stub.GrpcSubscriberStub;
import com.google.cloud.pubsub.v1.stub.SubscriberStubSettings;
import com.google.gson.JsonParser;
import com.google.pubsub.v1.ProjectTopicName;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.StreamingPullRequest;
import com.google.pubsub.v1.StreamingPullResponse;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PubSubIamEnforcementTest {
    private final boolean enforce = Boolean.parseBoolean(System.getenv("FLOCI_GCP_IAM_TEST_ENFORCEMENT"));
    private String project;
    private String member;
    private Pubsub setup;
    private Pubsub callerRest;
    private ManagedChannel channel;
    private TopicAdminClient callerGrpc;
    private SubscriptionAdminClient callerSubscriptions;

    @BeforeEach
    void setup() throws Exception {
        project = TestFixtures.uniqueName("pubsub-iam-sdk");
        String email = "reader@" + project + ".iam.gserviceaccount.com";
        member = "serviceAccount:" + email;
        HttpRequest request = HttpRequest.newBuilder(URI.create(TestFixtures.endpoint()
                        + "/v1/projects/-/serviceAccounts/" + email + ":generateAccessToken"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"scope\":[\"https://www.googleapis.com/auth/cloud-platform\"]}"))
                .build();
        String token;
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            token = JsonParser.parseString(response.body()).getAsJsonObject().get("accessToken").getAsString();
        }
        setup = rest(null);
        callerRest = rest(token);
        URI endpoint = URI.create(TestFixtures.endpoint());
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext()
                .intercept(MetadataUtils.newAttachHeadersInterceptor(headers)).build();
        callerGrpc = TopicAdminClient.create(TopicAdminSettings.newBuilder()
                .setTransportChannelProvider(FixedTransportChannelProvider.create(
                        GrpcTransportChannel.create(channel)))
                .setCredentialsProvider(NoCredentialsProvider.create()).build());
        callerSubscriptions = SubscriptionAdminClient.create(SubscriptionAdminSettings.newBuilder()
                .setTransportChannelProvider(FixedTransportChannelProvider.create(
                        GrpcTransportChannel.create(channel)))
                .setCredentialsProvider(NoCredentialsProvider.create()).build());
    }

    @AfterEach
    void close() throws Exception {
        callerGrpc.close();
        callerSubscriptions.close();
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void projectConditionScopesTopicCreationAcrossOfficialRestAndGrpcClients() throws Exception {
        String allowed = "projects/" + project + "/topics/allowed";
        String excluded = "projects/" + project + "/topics/excluded";
        grantProject("roles/pubsub.admin", "resource.name == '" + allowed + "'");

        assertThat(callerRest.projects().topics().create(allowed, new Topic()).execute().getName())
                .isEqualTo(allowed);
        assertThat(callerGrpc.getTopic(allowed).getName()).isEqualTo(allowed);
        List<String> requested = List.of("pubsub.topics.get", "pubsub.topics.setIamPolicy",
                "pubsub.topics.unimplemented");
        assertThat(callerRest.projects().topics().testIamPermissions(allowed,
                new TestIamPermissionsRequest().setPermissions(requested)).execute().getPermissions())
                .containsExactlyElementsOf(enforce ? requested.subList(0, 2) : requested);
        if (enforce) {
            assertThatThrownBy(() -> callerRest.projects().topics().create(excluded, new Topic()).execute())
                    .isInstanceOfSatisfying(GoogleJsonResponseException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(403));
            assertThatThrownBy(() -> callerGrpc.createTopic(ProjectTopicName.of(project, "excluded")))
                    .hasMessageContaining("PERMISSION_DENIED");
            assertThatThrownBy(() -> setup.projects().topics().get(excluded).execute())
                    .isInstanceOfSatisfying(GoogleJsonResponseException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(404));
        } else {
            assertThat(callerRest.projects().topics().create(excluded, new Topic()).execute().getName())
                    .isEqualTo(excluded);
        }
    }

    @Test
    void officialClientsCannotReadAnIndependentlyScopedSubscription() throws Exception {
        String topic = "projects/" + project + "/topics/source";
        String first = "projects/" + project + "/subscriptions/first";
        String second = "projects/" + project + "/subscriptions/second";
        setup.projects().topics().create(topic, new Topic()).execute();
        setup.projects().subscriptions().create(first, new Subscription().setTopic(topic)).execute();
        setup.projects().subscriptions().create(second, new Subscription().setTopic(topic)).execute();
        grantProject("roles/pubsub.viewer", "resource.name == '" + first + "'");

        assertThat(callerRest.projects().subscriptions().get(first).execute().getName()).isEqualTo(first);
        assertThat(callerSubscriptions.getSubscription(first).getName()).isEqualTo(first);
        if (enforce) {
            assertThatThrownBy(() -> callerRest.projects().subscriptions().get(second).execute())
                    .isInstanceOfSatisfying(GoogleJsonResponseException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(403));
            assertThatThrownBy(() -> callerSubscriptions.getSubscription(second))
                    .hasMessageContaining("PERMISSION_DENIED");
        } else {
            assertThat(callerRest.projects().subscriptions().get(second).execute().getName()).isEqualTo(second);
            assertThat(callerSubscriptions.getSubscription(second).getName()).isEqualTo(second);
        }
    }

    @Test
    void streamingPullClosesAfterSubscriberGrantIsRevoked() throws Exception {
        String topic = "projects/" + project + "/topics/stream-source";
        String subscription = "projects/" + project + "/subscriptions/stream-source";
        setup.projects().topics().create(topic, new Topic()).execute();
        setup.projects().subscriptions().create(subscription, new Subscription().setTopic(topic)).execute();
        grantProject("roles/pubsub.subscriber", "resource.name == '" + subscription + "'");

        URI endpoint = URI.create(TestFixtures.endpoint());
        ManagedChannel setupChannel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext().build();
        Publisher publisher = Publisher.newBuilder(ProjectTopicName.parse(topic))
                .setChannelProvider(FixedTransportChannelProvider.create(GrpcTransportChannel.create(setupChannel)))
                .setCredentialsProvider(NoCredentialsProvider.create()).build();
        publisher.publish(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("initial"))
                .build()).get(5, TimeUnit.SECONDS);

        CountDownLatch delivered = new CountDownLatch(1);
        CountDownLatch deliveredAfterRevocation = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicInteger responseCount = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SubscriberStubSettings settings = SubscriberStubSettings.newBuilder()
                .setTransportChannelProvider(FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel)))
                .setCredentialsProvider(NoCredentialsProvider.create()).build();
        try (GrpcSubscriberStub subscriber = GrpcSubscriberStub.create(settings)) {
            ApiStreamObserver<StreamingPullRequest> requests = subscriber.streamingPullCallable()
                .bidiStreamingCall(new ApiStreamObserver<>() {
                    @Override
                    public void onNext(StreamingPullResponse response) {
                        if (responseCount.incrementAndGet() == 1) {
                            delivered.countDown();
                        } else {
                            deliveredAfterRevocation.countDown();
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        failure.set(error);
                        closed.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        closed.countDown();
                    }
                });
            requests.onNext(StreamingPullRequest.newBuilder().setSubscription(subscription).build());
            assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();

            grantProject("roles/pubsub.viewer", "resource.name == '" + subscription + "'");
            publisher.publish(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("queued"))
                    .build()).get(5, TimeUnit.SECONDS);

            if (enforce) {
                assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(Status.fromThrowable(failure.get()).getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
                assertThat(responseCount.get()).isEqualTo(1);
                assertThat(setup.projects().subscriptions().pull(subscription,
                        new PullRequest().setMaxMessages(1))
                        .execute().getReceivedMessages()).hasSize(1);
            } else {
                assertThat(deliveredAfterRevocation.await(5, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            publisher.shutdown();
            publisher.awaitTermination(5, TimeUnit.SECONDS);
            setupChannel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void grantProject(String role, String expression) throws IOException {
        CloudResourceManager manager = new CloudResourceManager.Builder(new NetHttpTransport(),
                GsonFactory.getDefaultInstance(), ignored -> {})
                .setRootUrl(TestFixtures.endpoint() + "/").setApplicationName("floci-pubsub-iam-compat").build();
        manager.projects().setIamPolicy(project, new SetIamPolicyRequest().setPolicy(new Policy()
                .setVersion(3).setBindings(List.of(new Binding().setRole(role)
                        .setMembers(List.of(member)).setCondition(new Expr().setTitle("scope")
                                .setExpression(expression)))))).execute();
    }

    private Pubsub rest(String token) {
        return new Pubsub.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance(), request -> {
            if (token != null) {
                request.getHeaders().setAuthorization("Bearer " + token);
            }
        }).setRootUrl(TestFixtures.endpoint() + "/").setApplicationName("floci-pubsub-iam-compat").build();
    }
}
