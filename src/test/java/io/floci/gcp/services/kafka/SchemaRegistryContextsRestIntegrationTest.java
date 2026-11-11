package io.floci.gcp.services.kafka;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * {@code ManagedSchemaRegistry} contexts and the raw and reverse lookup methods over REST, in mock
 * mode: the {@code contexts/{context}} binding of every route, separate schema ids per context,
 * {@code GetContext} and {@code ListContexts}, the raw schema bodies, and the lookups by schema id
 * and by referencing schema.
 */
@QuarkusTest
class SchemaRegistryContextsRestIntegrationTest {

    private static final String PROJECT = "kafka-sr-ctx";
    private static final String LOCATION = "us-central1";
    private static final String BASE = "/v1/projects/" + PROJECT + "/locations/" + LOCATION + "/schemaRegistries";
    private static final String NAME_BASE = "projects/" + PROJECT + "/locations/" + LOCATION + "/schemaRegistries";
    private static final String CONFLUENT = "application/vnd.schemaregistry.v1+json";
    private static final String USER = "{\\\"type\\\":\\\"record\\\",\\\"name\\\":\\\"User\\\",\\\"fields\\\":"
            + "[{\\\"name\\\":\\\"a\\\",\\\"type\\\":\\\"string\\\"}]}";
    private static final String ORDER = "{\\\"type\\\":\\\"record\\\",\\\"name\\\":\\\"Order\\\",\\\"fields\\\":"
            + "[{\\\"name\\\":\\\"user\\\",\\\"type\\\":\\\"User\\\"}]}";

    private static String registry(String id) {
        given().contentType("application/json").body("{\"schemaRegistryId\":\"" + id + "\"}")
                .when().post(BASE)
                .then().statusCode(200);
        return BASE + "/" + id;
    }

    private static int register(String path, String body) {
        return given().contentType(CONFLUENT).body(body)
                .when().post(path)
                .then().statusCode(200)
                .extract().path("id");
    }

    @Test
    void eachContextHasItsOwnSubjectsAndSchemaIds() {
        String registry = registry("ctx_main");
        String ctx = registry + "/contexts/.team";

        int defaultId = register(registry + "/subjects/users/versions", "{\"schema\":\"" + USER + "\"}");
        int ctxId = register(ctx + "/subjects/users/versions", "{\"schema\":\"" + ORDER.replace("User", "string")
                + "\"}");
        assertThat("ids restart in each context", ctxId, equalTo(defaultId));

        given().when().get(ctx + "/subjects")
                .then().statusCode(200).body("$", contains(":.team:users"));
        given().when().get(registry + "/subjects")
                .then().statusCode(200).body("$", containsInAnyOrder("users", ":.team:users"));
        given().when().get(ctx + "/subjects/users/versions/1")
                .then().statusCode(200).body("subject", equalTo(":.team:users"))
                .body("schema", containsString("Order"));
        given().when().get(ctx + "/schemas/ids/" + ctxId)
                .then().statusCode(200).body("schema", containsString("Order"));
        given().when().get(registry + "/schemas/ids/" + defaultId)
                .then().statusCode(200).body("schema", containsString("User"));

        // A qualified subject addressed without the context binding lands in its context.
        register(registry + "/subjects/:.team:orders/versions", "{\"schema\":\"" + USER + "\"}");
        given().when().get(ctx + "/subjects")
                .then().body("$", containsInAnyOrder(":.team:users", ":.team:orders"));
    }

    @Test
    void aPermanentDeleteForgetsTheSchemaOnlyInItsOwnContext() {
        String registry = registry("ctx_hard_delete");
        String ctx = registry + "/contexts/.team";
        int defaultId = register(registry + "/subjects/users/versions", "{\"schema\":\"" + USER + "\"}");
        int ctxId = register(ctx + "/subjects/users/versions", "{\"schema\":\"" + USER + "\"}");
        assertThat(ctxId, equalTo(defaultId));

        given().when().delete(ctx + "/subjects/users").then().statusCode(200);
        given().when().delete(ctx + "/subjects/users?permanent=true").then().statusCode(200);

        given().when().get(ctx + "/schemas/ids/" + ctxId).then().statusCode(404);
        given().when().get(registry + "/schemas/ids/" + defaultId)
                .then().statusCode(200).body("schema", containsString("User"));
    }

    @Test
    void contextsAreListedOnTheRegistryAndFetchedOneByOne() {
        String registry = registry("ctx_list");
        String name = NAME_BASE + "/ctx_list";

        given().when().get(registry)
                .then().statusCode(200).body("contexts", contains(name + "/contexts/."));
        register(registry + "/subjects/plain/versions", "{\"schema\":\"" + USER + "\"}");
        register(registry + "/contexts/.billing/subjects/invoices/versions", "{\"schema\":\"" + USER + "\"}");

        given().when().get(registry + "/contexts")
                .then().statusCode(200).body("$", contains(".", ".billing"));
        given().when().get(registry)
                .then().body("contexts", contains(name + "/contexts/.", name + "/contexts/.billing"));
        given().when().get(BASE)
                .then().body("schemaRegistries.find { it.name == '" + name + "' }.contexts",
                        hasItem(name + "/contexts/.billing"));

        given().when().get(registry + "/contexts/.billing")
                .then().statusCode(200)
                .body("name", equalTo(name + "/contexts/.billing"))
                .body("subjects", contains(name + "/contexts/.billing/subjects/invoices"));
        given().when().get(registry + "/contexts/.nowhere")
                .then().statusCode(404).body("error.status", equalTo("NOT_FOUND"));
    }

    @Test
    void contextConfigAndModeStayInTheirContext() {
        String registry = registry("ctx_config");
        String ctx = registry + "/contexts/.strict";
        register(ctx + "/subjects/s/versions", "{\"schema\":\"" + USER + "\"}");

        given().when().get(ctx + "/config").then().statusCode(404);
        given().queryParam("defaultToGlobal", true).when().get(ctx + "/config/s")
                .then().statusCode(200).body("compatibility", equalTo("BACKWARD"));
        given().contentType("application/json").body("{\"compatibility\":\"FULL\"}")
                .when().put(ctx + "/config").then().statusCode(200);
        given().queryParam("defaultToGlobal", true).when().get(ctx + "/config/s")
                .then().statusCode(200).body("compatibility", equalTo("FULL"));
        given().when().get(registry + "/config")
                .then().body("compatibility", equalTo("BACKWARD"));

        given().contentType("application/json").body("{\"mode\":\"READONLY\"}")
                .when().put(ctx + "/mode").then().statusCode(200);
        given().contentType(CONFLUENT).body("{\"schema\":\"" + ORDER.replace("User", "string") + "\"}")
                .when().post(ctx + "/subjects/s/versions")
                .then().statusCode(400).body("error.message", containsString("read-only"));
        register(registry + "/subjects/s/versions", "{\"schema\":\"" + USER + "\"}");
    }

    @Test
    void aContextDoesNotInheritTheRegistryConfigOrMode() {
        String registry = registry("ctx_no_inherit");
        String ctx = registry + "/contexts/.team";
        register(ctx + "/subjects/orders/versions", "{\"schema\":\"" + USER + "\"}");

        given().contentType("application/json").body("{\"compatibility\":\"FULL\"}")
                .when().put(registry + "/config").then().statusCode(200);
        given().queryParam("defaultToGlobal", true).when().get(ctx + "/config/orders")
                .then().statusCode(200).body("compatibility", equalTo("BACKWARD"));
        given().when().get(ctx + "/config").then().statusCode(404);

        given().contentType("application/json").body("{\"mode\":\"READONLY\"}")
                .when().put(registry + "/mode").then().statusCode(200);
        register(ctx + "/subjects/other/versions", "{\"schema\":\"" + USER + "\"}");
        given().contentType(CONFLUENT).body("{\"schema\":\"" + USER + "\"}")
                .when().post(registry + "/subjects/plain/versions")
                .then().statusCode(400).body("error.message", containsString("read-only"));
    }

    @Test
    void rawSchemasComeBackAsTheSchemaText() {
        String registry = registry("raw");
        int id = register(registry + "/subjects/users/versions", "{\"schema\":\"" + USER + "\"}");
        register(registry + "/subjects/proto/versions",
                "{\"schemaType\":\"PROTOBUF\",\"schema\":\"syntax = \\\"proto3\\\"; message M { string a = 1; }\"}");

        given().when().get(registry + "/schemas/ids/" + id + "/schema")
                .then().statusCode(200).contentType(startsWith("application/json"))
                .body("name", equalTo("User")).body("type", equalTo("record"));
        given().when().get(registry + "/subjects/users/versions/1/schema")
                .then().statusCode(200).body("name", equalTo("User"));
        given().when().get(registry + "/subjects/proto/versions/latest/schema")
                .then().statusCode(200).contentType(startsWith("text/plain"))
                .body(startsWith("syntax = \"proto3\""));
        given().when().get(registry + "/schemas/ids/999/schema").then().statusCode(404);
    }

    @Test
    void schemasAreFoundByIdAndByTheSchemasThatReferenceThem() {
        String registry = registry("lookups");
        int userId = register(registry + "/subjects/users/versions", "{\"schema\":\"" + USER + "\"}");
        register(registry + "/subjects/users-copy/versions", "{\"schema\":\"" + USER + "\"}");
        int orderId = register(registry + "/subjects/orders/versions", "{\"schema\":\"" + ORDER + "\","
                + "\"references\":[{\"name\":\"User\",\"subject\":\"users\",\"version\":1}]}");

        given().when().get(registry + "/schemas/ids/" + userId + "/versions")
                .then().statusCode(200)
                .body("subject", contains("users", "users-copy"))
                .body("version", contains(1, 1));
        given().queryParam("subject", "users-copy").when().get(registry + "/schemas/ids/" + userId + "/subjects")
                .then().statusCode(200).body("$", contains("users-copy"));
        given().when().get(registry + "/schemas/ids/" + userId + "/subjects")
                .then().body("$", contains("users", "users-copy"));
        given().when().get(registry + "/subjects/users/versions/1/referencedby")
                .then().statusCode(200).body("$", contains(orderId));
        given().when().get(registry + "/subjects/orders/versions/1/referencedby")
                .then().statusCode(200).body("$", not(hasItem(orderId)));
        given().when().get(registry + "/schemas/ids/" + orderId)
                .then().body("references[0].subject", equalTo("users"));
        given().when().get(registry + "/schemas/types")
                .then().statusCode(200).body("$", containsInAnyOrder("AVRO", "JSON", "PROTOBUF"));
        given().when().get(registry + "/schemas/ids/999/versions").then().statusCode(404);
        given().when().get(registry + "/contexts/.none/schemas/ids/" + userId)
                .then().statusCode(404).body("error.message", containsString("not found"));
    }
}
