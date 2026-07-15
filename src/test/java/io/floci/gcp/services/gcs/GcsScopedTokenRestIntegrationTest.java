package io.floci.gcp.services.gcs;

import io.floci.gcp.services.credentials.CredentialAccessBoundaryRule;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class GcsScopedTokenRestIntegrationTest {

	private static final String READER = "inRole:roles/storage.legacyObjectReader";
	private static final String VIEWER = "inRole:roles/storage.objectViewer";
	private static final String WRITER = "inRole:roles/storage.legacyBucketWriter";

	@Inject
	CredentialTokenService tokenService;

	private String bucket;

	@BeforeEach
	void setUp() {
		tokenService.clear();
		bucket = "scoped-" + System.nanoTime();
		createBucket(bucket);
	}

	@Test
	void missingAndStaticBearerTokensPreserveExistingBypassBehavior() {
		upload(null, "bypass/missing.txt", "missing").statusCode(200);
		upload("Bearer static-token", "bypass/static.txt", "static").statusCode(200);

		given()
				.header("Authorization", "Bearer static-token")
				.when().get("/storage/v1/b/{bucket}/o/{object}?alt=media", bucket, "bypass/static.txt")
				.then()
					.statusCode(200)
					.body(equalTo("static"));
	}

	@Test
	void storedImpersonatedBearerTokenPreservesExistingBypassBehavior() {
		String authorization = bearer(tokenService.mintImpersonatedToken(
				"test@test-project.iam.gserviceaccount.com", java.time.Instant.now().plusSeconds(1200))
				.getTokenValue());

		upload(authorization, "impersonated/outside.txt", "impersonated").statusCode(200);

		given()
				.header("Authorization", authorization)
				.when().get("/storage/v1/b/{bucket}/o/{object}?alt=media", bucket, "impersonated/outside.txt")
				.then()
				.statusCode(200)
				.body(equalTo("impersonated"));
	}

	@Test
	void unknownFlociTokenIsUnauthenticated() {
		given()
				.header("Authorization", "Bearer floci-gcp-downscoped-missing")
				.when().get("/storage/v1/b/{bucket}/o/{object}?alt=media", bucket, "allowed/file.txt")
				.then()
				.statusCode(401)
				.body("error.status", equalTo("UNAUTHENTICATED"));
	}

	@Test
	void downscopedTokenAllowsOnlyMatchingPrefixOperations() {
		String authorization = bearer(mint("allowed/", READER, VIEWER, WRITER));

		upload(authorization, "allowed/file.txt", "allowed").statusCode(200);

		given()
				.header("Authorization", authorization)
				.when().get("/storage/v1/b/{bucket}/o/{object}?alt=media", bucket, "allowed/file.txt")
				.then()
				.statusCode(200)
				.body(equalTo("allowed"));

		given()
				.header("Authorization", authorization)
				.queryParam("prefix", "allowed/")
				.when().get("/storage/v1/b/{bucket}/o", bucket)
				.then()
				.statusCode(200)
				.body("items[0].name", equalTo("allowed/file.txt"));

		given()
				.header("Authorization", authorization)
				.when().delete("/storage/v1/b/{bucket}/o/{object}", bucket, "allowed/file.txt")
				.then()
				.statusCode(204);
	}

	@Test
	void siblingPrefixAndNoPrefixListAreForbidden() {
		upload(null, "allowed_sibling/file.txt", "sibling").statusCode(200);
		String authorization = bearer(mint("allowed/", READER, VIEWER, WRITER));

		given()
				.header("Authorization", authorization)
				.when().get("/storage/v1/b/{bucket}/o/{object}?alt=media", bucket, "allowed_sibling/file.txt")
				.then()
				.statusCode(403)
				.body("error.status", equalTo("PERMISSION_DENIED"));

		upload(authorization, "allowed_sibling/write.txt", "denied")
				.statusCode(403)
				.body("error.status", equalTo("PERMISSION_DENIED"));

		given()
				.header("Authorization", authorization)
				.queryParam("prefix", "allowed_sibling/")
				.when().get("/storage/v1/b/{bucket}/o", bucket)
				.then()
				.statusCode(403);

		given()
				.header("Authorization", authorization)
				.when().get("/storage/v1/b/{bucket}/o", bucket)
				.then()
				.statusCode(403);
	}

	@Test
	void composeCopyAndRewriteRequireSourceReadAndDestinationWrite() {
		upload(null, "allowed/source.txt", "source").statusCode(200);
		upload(null, "allowed_sibling/source.txt", "source").statusCode(200);
		String authorization = bearer(mint("allowed/", READER, VIEWER, WRITER));

		given()
				.header("Authorization", authorization)
				.contentType("application/json")
				.body(Map.of("sourceObjects", List.of(Map.of("name", "allowed_sibling/source.txt"))))
				.when().post("/storage/v1/b/{bucket}/o/{object}/compose", bucket, "allowed/composed.txt")
				.then()
				.statusCode(403);

			given()
					.header("Authorization", authorization)
					.when().post("/storage/v1/b/{bucket}/o/{source}/copyTo/b/{destBucket}/o/{dest}",
							bucket, "allowed/source.txt", bucket, "allowed_sibling/copied.txt")
				.then()
				.statusCode(403);

			given()
					.header("Authorization", authorization)
					.when().post("/storage/v1/b/{bucket}/o/{source}/rewriteTo/b/{destBucket}/o/{dest}",
							bucket, "allowed_sibling/source.txt", bucket, "allowed/rewritten.txt")
				.then()
				.statusCode(403);
	}

	@Test
	void resumableUploadRequiresCurrentTokenScopeForCompletion() {
		String location = given()
				.contentType("application/json")
				.queryParam("uploadType", "resumable")
				.queryParam("name", "allowed/resumable.txt")
				.body("{}")
				.when().post("/upload/storage/v1/b/{bucket}/o", bucket)
				.then()
				.statusCode(200)
				.header("Location", containsString("upload_id="))
				.extract().header("Location");

		String uploadId = location.substring(location.indexOf("upload_id=") + "upload_id=".length());
		String otherAuthorization = bearer(mint("other/", READER, VIEWER, WRITER));

		given()
				.header("Authorization", otherAuthorization)
				.contentType("text/plain")
				.queryParam("upload_id", uploadId)
				.body("denied")
				.when().put("/upload/storage/v1/b/{bucket}/o", bucket)
				.then()
				.statusCode(403);
	}

	@Test
	void downscopedTokenCannotUseBucketAdminSurfaces() {
		String authorization = bearer(mint("allowed/", READER, VIEWER, WRITER));

		given()
				.header("Authorization", authorization)
				.when().get("/storage/v1/b/{bucket}", bucket)
				.then()
				.statusCode(403);

		given()
				.header("Authorization", authorization)
				.when().get("/storage/v1/b/{bucket}/notificationConfigs", bucket)
				.then()
				.statusCode(403);
	}

	@Test
	void batchRequestsEnforceOuterAndEmbeddedFlociTokens() {
		upload(null, "allowed_sibling/file.txt", "sibling").statusCode(200);
		String authorization = bearer(mint("allowed/", READER, VIEWER, WRITER));

		given()
					.header("Authorization", authorization)
					.contentType("multipart/mixed; boundary=batch_test")
					.body(batchRequest("/storage/v1/b/" + bucket + "/o/allowed_sibling/file.txt?alt=media", null)
							.getBytes(StandardCharsets.UTF_8))
				.when().post("/batch/storage/v1")
				.then()
				.statusCode(200)
				.body(startsWith("--batch_"))
				.body(containsString("HTTP/1.1 403 Forbidden"));

		given()
					.contentType("multipart/mixed; boundary=batch_test")
					.body(batchRequest("https://storage.googleapis.com/storage/v1/b/" + bucket
							+ "/o/allowed_sibling/file.txt?alt=media", authorization)
							.getBytes(StandardCharsets.UTF_8))
				.when().post("/batch/storage/v1")
				.then()
				.statusCode(200)
				.body(containsString("HTTP/1.1 403 Forbidden"));
	}

	private void createBucket(String bucketName) {
		given()
				.contentType("application/json")
				.queryParam("project", "test-project")
				.body(Map.of("name", bucketName))
				.when().post("/storage/v1/b")
				.then()
				.statusCode(200);
	}

	private io.restassured.response.ValidatableResponse upload(
			String authorization, String objectName, String content) {
		var request = given()
				.contentType("text/plain")
				.queryParam("uploadType", "media")
				.queryParam("name", objectName)
				.body(content.getBytes(StandardCharsets.UTF_8));
		if (authorization != null) {
			request.header("Authorization", authorization);
		}
		return request.when().post("/upload/storage/v1/b/{bucket}/o", bucket).then();
	}

	private String mint(String prefix, String... permissions) {
		return tokenService.mintDownscopedToken("source-token",
				List.of(new CredentialAccessBoundaryRule(bucket, prefix, List.of(permissions))))
				.getTokenValue();
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	private static String batchRequest(String path, String authorization) {
		String authHeader = authorization == null ? "" : "Authorization: " + authorization + "\r\n";
		return """
				--batch_test\r
				Content-Type: application/http\r
				Content-ID: <item1>\r
				\r
				GET %s HTTP/1.1\r
				%s\r
				--batch_test--\r
				""".formatted(path, authHeader);
	}
}
