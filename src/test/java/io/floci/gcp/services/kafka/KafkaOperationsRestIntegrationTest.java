package io.floci.gcp.services.kafka;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/** Managed Kafka operations on the shared v1 route, in mock mode (operations finish as they are created). */
@QuarkusTest
class KafkaOperationsRestIntegrationTest {

    private static final String LOCATION = "us-central1";
    private static final String KAFKA_HOST = "managedkafka.googleapis.com";
    private static final String METADATA_TYPE = "type.googleapis.com/google.cloud.managedkafka.v1.OperationMetadata";

    @Test
    void clusterOperationsCanBeReadBackAndListed() {
        String project = "kafka-ops-1";
        String cluster = parent(project) + "/clusters/c1";

        String create = given()
                .contentType("application/json")
                .queryParam("clusterId", "c1")
                .body("{\"capacityConfig\":{\"vcpuCount\":3,\"memoryBytes\":3221225472}}")
                .when().post("/v1/" + parent(project) + "/clusters")
                .then()
                .statusCode(200)
                .body("name", startsWith(parent(project) + "/operations/"))
                .body("done", equalTo(true))
                .body("metadata.'@type'", equalTo(METADATA_TYPE))
                .body("metadata.target", equalTo(cluster))
                .body("metadata.verb", equalTo("create"))
                .body("metadata.apiVersion", equalTo("v1"))
                .body("metadata.createTime", notNullValue())
                .body("metadata.endTime", notNullValue())
                .body("response.'@type'", equalTo("type.googleapis.com/google.cloud.managedkafka.v1.Cluster"))
                .body("response.name", equalTo(cluster))
                .body("response.state", equalTo("ACTIVE"))
                .extract().path("name");

        given()
                .when().get("/v1/" + create)
                .then()
                .statusCode(200)
                .body("name", equalTo(create))
                .body("done", equalTo(true))
                .body("response.name", equalTo(cluster));

        String update = given()
                .contentType("application/json")
                .body("{\"capacityConfig\":{\"vcpuCount\":6}}")
                .when().patch("/v1/" + cluster)
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("metadata.verb", equalTo("update"))
                .body("response.vcpuCount", equalTo(6))
                .extract().path("name");

        String delete = given()
                .when().delete("/v1/" + cluster)
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("metadata.verb", equalTo("delete"))
                .body("response.'@type'", equalTo("type.googleapis.com/google.protobuf.Empty"))
                .extract().path("name");

        given()
                .header("Host", KAFKA_HOST)
                .when().get("/v1/" + parent(project) + "/operations")
                .then()
                .statusCode(200)
                .body("operations.name", hasItems(create, update, delete));
    }

    @Test
    void eventarcAndKafkaShareTheRouteAndAListNeverMixesThem() {
        String project = "kafka-ops-2";
        String kafka = createCluster(project, "c1");
        String eventarc = createTrigger(project);

        given()
                .when().get("/v1/" + kafka)
                .then()
                .statusCode(200)
                .body("metadata.'@type'", equalTo(METADATA_TYPE));
        given()
                .when().get("/v1/" + eventarc)
                .then()
                .statusCode(200)
                .body("metadata.'@type'", equalTo("type.googleapis.com/google.cloud.eventarc.v1.OperationMetadata"));

        // floci's own host names no API: the list is Eventarc's, which owned this route first, and
        // never mixes in Kafka operations an Eventarc client could not unpack.
        given()
                .when().get("/v1/" + parent(project) + "/operations")
                .then()
                .statusCode(200)
                .body("operations.name", hasItem(eventarc))
                .body("operations.name", not(hasItem(kafka)));

        given()
                .header("Host", KAFKA_HOST)
                .when().get("/v1/" + parent(project) + "/operations")
                .then()
                .statusCode(200)
                .body("operations.name", hasItem(kafka))
                .body("operations.name", not(hasItem(eventarc)));

        given()
                .header("Host", "eventarc.googleapis.com:443")
                .when().get("/v1/" + parent(project) + "/operations")
                .then()
                .statusCode(200)
                .body("operations.name", hasItem(eventarc))
                .body("operations.name", not(hasItem(kafka)));
    }

    @Test
    void aKafkaListPagesInNameOrder() {
        String project = "kafka-ops-3";
        createCluster(project, "c1");
        createCluster(project, "c2");
        createTrigger(project);

        String pageToken = given()
                .header("Host", KAFKA_HOST)
                .queryParam("pageSize", 1)
                .when().get("/v1/" + parent(project) + "/operations")
                .then()
                .statusCode(200)
                .body("operations.size()", equalTo(1))
                .body("nextPageToken", notNullValue())
                .extract().path("nextPageToken");

        given()
                .header("Host", KAFKA_HOST)
                .queryParam("pageSize", 1)
                .queryParam("pageToken", pageToken)
                .when().get("/v1/" + parent(project) + "/operations")
                .then()
                .statusCode(200)
                .body("operations.size()", equalTo(1))
                .body("nextPageToken", nullValue());
    }

    @Test
    void cancelLeavesAKafkaOperationAsItIsAndDeleteRemovesIt() {
        String project = "kafka-ops-4";
        String operation = createCluster(project, "c1");

        given()
                .urlEncodingEnabled(false)
                .contentType("application/x-www-form-urlencoded")
                .when().post("/v1/" + operation + ":cancel")
                .then()
                .statusCode(200)
                .body(equalTo("{}"));
        given()
                .when().get("/v1/" + operation)
                .then()
                .statusCode(200)
                .body("done", equalTo(true))
                .body("error", nullValue());

        given()
                .when().delete("/v1/" + operation)
                .then()
                .statusCode(200)
                .body(equalTo("{}"));
        given()
                .when().get("/v1/" + operation)
                .then()
                .statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"));
        given()
                .when().get("/v1/" + parent(project) + "/clusters/c1")
                .then()
                .statusCode(200);
    }

    private static String createCluster(String project, String clusterId) {
        return given()
                .contentType("application/json")
                .queryParam("clusterId", clusterId)
                .body("{}")
                .when().post("/v1/" + parent(project) + "/clusters")
                .then()
                .statusCode(200)
                .extract().path("name");
    }

    private static String createTrigger(String project) {
        return given()
                .contentType("application/json")
                .queryParam("triggerId", "t1")
                .body("{\"destination\":{\"httpEndpoint\":{\"uri\":\"http://localhost:1/hook\"}},"
                        + "\"eventFilters\":[{\"attribute\":\"type\","
                        + "\"value\":\"google.cloud.pubsub.topic.v1.messagePublished\"}]}")
                .when().post("/v1/" + parent(project) + "/triggers")
                .then()
                .statusCode(200)
                .extract().path("name");
    }

    private static String parent(String project) {
        return "projects/" + project + "/locations/" + LOCATION;
    }
}
