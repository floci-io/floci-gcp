package io.floci.gcp.services.kafka;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * {@code ManagedSchemaRegistry} over REST in mock mode (the test profile's default): registry
 * resources, and the Confluent-shaped routes under a registry as a Confluent serializer calls
 * them, with the Google shapes the proto gives the non-{@code HttpBody} methods.
 */
@QuarkusTest
class SchemaRegistryRestIntegrationTest {

    private static final String PROJECT = "kafka-sr-it";
    private static final String LOCATION = "us-central1";
    private static final String BASE = "/v1/projects/" + PROJECT + "/locations/" + LOCATION + "/schemaRegistries";
    private static final String CONFLUENT = "application/vnd.schemaregistry.v1+json";
    private static final String USER_V1 = "{\\\"type\\\":\\\"record\\\",\\\"name\\\":\\\"User\\\",\\\"fields\\\":"
            + "[{\\\"name\\\":\\\"a\\\",\\\"type\\\":\\\"string\\\"}]}";
    private static final String USER_V2 = "{\\\"type\\\":\\\"record\\\",\\\"name\\\":\\\"User\\\",\\\"fields\\\":"
            + "[{\\\"name\\\":\\\"a\\\",\\\"type\\\":\\\"string\\\"},{\\\"name\\\":\\\"b\\\",\\\"type\\\":\\\"int\\\","
            + "\\\"default\\\":0}]}";

    private static String registry(String id) {
        given().contentType("application/json")
                .body("{\"schemaRegistryId\":\"" + id + "\",\"schemaRegistry\":{}}")
                .when().post(BASE)
                .then().statusCode(200)
                .body("name", equalTo("projects/" + PROJECT + "/locations/" + LOCATION + "/schemaRegistries/" + id));
        return BASE + "/" + id;
    }

    private static int register(String registry, String subject, String schema) {
        return given().contentType(CONFLUENT)
                .body("{\"schema\":\"" + schema + "\"}")
                .when().post(registry + "/subjects/" + subject + "/versions")
                .then().statusCode(200)
                .extract().path("id");
    }

    @Test
    void registriesAreCreatedListedFetchedAndDeleted() {
        String registry = registry("lifecycle");

        given().contentType("application/json").body("{\"schemaRegistryId\":\"lifecycle\"}")
                .when().post(BASE)
                .then().statusCode(409).body("error.status", equalTo("ALREADY_EXISTS"));
        given().when().get(registry)
                .then().statusCode(200).body("name", containsString("/schemaRegistries/lifecycle"));
        given().when().get(BASE)
                .then().statusCode(200).body("schemaRegistries.name", hasItem(containsString("/lifecycle")));

        given().when().delete(registry).then().statusCode(200);
        given().when().get(registry).then().statusCode(404).body("error.status", equalTo("NOT_FOUND"));
        given().when().get(registry + "/subjects").then().statusCode(404);
        given().when().delete(registry).then().statusCode(404);
    }

    @Test
    void createValidatesTheRegistryId() {
        given().contentType("application/json").body("{\"schemaRegistryId\":\"9starts_with_digit\"}")
                .when().post(BASE)
                .then().statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));
        given().contentType("application/json").body("{}")
                .when().post(BASE)
                .then().statusCode(400);
    }

    @Test
    void aConfluentSerializerCanRegisterAndReadSchemasBack() {
        String registry = registry("serializer");

        // CreateVersion: the Google response carries only the schema id.
        int id = register(registry, "orders-value", USER_V1);
        given().contentType(CONFLUENT).body("{\"schema\":\"" + USER_V1 + "\"}")
                .when().post(registry + "/subjects/orders-value/versions")
                .then().statusCode(200).body("id", equalTo(id)).body("version", nullValue());

        // GetSchema by id, as the deserializer fetches it.
        given().queryParam("subject", "orders-value")
                .when().get(registry + "/schemas/ids/" + id)
                .then().statusCode(200).body("schema", containsString("\"User\""));

        // LookupVersion and GetVersion return a SchemaVersion, without Confluent's "deleted".
        given().contentType(CONFLUENT).body("{\"schema\":\"" + USER_V1 + "\"}")
                .when().post(registry + "/subjects/orders-value")
                .then().statusCode(200)
                .body("subject", equalTo("orders-value")).body("version", equalTo(1)).body("id", equalTo(id))
                .body("deleted", nullValue());
        given().when().get(registry + "/subjects/orders-value/versions/latest")
                .then().statusCode(200).body("version", equalTo(1)).body("deleted", nullValue());

        int id2 = register(registry, "orders-value", USER_V2);
        given().when().get(registry + "/subjects/orders-value/versions")
                .then().statusCode(200).body("$", contains(1, 2));
        given().when().get(registry + "/subjects")
                .then().statusCode(200).body("$", contains("orders-value"));
        // The same schema under another subject keeps its id.
        assertEquals(id2, register(registry, "other-value", USER_V2));
    }

    @Test
    void googleSdkEnumsAndFieldsAreAccepted() {
        String registry = registry("sdk_shapes");

        given().contentType("application/json")
                .body("{\"schema\":\"{\\\"type\\\":\\\"object\\\"}\",\"schemaType\":\"JSON\",\"normalize\":false}")
                .when().post(registry + "/subjects/json-value/versions")
                .then().statusCode(200);
        given().when().get(registry + "/subjects/json-value/versions/1")
                .then().statusCode(200).body("schemaType", equalTo("JSON"));
        given().contentType("application/json").body("{\"schema\":\"{\\\"type\\\":\\\"object\\\"}\",\"schemaType\":2}")
                .when().post(registry + "/subjects/json-value")
                .then().statusCode(200).body("version", equalTo(1));
    }

    @Test
    void invalidAndMissingSchemasMapToGoogleErrors() {
        String registry = registry("errors");

        given().contentType(CONFLUENT).body("{\"schema\":\"{not json\"}")
                .when().post(registry + "/subjects/s/versions")
                .then().statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"))
                .body("error.message", containsString("Invalid schema"));
        given().when().get(registry + "/subjects/missing/versions")
                .then().statusCode(404).body("error.message", equalTo("Subject 'missing' not found."));
        given().when().get(registry + "/schemas/ids/999")
                .then().statusCode(404);
        given().contentType(CONFLUENT).body("{}")
                .when().post(registry + "/subjects/s/versions")
                .then().statusCode(400).body("error.message", equalTo("schema is required"));
    }

    @Test
    void versionsAndSubjectsAreSoftDeletedBeforeTheyArePermanentlyDeleted() {
        String registry = registry("deletes");
        register(registry, "s", USER_V1);
        register(registry, "s", USER_V2);

        given().when().delete(registry + "/subjects/s/versions/1?permanent=true")
                .then().statusCode(404);
        given().when().delete(registry + "/subjects/s/versions/1")
                .then().statusCode(200).body(equalTo("1"));
        given().when().get(registry + "/subjects/s/versions")
                .then().body("$", contains(2));
        given().when().get(registry + "/subjects/s/versions?deleted=true")
                .then().body("$", contains(1, 2));

        given().when().delete(registry + "/subjects/s")
                .then().statusCode(200).body("$", contains(2));
        given().when().get(registry + "/subjects")
                .then().body("$", not(hasItem("s")));
        given().when().get(registry + "/subjects?deleted=true")
                .then().body("$", hasItem("s"));
        given().when().delete(registry + "/subjects/s?permanent=true")
                .then().statusCode(200).body("$", contains(1, 2));
        given().when().get(registry + "/subjects?deleted=true")
                .then().body("$", not(hasItem("s")));
    }

    @Test
    void confluentQueryFlagsAreHonoured() {
        String registry = registry("query_flags");
        register(registry, "s", USER_V1);
        given().when().delete(registry + "/subjects/s/versions/1").then().statusCode(200);

        // A Confluent client sends deleted, normalize and verbose as query parameters.
        given().contentType(CONFLUENT).body("{\"schema\":\"" + USER_V1 + "\"}")
                .when().post(registry + "/subjects/s")
                .then().statusCode(404);
        given().contentType(CONFLUENT).body("{\"schema\":\"" + USER_V1 + "\"}")
                .queryParam("deleted", true)
                .when().post(registry + "/subjects/s")
                .then().statusCode(200).body("version", equalTo(1));
        given().contentType(CONFLUENT).body("{\"schema\":\"" + USER_V2 + "\"}")
                .queryParam("normalize", true)
                .when().post(registry + "/subjects/s/versions")
                .then().statusCode(200);
        given().contentType(CONFLUENT).body("{\"schema\":\"" + USER_V2 + "\"}")
                .queryParam("verbose", true)
                .when().post(registry + "/compatibility/subjects/s/versions/latest")
                .then().statusCode(200).body("messages", empty());
    }

    @Test
    void readOnlyModeAlsoGuardsPermanentDeletes() {
        String registry = registry("readonly_deletes");
        register(registry, "s", USER_V1);
        register(registry, "s", USER_V2);
        given().when().delete(registry + "/subjects/s/versions/1").then().statusCode(200);
        given().contentType("application/json").body("{\"mode\":\"READONLY\"}")
                .when().put(registry + "/mode/s").then().statusCode(200);

        given().when().delete(registry + "/subjects/s/versions/1?permanent=true")
                .then().statusCode(400).body("error.message", containsString("read-only"));
        given().when().delete(registry + "/subjects/s")
                .then().statusCode(400).body("error.message", containsString("read-only"));
        given().when().get(registry + "/subjects/s/versions?deleted=true")
                .then().body("$", contains(1, 2));
    }

    @Test
    void configIsReturnedAsASchemaConfig() {
        String registry = registry("config");
        register(registry, "s", USER_V1);

        given().when().get(registry + "/config")
                .then().statusCode(200).body("compatibility", equalTo("BACKWARD"))
                .body("compatibilityLevel", nullValue());
        given().contentType("application/json").body("{\"compatibility\":\"FULL_TRANSITIVE\"}")
                .when().put(registry + "/config/s")
                .then().statusCode(200).body("compatibility", equalTo("FULL_TRANSITIVE"));
        given().when().get(registry + "/config/s")
                .then().statusCode(200).body("compatibility", equalTo("FULL_TRANSITIVE"));
        given().when().delete(registry + "/config/s")
                .then().statusCode(200).body("compatibility", equalTo("FULL_TRANSITIVE"));
        given().when().get(registry + "/config/s")
                .then().statusCode(404);
        given().queryParam("defaultToGlobal", true).when().get(registry + "/config/s")
                .then().statusCode(200).body("compatibility", equalTo("BACKWARD"));

        given().contentType("application/json").body("{\"compatibility\":\"SIDEWAYS\"}")
                .when().put(registry + "/config")
                .then().statusCode(400);
        given().contentType("application/json").body("{\"compatibility\":\"FULL\",\"normalize\":true}")
                .when().put(registry + "/config")
                .then().statusCode(400).body("error.message", containsString("normalize"));
        given().contentType("application/json").body("{}")
                .when().put(registry + "/config")
                .then().statusCode(400);
    }

    @Test
    void modeGuardsWritesAndImportModeAllowsExplicitIds() {
        String registry = registry("mode");

        given().when().get(registry + "/mode").then().statusCode(200).body("mode", equalTo("READWRITE"));
        given().contentType("application/json").body("{\"mode\":\"READONLY\"}")
                .when().put(registry + "/mode/locked")
                .then().statusCode(200).body("mode", equalTo("READONLY"));
        given().contentType(CONFLUENT).body("{\"schema\":\"" + USER_V1 + "\"}")
                .when().post(registry + "/subjects/locked/versions")
                .then().statusCode(400).body("error.message", containsString("read-only"));
        given().when().delete(registry + "/mode/locked")
                .then().statusCode(200).body("mode", equalTo("READONLY"));

        given().contentType(CONFLUENT).body("{\"schema\":\"" + USER_V1 + "\",\"id\":100,\"version\":7}")
                .when().post(registry + "/subjects/imported/versions")
                .then().statusCode(400).body("error.message", containsString("import mode"));
        given().contentType("application/json").body("{\"mode\":\"IMPORT\"}")
                .when().put(registry + "/mode/imported")
                .then().statusCode(200);
        given().contentType(CONFLUENT).body("{\"schema\":\"" + USER_V1 + "\",\"id\":100,\"version\":7}")
                .when().post(registry + "/subjects/imported/versions")
                .then().statusCode(200).body("id", equalTo(100));
        given().when().get(registry + "/subjects/imported/versions/7")
                .then().statusCode(200).body("id", equalTo(100));
    }

    @Test
    void compatibilityChecksPassInMockModeButStillNeedTheSubject() {
        String registry = registry("compat");
        register(registry, "s", USER_V1);

        given().contentType(CONFLUENT).body("{\"schema\":\"" + USER_V2 + "\"}")
                .when().post(registry + "/compatibility/subjects/s/versions/latest")
                .then().statusCode(200).body("is_compatible", equalTo(true));
        given().contentType(CONFLUENT).body("{\"schema\":\"" + USER_V2 + "\",\"verbose\":true}")
                .when().post(registry + "/compatibility/subjects/s/versions")
                .then().statusCode(200).body("is_compatible", equalTo(true));
        given().contentType(CONFLUENT).body("{\"schema\":\"" + USER_V2 + "\"}")
                .when().post(registry + "/compatibility/subjects/s/versions/9")
                .then().statusCode(404);
        // No versions under the subject yet: nothing to be incompatible with.
        given().contentType(CONFLUENT).body("{\"schema\":\"" + USER_V2 + "\"}")
                .when().post(registry + "/compatibility/subjects/fresh/versions")
                .then().statusCode(200).body("is_compatible", equalTo(true));
    }

    @Test
    void aSubjectNameWithAnEncodedSlashIsOneSubject() {
        String registry = registry("encoded");
        given().contentType(CONFLUENT).body("{\"schema\":\"" + USER_V1 + "\"}")
                .urlEncodingEnabled(false)
                .when().post(registry + "/subjects/team%2Forders/versions")
                .then().statusCode(200);
        given().when().get(registry + "/subjects")
                .then().body("$", contains("team/orders"));
    }
}
