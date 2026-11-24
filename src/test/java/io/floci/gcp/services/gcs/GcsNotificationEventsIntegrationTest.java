package io.floci.gcp.services.gcs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.FieldMask;
import com.google.pubsub.v1.PubsubMessage;
import com.google.storage.v2.StorageGrpc;
import com.google.storage.v2.UpdateObjectRequest;
import io.floci.gcp.services.pubsub.PubSubService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * Pub/Sub notification attributes and event types, as listed in
 * https://cloud.google.com/storage/docs/pubsub-notifications.
 */
@QuarkusTest
class GcsNotificationEventsIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @InjectMock
    PubSubService pubsub;

    @TestHTTPResource
    URI endpoint;

    private final List<PubsubMessage> published = new ArrayList<>();

    @BeforeEach
    void capturePublishedMessages() {
        published.clear();
        doAnswer(call -> {
            List<PubsubMessage> messages = call.getArgument(1);
            synchronized (published) {
                published.addAll(messages);
            }
            return List.of("synthetic-message");
        }).when(pubsub).publish(anyString(), anyList());
    }

    @Test
    void finalizeCarriesEventTimeAndTheConfigResourceName() {
        String bucket = createBucket(false);
        String id = createNotification(bucket, Map.of("custom_attributes", Map.of("team", "data")));
        Instant before = Instant.now();
        upload(bucket, "a.txt", "hello");

        PubsubMessage message = single("OBJECT_FINALIZE");
        Map<String, String> attributes = message.getAttributesMap();
        assertEquals("projects/_/buckets/" + bucket + "/notificationConfigs/" + id,
                attributes.get("notificationConfig"));
        assertEquals("JSON_API_V1", attributes.get("payloadFormat"));
        assertEquals(bucket, attributes.get("bucketId"));
        assertEquals("a.txt", attributes.get("objectId"));
        assertEquals("data", attributes.get("team"));
        assertFalse(attributes.containsKey("overwroteGeneration"));
        Instant eventTime = Instant.parse(attributes.get("eventTime"));
        assertFalse(eventTime.isBefore(before.minusSeconds(1)));
        assertFalse(eventTime.isAfter(Instant.now().plusSeconds(1)));
    }

    @Test
    void jsonPatchOfMetadataSendsMetadataUpdate() throws Exception {
        String bucket = createBucket(false);
        createNotification(bucket, Map.of());
        upload(bucket, "a.txt", "hello");
        published.clear();

        given().contentType("application/json").body(Map.of("metadata", Map.of("k", "v")))
                .patch("/storage/v1/b/" + bucket + "/o/a.txt").then().statusCode(200);

        PubsubMessage message = single("OBJECT_METADATA_UPDATE");
        assertNotNull(message.getAttributesMap().get("eventTime"));
        JsonNode payload = JSON.readTree(message.getData().toByteArray());
        assertEquals("2", payload.path("metageneration").asText());
        assertEquals("v", payload.path("metadata").path("k").asText());
    }

    @Test
    void jsonUpdateOfMetadataSendsMetadataUpdate() throws Exception {
        String bucket = createBucket(false);
        createNotification(bucket, Map.of("event_types", List.of("OBJECT_METADATA_UPDATE")));
        upload(bucket, "a.txt", "hello");

        given().contentType("application/json").body(Map.of("contentType", "video/mp4"))
                .put("/storage/v1/b/" + bucket + "/o/a.txt").then().statusCode(200);

        PubsubMessage message = single("OBJECT_METADATA_UPDATE");
        assertEquals("video/mp4", JSON.readTree(message.getData().toByteArray()).path("contentType").asText());
    }

    @Test
    void grpcUpdateObjectSendsMetadataUpdate() throws Exception {
        String bucket = createBucket(false);
        createNotification(bucket, Map.of("event_types", List.of("OBJECT_METADATA_UPDATE")));
        upload(bucket, "a.txt", "hello");

        ManagedChannel channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext().build();
        try {
            StorageGrpc.newBlockingStub(channel).updateObject(UpdateObjectRequest.newBuilder()
                    .setObject(com.google.storage.v2.Object.newBuilder()
                            .setBucket("projects/_/buckets/" + bucket)
                            .setName("a.txt")
                            .putMetadata("k", "v"))
                    .setUpdateMask(FieldMask.newBuilder().addPaths("metadata.k"))
                    .build());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }

        PubsubMessage message = single("OBJECT_METADATA_UPDATE");
        assertEquals("v", JSON.readTree(message.getData().toByteArray()).path("metadata").path("k").asText());
    }

    @Test
    void replacingAnObjectSendsFinalizeAndDeleteWithTheOtherGeneration() throws Exception {
        String bucket = createBucket(false);
        createNotification(bucket, Map.of());
        String first = upload(bucket, "a.txt", "one");
        String second = upload(bucket, "a.txt", "two");

        PubsubMessage finalize = events("OBJECT_FINALIZE").getLast();
        assertEquals(second, finalize.getAttributesMap().get("objectGeneration"));
        assertEquals(first, finalize.getAttributesMap().get("overwroteGeneration"));
        PubsubMessage delete = single("OBJECT_DELETE");
        assertEquals(first, delete.getAttributesMap().get("objectGeneration"));
        assertEquals(second, delete.getAttributesMap().get("overwrittenByGeneration"));
        assertTrue(JSON.readTree(delete.getData().toByteArray()).hasNonNull("timeDeleted"));
    }

    @Test
    void versionedBucketArchivesInsteadOfDeleting() {
        String bucket = createBucket(true);
        createNotification(bucket, Map.of());
        String first = upload(bucket, "a.txt", "one");
        String second = upload(bucket, "a.txt", "two");

        PubsubMessage archived = single("OBJECT_ARCHIVE");
        assertEquals(first, archived.getAttributesMap().get("objectGeneration"));
        assertEquals(second, archived.getAttributesMap().get("overwrittenByGeneration"));

        published.clear();
        given().delete("/storage/v1/b/" + bucket + "/o/a.txt").then().statusCode(204);
        assertEquals(second, single("OBJECT_ARCHIVE").getAttributesMap().get("objectGeneration"));
        assertTrue(events("OBJECT_DELETE").isEmpty());
    }

    @Test
    void emptyEventTypesSendsEveryEventType() {
        String bucket = createBucket(false);
        createNotification(bucket, Map.of("event_types", List.of()));
        String first = upload(bucket, "a.txt", "one");
        given().contentType("application/json").body(Map.of("metadata", Map.of("k", "v")))
                .patch("/storage/v1/b/" + bucket + "/o/a.txt").then().statusCode(200);
        upload(bucket, "a.txt", "two");
        given().delete("/storage/v1/b/" + bucket + "/o/a.txt").then().statusCode(204);

        assertEquals(2, events("OBJECT_FINALIZE").size());
        assertEquals(1, events("OBJECT_METADATA_UPDATE").size());
        assertEquals(2, events("OBJECT_DELETE").size());
        assertEquals(first, events("OBJECT_DELETE").getFirst().getAttributesMap().get("objectGeneration"));
    }

    @Test
    void payloadFormatNoneSendsAttributesWithoutData() {
        String bucket = createBucket(false);
        String id = createNotification(bucket, Map.of("payload_format", "NONE"));
        upload(bucket, "a.txt", "one");
        given().contentType("application/json").body(Map.of("metadata", Map.of("k", "v")))
                .patch("/storage/v1/b/" + bucket + "/o/a.txt").then().statusCode(200);
        upload(bucket, "a.txt", "two");
        given().delete("/storage/v1/b/" + bucket + "/o/a.txt").then().statusCode(204);

        assertEquals(2, events("OBJECT_FINALIZE").size());
        assertEquals(1, events("OBJECT_METADATA_UPDATE").size());
        assertEquals(2, events("OBJECT_DELETE").size());
        assertNoneFormat(published, bucket, id);
    }

    @Test
    void payloadFormatNoneArchiveCarriesNoData() {
        String bucket = createBucket(true);
        String id = createNotification(bucket, Map.of("payload_format", "NONE"));
        upload(bucket, "a.txt", "one");
        upload(bucket, "a.txt", "two");
        given().delete("/storage/v1/b/" + bucket + "/o/a.txt").then().statusCode(204);

        assertEquals(2, events("OBJECT_ARCHIVE").size());
        assertNoneFormat(published, bucket, id);
    }

    @Test
    void objectNamePrefixFiltersMetadataUpdateReplaceAndDelete() {
        String bucket = createBucket(false);
        createNotification(bucket, Map.of("object_name_prefix", "in/"));
        upload(bucket, "in/a.txt", "one");
        upload(bucket, "out/a.txt", "one");
        published.clear();

        for (String name : List.of("in/a.txt", "out/a.txt")) {
            String encoded = name.replace("/", "%2F");
            given().urlEncodingEnabled(false).contentType("application/json")
                    .body(Map.of("metadata", Map.of("k", "v")))
                    .patch("/storage/v1/b/" + bucket + "/o/" + encoded).then().statusCode(200);
            upload(bucket, name, "two");
            given().urlEncodingEnabled(false)
                    .delete("/storage/v1/b/" + bucket + "/o/" + encoded).then().statusCode(204);
        }

        synchronized (published) {
            assertFalse(published.isEmpty());
            assertTrue(published.stream().allMatch(m -> "in/a.txt".equals(m.getAttributesMap().get("objectId"))),
                    "messages: " + published);
        }
        assertEquals(1, events("OBJECT_METADATA_UPDATE").size());
        assertEquals(1, events("OBJECT_FINALIZE").size());
        assertEquals(2, events("OBJECT_DELETE").size());
    }

    @Test
    void xmlPutOverAnExistingObjectSendsFinalizeAndDelete() {
        String bucket = createBucket(false);
        createNotification(bucket, Map.of());
        String first = upload(bucket, "a.txt", "one");
        published.clear();

        String second = given().contentType("text/plain").body("two")
                .put("/" + bucket + "/a.txt").then().statusCode(200)
                .extract().header("x-goog-generation");

        assertReplaced(first, second);
    }

    @Test
    void copyOntoAnExistingObjectSendsFinalizeAndDelete() {
        String bucket = createBucket(false);
        createNotification(bucket, Map.of());
        upload(bucket, "src.txt", "source");
        String first = upload(bucket, "a.txt", "one");
        published.clear();

        String second = given().contentType("application/json").body("{}")
                .post("/storage/v1/b/" + bucket + "/o/src.txt/copyTo/b/" + bucket + "/o/a.txt")
                .then().statusCode(200).extract().path("generation");

        assertReplaced(first, second);
    }

    @Test
    void composeOntoAnExistingObjectSendsFinalizeAndDelete() {
        String bucket = createBucket(false);
        createNotification(bucket, Map.of());
        upload(bucket, "p1.txt", "one");
        upload(bucket, "p2.txt", "two");
        String first = upload(bucket, "a.txt", "old");
        published.clear();

        String second = given().contentType("application/json")
                .body(Map.of("sourceObjects", List.of(Map.of("name", "p1.txt"), Map.of("name", "p2.txt"))))
                .post("/storage/v1/b/" + bucket + "/o/a.txt/compose")
                .then().statusCode(200).extract().path("generation");

        assertReplaced(first, second);
    }

    private void assertReplaced(String first, String second) {
        assertNotNull(second);
        PubsubMessage finalize = single("OBJECT_FINALIZE");
        assertEquals("a.txt", finalize.getAttributesMap().get("objectId"));
        assertEquals(second, finalize.getAttributesMap().get("objectGeneration"));
        assertEquals(first, finalize.getAttributesMap().get("overwroteGeneration"));
        PubsubMessage delete = single("OBJECT_DELETE");
        assertEquals("a.txt", delete.getAttributesMap().get("objectId"));
        assertEquals(first, delete.getAttributesMap().get("objectGeneration"));
        assertEquals(second, delete.getAttributesMap().get("overwrittenByGeneration"));
    }

    private static void assertNoneFormat(List<PubsubMessage> messages, String bucket, String id) {
        synchronized (messages) {
            assertFalse(messages.isEmpty());
            for (PubsubMessage message : messages) {
                Map<String, String> attributes = message.getAttributesMap();
                assertEquals("NONE", attributes.get("payloadFormat"), attributes.toString());
                assertEquals("projects/_/buckets/" + bucket + "/notificationConfigs/" + id,
                        attributes.get("notificationConfig"));
                assertNotNull(attributes.get("eventTime"));
                assertTrue(message.getData().isEmpty(), attributes.get("eventType") + " carried data");
            }
        }
    }

    @Test
    void restoringOverALiveObjectSendsFinalizeAndDeleteWithTheOtherGeneration() throws Exception {
        String bucket = createBucket(false);
        createNotification(bucket, Map.of());
        String softDeleted = upload(bucket, "a.txt", "one");
        given().delete("/storage/v1/b/" + bucket + "/o/a.txt").then().statusCode(204);
        String displaced = upload(bucket, "a.txt", "two");
        published.clear();

        String restored = restore(bucket, "a.txt", softDeleted);

        PubsubMessage finalize = single("OBJECT_FINALIZE");
        assertEquals(restored, finalize.getAttributesMap().get("objectGeneration"));
        assertEquals(displaced, finalize.getAttributesMap().get("overwroteGeneration"));
        PubsubMessage delete = single("OBJECT_DELETE");
        assertEquals(displaced, delete.getAttributesMap().get("objectGeneration"));
        assertEquals(restored, delete.getAttributesMap().get("overwrittenByGeneration"));
        assertFalse(delete.getAttributesMap().containsKey("overwroteGeneration"));
        assertTrue(JSON.readTree(delete.getData().toByteArray()).hasNonNull("timeDeleted"));
        assertTrue(events("OBJECT_ARCHIVE").isEmpty());

        List<String> softDeletedGenerations = given().queryParam("softDeleted", true)
                .get("/storage/v1/b/" + bucket + "/o").then().statusCode(200)
                .extract().jsonPath().getList("items.generation", String.class);
        assertTrue(softDeletedGenerations.contains(displaced), softDeletedGenerations.toString());
    }

    @Test
    void restoringOverALiveObjectInAVersionedBucketArchivesTheDisplacedOne() {
        String bucket = createBucket(true);
        createNotification(bucket, Map.of());
        String softDeleted = upload(bucket, "a.txt", "one");
        given().delete("/storage/v1/b/" + bucket + "/o/a.txt").then().statusCode(204);
        given().queryParam("generation", softDeleted)
                .delete("/storage/v1/b/" + bucket + "/o/a.txt").then().statusCode(204);
        String displaced = upload(bucket, "a.txt", "two");
        published.clear();

        String restored = restore(bucket, "a.txt", softDeleted);

        PubsubMessage finalize = single("OBJECT_FINALIZE");
        assertEquals(restored, finalize.getAttributesMap().get("objectGeneration"));
        assertEquals(displaced, finalize.getAttributesMap().get("overwroteGeneration"));
        PubsubMessage archived = single("OBJECT_ARCHIVE");
        assertEquals(displaced, archived.getAttributesMap().get("objectGeneration"));
        assertEquals(restored, archived.getAttributesMap().get("overwrittenByGeneration"));
        assertTrue(events("OBJECT_DELETE").isEmpty());
    }

    @Test
    void restoringWithNoLiveObjectSendsOnlyAFinalize() {
        String bucket = createBucket(false);
        createNotification(bucket, Map.of());
        String softDeleted = upload(bucket, "a.txt", "one");
        given().delete("/storage/v1/b/" + bucket + "/o/a.txt").then().statusCode(204);
        published.clear();

        String restored = restore(bucket, "a.txt", softDeleted);

        PubsubMessage finalize = single("OBJECT_FINALIZE");
        assertEquals(restored, finalize.getAttributesMap().get("objectGeneration"));
        assertFalse(finalize.getAttributesMap().containsKey("overwroteGeneration"));
        assertEquals(1, published.size(), published.toString());
    }

    private String restore(String bucket, String name, String generation) {
        return given().queryParam("generation", generation)
                .post("/storage/v1/b/" + bucket + "/o/" + name + "/restore")
                .then().statusCode(200).extract().path("generation");
    }

    private String createBucket(boolean versioned) {
        String bucket = "notif-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> body = versioned
                ? Map.of("name", bucket, "versioning", Map.of("enabled", true))
                : Map.of("name", bucket);
        given().contentType("application/json").body(body)
                .post("/storage/v1/b?project=test-project").then().statusCode(200);
        return bucket;
    }

    private String createNotification(String bucket, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(extra);
        body.put("topic", "projects/test-project/topics/" + bucket);
        body.putIfAbsent("payload_format", "JSON_API_V1");
        return given().contentType("application/json").body(body)
                .post("/storage/v1/b/" + bucket + "/notificationConfigs")
                .then().statusCode(200).extract().path("id");
    }

    private String upload(String bucket, String name, String content) {
        return given().contentType("text/plain").body(content)
                .post("/upload/storage/v1/b/" + bucket + "/o?uploadType=media&name=" + name)
                .then().statusCode(200).extract().path("generation");
    }

    private List<PubsubMessage> events(String eventType) {
        synchronized (published) {
            return published.stream()
                    .filter(m -> eventType.equals(m.getAttributesMap().get("eventType")))
                    .toList();
        }
    }

    private PubsubMessage single(String eventType) {
        List<PubsubMessage> matching = events(eventType);
        assertEquals(1, matching.size(), eventType + " messages: " + published);
        return matching.getFirst();
    }
}
