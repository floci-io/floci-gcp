package io.floci.gcp.services.cloudlogging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.MonitoredResource;
import com.google.logging.type.LogSeverity;
import com.google.logging.v2.ConfigServiceV2Grpc;
import com.google.logging.v2.CreateSinkRequest;
import com.google.logging.v2.LogEntry;
import com.google.logging.v2.ListLogEntriesRequest;
import com.google.logging.v2.LogExclusion;
import com.google.protobuf.FieldMask;
import com.google.logging.v2.LogSink;
import com.google.logging.v2.LoggingServiceV2Grpc;
import com.google.logging.v2.WriteLogEntriesRequest;
import io.floci.gcp.core.common.ProjectRecord;
import io.floci.gcp.core.common.ProjectRegistry;
import io.floci.gcp.services.gcs.GcsService;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CloudLoggingExportIntegrationTest {
    @Inject
    GcsService storage;
    @Inject
    CloudLoggingSinkService sinks;
    @Inject
    ProjectRegistry projects;
    @Inject
    ObjectMapper json;
    @TestHTTPResource
    URI endpoint;

    @Test
    void restAndGrpcIngestionExportFilteredEntriesWithDefaultsAndExclusions() throws Exception {
        String bucket = bucket();
        String parent = "projects/test-project";
        String id = "export-" + UUID.randomUUID();
        String log = parent + "/logs/audit%2F" + id;
        ManagedChannel channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
        try {
            ConfigServiceV2Grpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
                    .createSink(CreateSinkRequest.newBuilder().setParent(parent).setUniqueWriterIdentity(true)
                            .setSink(LogSink.newBuilder().setName(id).setDestination("storage.googleapis.com/" + bucket)
                                    .setFilter("logName=\"" + log + "\" AND severity>=WARNING AND resource.labels.project_id=test-project")
                                    .addExclusions(LogExclusion.newBuilder().setName("noise").setFilter("labels.noise=true")))
                            .build());
            Map<String, Object> request = Map.of("logName", log, "resource", Map.of("type", "global", "labels", Map.of("project_id", "test-project")),
                    "labels", Map.of("env", "prod"), "entries", List.of(
                            Map.of("severity", "ERROR", "jsonPayload", Map.of("message", "kept", "count", 3), "timestamp", "2026-01-02T03:04:05Z"),
                            Map.of("severity", "INFO", "textPayload", "filtered"),
                            Map.of("severity", "ERROR", "textPayload", "excluded", "labels", Map.of("noise", "true"))));
            given().urlEncodingEnabled(false).contentType("application/json").body(request).post("/v2/entries:write").then().statusCode(200);
            List<GcsObjectMeta> objects = storage.listObjects(bucket);
            assertEquals(1, objects.size());
            String name = objects.getFirst().getName();
            assertTrue(name.matches("audit/" + id + "/2026/01/02/03:00:00_03:59:59_A[0-9]+:[0-9]+\\.json"), name);
            assertEquals("application/json", objects.getFirst().getContentType());
            JsonNode entries = json.readTree(storage.getObjectData(bucket, name));
            assertTrue(entries.isArray());
            assertEquals(1, entries.size());
            assertEquals(3, entries.get(0).path("jsonPayload").path("count").asInt());
            assertEquals("prod", entries.get(0).path("labels").path("env").asText());
            assertFalse(entries.get(0).has("sequence"));
            assertTrue(entries.get(0).has("receiveTimestamp"));
            assertFalse(entries.get(0).path("insertId").asText().isEmpty());

            LoggingServiceV2Grpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
                    .writeLogEntries(WriteLogEntriesRequest.newBuilder().setLogName(log)
                            .setResource(MonitoredResource.newBuilder().setType("global").putLabels("project_id", "test-project"))
                            .addEntries(LogEntry.newBuilder().setSeverity(LogSeverity.WARNING).setTextPayload("grpc-kept")).build());
            assertEquals(2, storage.listObjects(bucket).size());
            assertTrue(storage.listObjects(bucket).stream().anyMatch(meta -> new String(storage.getObjectData(bucket, meta.getName()),
                    StandardCharsets.UTF_8).contains("grpc-kept")));
            assertArrayEquals(json.writeValueAsBytes(entries), json.writeValueAsBytes(json.readTree(storage.getObjectData(bucket, name))));
        } finally {
            sinks.deleteSink(parent + "/sinks/" + id);
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void dryRunDisabledNonmatchingAndDeletedSinksNeverPublish() {
        String bucket = bucket();
        String parent = "projects/test-project";
        String id = "disabled-" + UUID.randomUUID();
        String log = parent + "/logs/" + id;
        LogSink request = LogSink.newBuilder().setName(id).setDestination("storage.googleapis.com/" + bucket).build();
        sinks.createSink(parent, request, true);
        Map<String, Object> body = Map.of("logName", log, "entries", List.of(Map.of("textPayload", "ignored")), "dryRun", true);
        given().urlEncodingEnabled(false).contentType("application/json").body(body).post("/v2/entries:write").then().statusCode(200);
        assertTrue(storage.listObjects(bucket).isEmpty());
        sinks.updateSink(parent + "/sinks/" + id, request.toBuilder().setDisabled(true).build(),
                FieldMask.newBuilder().addPaths("disabled").build(), true);
        write(log);
        assertTrue(storage.listObjects(bucket).isEmpty());
        sinks.deleteSink(parent + "/sinks/" + id);
        write(log);
        assertTrue(storage.listObjects(bucket).isEmpty());
    }

    @Test
    void destinationFailureAndUnsupportedExclusionsDoNotLeakOrRejectAcceptedEntries() {
        String bucket = bucket();
        String parent = "projects/test-project";
        String id = "fail-" + UUID.randomUUID();
        String log = parent + "/logs/" + id;
        LogSink request = LogSink.newBuilder().setName(id).setDestination("storage.googleapis.com/" + bucket)
                .setFilter("logName=\"" + log + "\"").build();
        sinks.createSink(parent, request.toBuilder().setName(id + "-missing").setDestination("storage.googleapis.com/missing-" + id).build(), true);
        sinks.createSink(parent, request.toBuilder().setName(id + "-unsafe").addExclusions(LogExclusion.newBuilder()
                .setName("unsupported").setFilter("protoPayload.methodName:secret")).build(), true);
        sinks.createSink(parent, request, true);
        try {
            write(log);
            assertEquals(1, storage.listObjects(bucket).size());
            given().urlEncodingEnabled(false).contentType("application/json").body(Map.of("resourceNames", List.of(parent), "filter", "logName=\"" + log + "\""))
                    .post("/v2/entries:list").then().statusCode(200);
        } finally {
            for (String suffix : List.of("", "-missing", "-unsafe")) {
                sinks.deleteSink(parent + "/sinks/" + id + suffix);
            }
        }
    }

    @Test
    void aggregatedSinksUseOnlyKnownHierarchyAndIncludeChildren() {
        String bucket = bucket();
        String project = "export-project-" + UUID.randomUUID();
        ProjectRecord record = new ProjectRecord();
        record.setProjectId(project);
        record.setParentType("folder");
        record.setParentId("812345");
        projects.createOrAdopt(record);
        String id = "aggregate-" + UUID.randomUUID();
        String parent = "folders/812345";
        LogSink request = LogSink.newBuilder().setName(id).setDestination("storage.googleapis.com/" + bucket).build();
        sinks.createSink(parent, request, true);
        try {
            write("projects/" + project + "/logs/test");
            assertTrue(storage.listObjects(bucket).isEmpty());
            sinks.updateSink(parent + "/sinks/" + id, request.toBuilder().setIncludeChildren(true).build(),
                    FieldMask.newBuilder().addPaths("include_children").build(), true);
            write("projects/unrelated-export-project/logs/test");
            assertTrue(storage.listObjects(bucket).isEmpty());
            write("projects/" + project + "/logs/test");
            assertEquals(1, storage.listObjects(bucket).size());
        } finally {
            sinks.deleteSink(parent + "/sinks/" + id);
        }
    }

    @Test
    void legacyWritersCannotExportToAnotherProjectsBucket() {
        String parent = "projects/test-project";
        String id = "legacy-export-" + UUID.randomUUID();
        String otherBucket = bucket();
        String ownBucket = "legacy-own-" + UUID.randomUUID();
        storage.createBucket(ownBucket, "test-project", endpoint.toString(), Map.of());
        LogSink sink = LogSink.newBuilder().setName(id).setDestination("storage.googleapis.com/" + otherBucket).build();
        sinks.createSink(parent, sink, false);
        try {
            write(parent + "/logs/legacy");
            assertTrue(storage.listObjects(otherBucket).isEmpty());
            sinks.updateSink(parent + "/sinks/" + id, sink.toBuilder().setDestination("storage.googleapis.com/" + ownBucket).build(),
                    FieldMask.newBuilder().addPaths("destination").build(), false);
            write(parent + "/logs/legacy");
            assertEquals(1, storage.listObjects(ownBucket).size());
        } finally {
            sinks.deleteSink(parent + "/sinks/" + id);
        }
    }

    @Test
    void legacyBucketOwnershipResolvesBothProjectIdAndNumber() {
        String id = "legacy-alias-" + UUID.randomUUID();
        String number = projects.require("test-project").getProjectNumber();
        storage.createBucket(id, number, endpoint.toString(), Map.of());
        String parent = "projects/test-project";
        sinks.createSink(parent, LogSink.newBuilder().setName(id).setDestination("storage.googleapis.com/" + id).build(), false);
        try {
            write(parent + "/logs/aliases");
            assertEquals(1, storage.listObjects(id).size());
        } finally {
            sinks.deleteSink(parent + "/sinks/" + id);
        }
    }

    @Test
    void mixedJsonTypesAndNumericSeverityPreserveMatchingBatchEntries() throws Exception {
        String bucket = bucket();
        String parent = "projects/test-project";
        String id = "conversion-" + UUID.randomUUID();
        String log = parent + "/logs/" + id;
        sinks.createSink(parent, LogSink.newBuilder().setName(id).setDestination("storage.googleapis.com/" + bucket)
                .setFilter("logName=\"" + log + "\" AND jsonPayload.kind=match")
                .addExclusions(LogExclusion.newBuilder().setName("noise").setFilter("jsonPayload.noise=skip")).build(), true);
        try {
            given().urlEncodingEnabled(false).contentType("application/json").body(Map.of("logName", log,
                    "entries", List.of(
                            Map.of("severity", "ERROR", "jsonPayload", Map.of("kind", 1)),
                            Map.of("severity", "ERROR", "jsonPayload", Map.of("kind", true)),
                            Map.of("severity", "ERROR", "jsonPayload", Map.of("kind", Map.of("nested", "match"))),
                            Map.of("severity", 500, "jsonPayload", Map.of("kind", "match", "noise", Map.of("nested", "skip"))),
                            Map.of("severity", "ERROR", "jsonPayload", Map.of("kind", "match", "noise", 1)))))
                    .post("/v2/entries:write").then().statusCode(200);
            List<GcsObjectMeta> objects = storage.listObjects(bucket);
            assertEquals(1, objects.size());
            JsonNode exported = json.readTree(storage.getObjectData(bucket, objects.getFirst().getName()));
            assertEquals(2, exported.size());
            for (JsonNode entry : exported) {
                assertEquals("ERROR", entry.path("severity").asText());
            }
            ManagedChannel channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
            try {
                assertEquals(5, LoggingServiceV2Grpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)
                        .listLogEntries(ListLogEntriesRequest.newBuilder().addResourceNames(parent)
                                .setFilter("logName=\"" + log + "\"").build()).getEntriesList().stream()
                        .filter(entry -> entry.getSeverity() == LogSeverity.ERROR).count());
            } finally {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        } finally {
            sinks.deleteSink(parent + "/sinks/" + id);
        }
    }

    @Test
    void billingAccountSinksReceiveOwnedLogsWithoutTreatingBillingLinksAsAncestry() {
        String bucket = bucket();
        String project = "billing-export-" + UUID.randomUUID();
        String parent = "billingAccounts/abcdef";
        ProjectRecord record = new ProjectRecord();
        record.setProjectId(project);
        record.setBillingAccountName(parent);
        projects.createOrAdopt(record);
        String id = "billing-" + UUID.randomUUID();
        sinks.createSink(parent, LogSink.newBuilder().setName(id).setDestination("storage.googleapis.com/" + bucket)
                .setIncludeChildren(true).build(), true);
        try {
            write("projects/" + project + "/logs/test");
            assertTrue(storage.listObjects(bucket).isEmpty());
            write(parent + "/logs/test");
            assertEquals(1, storage.listObjects(bucket).size());
        } finally {
            sinks.deleteSink(parent + "/sinks/" + id);
        }
    }

    private String bucket() {
        String bucket = "export-" + UUID.randomUUID();
        storage.createBucket(bucket, "destination-project", endpoint.toString(), Map.of());
        return bucket;
    }

    private void write(String log) {
        given().urlEncodingEnabled(false).contentType("application/json").body(Map.of("logName", log, "entries", List.of(Map.of("textPayload", "entry"))))
                .post("/v2/entries:write").then().statusCode(200);
    }
}
