package io.floci.gcp.core.common;

import com.google.api.MetricDescriptor;
import com.google.cloud.secretmanager.v1.AccessSecretVersionRequest;
import com.google.cloud.secretmanager.v1.AddSecretVersionRequest;
import com.google.cloud.secretmanager.v1.CreateSecretRequest;
import com.google.cloud.secretmanager.v1.Replication;
import com.google.cloud.secretmanager.v1.Secret;
import com.google.cloud.secretmanager.v1.SecretManagerServiceGrpc;
import com.google.cloud.secretmanager.v1.SecretPayload;
import com.google.firestore.v1.CommitRequest;
import com.google.firestore.v1.Document;
import com.google.firestore.v1.FirestoreGrpc;
import com.google.firestore.v1.GetDocumentRequest;
import com.google.firestore.v1.Value;
import com.google.firestore.v1.Write;
import com.google.monitoring.v3.CreateMetricDescriptorRequest;
import com.google.monitoring.v3.GetMetricDescriptorRequest;
import com.google.monitoring.v3.MetricServiceGrpc;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.GetSubscriptionRequest;
import com.google.pubsub.v1.GetTopicRequest;
import com.google.pubsub.v1.PublisherGrpc;
import com.google.pubsub.v1.SubscriberGrpc;
import com.google.pubsub.v1.Subscription;
import com.google.pubsub.v1.Topic;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class GrpcProjectRegistrationIntegrationTest {

    @TestHTTPResource
    URI endpoint;

    @Inject
    ProjectRegistry projects;

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

    @Test
    void aProjectFirstNamedOverPubSubIsKnownByItsNumber() {
        String project = "grpc-first-use-pubsub";
        publisher().createTopic(Topic.newBuilder().setName("projects/" + project + "/topics/t").build());

        given().when().get("/v1/projects/" + ProjectNumbers.of(project))
                .then().statusCode(200)
                .body("projectId", equalTo(project));
    }

    @Test
    void aProjectFirstNamedOverSecretManagerIsKnownByItsNumber() {
        String project = "grpc-first-use-secrets";
        SecretManagerServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
                .createSecret(CreateSecretRequest.newBuilder()
                        .setParent("projects/" + project)
                        .setSecretId("s")
                        .setSecret(Secret.newBuilder().setReplication(Replication.newBuilder()
                                .setAutomatic(Replication.Automatic.getDefaultInstance())))
                        .build());

        given().when().get("/v1/projects/" + ProjectNumbers.of(project))
                .then().statusCode(200)
                .body("projectId", equalTo(project));
    }

    @Test
    void anUnregisteredProjectNumberStillWorksAndRegistersNothing() {
        String number = "990000000148";
        String topic = "projects/" + number + "/topics/t";
        publisher().createTopic(Topic.newBuilder().setName(topic).build());

        assertEquals(topic, publisher().getTopic(GetTopicRequest.newBuilder().setTopic(topic).build()).getName());
        given().when().get("/v1/projects/" + number).then().statusCode(403);
    }

    @Test
    void aTopicCreatedByProjectIdIsFoundByProjectNumber() {
        String project = "grpc-number-pubsub";
        String number = ProjectNumbers.of(project);
        publisher().createTopic(Topic.newBuilder().setName("projects/" + project + "/topics/t").build());

        Topic byNumber = publisher().getTopic(GetTopicRequest.newBuilder()
                .setTopic("projects/" + number + "/topics/t").build());

        assertEquals("projects/" + project + "/topics/t", byNumber.getName());
    }

    @Test
    void aSubscriptionNamedByProjectNumberAttachesToTheTopicCreatedById() {
        String project = "grpc-number-subscription";
        String number = ProjectNumbers.of(project);
        publisher().createTopic(Topic.newBuilder().setName("projects/" + project + "/topics/t").build());
        SubscriberGrpc.SubscriberBlockingStub subscriber =
                SubscriberGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);

        subscriber.createSubscription(Subscription.newBuilder()
                .setName("projects/" + number + "/subscriptions/s")
                .setTopic("projects/" + number + "/topics/t")
                .build());

        Subscription byId = subscriber.getSubscription(GetSubscriptionRequest.newBuilder()
                .setSubscription("projects/" + project + "/subscriptions/s").build());
        assertEquals("projects/" + project + "/topics/t", byId.getTopic());
    }

    @Test
    void aSecretCreatedByProjectIdIsWrittenAndReadByProjectNumber() {
        String project = "grpc-number-secrets";
        String number = ProjectNumbers.of(project);
        SecretManagerServiceGrpc.SecretManagerServiceBlockingStub secrets =
                SecretManagerServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
        secrets.createSecret(CreateSecretRequest.newBuilder()
                .setParent("projects/" + project)
                .setSecretId("s")
                .setSecret(Secret.newBuilder().setReplication(Replication.newBuilder()
                        .setAutomatic(Replication.Automatic.getDefaultInstance())))
                .build());

        secrets.addSecretVersion(AddSecretVersionRequest.newBuilder()
                .setParent("projects/" + number + "/secrets/s")
                .setPayload(SecretPayload.newBuilder().setData(ByteString.copyFromUtf8("v1")))
                .build());

        assertEquals("v1", secrets.accessSecretVersion(AccessSecretVersionRequest.newBuilder()
                        .setName("projects/" + number + "/secrets/s/versions/latest").build())
                .getPayload().getData().toStringUtf8());
    }

    @Test
    void aMetricDescriptorCreatedByProjectIdIsFoundByProjectNumber() {
        String project = "grpc-number-monitoring";
        String number = ProjectNumbers.of(project);
        String type = "custom.googleapis.com/grpc-number/m";
        MetricServiceGrpc.MetricServiceBlockingStub metrics =
                MetricServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
        metrics.createMetricDescriptor(CreateMetricDescriptorRequest.newBuilder()
                .setName("projects/" + project)
                .setMetricDescriptor(MetricDescriptor.newBuilder()
                        .setType(type)
                        .setMetricKind(MetricDescriptor.MetricKind.GAUGE)
                        .setValueType(MetricDescriptor.ValueType.INT64))
                .build());

        MetricDescriptor byNumber = metrics.getMetricDescriptor(GetMetricDescriptorRequest.newBuilder()
                .setName("projects/" + number + "/metricDescriptors/" + type).build());

        assertEquals(type, byNumber.getType());
    }

    @Test
    void aFirestoreDocumentWrittenByProjectNumberIsReadByNumberAndIdAndDeletedByNumber() {
        String project = "grpc-number-firestore";
        projects.observe(project);
        String byNumber = "projects/" + ProjectNumbers.of(project) + "/databases/(default)";
        String byId = "projects/" + project + "/databases/(default)";
        FirestoreGrpc.FirestoreBlockingStub firestore =
                FirestoreGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
        firestore.commit(CommitRequest.newBuilder().setDatabase(byNumber)
                .addWrites(Write.newBuilder().setUpdate(Document.newBuilder()
                        .setName(byNumber + "/documents/c/d")
                        .putFields("v", Value.newBuilder().setStringValue("written").build())))
                .build());

        for (String database : List.of(byNumber, byId)) {
            Document read = firestore.getDocument(GetDocumentRequest.newBuilder()
                    .setName(database + "/documents/c/d").build());
            assertEquals(byId + "/documents/c/d", read.getName());
            assertEquals("written", read.getFieldsOrThrow("v").getStringValue());
        }

        firestore.commit(CommitRequest.newBuilder().setDatabase(byNumber)
                .addWrites(Write.newBuilder().setDelete(byNumber + "/documents/c/d")).build());
        StatusRuntimeException missing = assertThrows(StatusRuntimeException.class,
                () -> firestore.getDocument(GetDocumentRequest.newBuilder()
                        .setName(byId + "/documents/c/d").build()));
        assertEquals(Status.Code.NOT_FOUND, missing.getStatus().getCode());
    }

    @Test
    void aProjectFirstNamedByAFirestoreCommitWithoutARoutingHeaderIsKnownByItsNumber() {
        String project = "grpc-first-use-firestore";
        String database = "projects/" + project + "/databases/(default)";
        FirestoreGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
                .commit(CommitRequest.newBuilder().setDatabase(database)
                        .addWrites(Write.newBuilder().setUpdate(Document.newBuilder()
                                .setName(database + "/documents/c/d")))
                        .build());

        given().when().get("/v1/projects/" + ProjectNumbers.of(project))
                .then().statusCode(200)
                .body("projectId", equalTo(project));
    }
}
