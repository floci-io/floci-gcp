package io.floci.gcp.services.locations;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.cloudfunctions.CloudFunctionsService;
import io.floci.gcp.services.cloudrun.CloudRunInstancesService;
import io.floci.gcp.services.cloudrun.CloudRunJobsService;
import io.floci.gcp.services.cloudrun.CloudRunWorkerPoolsService;
import io.floci.gcp.services.eventarc.EventarcService;
import io.floci.gcp.services.gke.GkeService;
import io.floci.gcp.services.scheduler.SchedulerService;
import io.floci.gcp.services.scheduler.model.StoredJob;
import io.floci.gcp.services.tasks.CloudTasksService;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(LocationsStrictIntegrationTest.StrictLocationsProfile.class)
class LocationsStrictIntegrationTest {

    @Inject CloudFunctionsService functions;
    @Inject CloudTasksService tasks;
    @Inject SchedulerService scheduler;
    @Inject EventarcService eventarc;
    @Inject GkeService gke;
    @Inject CloudRunJobsService runJobs;
    @Inject CloudRunWorkerPoolsService runWorkerPools;
    @Inject CloudRunInstancesService runInstances;

    @Test
    void strictCreateChecksAllRequiredServices() {
        List<Consumer<String>> creators = List.of(
            loc -> functions.createFunction("loc-strict", loc, "f1", "{}", false),
            loc -> tasks.createQueue("loc-strict", loc, "q1", 0, 0, 0),
            loc -> {
                StoredJob job = new StoredJob();
                job.setName("projects/loc-strict/locations/" + loc + "/jobs/j1");
                scheduler.createJob("projects/loc-strict/locations/" + loc, job);
            },
            loc -> eventarc.createTrigger("loc-strict", loc, "t1", "{}", false),
            loc -> gke.createCluster("loc-strict", loc, Map.of("name", "c1")),
            loc -> runJobs.createJob("loc-strict", loc, "j1", "{}", false),
            loc -> runWorkerPools.createWorkerPool("loc-strict", loc, "wp1", "{}", false),
            loc -> runInstances.createInstance("loc-strict", loc, "i1", "{}", false)
        );

        for (Consumer<String> creator : creators) {
            GcpException e = assertThrows(GcpException.class, () -> creator.accept("moon-1"));
            assertEquals("INVALID_ARGUMENT", e.getGcpStatus());
            assertTrue(e.getMessage().contains("Invalid location"), "Expected Invalid location, got: " + e.getMessage());

            try {
                creator.accept("us-central1");
            } catch (GcpException ex) {
                assertNotEquals("INVALID_ARGUMENT", ex.getGcpStatus(), "Valid location should not fail with INVALID_ARGUMENT");
            }
        }
    }

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
    void strictCloudRunListsNeverCheckTheWildcardAndRejectUnknowns() {
        for (String resource : List.of("jobs", "workerPools", "instances")) {
            given().when().get("/v2/projects/loc-strict/locations/-/" + resource).then().statusCode(200);
            given().when().get("/v2/projects/loc-strict/locations/us-central1/" + resource).then().statusCode(200);
            given().when().get("/v2/projects/loc-strict/locations/mars-north1/" + resource)
                    .then().statusCode(400)
                    .body("error.status", equalTo("INVALID_ARGUMENT"));
        }
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
