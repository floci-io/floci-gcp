package io.floci.gcp.services.iam;

import com.google.iam.v1.Binding;
import com.google.iam.v1.IAMPolicyGrpc;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import io.floci.gcp.core.common.ProjectNumbers;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class IamWorkloadIdentityPoolRestIntegrationTest {

    private static final String PROJECT = "test-project";
    private static final String BASE = "/v1/projects/" + PROJECT + "/locations/global/workloadIdentityPools";

    @TestHTTPResource
    URI endpoint;

    @Test
    void poolLifecycleUsesProjectNumbersAndSoftDelete() {
        String poolId = "pool-" + suffix();
        String name = name(poolId);

        String operation = given().queryParam("workloadIdentityPoolId", poolId)
                .contentType("application/json")
                .body(Map.of("displayName", "verification pool", "description", "created by a test"))
                .when().post(BASE)
                .then().statusCode(200)
                .body("name", startsWith(name + "/operations/"))
                .body("done", equalTo(true))
                .body("response", equalTo(Map.of()))
                .extract().path("name");

        given().when().get("/v1/" + operation)
                .then().statusCode(200)
                .body("name", equalTo(operation))
                .body("done", equalTo(true))
                .body("response", equalTo(Map.of()));

        given().when().get(BASE + "/" + poolId)
                .then().statusCode(200)
                .body("name", equalTo(name))
                .body("state", equalTo("ACTIVE"))
                .body("displayName", equalTo("verification pool"))
                .body("description", equalTo("created by a test"))
                .body("expireTime", nullValue());

        given().queryParam("updateMask", "displayName,description")
                .contentType("application/json")
                .body(Map.of("displayName", "updated pool", "description", "updated"))
                .when().patch(BASE + "/" + poolId)
                .then().statusCode(200)
                .body("done", equalTo(true));

        given().when().get(BASE + "/" + poolId)
                .then().statusCode(200)
                .body("displayName", equalTo("updated pool"))
                .body("description", equalTo("updated"));

        given().queryParam("showDeleted", false).when().get(BASE)
                .then().statusCode(200)
                .body("workloadIdentityPools.name", hasItem(name));

        given().when().delete(BASE + "/" + poolId)
                .then().statusCode(200)
                .body("done", equalTo(true));

        given().when().get(BASE + "/" + poolId)
                .then().statusCode(200)
                .body("state", equalTo("DELETED"))
                .body("expireTime", notNullValue());

        given().queryParam("showDeleted", false).when().get(BASE)
                .then().statusCode(200)
                .body("workloadIdentityPools.name", not(hasItem(name)));

        given().queryParam("showDeleted", true).when().get(BASE)
                .then().statusCode(200)
                .body("workloadIdentityPools.name", hasItem(name))
                .body("workloadIdentityPools.find { it.name == '" + name + "' }.state",
                        equalTo("DELETED"));

        given().urlEncodingEnabled(false).contentType("application/json").body(Map.of())
                .when().post(BASE + "/" + poolId + ":undelete")
                .then().statusCode(200)
                .body("done", equalTo(true));

        given().when().get(BASE + "/" + poolId)
                .then().statusCode(200)
                .body("state", equalTo("ACTIVE"))
                .body("expireTime", nullValue());
    }

    @Test
    void providerLifecycleForOidcAndAws() {
        String poolId = "pool-" + suffix();
        String oidcId = "oidc-" + suffix();
        String awsId = "aws-" + suffix();
        String pool = name(poolId);
        given().queryParam("workloadIdentityPoolId", poolId).contentType("application/json")
                .body(Map.of()).when().post(BASE).then().statusCode(200);

        given().queryParam("workloadIdentityPoolProviderId", oidcId)
                .contentType("application/json")
                .body(Map.of(
                        "displayName", "oidc provider",
                        "attributeMapping", Map.of("google.subject", "assertion.sub"),
                        "oidc", Map.of(
                                "issuerUri", "https://example.com",
                                "allowedAudiences", List.of("floci-aud"))))
                .when().post(BASE + "/" + poolId + "/providers")
                .then().statusCode(200)
                .body("name", startsWith(pool + "/providers/" + oidcId + "/operations/"))
                .body("done", equalTo(true));

        given().when().get(BASE + "/" + poolId + "/providers/" + oidcId)
                .then().statusCode(200)
                .body("state", equalTo("ACTIVE"))
                .body("attributeMapping.'google.subject'", equalTo("assertion.sub"))
                .body("oidc.issuerUri", equalTo("https://example.com"))
                .body("oidc.allowedAudiences", contains("floci-aud"));

        given().queryParam("workloadIdentityPoolProviderId", awsId)
                .contentType("application/json")
                .body(Map.of("aws", Map.of("accountId", "123456789012")))
                .when().post(BASE + "/" + poolId + "/providers")
                .then().statusCode(200);

        given().when().get(BASE + "/" + poolId + "/providers/" + awsId)
                .then().statusCode(200)
                .body("aws.accountId", equalTo("123456789012"));

        given().queryParam("updateMask", "oidc.allowedAudiences,attributeCondition")
                .contentType("application/json")
                .body(Map.of(
                        "attributeCondition", "assertion.sub != ''",
                        "oidc", Map.of(
                                "issuerUri", "https://example.com",
                                "allowedAudiences", List.of("floci-aud", "other-aud"))))
                .when().patch(BASE + "/" + poolId + "/providers/" + oidcId)
                .then().statusCode(200);

        given().when().get(BASE + "/" + poolId + "/providers/" + oidcId)
                .then().statusCode(200)
                .body("oidc.allowedAudiences", contains("floci-aud", "other-aud"))
                .body("attributeCondition", equalTo("assertion.sub != ''"));

        given().queryParam("showDeleted", false).when().get(BASE + "/" + poolId + "/providers")
                .then().statusCode(200)
                .body("workloadIdentityPoolProviders.name", hasItem(pool + "/providers/" + awsId));

        given().when().delete(BASE + "/" + poolId + "/providers/" + awsId)
                .then().statusCode(200)
                .body("done", equalTo(true));

        given().when().get(BASE + "/" + poolId + "/providers/" + awsId)
                .then().statusCode(200)
                .body("state", equalTo("DELETED"))
                .body("expireTime", notNullValue());

        given().queryParam("showDeleted", false).when().get(BASE + "/" + poolId + "/providers")
                .then().statusCode(200)
                .body("workloadIdentityPoolProviders.name", not(hasItem(pool + "/providers/" + awsId)));

        given().queryParam("showDeleted", true).when().get(BASE + "/" + poolId + "/providers")
                .then().statusCode(200)
                .body("workloadIdentityPoolProviders.name", hasItem(pool + "/providers/" + awsId));

        given().urlEncodingEnabled(false).contentType("application/json").body(Map.of())
                .when().post(BASE + "/" + poolId + "/providers/" + awsId + ":undelete")
                .then().statusCode(200);

        given().when().get(BASE + "/" + poolId + "/providers/" + awsId)
                .then().statusCode(200)
                .body("state", equalTo("ACTIVE"))
                .body("expireTime", nullValue());

        given().urlEncodingEnabled(false).contentType("application/json").body(Map.of())
                .when().post(BASE + "/" + poolId + "/providers/" + awsId + ":undelete")
                .then().statusCode(400)
                .body("error.message", equalTo(IamWorkloadIdentityPoolService.PROVIDER_NOT_DELETED));

        given().when().delete(BASE + "/" + poolId + "/providers/" + awsId).then().statusCode(200);
        given().when().delete(BASE + "/" + poolId + "/providers/" + awsId)
                .then().statusCode(404)
                .body("error.message", equalTo(IamWorkloadIdentityPoolService.ENTITY_NOT_FOUND));

        // Providers are unreachable while their pool is soft-deleted, and return with it.
        given().when().delete(BASE + "/" + poolId).then().statusCode(200);
        given().when().get(BASE + "/" + poolId + "/providers/" + oidcId)
                .then().statusCode(404)
                .body("error.message", equalTo(IamWorkloadIdentityPoolService.ENTITY_NOT_FOUND));
        given().when().get(BASE + "/" + poolId + "/providers")
                .then().statusCode(404)
                .body("error.message", equalTo(IamWorkloadIdentityPoolService.ENTITY_NOT_FOUND));
        given().urlEncodingEnabled(false).contentType("application/json").body(Map.of())
                .when().post(BASE + "/" + poolId + ":undelete").then().statusCode(200);
        given().when().get(BASE + "/" + poolId + "/providers/" + oidcId)
                .then().statusCode(200)
                .body("state", equalTo("ACTIVE"));
    }

    @Test
    void providerConfigurationValidationMatchesTheApi() {
        String poolId = "pool-" + suffix();
        given().queryParam("workloadIdentityPoolId", poolId).contentType("application/json")
                .body(Map.of()).when().post(BASE).then().statusCode(200);

        given().queryParam("workloadIdentityPoolProviderId", "abc")
                .contentType("application/json")
                .body(Map.of("attributeMapping", Map.of("google.subject", "assertion.sub"),
                        "oidc", Map.of("issuerUri", "https://example.com")))
                .when().post(BASE + "/" + poolId + "/providers")
                .then().statusCode(400)
                .body("error.message", equalTo(IamWorkloadIdentityPoolService.PROVIDER_ID_RULE));

        given().queryParam("workloadIdentityPoolProviderId", "gcp-provider")
                .contentType("application/json")
                .body(Map.of("attributeMapping", Map.of("google.subject", "assertion.sub"),
                        "oidc", Map.of("issuerUri", "https://example.com")))
                .when().post(BASE + "/" + poolId + "/providers")
                .then().statusCode(400)
                .body("error.message", equalTo(IamWorkloadIdentityPoolService.PROVIDER_ID_RESERVED));

        given().queryParam("workloadIdentityPoolProviderId", "nomap-" + suffix())
                .contentType("application/json")
                .body(Map.of("oidc", Map.of("issuerUri", "https://example.com")))
                .when().post(BASE + "/" + poolId + "/providers")
                .then().statusCode(400)
                .body("error.message", equalTo(IamWorkloadIdentityPoolService.OIDC_MAPPINGS_REQUIRED));

        given().queryParam("workloadIdentityPoolProviderId", "nosub-" + suffix())
                .contentType("application/json")
                .body(Map.of("attributeMapping", Map.of("attribute.foo", "assertion.foo"),
                        "oidc", Map.of("issuerUri", "https://example.com")))
                .when().post(BASE + "/" + poolId + "/providers")
                .then().statusCode(400)
                .body("error.message", equalTo(IamWorkloadIdentityPoolService.SUBJECT_MAPPING_REQUIRED));

        given().queryParam("workloadIdentityPoolProviderId", "noiss-" + suffix())
                .contentType("application/json")
                .body(Map.of("attributeMapping", Map.of("google.subject", "assertion.sub"),
                        "oidc", Map.of()))
                .when().post(BASE + "/" + poolId + "/providers")
                .then().statusCode(400)
                .body("error.message", equalTo("Missing OIDC issuer URI."));

        given().queryParam("workloadIdentityPoolProviderId", "notype-" + suffix())
                .contentType("application/json")
                .body(Map.of())
                .when().post(BASE + "/" + poolId + "/providers")
                .then().statusCode(400)
                .body("error.message", equalTo(
                        "Invalid WorkloadIdentityPoolProvider IDP configuration: PROVIDERCONFIG_NOT_SET."));

        given().queryParam("workloadIdentityPoolProviderId", "twotype-" + suffix())
                .contentType("application/json")
                .body(Map.of("attributeMapping", Map.of("google.subject", "assertion.sub"),
                        "oidc", Map.of("issuerUri", "https://example.com"),
                        "aws", Map.of("accountId", "123456789012")))
                .when().post(BASE + "/" + poolId + "/providers")
                .then().statusCode(400)
                .body("error.message", equalTo("Invalid value at 'workload_identity_pool_provider' (oneof), "
                        + "oneof field 'provider_config' is already set. Cannot set 'aws'"));

        given().queryParam("workloadIdentityPoolProviderId", "awsbad-" + suffix())
                .contentType("application/json")
                .body(Map.of("aws", Map.of("accountId", "1234")))
                .when().post(BASE + "/" + poolId + "/providers")
                .then().statusCode(400)
                .body("error.message", equalTo(IamWorkloadIdentityPoolService.AWS_ACCOUNT_INVALID));
    }

    @Test
    void invalidPoolRequestsFailLikeTheApi() {
        String poolId = "pool-" + suffix();
        given().queryParam("workloadIdentityPoolId", "abc").contentType("application/json").body(Map.of())
                .when().post(BASE)
                .then().statusCode(400)
                .body("error.message", equalTo(IamWorkloadIdentityPoolService.POOL_ID_RULE));

        given().queryParam("workloadIdentityPoolId", "gcp-pool").contentType("application/json").body(Map.of())
                .when().post(BASE)
                .then().statusCode(400)
                .body("error.message", equalTo(IamWorkloadIdentityPoolService.POOL_ID_RESERVED));

        given().queryParam("workloadIdentityPoolId", poolId).contentType("application/json").body(Map.of())
                .when().post(BASE).then().statusCode(200);
        given().queryParam("workloadIdentityPoolId", poolId).contentType("application/json").body(Map.of())
                .when().post(BASE)
                .then().statusCode(409)
                .body("error.message", equalTo(IamWorkloadIdentityPoolService.ENTITY_ALREADY_EXISTS));

        given().contentType("application/json").body(Map.of("displayName", "no mask"))
                .when().patch(BASE + "/" + poolId)
                .then().statusCode(400)
                .body("error.message", equalTo(IamWorkloadIdentityPoolService.UPDATE_MASK_REQUIRED));

        given().when().get("/v1/projects/" + PROJECT + "/locations/us-central1/workloadIdentityPools/" + poolId)
                .then().statusCode(400)
                .body("error.message", equalTo(IamWorkloadIdentityPoolService.PARENT_INVALID));

        String missing = "missing-" + suffix();
        given().when().get(BASE + "/" + missing)
                .then().statusCode(404)
                .body("error.message", equalTo(IamWorkloadIdentityPoolService.ENTITY_NOT_FOUND));

        given().urlEncodingEnabled(false).contentType("application/json")
                .body(Map.of("policy", Map.of("bindings", List.of())))
                .when().post(BASE + "/" + missing + ":setIamPolicy")
                .then().statusCode(404)
                .body("error.message", equalTo(IamWorkloadIdentityPoolService.ENTITY_NOT_FOUND));

        given().queryParam("updateMask", "mode").contentType("application/json")
                .body(Map.of("mode", "FEDERATION_ONLY"))
                .when().patch(BASE + "/" + poolId)
                .then().statusCode(400)
                .body("error.message", equalTo(IamWorkloadIdentityPoolService.IMMUTABLE_FIELD));

        given().when().delete(BASE + "/" + poolId).then().statusCode(200);
        given().contentType("application/json").body(Map.of("displayName", "deleted"))
                .queryParam("updateMask", "displayName")
                .when().patch(BASE + "/" + poolId)
                .then().statusCode(404)
                .body("error.message", equalTo(IamWorkloadIdentityPoolService.ENTITY_NOT_FOUND));
        given().urlEncodingEnabled(false).contentType("application/json").body(Map.of())
                .when().post(BASE + "/" + poolId + ":getIamPolicy")
                .then().statusCode(404)
                .body("error.message", equalTo(IamWorkloadIdentityPoolService.ENTITY_NOT_FOUND));
        given().when().delete(BASE + "/" + poolId)
                .then().statusCode(404)
                .body("error.message", equalTo(IamWorkloadIdentityPoolService.ENTITY_NOT_FOUND));
        given().urlEncodingEnabled(false).contentType("application/json").body(Map.of())
                .when().post(BASE + "/" + poolId + ":undelete").then().statusCode(200);
        given().urlEncodingEnabled(false).contentType("application/json").body(Map.of())
                .when().post(BASE + "/" + poolId + ":undelete")
                .then().statusCode(400)
                .body("error.message", equalTo(IamWorkloadIdentityPoolService.POOL_NOT_DELETED));
    }

    @Test
    void sharedGrpcPolicyUsesTheSameKeyAsRest() throws Exception {
        String poolId = "pool-" + suffix();
        String name = name(poolId);
        given().queryParam("workloadIdentityPoolId", poolId).contentType("application/json").body(Map.of())
                .when().post(BASE).then().statusCode(200);

        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext()
                .build();
        try {
            IAMPolicyGrpc.IAMPolicyBlockingStub policy = IAMPolicyGrpc.newBlockingStub(channel);
            policy.setIamPolicy(SetIamPolicyRequest.newBuilder()
                    .setResource(name)
                    .setPolicy(Policy.newBuilder().addBindings(Binding.newBuilder()
                            .setRole("roles/iam.workloadIdentityPoolViewer")
                            .addMembers("serviceAccount:reader@test-project.iam.gserviceaccount.com")))
                    .build());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }

        given().urlEncodingEnabled(false).contentType("application/json").body(Map.of())
                .when().post(BASE + "/" + poolId + ":getIamPolicy")
                .then().statusCode(200)
                .body("bindings[0].role", equalTo("roles/iam.workloadIdentityPoolViewer"));
    }

    @Test
    void projectNumberAddressingAndPoolPolicyRoundTrip() {
        String poolId = "pool-" + suffix();
        String name = name(poolId);
        given().queryParam("workloadIdentityPoolId", poolId).contentType("application/json").body(Map.of())
                .when().post(BASE).then().statusCode(200);

        String numberPath = "/v1/projects/" + ProjectNumbers.of(PROJECT)
                + "/locations/global/workloadIdentityPools/" + poolId;
        given().when().get(numberPath)
                .then().statusCode(200)
                .body("name", equalTo(name));

        given().urlEncodingEnabled(false).contentType("application/json").body(Map.of())
                .when().post(numberPath + ":getIamPolicy")
                .then().statusCode(200)
                .body("etag", equalTo("ACAB"));

        Map<String, Object> binding = Map.of(
                "role", "roles/iam.workloadIdentityPoolViewer",
                "members", List.of("serviceAccount:reader@test-project.iam.gserviceaccount.com"));
        given().urlEncodingEnabled(false).contentType("application/json")
                .body(Map.of("policy", Map.of("version", 1, "bindings", List.of(binding))))
                .when().post(numberPath + ":setIamPolicy")
                .then().statusCode(200)
                .body("bindings[0].role", equalTo("roles/iam.workloadIdentityPoolViewer"));

        given().when().get(numberPath)
                .then().statusCode(200);

        given().urlEncodingEnabled(false).contentType("application/json")
                .body(Map.of("permissions", List.of("iam.googleapis.com/workloadIdentityPools.get")))
                .when().post(numberPath + ":testIamPermissions")
                .then().statusCode(200)
                .body("permissions", contains("iam.googleapis.com/workloadIdentityPools.get"));
    }

    private static String name(String poolId) {
        return "projects/" + ProjectNumbers.of(PROJECT) + "/locations/global/workloadIdentityPools/" + poolId;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
