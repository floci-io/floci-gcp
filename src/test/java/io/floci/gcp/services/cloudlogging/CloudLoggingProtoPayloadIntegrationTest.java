package io.floci.gcp.services.cloudlogging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.MonitoredResource;
import com.google.cloud.audit.AuditLog;
import com.google.cloud.audit.AuthenticationInfo;
import com.google.cloud.audit.AuthorizationInfo;
import com.google.cloud.audit.RequestMetadata;
import com.google.logging.type.LogSeverity;
import com.google.logging.v2.ListLogEntriesRequest;
import com.google.logging.v2.LogEntry;
import com.google.logging.v2.LogEntryOperation;
import com.google.logging.v2.LogSink;
import com.google.logging.v2.LoggingServiceV2Grpc;
import com.google.logging.v2.WriteLogEntriesRequest;
import com.google.protobuf.Any;
import com.google.protobuf.Duration;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.rpc.Status;
import io.floci.gcp.services.gcs.GcsService;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudLoggingProtoPayloadIntegrationTest {
    private static final String PARENT = "projects/test-project";
    private static final String AUDIT_LOG_TYPE = "type.googleapis.com/google.cloud.audit.AuditLog";

    @Inject
    GcsService storage;
    @Inject
    CloudLoggingSinkService sinks;
    @Inject
    ObjectMapper json;
    @TestHTTPResource
    URI endpoint;

    private ManagedChannel channel;
    private LoggingServiceV2Grpc.LoggingServiceV2BlockingStub logging;
    private String log;

    @BeforeEach
    void setUp() {
        channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
        logging = LoggingServiceV2Grpc.newBlockingStub(channel);
        log = PARENT + "/logs/cloudaudit.googleapis.com%2Factivity-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void grpcAuditLogPayloadAndOperationReadBackOverBothTransports() {
        AuditLog audit = auditLog("SetIamPolicy");
        LogEntryOperation operation = LogEntryOperation.newBuilder().setId("operation-1")
                .setProducer("cloudresourcemanager.googleapis.com").setFirst(true).setLast(true).build();
        writeGrpc(LogEntry.newBuilder().setSeverity(LogSeverity.NOTICE).setProtoPayload(Any.pack(audit))
                .setOperation(operation).build());

        LogEntry read = listGrpc().getFirst();
        assertEquals(LogEntry.PayloadCase.PROTO_PAYLOAD, read.getPayloadCase());
        assertEquals(AUDIT_LOG_TYPE, read.getProtoPayload().getTypeUrl());
        assertEquals(audit, unpack(read));
        assertEquals(operation, read.getOperation());

        JsonPath rest = listRest();
        assertEquals(AUDIT_LOG_TYPE, rest.getString("entries[0].protoPayload.'@type'"));
        assertEquals("SetIamPolicy", rest.getString("entries[0].protoPayload.methodName"));
        assertEquals("admin@example.com",
                rest.getString("entries[0].protoPayload.authenticationInfo.principalEmail"));
        assertEquals("203.0.113.7", rest.getString("entries[0].protoPayload.requestMetadata.callerIp"));
        assertEquals("resourcemanager.projects.setIamPolicy",
                rest.getString("entries[0].protoPayload.authorizationInfo[0].permission"));
        assertEquals(true, rest.getBoolean("entries[0].protoPayload.authorizationInfo[0].granted"));
        assertEquals("ADMIN_WRITE", rest.getString("entries[0].protoPayload.authorizationInfo[0].permissionType"));
        assertEquals(Map.of(), rest.getMap("entries[0].protoPayload.status"));
        assertEquals("project-1", rest.getString("entries[0].protoPayload.request.resource"));
        assertEquals("operation-1", rest.getString("entries[0].operation.id"));
        assertEquals(true, rest.getBoolean("entries[0].operation.first"));
        assertFalse(rest.getMap("entries[0]").containsKey("jsonPayload"));
    }

    @Test
    void restAuditLogPayloadReadsBackAsTheSameProtoOverGrpc() {
        Map<String, Object> payload = Map.of(
                "@type", AUDIT_LOG_TYPE,
                "serviceName", "iam.googleapis.com",
                "methodName", "google.iam.admin.v1.CreateServiceAccountKey",
                "resourceName", "projects/-/serviceAccounts/123",
                "authenticationInfo", Map.of("principalEmail", "admin@example.com"),
                "authorizationInfo", List.of(Map.of("resource", "projects/-/serviceAccounts/123",
                        "permission", "iam.serviceAccountKeys.create", "granted", true,
                        "permissionType", "ADMIN_WRITE")),
                "status", Map.of("code", 7, "message", "denied"),
                "numResponseItems", "3");
        writeRest(Map.of("severity", "ERROR", "protoPayload", payload,
                "operation", Map.of("id", "op-2", "producer", "iam.googleapis.com", "last", true)));

        LogEntry read = listGrpc().getFirst();
        AuditLog audit = unpack(read);
        assertEquals("google.iam.admin.v1.CreateServiceAccountKey", audit.getMethodName());
        assertEquals(7, audit.getStatus().getCode());
        assertEquals(3, audit.getNumResponseItems());
        assertEquals(AuthorizationInfo.PermissionType.ADMIN_WRITE, audit.getAuthorizationInfo(0).getPermissionType());
        assertEquals("op-2", read.getOperation().getId());
        assertTrue(read.getOperation().getLast());

        JsonPath rest = listRest();
        assertEquals("3", rest.getString("entries[0].protoPayload.numResponseItems"));
        assertEquals(7, rest.getInt("entries[0].protoPayload.status.code"));
    }

    @Test
    void unsupportedPayloadTypesAreDroppedAndTheEntryIsStillWritten() {
        writeGrpc(LogEntry.newBuilder().setProtoPayload(Any.pack(Duration.newBuilder().setSeconds(5).build()))
                .putLabels("kind", "grpc").build());
        writeRest(Map.of("protoPayload", Map.of("@type", "type.googleapis.com/example.Custom", "id", 1),
                "labels", Map.of("kind", "rest")));

        List<LogEntry> entries = listGrpc();
        assertEquals(2, entries.size());
        for (LogEntry entry : entries) {
            assertEquals(LogEntry.PayloadCase.PAYLOAD_NOT_SET, entry.getPayloadCase());
        }
    }

    @Test
    void anAuditLogPayloadThatDoesNotParseIsRejected() {
        given().urlEncodingEnabled(false).contentType("application/json")
                .body(Map.of("logName", log, "entries", List.of(Map.of("protoPayload",
                        Map.of("@type", AUDIT_LOG_TYPE, "status", "not an object")))))
                .post("/v2/entries:write")
                .then().statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));
        given().urlEncodingEnabled(false).contentType("application/json")
                .body(Map.of("logName", log, "entries", List.of(Map.of("textPayload", "x", "operation", "op"))))
                .post("/v2/entries:write")
                .then().statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));
        assertTrue(listGrpc().isEmpty());
    }

    @Test
    void sinksRouteOnAuditLogFieldsAndExportThePayload() throws Exception {
        String bucket = "audit-export-" + UUID.randomUUID();
        storage.createBucket(bucket, "test-project", endpoint.toString(), Map.of());
        String id = "audit-" + UUID.randomUUID();
        sinks.createSink(PARENT, LogSink.newBuilder().setName(id).setDestination("storage.googleapis.com/" + bucket)
                .setFilter("logName=\"" + log + "\" AND protoPayload.methodName=\"SetIamPolicy\" "
                        + "AND protoPayload.authorizationInfo.granted=true")
                .build(), true);
        try {
            writeGrpc(LogEntry.newBuilder().setProtoPayload(Any.pack(auditLog("SetIamPolicy"))).build());
            writeGrpc(LogEntry.newBuilder().setProtoPayload(Any.pack(auditLog("GetIamPolicy"))).build());

            List<GcsObjectMeta> objects = storage.listObjects(bucket);
            assertEquals(1, objects.size());
            JsonNode exported = json.readTree(storage.getObjectData(bucket, objects.getFirst().getName()));
            assertEquals(1, exported.size());
            JsonNode payload = exported.get(0).path("protoPayload");
            assertEquals(AUDIT_LOG_TYPE, payload.path("@type").asText());
            assertEquals("SetIamPolicy", payload.path("methodName").asText());
            assertEquals("admin@example.com", payload.path("authenticationInfo").path("principalEmail").asText());
        } finally {
            sinks.deleteSink(PARENT + "/sinks/" + id);
        }
    }

    private static AuditLog auditLog(String method) {
        return AuditLog.newBuilder()
                .setServiceName("cloudresourcemanager.googleapis.com")
                .setMethodName(method)
                .setResourceName("projects/project-1")
                .setStatus(Status.getDefaultInstance())
                .setAuthenticationInfo(AuthenticationInfo.newBuilder().setPrincipalEmail("admin@example.com"))
                .addAuthorizationInfo(AuthorizationInfo.newBuilder().setResource("projects/project-1")
                        .setPermission("resourcemanager.projects.setIamPolicy").setGranted(true)
                        .setPermissionType(AuthorizationInfo.PermissionType.ADMIN_WRITE))
                .setRequestMetadata(RequestMetadata.newBuilder().setCallerIp("203.0.113.7")
                        .setCallerSuppliedUserAgent("google-cloud-sdk gcloud/550.0.0"))
                .setRequest(Struct.newBuilder()
                        .putFields("@type", Value.newBuilder()
                                .setStringValue("type.googleapis.com/google.iam.v1.SetIamPolicyRequest").build())
                        .putFields("resource", Value.newBuilder().setStringValue("project-1").build()))
                .build();
    }

    private static AuditLog unpack(LogEntry entry) {
        try {
            return entry.getProtoPayload().unpack(AuditLog.class);
        } catch (InvalidProtocolBufferException e) {
            throw new AssertionError(e);
        }
    }

    private void writeGrpc(LogEntry entry) {
        logging.withDeadlineAfter(10, TimeUnit.SECONDS).writeLogEntries(WriteLogEntriesRequest.newBuilder()
                .setLogName(log)
                .setResource(MonitoredResource.newBuilder().setType("project").putLabels("project_id", "test-project"))
                .addEntries(entry).build());
    }

    private void writeRest(Map<String, Object> entry) {
        given().urlEncodingEnabled(false).contentType("application/json")
                .body(Map.of("logName", log, "resource", Map.of("type", "project"), "entries", List.of(entry)))
                .post("/v2/entries:write").then().statusCode(200);
    }

    private List<LogEntry> listGrpc() {
        return logging.withDeadlineAfter(10, TimeUnit.SECONDS).listLogEntries(ListLogEntriesRequest.newBuilder()
                .addResourceNames(PARENT).setFilter("logName=\"" + log + "\"").build()).getEntriesList();
    }

    private JsonPath listRest() {
        return given().urlEncodingEnabled(false).contentType("application/json")
                .body(Map.of("resourceNames", List.of(PARENT), "filter", "logName=\"" + log + "\""))
                .post("/v2/entries:list")
                .then().statusCode(200).extract().jsonPath();
    }
}
