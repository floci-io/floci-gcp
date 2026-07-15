package io.floci.gcp.services.sts;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(StsDisabledRestIntegrationTest.DisabledStsProfile.class)
class StsDisabledRestIntegrationTest {

	@Test
	void disabledStsReturnsUnavailableWrapper() {
		given()
				.contentType("application/x-www-form-urlencoded")
				.formParam("grant_type", StsService.TOKEN_EXCHANGE_GRANT_TYPE)
				.formParam("subject_token_type", StsService.ACCESS_TOKEN_TYPE)
				.formParam("subject_token", "source-token")
				.formParam("requested_token_type", StsService.ACCESS_TOKEN_TYPE)
				.formParam("options", StsServiceTest.cab())
				.when().post("/v1/token")
				.then()
				.statusCode(503)
				.body("error.status", equalTo("UNAVAILABLE"))
				.body("error.message", equalTo("Service sts is not enabled."));
	}

	public static class DisabledStsProfile implements QuarkusTestProfile {
		@Override
		public Map<String, String> getConfigOverrides() {
			return Map.of("floci-gcp.services.sts.enabled", "false");
		}
	}
}
