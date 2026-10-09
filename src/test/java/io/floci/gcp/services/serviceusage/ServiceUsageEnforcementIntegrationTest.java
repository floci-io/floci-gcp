package io.floci.gcp.services.serviceusage;

import com.google.protobuf.Any;
import com.google.pubsub.v1.PublisherGrpc;
import com.google.pubsub.v1.Topic;
import com.google.rpc.ErrorInfo;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.MetadataUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(ServiceUsageEnforcementIntegrationTest.EnforceProfile.class)
class ServiceUsageEnforcementIntegrationTest {

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

    private static ErrorInfo errorInfo(StatusRuntimeException error) {
        com.google.rpc.Status status = StatusProto.fromThrowable(error);
        try {
            Any detail = status.getDetails(0);
            return detail.unpack(ErrorInfo.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void enable(String project, String service) {
        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().post("/v1/projects/" + project + "/services/" + service + ":enable")
                .then().statusCode(200)
                .body("response.service.state", equalTo("ENABLED"));
    }

    public static class EnforceProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.serviceusage.enforce", "true");
        }
    }
}
