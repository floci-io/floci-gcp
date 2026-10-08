package io.floci.gcp.services.compute;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ComputePublicImageIntegrationTest extends ComputeTestSupport {
    private static final String UBUNTU = "https://www.googleapis.com/compute/v1/projects/ubuntu-os-cloud/global/images/ubuntu-2404-noble-amd64-v20250115";

    @Test void getFromFamilyGetAndListServeSyntheticImages() {
        String base = "/compute/v1/projects/ubuntu-os-cloud/global/images";
        var family = given().get(base + "/family/ubuntu-2404-lts-amd64").then().statusCode(200).extract().response();
        assertEquals(UBUNTU, family.jsonPath().getString("selfLink"));
        assertEquals("ubuntu-2404-lts-amd64", family.jsonPath().getString("family"));
        assertEquals("READY", family.jsonPath().getString("status"));
        assertEquals("X86_64", family.jsonPath().getString("architecture"));
        assertEquals(UBUNTU, given().get(base + "/ubuntu-2404-noble-amd64-v20250115").jsonPath().getString("selfLink"));
        assertTrue(given().get(base).jsonPath().getList("items.name").contains("ubuntu-2404-noble-amd64-v20250115"));
        assertEquals(1, given().queryParam("filter", "family = ubuntu-2204-lts").get(base).jsonPath().getList("items").size());
        given().get(base + "/family/does-not-exist").then().statusCode(404);
        given().get(base + "/missing").then().statusCode(404);
        assertTrue(given().get("/compute/v1/projects/debian-cloud/global/images/family/debian-12").jsonPath().getString("selfLink").contains("debian-12-bookworm"));
        assertTrue(given().get("/compute/v1/projects/cos-cloud/global/images/family/cos-stable").jsonPath().getString("name").startsWith("cos-stable"));
        assertTrue(given().get("/compute/v1/projects/rocky-linux-cloud/global/images/family/rocky-linux-9").jsonPath().getString("name").startsWith("rocky-linux-9"));
    }

    @Test void publicImageProjectsAreReadOnly() {
        post("/compute/v1/projects/debian-cloud/global/images", Map.of("name", "mine", "sourceImage", "global/images/x")).then().statusCode(403);
    }

    @Test void instancesAndDisksAcceptCrossProjectPublicImages() throws Exception {
        String root = root(), zone = root + "/zones/us-central1-a";
        done(root, post(root + "/global/networks", Map.of("name", "net", "autoCreateSubnetworks", false)));
        done(root, post(root + "/regions/us-central1/subnetworks", Map.of("name", "subnet", "network", "global/networks/net", "ipCidrRange", "10.10.0.0/24")));
        List<String> images = List.of(UBUNTU, "projects/ubuntu-os-cloud/global/images/family/ubuntu-2404-lts-amd64",
                "projects/ubuntu-os-cloud/global/images/ubuntu-2404-noble-amd64-v20250115");
        int index = 0;
        for (String image : images) {
            String name = "vm" + index++;
            Map<String, Object> vm = Map.of("name", name, "machineType", "zones/us-central1-a/machineTypes/e2-standard-2",
                    "networkInterfaces", List.of(Map.of("subnetwork", "regions/us-central1/subnetworks/subnet")),
                    "disks", List.of(Map.of("boot", true, "autoDelete", true, "initializeParams", Map.of("sourceImage", image, "diskSizeGb", "20"))));
            done(root, post(zone + "/instances", vm));
            assertEquals(UBUNTU, given().get(zone + "/disks/" + name).jsonPath().getString("sourceImage"));
            assertEquals("20", given().get(zone + "/disks/" + name).jsonPath().getString("sizeGb"));
        }
        done(root, post(zone + "/disks", Map.of("name", "d", "sourceImage", "projects/debian-cloud/global/images/family/debian-12")));
        assertTrue(given().get(zone + "/disks/d").jsonPath().getString("sourceImage").contains("/projects/debian-cloud/global/images/debian-12-bookworm"));
        assertEquals("10", given().get(zone + "/disks/d").jsonPath().getString("sizeGb"));
        post(zone + "/disks", Map.of("name", "bad", "sourceImage", "projects/debian-cloud/global/images/family/nope")).then().statusCode(404);
        post(zone + "/disks", Map.of("name", "other", "sourceImage", "projects/other-project/global/images/x")).then().statusCode(400);
    }
}
