package io.floci.gcp.services.gcs;

import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(GcsDownscopedIamAuthorizationRestIntegrationTest.EnforceAuthorizationProfile.class)
class GcsDownscopedIamAuthorizationRestIntegrationTest {

	private static final String PROJECT = "test-project";
	private static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";
	private static final String SOURCE_SERVICE_ACCOUNT = "reader@test-project.iam.gserviceaccount.com";
	private static final String UNAUTHORIZED_SERVICE_ACCOUNT = "other@test-project.iam.gserviceaccount.com";

	@Inject CredentialTokenService tokenService;
	@Inject GcsService gcsService;
	@Inject IamService iamService;

	private String bucket;

	@BeforeEach
	void setUp() {
		tokenService.clear();
		bucket = "downscoped-iam-" + UUID.randomUUID().toString().substring(0, 8);
		given().contentType("application/json").body(Map.of("name", bucket))
				.when().post("/storage/v1/b?project=" + PROJECT).then().statusCode(200);
		gcsService.putObject(bucket, "allowed/report.csv", "text/csv",
				"allowed".getBytes(StandardCharsets.UTF_8), GcsCustomerEncryption.none(), "http://localhost:4588");
		gcsService.putObject(bucket, "outside/report.csv", "text/csv",
				"outside".getBytes(StandardCharsets.UTF_8), GcsCustomerEncryption.none(), "http://localhost:4588");
		iamService.setPolicy("buckets/" + bucket, policyFor(SOURCE_SERVICE_ACCOUNT));
	}

	@Test
	void downscopedImpersonatedTokenRequiresBothCabAndBucketPolicy() {
		String authorizedToken = downscopedTokenFor(SOURCE_SERVICE_ACCOUNT);

		given().header("Authorization", bearer(authorizedToken))
				.when().get("/storage/v1/b/{bucket}/o/{object}?alt=media", bucket, "allowed/report.csv")
				.then().statusCode(200).body(equalTo("allowed"));

		given().header("Authorization", bearer(authorizedToken))
				.queryParam("prefix", "allowed/")
				.when().get("/storage/v1/b/{bucket}/o", bucket)
				.then().statusCode(200).body("items[0].name", equalTo("allowed/report.csv"));

		given().header("Authorization", bearer(authorizedToken))
				.when().get("/storage/v1/b/{bucket}/o/{object}?alt=media", bucket, "outside/report.csv")
				.then().statusCode(403).body("error.status", equalTo("PERMISSION_DENIED"));

		String unauthorizedToken = downscopedTokenFor(UNAUTHORIZED_SERVICE_ACCOUNT);
		given().header("Authorization", bearer(unauthorizedToken))
				.when().get("/storage/v1/b/{bucket}/o/{object}?alt=media", bucket, "allowed/report.csv")
				.then().statusCode(403).body("error.status", equalTo("PERMISSION_DENIED"));
	}

	private String downscopedTokenFor(String serviceAccount) {
		String sourceToken = given()
				.urlEncodingEnabled(false)
				.contentType("application/json")
				.body(Map.of("scope", List.of(CLOUD_PLATFORM_SCOPE), "lifetime", "600s"))
				.when().post("/v1/projects/-/serviceAccounts/" + serviceAccount + ":generateAccessToken")
				.then().statusCode(200)
				.extract().path("accessToken");

		return given()
				.contentType("application/x-www-form-urlencoded")
				.formParam("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
				.formParam("subject_token_type", "urn:ietf:params:oauth:token-type:access_token")
				.formParam("subject_token", sourceToken)
				.formParam("requested_token_type", "urn:ietf:params:oauth:token-type:access_token")
				.formParam("options", cabOptions())
				.when().post("/v1/token")
				.then().statusCode(200)
				.extract().path("access_token");
	}

	private StoredPolicy policyFor(String serviceAccount) {
		StoredPolicy policy = new StoredPolicy();
		policy.setBindings(List.of(Map.of(
				"role", "roles/storage.objectViewer",
				"members", List.of("serviceAccount:" + serviceAccount))));
		return policy;
	}

	private String cabOptions() {
		String resourcePrefix = "projects/_/buckets/" + bucket + "/objects/allowed/";
		return """
				{"accessBoundary":{"accessBoundaryRules":[{
				  "availableResource":"//storage.googleapis.com/projects/_/buckets/%s",
				  "availablePermissions":["inRole:roles/storage.legacyObjectReader","inRole:roles/storage.objectViewer"],
				  "availabilityCondition":{"expression":"resource.name.startsWith('%s') || api.getAttribute('storage.googleapis.com/objectListPrefix', '').startsWith('allowed/')"}
				}]}}
				""".formatted(bucket, resourcePrefix);
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	public static class EnforceAuthorizationProfile implements QuarkusTestProfile {
		@Override
		public Map<String, String> getConfigOverrides() {
			return Map.of("floci-gcp.services.iam.authorization-mode", "enforce");
		}
	}
}
