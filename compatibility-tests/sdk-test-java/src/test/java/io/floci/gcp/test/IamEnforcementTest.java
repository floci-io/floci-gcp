package io.floci.gcp.test;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.InstantiatingGrpcChannelProvider;
import com.google.api.gax.rpc.FixedHeaderProvider;
import com.google.api.gax.rpc.PermissionDeniedException;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.cloud.pubsub.v1.SubscriptionAdminSettings;
import com.google.cloud.pubsub.v1.TopicAdminClient;
import com.google.cloud.pubsub.v1.TopicAdminSettings;
import com.google.cloud.pubsub.v1.stub.GrpcPublisherStub;
import com.google.cloud.pubsub.v1.stub.PublisherStubSettings;
import com.google.cloud.secretmanager.v1.Replication;
import com.google.cloud.secretmanager.v1.Secret;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretManagerServiceSettings;
import com.google.cloud.secretmanager.v1.SecretPayload;
import com.google.gson.JsonParser;
import com.google.iam.v1.Binding;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import com.google.iam.v1.TestIamPermissionsRequest;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PublishRequest;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.PullRequest;
import com.google.pubsub.v1.PushConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs against disabled mode by default; set FLOCI_GCP_IAM_TEST_ENFORCEMENT=true for an enforcing server. */
class IamEnforcementTest {
    private final boolean enforce = Boolean.parseBoolean(System.getenv("FLOCI_GCP_IAM_TEST_ENFORCEMENT"));
    private String project;
    private String member;
    private InstantiatingGrpcChannelProvider transport;
    private FixedHeaderProvider headers;

    @BeforeEach
    void setup() throws Exception {
        project = "projects/" + TestFixtures.uniqueName("iam-sdk");
        String email = "reader@" + project.substring(9) + ".iam.gserviceaccount.com";
        member = "serviceAccount:" + email;
        HttpRequest request = HttpRequest.newBuilder(URI.create(TestFixtures.endpoint()
                        + "/v1/projects/-/serviceAccounts/" + email + ":generateAccessToken"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"scope\":[\"https://www.googleapis.com/auth/cloud-platform\"]}"))
                .build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            String token = JsonParser.parseString(response.body()).getAsJsonObject().get("accessToken").getAsString();
            headers = FixedHeaderProvider.create("authorization", "Bearer " + token);
        }
        URI endpoint = URI.create(TestFixtures.endpoint());
        transport = InstantiatingGrpcChannelProvider.newBuilder().setEndpoint(endpoint.getHost() + ":" + endpoint.getPort())
                .setChannelConfigurator(builder -> builder.usePlaintext()).build();
    }

    @Test
    void publisherGrantAllowsPublishingButDeniesMetadataAndSiblingTopic() throws Exception {
        TopicAdminSettings anonymous = TopicAdminSettings.newBuilder().setTransportChannelProvider(transport)
                .setCredentialsProvider(NoCredentialsProvider.create()).build();
        TopicAdminSettings authenticated = anonymous.toBuilder().setCredentialsProvider(NoCredentialsProvider.create()).setHeaderProvider(headers).build();
        try (TopicAdminClient setup = TopicAdminClient.create(anonymous);
                TopicAdminClient reader = TopicAdminClient.create(authenticated);
                GrpcPublisherStub publisher = GrpcPublisherStub.create(PublisherStubSettings.newBuilder()
                        .setTransportChannelProvider(transport).setCredentialsProvider(NoCredentialsProvider.create()).setHeaderProvider(headers).build())) {
            String topic = project + "/topics/allowed";
            String sibling = project + "/topics/sibling";
            setup.createTopic(topic);
            setup.createTopic(sibling);
            try {
                setup.setIamPolicy(grant(topic, "roles/pubsub.publisher"));
                PublishRequest publish = PublishRequest.newBuilder().setTopic(topic)
                        .addMessages(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8("payload"))).build();
                assertThat(publisher.publishCallable().call(publish).getMessageIdsCount()).isEqualTo(1);
                denied(() -> reader.getTopic(topic));
                denied(() -> publisher.publishCallable().call(publish.toBuilder().setTopic(sibling).build()));
                assertThat(reader.testIamPermissions(TestIamPermissionsRequest.newBuilder().setResource(topic)
                        .addPermissions("pubsub.topics.publish").addPermissions("pubsub.topics.delete").build()).getPermissionsList())
                        .containsExactlyElementsOf(enforce ? List.of("pubsub.topics.publish")
                                : List.of("pubsub.topics.publish", "pubsub.topics.delete"));
            } finally {
                setup.deleteTopic(topic);
                setup.deleteTopic(sibling);
            }
        }
    }

    @Test
    void subscriberGrantAllowsPullButDeniesMetadataAndSiblingSubscription() throws Exception {
        TopicAdminSettings topics = TopicAdminSettings.newBuilder().setTransportChannelProvider(transport)
                .setCredentialsProvider(NoCredentialsProvider.create()).build();
        SubscriptionAdminSettings subscriptions = SubscriptionAdminSettings.newBuilder().setTransportChannelProvider(transport)
                .setCredentialsProvider(NoCredentialsProvider.create()).build();
        try (TopicAdminClient setupTopics = TopicAdminClient.create(topics);
                SubscriptionAdminClient setup = SubscriptionAdminClient.create(subscriptions);
                SubscriptionAdminClient reader = SubscriptionAdminClient.create(subscriptions.toBuilder()
                        .setCredentialsProvider(NoCredentialsProvider.create()).setHeaderProvider(headers).build())) {
            String topic = project + "/topics/source";
            String subscription = project + "/subscriptions/allowed";
            String sibling = project + "/subscriptions/sibling";
            setupTopics.createTopic(topic);
            setup.createSubscription(subscription, topic, PushConfig.getDefaultInstance(), 10);
            setup.createSubscription(sibling, topic, PushConfig.getDefaultInstance(), 10);
            try {
                setup.setIamPolicy(grant(subscription, "roles/pubsub.subscriber"));
                PullRequest pull = PullRequest.newBuilder().setSubscription(subscription).setMaxMessages(1).build();
                assertThat(reader.pull(pull).getReceivedMessagesCount()).isZero();
                denied(() -> reader.getSubscription(subscription));
                denied(() -> reader.pull(pull.toBuilder().setSubscription(sibling).build()));
            } finally {
                setup.deleteSubscription(subscription);
                setup.deleteSubscription(sibling);
                setupTopics.deleteTopic(topic);
            }
        }
    }

    @Test
    void secretAccessorGrantAllowsPayloadButDeniesMetadataAndSiblingSecret() throws Exception {
        SecretManagerServiceSettings settings = SecretManagerServiceSettings.newBuilder().setTransportChannelProvider(transport)
                .setCredentialsProvider(NoCredentialsProvider.create()).build();
        try (SecretManagerServiceClient setup = SecretManagerServiceClient.create(settings);
                SecretManagerServiceClient reader = SecretManagerServiceClient.create(settings.toBuilder()
                        .setCredentialsProvider(NoCredentialsProvider.create()).setHeaderProvider(headers).build())) {
            Secret secret = Secret.newBuilder().setReplication(Replication.newBuilder()
                    .setAutomatic(Replication.Automatic.getDefaultInstance())).build();
            String allowed = setup.createSecret(project, "allowed", secret).getName();
            String sibling = setup.createSecret(project, "sibling", secret).getName();
            try {
                SecretPayload payload = SecretPayload.newBuilder().setData(ByteString.copyFromUtf8("secret")).build();
                String version = setup.addSecretVersion(allowed, payload).getName();
                String excluded = setup.addSecretVersion(sibling, payload).getName();
                setup.setIamPolicy(grant(allowed, "roles/secretmanager.secretAccessor"));
                assertThat(reader.accessSecretVersion(version).getPayload().getData().toStringUtf8()).isEqualTo("secret");
                denied(() -> reader.getSecretVersion(version));
                denied(() -> reader.accessSecretVersion(excluded));
            } finally {
                setup.deleteSecret(allowed);
                setup.deleteSecret(sibling);
            }
        }
    }

    private SetIamPolicyRequest grant(String resource, String role) {
        return SetIamPolicyRequest.newBuilder().setResource(resource).setPolicy(Policy.newBuilder()
                .addBindings(Binding.newBuilder().setRole(role).addMembers(member))).build();
    }

    private void denied(Runnable operation) {
        if (enforce) {
            assertThatThrownBy(operation::run).isInstanceOf(PermissionDeniedException.class);
        } else {
            operation.run();
        }
    }
}
