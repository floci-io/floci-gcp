package io.floci.gcp.services.cloudbilling;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(CloudBillingDisabledRestIntegrationTest.DisabledCloudBillingProfile.class)
class CloudBillingDisabledRestIntegrationTest {

    @Test
    void disabledCloudBillingReturnsUnavailableWrapper() {
        given().when().get("/v1/projects/billing-disabled/billingInfo")
                .then().statusCode(503)
                .body("error.status", equalTo("UNAVAILABLE"))
                .body("error.message", equalTo("Service cloudbilling is not enabled."));
    }

    public static class DisabledCloudBillingProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.cloudbilling.enabled", "false");
        }
    }
}
