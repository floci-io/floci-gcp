package io.floci.gcp.services.kafka;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ManagedSchemaRegistry} backed by a Redpanda container per registry: Redpanda's
 * compatibility checks reach the Google API, the all-versions check covers what the compatibility
 * level covers, and deleting the registry removes its container.
 */
@QuarkusTest
@TestProfile(SchemaRegistryRedpandaRestIntegrationTest.RedpandaProfile.class)
class SchemaRegistryRedpandaRestIntegrationTest {

    private static final String PROJECT = "kafka-sr-rp";
    private static final String LOCATION = "us-central1";
    private static final String BASE = "/v1/projects/" + PROJECT + "/locations/" + LOCATION + "/schemaRegistries";
    private static final String CONFLUENT = "application/vnd.schemaregistry.v1+json";
    private static final String V1 = "{\\\"type\\\":\\\"record\\\",\\\"name\\\":\\\"U\\\",\\\"fields\\\":"
            + "[{\\\"name\\\":\\\"a\\\",\\\"type\\\":\\\"string\\\"}]}";
    /** Adds a field without a default: not backward compatible with V1. */
    private static final String V2_BREAKING = "{\\\"type\\\":\\\"record\\\",\\\"name\\\":\\\"U\\\",\\\"fields\\\":"
            + "[{\\\"name\\\":\\\"a\\\",\\\"type\\\":\\\"string\\\"},{\\\"name\\\":\\\"b\\\",\\\"type\\\":\\\"int\\\"}]}";
    /** Drops field a: backward compatible with V1 (a reader without a ignores it). */
    private static final String V2_DROPS_A = "{\\\"type\\\":\\\"record\\\",\\\"name\\\":\\\"U\\\",\\\"fields\\\":"
            + "[{\\\"name\\\":\\\"c\\\",\\\"type\\\":\\\"string\\\",\\\"default\\\":\\\"\\\"}]}";

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(dockerAvailable(),
                "Docker daemon is required for the Redpanda-backed schema registry");
    }

    @Test
    void redpandaChecksCompatibilityAndTheRegistryContainerGoesWithTheRegistry() throws Exception {
        given().contentType("application/json").body("{\"schemaRegistryId\":\"rp_main\"}")
                .when().post(BASE)
                .then().statusCode(200);
        String registry = BASE + "/rp_main";
        assertEquals(1, containers("rp_main"));

        int id = given().contentType(CONFLUENT).body("{\"schema\":\"" + V1 + "\"}")
                .when().post(registry + "/subjects/orders-value/versions")
                .then().statusCode(200).extract().path("id");
        given().when().get(registry + "/schemas/ids/" + id)
                .then().statusCode(200).body("schema", containsString("\"U\""));

        // A breaking change is refused under the default BACKWARD level, with Redpanda's reason.
        given().contentType(CONFLUENT).body("{\"schema\":\"" + V2_BREAKING + "\"}")
                .when().post(registry + "/subjects/orders-value/versions")
                .then().statusCode(400).body("error.status", equalTo("FAILED_PRECONDITION"))
                .body("error.message", containsString("incompatible"));
        given().contentType(CONFLUENT).body("{\"schema\":\"" + V2_BREAKING + "\"}")
                .queryParam("verbose", true)
                .when().post(registry + "/compatibility/subjects/orders-value/versions/latest")
                .then().statusCode(200).body("is_compatible", equalTo(false))
                .body("messages[0]", containsString("READER_FIELD_MISSING_DEFAULT_VALUE"));

        // Redpanda has no all-versions route; floci checks the versions the level covers.
        given().contentType(CONFLUENT).body("{\"schema\":\"" + V2_BREAKING + "\"}")
                .when().post(registry + "/compatibility/subjects/orders-value/versions")
                .then().statusCode(200).body("is_compatible", equalTo(false));
        given().contentType(CONFLUENT).body("{\"schema\":\"" + V2_DROPS_A + "\"}")
                .when().post(registry + "/compatibility/subjects/orders-value/versions")
                .then().statusCode(200).body("is_compatible", equalTo(true));

        // Config and mode come back in the Google shapes.
        given().contentType("application/json").body("{\"compatibility\":\"NONE\"}")
                .when().put(registry + "/config/orders-value")
                .then().statusCode(200).body("compatibility", equalTo("NONE"));
        given().when().get(registry + "/config/orders-value")
                .then().statusCode(200).body("compatibility", equalTo("NONE"));
        given().contentType(CONFLUENT).body("{\"schema\":\"" + V2_BREAKING + "\"}")
                .when().post(registry + "/subjects/orders-value/versions")
                .then().statusCode(200);
        given().when().get(registry + "/subjects/orders-value/versions")
                .then().statusCode(200).body("$", contains(1, 2));
        given().when().get(registry + "/mode")
                .then().statusCode(200).body("mode", equalTo("READWRITE"));

        // A second registry is a separate registry.
        given().contentType("application/json").body("{\"schemaRegistryId\":\"rp_other\"}")
                .when().post(BASE)
                .then().statusCode(200);
        given().when().get(BASE + "/rp_other/subjects")
                .then().statusCode(200).body("$", empty());

        given().when().delete(registry).then().statusCode(200);
        given().when().delete(BASE + "/rp_other").then().statusCode(200);
        assertEquals(0, containers("rp_main"));
        assertEquals(0, containers("rp_other"));
        given().when().get(registry + "/subjects").then().statusCode(404);
    }

    @Test
    void contextsRawSchemasAndReverseLookupsReachRedpanda() {
        given().contentType("application/json").body("{\"schemaRegistryId\":\"rp_ctx\"}")
                .when().post(BASE)
                .then().statusCode(200);
        String registry = BASE + "/rp_ctx";
        String name = "projects/" + PROJECT + "/locations/" + LOCATION + "/schemaRegistries/rp_ctx";
        String ctx = registry + "/contexts/.team";

        int baseId = given().contentType(CONFLUENT).body("{\"schema\":\"" + V1 + "\"}")
                .when().post(registry + "/subjects/users/versions")
                .then().statusCode(200).extract().path("id");
        int refId = given().contentType(CONFLUENT)
                .body("{\"schema\":\"{\\\"type\\\":\\\"record\\\",\\\"name\\\":\\\"W\\\","
                        + "\\\"fields\\\":[{\\\"name\\\":\\\"u\\\",\\\"type\\\":\\\"U\\\"}]}\","
                        + "\"references\":[{\"name\":\"U\",\"subject\":\"users\",\"version\":1}]}")
                .when().post(registry + "/subjects/wrappers/versions")
                .then().statusCode(200).extract().path("id");
        given().contentType(CONFLUENT).body("{\"schema\":\"" + V1 + "\"}")
                .when().post(ctx + "/subjects/users/versions")
                .then().statusCode(200);

        given().when().get(registry + "/contexts")
                .then().statusCode(200).body("$", contains(".", ".team"));
        given().when().get(registry)
                .then().statusCode(200).body("contexts", contains(name + "/contexts/.", name + "/contexts/.team"));
        given().when().get(registry + "/contexts/.team")
                .then().statusCode(200).body("subjects", contains(name + "/contexts/.team/subjects/users"));
        given().when().get(ctx + "/subjects/users/versions/1")
                .then().statusCode(200).body("subject", equalTo(":.team:users"));

        given().when().get(registry + "/schemas/ids/" + baseId + "/schema")
                .then().statusCode(200).body("name", equalTo("U"));
        given().when().get(registry + "/subjects/users/versions/1/referencedby")
                .then().statusCode(200).body("$", contains(refId));
        given().when().get(registry + "/schemas/ids/" + baseId + "/versions")
                .then().statusCode(200).body("subject", contains("users"));
        given().when().get(registry + "/schemas/ids/" + baseId + "/subjects")
                .then().statusCode(200).body("$", contains("users"));
        given().when().get(registry + "/schemas/types")
                .then().statusCode(200).body("$", hasItems("AVRO", "JSON", "PROTOBUF"));

        // A context with no config or mode of its own does not inherit the registry's.
        given().contentType("application/json").body("{\"compatibility\":\"FULL\"}")
                .when().put(registry + "/config").then().statusCode(200);
        given().queryParam("defaultToGlobal", true).when().get(ctx + "/config/users")
                .then().statusCode(200).body("compatibility", equalTo("BACKWARD"));
        given().contentType("application/json").body("{\"mode\":\"READONLY\"}")
                .when().put(registry + "/mode").then().statusCode(200);
        given().contentType(CONFLUENT).body("{\"schema\":\"" + V1 + "\"}")
                .when().post(ctx + "/subjects/other/versions")
                .then().statusCode(200);
        given().contentType(CONFLUENT).body("{\"schema\":\"" + V1 + "\"}")
                .when().post(registry + "/subjects/plain/versions")
                .then().statusCode(400).body("error.message", containsString("read-only"));

        given().when().delete(registry).then().statusCode(200);
    }

    /** Containers (running or not) floci started for the registry, found by its identity label. */
    private static int containers(String registryId) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("docker", "ps", "-aq",
                "--filter", "label=io.floci.service=" + RedpandaSchemaRegistryDataPlane.SERVICE,
                "--filter", "label=io.floci.resource-id=" + registryId)
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
