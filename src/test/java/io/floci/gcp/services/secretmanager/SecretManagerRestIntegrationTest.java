package io.floci.gcp.services.secretmanager;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

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

    @ParameterizedTest
    @ValueSource(strings = {"+/8=", "+/8", "-_8=", "-_8"})
    void secretPayloadAcceptsBothBase64Alphabets(String data) {
        String base = createPayloadSecret();
        String version = given().urlEncodingEnabled(false).contentType("application/json")
                .body("{\"payload\":{\"data\":\"" + data + "\"}}")
                .when().post(base + ":addVersion")
                .then().statusCode(200)
                .extract().path("name");

        given().urlEncodingEnabled(false)
                .when().get("/v1/" + version + ":access")
                .then().statusCode(200)
                .body("payload.data", equalTo("+/8="));
    }

    @ParameterizedTest
    @ValueSource(strings = {"!", "A", "AA="})
    void invalidBase64DoesNotCreateSecretVersion(String data) {
        String base = createPayloadSecret();
        given().urlEncodingEnabled(false).contentType("application/json")
                .body("{\"payload\":{\"data\":\"" + data + "\"}}")
                .when().post(base + ":addVersion")
                .then().statusCode(400)
                .body("error.code", equalTo(400))
                .body("error.status", equalTo("INVALID_ARGUMENT"));

        given().when().get(base + "/versions")
                .then().statusCode(200)
                .body("versions", empty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"payload\":{\"data\":\"\"}}", "{\"payload\":{}}", "{}"})
    void emptyOrMissingPayloadDataRemainsEmpty(String body) {
        String base = createPayloadSecret();
        String version = given().urlEncodingEnabled(false).contentType("application/json")
                .body(body)
                .when().post(base + ":addVersion")
                .then().statusCode(200)
                .extract().path("name");

        given().urlEncodingEnabled(false)
                .when().get("/v1/" + version + ":access")
                .then().statusCode(200)
                .body("payload.data", equalTo(""));
    }

    private static String createPayloadSecret() {
        String project = "secret-payload-rest-it";
        String secretId = "base64-" + UUID.randomUUID();
        given().contentType("application/json")
                .body("{\"replication\":{\"automatic\":{}}}")
                .queryParam("secretId", secretId)
                .when().post("/v1/projects/" + project + "/secrets")
                .then().statusCode(200);
        return "/v1/projects/" + project + "/secrets/" + secretId;
    }
}
