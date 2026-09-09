package io.floci.gcp.services.gcs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.pubsub.v1.PubsubMessage;
import com.sun.net.httpserver.HttpServer;
import io.floci.gcp.services.eventarc.EventarcService;
import io.floci.gcp.services.pubsub.PubSubService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class GcsMultipartNotificationIntegrationTest {
    @Inject GcsService gcs;
    @Inject EventarcService eventarc;
    @InjectMock PubSubService pubsub;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void serializedNotificationsMatchThePublishedGeneration() throws Exception {
        String bucket = "events-" + UUID.randomUUID(), topic = "projects/test-project/topics/" + bucket;
        String triggerName = "projects/test-project/locations/us-central1/triggers/" + bucket;
        var notifications = new LinkedBlockingQueue<byte[]>();
        var deliveries = new LinkedBlockingQueue<byte[]>();
        doAnswer(call -> {
            List<PubsubMessage> messages = call.getArgument(1);
            messages.forEach(message -> notifications.add(message.getData().toByteArray()));
            return List.of("synthetic-message");
        }).when(pubsub).publish(eq(topic), anyList());
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/events", exchange -> {
            deliveries.add(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        receiver.start();
        try {
            given().contentType("application/json").body(Map.of("name", bucket)).post("/storage/v1/b?project=test-project").then().statusCode(200);
            var notification = gcs.createNotification(bucket, Map.of("topic", topic, "event_types", List.of("OBJECT_FINALIZE"), "payload_format", "JSON_API_V1"));
            eventarc.createTrigger("test-project", "us-central1", bucket, JSON.writeValueAsString(Map.of(
                    "eventFilters", List.of(Map.of("attribute", "type", "value", "google.cloud.storage.object.v1.finalized"), Map.of("attribute", "bucket", "value", bucket)),
                    "destination", Map.of("httpEndpoint", Map.of("uri", "http://127.0.0.1:" + receiver.getAddress().getPort() + "/events")))), false);
            byte[] bytes = new byte[]{0, 1, 2, (byte) 255};
            String path = "/" + bucket + "/file";
            String upload = given().contentType("application/octet-stream").header("x-goog-meta-test", "multipart")
                    .post(path + "?uploads").then().statusCode(200).extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
            String etag = given().body(bytes).put(path + "?uploadId=" + upload + "&partNumber=1").then().statusCode(200).extract().header("ETag");
            assertTrue(notifications.isEmpty()); assertTrue(deliveries.isEmpty());
            given().contentType("application/xml").body("<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>" + etag + "</ETag></Part></CompleteMultipartUpload>")
                    .post(path + "?uploadId=" + upload).then().statusCode(200);
            JsonNode multipart = metadata(bucket);
            assertFalse(multipart.hasNonNull("md5Hash"));
            assertEquals("multipart", multipart.path("metadata").path("test").asText());
            assertEvents(multipart, notifications, deliveries);
            assertArrayEquals(bytes, given().get(path).asByteArray());
            // Overwrite normally: the next generation and both events must retain its MD5.
            given().contentType("application/octet-stream").body(bytes).put(path).then().statusCode(200);
            JsonNode ordinary = metadata(bucket);
            assertTrue(ordinary.hasNonNull("md5Hash"));
            assertNotEquals(multipart.path("generation"), ordinary.path("generation"));
            assertEvents(ordinary, notifications, deliveries);
            assertArrayEquals(bytes, given().get(path).asByteArray());
            verify(pubsub, times(2)).publish(eq(topic), anyList());
            gcs.deleteNotification(bucket, notification.getId());
        } finally {
            eventarc.deleteTrigger(triggerName, true, false);
            given().delete("/storage/v1/b/" + bucket + "/o/file");
            given().delete("/storage/v1/b/" + bucket);
            receiver.stop(0);
        }
    }
    private JsonNode metadata(String bucket) throws Exception {
        return JSON.readTree(given().get("/storage/v1/b/" + bucket + "/o/file").then().statusCode(200).extract().asByteArray());
    }
    private void assertEvents(JsonNode stored, LinkedBlockingQueue<byte[]> notifications, LinkedBlockingQueue<byte[]> deliveries) throws Exception {
        for (var queue : List.of(notifications, deliveries)) {
            byte[] bytes = queue.poll(10, TimeUnit.SECONDS);
            assertNotNull(bytes, "Missing finalized event");
            JsonNode event = JSON.readTree(bytes);
            for (String field : List.of("generation", "etag", "md5Hash", "crc32c", "metadata", "size", "contentType")) {
                assertEquals(stored.path(field), event.path(field), field);
            }
        }
    }
}
