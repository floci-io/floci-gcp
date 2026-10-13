package io.floci.gcp.services.serviceusage;

import com.google.cloud.bigquery.storage.v1.AppendRowsRequest;
import com.google.cloud.bigquery.storage.v1.AppendRowsResponse;
import com.google.cloud.bigquery.storage.v1.BigQueryReadGrpc;
import com.google.cloud.bigquery.storage.v1.BigQueryWriteGrpc;
import com.google.cloud.bigquery.storage.v1.CreateReadSessionRequest;
import com.google.cloud.bigquery.storage.v1.CreateWriteStreamRequest;
import com.google.cloud.bigquery.storage.v1.ReadSession;
import com.google.cloud.secretmanager.v1.Secret;
import com.google.cloud.secretmanager.v1.SecretManagerServiceGrpc;
import com.google.cloud.secretmanager.v1.UpdateSecretRequest;
import com.google.datastore.v1.LookupRequest;
import com.google.firestore.v1.BatchGetDocumentsRequest;
import com.google.firestore.v1.CommitRequest;
import com.google.firestore.v1.Document;
import com.google.firestore.v1.FirestoreGrpc;
import com.google.firestore.v1.Write;
import com.google.logging.v2.ListLogEntriesRequest;
import com.google.logging.v2.LogEntry;
import com.google.logging.v2.LoggingServiceV2Grpc;
import com.google.logging.v2.WriteLogEntriesRequest;
import com.google.protobuf.Any;
import com.google.pubsub.v1.GetTopicRequest;
import com.google.pubsub.v1.PublisherGrpc;
import com.google.pubsub.v1.Topic;
import com.google.rpc.Code;
import com.google.rpc.ErrorInfo;
import com.google.storage.v2.GetBucketRequest;
import com.google.storage.v2.GetObjectRequest;
import com.google.storage.v2.QueryWriteStatusRequest;
import com.google.storage.v2.StartResumableWriteRequest;
import com.google.storage.v2.StorageGrpc;
import com.google.storage.v2.WriteObjectRequest;
import com.google.storage.v2.WriteObjectResponse;
import com.google.storage.v2.WriteObjectSpec;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(ServiceUsageEnforcementIntegrationTest.EnforceProfile.class)
class ServiceUsageEnforcementIntegrationTest {

    private static final String DEFAULT_PROJECT = "test-project";

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

    @Test
    void disabledApiIsRejectedWithServiceDisabledErrorInfo() {
        String project = "su-enforce-rest";
        given().contentType("application/json").body("{}")
                .when().put("/v1/projects/" + project + "/topics/t1")
                .then().statusCode(403)
                .body("error.code", equalTo(403))
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", startsWith(
                        "Cloud Pub/Sub API has not been used in project " + project + " before or it is disabled."))
                .body("error.errors[0].reason", equalTo("accessNotConfigured"))
                .body("error.details[0].'@type'", equalTo("type.googleapis.com/google.rpc.ErrorInfo"))
                .body("error.details[0].reason", equalTo("SERVICE_DISABLED"))
                .body("error.details[0].domain", equalTo("googleapis.com"))
                .body("error.details[0].metadata.service", equalTo("pubsub.googleapis.com"))
                .body("error.details[0].metadata.consumer", equalTo("projects/" + project));
    }

    @Test
    void enableUnblocksAndDisableBlocksAgainPerProject() {
        String project = "su-enforce-toggle";
        enable(project, "pubsub.googleapis.com");

        given().contentType("application/json").body("{}")
                .when().put("/v1/projects/" + project + "/topics/t1")
                .then().statusCode(200);
        given().contentType("application/json").body("{}")
                .when().put("/v1/projects/su-enforce-other/topics/t1")
                .then().statusCode(403);

        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().post("/v1/projects/" + project + "/services/pubsub.googleapis.com:disable")
                .then().statusCode(200);
        given().when().get("/v1/projects/" + project + "/topics/t1")
                .then().statusCode(403)
                .body("error.details[0].reason", equalTo("SERVICE_DISABLED"));
    }

    @Test
    void restResourcesOutsideTheEnabledFlagAreGated() {
        String project = "su-enforce-secrets";
        given().when().get("/v1/projects/" + project + "/secrets")
                .then().statusCode(403)
                .body("error.details[0].metadata.service", equalTo("secretmanager.googleapis.com"));

        enable(project, "secretmanager.googleapis.com");
        given().when().get("/v1/projects/" + project + "/secrets")
                .then().statusCode(200);
    }

    @Test
    void exemptApisAlwaysWork() {
        String project = "su-enforce-exempt";
        given().when().get("/v1/projects/" + project + "/services")
                .then().statusCode(200);
        given().when().get("/v1/projects/" + project)
                .then().statusCode(200)
                .body("projectId", equalTo(project));
    }

    @Test
    void defaultEnabledServicesWorkUntilDisabled() {
        String project = "su-enforce-defaults";
        given().when().get("/storage/v1/b?project=" + project)
                .then().statusCode(200);
        given().when().get("/v1/projects/" + project + "/services/storage.googleapis.com")
                .then().statusCode(200)
                .body("state", equalTo("ENABLED"));
        given().queryParam("filter", "state:ENABLED")
                .when().get("/v1/projects/" + project + "/services")
                .then().statusCode(200)
                .body("services.name", hasItem("projects/" + project + "/services/storage.googleapis.com"));

        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().post("/v1/projects/" + project + "/services/storage.googleapis.com:disable")
                .then().statusCode(200);
        given().when().get("/storage/v1/b?project=" + project)
                .then().statusCode(403)
                .body("error.details[0].metadata.service", equalTo("storage.googleapis.com"));
    }

    @Test
    void grpcCallIsRejectedUntilApiIsEnabled() {
        String project = "su-enforce-grpc";
        PublisherGrpc.PublisherBlockingStub publisher = PublisherGrpc.newBlockingStub(channel)
                .withDeadlineAfter(10, TimeUnit.SECONDS);
        Topic topic = Topic.newBuilder().setName("projects/" + project + "/topics/g1").build();

        StatusRuntimeException error = assertThrows(StatusRuntimeException.class, () -> publisher.createTopic(topic));
        assertEquals(Status.Code.PERMISSION_DENIED, error.getStatus().getCode());
        assertTrue(error.getStatus().getDescription().startsWith(
                "Cloud Pub/Sub API has not been used in project " + project));
        ErrorInfo info = errorInfo(error);
        assertEquals("SERVICE_DISABLED", info.getReason());
        assertEquals("googleapis.com", info.getDomain());
        assertEquals("pubsub.googleapis.com", info.getMetadataOrThrow("service"));
        assertEquals("projects/" + project, info.getMetadataOrThrow("consumer"));

        enable(project, "pubsub.googleapis.com");
        assertEquals(topic.getName(), publisher.createTopic(topic).getName());
    }

    @Test
    void grpcRoutingHeaderSelectsTheConsumerProject() {
        enable("su-enforce-header-on", "pubsub.googleapis.com");
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("x-goog-request-params", Metadata.ASCII_STRING_MARSHALLER),
                "name=projects%2Fsu-enforce-header-off%2Ftopics%2Fh1");
        PublisherGrpc.PublisherBlockingStub publisher = PublisherGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .withDeadlineAfter(10, TimeUnit.SECONDS);

        StatusRuntimeException error = assertThrows(StatusRuntimeException.class, () -> publisher.createTopic(
                Topic.newBuilder().setName("projects/su-enforce-header-off/topics/h1").build()));
        assertEquals(Status.Code.PERMISSION_DENIED, error.getStatus().getCode());
        assertEquals("projects/su-enforce-header-off", errorInfo(error).getMetadataOrThrow("consumer"));
    }

    @Test
    void routingHeaderDoesNotAdmitAnotherProjectNamedInTheMessage() {
        String enabled = "su-enforce-conflict-on";
        String disabled = "su-enforce-conflict-off";
        enable(enabled, "pubsub.googleapis.com");
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("x-goog-request-params", Metadata.ASCII_STRING_MARSHALLER),
                "name=projects%2F" + enabled + "%2Ftopics%2Fc1");
        PublisherGrpc.PublisherBlockingStub routed = PublisherGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .withDeadlineAfter(10, TimeUnit.SECONDS);
        String topic = "projects/" + disabled + "/topics/c1";

        StatusRuntimeException error = assertThrows(StatusRuntimeException.class,
                () -> routed.createTopic(Topic.newBuilder().setName(topic).build()));
        assertEquals(Status.Code.PERMISSION_DENIED, error.getStatus().getCode());
        assertEquals("projects/" + disabled, errorInfo(error).getMetadataOrThrow("consumer"));

        enable(disabled, "pubsub.googleapis.com");
        StatusRuntimeException missing = assertThrows(StatusRuntimeException.class,
                () -> PublisherGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
                        .getTopic(GetTopicRequest.newBuilder().setTopic(topic).build()));
        assertEquals(Status.Code.NOT_FOUND, missing.getStatus().getCode());
    }

    @Test
    void laterStreamMessageNamingADisabledProjectIsRejected() throws Exception {
        String enabled = "su-enforce-append-on";
        String disabled = "su-enforce-append-off";
        disable(disabled, "bigquerystorage.googleapis.com");
        LinkedBlockingQueue<AppendRowsResponse> responses = new LinkedBlockingQueue<>();
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        StreamObserver<AppendRowsRequest> requests = BigQueryWriteGrpc.newStub(channel)
                .withDeadlineAfter(10, TimeUnit.SECONDS)
                .appendRows(new StreamObserver<>() {
                    @Override
                    public void onNext(AppendRowsResponse response) {
                        responses.add(response);
                    }

                    @Override
                    public void onError(Throwable t) {
                        failure.complete(t);
                    }

                    @Override
                    public void onCompleted() {
                        failure.complete(null);
                    }
                });

        requests.onNext(AppendRowsRequest.newBuilder()
                .setWriteStream("projects/" + enabled + "/datasets/d/tables/t/streams/_default").build());
        AppendRowsResponse first = responses.poll(10, TimeUnit.SECONDS);
        assertTrue(first != null && first.hasError(), "the first append is served, with an in-stream error");

        requests.onNext(AppendRowsRequest.newBuilder()
                .setWriteStream("projects/" + disabled + "/datasets/d/tables/t/streams/_default").build());
        StatusRuntimeException error = (StatusRuntimeException) failure.get(10, TimeUnit.SECONDS);
        assertEquals(Status.Code.PERMISSION_DENIED, error.getStatus().getCode());
        assertEquals("bigquerystorage.googleapis.com", errorInfo(error).getMetadataOrThrow("service"));
        assertEquals("projects/" + disabled, errorInfo(error).getMetadataOrThrow("consumer"));
        assertTrue(responses.isEmpty());
    }

    @Test
    void projectNamedOnlyInANestedMessageIsChecked() {
        String project = "su-enforce-nested";

        StatusRuntimeException error = assertThrows(StatusRuntimeException.class,
                () -> SecretManagerServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
                        .updateSecret(UpdateSecretRequest.newBuilder()
                                .setSecret(Secret.newBuilder().setName("projects/" + project + "/secrets/s1"))
                                .build()));
        assertEquals(Status.Code.PERMISSION_DENIED, error.getStatus().getCode());
        assertEquals("secretmanager.googleapis.com", errorInfo(error).getMetadataOrThrow("service"));
        assertEquals("projects/" + project, errorInfo(error).getMetadataOrThrow("consumer"));
    }

    @Test
    void everyProjectInARepeatedFieldIsChecked() {
        String enabled = "su-enforce-repeated-on";
        String disabled = "su-enforce-repeated-off";
        disable(disabled, "logging.googleapis.com");
        LoggingServiceV2Grpc.LoggingServiceV2BlockingStub logging = LoggingServiceV2Grpc.newBlockingStub(channel)
                .withDeadlineAfter(10, TimeUnit.SECONDS);

        logging.listLogEntries(ListLogEntriesRequest.newBuilder()
                .addResourceNames("projects/" + enabled).build());
        StatusRuntimeException error = assertThrows(StatusRuntimeException.class,
                () -> logging.listLogEntries(ListLogEntriesRequest.newBuilder()
                        .addResourceNames("projects/" + enabled)
                        .addResourceNames("projects/" + disabled).build()));
        assertEquals(Status.Code.PERMISSION_DENIED, error.getStatus().getCode());
        assertEquals("logging.googleapis.com", errorInfo(error).getMetadataOrThrow("service"));
        assertEquals("projects/" + disabled, errorInfo(error).getMetadataOrThrow("consumer"));
    }

    @Test
    void customMethodOnTheProjectSegmentChecksThatProject() throws Exception {
        String project = "su-enforce-datastore";
        byte[] lookup = LookupRequest.newBuilder().setProjectId(project).build().toByteArray();
        given().urlEncodingEnabled(false).contentType("application/x-protobuf").body(lookup)
                .when().post("/v1/projects/" + project + ":lookup")
                .then().statusCode(200);

        disable(project, "datastore.googleapis.com");
        byte[] denied = given().urlEncodingEnabled(false).contentType("application/x-protobuf").body(lookup)
                .when().post("/v1/projects/" + project + ":lookup")
                .then().statusCode(403)
                .contentType("application/x-protobuf")
                .extract().asByteArray();
        com.google.rpc.Status status = com.google.rpc.Status.parseFrom(denied);
        assertEquals(Code.PERMISSION_DENIED_VALUE, status.getCode());
        ErrorInfo info = status.getDetails(0).unpack(ErrorInfo.class);
        assertEquals("datastore.googleapis.com", info.getMetadataOrThrow("service"));
        assertEquals("projects/" + project, info.getMetadataOrThrow("consumer"));

        enable(project, "datastore.googleapis.com");
        given().urlEncodingEnabled(false).contentType("application/x-protobuf").body(lookup)
                .when().post("/v1/projects/" + project + ":lookup")
                .then().statusCode(200);
    }

    @Test
    void everyLogEntryDestinationIsChecked() {
        String enabled = "su-enforce-entries-on";
        String disabled = "su-enforce-entries-off";
        disable(disabled, "logging.googleapis.com");
        LoggingServiceV2Grpc.LoggingServiceV2BlockingStub logging = LoggingServiceV2Grpc.newBlockingStub(channel)
                .withDeadlineAfter(10, TimeUnit.SECONDS);
        LogEntry own = LogEntry.newBuilder().setLogName("projects/" + disabled + "/logs/app")
                .setTextPayload("x").build();

        StatusRuntimeException withDefault = assertThrows(StatusRuntimeException.class,
                () -> logging.writeLogEntries(WriteLogEntriesRequest.newBuilder()
                        .setLogName("projects/" + enabled + "/logs/app").addEntries(own).build()));
        assertEquals(Status.Code.PERMISSION_DENIED, withDefault.getStatus().getCode());
        assertEquals("projects/" + disabled, errorInfo(withDefault).getMetadataOrThrow("consumer"));

        StatusRuntimeException withoutDefault = assertThrows(StatusRuntimeException.class,
                () -> logging.writeLogEntries(WriteLogEntriesRequest.newBuilder().addEntries(own).build()));
        assertEquals(Status.Code.PERMISSION_DENIED, withoutDefault.getStatus().getCode());

        enable(disabled, "logging.googleapis.com");
        assertEquals(0, logging.listLogEntries(ListLogEntriesRequest.newBuilder()
                .addResourceNames("projects/" + disabled).build()).getEntriesCount());
        logging.writeLogEntries(WriteLogEntriesRequest.newBuilder()
                .setLogName("projects/" + enabled + "/logs/app").addEntries(own).build());
    }

    @Test
    void restLoggingChecksTheProjectsNamedInTheBody() {
        String enabled = "su-enforce-rest-entries-on";
        String disabled = "su-enforce-rest-entries-off";
        disable(disabled, "logging.googleapis.com");
        String write = "{\"logName\":\"projects/" + enabled + "/logs/app\",\"entries\":["
                + "{\"textPayload\":\"a\"},"
                + "{\"logName\":\"projects/" + disabled + "/logs/app\",\"textPayload\":\"b\"}]}";

        given().urlEncodingEnabled(false).contentType("application/json").body(write)
                .when().post("/v2/entries:write")
                .then().statusCode(403)
                .body("error.details[0].metadata.service", equalTo("logging.googleapis.com"))
                .body("error.details[0].metadata.consumer", equalTo("projects/" + disabled));
        given().urlEncodingEnabled(false).contentType("application/json")
                .body("{\"resourceNames\":[\"projects/" + enabled + "\",\"projects/" + disabled + "\"]}")
                .when().post("/v2/entries:list")
                .then().statusCode(403)
                .body("error.details[0].metadata.consumer", equalTo("projects/" + disabled));

        enable(disabled, "logging.googleapis.com");
        given().urlEncodingEnabled(false).contentType("application/json").body(write)
                .when().post("/v2/entries:write")
                .then().statusCode(200);
        given().urlEncodingEnabled(false).contentType("application/json")
                .body("{\"resourceNames\":[\"projects/" + disabled + "\"]}")
                .when().post("/v2/entries:list")
                .then().statusCode(200)
                .body("entries.size()", equalTo(1));
    }

    @Test
    void firebaseClientEndpointsCheckTheDefaultProject() {
        String other = "su-enforce-firebase-other";
        String signUp = "/identitytoolkit.googleapis.com/v1/accounts:signUp";
        enable(other, "identitytoolkit.googleapis.com");

        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .queryParam("key", "k").queryParam("project", other)
                .when().post(signUp)
                .then().statusCode(403)
                .body("error.details[0].metadata.service", equalTo("identitytoolkit.googleapis.com"))
                .body("error.details[0].metadata.consumer", equalTo("projects/" + DEFAULT_PROJECT));

        enable(DEFAULT_PROJECT, "identitytoolkit.googleapis.com");
        try {
            given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                    .queryParam("key", "k").queryParam("project", other)
                    .when().post(signUp)
                    .then().statusCode(200);
        } finally {
            disable(DEFAULT_PROJECT, "identitytoolkit.googleapis.com");
        }
    }

    @Test
    void bucketRequestsCheckTheProjectThatOwnsTheBucket() {
        String project = "su-enforce-bucket-owner";
        String bucket = "su-enforce-owned-bucket";
        given().urlEncodingEnabled(false).contentType("application/json").body("{\"name\":\"" + bucket + "\"}")
                .when().post("/storage/v1/b?project=" + project)
                .then().statusCode(200);
        given().when().get("/storage/v1/b/" + bucket)
                .then().statusCode(200);

        disable(project, "storage.googleapis.com");
        given().when().get("/storage/v1/b/" + bucket)
                .then().statusCode(403)
                .body("error.details[0].metadata.consumer", equalTo("projects/" + project));
        given().when().get("/" + bucket + "/missing.txt")
                .then().statusCode(403)
                .contentType(startsWith("application/xml"))
                .body("Error.Code", equalTo("AccessDenied"));
        StatusRuntimeException error = assertThrows(StatusRuntimeException.class,
                () -> StorageGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
                        .getBucket(GetBucketRequest.newBuilder().setName("projects/_/buckets/" + bucket).build()));
        assertEquals(Status.Code.PERMISSION_DENIED, error.getStatus().getCode());
        assertEquals("projects/" + project, errorInfo(error).getMetadataOrThrow("consumer"));

        enable(project, "storage.googleapis.com");
        given().when().get("/storage/v1/b/" + bucket)
                .then().statusCode(200);
    }

    @Test
    void copyAndRewriteCheckTheDestinationBucketOwner() {
        String source = "su-enforce-copy-src";
        String destination = "su-enforce-copy-dst";
        createBucket(source, "su-enforce-copy-src-bucket");
        createBucket(destination, "su-enforce-copy-dst-bucket");
        given().urlEncodingEnabled(false).contentType("text/plain").body("x")
                .when().post("/upload/storage/v1/b/su-enforce-copy-src-bucket/o?uploadType=media&name=a.txt")
                .then().statusCode(200);
        String route = "/storage/v1/b/su-enforce-copy-src-bucket/o/a.txt/%s/b/su-enforce-copy-dst-bucket/o/b.txt";

        disable(destination, "storage.googleapis.com");
        for (String method : List.of("copyTo", "rewriteTo")) {
            given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                    .when().post(String.format(route, method))
                    .then().statusCode(403)
                    .body("error.details[0].metadata.consumer", equalTo("projects/" + destination));
        }

        enable(destination, "storage.googleapis.com");
        given().when().get("/storage/v1/b/su-enforce-copy-dst-bucket/o/b.txt")
                .then().statusCode(404);
        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().post(String.format(route, "copyTo"))
                .then().statusCode(200);
    }

    @Test
    void bucketNamedUnderAnotherProjectChecksTheBucketOwner() {
        String owner = "su-enforce-named-owner";
        String bucket = "su-enforce-named-bucket";
        createBucket(owner, bucket);
        StorageGrpc.StorageBlockingStub storage = StorageGrpc.newBlockingStub(channel);
        GetBucketRequest request = GetBucketRequest.newBuilder()
                .setName("projects/su-enforce-named-other/buckets/" + bucket).build();
        assertTrue(storage.withDeadlineAfter(10, TimeUnit.SECONDS).getBucket(request).getName()
                .endsWith("/buckets/" + bucket));

        disable(owner, "storage.googleapis.com");
        StatusRuntimeException error = assertThrows(StatusRuntimeException.class,
                () -> storage.withDeadlineAfter(10, TimeUnit.SECONDS).getBucket(request));
        assertEquals(Status.Code.PERMISSION_DENIED, error.getStatus().getCode());
        assertEquals("projects/" + owner, errorInfo(error).getMetadataOrThrow("consumer"));
    }

    @Test
    void resumedWritesCheckTheOwnerOfTheUploadsBucket() throws Exception {
        String project = "su-enforce-resume";
        String bucket = "su-enforce-resume-bucket";
        createBucket(project, bucket);
        StorageGrpc.StorageBlockingStub storage = StorageGrpc.newBlockingStub(channel);
        String uploadId = storage.withDeadlineAfter(10, TimeUnit.SECONDS)
                .startResumableWrite(StartResumableWriteRequest.newBuilder()
                        .setWriteObjectSpec(WriteObjectSpec.newBuilder()
                                .setResource(com.google.storage.v2.Object.newBuilder()
                                        .setBucket("projects/_/buckets/" + bucket).setName("resumed.txt")))
                        .build())
                .getUploadId();
        QueryWriteStatusRequest status = QueryWriteStatusRequest.newBuilder().setUploadId(uploadId).build();
        assertEquals(0, storage.withDeadlineAfter(10, TimeUnit.SECONDS).queryWriteStatus(status).getPersistedSize());

        disable(project, "storage.googleapis.com");
        StatusRuntimeException queried = assertThrows(StatusRuntimeException.class,
                () -> storage.withDeadlineAfter(10, TimeUnit.SECONDS).queryWriteStatus(status));
        assertEquals(Status.Code.PERMISSION_DENIED, queried.getStatus().getCode());
        assertEquals("projects/" + project, errorInfo(queried).getMetadataOrThrow("consumer"));

        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        StreamObserver<WriteObjectRequest> requests = StorageGrpc.newStub(channel)
                .withDeadlineAfter(10, TimeUnit.SECONDS)
                .writeObject(new StreamObserver<>() {
                    @Override
                    public void onNext(WriteObjectResponse response) {
                    }

                    @Override
                    public void onError(Throwable t) {
                        failure.complete(t);
                    }

                    @Override
                    public void onCompleted() {
                        failure.complete(null);
                    }
                });
        requests.onNext(WriteObjectRequest.newBuilder().setUploadId(uploadId).setFinishWrite(true).build());
        StatusRuntimeException written = (StatusRuntimeException) failure.get(10, TimeUnit.SECONDS);
        assertEquals(Status.Code.PERMISSION_DENIED, written.getStatus().getCode());
        assertEquals("projects/" + project, errorInfo(written).getMetadataOrThrow("consumer"));

        enable(project, "storage.googleapis.com");
        assertEquals(0, storage.withDeadlineAfter(10, TimeUnit.SECONDS).queryWriteStatus(status).getPersistedSize());
    }

    @Test
    void resumableChunkChecksTheOwnerOfTheUploadsBucket() {
        String project = "su-enforce-chunk";
        createBucket(project, "su-enforce-chunk-bucket");
        createBucket("su-enforce-chunk-other", "su-enforce-chunk-other-bucket");
        String session = given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().post("/upload/storage/v1/b/su-enforce-chunk-bucket/o?uploadType=resumable&name=c.txt")
                .then().statusCode(200)
                .extract().header("Location");
        String uploadId = session.substring(session.indexOf("upload_id=") + "upload_id=".length());

        disable(project, "storage.googleapis.com");
        given().urlEncodingEnabled(false).contentType("text/plain").body("x")
                .when().put("/upload/storage/v1/b/su-enforce-chunk-other-bucket/o?upload_id=" + uploadId)
                .then().statusCode(403)
                .body("error.details[0].metadata.consumer", equalTo("projects/" + project));

        enable(project, "storage.googleapis.com");
        given().urlEncodingEnabled(false).contentType("text/plain").body("x")
                .when().put("/upload/storage/v1/b/su-enforce-chunk-other-bucket/o?upload_id=" + uploadId)
                .then().statusCode(200)
                .body("bucket", equalTo("su-enforce-chunk-bucket"));

        disable(project, "storage.googleapis.com");
        given().urlEncodingEnabled(false).contentType("text/plain").body("x")
                .when().put("/upload/storage/v1/b/su-enforce-chunk-other-bucket/o?upload_id=" + uploadId)
                .then().statusCode(403)
                .body("error.details[0].metadata.consumer", equalTo("projects/" + project));
    }

    @Test
    void firestoreDocumentsOutsideTheDatabaseAreChecked() {
        String enabled = "su-enforce-fs-on";
        String disabled = "su-enforce-fs-off";
        enable(enabled, "firestore.googleapis.com");
        String database = "projects/" + enabled + "/databases/(default)";
        String document = "projects/" + disabled + "/databases/(default)/documents/c/d";
        FirestoreGrpc.FirestoreBlockingStub firestore = FirestoreGrpc.newBlockingStub(channel);

        StatusRuntimeException read = assertThrows(StatusRuntimeException.class,
                () -> firestore.withDeadlineAfter(10, TimeUnit.SECONDS)
                        .batchGetDocuments(BatchGetDocumentsRequest.newBuilder()
                                .setDatabase(database).addDocuments(document).build())
                        .hasNext());
        assertEquals(Status.Code.PERMISSION_DENIED, read.getStatus().getCode());
        assertEquals("projects/" + disabled, errorInfo(read).getMetadataOrThrow("consumer"));

        StatusRuntimeException written = assertThrows(StatusRuntimeException.class,
                () -> firestore.withDeadlineAfter(10, TimeUnit.SECONDS)
                        .commit(CommitRequest.newBuilder().setDatabase(database)
                                .addWrites(Write.newBuilder()
                                        .setUpdate(Document.newBuilder().setName(document)))
                                .build()));
        assertEquals(Status.Code.PERMISSION_DENIED, written.getStatus().getCode());
        assertEquals("projects/" + disabled, errorInfo(written).getMetadataOrThrow("consumer"));

        assertTrue(firestore.withDeadlineAfter(10, TimeUnit.SECONDS)
                .batchGetDocuments(BatchGetDocumentsRequest.newBuilder().setDatabase(database)
                        .addDocuments(database + "/documents/c/d").build())
                .hasNext());
    }

    @Test
    void wildcardServiceAccountChecksTheProjectThatOwnsTheAccount() {
        String project = "su-enforce-iam-wildcard";
        enable(project, "iam.googleapis.com");
        JsonPath account = given().contentType("application/json")
                .body(Map.of("accountId", "gate-sa"))
                .when().post("/v1/projects/" + project + "/serviceAccounts")
                .then().statusCode(200)
                .extract().jsonPath();
        String email = account.getString("email");
        List<String> routes = List.of(
                "/v1/projects/-/serviceAccounts/" + email,
                "/v1/projects/-/serviceAccounts/" + account.getString("uniqueId"),
                "/v1/projects/-/serviceAccounts/" + email + "/keys");
        for (String route : routes) {
            given().when().get(route)
                    .then().statusCode(200);
        }

        disable(project, "iam.googleapis.com");
        for (String route : routes) {
            given().when().get(route)
                    .then().statusCode(403)
                    .body("error.details[0].reason", equalTo("SERVICE_DISABLED"))
                    .body("error.details[0].metadata.consumer", equalTo("projects/" + project));
        }
    }

    @Test
    void batchIsGatedOnTheBucketsOfTheRequestsItCarries() {
        String project = "su-enforce-batch";
        String bucket = "su-enforce-batch-bucket";
        createBucket(project, bucket);
        byte[] batch = ("--batch_test\r\nContent-Type: application/http\r\nContent-ID: <item1>\r\n\r\n"
                + "GET /storage/v1/b/" + bucket + " HTTP/1.1\r\n\r\n--batch_test--\r\n")
                .getBytes(StandardCharsets.UTF_8);

        disable(DEFAULT_PROJECT, "storage.googleapis.com");
        try {
            given().contentType("multipart/mixed; boundary=batch_test").body(batch)
                    .when().post("/batch/storage/v1")
                    .then().statusCode(200)
                    .body(containsString("HTTP/1.1 200 OK"));

            disable(project, "storage.googleapis.com");
            given().contentType("multipart/mixed; boundary=batch_test").body(batch)
                    .when().post("/batch/storage/v1")
                    .then().statusCode(200)
                    .body(containsString("HTTP/1.1 403 Forbidden"))
                    .body(containsString("SERVICE_DISABLED"));
        } finally {
            enable(DEFAULT_PROJECT, "storage.googleapis.com");
        }
    }

    @Test
    void bigQueryStorageIsGatedAsItsOwnApi() {
        String project = "su-enforce-bqstorage";
        disable(project, "bigquerystorage.googleapis.com");

        StatusRuntimeException read = assertThrows(StatusRuntimeException.class,
                () -> BigQueryReadGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
                        .createReadSession(CreateReadSessionRequest.newBuilder()
                                .setParent("projects/" + project).build()));
        assertEquals(Status.Code.PERMISSION_DENIED, read.getStatus().getCode());
        assertEquals("bigquerystorage.googleapis.com", errorInfo(read).getMetadataOrThrow("service"));
        assertTrue(read.getStatus().getDescription().startsWith(
                "BigQuery Storage API has not been used in project " + project));

        StatusRuntimeException write = assertThrows(StatusRuntimeException.class,
                () -> BigQueryWriteGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
                        .createWriteStream(CreateWriteStreamRequest.newBuilder()
                                .setParent("projects/" + project + "/datasets/d/tables/t").build()));
        assertEquals(Status.Code.PERMISSION_DENIED, write.getStatus().getCode());
        assertEquals("bigquerystorage.googleapis.com", errorInfo(write).getMetadataOrThrow("service"));

        given().when().get("/bigquery/v2/projects/" + project + "/datasets")
                .then().statusCode(200);
    }

    @Test
    void readSessionChecksTheProjectOfTheTable() {
        String disabled = "su-enforce-session-off";
        disable(disabled, "bigquerystorage.googleapis.com");

        StatusRuntimeException error = assertThrows(StatusRuntimeException.class,
                () -> BigQueryReadGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
                        .createReadSession(CreateReadSessionRequest.newBuilder()
                                .setParent("projects/su-enforce-session-on")
                                .setReadSession(ReadSession.newBuilder()
                                        .setTable("projects/" + disabled + "/datasets/d/tables/t"))
                                .build()));
        assertEquals(Status.Code.PERMISSION_DENIED, error.getStatus().getCode());
        assertEquals("projects/" + disabled, errorInfo(error).getMetadataOrThrow("consumer"));
    }

    @Test
    void objectNamedLikeABucketChecksOnlyItsOwnBucket() {
        String other = "su-enforce-object-other";
        createBucket("su-enforce-object-own", "su-enforce-object-own-bucket");
        createBucket(other, "su-enforce-object-other-bucket");
        disable(other, "storage.googleapis.com");

        StatusRuntimeException error = assertThrows(StatusRuntimeException.class,
                () -> StorageGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
                        .getObject(GetObjectRequest.newBuilder()
                                .setBucket("projects/_/buckets/su-enforce-object-own-bucket")
                                .setObject("projects/_/buckets/su-enforce-object-other-bucket")
                                .build()));
        assertEquals(Status.Code.NOT_FOUND, error.getStatus().getCode());
    }

    @Test
    void disablingBigQueryLeavesBigQueryStorageServed() {
        String project = "su-enforce-bq-only";
        disable(project, "bigquery.googleapis.com");

        given().when().get("/bigquery/v2/projects/" + project + "/datasets")
                .then().statusCode(403)
                .body("error.details[0].metadata.service", equalTo("bigquery.googleapis.com"));
        StatusRuntimeException read = assertThrows(StatusRuntimeException.class,
                () -> BigQueryReadGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
                        .createReadSession(CreateReadSessionRequest.newBuilder()
                                .setParent("projects/" + project).build()));
        assertNotEquals(Status.Code.PERMISSION_DENIED, read.getStatus().getCode());
    }

    private static ErrorInfo errorInfo(StatusRuntimeException error) {
        // Qualified to avoid a name clash with io.grpc.Status.
        com.google.rpc.Status status = StatusProto.fromThrowable(error);
        try {
            Any detail = status.getDetails(0);
            return detail.unpack(ErrorInfo.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void createBucket(String project, String bucket) {
        given().urlEncodingEnabled(false).contentType("application/json").body("{\"name\":\"" + bucket + "\"}")
                .when().post("/storage/v1/b?project=" + project)
                .then().statusCode(200);
    }

    private static void enable(String project, String service) {
        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().post("/v1/projects/" + project + "/services/" + service + ":enable")
                .then().statusCode(200)
                .body("response.service.state", equalTo("ENABLED"));
    }

    private static void disable(String project, String service) {
        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().post("/v1/projects/" + project + "/services/" + service + ":disable")
                .then().statusCode(200);
    }

    public static class EnforceProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.serviceusage.enforcement-enabled", "true");
        }
    }
}
