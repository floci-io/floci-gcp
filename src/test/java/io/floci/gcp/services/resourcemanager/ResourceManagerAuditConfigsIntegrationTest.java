package io.floci.gcp.services.resourcemanager;

import com.google.iam.v1.AuditConfig;
import com.google.iam.v1.AuditLogConfig;
import com.google.iam.v1.Binding;
import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.IAMPolicyGrpc;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import com.google.protobuf.FieldMask;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class ResourceManagerAuditConfigsIntegrationTest {
    private static final Map<String, Object> DATA_ACCESS = Map.of("service", "allServices", "auditLogConfigs", List.of(
            Map.of("logType", "DATA_READ", "exemptedMembers", List.of("user:jose@example.com")),
            Map.of("logType", "DATA_WRITE")));
    private static final List<Map<String, Object>> OWNER = List.of(
            Map.of("role", "roles/owner", "members", List.of("user:admin@example.com")));

    @TestHTTPResource
    URI endpoint;

    @Test
    void auditConfigsChangeOnlyWhenTheUpdateMaskNamesThem() {
        String base = "/v1/projects/audit-cfg-" + UUID.randomUUID().toString().substring(0, 8);

        set(base, Map.of("policy", Map.of("bindings", OWNER, "auditConfigs", List.of(DATA_ACCESS))))
                .then().statusCode(200);
        assertNull(get(base).get("auditConfigs"), "the default mask is bindings, etag");

        set(base, Map.of("policy", Map.of("bindings", OWNER, "auditConfigs", List.of(DATA_ACCESS)),
                "updateMask", "bindings,etag,auditConfigs"))
                .then().statusCode(200).body("auditConfigs[0].service", equalTo("allServices"));
        JsonPath policy = get(base);
        assertEquals("DATA_READ", policy.getString("auditConfigs[0].auditLogConfigs[0].logType"));
        assertEquals(List.of("user:jose@example.com"),
                policy.getList("auditConfigs[0].auditLogConfigs[0].exemptedMembers"));
        assertEquals("DATA_WRITE", policy.getString("auditConfigs[0].auditLogConfigs[1].logType"));

        set(base, Map.of("policy", Map.of("bindings", List.of(
                Map.of("role", "roles/viewer", "members", List.of("user:viewer@example.com"))))))
                .then().statusCode(200);
        policy = get(base);
        assertEquals("allServices", policy.getString("auditConfigs[0].service"), "an omitted auditConfigs is kept");
        assertEquals("roles/viewer", policy.getString("bindings[0].role"));

        set(base, Map.of("policy", Map.of(), "updateMask", "auditConfigs")).then().statusCode(200);
        policy = get(base);
        assertNull(policy.get("auditConfigs"));
        assertEquals("roles/viewer", policy.getString("bindings[0].role"), "bindings are outside the mask");
    }

    @Test
    void invalidAuditConfigsAndMaskPathsAreRefused() {
        String base = "/v1/projects/audit-cfg-" + UUID.randomUUID().toString().substring(0, 8);
        set(base, Map.of("policy", Map.of("bindings", OWNER, "auditConfigs", List.of(Map.of("service", "allServices",
                "auditLogConfigs", List.of(Map.of("logType", "ADMIN_WRITE"))))), "updateMask", "auditConfigs"))
                .then().statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));
        set(base, Map.of("policy", Map.of("bindings", OWNER), "updateMask", "bindings,owners"))
                .then().statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));
        assertEquals(List.of(), get(base).getList("bindings"));
    }

    @Test
    void grpcMixinAppliesTheUpdateMaskToProjectsAndOrganizations() throws Exception {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext().build();
        try {
            IAMPolicyGrpc.IAMPolicyBlockingStub iam = IAMPolicyGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(10, TimeUnit.SECONDS);
            AuditConfig config = AuditConfig.newBuilder().setService("storage.googleapis.com")
                    .addAuditLogConfigs(AuditLogConfig.newBuilder().setLogType(AuditLogConfig.LogType.DATA_READ))
                    .build();
            Policy policy = Policy.newBuilder().addBindings(Binding.newBuilder().setRole("roles/owner")
                    .addMembers("user:admin@example.com")).addAuditConfigs(config).build();
            for (String resource : List.of("projects/audit-cfg-" + UUID.randomUUID().toString().substring(0, 8),
                    "organizations/9" + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100000000L))) {
                iam.setIamPolicy(SetIamPolicyRequest.newBuilder().setResource(resource).setPolicy(policy)
                        .setUpdateMask(FieldMask.newBuilder().addPaths("bindings").addPaths("audit_configs"))
                        .build());
                Policy read = iam.getIamPolicy(GetIamPolicyRequest.newBuilder().setResource(resource).build());
                assertEquals(List.of(config), read.getAuditConfigsList(), resource);

                iam.setIamPolicy(SetIamPolicyRequest.newBuilder().setResource(resource)
                        .setPolicy(policy.toBuilder().clearAuditConfigs()).build());
                read = iam.getIamPolicy(GetIamPolicyRequest.newBuilder().setResource(resource).build());
                assertEquals(List.of(config), read.getAuditConfigsList(), resource);
            }
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static Response set(String base, Map<String, Object> body) {
        return given().urlEncodingEnabled(false).contentType("application/json").body(body)
                .when().post(base + ":setIamPolicy");
    }

    private static JsonPath get(String base) {
        return given().urlEncodingEnabled(false).contentType("application/json").body(Map.of())
                .when().post(base + ":getIamPolicy")
                .then().statusCode(200).extract().jsonPath();
    }
}
