package io.floci.gcp.services.cloudlogging;

import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.gcs.GcsService;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(CloudLoggingExportIamIntegrationTest.EnforceProfile.class)
class CloudLoggingExportIamIntegrationTest {
    @Inject
    GcsService storage;
    @Inject
    IamService iam;
    @Inject
    CredentialTokenService tokens;
    @Inject
    CloudLoggingSinkService sinks;

    @Test
    void exportUsesWriterGrantAndDestinationProjectRatherThanIngestionCaller() {
        String id = "export-iam-" + UUID.randomUUID();
        String bucket = id;
        String parent = "projects/test-project";
        String name = parent + "/sinks/" + id;
        String log = parent + "/logs/" + id;
        storage.createBucket(bucket, "export-destination", "http://localhost", Map.of());
        String writer = given().queryParam("uniqueWriterIdentity", true).contentType("application/json")
                .body(Map.of("name", id, "destination", "storage.googleapis.com/" + bucket, "filter", "logName=\"" + log + "\""))
                .post("/v2/" + parent + "/sinks").then().statusCode(200).extract().path("writerIdentity");
        String callerEmail = "logging-caller@example.test";
        String caller = "Bearer " + tokens.mintImpersonatedToken(callerEmail, Instant.now().plusSeconds(600)).getTokenValue();
        try {
            iam.setPolicy("buckets/" + bucket, grant("serviceAccount:" + callerEmail, "roles/storage.objectCreator"));
            write(log, caller);
            assertTrue(storage.listObjects(bucket).isEmpty(), "The logging caller's bucket grant must not grant export access");
            iam.setPolicy("buckets/" + bucket, grant(writer, "roles/storage.objectViewer"));
            write(log, caller);
            assertTrue(storage.listObjects(bucket).isEmpty(), "Read permission cannot authorize export");
            iam.setPolicy("buckets/" + bucket, grant(writer + "-wrong", "roles/storage.objectCreator"));
            write(log, caller);
            assertTrue(storage.listObjects(bucket).isEmpty());
            iam.setPolicy("buckets/" + bucket, new StoredPolicy());
            iam.setPolicy(parent, grant(writer, "roles/storage.objectCreator"));
            write(log, caller);
            assertTrue(storage.listObjects(bucket).isEmpty(), "The source project grant must not authorize another project's bucket");
            iam.setPolicy("projects/export-destination", grant(writer, "roles/storage.objectCreator"));
            write(log, caller);
            assertEquals(1, storage.listObjects(bucket).size(), "Destination project policy must be inherited");
            iam.setPolicy("projects/export-destination", new StoredPolicy());
            iam.setPolicy("buckets/" + bucket, grant(writer, "roles/storage.objectCreator"));
            write(log, caller);
            assertEquals(2, storage.listObjects(bucket).size(), "Object creator needs no delete permission for fresh shards");
            iam.setPolicy("buckets/" + bucket, new StoredPolicy());
            write(log, caller);
            assertEquals(2, storage.listObjects(bucket).size(), "Revocation must affect the next export");
        } finally {
            sinks.deleteSink(name);
            iam.setPolicy(parent, new StoredPolicy());
            iam.setPolicy("projects/export-destination", new StoredPolicy());
        }
    }

    @Test
    void conditionalWriterGrantsAreEvaluatedAgainstTheExportObject() {
        String id = "conditional-export-" + UUID.randomUUID();
        String parent = "projects/test-project";
        storage.createBucket(id, "condition-destination", "http://localhost", Map.of("iamConfiguration",
                Map.of("uniformBucketLevelAccess", Map.of("enabled", true))));
        String writer = given().queryParam("uniqueWriterIdentity", true).contentType("application/json")
                .body(Map.of("name", id, "destination", "storage.googleapis.com/" + id))
                .post("/v2/" + parent + "/sinks").then().statusCode(200).extract().path("writerIdentity");
        StoredPolicy policy = new StoredPolicy();
        policy.setVersion(3);
        policy.setBindings(List.of(Map.of("role", "roles/storage.objectCreator", "members", List.of(writer),
                "condition", Map.of("title", "only-approved", "expression",
                        "resource.name.startsWith('projects/_/buckets/" + id + "/objects/approved/')"))));
        iam.setPolicy("buckets/" + id, policy);
        try {
            write(parent + "/logs/denied", "Bearer external-token");
            assertTrue(storage.listObjects(id).isEmpty());
            write(parent + "/logs/approved", "Bearer external-token");
            assertEquals(1, storage.listObjects(id).size());
        } finally {
            sinks.deleteSink(parent + "/sinks/" + id);
        }
    }

    private void write(String log, String authorization) {
        given().urlEncodingEnabled(false).header("Authorization", authorization).contentType("application/json")
                .body(Map.of("logName", log, "entries", List.of(Map.of("textPayload", "accepted"))))
                .post("/v2/entries:write").then().statusCode(200);
    }

    private static StoredPolicy grant(String member, String role) {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of("role", role, "members", List.of(member))));
        return policy;
    }

    public static class EnforceProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.iam.authorization-mode", "enforce");
        }
    }
}
