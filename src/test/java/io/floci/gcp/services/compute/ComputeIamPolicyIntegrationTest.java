package io.floci.gcp.services.compute;

import com.google.iam.v1.Binding;
import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.IAMPolicyGrpc;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ComputeIamPolicyIntegrationTest extends ComputeTestSupport {
    private static final String ZONE = "/zones/us-central1-a";
    private static final String REGION = "/regions/us-central1";
    private static final String ROLE = "roles/compute.instanceAdmin.v1";
    private static final String MEMBER = "serviceAccount:sa@p.iam.gserviceaccount.com";
    private static final Map<String, Object> BINDING = Map.of("role", ROLE, "members", List.of(MEMBER));

    @TestHTTPResource URI endpoint;

    private static Policy policy() {
        return Policy.newBuilder().addBindings(Binding.newBuilder().setRole(ROLE).addMembers(MEMBER)).build();
    }

    private void disk(String root, String name) throws Exception {
        done(root, post(root + ZONE + "/disks", Map.of("name", name, "sizeGb", "10")));
    }

    private void network(String root) throws Exception {
        done(root, post(root + "/global/networks", Map.of("name", "net", "autoCreateSubnetworks", false)));
        done(root, post(root + REGION + "/subnetworks", Map.of("name", "subnet", "network", "global/networks/net", "ipCidrRange", "10.10.0.0/24")));
    }

    private Map<String, Object> vm(boolean autoDelete) {
        return Map.of("name", "vm", "machineType", "zones/us-central1-a/machineTypes/e2-medium",
                "networkInterfaces", List.of(Map.of("subnetwork", "regions/us-central1/subnetworks/subnet")),
                "disks", List.of(Map.of("boot", true, "autoDelete", autoDelete, "initializeParams", Map.of("diskSizeGb", "20"))));
    }

    private void assertBound(String resource) {
        Response read = given().get(resource + "/getIamPolicy").then().statusCode(200).extract().response();
        assertEquals(ROLE, read.jsonPath().getString("bindings[0].role"), resource);
        assertEquals(MEMBER, read.jsonPath().getString("bindings[0].members[0]"), resource);
    }

    private void assertUnbound(String resource) {
        assertTrue(given().get(resource + "/getIamPolicy").then().statusCode(200).extract()
                .jsonPath().getList("bindings").isEmpty(), resource);
    }

    private void bind(String resource) {
        post(resource + "/setIamPolicy", Map.of("policy", Map.of("bindings", List.of(BINDING)))).then().statusCode(200);
    }

    @Test void everyCollectionServesPolicies() throws Exception {
        String root = root();
        network(root);
        disk(root, "data");
        done(root, post(root + ZONE + "/instances", vm(false)));
        done(root, post(root + "/global/images", Map.of("name", "img", "sourceDisk", "zones/us-central1-a/disks/data")));
        done(root, post(root + "/global/snapshots", Map.of("name", "snap", "sourceDisk", "zones/us-central1-a/disks/data")));
        done(root, post(root + REGION + "/resourcePolicies", Map.of("name", "sched", "instanceSchedulePolicy", Map.of(
                "timeZone", "UTC", "vmStartSchedule", Map.of("schedule", "0 7 * * *")))));
        for (String resource : List.of(root + ZONE + "/instances/vm", root + ZONE + "/disks/data", root + "/global/images/img",
                root + "/global/snapshots/snap", root + REGION + "/subnetworks/subnet", root + REGION + "/resourcePolicies/sched")) {
            assertUnbound(resource);
            bind(resource);
            assertBound(resource);
            assertEquals(List.of("compute.any.get"), post(resource + "/testIamPermissions", Map.of("permissions", List.of("compute.any.get")))
                    .then().statusCode(200).extract().jsonPath().getList("permissions"), resource);
        }
    }

    @Test void policyRoundTripsWithEtagChecks() throws Exception {
        String root = root(), disk = root + ZONE + "/disks/data";
        disk(root, "data");

        Response empty = given().queryParam("optionsRequestedPolicyVersion", 3).get(disk + "/getIamPolicy")
                .then().statusCode(200).extract().response();
        assertEquals("ACAB", empty.jsonPath().getString("etag"));

        Response set = post(disk + "/setIamPolicy", Map.of("policy", Map.of("version", 3, "etag", "ACAB", "bindings", List.of(BINDING))))
                .then().statusCode(200).extract().response();
        assertNotEquals("ACAB", set.jsonPath().getString("etag"));
        assertEquals(set.jsonPath().getString("etag"), given().get(disk + "/getIamPolicy").jsonPath().getString("etag"));

        post(disk + "/setIamPolicy", Map.of("policy", Map.of("etag", "ACAB", "bindings", List.of()))).then().statusCode(409);
        assertBound(disk);
    }

    @Test void flatRequestBodyIsAccepted() throws Exception {
        String root = root(), disk = root + ZONE + "/disks/data";
        disk(root, "data");
        post(disk + "/setIamPolicy", Map.of("bindings", List.of(BINDING), "etag", "ACAB")).then().statusCode(200);
        assertBound(disk);
    }

    @Test void malformedPoliciesAreRejected() throws Exception {
        String root = root(), disk = root + ZONE + "/disks/data";
        disk(root, "data");
        post(disk + "/setIamPolicy", Map.of("policy", Map.of("version", "three"))).then().statusCode(400);
        post(disk + "/setIamPolicy", Map.of("policy", Map.of("etag", 1))).then().statusCode(400);
        post(disk + "/setIamPolicy", Map.of("policy", Map.of("bindings", "none"))).then().statusCode(400);
        post(disk + "/setIamPolicy", Map.of("policy", Map.of("bindings", List.of("none")))).then().statusCode(400);
    }

    @Test void aPolicyFieldThatIsNotAnObjectLeavesTheBindingsAlone() throws Exception {
        String root = root(), disk = root + ZONE + "/disks/data";
        disk(root, "data");
        bind(disk);
        post(disk + "/setIamPolicy", Map.of("policy", "invalid")).then().statusCode(400);
        assertBound(disk);
    }

    @Test void numericIdReachesTheSamePolicy() throws Exception {
        String root = root(), disk = root + ZONE + "/disks/data";
        disk(root, "data");
        String id = given().get(disk).jsonPath().getString("id");
        bind(root + ZONE + "/disks/" + id);
        assertBound(disk);
        assertBound(root + ZONE + "/disks/" + id);
    }

    @Test void deleteAndRecreateStartsWithoutAPolicy() throws Exception {
        String root = root(), disk = root + ZONE + "/disks/data";
        disk(root, "data");
        bind(disk);
        done(root, given().delete(disk));
        given().get(disk + "/getIamPolicy").then().statusCode(404);
        disk(root, "data");
        assertUnbound(disk);
    }

    @Test void autoDeletedBootDiskTakesItsPolicyWithIt() throws Exception {
        String root = root(), vm = root + ZONE + "/instances/vm";
        network(root);
        done(root, post(root + ZONE + "/instances", vm(true)));
        String source = given().get(vm).jsonPath().getString("disks[0].source");
        String disk = root + ZONE + "/disks/" + source.substring(source.lastIndexOf('/') + 1);
        bind(vm);
        bind(disk);

        done(root, given().delete(vm));
        given().get(disk + "/getIamPolicy").then().statusCode(404);

        done(root, post(root + ZONE + "/instances", vm(true)));
        assertUnbound(vm);
        assertUnbound(disk);
    }

    @Test void keptBootDiskKeepsItsPolicy() throws Exception {
        String root = root(), vm = root + ZONE + "/instances/vm";
        network(root);
        done(root, post(root + ZONE + "/instances", vm(false)));
        String source = given().get(vm).jsonPath().getString("disks[0].source");
        String disk = root + ZONE + "/disks/" + source.substring(source.lastIndexOf('/') + 1);
        bind(disk);
        done(root, given().delete(vm));
        assertBound(disk);
    }

    @Test void missingResourcesReturnNotFound() {
        String root = root();
        for (String missing : List.of(ZONE + "/instances/nope", ZONE + "/disks/nope", "/global/images/nope",
                "/global/snapshots/nope", REGION + "/subnetworks/nope", REGION + "/resourcePolicies/nope")) {
            given().get(root + missing + "/getIamPolicy").then().statusCode(404);
            post(root + missing + "/setIamPolicy", Map.of("policy", Map.of())).then().statusCode(404);
            post(root + missing + "/testIamPermissions", Map.of("permissions", List.of("x.y.z"))).then().statusCode(404);
        }
    }

    @Test void otherCollectionsStayUnimplemented() throws Exception {
        String root = root();
        done(root, post(root + "/global/networks", Map.of("name", "net", "autoCreateSubnetworks", false)));
        given().get(root + "/global/networks/net/getIamPolicy").then().statusCode(501);
    }

    @Test void publicImagesKeepTheirReadOnlyRules() {
        post("/compute/v1/projects/debian-cloud/global/images/debian-12/setIamPolicy", Map.of("policy", Map.of()))
                .then().statusCode(403);
        given().get("/compute/v1/projects/debian-cloud/global/images/debian-12/getIamPolicy").then().statusCode(403);
    }

    @Test void sharedIamEndpointSeesComputeResources() throws Exception {
        String root = root(), project = root.substring(root.lastIndexOf('/') + 1), disk = root + ZONE + "/disks/data";
        String diskName = "projects/" + project + "/zones/us-central1-a/disks/data";
        ManagedChannel channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
        try {
            IAMPolicyGrpc.IAMPolicyBlockingStub stub = IAMPolicyGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
            for (String missing : List.of(ZONE + "/instances/nope", ZONE + "/disks/nope", "/global/images/nope",
                    "/global/snapshots/nope", REGION + "/subnetworks/nope", REGION + "/resourcePolicies/nope")) {
                String name = "projects/" + project + missing;
                assertEquals(Status.Code.NOT_FOUND, assertThrows(StatusRuntimeException.class, () -> stub.setIamPolicy(
                        SetIamPolicyRequest.newBuilder().setResource(name).setPolicy(policy()).build())).getStatus().getCode(), name);
                assertEquals(Status.Code.NOT_FOUND, assertThrows(StatusRuntimeException.class, () -> stub.getIamPolicy(
                        GetIamPolicyRequest.newBuilder().setResource(name).build())).getStatus().getCode(), name);
            }

            disk(root, "data");
            stub.setIamPolicy(SetIamPolicyRequest.newBuilder().setResource(diskName).setPolicy(policy()).build());
            assertBound(disk);
            done(root, given().delete(disk));

            assertEquals(Status.Code.NOT_FOUND, assertThrows(StatusRuntimeException.class, () -> stub.setIamPolicy(
                    SetIamPolicyRequest.newBuilder().setResource(diskName).setPolicy(policy()).build())).getStatus().getCode());

            disk(root, "data");
            assertEquals(0, stub.getIamPolicy(GetIamPolicyRequest.newBuilder().setResource(diskName).build()).getBindingsCount());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
