package io.floci.gcp.services.compute;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ComputeImageIntegrationTest extends ComputeIntegrationTest {
    @Test void imageSnapshotAndDiskRestoration() throws Exception {
        String root = root();
        done(root, post(root + "/zones/us-central1-a/disks", Map.of("name", "source", "sizeGb", "30")));
        done(root, post(root + "/global/images", Map.of("name", "image-one", "family", "desktop", "sourceDisk", "zones/us-central1-a/disks/source")));
        done(root, post(root + "/global/images", Map.of("name", "image-two", "family", "desktop", "sourceImage", "global/images/image-one")));
        assertEquals("image-two", given().get(root + "/global/images/family/desktop").jsonPath().getString("name"));
        done(root, post(root + "/global/snapshots", Map.of("name", "snapshot", "sourceDisk", "zones/us-central1-a/disks/source")));
        post(root + "/zones/us-central1-b/disks", Map.of("name", "too-small", "sizeGb", "5", "sourceSnapshot", "global/snapshots/snapshot")).then().statusCode(400);
        done(root, post(root + "/zones/us-central1-b/disks", Map.of("name", "restored", "sourceSnapshot", "global/snapshots/snapshot")));
        assertEquals("30", given().get(root + "/zones/us-central1-b/disks/restored").jsonPath().getString("sizeGb"));
        done(root, given().delete(root + "/global/snapshots/snapshot"));
        given().get(root + "/zones/us-central1-b/disks/restored").then().statusCode(200);
        done(root, given().delete(root + "/zones/us-central1-a/disks/source"));
        assertEquals("READY", given().get(root + "/global/images/image-one").jsonPath().getString("status"));
        post(root + "/global/images", Map.of("name", "missing-source", "sourceImage", "global/images/missing")).then().statusCode(404);
    }
}
