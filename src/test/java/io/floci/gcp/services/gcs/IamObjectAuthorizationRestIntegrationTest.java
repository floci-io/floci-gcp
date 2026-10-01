package io.floci.gcp.services.gcs;

import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamObjectAuthorizationRestIntegrationTest.EnforceAuthorizationProfile.class)
class IamObjectAuthorizationRestIntegrationTest {

    private static final DateTimeFormatter V4_DATE_FORMATTER = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    @Inject IamService iamService;
    @Inject GcsService gcsService;
    @Inject CredentialTokenService tokenService;

    @Test
    void namedPrincipalCanCompleteResumableUploadWithoutAuthorizationHeader() {
        String bucket = createBucket();
        String serviceAccount = "object-writer@example.test";
        String authorization = bearer(serviceAccount);
        setRole(bucket, "roles/storage.objectAdmin", "serviceAccount:" + serviceAccount);

        given().contentType("application/json").body(Map.of("name", "principal/resumable.txt"))
                .when().post("/upload/storage/v1/b/{bucket}/o?uploadType=resumable", bucket)
                .then().statusCode(403);

        String location = given().header("Authorization", authorization)
                .contentType("application/json").body(Map.of("name", "principal/resumable.txt"))
                .when().post("/upload/storage/v1/b/{bucket}/o?uploadType=resumable", bucket)
                .then().statusCode(200).extract().header("Location");
        String uploadId = location.substring(location.indexOf("upload_id=") + "upload_id=".length());

        given().header("Content-Range", "bytes */*").body(new byte[0])
                .when().put("/upload/storage/v1/b/{bucket}/o?uploadType=resumable&upload_id=" + uploadId, bucket)
                .then().statusCode(308);
        given().contentType("text/plain").body("resumable")
                .when().put("/upload/storage/v1/b/{bucket}/o?uploadType=resumable&upload_id=" + uploadId, bucket)
                .then().statusCode(200);
        given().when().get("/storage/v1/b/{bucket}/o/principal/resumable.txt", bucket)
                .then().statusCode(403);
        given().header("Authorization", authorization)
                .when().get("/storage/v1/b/{bucket}/o/principal/resumable.txt", bucket)
                .then().statusCode(200);
    }

    @Test
    void resourceNameConditionRestrictsSlashContainingObjectNames() {
        String bucket = createBucket();
        String serviceAccount = "conditional-reader@example.test";
        String authorization = bearer(serviceAccount);
        gcsService.putObject(bucket, "reports/2026/july.csv", "text/csv", "report".getBytes(),
                GcsCustomerEncryption.none(), "http://localhost:4588");
        gcsService.putObject(bucket, "private/2026/july.csv", "text/csv", "private".getBytes(),
                GcsCustomerEncryption.none(), "http://localhost:4588");
        StoredPolicy policy = new StoredPolicy();
        policy.setVersion(3);
        policy.setBindings(List.of(Map.of(
                "role", "roles/storage.objectViewer",
                "members", List.of("serviceAccount:" + serviceAccount),
                "condition", Map.of(
                        "title", "reports",
                        "expression", "resource.name.startsWith('projects/_/buckets/" + bucket
                                + "/objects/reports/')"))));
        iamService.setPolicy("buckets/" + bucket, policy);

        given().header("Authorization", authorization)
                .when().get("/storage/v1/b/{bucket}/o/reports/2026/july.csv", bucket)
                .then().statusCode(200);
        given().header("Authorization", authorization)
                .when().get("/storage/v1/b/{bucket}/o/private/2026/july.csv", bucket)
                .then().statusCode(403);
    }

    @Test
    void owningProjectPolicyGrantsObjectReadThroughInheritance() {
        String bucket = createBucket();
        String serviceAccount = "project-object-reader@example.test";
        String authorization = bearer(serviceAccount);
        gcsService.putObject(bucket, "inherited.txt", "text/plain",
                "inherited".getBytes(StandardCharsets.UTF_8),
                GcsCustomerEncryption.none(), "http://localhost:4588");
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", "roles/storage.objectViewer",
                "members", List.of("serviceAccount:" + serviceAccount))));
        iamService.setPolicy("projects/test-project", policy);

        given().when().get("/storage/v1/b/{bucket}/o/inherited.txt", bucket)
                .then().statusCode(403);
        given().header("Authorization", authorization)
                .when().get("/storage/v1/b/{bucket}/o/inherited.txt", bucket)
                .then().statusCode(200);
    }

    @Test
    void objectViewerAllowsReadAndListButNotUpload() {
        String bucket = "iam-object-" + UUID.randomUUID().toString().substring(0, 8);
        given().contentType("application/json").body(Map.of("name", bucket))
                .when().post("/storage/v1/b?project=test-project").then().statusCode(200);
        gcsService.putObject(bucket, "reports/july.csv", "text/csv", "data".getBytes(),
                GcsCustomerEncryption.none(), "http://localhost:4588");
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", "roles/storage.objectViewer", "members", List.of("allUsers"))));
        iamService.setPolicy("buckets/" + bucket, policy);

        given().when().get("/storage/v1/b/" + bucket + "/o").then().statusCode(200);
        given().when().get("/storage/v1/b/" + bucket + "/o/reports/july.csv").then().statusCode(200);
        given().when().get("/{bucket}/{object}", bucket, "reports/july.csv").then().statusCode(200);
        given().when().get("/download/storage/v1/b/{bucket}/o/{object}", bucket, "reports/july.csv")
                .then().statusCode(200);
        given().contentType("text/plain").body("new")
                .when().post("/upload/storage/v1/b/" + bucket + "/o?uploadType=media&name=new.txt")
                .then().statusCode(403);
    }

    @Test
    void objectViewerCannotMutateJsonOrXmlObjects() {
        String bucket = createBucket();
        gcsService.putObject(bucket, "existing.txt", "text/plain", "original".getBytes(),
                GcsCustomerEncryption.none(), "http://localhost:4588");
        setRole(bucket, "roles/storage.objectViewer");

        given().contentType("application/json").body(Map.of("contentType", "text/csv"))
                .when().patch("/storage/v1/b/{bucket}/o/{object}", bucket, "existing.txt")
                .then().statusCode(403);
        given().header("X-HTTP-Method-Override", "PATCH").contentType("application/json")
                .body(Map.of("contentType", "text/csv"))
                .when().post("/storage/v1/b/{bucket}/o/{object}", bucket, "existing.txt")
                .then().statusCode(403);
        given().when().delete("/storage/v1/b/{bucket}/o/{object}", bucket, "existing.txt")
                .then().statusCode(403);
        given().when().delete("/{bucket}/{object}", bucket, "existing.txt")
                .then().statusCode(403)
                .contentType(containsString("application/xml"))
                .body("Error.Code", equalTo("AccessDenied"));
        given().contentType("text/plain").body("new")
                .when().put("/{bucket}/{object}", bucket, "xml-upload.txt")
                .then().statusCode(403);

        assertTrue(gcsService.objectExists(bucket, "existing.txt"));
        assertFalse(gcsService.objectExists(bucket, "xml-upload.txt"));

        setRole(bucket, "roles/storage.objectCreator");
        given().when().get("/{bucket}", bucket).then().statusCode(403);
    }

    @Test
    void unverifiedV4SignedUrlDoesNotBypassIam() {
        String bucket = createBucket();
        gcsService.putObject(bucket, "signed-download.txt", "text/plain", "download".getBytes(),
                GcsCustomerEncryption.none(), "http://localhost:4588");
        setRole(bucket, "roles/storage.objectViewer", "serviceAccount:private@example.test");

        given().when().get("/{bucket}/{object}", bucket, "signed-download.txt")
                .then().statusCode(403);
        signedRequest().when().get("/{bucket}/{object}", bucket, "signed-download.txt")
                .then().statusCode(403);
        signedRequest().contentType("text/plain").body("upload")
                .when().put("/{bucket}/{object}", bucket, "signed-upload.txt")
                .then().statusCode(403);
        signedRequest().when().delete("/{bucket}/{object}", bucket, "signed-download.txt")
                .then().statusCode(403);

        given().queryParam("X-Goog-Date", V4_DATE_FORMATTER.format(Instant.now()))
                .queryParam("X-Goog-Expires", "600")
                .when().get("/{bucket}/{object}", bucket, "signed-upload.txt")
                .then().statusCode(403);
        assertTrue(gcsService.objectExists(bucket, "signed-download.txt"));
        assertFalse(gcsService.objectExists(bucket, "signed-upload.txt"));
    }

    @Test
    void sourceDestinationAndReplacementChecksPreventObjectSideEffects() {
        String bucket = createBucket();
        gcsService.putObject(bucket, "source.txt", "text/plain", "source".getBytes(),
                GcsCustomerEncryption.none(), "http://localhost:4588");
        gcsService.putObject(bucket, "replacement.txt", "text/plain", "original".getBytes(),
                GcsCustomerEncryption.none(), "http://localhost:4588");

        setRole(bucket, "roles/storage.objectViewer");
        given().when().post("/storage/v1/b/{sourceBucket}/o/{source}/copyTo/b/{destinationBucket}/o/{destination}",
                bucket, "source.txt", bucket, "copied.txt").then().statusCode(403);
        given().when().post("/storage/v1/b/{sourceBucket}/o/{source}/rewriteTo/b/{destinationBucket}/o/{destination}",
                bucket, "source.txt", bucket, "rewritten.txt").then().statusCode(403);
        given().contentType("application/json").body(Map.of("sourceObjects", List.of(Map.of("name", "source.txt"))))
                .when().post("/storage/v1/b/{bucket}/o/{destination}/compose", bucket, "composed.txt")
                .then().statusCode(403);
        given().when().post("/storage/v1/b/{bucket}/o/{source}/moveTo/o/{destination}",
                bucket, "source.txt", "moved.txt").then().statusCode(403);

        assertTrue(gcsService.objectExists(bucket, "source.txt"));
        assertFalse(gcsService.objectExists(bucket, "copied.txt"));
        assertFalse(gcsService.objectExists(bucket, "rewritten.txt"));
        assertFalse(gcsService.objectExists(bucket, "composed.txt"));
        assertFalse(gcsService.objectExists(bucket, "moved.txt"));

        setRole(bucket, "roles/storage.objectCreator");
        given().contentType("text/plain").queryParam("uploadType", "media").queryParam("name", "replacement.txt")
                .body("replacement").when().post("/upload/storage/v1/b/{bucket}/o", bucket)
                .then().statusCode(403);
        assertTrue(gcsService.objectExists(bucket, "replacement.txt"));
    }

    @Test
    void objectCreatorCannotComposeAnUnreadableSource() {
        String bucket = createBucket();
        gcsService.putObject(bucket, "private-source.txt", "text/plain",
                "private".getBytes(StandardCharsets.UTF_8), GcsCustomerEncryption.none(),
                "http://localhost:4588");
        setRole(bucket, "roles/storage.objectCreator");

        given().contentType("application/json")
                .body(Map.of("sourceObjects", List.of(Map.of("name", "private-source.txt"))))
                .when().post("/storage/v1/b/{bucket}/o/{destination}/compose", bucket, "composed.txt")
                .then().statusCode(403);

        assertTrue(gcsService.objectExists(bucket, "private-source.txt"));
        assertFalse(gcsService.objectExists(bucket, "composed.txt"));
    }

    @Test
    void objectCreatorCannotMoveUnreadableSource() {
        String bucket = createBucket();
        gcsService.putObject(bucket, "private-source.txt", "text/plain",
                "private".getBytes(StandardCharsets.UTF_8), GcsCustomerEncryption.none(),
                "http://localhost:4588");
        setRole(bucket, "roles/storage.objectCreator");

        given().when().post("/storage/v1/b/{bucket}/o/{source}/moveTo/o/{destination}",
                bucket, "private-source.txt", "moved.txt").then().statusCode(403);

        assertTrue(gcsService.objectExists(bucket, "private-source.txt"));
        assertFalse(gcsService.objectExists(bucket, "moved.txt"));
    }

    @Test
    void objectViewerAndCreatorCannotMoveWithoutDeletePermission() {
        String bucket = createBucket();
        gcsService.putObject(bucket, "source.txt", "text/plain",
                "source".getBytes(StandardCharsets.UTF_8), GcsCustomerEncryption.none(),
                "http://localhost:4588");
        setRoles(bucket, "roles/storage.objectViewer", "roles/storage.objectCreator");

        given().when().post("/storage/v1/b/{bucket}/o/{source}/moveTo/o/{destination}",
                bucket, "source.txt", "moved.txt").then().statusCode(403);

        assertTrue(gcsService.objectExists(bucket, "source.txt"));
        assertFalse(gcsService.objectExists(bucket, "moved.txt"));
    }

    @Test
    void objectAdminAllowsImplementedObjectMutationRoutes() {
        String bucket = createBucket();
        gcsService.putObject(bucket, "source.txt", "text/plain", "source".getBytes(),
                GcsCustomerEncryption.none(), "http://localhost:4588");
        gcsService.putObject(bucket, "compose-source.txt", "text/plain", "compose".getBytes(),
                GcsCustomerEncryption.none(), "http://localhost:4588");
        gcsService.putObject(bucket, "move-source.txt", "text/plain", "move".getBytes(),
                GcsCustomerEncryption.none(), "http://localhost:4588");
        setRole(bucket, "roles/storage.objectAdmin");

        given().contentType("application/json").body(Map.of("contentType", "text/csv"))
                .when().patch("/storage/v1/b/{bucket}/o/{object}", bucket, "source.txt")
                .then().statusCode(200);
        given().contentType("text/plain").queryParam("uploadType", "media").queryParam("name", "media.txt")
                .body("media").when().post("/upload/storage/v1/b/{bucket}/o", bucket).then().statusCode(200);
        given().contentType("text/plain").body("xml")
                .when().put("/{bucket}/{object}", bucket, "xml.txt").then().statusCode(200);
        given().when().post("/storage/v1/b/{sourceBucket}/o/{source}/copyTo/b/{destinationBucket}/o/{destination}",
                bucket, "source.txt", bucket, "copied.txt").then().statusCode(200);
        given().when().post("/storage/v1/b/{sourceBucket}/o/{source}/rewriteTo/b/{destinationBucket}/o/{destination}",
                bucket, "source.txt", bucket, "rewritten.txt").then().statusCode(200);
        given().contentType("application/json")
                .body(Map.of("sourceObjects", List.of(Map.of("name", "compose-source.txt"))))
                .when().post("/storage/v1/b/{bucket}/o/{destination}/compose", bucket, "composed.txt")
                .then().statusCode(200);
        given().when().post("/storage/v1/b/{bucket}/o/{source}/moveTo/o/{destination}",
                bucket, "move-source.txt", "moved.txt").then().statusCode(200);
        given().when().delete("/storage/v1/b/{bucket}/o/{object}", bucket, "media.txt").then().statusCode(204);
        given().when().get("/{bucket}", bucket).then().statusCode(200);
        given().when().delete("/{bucket}/{object}", bucket, "xml.txt").then().statusCode(204);

        assertTrue(gcsService.objectExists(bucket, "copied.txt"));
        assertTrue(gcsService.objectExists(bucket, "rewritten.txt"));
        assertTrue(gcsService.objectExists(bucket, "composed.txt"));
        assertFalse(gcsService.objectExists(bucket, "move-source.txt"));
        assertTrue(gcsService.objectExists(bucket, "moved.txt"));
        assertFalse(gcsService.objectExists(bucket, "media.txt"));
        assertFalse(gcsService.objectExists(bucket, "xml.txt"));
    }

    @Test
    void objectCreatorCannotRestoreWithoutRestorePermission() {
        String bucket = "iam-object-" + UUID.randomUUID().toString().substring(0, 8);
        given().contentType("application/json")
                .body(Map.of("name", bucket, "softDeletePolicy", Map.of("retentionDurationSeconds", "604800")))
                .when().post("/storage/v1/b?project=test-project").then().statusCode(200);
        String generation = gcsService.putObject(bucket, "restore.txt", "text/plain", "archived".getBytes(),
                GcsCustomerEncryption.none(), "http://localhost:4588").getGeneration();
        gcsService.deleteObject(bucket, "restore.txt");
        setRole(bucket, "roles/storage.objectCreator");

        given().queryParam("generation", generation)
                .when().post("/storage/v1/b/{bucket}/o/{object}/restore", bucket, "restore.txt")
                .then().statusCode(403);
        assertFalse(gcsService.objectExists(bucket, "restore.txt"));

        setRole(bucket, "roles/storage.objectAdmin");
        given().queryParam("generation", generation)
                .when().post("/storage/v1/b/{bucket}/o/{object}/restore", bucket, "restore.txt")
                .then().statusCode(200);
        assertTrue(gcsService.objectExists(bucket, "restore.txt"));
    }

    @Test
    void objectViewerCannotUseXmlMultipartOperations() {
        String bucket = createBucket();
        String object = "denied-multipart.txt";
        String path = "/" + bucket + "/" + object;
        setRole(bucket, "roles/storage.objectAdmin");
        String uploadId = initiateXmlMultipart(path);
        String etag = uploadXmlMultipartPart(path, uploadId, "original");
        String completion = xmlMultipartCompletion(etag);
        setRole(bucket, "roles/storage.objectViewer");

        given().when().post(path + "?uploads").then().statusCode(403);
        given().body("replacement").when()
                .put(path + "?uploadId=" + uploadId + "&partNumber=1").then().statusCode(403);
        given().when().get(path + "?uploadId=" + uploadId).then().statusCode(403);
        given().when().get("/" + bucket + "?uploads").then().statusCode(403);
        given().contentType("application/xml").body(completion).when()
                .post(path + "?uploadId=" + uploadId).then().statusCode(403);
        given().when().delete(path + "?uploadId=" + uploadId).then().statusCode(403);

        assertFalse(gcsService.objectExists(bucket, object));
        setRole(bucket, "roles/storage.objectAdmin");
        given().when().delete(path + "?uploadId=" + uploadId).then().statusCode(204);
    }

    @Test
    void xmlMultipartPermissionsAllowCreationAndProtectReplacement() {
        String bucket = createBucket();
        String createdObject = "created-multipart.txt";
        String createdPath = "/" + bucket + "/" + createdObject;
        gcsService.putObject(bucket, "existing-multipart.txt", "text/plain",
                "original".getBytes(StandardCharsets.UTF_8), GcsCustomerEncryption.none(),
                "http://localhost:4588");
        setRole(bucket, "roles/storage.objectCreator");

        String createdUploadId = initiateXmlMultipart(createdPath);
        String createdEtag = uploadXmlMultipartPart(createdPath, createdUploadId, "created");
        given().when().get(createdPath + "?uploadId=" + createdUploadId).then().statusCode(200);
        given().contentType("application/xml").body(xmlMultipartCompletion(createdEtag)).when()
                .post(createdPath + "?uploadId=" + createdUploadId).then().statusCode(200);
        assertArrayEquals("created".getBytes(StandardCharsets.UTF_8),
                gcsService.getObjectData(bucket, createdObject, GcsCustomerEncryption.none()));

        String abortedPath = "/" + bucket + "/aborted-multipart.txt";
        String abortedUploadId = initiateXmlMultipart(abortedPath);
        given().when().get("/" + bucket + "?uploads").then().statusCode(403);
        given().when().delete(abortedPath + "?uploadId=" + abortedUploadId).then().statusCode(204);

        String existingPath = "/" + bucket + "/existing-multipart.txt";
        String existingUploadId = initiateXmlMultipart(existingPath);
        String existingEtag = uploadXmlMultipartPart(existingPath, existingUploadId, "replacement");
        String completion = xmlMultipartCompletion(existingEtag);
        given().contentType("application/xml").body(completion).when()
                .post(existingPath + "?uploadId=" + existingUploadId).then().statusCode(403);
        assertArrayEquals("original".getBytes(StandardCharsets.UTF_8),
                gcsService.getObjectData(bucket, "existing-multipart.txt", GcsCustomerEncryption.none()));

        setRole(bucket, "roles/storage.objectAdmin");
        given().when().get("/" + bucket + "?uploads").then().statusCode(200);
        given().contentType("application/xml").body(completion).when()
                .post(existingPath + "?uploadId=" + existingUploadId).then().statusCode(200);
        assertArrayEquals("replacement".getBytes(StandardCharsets.UTF_8),
                gcsService.getObjectData(bucket, "existing-multipart.txt", GcsCustomerEncryption.none()));
    }

    @Test
    void objectViewerCannotStartMultipartOrResumableUploads() {
        String bucket = createBucket();
        setRole(bucket, "roles/storage.objectViewer");

        given().header("Content-Type", "multipart/related; boundary=iam-boundary")
                .body(multipartBody("iam-boundary", "multipart.txt").getBytes(StandardCharsets.UTF_8))
                .when().post("/upload/storage/v1/b/{bucket}/o?uploadType=multipart", bucket)
                .then().statusCode(403);
        given().contentType("application/json").body(Map.of("name", "resumable.txt"))
                .when().post("/upload/storage/v1/b/{bucket}/o?uploadType=resumable", bucket)
                .then().statusCode(403);

        assertFalse(gcsService.objectExists(bucket, "multipart.txt"));
        assertFalse(gcsService.objectExists(bucket, "resumable.txt"));
    }

    @Test
    void objectAdminAllowsMultipartAndResumableUploads() {
        String bucket = createBucket();
        setRole(bucket, "roles/storage.objectAdmin");

        given().header("Content-Type", "multipart/related; boundary=iam-boundary")
                .body(multipartBody("iam-boundary", "multipart.txt").getBytes(StandardCharsets.UTF_8))
                .when().post("/upload/storage/v1/b/{bucket}/o?uploadType=multipart", bucket)
                .then().statusCode(200);
        String location = given().contentType("application/json").body(Map.of("name", "resumable.txt"))
                .when().post("/upload/storage/v1/b/{bucket}/o?uploadType=resumable", bucket)
                .then().statusCode(200).extract().header("Location");
        String uploadId = location.substring(location.indexOf("upload_id=") + "upload_id=".length());
        given().contentType("text/plain").body("resumable")
                .when().put("/upload/storage/v1/b/{bucket}/o?uploadType=resumable&upload_id=" + uploadId, bucket)
                .then().statusCode(200);

        assertTrue(gcsService.objectExists(bucket, "multipart.txt"));
        assertTrue(gcsService.objectExists(bucket, "resumable.txt"));
    }

    @Test
    void objectAdminSessionCanReplaceObjectCreatedAfterInitiation() {
        String bucket = createBucket();
        String object = "late-admin-replacement.txt";
        setRole(bucket, "roles/storage.objectAdmin");
        String location = given().contentType("application/json").body(Map.of("name", object))
                .when().post("/upload/storage/v1/b/{bucket}/o?uploadType=resumable", bucket)
                .then().statusCode(200).extract().header("Location");
        String uploadId = location.substring(location.indexOf("upload_id=") + "upload_id=".length());
        gcsService.putObject(bucket, object, "text/plain", "competing".getBytes(StandardCharsets.UTF_8),
                GcsCustomerEncryption.none(), "http://localhost:4588");

        given().contentType("text/plain").body("replacement")
                .when().put("/upload/storage/v1/b/{bucket}/o?uploadType=resumable&upload_id=" + uploadId, bucket)
                .then().statusCode(200);

        assertArrayEquals("replacement".getBytes(StandardCharsets.UTF_8),
                gcsService.getObjectData(bucket, object, GcsCustomerEncryption.none()));
    }

    @Test
    void objectCreatorSessionCannotReplaceObjectCreatedAfterInitiation() {
        String bucket = createBucket();
        String object = "late-creator-conflict.txt";
        setRole(bucket, "roles/storage.objectCreator");
        String location = given().contentType("application/json").body(Map.of("name", object))
                .when().post("/upload/storage/v1/b/{bucket}/o?uploadType=resumable", bucket)
                .then().statusCode(200).extract().header("Location");
        String uploadId = location.substring(location.indexOf("upload_id=") + "upload_id=".length());
        gcsService.putObject(bucket, object, "text/plain", "competing".getBytes(StandardCharsets.UTF_8),
                GcsCustomerEncryption.none(), "http://localhost:4588");

        given().contentType("text/plain").body("replacement")
                .when().put("/upload/storage/v1/b/{bucket}/o?uploadType=resumable&upload_id=" + uploadId, bucket)
                .then().statusCode(403);

        assertArrayEquals("competing".getBytes(StandardCharsets.UTF_8),
                gcsService.getObjectData(bucket, object, GcsCustomerEncryption.none()));
    }

    private String createBucket() {
        String bucket = "iam-object-" + UUID.randomUUID().toString().substring(0, 8);
        given().contentType("application/json").body(Map.of("name", bucket))
                .when().post("/storage/v1/b?project=test-project").then().statusCode(200);
        return bucket;
    }

    private void setRole(String bucket, String role) {
        setRole(bucket, role, "allUsers");
    }

    private void setRole(String bucket, String role, String member) {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of("role", role, "members", List.of(member))));
        iamService.setPolicy("buckets/" + bucket, policy);
    }

    private void setRoles(String bucket, String... roles) {
        List<Map<String, Object>> bindings = new ArrayList<>();
        for (String role : roles) {
            bindings.add(Map.of("role", role, "members", List.of("allUsers")));
        }
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(bindings);
        iamService.setPolicy("buckets/" + bucket, policy);
    }

    private String bearer(String serviceAccount) {
        return "Bearer " + tokenService.mintImpersonatedToken(serviceAccount, Instant.now().plusSeconds(600))
                .getTokenValue();
    }

    private static String initiateXmlMultipart(String path) {
        return given().when().post(path + "?uploads").then().statusCode(200)
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
    }

    private static String uploadXmlMultipartPart(String path, String uploadId, String content) {
        return given().body(content).when().put(path + "?uploadId=" + uploadId + "&partNumber=1")
                .then().statusCode(200).extract().header("ETag");
    }

    private static String xmlMultipartCompletion(String etag) {
        return "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>"
                + etag + "</ETag></Part></CompleteMultipartUpload>";
    }

    private static RequestSpecification signedRequest() {
        return given()
                .queryParam("X-Goog-Algorithm", "GOOG4-RSA-SHA256")
                .queryParam("X-Goog-Credential",
                        "signer@example.test/20260927/auto/storage/goog4_request")
                .queryParam("X-Goog-Date", V4_DATE_FORMATTER.format(Instant.now()))
                .queryParam("X-Goog-Expires", "600")
                .queryParam("X-Goog-SignedHeaders", "host")
                .queryParam("X-Goog-Signature", "not-cryptographically-verified");
    }

    private static String multipartBody(String boundary, String objectName) {
        return "--" + boundary + "\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                + "{\"name\":\"" + objectName + "\",\"contentType\":\"text/plain\"}\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + "content\r\n"
                + "--" + boundary + "--\r\n";
    }

    public static class EnforceAuthorizationProfile implements QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.iam.authorization-mode", "enforce");
        }
    }
}
