package io.floci.gcp.services.compute;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ComputeAddressIntegrationTest extends ComputeTestSupport {
    private void frontend(String root) throws Exception {
        done(root, post(root + "/global/healthChecks", Map.of("name", "health", "type", "HTTP")));
        done(root, post(root + "/global/backendServices", Map.of("name", "backend", "healthChecks", List.of("global/healthChecks/health"))));
        done(root, post(root + "/global/urlMaps", Map.of("name", "map", "defaultService", "global/backendServices/backend")));
        done(root, post(root + "/global/targetHttpProxies", Map.of("name", "proxy", "urlMap", "global/urlMaps/map")));
    }
    private Map<String, Object> rule(String name, String ip, String ports) {
        return Map.of("name", name, "IPAddress", ip, "portRange", ports, "IPProtocol", "TCP",
                "target", "global/targetHttpProxies/proxy", "loadBalancingScheme", "EXTERNAL_MANAGED");
    }
    @Test void prefixNamesConflictAndSharedPortsKeepExactOwners() throws Exception {
        for (List<String> names : List.of(List.of("frontend2", "frontend"), List.of("frontend", "frontend2"))) {
            String root = root(); frontend(root);
            done(root, post(root + "/global/addresses", Map.of("name", "reserved")));
            String address = root + "/global/addresses/reserved";
            done(root, post(root + "/global/forwardingRules", rule(names.get(0), "global/addresses/reserved", "80-81")));
            String before = given().get(address).asString();
            post(root + "/global/forwardingRules", rule(names.get(1), "global/addresses/reserved", "81-82")).then().statusCode(400);
            assertEquals(before, given().get(address).asString());
            given().get(root + "/global/forwardingRules/" + names.get(1)).then().statusCode(404);
            done(root, post(root + "/global/forwardingRules", rule(names.get(1), "global/addresses/reserved", "82")));
            assertEquals(2, given().get(address).jsonPath().getList("users").size());
            done(root, given().delete(root + "/global/forwardingRules/" + names.get(0)));
            String remaining = given().get(root + "/global/forwardingRules/" + names.get(1)).jsonPath().getString("selfLink");
            assertEquals(List.of(remaining), given().get(address).jsonPath().getList("users"));
            assertEquals("IN_USE", given().get(address).jsonPath().getString("status"));
            done(root, given().delete(root + "/global/forwardingRules/" + names.get(1)));
            assertEquals("RESERVED", given().get(address).jsonPath().getString("status"));
            assertTrue(given().get(address).jsonPath().getList("users").isEmpty());
        }
    }
    @Test void immutableIpAndFailedUpdatesPreserveAllState() throws Exception {
        String root = root(); frontend(root);
        for (String name : List.of("one", "two")) { done(root, post(root + "/global/addresses", Map.of("name", name))); }
        done(root, post(root + "/global/forwardingRules", rule("frontend", "global/addresses/one", "80")));
        String path = root + "/global/forwardingRules/frontend";
        var original = given().get(path).jsonPath();
        String one = given().get(root + "/global/addresses/one").asString(), two = given().get(root + "/global/addresses/two").asString();
        for (Map<String, Object> patch : List.<Map<String, Object>>of(
                Map.of("fingerprint", original.getString("fingerprint"), "IPAddress", "global/addresses/two"),
                Map.of("fingerprint", "stale", "description", "changed"),
                Map.of("fingerprint", original.getString("fingerprint"), "target", "global/targetHttpProxies/missing"))) {
            given().contentType("application/json").body(patch).patch(path).then().statusCode(patch.get("fingerprint").equals("stale") ? 412 : patch.containsKey("target") ? 404 : 400);
            assertEquals(original.getMap(""), given().get(path).jsonPath().getMap(""));
            assertEquals(one, given().get(root + "/global/addresses/one").asString());
            assertEquals(two, given().get(root + "/global/addresses/two").asString());
        }
        for (String value : List.of("global/addresses/one", original.getString("IPAddress"), given().get(root + "/global/addresses/one").jsonPath().getString("selfLink"))) {
            done(root, given().contentType("application/json").body(Map.of("fingerprint", given().get(path).jsonPath().getString("fingerprint"), "IPAddress", value)).patch(path));
            assertEquals(original.getString("IPAddress"), given().get(path).jsonPath().getString("IPAddress"));
        }
        done(root, given().delete(root + "/global/addresses/one"));
        given().get(root + "/global/addresses/one").then().statusCode(404);
        done(root, given().contentType("application/json").body(Map.of("fingerprint", given().get(path).jsonPath().getString("fingerprint"), "description", "released reservation")).patch(path));
        assertEquals(original.getString("IPAddress"), given().get(path).jsonPath().getString("IPAddress"));
        post(root + "/global/addresses", Map.of("name", "reuse", "address", original.getString("IPAddress"))).then().statusCode(400);
        done(root, given().delete(path));
        done(root, post(root + "/global/addresses", Map.of("name", "reuse", "address", original.getString("IPAddress"))));
        var ephemeral = new java.util.HashMap<>(rule("ephemeral", "unused", "80")); ephemeral.remove("IPAddress");
        done(root, post(root + "/global/forwardingRules", ephemeral));
        String ep = root + "/global/forwardingRules/ephemeral", ip = given().get(ep).jsonPath().getString("IPAddress");
        done(root, given().contentType("application/json").body(Map.of("fingerprint", given().get(ep).jsonPath().getString("fingerprint"), "description", "unchanged IP")).patch(ep));
        assertEquals(ip, given().get(ep).jsonPath().getString("IPAddress"));
    }
    @Test void regionalReservationReleaseKeepsInstanceIpUntilDetached() throws Exception {
        String root = root(), region = root + "/regions/us-central1", zone = root + "/zones/us-central1-a";
        done(root, post(root + "/global/networks", Map.of("name", "net", "autoCreateSubnetworks", false)));
        done(root, post(region + "/subnetworks", Map.of("name", "subnet", "network", "global/networks/net", "ipCidrRange", "10.65.0.0/24")));
        done(root, post(region + "/addresses", Map.of("name", "reserved")));
        String ip = given().get(region + "/addresses/reserved").jsonPath().getString("address");
        done(root, post(zone + "/instances", Map.of("name", "vm", "machineType", "zones/us-central1-a/machineTypes/n2-standard-4",
                "networkInterfaces", List.of(Map.of("subnetwork", "regions/us-central1/subnetworks/subnet", "accessConfigs", List.of(Map.of("natIP", ip)))),
                "disks", List.of(Map.of("boot", true, "autoDelete", true, "initializeParams", Map.of("diskSizeGb", "20"))))));
        done(root, given().delete(region + "/addresses/reserved"));
        given().get(region + "/addresses/reserved").then().statusCode(404);
        assertEquals(ip, given().get(zone + "/instances/vm").jsonPath().getString("networkInterfaces[0].accessConfigs[0].natIP"));
        post(region + "/addresses", Map.of("name", "reuse", "address", ip)).then().statusCode(400);
        done(root, given().queryParam("networkInterface", "nic0").queryParam("accessConfig", "External NAT").post(zone + "/instances/vm/deleteAccessConfig"));
        done(root, post(region + "/addresses", Map.of("name", "reuse", "address", ip)));
    }
}
