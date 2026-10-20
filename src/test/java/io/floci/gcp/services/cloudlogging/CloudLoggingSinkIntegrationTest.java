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
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CloudLoggingSinkIntegrationTest {

    @TestHTTPResource
    URI endpoint;

    @Test
    void organizationLifecycleAndReadbackWorkAcrossGrpcAndRest() throws Exception {
        String parent = "organizations/123456789";
        String sinkId = "grpc-sink-" + UUID.randomUUID();
        String name = parent + "/sinks/" + sinkId;
        String destination = "pubsub.googleapis.com/projects/core/topics/gws-events";
        ManagedChannel channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext().build();
        ConfigServiceV2Grpc.ConfigServiceV2BlockingStub client = ConfigServiceV2Grpc.newBlockingStub(channel)
                .withDeadlineAfter(10, TimeUnit.SECONDS);
        try {
            LogSink sink = LogSink.newBuilder().setName(sinkId).setDestination(destination)
                    .setFilter("protoPayload.methodName:\"Group\"").setIncludeChildren(true).build();
            LogSink created = client.createSink(CreateSinkRequest.newBuilder().setParent(parent)
                    .setSink(sink).setUniqueWriterIdentity(true).build());
            assertEquals(sinkId, created.getName());
            assertFalse(created.getWriterIdentity().isEmpty());
            assertEquals(created, client.getSink(GetSinkRequest.newBuilder().setSinkName(name).build()));
            assertTrue(client.listSinks(ListSinksRequest.newBuilder().setParent(parent).build()).getSinksList().contains(created));
            given().when().get("/v2/" + name).then().statusCode(200)
                    .body("name", equalTo(sinkId)).body("writerIdentity", equalTo(created.getWriterIdentity()))
                    .body("includeChildren", equalTo(true));

            LogSink changed = client.updateSink(UpdateSinkRequest.newBuilder().setSinkName(name)
                    .setUniqueWriterIdentity(true).setSink(sink.toBuilder().setFilter("severity>=ERROR"))
                    .setUpdateMask(FieldMask.newBuilder().addPaths("filter")).build());
            assertEquals("severity>=ERROR", changed.getFilter());
            assertEquals(created.getWriterIdentity(), changed.getWriterIdentity());
            assertEquals(created.getCreateTime(), changed.getCreateTime());

            // REST uses lowerCamelCase masks while the gRPC field mask uses snake_case.
            given().queryParam("updateMask", "includeChildren,disabled")
                    .queryParam("uniqueWriterIdentity", true).contentType("application/json")
                    .body(Map.of("name", sinkId, "disabled", true))
                    .when().patch("/v2/" + name).then().statusCode(200).body("disabled", equalTo(true));
            LogSink repaired = client.getSink(GetSinkRequest.newBuilder().setSinkName(name).build());
            assertFalse(repaired.getIncludeChildren());
            assertTrue(repaired.getDisabled());
            assertEquals(changed.getFilter(), repaired.getFilter());

            StatusRuntimeException invalidMask = assertThrows(StatusRuntimeException.class,
                    () -> client.updateSink(UpdateSinkRequest.newBuilder().setSinkName(name).setSink(sink)
                            .setUniqueWriterIdentity(true)
                            .setUpdateMask(FieldMask.newBuilder().addPaths("filter").addPaths("writer_identity")).build()));
            assertEquals(Status.Code.INVALID_ARGUMENT, invalidMask.getStatus().getCode());
            assertEquals(repaired, client.getSink(GetSinkRequest.newBuilder().setSinkName(name).build()));

            client.deleteSink(DeleteSinkRequest.newBuilder().setSinkName(name).build());
            assertEquals(Status.Code.NOT_FOUND, assertThrows(StatusRuntimeException.class,
                    () -> client.getSink(GetSinkRequest.newBuilder().setSinkName(name).build())).getStatus().getCode());
            given().when().get("/v2/" + name).then().statusCode(404).body("error.status", equalTo("NOT_FOUND"));
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void grpcRejectsMissingParentsAndInvalidNames() throws Exception {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext().build();
        try {
            ConfigServiceV2Grpc.ConfigServiceV2BlockingStub client = ConfigServiceV2Grpc.newBlockingStub(channel)
                    .withDeadlineAfter(10, TimeUnit.SECONDS);
            LogSink sink = LogSink.newBuilder().setName("valid-name")
                    .setDestination("pubsub.googleapis.com/projects/core/topics/gws-events").build();
            assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class,
                    () -> client.createSink(CreateSinkRequest.newBuilder().setSink(sink).build())).getStatus().getCode());
            assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class,
                    () -> client.listSinks(ListSinksRequest.newBuilder().setParent("invalid/parent").build())).getStatus().getCode());
            assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class,
                    () -> client.getSink(GetSinkRequest.newBuilder().setSinkName("organizations/123/sinks/").build())).getStatus().getCode());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
