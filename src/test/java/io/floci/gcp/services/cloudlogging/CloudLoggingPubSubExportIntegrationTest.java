package io.floci.gcp.services.cloudlogging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.MonitoredResource;
import com.google.logging.type.LogSeverity;
import com.google.logging.v2.LogEntry;
import com.google.logging.v2.LogSink;
import com.google.logging.v2.LoggingServiceV2Grpc;
import com.google.logging.v2.WriteLogEntriesRequest;
import com.google.protobuf.FieldMask;
import com.google.pubsub.v1.ReceivedMessage;
import io.floci.gcp.services.pubsub.PubSubService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CloudLoggingPubSubExportIntegrationTest {
    private static final String PARENT = "projects/test-project";

    @Inject
    PubSubService pubsub;
    @Inject
    CloudLoggingSinkService sinks;
    @Inject
    ObjectMapper json;
    @TestHTTPResource
    URI endpoint;

    @Test
    void restAndGrpcWritesPublishMatchingEntriesToTheSinkTopic() throws Exception {
        String id = "pubsub-export-" + UUID.randomUUID();
        String topic = PARENT + "/topics/" + id;
        String subscription = PARENT + "/subscriptions/" + id;
        String log = PARENT + "/logs/" + id;
        pubsub.createTopic(topic);
        pubsub.createSubscription(subscription, topic, 10);
        given().queryParam("uniqueWriterIdentity", true).contentType("application/json")
                .body(Map.of("name", id, "destination", "pubsub.googleapis.com/" + topic,
                        "filter", "logName=\"" + log + "\" AND severity>=WARNING"))
                .post("/v2/" + PARENT + "/sinks").then().statusCode(200);
        ManagedChannel channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext().build();
        try {
            given().urlEncodingEnabled(false).contentType("application/json")
                    .body(Map.of("logName", log, "resource", Map.of("type", "global"), "entries", List.of(
                            Map.of("severity", "ERROR", "jsonPayload", Map.of("message", "kept"),
                                    "timestamp", "2026-11-17T10:00:00Z"),
                            Map.of("severity", "INFO", "textPayload", "filtered"))))
                    .post("/v2/entries:write").then().statusCode(200);
            LoggingServiceV2Grpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
                    .writeLogEntries(WriteLogEntriesRequest.newBuilder().setLogName(log)
                            .setResource(MonitoredResource.newBuilder().setType("global"))
                            .addEntries(LogEntry.newBuilder().setSeverity(LogSeverity.WARNING)
                                    .setTextPayload("grpc-kept"))
                            .build());

            List<ReceivedMessage> received = pubsub.pull(subscription, 10);
            assertEquals(2, received.size());
            JsonNode rest = json.readTree(received.get(0).getMessage().getData().toStringUtf8());
            assertEquals("kept", rest.path("jsonPayload").path("message").asText());
            assertEquals(log, rest.path("logName").asText());
            assertFalse(rest.path("insertId").asText().isEmpty());
            assertTrue(rest.has("receiveTimestamp"));
            assertEquals("2026-11-17T10:00:00Z",
                    received.get(0).getMessage().getAttributesOrThrow("logging.googleapis.com/timestamp"));
            JsonNode grpc = json.readTree(received.get(1).getMessage().getData().toStringUtf8());
            assertEquals("grpc-kept", grpc.path("textPayload").asText());
            assertEquals(grpc.path("timestamp").asText(),
                    received.get(1).getMessage().getAttributesOrThrow("logging.googleapis.com/timestamp"));
        } finally {
            sinks.deleteSink(PARENT + "/sinks/" + id);
            pubsub.deleteSubscription(subscription);
            pubsub.deleteTopic(topic);
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void disabledSinksPublishNothingAndAMissingTopicStillAcceptsTheWrite() {
        String id = "pubsub-missing-" + UUID.randomUUID();
        String topic = PARENT + "/topics/" + id;
        String subscription = PARENT + "/subscriptions/" + id;
        String log = PARENT + "/logs/" + id;
        LogSink request = LogSink.newBuilder().setName(id).setDestination("pubsub.googleapis.com/" + topic)
                .setFilter("logName=\"" + log + "\"").build();
        sinks.createSink(PARENT, request, true);
        try {
            write(log);
            pubsub.createTopic(topic);
            pubsub.createSubscription(subscription, topic, 10);
            sinks.updateSink(PARENT + "/sinks/" + id, request.toBuilder().setDisabled(true).build(),
                    FieldMask.newBuilder().addPaths("disabled").build(), true);
            write(log);
            assertTrue(pubsub.pull(subscription, 10).isEmpty());
        } finally {
            sinks.deleteSink(PARENT + "/sinks/" + id);
            pubsub.deleteSubscription(subscription);
            pubsub.deleteTopic(topic);
        }
    }

    private static void write(String log) {
        given().urlEncodingEnabled(false).contentType("application/json")
                .body(Map.of("logName", log, "entries", List.of(Map.of("textPayload", "accepted"))))
                .post("/v2/entries:write").then().statusCode(200);
    }
}
