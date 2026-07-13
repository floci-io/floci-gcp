package io.floci.gcp.core.common;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestProfile(CtfHideInternalEndpointsIntegrationTest.HideProfile.class)
class CtfHideInternalEndpointsIntegrationTest {

    @Test
    void prefixedModeHidesFlociGcpRoutesButKeepsHealth() {
        given().when().get("/_floci-gcp/info").then().statusCode(404);
        given().when().get("/_floci-gcp/init").then().statusCode(404);

        given().when().get("/health").then().statusCode(200).body("services", notNullValue());
    }

    public static class HideProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.ctf.hide-internal-endpoints", "true");
        }
    }
}
