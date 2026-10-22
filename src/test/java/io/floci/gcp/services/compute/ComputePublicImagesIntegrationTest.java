package io.floci.gcp.services.compute;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ComputePublicImagesIntegrationTest extends ComputeTestSupport {
    private static final String DEBIAN = "/compute/v1/projects/debian-cloud/global/images";

    @Test void terraformImageResolutionFindsTheFamilyNotAnImageOfThatName() {
        given().get(DEBIAN + "/debian-12").then().statusCode(404);
        given().get(DEBIAN + "/family/debian-12").then().statusCode(200)
                .body("kind", equalTo("compute#image"))
                .body("name", equalTo("debian-12-bookworm-v20261001"))
                .body("family", equalTo("debian-12"))
                .body("status", equalTo("READY"))
                .body("selfLink", equalTo("https://www.googleapis.com" + DEBIAN + "/debian-12-bookworm-v20261001"));
        given().get(DEBIAN + "/debian-12-bookworm-v20261001").then().statusCode(200).body("family", equalTo("debian-12"));
        given().get(DEBIAN + "/family/debian-99").then().statusCode(404);
    }

    @Test void publicImagesListAndFilter() {
        List<String> names = given().get("/compute/v1/projects/ubuntu-os-cloud/global/images").then().statusCode(200)
                .extract().jsonPath().getList("items.name");
        assertTrue(names.contains("ubuntu-2204-jammy-v20261001"));
        assertEquals(List.of("ubuntu-2404-noble-arm64-v20261001"),
                given().queryParam("filter", "family = ubuntu-2404-lts-arm64")
                        .get("/compute/v1/projects/ubuntu-os-cloud/global/images").then().statusCode(200)
                        .extract().jsonPath().getList("items.name"));
    }

    @Test void publicProjectsAreReadOnlyAndExposeOnlyImages() {
        post(DEBIAN, Map.of("name", "mine", "sourceImage", "projects/debian-cloud/global/images/family/debian-12"))
                .then().statusCode(403);
        given().delete(DEBIAN + "/debian-12-bookworm-v20261001").then().statusCode(403);
        given().get("/compute/v1/projects/debian-cloud/zones/us-central1-a/instances").then().statusCode(403);
    }

    @Test void instancesBootFromPublicFamiliesAndImages() throws Exception {
        String root = network(), zone = root + "/zones/us-central1-a";
        done(root, post(zone + "/instances", vm("family-vm", "projects/debian-cloud/global/images/family/debian-12")));
        var disk = given().get(zone + "/disks/family-vm").then().statusCode(200).extract().jsonPath();
        assertEquals("https://www.googleapis.com" + DEBIAN + "/debian-12-bookworm-v20261001", disk.getString("sourceImage"));
        assertFalse(disk.getString("sourceImageId").isBlank());
        assertEquals("10", disk.getString("sizeGb"));
        assertEquals("RUNNING", given().get(zone + "/instances/family-vm").jsonPath().getString("status"));

        String ubuntu = "https://www.googleapis.com/compute/v1/projects/ubuntu-os-cloud/global/images/ubuntu-2204-jammy-v20261001";
        done(root, post(zone + "/instances", vm("image-vm", ubuntu)));
        assertEquals(ubuntu, given().get(zone + "/disks/image-vm").jsonPath().getString("sourceImage"));
    }

    @Test void customImagesCanCopyAPublicImage() throws Exception {
        String root = root();
        done(root, post(root + "/global/images", Map.of("name", "golden", "family", "golden",
                "sourceImage", "projects/rocky-linux-cloud/global/images/family/rocky-linux-9")));
        var image = given().get(root + "/global/images/golden").then().statusCode(200).extract().jsonPath();
        assertEquals("https://www.googleapis.com/compute/v1/projects/rocky-linux-cloud/global/images/rocky-linux-9-v20261001",
                image.getString("sourceImage"));
        assertEquals("20", image.getString("diskSizeGb"));
    }

    @Test void unknownPublicImagesAndOtherProjectsStillFail() {
        String root = network(), zone = root + "/zones/us-central1-a";
        post(zone + "/instances", vm("missing", "projects/debian-cloud/global/images/family/debian-99")).then().statusCode(404);
        post(zone + "/instances", vm("foreign", "projects/someone-else/global/images/family/debian-12")).then().statusCode(400);
    }

    private String network() {
        String root = root();
        try {
            done(root, post(root + "/global/networks", Map.of("name", "net", "autoCreateSubnetworks", false)));
            done(root, post(root + "/regions/us-central1/subnetworks", Map.of("name", "subnet",
                    "network", "global/networks/net", "ipCidrRange", "10.10.0.0/24")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return root;
    }

    private static Map<String, Object> vm(String name, String sourceImage) {
        return Map.of("name", name, "machineType", "zones/us-central1-a/machineTypes/e2-standard-2",
                "networkInterfaces", List.of(Map.of("subnetwork", "regions/us-central1/subnetworks/subnet")),
                "disks", List.of(Map.of("boot", true, "initializeParams", Map.of("sourceImage", sourceImage))));
    }
}
