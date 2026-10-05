package io.floci.gcp.services.compute;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(ComputeRegionOverrideIntegrationTest.SingleRegionProfile.class)
class ComputeRegionOverrideIntegrationTest extends ComputeTestSupport {

    @Test
    void configuredRegionsRestrictTheCatalog() {
        String root = root();
        assertEquals(List.of("us-central1"),
                given().get(root + "/regions").then().statusCode(200).extract().jsonPath().getList("items.name", String.class));
        assertEquals(List.of("us-central1-a", "us-central1-b", "us-central1-c", "us-central1-f"),
                given().get(root + "/zones").then().statusCode(200).extract().jsonPath().getList("items.name", String.class));
        given().get(root + "/regions/europe-west1").then().statusCode(404);
        given().get(root + "/regions/mars-north1").then().statusCode(404);
        given().get(root + "/zones/europe-west1-b/diskTypes").then().statusCode(404);
    }

    public static class SingleRegionProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // mars-north1 is not in the catalog, so it is ignored rather than advertised as UP.
            return Map.of("floci-gcp.services.compute.regions", "us-central1,mars-north1");
        }
    }
}
