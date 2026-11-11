package io.floci.gcp.services.kafka;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With Docker, create and delete answer running operations that a client polls on the shared v1
 * operations route while a real Redpanda broker starts and stops.
 */
@QuarkusTest
@TestProfile(KafkaClusterRedpandaRestIntegrationTest.RedpandaProfile.class)
class KafkaClusterRedpandaRestIntegrationTest {

    private static final String PARENT = "/v1/projects/kafka-async/locations/us-central1";
    private static final String CLUSTER_ID = "async1";

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(dockerAvailable(), "Docker daemon is required for the Redpanda-backed cluster");
    }

    @Test
    void createAndDeleteArePollableWhileTheBrokerStartsAndStops() throws Exception {
        String create = given().contentType("application/json").queryParam("clusterId", CLUSTER_ID).body("{}")
                .when().post(PARENT + "/clusters")
                .then().statusCode(200)
                .body("done", nullValue())
                .body("metadata.verb", equalTo("create"))
                .extract().path("name");
        String cluster = PARENT + "/clusters/" + CLUSTER_ID;

        JsonPath created = poll(create);
        assertEquals("ACTIVE", created.getString("response.state"), created.prettify());
        given().when().get(cluster).then().statusCode(200).body("state", equalTo("ACTIVE"));
        assertEquals(1, containers());
        given().contentType("application/json").queryParam("topicId", "orders").body("{\"partitionCount\":1}")
                .when().post(cluster + "/topics")
                .then().statusCode(200);

        String delete = given().when().delete(cluster)
                .then().statusCode(200)
                .body("done", nullValue())
                .extract().path("name");
        given().when().get(cluster).then().statusCode(200).body("state", equalTo("DELETING"));
        given().contentType("application/json").queryParam("topicId", "late").body("{}")
                .when().post(cluster + "/topics")
                .then().statusCode(400).body("error.status", equalTo("FAILED_PRECONDITION"));

        JsonPath deleted = poll(delete);
        assertEquals("type.googleapis.com/google.protobuf.Empty", deleted.getString("response.'@type'"));
        given().when().get(cluster).then().statusCode(404);
        assertEquals(0, containers());
    }

    private static JsonPath poll(String operation) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(120));
        while (Instant.now().isBefore(deadline)) {
            JsonPath current = given().when().get("/v1/" + operation)
                    .then().statusCode(200).extract().jsonPath();
            if (Boolean.TRUE.equals(current.get("done"))) {
                assertTrue(current.get("error") == null, current.prettify());
                return current;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Operation " + operation + " did not finish within 120s");
    }

    private static int containers() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("docker", "ps", "-aq",
                "--filter", "label=io.floci.service=kafka",
                "--filter", "label=io.floci.resource-id=" + CLUSTER_ID)
                .redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes()).trim();
        assertTrue(process.waitFor() == 0, out);
        return out.isEmpty() ? 0 : out.split("\\s+").length;
    }

    private static boolean dockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static class RedpandaProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.kafka.mock", "false");
        }
    }
}
