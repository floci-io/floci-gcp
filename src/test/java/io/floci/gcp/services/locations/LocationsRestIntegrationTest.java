package io.floci.gcp.services.locations;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class LocationsRestIntegrationTest {

    @Test
    void v1ListReturnsEveryCatalogRegionInTerraformShape() {
        // 43 catalog regions plus KMS's global and three multi-regions: localhost names no API.
        JsonPath body = given().when().get("/v1/projects/loc-rest/locations")
                .then().statusCode(200)
                .body("locations", hasSize(47))
                .body("nextPageToken", nullValue())
                .extract().jsonPath();
        List<String> ids = body.getList("locations.locationId", String.class);
        assertTrue(ids.contains("us-central1"));
        assertTrue(ids.contains("europe-west1"));
        Map<String, Object> usCentral = body.getMap("locations.find { it.locationId == 'us-central1' }");
        assertEquals("projects/loc-rest/locations/us-central1", usCentral.get("name"));
        assertEquals("Council Bluffs", usCentral.get("displayName"));
        assertEquals(Map.of("cloud.googleapis.com/region", "us-central1"), usCentral.get("labels"));
        assertNull(usCentral.get("metadata"));
    }

    @Test
    void v1ListPagesWithPageToken() {
        List<String> seen = new ArrayList<>();
        String token = null;
        do {
            var request = given().queryParam("pageSize", 20);
            if (token != null) {
                request.queryParam("pageToken", token);
            }
            JsonPath page = request.when().get("/v1/projects/loc-rest/locations")
                    .then().statusCode(200).extract().jsonPath();
            seen.addAll(page.getList("locations.locationId", String.class));
            token = page.getString("nextPageToken");
        } while (token != null);
        assertEquals(47, seen.size());
    }

    @Test
    void pageTokenPastTheEndStillEmitsLocationsArray() {
        given().queryParam("pageSize", 10).queryParam("pageToken", "OTk5")
                .when().get("/v1/projects/loc-rest/locations")
                .then().statusCode(200)
                .body("locations", hasSize(0));
    }

    @Test
    void v1GetReturnsLocationAndUnknownIsNotFound() {
        given().when().get("/v1/projects/loc-rest/locations/asia-northeast1")
                .then().statusCode(200)
                .body("name", equalTo("projects/loc-rest/locations/asia-northeast1"))
                .body("locationId", equalTo("asia-northeast1"))
                .body("displayName", equalTo("Tokyo"));
        given().when().get("/v1/projects/loc-rest/locations/mars-north1")
                .then().statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"));
    }

    @Test
    void v2ListAndGetServeTheSameCatalog() {
        given().when().get("/v2/projects/loc-rest/locations")
                .then().statusCode(200)
                .body("locations", hasSize(47))
                .body("locations.locationId", hasItem("southamerica-east1"));
        given().when().get("/v2/projects/loc-rest/locations/us-east4")
                .then().statusCode(200)
                .body("locationId", equalTo("us-east4"));
        given().when().get("/v2/projects/loc-rest/locations/us-east4-a")
                .then().statusCode(404);
    }

    @Test
    void kmsHostAddsMultiRegionsAndKmsMetadata() {
        given().header("Host", "cloudkms.googleapis.com")
                .when().get("/v1/projects/loc-rest/locations")
                .then().statusCode(200)
                .body("locations", hasSize(47))
                .body("locations.locationId", hasItem("global"))
                .body("locations.locationId", hasItem("europe"))
                .body("locations[0].metadata.'@type'", equalTo("type.googleapis.com/google.cloud.kms.v1.LocationMetadata"));
        given().header("Host", "cloudkms.googleapis.com")
                .when().get("/v1/projects/loc-rest/locations/global")
                .then().statusCode(200)
                .body("locationId", equalTo("global"))
                .body("labels", nullValue());
        given().header("Host", "cloudtasks.googleapis.com")
                .when().get("/v1/projects/loc-rest/locations")
                .then().statusCode(200)
                .body("locations", hasSize(43));
        given().header("Host", "cloudtasks.googleapis.com")
                .when().get("/v1/projects/loc-rest/locations/global").then().statusCode(404);
    }

    @Test
    void functionsHostAddsFunctionsMetadata() {
        given().header("Host", "cloudfunctions.googleapis.com")
                .when().get("/v2/projects/loc-rest/locations")
                .then().statusCode(200)
                .body("locations[0].metadata.'@type'", equalTo("type.googleapis.com/google.cloud.functions.v2.LocationMetadata"))
                .body("locations[0].metadata.environments", equalTo(List.of("GEN_2")));
    }

    @Test
    void overlappingRegionalRoutesStillResolve() {
        given().contentType("application/json").body("{}")
                .when().post("/v1/projects/loc-rest/locations/us-central1/keyRings?keyRingId=ring")
                .then().statusCode(200)
                .body("name", equalTo("projects/loc-rest/locations/us-central1/keyRings/ring"));
        given().when().get("/v1/projects/loc-rest/locations/us-central1/keyRings")
                .then().statusCode(200);
        given().when().get("/v1/projects/loc-rest/locations/us-central1/triggers")
                .then().statusCode(200);
        given().when().get("/v1/projects/loc-rest/locations/us-central1/clusters")
                .then().statusCode(200);
        given().when().get("/v2/projects/loc-rest/locations/us-central1/services")
                .then().statusCode(200);
        given().when().get("/v2/projects/loc-rest/locations/us-central1/functions")
                .then().statusCode(200);
        given().when().get("/v2/projects/loc-rest/locations/us-central1/operations")
                .then().statusCode(200);
    }

    @Test
    void lenientDefaultAcceptsUnknownLocationsOnCreate() {
        given().contentType("application/json").body("{}")
                .when().post("/v1/projects/loc-rest/locations/mars-north1/keyRings?keyRingId=lenient")
                .then().statusCode(200)
                .body("name", notNullValue());
    }
}
