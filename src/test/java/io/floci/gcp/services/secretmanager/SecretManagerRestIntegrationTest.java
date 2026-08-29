package io.floci.gcp.services.secretmanager;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class SecretManagerRestIntegrationTest {

    @Test
    void iamPolicyUsesGetForReadAndPostForWrites() {
        String project = "secret-iam-rest-it";
        String base = "/v1/projects/" + project + "/secrets/iam-target";

        given().contentType("application/json")
                .body("{\"replication\": {\"automatic\": {}}}")
                .queryParam("secretId", "iam-target")
                .when().post("/v1/projects/" + project + "/secrets")
                .then().statusCode(200);

        String etag = given().urlEncodingEnabled(false)
                .contentType("application/json")
                .body("""
                        {"policy":{"bindings":[{"role":"roles/secretmanager.secretAccessor",
                        "members":["user:reader@example.com"]}]}}
                        """)
                .when().post(base + ":setIamPolicy")
                .then().statusCode(200)
                .body("bindings[0].role", equalTo("roles/secretmanager.secretAccessor"))
                .extract().path("etag");

        given().urlEncodingEnabled(false)
                .when().get(base + ":getIamPolicy")
                .then().statusCode(200)
                .body("etag", equalTo(etag))
                .body("bindings[0].members[0]", equalTo("user:reader@example.com"));

        given().urlEncodingEnabled(false).contentType("application/json")
                .body("{\"permissions\":[\"secretmanager.secrets.get\"]}")
                .when().post(base + ":testIamPermissions")
                .then().statusCode(200)
                .body("permissions[0]", equalTo("secretmanager.secrets.get"));

        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().post(base + ":getIamPolicy")
                .then().statusCode(405);
        given().urlEncodingEnabled(false).when().get(base + ":setIamPolicy")
                .then().statusCode(405);
        given().urlEncodingEnabled(false).when().get(base + ":testIamPermissions")
                .then().statusCode(405);
    }

    @Test
    void iamPolicyDoesNotSurviveSecretDeletion() {
        String project = "secret-iam-delete-it";
        String base = "/v1/projects/" + project + "/secrets/iam-target";

        given().contentType("application/json")
                .body("{\"replication\": {\"automatic\": {}}}")
                .queryParam("secretId", "iam-target")
                .when().post("/v1/projects/" + project + "/secrets")
                .then().statusCode(200);
        given().urlEncodingEnabled(false).contentType("application/json")
                .body("{\"policy\":{\"bindings\":[{\"role\":\"roles/secretmanager.viewer\",\"members\":[\"user:reader@example.com\"]}]}}")
                .when().post(base + ":setIamPolicy").then().statusCode(200);

        given().when().delete(base).then().statusCode(200);
        given().urlEncodingEnabled(false).when().get(base + ":getIamPolicy")
                .then().statusCode(404).body("error.status", equalTo("NOT_FOUND"));

        given().contentType("application/json")
                .body("{\"replication\": {\"automatic\": {}}}")
                .queryParam("secretId", "iam-target")
                .when().post("/v1/projects/" + project + "/secrets")
                .then().statusCode(200);
        given().urlEncodingEnabled(false).when().get(base + ":getIamPolicy")
                .then().statusCode(200).body("bindings", empty());
    }
}
