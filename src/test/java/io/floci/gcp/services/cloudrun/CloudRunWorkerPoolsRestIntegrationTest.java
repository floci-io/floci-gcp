package io.floci.gcp.services.cloudrun;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class CloudRunWorkerPoolsRestIntegrationTest {

    private static final String LOCATION = "us-central1";
    private static final String BUSYBOX_POOL = """
            {
              "template": {
                "containers": [{
                  "image": "docker.io/library/busybox",
                  "command": ["sh", "-c"],
                  "args": ["sleep 3600"]
                }]
              },
              "customAudiences": ["https://example.com"]
            }
            """;

    @Test
    void createCompletesGcpDefaultsAndFirstRevision() {
        String project = "wp-it-create";
        String name = poolName(project, "floci-wp");

        String operationName = given()
                .contentType("application/json")
                .queryParam("workerPoolId", "floci-wp")
                .body(BUSYBOX_POOL)
                .when().post(parentPath(project) + "/workerPools")
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("metadata.'@type'", equalTo("type.googleapis.com/google.cloud.run.v2.WorkerPool"))
                .body("response.'@type'", equalTo("type.googleapis.com/google.cloud.run.v2.WorkerPool"))
                .body("response.name", equalTo(name))
                .extract().path("name");

        given()
                .when().get("/v2/" + operationName)
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.name", equalTo(name));

        String revisionName = given()
                .when().get("/v2/" + name)
                .then()
                .statusCode(200)
                .body("uid", notNullValue())
                .body("generation", equalTo("1"))
                .body("observedGeneration", equalTo("1"))
                .body("launchStage", equalTo("GA"))
                .body("scaling.manualInstanceCount", equalTo(1))
                .body("template.containers[0].name", nullValue())
                .body("template.containers[0].resources.limits.cpu", equalTo("1000m"))
                .body("template.containers[0].resources.limits.memory", equalTo("512Mi"))
                .body("instanceSplits", hasSize(1))
                .body("instanceSplits[0].type", equalTo("INSTANCE_SPLIT_ALLOCATION_TYPE_LATEST"))
                .body("instanceSplits[0].percent", equalTo(100))
                .body("terminalCondition.type", equalTo("Ready"))
                .body("terminalCondition.state", equalTo("CONDITION_SUCCEEDED"))
                .body("conditions", nullValue())
                .body("reconciling", nullValue())
                .body("customAudiences", nullValue())
                .body("etag", notNullValue())
                .body("latestCreatedRevision", matchesPattern(name + "/revisions/floci-wp-00001-[a-z0-9]{3}"))
                .extract().path("latestCreatedRevision");
        String revisionId = revisionName.substring(revisionName.lastIndexOf('/') + 1);

        given()
                .when().get("/v2/" + name)
                .then()
                .body("latestReadyRevision", equalTo(revisionName))
                .body("instanceSplitStatuses", hasSize(1))
                .body("instanceSplitStatuses[0].type", equalTo("INSTANCE_SPLIT_ALLOCATION_TYPE_LATEST"))
                .body("instanceSplitStatuses[0].revision", equalTo(revisionId))
                .body("instanceSplitStatuses[0].percent", equalTo(100));

        given()
                .when().get("/v2/" + revisionName)
                .then()
                .statusCode(200)
                .body("service", nullValue())
                .body("launchStage", equalTo("GA"))
                .body("containers[0].name", equalTo("busybox-1"))
                .body("containers[0].image", equalTo("docker.io/library/busybox"))
                .body("containers[0].resources.limits.cpu", equalTo("1000m"))
                .body("executionEnvironment", equalTo("EXECUTION_ENVIRONMENT_GEN2"))
                .body("conditions.type", contains("Ready", "Active", "ResourcesAvailable", "ContainerReady",
                        "MinInstancesProvisioned"))
                .body("conditions.state", contains("CONDITION_SUCCEEDED", "CONDITION_SUCCEEDED",
                        "CONDITION_SUCCEEDED", "CONDITION_SUCCEEDED", "CONDITION_SUCCEEDED"))
                .body("conditions.find { it.type == 'Ready' }.message",
                        matchesPattern("Deploying revision succeeded in \\d+\\.\\d{2}s\\."))
                .body("conditions.find { it.type == 'Retry' }", nullValue())
                .body("scalingStatus.desiredMinInstanceCount", equalTo(1));

        given()
                .when().get(poolPath(project, "floci-wp") + "/revisions")
                .then()
                .statusCode(200)
                .body("revisions.name", contains(revisionName));
    }

    @Test
    void duplicateCreateIsAlreadyExists() {
        String project = "wp-it-dup";
        createPool(project, "dup");

        given()
                .contentType("application/json")
                .queryParam("workerPoolId", "dup")
                .body(BUSYBOX_POOL)
                .when().post(parentPath(project) + "/workerPools")
                .then()
                .statusCode(409)
                .body("error.code", equalTo(409))
                .body("error.status", equalTo("ALREADY_EXISTS"))
                .body("error.message", equalTo("Resource 'dup' already exists."));
    }

    @Test
    void revisionsAreCreatedOnTemplateChangeAndForceNewRevisionOnly() {
        String project = "wp-it-revs";
        String pool = poolPath(project, "wp2");
        String name = poolName(project, "wp2");
        String first = createPool(project, "wp2");

        given()
                .contentType("application/json")
                .queryParam("updateMask", "scaling.manualInstanceCount")
                .body("{\"scaling\":{\"manualInstanceCount\":2}}")
                .when().patch(pool)
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.generation", equalTo("2"))
                .body("response.scaling.manualInstanceCount", equalTo(2))
                .body("response.latestCreatedRevision", equalTo(first));
        given()
                .when().get("/v2/" + first)
                .then()
                .body("scalingStatus.desiredMinInstanceCount", equalTo(2));

        given()
                .contentType("application/json")
                .queryParam("updateMask", "labels")
                .body("{\"labels\":{\"team\":\"core\"}}")
                .when().patch(pool)
                .then()
                .statusCode(200)
                .body("response.generation", equalTo("3"))
                .body("response.labels.team", equalTo("core"))
                .body("response.latestCreatedRevision", equalTo(first));

        String second = given()
                .contentType("application/json")
                .queryParam("updateMask", "template")
                .body("""
                        {"template":{"containers":[{"image":"docker.io/library/busybox",
                          "command":["sh","-c"],"args":["sleep 3601"]}]}}
                        """)
                .when().patch(pool)
                .then()
                .statusCode(200)
                .body("response.generation", equalTo("4"))
                .body("response.latestCreatedRevision", matchesPattern(name + "/revisions/wp2-00002-[a-z0-9]{3}"))
                .extract().path("response.latestCreatedRevision");

        String third = given()
                .contentType("application/json")
                .queryParam("updateMask", "template")
                .queryParam("forceNewRevision", true)
                .body("""
                        {"template":{"containers":[{"image":"docker.io/library/busybox",
                          "command":["sh","-c"],"args":["sleep 3601"]}]}}
                        """)
                .when().patch(pool)
                .then()
                .statusCode(200)
                .body("response.latestCreatedRevision", matchesPattern(name + "/revisions/wp2-00003-[a-z0-9]{3}"))
                .extract().path("response.latestCreatedRevision");
        given()
                .when().get(pool)
                .then()
                .body("latestReadyRevision", equalTo(third))
                .body("instanceSplitStatuses[0].revision", equalTo(third.substring(third.lastIndexOf('/') + 1)));

        given()
                .contentType("application/json")
                .queryParam("updateMask", "template")
                .body("""
                        {"template":{"containers":[{"image":"docker.io/library/busybox",
                          "command":["sh","-c"],"args":["sleep 3601"]}]}}
                        """)
                .when().patch(pool)
                .then()
                .statusCode(200)
                .body("response.latestCreatedRevision", equalTo(third));

        given()
                .when().get(pool + "/revisions")
                .then()
                .statusCode(200)
                .body("revisions.name", contains(third, second, first));

        for (String retired : new String[] {first, second}) {
            given()
                    .when().get("/v2/" + retired)
                    .then()
                    .body("conditions.find { it.type == 'Active' }.state", equalTo("CONDITION_FAILED"))
                    .body("conditions.find { it.type == 'Active' }.message", equalTo("Revision retired."))
                    .body("conditions.find { it.type == 'Active' }.revisionReason", equalTo("RETIRED"))
                    .body("scalingStatus", nullValue());
        }
        given()
                .when().get("/v2/" + third)
                .then()
                .body("conditions.find { it.type == 'Active' }.state", equalTo("CONDITION_SUCCEEDED"))
                .body("scalingStatus.desiredMinInstanceCount", equalTo(2));
    }

    @Test
    void instanceSplitsPinRevisionAndServingRevisionCannotBeDeleted() {
        String project = "wp-it-split";
        String pool = poolPath(project, "wp");
        String first = createPool(project, "wp");
        String firstId = first.substring(first.lastIndexOf('/') + 1);
        String second = given()
                .contentType("application/json")
                .queryParam("updateMask", "template")
                .body("{\"template\":{\"containers\":[{\"image\":\"busybox:1.36\"}]}}")
                .when().patch(pool)
                .then()
                .statusCode(200)
                .extract().path("response.latestCreatedRevision");
        String secondId = second.substring(second.lastIndexOf('/') + 1);

        given()
                .contentType("application/json")
                .queryParam("updateMask", "instanceSplits")
                .body("""
                        {"instanceSplits":[{"type":"INSTANCE_SPLIT_ALLOCATION_TYPE_REVISION",
                          "revision":"%s","percent":100}]}
                        """.formatted(firstId))
                .when().patch(pool)
                .then()
                .statusCode(200)
                .body("response.instanceSplits[0].type", equalTo("INSTANCE_SPLIT_ALLOCATION_TYPE_REVISION"))
                .body("response.instanceSplitStatuses", hasSize(1))
                .body("response.instanceSplitStatuses[0].type", equalTo("INSTANCE_SPLIT_ALLOCATION_TYPE_REVISION"))
                .body("response.instanceSplitStatuses[0].revision", equalTo(firstId))
                .body("response.instanceSplitStatuses[0].percent", equalTo(100));

        given()
                .when().get("/v2/" + first)
                .then()
                .body("conditions.find { it.type == 'Active' }.state", equalTo("CONDITION_SUCCEEDED"))
                .body("scalingStatus.desiredMinInstanceCount", equalTo(1));

        given()
                .when().delete("/v2/" + first)
                .then()
                .statusCode(400)
                .body("error.status", equalTo("FAILED_PRECONDITION"))
                .body("error.message", equalTo("Revision \"" + firstId
                        + "\" cannot be directly deleted because it is actively serving."));

        given()
                .when().delete("/v2/" + second)
                .then()
                .statusCode(400)
                .body("error.status", equalTo("FAILED_PRECONDITION"))
                .body("error.message", equalTo("Revision \"" + secondId
                        + "\" cannot be directly deleted because it is actively serving."));

        String third = given()
                .contentType("application/json")
                .queryParam("updateMask", "template")
                .body("{\"template\":{\"containers\":[{\"image\":\"busybox:1.37\"}]}}")
                .when().patch(pool)
                .then()
                .statusCode(200)
                .body("response.instanceSplitStatuses[0].revision", equalTo(firstId))
                .extract().path("response.latestCreatedRevision");
        String thirdId = third.substring(third.lastIndexOf('/') + 1);
        given()
                .when().delete("/v2/" + third)
                .then()
                .statusCode(400)
                .body("error.status", equalTo("FAILED_PRECONDITION"));

        given()
                .queryParam("validateOnly", true)
                .when().delete("/v2/" + second)
                .then()
                .statusCode(200)
                .body("done", equalTo(true));
        given().when().get("/v2/" + second).then().statusCode(200);

        given()
                .when().delete("/v2/" + second)
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("metadata.'@type'", equalTo("type.googleapis.com/google.cloud.run.v2.Revision"))
                .body("response.'@type'", equalTo("type.googleapis.com/google.cloud.run.v2.Revision"))
                .body("response.name", equalTo(second))
                .body("response.generation", equalTo("2"))
                .body("response.deleteTime", notNullValue())
                .body("response.expireTime", notNullValue());

        given().when().get("/v2/" + second).then().statusCode(404).body("error.status", equalTo("NOT_FOUND"));
        given()
                .when().get(pool + "/revisions")
                .then()
                .body("revisions.name", contains(third, first));

        given()
                .contentType("application/json")
                .queryParam("updateMask", "instanceSplits")
                .body("{\"instanceSplits\":[{\"type\":\"INSTANCE_SPLIT_ALLOCATION_TYPE_REVISION\","
                        + "\"revision\":\"" + secondId + "\",\"percent\":100}]}")
                .when().patch(pool)
                .then()
                .statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));

        given()
                .contentType("application/json")
                .queryParam("updateMask", "instanceSplits")
                .body("{\"instanceSplits\":[{\"type\":\"INSTANCE_SPLIT_ALLOCATION_TYPE_LATEST\",\"percent\":100}]}")
                .when().patch(pool)
                .then()
                .statusCode(200)
                .body("response.instanceSplitStatuses[0].type", equalTo("INSTANCE_SPLIT_ALLOCATION_TYPE_LATEST"))
                .body("response.instanceSplitStatuses[0].revision", equalTo(thirdId))
                .body("response.latestReadyRevision", equalTo(third));
        given()
                .when().get("/v2/" + third)
                .then()
                .body("conditions.find { it.type == 'Active' }.state", equalTo("CONDITION_SUCCEEDED"));
    }

    @Test
    void latestCreatedRevisionCannotBeDeletedWhileSplitsPinAnOlderOne() {
        String project = "wp-it-latest-rev";
        String pool = poolPath(project, "wp");
        String first = createPool(project, "wp");
        String firstId = first.substring(first.lastIndexOf('/') + 1);
        String second = given()
                .contentType("application/json")
                .queryParam("updateMask", "template")
                .body("{\"template\":{\"containers\":[{\"image\":\"busybox:1.36\"}]}}")
                .when().patch(pool)
                .then()
                .statusCode(200)
                .extract().path("response.latestCreatedRevision");
        String secondId = second.substring(second.lastIndexOf('/') + 1);
        given()
                .contentType("application/json")
                .queryParam("updateMask", "instanceSplits")
                .body("{\"instanceSplits\":[{\"type\":\"INSTANCE_SPLIT_ALLOCATION_TYPE_REVISION\","
                        + "\"revision\":\"" + firstId + "\",\"percent\":100}]}")
                .when().patch(pool)
                .then()
                .statusCode(200);

        given()
                .when().delete("/v2/" + second)
                .then()
                .statusCode(400)
                .body("error.code", equalTo(400))
                .body("error.status", equalTo("FAILED_PRECONDITION"))
                .body("error.message", equalTo("Revision \"" + secondId
                        + "\" cannot be directly deleted because it is actively serving."));

        given()
                .contentType("application/json")
                .queryParam("updateMask", "instanceSplits")
                .body("{\"instanceSplits\":[{\"type\":\"INSTANCE_SPLIT_ALLOCATION_TYPE_LATEST\",\"percent\":100}]}")
                .when().patch(pool)
                .then()
                .statusCode(200)
                .body("response.instanceSplitStatuses[0].revision", equalTo(secondId));
        given().when().get("/v2/" + second).then().statusCode(200);
    }

    @Test
    void missingPoolAndRevisionUseGcpNotFoundMessage() {
        String project = "wp-it-notfound";
        given()
                .when().get(poolPath(project, "absent"))
                .then()
                .statusCode(404)
                .body("error.code", equalTo(404))
                .body("error.status", equalTo("NOT_FOUND"))
                .body("error.message", equalTo("Resource 'absent' of kind 'WORKER_POOL' in region '" + LOCATION
                        + "' in project '" + project + "' does not exist."));
        given()
                .contentType("application/json")
                .body(BUSYBOX_POOL)
                .when().patch(poolPath(project, "absent"))
                .then()
                .statusCode(404)
                .body("error.message", equalTo("Resource 'absent' of kind 'WORKER_POOL' in region '" + LOCATION
                        + "' in project '" + project + "' does not exist."));
        given()
                .when().delete(poolPath(project, "absent"))
                .then()
                .statusCode(404)
                .body("error.message", equalTo("Resource 'absent' of kind 'WORKER_POOL' in region '" + LOCATION
                        + "' in project '" + project + "' does not exist."));

        createPool(project, "wp");
        given()
                .when().get(poolPath(project, "wp") + "/revisions/wp-00009-zzz")
                .then()
                .statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"))
                .body("error.message", equalTo("Resource 'wp-00009-zzz' of kind 'REVISION' in region '" + LOCATION
                        + "' in project '" + project + "' does not exist."));
        given()
                .when().delete(poolPath(project, "wp") + "/revisions/wp-00009-zzz")
                .then()
                .statusCode(404)
                .body("error.message", equalTo("Resource 'wp-00009-zzz' of kind 'REVISION' in region '" + LOCATION
                        + "' in project '" + project + "' does not exist."));
    }

    @Test
    void createIgnoresOutputOnlyFieldsFromTheRequest() {
        String project = "wp-it-output-only";
        given()
                .contentType("application/json")
                .queryParam("workerPoolId", "wp")
                .body("""
                        {
                          "creator": "mallory@example.com",
                          "lastModifier": "mallory@example.com",
                          "satisfiesPzs": true,
                          "threatDetectionEnabled": true,
                          "reconciling": true,
                          "observedGeneration": "7",
                          "latestReadyRevision": "bogus",
                          "template": {"containers": [{"image": "docker.io/library/busybox"}]}
                        }
                        """)
                .when().post(parentPath(project) + "/workerPools")
                .then()
                .statusCode(200);

        given()
                .when().get(poolPath(project, "wp"))
                .then()
                .statusCode(200)
                .body("creator", nullValue())
                .body("lastModifier", nullValue())
                .body("satisfiesPzs", nullValue())
                .body("threatDetectionEnabled", nullValue())
                .body("reconciling", nullValue())
                .body("observedGeneration", equalTo("1"))
                .body("latestReadyRevision", containsString("/revisions/wp-00001-"));
    }

    @Test
    void iamPolicyOnMissingPoolIsNotFoundAndNeverStored() {
        String project = "wp-it-iam-missing";
        String pool = poolPath(project, "wp");
        String notFound = "Resource 'wp' of kind 'WORKER_POOL' in region '" + LOCATION + "' in project '" + project
                + "' does not exist.";
        String policy = "{\"policy\":{\"bindings\":[{\"role\":\"roles/run.developer\","
                + "\"members\":[\"user:dev@example.com\"]}]}}";

        given()
                .urlEncodingEnabled(false)
                .when().get(pool + ":getIamPolicy")
                .then()
                .statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"))
                .body("error.message", equalTo(notFound));
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body(policy)
                .when().post(pool + ":setIamPolicy")
                .then()
                .statusCode(404)
                .body("error.message", equalTo(notFound));
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{\"permissions\":[\"run.workerpools.get\"]}")
                .when().post(pool + ":testIamPermissions")
                .then()
                .statusCode(200)
                .body("permissions", nullValue());

        createPool(project, "wp");
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body(policy)
                .when().post(pool + ":setIamPolicy")
                .then()
                .statusCode(200);
        given().when().delete(pool).then().statusCode(200);
        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body(policy)
                .when().post(pool + ":setIamPolicy")
                .then()
                .statusCode(404);

        createPool(project, "wp");
        given()
                .urlEncodingEnabled(false)
                .when().get(pool + ":getIamPolicy")
                .then()
                .statusCode(200)
                .body("bindings", nullValue());
    }

    @Test
    void iamPolicyRoundTrips() {
        String project = "wp-it-iam";
        String pool = poolPath(project, "wp");
        createPool(project, "wp");

        String servicePolicyEtag = given()
                .urlEncodingEnabled(false)
                .when().get(parentPath(project) + "/services/any:getIamPolicy")
                .then()
                .statusCode(200)
                .extract().path("etag");
        given()
                .urlEncodingEnabled(false)
                .when().get(pool + ":getIamPolicy")
                .then()
                .statusCode(200)
                .body("etag", equalTo(servicePolicyEtag))
                .body("bindings", nullValue());

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{\"policy\":{\"bindings\":[{\"role\":\"roles/run.developer\","
                        + "\"members\":[\"user:dev@example.com\"]}]}}")
                .when().post(pool + ":setIamPolicy")
                .then()
                .statusCode(200)
                .body("bindings[0].role", equalTo("roles/run.developer"))
                .body("etag", not(equalTo(servicePolicyEtag)));

        given()
                .urlEncodingEnabled(false)
                .when().get(pool + ":getIamPolicy")
                .then()
                .statusCode(200)
                .body("bindings[0].members", hasItem("user:dev@example.com"));

        given()
                .urlEncodingEnabled(false)
                .contentType("application/json")
                .body("{\"permissions\":[\"run.workerpools.get\",\"run.workerpools.update\"]}")
                .when().post(pool + ":testIamPermissions")
                .then()
                .statusCode(200)
                .body("permissions", contains("run.workerpools.get", "run.workerpools.update"));
    }

    @Test
    void validateOnlyNeverPersists() {
        String project = "wp-it-validate";
        String pool = poolPath(project, "wp");

        String operationName = given()
                .contentType("application/json")
                .queryParam("workerPoolId", "wp")
                .queryParam("validateOnly", true)
                .body(BUSYBOX_POOL)
                .when().post(parentPath(project) + "/workerPools")
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.name", equalTo(poolName(project, "wp")))
                .extract().path("name");
        given().when().get(pool).then().statusCode(404);
        given().when().get("/v2/" + operationName).then().statusCode(404);

        String first = createPool(project, "wp");
        given()
                .contentType("application/json")
                .queryParam("updateMask", "template")
                .queryParam("validateOnly", true)
                .body("{\"template\":{\"containers\":[{\"image\":\"busybox:1.36\"}]}}")
                .when().patch(pool)
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.generation", equalTo("2"));
        given()
                .when().get(pool)
                .then()
                .body("generation", equalTo("1"))
                .body("latestCreatedRevision", equalTo(first));
        given().when().get(pool + "/revisions").then().body("revisions", hasSize(1));

        given()
                .queryParam("validateOnly", true)
                .when().delete(pool)
                .then()
                .statusCode(200)
                .body("done", equalTo(true));
        given().when().get(pool).then().statusCode(200);
    }

    @Test
    void patchWithAllowMissingCreatesPool() {
        String project = "wp-it-upsert";
        String pool = poolPath(project, "wp");

        given()
                .contentType("application/json")
                .body(BUSYBOX_POOL)
                .when().patch(pool)
                .then()
                .statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"));

        given()
                .contentType("application/json")
                .queryParam("allowMissing", true)
                .body(BUSYBOX_POOL)
                .when().patch(pool)
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.name", equalTo(poolName(project, "wp")))
                .body("response.generation", equalTo("1"))
                .body("response.latestCreatedRevision", containsString("/revisions/wp-00001-"));

        given()
                .contentType("application/json")
                .queryParam("allowMissing", true)
                .body("{\"template\":{\"containers\":[{\"image\":\"busybox:1.36\"}]}}")
                .when().patch(pool)
                .then()
                .statusCode(200)
                .body("response.generation", equalTo("2"))
                .body("response.latestCreatedRevision", containsString("/revisions/wp-00002-"));
    }

    @Test
    void listPagesAndDeleteRemovesPoolAndRevisions() {
        String project = "wp-it-list";
        createPool(project, "a");
        createPool(project, "b");
        createPool(project, "c");

        String token = given()
                .queryParam("pageSize", 2)
                .when().get(parentPath(project) + "/workerPools")
                .then()
                .statusCode(200)
                .body("workerPools.name", contains(poolName(project, "a"), poolName(project, "b")))
                .body("nextPageToken", notNullValue())
                .extract().path("nextPageToken");
        given()
                .queryParam("pageSize", 2)
                .queryParam("pageToken", token)
                .when().get(parentPath(project) + "/workerPools")
                .then()
                .statusCode(200)
                .body("workerPools.name", contains(poolName(project, "c")))
                .body("nextPageToken", nullValue());

        given()
                .queryParam("etag", "\"bogus\"")
                .when().delete(poolPath(project, "b"))
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.name", equalTo(poolName(project, "b")))
                .body("response.generation", equalTo("2"))
                .body("response.deleteTime", notNullValue())
                .body("response.expireTime", notNullValue());

        given().when().get(poolPath(project, "b")).then().statusCode(404).body("error.status", equalTo("NOT_FOUND"));
        given()
                .when().get(poolPath(project, "b") + "/revisions")
                .then()
                .statusCode(200)
                .body("$", anEmptyMap());
        given()
                .when().get(parentPath(project) + "/workerPools")
                .then()
                .body("workerPools.name", contains(poolName(project, "a"), poolName(project, "c")));
        given().when().delete(poolPath(project, "b")).then().statusCode(404);
    }

    @Test
    void serviceAndWorkerPoolRevisionsNeverMix() {
        String project = "wp-it-mixed";
        String servicePath = parentPath(project) + "/services/same";
        given()
                .contentType("application/json")
                .queryParam("serviceId", "same")
                .body("{\"template\":{\"containers\":[{\"image\":\"gcr.io/p/svc:v1\"}]}}")
                .when().post(parentPath(project) + "/services")
                .then()
                .statusCode(200);
        String poolRevision = createPool(project, "same");
        String poolRevisionId = poolRevision.substring(poolRevision.lastIndexOf('/') + 1);

        given()
                .when().get(servicePath + "/revisions")
                .then()
                .body("revisions.name", contains(servicePath.substring("/v2/".length()) + "/revisions/same-00001"));
        given()
                .when().get(poolPath(project, "same") + "/revisions")
                .then()
                .body("revisions.name", contains(poolRevision));
        given().when().get(servicePath + "/revisions/" + poolRevisionId).then().statusCode(404);
        given().when().delete(servicePath + "/revisions/" + poolRevisionId).then().statusCode(404);
        given().when().delete(poolPath(project, "same") + "/revisions/same-00001").then().statusCode(404);
    }

    @Test
    void serviceRevisionDeleteRefusesServingRevision() {
        String project = "wp-it-svc-rev";
        String servicePath = parentPath(project) + "/services/svc";
        given()
                .contentType("application/json")
                .queryParam("serviceId", "svc")
                .body("{\"template\":{\"containers\":[{\"image\":\"gcr.io/p/svc:v1\"}]}}")
                .when().post(parentPath(project) + "/services")
                .then()
                .statusCode(200);

        given()
                .when().delete(servicePath + "/revisions/svc-00001")
                .then()
                .statusCode(400)
                .body("error.status", equalTo("FAILED_PRECONDITION"))
                .body("error.message",
                        equalTo("Revision \"svc-00001\" cannot be directly deleted because it is actively serving."));

        given()
                .contentType("application/json")
                .queryParam("updateMask", "template")
                .body("{\"template\":{\"containers\":[{\"image\":\"gcr.io/p/svc:v2\"}]}}")
                .when().patch(servicePath)
                .then()
                .statusCode(200)
                .body("response.latestReadyRevision", endsWith("/revisions/svc-00002"));

        given()
                .when().delete(servicePath + "/revisions/svc-00001")
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("response.'@type'", equalTo("type.googleapis.com/google.cloud.run.v2.Revision"))
                .body("response.deleteTime", notNullValue());
        given()
                .when().get(servicePath + "/revisions")
                .then()
                .body("revisions.name", contains(servicePath.substring("/v2/".length()) + "/revisions/svc-00002"));
    }

    private static String createPool(String project, String poolId) {
        return given()
                .contentType("application/json")
                .queryParam("workerPoolId", poolId)
                .body(BUSYBOX_POOL)
                .when().post(parentPath(project) + "/workerPools")
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .extract().path("response.latestCreatedRevision");
    }

    private static String parentPath(String project) {
        return "/v2/projects/" + project + "/locations/" + LOCATION;
    }

    private static String poolPath(String project, String poolId) {
        return parentPath(project) + "/workerPools/" + poolId;
    }

    private static String poolName(String project, String poolId) {
        return "projects/" + project + "/locations/" + LOCATION + "/workerPools/" + poolId;
    }
}
