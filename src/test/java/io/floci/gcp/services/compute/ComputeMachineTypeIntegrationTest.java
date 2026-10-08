package io.floci.gcp.services.compute;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ComputeMachineTypeIntegrationTest extends ComputeTestSupport {
    @Test void commonMachineTypeShapesAreServed() {
        String path = root() + "/zones/us-central1-a/machineTypes/";
        var standard = given().get(path + "e2-standard-4").then().statusCode(200).extract().response();
        assertEquals(4, standard.jsonPath().getInt("guestCpus"));
        assertEquals(16384, standard.jsonPath().getInt("memoryMb"));
        assertEquals(1024, given().get(path + "e2-micro").jsonPath().getInt("memoryMb"));
        assertTrue(given().get(path + "e2-micro").jsonPath().getBoolean("isSharedCpu"));
        assertEquals(32768, given().get(path + "n2-highmem-4").jsonPath().getInt("memoryMb"));
        assertEquals(2, given().get(path + "g2-standard-24").jsonPath().getInt("accelerators[0].guestAcceleratorCount"));
        given().get(path + "e2-standard-3").then().statusCode(404);
    }
}
