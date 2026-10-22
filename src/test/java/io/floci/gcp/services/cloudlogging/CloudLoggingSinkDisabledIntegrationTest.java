package io.floci.gcp.services.cloudlogging;

import com.google.logging.v2.ConfigServiceV2Grpc;
import com.google.logging.v2.CreateSinkRequest;
import com.google.logging.v2.DeleteSinkRequest;
import com.google.logging.v2.GetSinkRequest;
import com.google.logging.v2.ListSinksRequest;
import com.google.logging.v2.LogSink;
import com.google.logging.v2.UpdateSinkRequest;
import com.google.protobuf.FieldMask;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(CloudLoggingSinkDisabledIntegrationTest.DisabledLoggingProfile.class)
class CloudLoggingSinkDisabledIntegrationTest {

    @TestHTTPResource
    URI endpoint;

    @Inject
    CloudLoggingSinkService sinks;

    @Test
    void disabledLoggingRejectsSinkGrpcCallsWithoutChangingStoredSinks() throws Exception {
        String parent = "projects/logging-disabled";
        String name = parent + "/sinks/existing";
        LogSink request = LogSink.newBuilder().setName("existing")
                .setDestination("storage.googleapis.com/disabled-logs").build();
        LogSink existing = sinks.createSink(parent, request, true);
        ManagedChannel channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext().build();
        try {
            ConfigServiceV2Grpc.ConfigServiceV2BlockingStub client = ConfigServiceV2Grpc.newBlockingStub(channel)
                    .withDeadlineAfter(10, TimeUnit.SECONDS);
            assertUnimplemented(() -> client.createSink(CreateSinkRequest.newBuilder().setParent(parent)
                    .setSink(request.toBuilder().setName("new-sink")).setUniqueWriterIdentity(true).build()));
            assertUnimplemented(() -> client.getSink(GetSinkRequest.newBuilder().setSinkName(name).build()));
            assertUnimplemented(() -> client.listSinks(ListSinksRequest.newBuilder().setParent(parent).build()));
            assertUnimplemented(() -> client.updateSink(UpdateSinkRequest.newBuilder().setSinkName(name)
                    .setSink(request.toBuilder().setDisabled(true))
                    .setUpdateMask(FieldMask.newBuilder().addPaths("disabled")).build()));
            assertUnimplemented(() -> client.deleteSink(DeleteSinkRequest.newBuilder().setSinkName(name).build()));
            assertEquals(existing, sinks.getSink(name));
            assertEquals(1, sinks.listSinks(parent, 100, null).getSinksCount());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            sinks.deleteSink(name);
        }
    }

    @Test
    void disabledLoggingRejectsSinkRestCalls() {
        given().when().get("/v2/projects/logging-disabled/sinks")
                .then().statusCode(503)
                .body("error.status", equalTo("UNAVAILABLE"))
                .body("error.message", equalTo("Service logging is not enabled."));
    }

    private static void assertUnimplemented(Runnable call) {
        StatusRuntimeException exception = assertThrows(StatusRuntimeException.class, call::run);
        assertEquals(Status.Code.UNIMPLEMENTED, exception.getStatus().getCode());
    }

    public static class DisabledLoggingProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.logging.enabled", "false");
        }
    }
}
