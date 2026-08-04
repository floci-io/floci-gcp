package io.floci.gcp.services.sts;

import io.floci.gcp.services.credentials.CredentialTokenService;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class StsRestIntegrationTest {

	@Test
	void validFormExchangeReturnsRequiredFields() {
		given()
				.contentType("application/x-www-form-urlencoded")
				.formParam("grant_type", StsService.TOKEN_EXCHANGE_GRANT_TYPE)
				.formParam("subject_token_type", StsService.ACCESS_TOKEN_TYPE)
				.formParam("subject_token", "source-token")
				.formParam("requested_token_type", StsService.ACCESS_TOKEN_TYPE)
				.formParam("options", StsServiceTest.cab())
				.when().post("/v1/token")
				.then()
				.statusCode(200)
				.body("access_token", startsWith(CredentialTokenService.DOWNSCOPED_TOKEN_PREFIX))
				.body("issued_token_type", equalTo(StsService.ACCESS_TOKEN_TYPE))
				.body("token_type", equalTo(StsService.BEARER_TOKEN_TYPE))
				.body("expires_in", equalTo((int) CredentialTokenService.DEFAULT_LIFETIME_SECONDS));
	}

	@Test
	void missingOptionsReturnsOauthError() {
		given()
				.contentType("application/x-www-form-urlencoded")
				.formParam("grant_type", StsService.TOKEN_EXCHANGE_GRANT_TYPE)
				.formParam("subject_token_type", StsService.ACCESS_TOKEN_TYPE)
				.formParam("subject_token", "source-token")
				.formParam("requested_token_type", StsService.ACCESS_TOKEN_TYPE)
				.when().post("/v1/token")
				.then()
				.statusCode(400)
				.body("error", equalTo("invalid_request"));
	}

	@Test
	void unsupportedCabReturnsInvalidGrant() {
		given()
				.contentType("application/x-www-form-urlencoded")
				.formParam("grant_type", StsService.TOKEN_EXCHANGE_GRANT_TYPE)
				.formParam("subject_token_type", StsService.ACCESS_TOKEN_TYPE)
				.formParam("subject_token", "source-token")
				.formParam("requested_token_type", StsService.ACCESS_TOKEN_TYPE)
				.formParam("options", StsServiceTest.cab().replace(
						"inRole:roles/storage.legacyObjectReader", "inRole:roles/storage.admin"))
				.when().post("/v1/token")
				.then()
				.statusCode(400)
				.body("error", equalTo("invalid_grant"));
	}

	@Test
	void wrongGrantTypeReturnsInvalidRequest() {
		given()
				.contentType("application/x-www-form-urlencoded")
				.formParam("grant_type", "bad")
				.formParam("subject_token_type", StsService.ACCESS_TOKEN_TYPE)
				.formParam("subject_token", "source-token")
				.formParam("requested_token_type", StsService.ACCESS_TOKEN_TYPE)
				.formParam("options", StsServiceTest.cab())
				.when().post("/v1/token")
				.then()
				.statusCode(400)
				.body("error", equalTo("invalid_request"));
	}
}
