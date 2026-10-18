package io.floci.gcp.services.resourcemanager;

import io.floci.gcp.core.common.ProjectNumbers;
import io.floci.gcp.core.common.ProjectRegistry;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class ResourceManagerRestIntegrationTest {

    @Test
    void getProjectReportsActiveWithStableProjectNumber() {
        String projectNumber = given()
                .when().get("/v1/projects/crm-test")
                .then()
                .statusCode(200)
                .body("projectId", equalTo("crm-test"))
                .body("name", equalTo("crm-test"))
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("projectNumber", equalTo(ProjectNumbers.of("crm-test")))
                .body("createTime", equalTo(ProjectRegistry.implicitCreateTime("crm-test")))
                .extract().path("projectNumber");

        given()
                .when().get("/v1/projects/crm-test")
                .then()
                .statusCode(200)
                .body("projectNumber", equalTo(projectNumber));
    }

    @Test
    void projectIamPolicyUsesPostForEveryMixinMethod() {
        String base = "/v1/projects/crm-iam-test";

        String etag = given().urlEncodingEnabled(false).contentType("application/json")
                .body("{\"policy\":{\"bindings\":[{\"role\":\"roles/pubsub.publisher\",\"members\":[\"user:publisher@example.com\"]}]}}")
                .when().post(base + ":setIamPolicy")
                .then().statusCode(200)
                .body("bindings[0].role", equalTo("roles/pubsub.publisher"))
                .extract().path("etag");

        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().post(base + ":getIamPolicy")
                .then().statusCode(200)
                .body("etag", equalTo(etag))
                .body("bindings[0].members[0]", equalTo("user:publisher@example.com"));

        given().urlEncodingEnabled(false).contentType("application/json")
                .body("{\"permissions\":[\"resourcemanager.projects.get\",\"resourcemanager.projects.delete\"]}")
                .when().post(base + ":testIamPermissions")
                .then().statusCode(200)
                .body("permissions[0]", equalTo("resourcemanager.projects.get"))
                .body("permissions[1]", equalTo("resourcemanager.projects.delete"));

        given().urlEncodingEnabled(false).when().get(base + ":getIamPolicy").then().statusCode(405);
        given().urlEncodingEnabled(false).when().get(base + ":setIamPolicy").then().statusCode(405);
        given().urlEncodingEnabled(false).when().get(base + ":testIamPermissions").then().statusCode(405);
    }

    @Test
    void staleProjectIamWriteLeavesCurrentPolicyIntact() {
        String base = "/v1/projects/crm-iam-etag-test";

        given().urlEncodingEnabled(false).contentType("application/json")
                .body("{\"policy\":{\"bindings\":[{\"role\":\"roles/viewer\",\"members\":[\"user:reader@example.com\"]}]}}")
                .when().post(base + ":setIamPolicy").then().statusCode(200);

        given().urlEncodingEnabled(false).contentType("application/json")
                .body("{\"policy\":{\"etag\":\"stale\",\"bindings\":[]}}")
                .when().post(base + ":setIamPolicy")
                .then().statusCode(409).body("error.status", equalTo("ABORTED"));

        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().post(base + ":getIamPolicy")
                .then().statusCode(200)
                .body("bindings[0].role", equalTo("roles/viewer"));
    }

    @Test
    void createReturnsDoneOperationWithTypedProjectResponse() {
        String id = uniqueId("crm-create");

        String opName = given().contentType("application/json")
                .body("{\"projectId\":\"" + id + "\",\"name\":\"My Project\",\"labels\":{\"env\":\"dev\"},"
                        + "\"parent\":{\"type\":\"folder\",\"id\":\"123456\"}}")
                .when().post("/v1/projects")
                .then().statusCode(200)
                .body("name", equalTo("operations/cp." + ProjectNumbers.of(id)))
                .body("done", equalTo(true))
                .body("metadata.'@type'", equalTo("type.googleapis.com/google.cloudresourcemanager.v1.ProjectCreationStatus"))
                .body("metadata.gettable", equalTo(true))
                .body("metadata.ready", equalTo(true))
                .body("response.'@type'", equalTo("type.googleapis.com/google.cloudresourcemanager.v1.Project"))
                .body("response.projectId", equalTo(id))
                .body("response.name", equalTo("My Project"))
                .body("response.lifecycleState", equalTo("ACTIVE"))
                .body("response.labels.env", equalTo("dev"))
                .body("response.parent.type", equalTo("folder"))
                .body("response.parent.id", equalTo("123456"))
                .extract().path("name");

        given().urlEncodingEnabled(false)
                .when().get("/v1/" + opName)
                .then().statusCode(200)
                .body("name", equalTo(opName))
                .body("done", equalTo(true))
                .body("response.projectId", equalTo(id));

        given().when().get("/v1/projects/" + ProjectNumbers.of(id))
                .then().statusCode(200)
                .body("projectId", equalTo(id))
                .body("name", equalTo("My Project"));

        given().contentType("application/json")
                .body("{\"projectId\":\"" + id + "\",\"name\":\"Other Project\"}")
                .when().post("/v1/projects")
                .then().statusCode(409)
                .body("error.status", equalTo("ALREADY_EXISTS"));
    }

    @Test
    void createValidatesProjectIdNameAndParent() {
        given().contentType("application/json").body("{\"projectId\":\"Bad_Id\",\"name\":\"Valid Name\"}")
                .when().post("/v1/projects")
                .then().statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));
        given().contentType("application/json").body("{\"projectId\":\"trailing-hyphen-\"}")
                .when().post("/v1/projects")
                .then().statusCode(400);
        given().contentType("application/json").body("{\"projectId\":\"" + uniqueId("crm-name") + "\",\"name\":\"no\"}")
                .when().post("/v1/projects")
                .then().statusCode(400);
        given().contentType("application/json")
                .body("{\"projectId\":\"" + uniqueId("crm-parent") + "\",\"parent\":{\"type\":\"project\",\"id\":\"1\"}}")
                .when().post("/v1/projects")
                .then().statusCode(400);
    }

    @Test
    void createAdoptsProjectAlreadyInUse() {
        String id = uniqueId("crm-adopt");
        given().when().get("/v1/projects/" + id).then().statusCode(200);

        given().contentType("application/json").body("{\"projectId\":\"" + id + "\",\"name\":\"Adopted\"}")
                .when().post("/v1/projects")
                .then().statusCode(200)
                .body("response.name", equalTo("Adopted"))
                .body("response.projectNumber", equalTo(ProjectNumbers.of(id)));
    }

    @Test
    void updateDeleteAndUndeleteFollowLifecycle() {
        String id = uniqueId("crm-life");
        create(id, "Lifecycle");

        given().contentType("application/json")
                .body("{\"projectId\":\"" + id + "\",\"name\":\"Renamed\",\"labels\":{\"team\":\"core\"},"
                        + "\"parent\":{\"type\":\"organization\",\"id\":\"42\"}}")
                .when().put("/v1/projects/" + id)
                .then().statusCode(200)
                .body("name", equalTo("Renamed"))
                .body("labels.team", equalTo("core"))
                .body("parent.type", equalTo("organization"));

        given().when().delete("/v1/projects/" + id)
                .then().statusCode(200);

        given().when().get("/v1/projects/" + id)
                .then().statusCode(200)
                .body("lifecycleState", equalTo("DELETE_REQUESTED"));

        given().contentType("application/json").body("{\"name\":\"Nope Nope\"}")
                .when().put("/v1/projects/" + id)
                .then().statusCode(400)
                .body("error.status", equalTo("FAILED_PRECONDITION"));

        given().queryParam("filter", "id:" + id + " lifecycleState:DELETE_REQUESTED")
                .when().get("/v1/projects")
                .then().statusCode(200)
                .body("projects.projectId", hasItem(id));

        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().post("/v1/projects/" + id + ":undelete")
                .then().statusCode(200);

        given().when().get("/v1/projects/" + id)
                .then().statusCode(200)
                .body("lifecycleState", equalTo("ACTIVE"))
                .body("name", equalTo("Renamed"));

        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().post("/v1/projects/" + id + ":undelete")
                .then().statusCode(400)
                .body("error.status", equalTo("FAILED_PRECONDITION"));
    }

    @Test
    void rejectedUpdateOrAdoptionChangesNothing() {
        String id = uniqueId("crm-reject");
        create(id, "Original");

        given().contentType("application/json")
                .body("{\"name\":\"Changed Name\",\"labels\":{\"Bad Key\":\"x\"}}")
                .when().put("/v1/projects/" + id)
                .then().statusCode(400);
        given().contentType("application/json")
                .body("{\"name\":\"Changed Name\",\"parent\":{\"type\":\"planet\",\"id\":\"1\"}}")
                .when().put("/v1/projects/" + id)
                .then().statusCode(400);
        given().when().get("/v1/projects/" + id)
                .then().statusCode(200)
                .body("name", equalTo("Original"))
                .body("labels", nullValue());

        String implicit = uniqueId("crm-reject-adopt");
        given().when().get("/v1/projects/" + implicit).then().statusCode(200);
        given().contentType("application/json")
                .body("{\"projectId\":\"" + implicit + "\",\"name\":\"Adopted\",\"parent\":{\"type\":\"planet\",\"id\":\"1\"}}")
                .when().post("/v1/projects")
                .then().statusCode(400);
        given().when().get("/v1/projects/" + implicit)
                .then().statusCode(200)
                .body("name", equalTo(implicit));
        given().contentType("application/json").body("{\"projectId\":\"" + implicit + "\",\"name\":\"Adopted\"}")
                .when().post("/v1/projects")
                .then().statusCode(200)
                .body("response.name", equalTo("Adopted"));
    }

    @Test
    void quotedFilterValuesMayContainSpaces() {
        String tag = UUID.randomUUID().toString().substring(0, 6);
        String id = "crm-quoted-" + tag;
        create(id, "My Project " + tag);

        given().queryParam("filter", "name:\"My Project " + tag + "\"")
                .when().get("/v1/projects")
                .then().statusCode(200)
                .body("projects.projectId", contains(id));
        given().queryParam("filter", "name:'my project " + tag + "'")
                .when().get("/v1/projects")
                .then().statusCode(200)
                .body("projects.projectId", contains(id));
        given().queryParam("filter", "name:\"My Project")
                .when().get("/v1/projects")
                .then().statusCode(400);
    }

    @Test
    void getAncestryListsProjectThenParent() {
        String id = uniqueId("crm-anc");
        given().contentType("application/json")
                .body("{\"projectId\":\"" + id + "\",\"parent\":{\"type\":\"folder\",\"id\":\"777\"}}")
                .when().post("/v1/projects").then().statusCode(200);

        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().post("/v1/projects/" + id + ":getAncestry")
                .then().statusCode(200)
                .body("ancestor", hasSize(2))
                .body("ancestor[0].resourceId.type", equalTo("project"))
                .body("ancestor[0].resourceId.id", equalTo(id))
                .body("ancestor[1].resourceId.type", equalTo("folder"))
                .body("ancestor[1].resourceId.id", equalTo("777"));
    }

    @Test
    void listFiltersAreOredCaseInsensitiveWithWildcards() {
        String tag = UUID.randomUUID().toString().substring(0, 6);
        String red = "crm-red-" + tag;
        String blue = "crm-blue-" + tag;
        given().contentType("application/json")
                .body("{\"projectId\":\"" + red + "\",\"name\":\"Howl " + tag + "\",\"labels\":{\"color\":\"red\",\"tag\":\"t" + tag + "\"}}")
                .when().post("/v1/projects").then().statusCode(200);
        given().contentType("application/json")
                .body("{\"projectId\":\"" + blue + "\",\"name\":\"Other " + tag + "\",\"labels\":{\"color\":\"blue\",\"tag\":\"t" + tag + "\"}}")
                .when().post("/v1/projects").then().statusCode(200);

        given().queryParam("filter", "NAME:howl*" + tag)
                .when().get("/v1/projects")
                .then().statusCode(200)
                .body("projects.projectId", contains(red));

        given().queryParam("filter", "labels.tag:T" + tag + " name:nomatch")
                .when().get("/v1/projects")
                .then().statusCode(200)
                .body("projects.projectId", containsInAnyOrder(red, blue));

        given().queryParam("filter", "id:crm-blue-" + tag + " id:crm-red-*" + tag)
                .when().get("/v1/projects")
                .then().statusCode(200)
                .body("projects.projectId", containsInAnyOrder(red, blue));

        given().queryParam("filter", "id:crm-*-" + tag).queryParam("pageSize", 1)
                .when().get("/v1/projects")
                .then().statusCode(200)
                .body("projects", hasSize(1))
                .body("nextPageToken", notNullValue());

        given().queryParam("filter", "bogus:1")
                .when().get("/v1/projects")
                .then().statusCode(400);
    }

    @Test
    void unknownProjectNumberIsPermissionDenied() {
        given().when().get("/v1/projects/999999999999")
                .then().statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", equalTo("The caller does not have permission"));
    }

    @Test
    void defaultProjectResolvesByNumber() {
        given().when().get("/v1/projects/" + ProjectNumbers.of("test-project"))
                .then().statusCode(200)
                .body("projectId", equalTo("test-project"));
    }

    @Test
    void projectsSeenOnOtherRestApisAreListed() {
        String id = uniqueId("crm-seen");
        given().when().get("/v1/projects/" + id + "/topics").then().statusCode(200);

        given().queryParam("filter", "id:" + id)
                .when().get("/v1/projects")
                .then().statusCode(200)
                .body("projects.projectId", contains(id))
                .body("projects[0].name", equalTo(id));
    }

    @Test
    void anXmlObjectNameThatLooksLikeAProjectRouteRegistersNoProject() {
        String bucket = uniqueId("crm-xml");
        String phantom = uniqueId("crm-phantom");
        given().contentType("application/json").queryParam("project", "test-project")
                .body("{\"name\":\"" + bucket + "\"}")
                .when().post("/storage/v1/b")
                .then().statusCode(200);

        given().urlEncodingEnabled(false).body("x")
                .when().put("/" + bucket + "/backups/v2/projects/" + phantom + "/data.json")
                .then().statusCode(200);

        given().queryParam("filter", "id:" + phantom)
                .when().get("/v1/projects")
                .then().statusCode(200)
                .body("projects.projectId", not(hasItem(phantom)));
    }

    @Test
    void sharedRoutesKeepWorking() {
        given().when().get("/v1/operations/cp.1")
                .then().statusCode(404);
        given().when().get("/v1/operations/does-not-exist")
                .then().statusCode(404);
        given().when().get("/v1/projects/crm-routes-test/serviceAccounts")
                .then().statusCode(200);
        given().when().get("/v1/projects/crm-routes-test/services/pubsub.googleapis.com")
                .then().statusCode(200)
                .body("state", not(nullValue()));
    }

    private static void create(String id, String name) {
        given().contentType("application/json")
                .body("{\"projectId\":\"" + id + "\",\"name\":\"" + name + "\"}")
                .when().post("/v1/projects")
                .then().statusCode(200);
    }

    private static String uniqueId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
