package io.floci.gcp.services.kafka;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/** The shared v1 operations route gates each API by its own service, as that service's routes are gated. */
@QuarkusTest
@TestProfile(KafkaOperationsDisabledRestIntegrationTest.DisabledKafkaProfile.class)
class KafkaOperationsDisabledRestIntegrationTest {

    private static final String PARENT = "projects/kafka-ops-disabled/locations/us-central1";

    @Test
    void aDisabledKafkaLeavesEventarcOperationsServedAndRefusesAKafkaHost() {
        String eventarc = given()
                .contentType("application/json")
                .queryParam("triggerId", "t1")
                .body("{\"destination\":{\"httpEndpoint\":{\"uri\":\"http://localhost:1/hook\"}},"
                        + "\"eventFilters\":[{\"attribute\":\"type\","
                        + "\"value\":\"google.cloud.pubsub.topic.v1.messagePublished\"}]}")
                .when().post("/v1/" + PARENT + "/triggers")
                .then()
                .statusCode(200)
                .extract().path("name");

        given()
                .when().get("/v1/" + eventarc)
                .then()
                .statusCode(200);
        given()
                .when().get("/v1/" + PARENT + "/operations")
                .then()
                .statusCode(200)
                .body("operations.name", hasItem(eventarc));
        given()
                .when().get("/v1/" + PARENT + "/operations/unknown")
                .then()
                .statusCode(404);

        given()
                .header("Host", "managedkafka.googleapis.com")
                .when().get("/v1/" + PARENT + "/operations")
                .then()
                .statusCode(503)
                .body("error.status", equalTo("UNAVAILABLE"))
                .body("error.message", equalTo("Service kafka is not enabled."));
    }

    public static class DisabledKafkaProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.kafka.enabled", "false");
        }
    }
}
