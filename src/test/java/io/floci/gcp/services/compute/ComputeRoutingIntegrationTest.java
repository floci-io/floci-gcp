package io.floci.gcp.services.compute;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.List;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ComputeRoutingIntegrationTest extends ComputeTestSupport {
    @Test void routingMembershipAndDependencyCleanup() throws Exception {
        String root = root(), zone = "/zones/us-central1-a";
        done(root, post(root + "/global/networks", Map.of("name", "net", "autoCreateSubnetworks", false)));
        done(root, post(root + "/regions/us-central1/subnetworks", Map.of("name", "subnet", "network", "global/networks/net", "ipCidrRange", "10.8.0.0/24")));
        done(root, post(root + zone + "/instances", Map.of("name", "vm", "machineType", "zones/us-central1-a/machineTypes/n2-standard-4",
                "networkInterfaces", List.of(Map.of("subnetwork", "regions/us-central1/subnetworks/subnet")),
                "disks", List.of(Map.of("boot", true, "autoDelete", true, "initializeParams", Map.of("diskSizeGb", "20"))))));
        done(root, post(root + zone + "/networkEndpointGroups", Map.of("name", "neg", "networkEndpointType", "GCE_VM_IP_PORT", "network", "global/networks/net", "subnetwork", "regions/us-central1/subnetworks/subnet")));
        Map<String,Object> endpoints = Map.of("networkEndpoints", List.of(Map.of("instance", "vm", "port", 8080)));
        done(root, post(root + zone + "/networkEndpointGroups/neg/attachNetworkEndpoints", endpoints));
        assertEquals(8080, post(root + zone + "/networkEndpointGroups/neg/listNetworkEndpoints", Map.of()).jsonPath().getInt("items[0].networkEndpoint.port"));
        done(root, post(root + "/global/healthChecks", Map.of("name", "health", "type", "HTTP", "httpHealthCheck", Map.of("port", 8081))));
        done(root, post(root + "/global/backendServices", Map.of("name", "backend", "loadBalancingScheme", "EXTERNAL_MANAGED", "healthChecks", List.of("global/healthChecks/health"), "backends", List.of(Map.of("group", "zones/us-central1-a/networkEndpointGroups/neg", "balancingMode", "RATE", "maxRatePerEndpoint", 100)))));
        done(root, post(root + "/global/urlMaps", Map.of("name", "routes", "defaultService", "global/backendServices/backend", "hostRules", List.of(Map.of("hosts", List.of("*"), "pathMatcher", "routes")),
                "pathMatchers", List.of(Map.of("name", "routes", "defaultService", "global/backendServices/backend", "pathRules", List.of(Map.of("paths", List.of("/route-one/*", "/route-two/*"), "service", "global/backendServices/backend")))))));
        done(root, post(root + "/global/targetHttpProxies", Map.of("name", "proxy", "urlMap", "global/urlMaps/routes")));
        done(root, post(root + "/global/addresses", Map.of("name", "frontend")));
        done(root, post(root + "/global/forwardingRules", Map.of("name", "frontend", "target", "global/targetHttpProxies/proxy", "IPAddress", "global/addresses/frontend", "IPProtocol", "TCP", "portRange", "80", "loadBalancingScheme", "EXTERNAL_MANAGED")));
        given().delete(root + "/global/backendServices/backend").then().statusCode(400);
        given().delete(root + zone + "/instances/vm").then().statusCode(400);
        assertEquals(1, given().get(root + zone + "/disks/vm").jsonPath().getList("users").size());
        done(root, given().delete(root + "/global/forwardingRules/frontend"));
        done(root, given().delete(root + "/global/addresses/frontend"));
        done(root, given().delete(root + "/global/targetHttpProxies/proxy"));
        String fingerprint = given().get(root + "/global/urlMaps/routes").jsonPath().getString("fingerprint");
        done(root, given().contentType("application/json").body(Map.of("fingerprint", fingerprint, "pathMatchers", List.of(Map.of("name", "routes", "defaultService", "global/backendServices/backend", "pathRules", List.of(Map.of("paths", List.of("/route-two/*"), "service", "global/backendServices/backend")))))).patch(root + "/global/urlMaps/routes"));
        assertEquals("/route-two/*", given().get(root + "/global/urlMaps/routes").jsonPath().getString("pathMatchers[0].pathRules[0].paths[0]"));
        given().get(root + "/global/backendServices/backend").then().statusCode(200);
        done(root, given().delete(root + "/global/urlMaps/routes"));
        done(root, given().delete(root + "/global/backendServices/backend"));
        done(root, given().delete(root + "/global/healthChecks/health"));
        done(root, post(root + zone + "/networkEndpointGroups/neg/detachNetworkEndpoints", endpoints));
        done(root, given().delete(root + zone + "/networkEndpointGroups/neg"));
        done(root, given().delete(root + zone + "/instances/vm"));
        given().get(root + zone + "/disks/vm").then().statusCode(404);
    }
}
