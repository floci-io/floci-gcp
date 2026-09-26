package io.floci.gcp.services.cloudrun;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class CloudRunInstancesRestIntegrationTest {

    private static final String LOCATION = "asia-northeast1";
    private static final String BUSYBOX_BODY = """
            {"containers":[{"image":"docker.io/library/busybox","command":["sh","-c"],
             "args":["httpd -f -p 8080"]}],
             "serviceAccount":"runtime@p.iam.gserviceaccount.com"}
            """;

    @Test
    void createCompletesGcpDefaultsAndRoundTrips() {
        String project = "inst-it-crud";
        String name = instanceName(project, "floci-inst");

        given()
                .contentType("application/json")
                .queryParam("instanceId", "floci-inst")
                .body(BUSYBOX_BODY)
                .when().post(parentPath(project) + "/instances")
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("metadata.'@type'", equalTo("type.googleapis.com/google.cloud.run.v2.Instance"))
                .body("response.'@type'", equalTo("type.googleapis.com/google.cloud.run.v2.Instance"))
                .body("response.name", equalTo(name))
                .body("response.generation", equalTo("1"))
                .body("response.observedGeneration", equalTo("1"))
                .body("response.launchStage", equalTo("GA"))
                .body("response.ingress", equalTo("INGRESS_TRAFFIC_ALL"))
                .body("response.serviceAccount", equalTo("runtime@p.iam.gserviceaccount.com"))
                .body("response.containers[0].image", equalTo("docker.io/library/busybox"))
                .body("response.containers[0].name", nullValue())
                .body("response.containers[0].resources.limits.cpu", equalTo("2000m"))
                .body("response.containers[0].resources.limits.memory", equalTo("2048Mi"))
                .body("response.containers[0].ports[0].name", equalTo("http1"))
                .body("response.containers[0].ports[0].containerPort", equalTo(8080))
                .body("response.terminalCondition.type", equalTo("Running"))
                .body("response.terminalCondition.state", equalTo("CONDITION_SUCCEEDED"))
                .body("response.terminalCondition.message", matchesPattern("Started instance in [0-9.]+s\\."))
                .body("response.conditions.type", contains("ContainerReady", "ResourcesAvailable"))
                .body("response.conditions[1].message", equalTo("Provisioned imported containers."))
                .body("response.containerStatuses", hasSize(1))
                .body("response.urls[0]", matchesPattern(
                        "http://floci-inst-[0-9a-f]{12}\\.asia-northeast1\\.run\\.localhost\\.floci\\.io:4588"))
                .body("response.reconciling", nullValue())
                .body("response.restartPolicy", nullValue())
                .body("response.sshEnabled", nullValue())
                .body("response.etag", notNullValue())
                .body("response.uid", notNullValue());

        given()
                .when().get("/v2/" + name)
                .then()
                .statusCode(200)
                .body("name", equalTo(name))
                .body("containers[0].ports[0].containerPort", equalTo(8080));

        given()
                .when().get(parentPath(project) + "/instances")
                .then()
                .statusCode(200)
                .body("instances.name", contains(name))
                .body("nextPageToken", nullValue());

        given()
                .contentType("application/json")
                .body("{\"containers\":[{\"image\":\"nginx\",\"ports\":[{\"name\":\"h2c\",\"containerPort\":80}],"
                        + "\"resources\":{\"limits\":{\"cpu\":\"1\"}}}],\"ingress\":\"INGRESS_TRAFFIC_INTERNAL_ONLY\"}")
                .queryParam("instanceId", "given-values")
                .when().post(parentPath(project) + "/instances")
                .then()
                .statusCode(200)
                .body("response.containers[0].ports[0].name", equalTo("h2c"))
                .body("response.containers[0].ports[0].containerPort", equalTo(80))
                .body("response.containers[0].resources.limits.cpu", equalTo("1"))
                .body("response.containers[0].resources.limits.memory", equalTo("2048Mi"))
                .body("response.ingress", equalTo("INGRESS_TRAFFIC_INTERNAL_ONLY"));

        given()
                .when().delete("/v2/" + name)
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.name", equalTo(name))
                .body("response.generation", equalTo("2"))
                .body("response.observedGeneration", equalTo("2"))
                .body("response.terminalCondition.type", equalTo("Running"))
                .body("response.terminalCondition.state", equalTo("CONDITION_FAILED"))
                .body("response.terminalCondition.message", equalTo("Instance completed for deletion."))
                .body("response.deleteTime", notNullValue())
                .body("response.expireTime", notNullValue());

        given()
                .when().get("/v2/" + name)
                .then()
                .statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"))
                .body("error.message", equalTo("Resource 'floci-inst' of kind 'INSTANCE' in region "
                        + "'asia-northeast1' in project 'inst-it-crud' does not exist."));

        given()
                .when().delete("/v2/" + name)
                .then()
                .statusCode(404);
    }

    @Test
    void instanceIdIsOptionalAndAcceptedInSnakeCase() {
        String project = "inst-it-ids";

        String generated = given()
                .contentType("application/json")
                .body(BUSYBOX_BODY)
                .when().post(parentPath(project) + "/instances")
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .extract().path("response.name");
        String generatedId = generated.substring(generated.lastIndexOf('/') + 1);
        assertTrue(generatedId.matches("[a-z][a-z0-9]{14}"), generatedId);

        given()
                .contentType("application/json")
                .queryParam("instance_id", "floci-inst2")
                .body(BUSYBOX_BODY)
                .when().post(parentPath(project) + "/instances")
                .then()
                .statusCode(200)
                .body("response.name", equalTo(instanceName(project, "floci-inst2")));

        given()
                .when().get("/v2/" + instanceName(project, "floci-inst2"))
                .then()
                .statusCode(200);
    }

    @Test
    void validateOnlyPersistsNothing() {
        String project = "inst-it-validate";
        String name = instanceName(project, "floci-validate");

        given()
                .contentType("application/json")
                .queryParam("instanceId", "floci-validate")
                .queryParam("validateOnly", true)
                .body(BUSYBOX_BODY)
                .when().post(parentPath(project) + "/instances")
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.name", equalTo(name));

        given().when().get("/v2/" + name).then().statusCode(404);

        createInstance(project, "floci-validate");

        given()
                .contentType("application/json")
                .queryParam("updateMask", "labels")
                .queryParam("validateOnly", true)
                .body("{\"labels\":{\"env\":\"test\"}}")
                .when().patch("/v2/" + name)
                .then()
                .statusCode(200)
                .body("response.labels.env", equalTo("test"));

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{\"validateOnly\":true}")
                .when().post("/v2/" + name + ":stop")
                .then()
                .statusCode(200)
                .body("response.terminalCondition.message", equalTo("Instance stopped."));

        given()
                .queryParam("validateOnly", true)
                .when().delete("/v2/" + name)
                .then()
                .statusCode(200)
                .body("done", equalTo(true));

        given()
                .when().get("/v2/" + name)
                .then()
                .statusCode(200)
                .body("generation", equalTo("1"))
                .body("labels", nullValue())
                .body("terminalCondition.state", equalTo("CONDITION_SUCCEEDED"));
    }

    @Test
    void patchAppliesUpdateMaskAndAllowMissingUpserts() {
        String project = "inst-it-patch";
        String name = instanceName(project, "floci-inst");
        createInstance(project, "floci-inst");

        given()
                .contentType("application/json")
                .queryParam("updateMask", "labels")
                .body("{\"labels\":{\"env\":\"test\"},\"description\":\"ignored\"}")
                .when().patch("/v2/" + name)
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("metadata.'@type'", equalTo("type.googleapis.com/google.cloud.run.v2.Instance"))
                .body("response.labels.env", equalTo("test"))
                .body("response.description", nullValue())
                .body("response.generation", equalTo("2"))
                .body("response.observedGeneration", equalTo("2"))
                .body("response.containers[0].image", equalTo("docker.io/library/busybox"));

        given()
                .contentType("application/json")
                .body("{\"containers\":[{\"image\":\"nginx\"}]}")
                .when().patch("/v2/" + name)
                .then()
                .statusCode(200)
                .body("response.generation", equalTo("3"))
                .body("response.labels.env", equalTo("test"))
                .body("response.containers[0].image", equalTo("nginx"))
                .body("response.containers[0].resources.limits.cpu", equalTo("2000m"))
                .body("response.containers[0].ports[0].containerPort", equalTo(8080));

        given()
                .contentType("application/json")
                .queryParam("updateMask", "notAField")
                .body("{}")
                .when().patch("/v2/" + name)
                .then()
                .statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));

        given()
                .contentType("application/json")
                .body(BUSYBOX_BODY)
                .when().patch("/v2/" + instanceName(project, "missing"))
                .then()
                .statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"));

        given()
                .contentType("application/json")
                .queryParam("allowMissing", true)
                .body(BUSYBOX_BODY)
                .when().patch("/v2/" + instanceName(project, "upserted"))
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.name", equalTo(instanceName(project, "upserted")))
                .body("response.generation", equalTo("1"))
                .body("response.terminalCondition.state", equalTo("CONDITION_SUCCEEDED"));
    }

    @Test
    void nestedUpdateMaskPathKeepsSiblingFieldsAndRejectsUnknownPaths() {
        String project = "inst-it-nested-mask";
        String name = instanceName(project, "floci-inst");
        given()
                .contentType("application/json")
                .queryParam("instanceId", "floci-inst")
                .body("""
                        {"containers":[{"image":"docker.io/library/busybox"}],
                         "vpcAccess":{"connector":"connector-a","egress":"ALL_TRAFFIC"}}
                        """)
                .when().post(parentPath(project) + "/instances")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .queryParam("updateMask", "vpcAccess.connector")
                .body("{\"vpcAccess\":{\"connector\":\"connector-b\"}}")
                .when().patch("/v2/" + name)
                .then()
                .statusCode(200)
                .body("response.vpcAccess.connector", equalTo("connector-b"))
                .body("response.vpcAccess.egress", equalTo("ALL_TRAFFIC"))
                .body("response.generation", equalTo("2"));

        given()
                .contentType("application/json")
                .queryParam("updateMask", "vpcAccess.bogus")
                .body("{\"vpcAccess\":{\"connector\":\"connector-c\"}}")
                .when().patch("/v2/" + name)
                .then()
                .statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"))
                .body("error.message", equalTo("Invalid update mask path: vpcAccess.bogus"));

        given()
                .when().get("/v2/" + name)
                .then()
                .statusCode(200)
                .body("vpcAccess.connector", equalTo("connector-b"))
                .body("vpcAccess.egress", equalTo("ALL_TRAFFIC"));
    }

    @Test
    void serviceCreateRefusesTheIdOfAnExistingInstance() {
        String project = "inst-it-svc-collision";
        createInstance(project, "taken-name");

        given()
                .contentType("application/json")
                .queryParam("serviceId", "taken-name")
                .body("{\"template\":{\"containers\":[{\"image\":\"nginx\"}]}}")
                .when().post(parentPath(project) + "/services")
                .then()
                .statusCode(409)
                .body("error.code", equalTo(409))
                .body("error.status", equalTo("ALREADY_EXISTS"))
                .body("error.message", equalTo("Resource 'taken-name' already exists."));

        given()
                .when().get("/v2/projects/" + project + "/locations/" + LOCATION + "/services/taken-name")
                .then()
                .statusCode(404);

        given()
                .contentType("application/json")
                .queryParam("serviceId", "other-name")
                .body("{\"template\":{\"containers\":[{\"image\":\"nginx\"}]}}")
                .when().post(parentPath(project) + "/services")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .queryParam("serviceId", "taken-name")
                .body("{\"template\":{\"containers\":[{\"image\":\"nginx\"}]}}")
                .when().post("/v2/projects/" + project + "/locations/us-central1/services")
                .then()
                .statusCode(200);
    }

    @Test
    void duplicateIdAndServiceCollisionReturnAlreadyExists() {
        String project = "inst-it-dup";
        createInstance(project, "floci-inst");

        given()
                .contentType("application/json")
                .queryParam("instanceId", "floci-inst")
                .body(BUSYBOX_BODY)
                .when().post(parentPath(project) + "/instances")
                .then()
                .statusCode(409)
                .body("error.code", equalTo(409))
                .body("error.status", equalTo("ALREADY_EXISTS"))
                .body("error.message", equalTo("Resource 'floci-inst' already exists."));

        given()
                .contentType("application/json")
                .queryParam("serviceId", "shared-name")
                .body("{\"template\":{\"containers\":[{\"image\":\"nginx\"}]}}")
                .when().post(parentPath(project) + "/services")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .queryParam("instanceId", "shared-name")
                .body(BUSYBOX_BODY)
                .when().post(parentPath(project) + "/instances")
                .then()
                .statusCode(409)
                .body("error.status", equalTo("ALREADY_EXISTS"))
                .body("error.message", equalTo("Resource 'shared-name' already exists."));

        given()
                .contentType("application/json")
                .queryParam("instanceId", "shared-name")
                .body(BUSYBOX_BODY)
                .when().post("/v2/projects/" + project + "/locations/us-central1/instances")
                .then()
                .statusCode(200);
    }

    @Test
    void stopAndStartFlipTheRunningConditionAndEnforcePreconditions() {
        String project = "inst-it-lifecycle";
        String name = instanceName(project, "floci-inst");
        String url = createInstance(project, "floci-inst");

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{}")
                .when().post("/v2/" + name + ":stop")
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.generation", equalTo("2"))
                .body("response.observedGeneration", equalTo("2"))
                .body("response.terminalCondition.type", equalTo("Running"))
                .body("response.terminalCondition.state", equalTo("CONDITION_FAILED"))
                .body("response.terminalCondition.message", equalTo("Instance stopped."))
                .body("response.conditions.type", contains("ContainerReady", "ResourcesAvailable"))
                .body("response.urls[0]", equalTo(url))
                .body("response.containerStatuses", hasSize(1));

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{}")
                .when().post("/v2/" + name + ":stop")
                .then()
                .statusCode(400)
                .body("error.code", equalTo(400))
                .body("error.status", equalTo("FAILED_PRECONDITION"))
                .body("error.message", equalTo("Instance 'floci-inst' cannot be stopped because it is not running."));

        given()
                .when().get("/v2/" + name)
                .then()
                .statusCode(200)
                .body("terminalCondition.message", equalTo("Instance stopped."))
                .body("urls[0]", equalTo(url));

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{}")
                .when().post("/v2/" + name + ":start")
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.generation", equalTo("3"))
                .body("response.terminalCondition.state", equalTo("CONDITION_SUCCEEDED"))
                .body("response.terminalCondition.message", matchesPattern("Started instance in [0-9.]+s\\."));

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{}")
                .when().post("/v2/" + name + ":start")
                .then()
                .statusCode(400)
                .body("error.status", equalTo("FAILED_PRECONDITION"))
                .body("error.message", equalTo(
                        "Instance 'floci-inst' cannot be started because it is already running."));

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{}")
                .when().post("/v2/" + instanceName(project, "missing") + ":start")
                .then()
                .statusCode(404);
    }

    @Test
    void iamPolicyRoundTrip() {
        String project = "inst-it-iam";
        String name = instanceName(project, "floci-inst");
        createInstance(project, "floci-inst");

        given()
                .urlEncodingEnabled(false)
                .when().get("/v2/" + name + ":getIamPolicy")
                .then()
                .statusCode(200)
                // Same codec as Services: the IAM empty-policy etag "ACAB" printed as proto3 JSON bytes.
                .body("etag", equalTo("QUNBQg=="))
                .body("bindings", nullValue());

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{\"policy\":{\"bindings\":[{\"role\":\"roles/run.invoker\","
                        + "\"members\":[\"serviceAccount:invoker@p.iam.gserviceaccount.com\"]}]}}")
                .when().post("/v2/" + name + ":setIamPolicy")
                .then()
                .statusCode(200)
                .body("bindings[0].role", equalTo("roles/run.invoker"));

        given()
                .urlEncodingEnabled(false)
                .when().get("/v2/" + name + ":getIamPolicy")
                .then()
                .statusCode(200)
                .body("bindings[0].members", contains("serviceAccount:invoker@p.iam.gserviceaccount.com"));

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{\"permissions\":[\"run.instances.get\",\"run.instances.invoke\"]}")
                .when().post("/v2/" + name + ":testIamPermissions")
                .then()
                .statusCode(200)
                .body("permissions", contains("run.instances.get", "run.instances.invoke"));

        given().when().delete("/v2/" + name).then().statusCode(200);
        createInstance(project, "floci-inst");

        given()
                .urlEncodingEnabled(false)
                .when().get("/v2/" + name + ":getIamPolicy")
                .then()
                .statusCode(200)
                .body("bindings", nullValue());
    }

    @Test
    void listPagesWithPageToken() {
        String project = "inst-it-paging";
        for (String id : List.of("inst-a", "inst-b", "inst-c")) {
            createInstance(project, id);
        }

        String token = given()
                .queryParam("pageSize", 2)
                .when().get(parentPath(project) + "/instances")
                .then()
                .statusCode(200)
                .body("instances.name", contains(instanceName(project, "inst-a"), instanceName(project, "inst-b")))
                .body("nextPageToken", notNullValue())
                .extract().path("nextPageToken");

        given()
                .queryParam("pageSize", 2)
                .queryParam("pageToken", token)
                .when().get(parentPath(project) + "/instances")
                .then()
                .statusCode(200)
                .body("instances.name", contains(instanceName(project, "inst-c")))
                .body("nextPageToken", nullValue());

        given()
                .when().get("/v2/projects/" + project + "/locations/us-central1/instances")
                .then()
                .statusCode(200)
                .body("instances", nullValue());
    }

    private static String createInstance(String project, String id) {
        return given()
                .contentType("application/json")
                .queryParam("instanceId", id)
                .body(BUSYBOX_BODY)
                .when().post(parentPath(project) + "/instances")
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .extract().path("response.urls[0]");
    }

    private static String parentPath(String project) {
        return "/v2/projects/" + project + "/locations/" + LOCATION;
    }

    private static String instanceName(String project, String id) {
        return "projects/" + project + "/locations/" + LOCATION + "/instances/" + id;
    }
}
