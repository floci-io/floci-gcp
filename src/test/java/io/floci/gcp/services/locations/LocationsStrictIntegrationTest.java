package io.floci.gcp.services.locations;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(LocationsStrictIntegrationTest.StrictLocationsProfile.class)
class LocationsStrictIntegrationTest {

    @Test
    void strictKmsRejectsUnknownLocationButAcceptsGlobalAndMultiRegion() {
        given().contentType("application/json").body("{}")
                .when().post("/v1/projects/loc-strict/locations/mars-north1/keyRings?keyRingId=ring")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"))
                .body("error.message", equalTo("Invalid location: mars-north1"));
        for (String location : new String[] {"global", "europe", "us-east1"}) {
            given().contentType("application/json").body("{}")
                    .when().post("/v1/projects/loc-strict/locations/" + location + "/keyRings?keyRingId=ring")
                    .then().statusCode(200);
        }
    }

    @Test
    void strictRegionalApisRejectUnknownLocations() {
        given().contentType("application/json").body("{\"capacityConfig\":{\"vcpuCount\":3,\"memoryBytes\":3221225472}}")
                .when().post("/v1/projects/loc-strict/locations/us-central1-a/clusters?clusterId=kafka")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
        given().when().get("/v2/projects/loc-strict/locations/mars-north1/functions")
                .then().statusCode(400);
        given().when().get("/v2/projects/loc-strict/locations/us-central1/functions")
                .then().statusCode(200);
        given().when().get("/v2/projects/loc-strict/locations/-/services")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
        given().contentType("application/json").body("{\"template\":{\"containers\":[{\"image\":\"nginx\"}]}}")
                .when().post("/v2/projects/loc-strict/locations/moon-1/services?serviceId=svc")
                .then().statusCode(400);
    }

    @Test
    void strictListsNeverCheckTheWildcard() {
        given().when().get("/v1/projects/loc-strict/locations/-/keyRings").then().statusCode(200);
        given().when().get("/v1/projects/loc-strict/locations/-/jobs").then().statusCode(200);
        given().when().get("/v1/projects/loc-strict/locations/mars-north1/jobs").then().statusCode(400);
    }

    @Test
    void strictGkeAcceptsZonesAndRegions() {
        given().when().get("/container/v1/projects/loc-strict/locations/us-central1-c/clusters")
                .then().statusCode(200);
        given().when().get("/container/v1/projects/loc-strict/locations/europe-west4/clusters")
                .then().statusCode(200);
        given().when().get("/container/v1/projects/loc-strict/locations/us-central1-z/clusters")
                .then().statusCode(400);
    }

    public static class StrictLocationsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.locations.strict", "true");
        }
    }
}
