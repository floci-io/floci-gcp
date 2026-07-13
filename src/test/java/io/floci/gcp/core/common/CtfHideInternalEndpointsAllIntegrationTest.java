package io.floci.gcp.core.common;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;

@QuarkusTest
@TestProfile(CtfHideInternalEndpointsAllIntegrationTest.AllHiddenProfile.class)
class CtfHideInternalEndpointsAllIntegrationTest {

    @Test
    void allModeHidesHealthAndPrefixedRoutes() {
        given().when().get("/health").then().statusCode(404);
        given().when().get("/_floci-gcp/info").then().statusCode(404);
        given().when().get("/_floci-gcp/init").then().statusCode(404);
    }

    public static class AllHiddenProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.ctf.hide-internal-endpoints", "all");
        }
    }
}
