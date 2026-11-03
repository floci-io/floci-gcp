package io.floci.gcp.services.compute;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ComputeMachineTypeIntegrationTest extends ComputeTestSupport {
    private JsonPath machineType(String path, String name) {
        return given().get(path + name).then().statusCode(200).extract().jsonPath();
    }

    @Test void commonMachineTypeShapesAreServed() {
        String path = root() + "/zones/us-central1-a/machineTypes/";
        JsonPath standard = machineType(path, "e2-standard-4");
        assertEquals(4, standard.getInt("guestCpus"));
        assertEquals(16384, standard.getInt("memoryMb"));
        assertEquals(3840, machineType(path, "n1-standard-1").getInt("memoryMb"));
        assertEquals(32768, machineType(path, "n2-highmem-4").getInt("memoryMb"));
        assertEquals(864 * 1024, machineType(path, "n2-highmem-128").getInt("memoryMb"));
        given().get(path + "e2-standard-3").then().statusCode(404);
    }

    @Test void onlySharedCoreTypesReportASharedCpu() {
        String path = root() + "/zones/us-central1-a/machineTypes/";
        assertEquals(1024, machineType(path, "e2-micro").getInt("memoryMb"));
        for (String shared : new String[] {"e2-micro", "e2-small", "e2-medium"}) {
            JsonPath type = machineType(path, shared);
            assertTrue(type.getBoolean("isSharedCpu"), shared);
            assertEquals(2, type.getInt("guestCpus"), shared);
        }
        for (String dedicated : new String[] {"e2-highcpu-2", "n2-highcpu-2", "e2-standard-2"}) {
            assertFalse(machineType(path, dedicated).getBoolean("isSharedCpu"), dedicated);
        }
    }

    @Test void g2TypesCarryTheirL4Count() {
        String path = root() + "/zones/us-central1-a/machineTypes/";
        assertEquals(1, machineType(path, "g2-standard-32").getInt("accelerators[0].guestAcceleratorCount"));
        assertEquals(2, machineType(path, "g2-standard-24").getInt("accelerators[0].guestAcceleratorCount"));
        assertEquals(8, machineType(path, "g2-standard-96").getInt("accelerators[0].guestAcceleratorCount"));
    }

    @Test void limitsComeFromTheCatalogNotTheName() {
        assertEquals(new ComputeCatalog.MachineShape(2, 2048), ComputeCatalog.machineShape("e2-highcpu-2").orElseThrow());
        assertEquals(new ComputeCatalog.MachineShape(2, 1024), ComputeCatalog.machineShape("e2-micro").orElseThrow());
        assertTrue(ComputeCatalog.machineShape("custom-4-8192").isEmpty());
    }
}
