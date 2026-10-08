package io.floci.gcp.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class IamCustomRoleIntegrationTest {

    private static final String ROLES = "/v1/projects/role-test/roles";
    private static final String CREATE = "{\"roleId\":\"%s\",\"role\":{\"title\":\"Bucket creator\",\"description\":\"d\","
            + "\"includedPermissions\":[\"storage.buckets.create\",\"storage.buckets.get\"],\"stage\":\"GA\"}}";

    @Test
    void createGetAndPatchRoundTripWithEtagChecks() {
        String etag = given().contentType("application/json").body(String.format(CREATE, "bucketCreator"))
                .when().post(ROLES)
                .then().statusCode(200)
                .body("name", equalTo("projects/role-test/roles/bucketCreator"))
                .body("title", equalTo("Bucket creator"))
                .body("stage", equalTo("GA"))
                .body("includedPermissions", hasItem("storage.buckets.create"))
                .body("deleted", nullValue())
                .body("etag", notNullValue())
                .extract().path("etag");

        given().when().get(ROLES + "/bucketCreator").then().statusCode(200).body("etag", equalTo(etag));

        String patched = given().contentType("application/json")
                .queryParam("updateMask", "title,includedPermissions")
                .body("{\"title\":\"Renamed\",\"includedPermissions\":[\"storage.buckets.list\"],\"etag\":\"" + etag + "\"}")
                .when().patch(ROLES + "/bucketCreator")
                .then().statusCode(200)
                .body("title", equalTo("Renamed"))
                .body("description", equalTo("d"))
                .body("includedPermissions", hasItem("storage.buckets.list"))
                .body("includedPermissions", not(hasItem("storage.buckets.create")))
                .extract().path("etag");

        given().contentType("application/json").queryParam("updateMask", "title")
                .body("{\"title\":\"Stale\",\"etag\":\"" + etag + "\"}")
                .when().patch(ROLES + "/bucketCreator")
                .then().statusCode(409);
        given().when().get(ROLES + "/bucketCreator").then().body("etag", equalTo(patched));
    }

    @Test
    void duplicateAndInvalidCreatesAreRejected() {
        given().contentType("application/json").body(String.format(CREATE, "dupRole")).post(ROLES).then().statusCode(200);
        given().contentType("application/json").body(String.format(CREATE, "dupRole")).post(ROLES).then().statusCode(409);
        given().contentType("application/json").body(String.format(CREATE, "x")).post(ROLES).then().statusCode(400);
        given().when().get(ROLES + "/missingRole").then().statusCode(404);
    }

    @Test
    void deleteIsSoftAndUndeleteRestoresTheRole() {
        given().contentType("application/json").body(String.format(CREATE, "softRole")).post(ROLES).then().statusCode(200);

        String deletedEtag = given().when().delete(ROLES + "/softRole")
                .then().statusCode(200).body("deleted", equalTo(true)).extract().path("etag");
        given().when().get(ROLES + "/softRole").then().statusCode(200).body("deleted", equalTo(true));
        given().when().get(ROLES).then().statusCode(200).body("roles.name", not(hasItem("projects/role-test/roles/softRole")));
        given().queryParam("showDeleted", true).when().get(ROLES)
                .then().statusCode(200).body("roles.name", hasItem("projects/role-test/roles/softRole"));

        given().urlEncodingEnabled(false).contentType("application/json").body("{\"etag\":\"" + deletedEtag + "\"}")
                .when().post(ROLES + "/softRole:undelete")
                .then().statusCode(200).body("deleted", nullValue()).body("name", equalTo("projects/role-test/roles/softRole"));
        given().when().get(ROLES).then().body("roles.name", hasItem("projects/role-test/roles/softRole"));
    }

    @Test
    void customRoleCanBeBoundInProjectPolicy() {
        given().contentType("application/json").body(String.format(CREATE, "policyRole")).post(ROLES).then().statusCode(200);
        given().urlEncodingEnabled(false).contentType("application/json")
                .body("{\"policy\":{\"bindings\":[{\"role\":\"projects/role-test/roles/policyRole\",\"members\":[\"serviceAccount:a@role-test.iam.gserviceaccount.com\"]}]}}")
                .when().post("/v1/projects/role-test:setIamPolicy")
                .then().statusCode(200)
                .body("bindings[0].role", equalTo("projects/role-test/roles/policyRole"));
    }

    @Test
    void serviceAccountRoutesKeepWorkingBesideRoles() {
        given().contentType("application/json").body("{\"accountId\":\"beside-roles\"}")
                .when().post("/v1/projects/role-test/serviceAccounts")
                .then().statusCode(200).body("email", equalTo("beside-roles@role-test.iam.gserviceaccount.com"));
    }
}
