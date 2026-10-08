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
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ComputeIamPolicyIntegrationTest extends ComputeTestSupport {
    @TestHTTPResource URI endpoint;

    private static Policy policy() {
        return Policy.newBuilder().addBindings(Binding.newBuilder()
                .setRole("roles/compute.instanceAdmin.v1").addMembers("serviceAccount:sa@p.iam.gserviceaccount.com")).build();
    }

    @Test void grpcPolicyWritesFailForMissingResourcesAndCannotResurrectDeletedOnes() throws Exception {
        String root = root(), project = root.substring(root.lastIndexOf('/') + 1);
        String disk = root + "/zones/us-central1-a/disks/data";
        String diskName = "projects/" + project + "/zones/us-central1-a/disks/data";
        ManagedChannel channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
        try {
            var stub = IAMPolicyGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
            for (String missing : List.of("zones/us-central1-a/instances/nope", "zones/us-central1-a/disks/nope",
                    "global/images/nope", "global/snapshots/nope", "regions/us-central1/subnetworks/nope")) {
                String name = "projects/" + project + "/" + missing;
                assertEquals(Status.Code.NOT_FOUND, assertThrows(StatusRuntimeException.class, () -> stub.setIamPolicy(
                        SetIamPolicyRequest.newBuilder().setResource(name).setPolicy(policy()).build())).getStatus().getCode(), name);
                assertEquals(Status.Code.NOT_FOUND, assertThrows(StatusRuntimeException.class, () -> stub.getIamPolicy(
                        GetIamPolicyRequest.newBuilder().setResource(name).build())).getStatus().getCode(), name);
            }

            done(root, post(root + "/zones/us-central1-a/disks", Map.of("name", "data", "sizeGb", "10")));
            stub.setIamPolicy(SetIamPolicyRequest.newBuilder().setResource(diskName).setPolicy(policy()).build());
            done(root, given().delete(disk));

            assertEquals(Status.Code.NOT_FOUND, assertThrows(StatusRuntimeException.class, () -> stub.setIamPolicy(
                    SetIamPolicyRequest.newBuilder().setResource(diskName).setPolicy(policy()).build())).getStatus().getCode());
            post(disk + "/setIamPolicy", Map.of("policy", Map.of())).then().statusCode(404);

            done(root, post(root + "/zones/us-central1-a/disks", Map.of("name", "data", "sizeGb", "10")));
            assertEquals(0, stub.getIamPolicy(GetIamPolicyRequest.newBuilder().setResource(diskName).build()).getBindingsCount());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test void diskPolicyRoundTripsThroughGetSetAndTestPermissions() throws Exception {
        String root = root(), disk = root + "/zones/us-central1-a/disks/data";
        done(root, post(root + "/zones/us-central1-a/disks", Map.of("name", "data", "sizeGb", "10")));

        var empty = given().queryParam("optionsRequestedPolicyVersion", 3).get(disk + "/getIamPolicy").then().statusCode(200).extract().response();
        assertTrue(empty.jsonPath().getList("bindings").isEmpty());
        assertEquals("ACAB", empty.jsonPath().getString("etag"));

        Map<String, Object> binding = Map.of("role", "roles/compute.instanceAdmin.v1", "members", List.of("serviceAccount:sa@p.iam.gserviceaccount.com"));
        var set = post(disk + "/setIamPolicy", Map.of("policy", Map.of("version", 3, "etag", "ACAB", "bindings", List.of(binding))))
                .then().statusCode(200).extract().response();
        assertEquals("roles/compute.instanceAdmin.v1", set.jsonPath().getString("bindings[0].role"));
        assertNotEquals("ACAB", set.jsonPath().getString("etag"));

        var read = given().get(disk + "/getIamPolicy").then().statusCode(200).extract().response();
        assertEquals(set.jsonPath().getString("etag"), read.jsonPath().getString("etag"));
        assertEquals("serviceAccount:sa@p.iam.gserviceaccount.com", read.jsonPath().getString("bindings[0].members[0]"));

        post(disk + "/setIamPolicy", Map.of("policy", Map.of("etag", "ACAB", "bindings", List.of()))).then().statusCode(409);
        assertEquals(List.of("compute.disks.get"), post(disk + "/testIamPermissions", Map.of("permissions", List.of("compute.disks.get")))
                .then().statusCode(200).extract().jsonPath().getList("permissions"));

        done(root, given().delete(disk));
        done(root, post(root + "/zones/us-central1-a/disks", Map.of("name", "data", "sizeGb", "10")));
        assertTrue(given().get(disk + "/getIamPolicy").jsonPath().getList("bindings").isEmpty());
    }

    @Test void missingResourceReturnsNotFound() {
        String root = root();
        given().get(root + "/zones/us-central1-a/instances/nope/getIamPolicy").then().statusCode(404);
        post(root + "/zones/us-central1-a/instances/nope/setIamPolicy", Map.of("policy", Map.of())).then().statusCode(404);
    }
}
