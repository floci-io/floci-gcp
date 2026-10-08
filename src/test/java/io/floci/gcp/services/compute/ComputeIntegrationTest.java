package io.floci.gcp.services.compute;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.floci.gcp.core.storage.ProjectAwareStorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.compute.model.ComputeProject;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ComputeIntegrationTest extends ComputeTestSupport {
    @Inject StorageFactory storage;

    @Test void regionsAndZonesServeTheFullLocationCatalog() {
        String root = root();
        var regions = given().get(root + "/regions").then().statusCode(200).extract().jsonPath();
        assertEquals(43, regions.getList("items.name").size());
        assertEquals(java.util.List.of("https://www.googleapis.com" + root + "/zones/europe-west1-b",
                        "https://www.googleapis.com" + root + "/zones/europe-west1-c",
                        "https://www.googleapis.com" + root + "/zones/europe-west1-d"),
                given().get(root + "/regions/europe-west1").then().statusCode(200).extract().jsonPath().getList("zones", String.class));
        var zones = given().get(root + "/zones").then().statusCode(200).extract().jsonPath().getList("items.name", String.class);
        assertEquals(130, zones.size());
        assertTrue(zones.contains("us-central1-f"));
        assertFalse(zones.contains("europe-west1-a"));
        given().get(root + "/zones/asia-northeast1-a/diskTypes").then().statusCode(200);
        given().get(root + "/zones/europe-west1-a/diskTypes").then().statusCode(404);
        given().contentType("application/json").body(Map.of("name", "legacy-zone-disk"))
                .post(root + "/zones/europe-west1-a/disks").then().statusCode(404);
        given().get(root + "/regions/mars-north1/subnetworks").then().statusCode(404);
    }

    @Test @SuppressWarnings("unchecked")
    void storedResourcesKeepAnUncataloguedZoneReadableButNotWritable() {
        String project = "compute-" + UUID.randomUUID(), root = "/compute/v1/projects/" + project;
        var store = (ProjectAwareStorageBackend<ComputeProject>) storage.<ComputeProject>create("compute", "compute.json",
                new TypeReference<Map<String, ComputeProject>>() {});
        ComputeProject state = new ComputeProject();
        state.resources.put("zones/europe-west1-a/disks/legacy",
                JsonNodeFactory.instance.objectNode().put("kind", "compute#disk").put("name", "legacy"));
        store.putForProject(project, "state", state);

        given().get(root + "/zones/europe-west1-a/disks/legacy").then().statusCode(200).body("name", equalTo("legacy"));
        post(root + "/zones/europe-west1-a/disks", Map.of("name", "fresh")).then().statusCode(404);
    }

    @Test void diskTypeZonesAreUrlsWhileOtherCatalogsUseZoneNames() {
        for (String zone : java.util.List.of("us-central1-a", "europe-west1-b")) {
            String root = root(), catalog = root + "/zones/" + zone;
            String zoneUrl = "https://www.googleapis.com" + catalog;
            var disk = given().get(catalog + "/diskTypes/pd-standard").then().statusCode(200).extract().jsonPath();
            assertEquals(zoneUrl, disk.getString("zone"));
            assertEquals(zoneUrl + "/diskTypes/pd-standard", disk.getString("selfLink"));
            var zones = given().get(catalog + "/diskTypes").then().statusCode(200).extract().jsonPath()
                    .getList("items.zone", String.class);
            assertFalse(zones.isEmpty());
            assertTrue(zones.stream().allMatch(zoneUrl::equals));
            assertEquals(zone, given().get(catalog + "/machineTypes/n2-standard-4").then().statusCode(200)
                    .extract().jsonPath().getString("zone"));
            assertEquals(zone, given().get(catalog + "/acceleratorTypes/nvidia-l4").then().statusCode(200)
                    .extract().jsonPath().getString("zone"));
        }
    }

    @Test void deletingAnOperationDoesNotCancelItsResource() throws Exception {
        String root = root();
        var created = post(root + "/zones/us-central1-a/disks", Map.of("name", "retained", "sizeGb", "20"));
        created.then().statusCode(200);
        String op = created.jsonPath().getString("selfLink").replaceFirst("https://www.googleapis.com", "");
        given().delete(op).then().statusCode(200);
        given().get(op).then().statusCode(404);
        Thread.sleep(100);
        assertEquals("READY", given().get(root + "/zones/us-central1-a/disks/retained").jsonPath().getString("status"));
    }
    @Test void networksOperationsAndIsolation() throws Exception {
        String root = root();
        String requestId = UUID.randomUUID().toString();
        Map<String,Object> network = Map.of("name", "custom-net", "autoCreateSubnetworks", false);
        Response create = post(root + "/global/networks?requestId=" + requestId, network);
        create.then().statusCode(200);
        assertEquals("compute#operation", create.jsonPath().getString("kind"));
        assertNull(create.jsonPath().get("done"));
        assertEquals(create.jsonPath().getString("name"), post(root + "/global/networks?requestId=" + requestId, network).jsonPath().getString("name"));
        done(root, create);
        post(root + "/global/networks", network).then().statusCode(409);
        given().get(root() + "/global/networks/custom-net").then().statusCode(404);
        done(root, post(root + "/regions/us-central1/subnetworks", Map.of("name", "subnet-a", "network", "global/networks/custom-net", "ipCidrRange", "10.20.0.0/24")));
        given().delete(root + "/global/networks/custom-net").then().statusCode(400);
        done(root, given().delete(root + "/regions/us-central1/subnetworks/subnet-a"));
        done(root, given().delete(root + "/global/networks/custom-net"));
        String operationPath = create.jsonPath().getString("selfLink").replaceFirst("https://www.googleapis.com", "");
        post(operationPath + "/wait", Map.of()).then().statusCode(200);
        given().delete(operationPath).then().statusCode(200);
        given().get(operationPath).then().statusCode(404);
    }
    @Test void paginationTokensAreBoundToProjectAndFilters() throws Exception {
        String root = root();
        for (String name : new String[]{"alpha", "bravo", "charlie"}) { done(root, post(root + "/global/networks", Map.of("name", name, "autoCreateSubnetworks", false))); }
        var page = given().queryParam("maxResults", 1).get(root + "/global/networks").then().statusCode(200).extract().response();
        String token = page.jsonPath().getString("nextPageToken");
        assertEquals("alpha", page.jsonPath().getString("items[0].name"));
        assertEquals("bravo", given().queryParam("pageToken", token).queryParam("maxResults", 1).get(root + "/global/networks").jsonPath().getString("items[0].name"));
        given().queryParam("pageToken", token).get(root() + "/global/networks").then().statusCode(400);
        given().queryParam("pageToken", token).queryParam("filter", "name = alpha").get(root + "/global/networks").then().statusCode(400);
    }
    @Test void subnetAndFirewallValidation() throws Exception {
        String root = root();
        done(root, post(root + "/global/networks", Map.of("name", "network", "autoCreateSubnetworks", false)));
        post(root + "/regions/us-central1/subnetworks", Map.of("name", "bad", "network", "global/networks/missing", "ipCidrRange", "10.0.0.0/24")).then().statusCode(404);
        done(root, post(root + "/regions/us-central1/subnetworks", Map.of("name", "good", "network", "global/networks/network", "ipCidrRange", "10.0.0.0/24")));
        post(root + "/regions/us-central1/subnetworks", Map.of("name", "overlap", "network", "global/networks/network", "ipCidrRange", "10.0.0.0/25")).then().statusCode(400);
        done(root, post(root + "/global/firewalls", Map.of("name", "stream", "network", "global/networks/network", "allowed", java.util.List.of(Map.of("IPProtocol", "udp", "ports", java.util.List.of("48000-49000", "7777"))))));
        given().contentType("application/json").body(Map.of("priority", -1)).patch(root + "/global/firewalls/stream").then().statusCode(400);
        assertEquals(1000, given().get(root + "/global/firewalls/stream").jsonPath().getInt("priority"));
    }
}
