package io.floci.gcp.services.gcs;

import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamObjectAuthorizationRestIntegrationTest.EnforceAuthorizationProfile.class)
class IamObjectAuthorizationRestIntegrationTest {

    @Inject IamService iamService;
    @Inject GcsService gcsService;

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
		given().contentType("text/plain").body("new")
				.when().put("/{bucket}/{object}", bucket, "xml-upload.txt")
				.then().statusCode(403);

		assertTrue(gcsService.objectExists(bucket, "existing.txt"));
		assertFalse(gcsService.objectExists(bucket, "xml-upload.txt"));
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

		assertTrue(gcsService.objectExists(bucket, "xml.txt"));
		assertTrue(gcsService.objectExists(bucket, "copied.txt"));
		assertTrue(gcsService.objectExists(bucket, "rewritten.txt"));
		assertTrue(gcsService.objectExists(bucket, "composed.txt"));
		assertFalse(gcsService.objectExists(bucket, "move-source.txt"));
		assertTrue(gcsService.objectExists(bucket, "moved.txt"));
		assertFalse(gcsService.objectExists(bucket, "media.txt"));
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

	private String createBucket() {
		String bucket = "iam-object-" + UUID.randomUUID().toString().substring(0, 8);
		given().contentType("application/json").body(Map.of("name", bucket))
				.when().post("/storage/v1/b?project=test-project").then().statusCode(200);
		return bucket;
	}

	private void setRole(String bucket, String role) {
		StoredPolicy policy = new StoredPolicy();
		policy.setBindings(List.of(Map.of("role", role, "members", List.of("allUsers"))));
		iamService.setPolicy("buckets/" + bucket, policy);
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
