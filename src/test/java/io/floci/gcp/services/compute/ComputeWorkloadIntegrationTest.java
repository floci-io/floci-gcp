package io.floci.gcp.services.compute;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ComputeWorkloadIntegrationTest extends ComputeTestSupport {
    @Test void lifecycleRetainedDisksAndRollback() throws Exception {
        String root = root(), zone = "/zones/us-central1-a";
        done(root, post(root + "/global/networks", Map.of("name", "net", "autoCreateSubnetworks", false)));
        done(root, post(root + "/regions/us-central1/subnetworks", Map.of("name", "subnet", "network", "global/networks/net", "ipCidrRange", "10.10.0.0/24")));
        Map<String,Object> vm = Map.of("name", "vm", "machineType", "zones/us-central1-a/machineTypes/e2-standard-2",
                "networkInterfaces", List.of(Map.of("subnetwork", "regions/us-central1/subnetworks/subnet", "accessConfigs", List.of(Map.of("type", "ONE_TO_ONE_NAT")))),
                "disks", List.of(Map.of("boot", true, "autoDelete", false, "initializeParams", Map.of("diskSizeGb", "20"))));
        done(root, post(root + zone + "/instances", vm));
        post(root + zone + "/instances/vm/setMachineType", Map.of("machineType", "zones/us-central1-a/machineTypes/n2-standard-4")).then().statusCode(400);
        done(root, post(root + zone + "/instances/vm/stop", Map.of()));
        assertEquals("TERMINATED", given().get(root + zone + "/instances/vm").jsonPath().getString("status"));
        done(root, post(root + zone + "/instances/vm/setMachineType", Map.of("machineType", "zones/us-central1-a/machineTypes/n2-standard-4")));
        done(root, post(root + zone + "/instances/vm/start", Map.of()));
        done(root, post(root + zone + "/instances/vm/reset", Map.of()));
        given().delete(root + zone + "/disks/vm").then().statusCode(400);
        done(root, post(root + zone + "/disks", Map.of("name", "data", "sizeGb", "30")));
        done(root, post(root + zone + "/instances/vm/attachDisk", Map.of("source", "zones/us-central1-a/disks/data", "deviceName", "data")));
        done(root, post(root + zone + "/instances/vm/detachDisk?deviceName=data", Map.of()));
        assertEquals(0, given().get(root + zone + "/disks/data").jsonPath().getList("users").size());
        done(root, given().delete(root + zone + "/instances/vm"));
        given().get(root + zone + "/disks/vm").then().statusCode(200);
        done(root, given().delete(root + zone + "/disks/vm"));
        done(root, given().delete(root + zone + "/disks/data"));
    }
    @Test void diskPerformanceLabelsAndAggregateScopes() throws Exception {
        String root = root(), path = root + "/zones/us-central1-a/disks";
        done(root, post(path, Map.of("name", "hyper", "sizeGb", "100", "type", "zones/us-central1-a/diskTypes/hyperdisk-balanced", "provisionedIops", "4000", "provisionedThroughput", "200")));
        done(root, given().contentType("application/json").body(Map.of("provisionedIops", "6000", "provisionedThroughput", "300")).patch(path + "/hyper"));
        assertEquals("6000", given().get(path + "/hyper").jsonPath().getString("provisionedIops"));
        String fingerprint = given().get(path + "/hyper").jsonPath().getString("labelFingerprint");
        done(root, post(path + "/hyper/setLabels", Map.of("labelFingerprint", fingerprint, "labels", Map.of("owner", "fixture"))));
        post(path + "/hyper/setLabels", Map.of("labelFingerprint", fingerprint, "labels", Map.of())).then().statusCode(412);
        assertEquals("hyper", given().queryParam("filter", "labels.owner = fixture").get(root + "/aggregated/disks").jsonPath().getString("items.'zones/us-central1-a'.disks[0].name"));
        post(path + "/hyper/resize", Map.of("sizeGb", "10")).then().statusCode(400);
        done(root, post(path, Map.of("name", "plain", "sizeGb", "20")));
        given().contentType("application/json").body(Map.of("provisionedIops", "5000")).patch(path + "/plain").then().statusCode(400);
        assertNull(given().get(path + "/plain").jsonPath().get("provisionedIops"));
    }
}
