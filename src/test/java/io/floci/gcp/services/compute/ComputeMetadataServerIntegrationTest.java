package io.floci.gcp.services.compute;

import io.floci.gcp.core.common.ProjectNumbers;
import io.floci.gcp.services.compute.ComputeInstanceRuntime.InstanceRef;
import io.floci.gcp.services.compute.ComputeInstanceRuntime.Phase;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** The metadata server as a guest sees it, with the caller lookup mocked to this test's instance. */
@QuarkusTest
class ComputeMetadataServerIntegrationTest extends ComputeTestSupport {
    private static final String MD = "/computeMetadata/v1/";

    @InjectMock ComputeInstanceRuntime runtime;

    private String project;
    private String root;

    @BeforeEach
    void instance() throws Exception {
        when(runtime.enabled()).thenReturn(false);
        when(runtime.phase(anyString())).thenReturn(Phase.DONE);
        root = root();
        project = root.substring(root.lastIndexOf('/') + 1);
        done(root, post(root + "/global/networks", Map.of("name", "net", "autoCreateSubnetworks", false)));
        done(root, post(root + "/regions/us-central1/subnetworks", Map.of("name", "subnet",
                "network", "global/networks/net", "ipCidrRange", "10.10.0.0/24")));
        String fingerprint = given().get(root).jsonPath().getString("commonInstanceMetadata.fingerprint");
        done(root, post(root + "/setCommonInstanceMetadata", Map.of("fingerprint", fingerprint,
                "items", List.of(Map.of("key", "team", "value", "platform")))));
        done(root, post(root + "/zones/us-central1-a/instances", Map.of("name", "vm",
                "machineType", "zones/us-central1-a/machineTypes/e2-standard-2",
                "networkInterfaces", List.of(Map.of("subnetwork", "regions/us-central1/subnetworks/subnet",
                        "accessConfigs", List.of(Map.of("type", "ONE_TO_ONE_NAT")))),
                "metadata", Map.of("items", List.of(Map.of("key", "startup-script", "value", "echo hi"),
                        Map.of("key", "role", "value", "web"))),
                "tags", Map.of("items", List.of("http-server", "ssh")),
                "serviceAccounts", List.of(Map.of("email", "default",
                        "scopes", List.of("https://www.googleapis.com/auth/cloud-platform"))),
                "disks", List.of(Map.of("boot", true, "initializeParams", Map.of("diskSizeGb", "10"))))));
        when(runtime.instanceAt(anyString())).thenReturn(Optional.of(new InstanceRef(project, "us-central1-a", "vm")));
    }

    @Test void requestsNeedTheFlavorHeaderAndNoForwardingHeader() {
        given().get(MD + "instance/name").then().statusCode(403).header("Metadata-Flavor", "Google");
        md().header("X-Forwarded-For", "10.0.0.1").get(MD + "instance/name").then().statusCode(403);
        md().get(MD + "instance/name").then().statusCode(200).header("Metadata-Flavor", "Google")
                .header("ETag", notNullValue()).body(equalTo("vm"));
    }

    @Test void callersWithoutAnInstanceAreNotServed() {
        when(runtime.instanceAt(anyString())).thenReturn(Optional.empty());
        md().get(MD + "instance/name").then().statusCode(404);
    }

    @Test void instanceAndProjectValuesUseTheDocumentedFormats() {
        String number = ProjectNumbers.of(project);
        assertEquals("projects/" + number + "/zones/us-central1-a", text("instance/zone"));
        assertEquals("projects/" + number + "/machineTypes/e2-standard-2", text("instance/machine-type"));
        assertEquals("vm.us-central1-a.c." + project + ".internal", text("instance/hostname"));
        assertTrue(text("instance/id").matches("[0-9]+"));
        assertEquals(project, text("project/project-id"));
        assertEquals(number, text("project/numeric-project-id"));
        assertEquals("[\"http-server\",\"ssh\"]", text("instance/tags"));
        assertEquals("http-server\nssh\n", md().queryParam("alt", "text").get(MD + "instance/tags").asString());
        assertEquals("10.10.0.2", text("instance/network-interfaces/0/ip"));
        assertTrue(text("instance/network-interfaces/0/access-configs/0/external-ip").startsWith("203.0.113."));
        assertEquals("projects/" + number + "/networks/net", text("instance/network-interfaces/0/network"));
        assertEquals("255.255.255.0", text("instance/network-interfaces/0/subnetmask"));
        assertEquals("10.10.0.1", text("instance/network-interfaces/0/gateway"));
        assertEquals("googleapis.com", text("universe/universe-domain"));
    }

    @Test void attributesComeFromInstanceAndProjectMetadata() {
        assertEquals("echo hi", text("instance/attributes/startup-script"));
        assertEquals("role\nstartup-script\n", text("instance/attributes/"));
        assertEquals("platform", text("project/attributes/team"));
        md().get(MD + "instance/attributes/missing").then().statusCode(404);
        md().get(MD + "project/attributes/role").then().statusCode(404);
    }

    @Test void directoriesListRedirectAndRenderRecursively() {
        assertEquals("instance/\nproject/\nuniverse/\n", text(""));
        md().redirects().follow(false).get(MD + "instance").then().statusCode(301)
                .header("Location", endsWith("/computeMetadata/v1/instance/"));
        md().redirects().follow(false).queryParam("recursive", "true").queryParam("alt", "text").get(MD + "instance")
                .then().statusCode(301).header("Location", endsWith("/computeMetadata/v1/instance/?recursive=true&alt=text"));
        JsonPath json = md().queryParam("recursive", "true").get(MD + "instance/").then().statusCode(200)
                .contentType("application/json").extract().jsonPath();
        assertEquals("echo hi", json.getString("attributes.startup-script"));
        assertEquals("10.10.0.2", json.getString("networkInterfaces[0].ip"));
        assertTrue(json.getString("networkInterfaces[0].accessConfigs[0].externalIp").startsWith("203.0.113."));
        assertEquals(1460, json.getInt("networkInterfaces[0].mtu"));
        assertEquals(List.of("http-server", "ssh"), json.getList("tags"));
        String text = md().queryParam("recursive", "true").queryParam("alt", "text").get(MD + "instance/attributes/").asString();
        assertTrue(text.contains("startup-script echo hi\n"), text);
    }

    @Test void serviceAccountsServeEmailScopesTokensAndIdentity() {
        String email = ProjectNumbers.of(project) + "-compute@developer.gserviceaccount.com";
        assertEquals(email, text("instance/service-accounts/default/email"));
        assertEquals("https://www.googleapis.com/auth/cloud-platform\n", text("instance/service-accounts/default/scopes"));
        assertEquals(email + "/\ndefault/\n", text("instance/service-accounts/"));
        md().get(MD + "instance/service-accounts/default/token").then().statusCode(200)
                .body("token_type", equalTo("Bearer")).body("expires_in", equalTo(3599))
                .body("access_token", startsWith("ya29."));
        md().get(MD + "instance/service-accounts/" + email + "/token").then().statusCode(200);
        JsonPath recursive = md().queryParam("recursive", "true").get(MD + "instance/service-accounts/").then().statusCode(200)
                .extract().jsonPath();
        assertEquals(email, recursive.getString("default.email"));
        assertNull(recursive.get("default.token"));

        md().get(MD + "instance/service-accounts/default/identity").then().statusCode(400);
        String jwt = md().queryParam("audience", "https://svc.example").queryParam("format", "full")
                .get(MD + "instance/service-accounts/default/identity").then().statusCode(200).extract().asString();
        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length);
        String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertTrue(claims.contains("\"aud\":\"https://svc.example\""), claims);
        assertTrue(claims.contains("\"instance_name\":\"vm\""), claims);
    }

    @Test void waitForChangeReturnsAtOnceForAStaleEtagAndOtherwiseOnTheChange() throws Exception {
        String etag = md().get(MD + "instance/attributes/role").header("ETag");
        assertEquals("web", md().queryParam("wait_for_change", "true").queryParam("last_etag", "stale")
                .get(MD + "instance/attributes/role").asString());

        long started = System.nanoTime();
        assertEquals("web", md().queryParam("wait_for_change", "true").queryParam("timeout_sec", "1")
                .get(MD + "instance/attributes/role").asString());
        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(900));

        CompletableFuture<String> waiting = CompletableFuture.supplyAsync(() -> md()
                .queryParam("wait_for_change", "true").queryParam("last_etag", etag).queryParam("timeout_sec", "20")
                .get(MD + "instance/attributes/role").asString());
        Thread.sleep(500);
        String fingerprint = given().get(root + "/zones/us-central1-a/instances/vm").jsonPath().getString("metadata.fingerprint");
        done(root, post(root + "/zones/us-central1-a/instances/vm/setMetadata", Map.of("fingerprint", fingerprint,
                "items", List.of(Map.of("key", "role", "value", "worker")))));
        assertEquals("worker", waiting.get(10, TimeUnit.SECONDS));

        md().queryParam("wait_for_change", "true").get(MD + "instance/service-accounts/default/token").then().statusCode(400);
        md().queryParam("wait_for_change", "true").get(MD + "instance/attributes/").then().statusCode(400);
        md().queryParam("wait_for_change", "true").queryParam("timeout_sec", "x").get(MD + "instance/name").then().statusCode(400);
        md().queryParam("wait_for_change", "true").queryParam("timeout_sec", Long.toString(Long.MAX_VALUE))
                .get(MD + "instance/name").then().statusCode(400);
    }

    private static RequestSpecification md() {
        return RestAssured.given().header("Metadata-Flavor", "Google");
    }

    private static String text(String path) {
        return md().get(MD + path).then().statusCode(200).extract().asString();
    }
}
