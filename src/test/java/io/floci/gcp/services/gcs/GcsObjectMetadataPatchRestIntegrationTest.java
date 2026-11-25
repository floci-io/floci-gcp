package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class GcsObjectMetadataPatchRestIntegrationTest {

    private static final String BUCKET = "object-metadata-patch-bucket";

    @Test
    void patchMergesMetadataAndNullRemovesAKeyWhileUpdateReplacesIt() {
        given()
                .contentType("application/json")
                .body(Map.of("name", BUCKET))
                .when().post("/storage/v1/b?project=test-project")
                .then().statusCode(200);
        given()
                .contentType("text/plain")
                .body("data")
                .when().post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=media&name=obj.txt")
                .then().statusCode(200);
        given()
                .contentType("application/json")
                .body(Map.of("metadata", Map.of("env", "compat", "team", "data")))
                .when().patch("/storage/v1/b/" + BUCKET + "/o/obj.txt")
                .then().statusCode(200);

        given()
                .contentType("application/json")
                .body(Map.of("metadata", Map.of("team", "ml")))
                .when().patch("/storage/v1/b/" + BUCKET + "/o/obj.txt")
                .then().statusCode(200)
                .body("metadata.env", equalTo("compat"))
                .body("metadata.team", equalTo("ml"));

        Map<String, Object> removeTeam = new HashMap<>();
        removeTeam.put("team", null);
        given()
                .contentType("application/json")
                .body(Map.of("metadata", removeTeam))
                .when().patch("/storage/v1/b/" + BUCKET + "/o/obj.txt")
                .then().statusCode(200)
                .body("metadata.env", equalTo("compat"))
                .body("metadata", not(hasKey("team")));

        given()
                .contentType("application/json")
                .body(Map.of("metadata", Map.of("owner", "ops")))
                .when().put("/storage/v1/b/" + BUCKET + "/o/obj.txt")
                .then().statusCode(200)
                .body("metadata.owner", equalTo("ops"))
                .body("metadata", not(hasKey("env")));

        Map<String, Object> removeOwner = new HashMap<>();
        removeOwner.put("owner", null);
        given()
                .contentType("application/json")
                .body(Map.of("metadata", removeOwner))
                .when().patch("/storage/v1/b/" + BUCKET + "/o/obj.txt")
                .then().statusCode(200)
                .body("metadata", nullValue());
    }

    @Test
    void updateDropsMetadataKeysSetToNullAndLeavesOutMetadataLeftEmpty() {
        String bucket = BUCKET + "-update";
        given()
                .contentType("application/json")
                .body(Map.of("name", bucket))
                .when().post("/storage/v1/b?project=test-project")
                .then().statusCode(200);
        given()
                .contentType("text/plain")
                .body("data")
                .when().post("/upload/storage/v1/b/" + bucket + "/o?uploadType=media&name=obj.txt")
                .then().statusCode(200);

        Map<String, Object> keepAndDrop = new HashMap<>();
        keepAndDrop.put("keep", "1");
        keepAndDrop.put("drop", null);
        given()
                .contentType("application/json")
                .body(Map.of("metadata", keepAndDrop))
                .when().put("/storage/v1/b/" + bucket + "/o/obj.txt")
                .then().statusCode(200)
                .body("metadata.keep", equalTo("1"))
                .body("metadata", not(hasKey("drop")));

        Map<String, Object> allNull = new HashMap<>();
        allNull.put("keep", null);
        given()
                .contentType("application/json")
                .body(Map.of("metadata", allNull))
                .when().put("/storage/v1/b/" + bucket + "/o/obj.txt")
                .then().statusCode(200)
                .body("metadata", nullValue());
        given()
                .when().get("/storage/v1/b/" + bucket + "/o/obj.txt")
                .then().statusCode(200)
                .body("metadata", nullValue());
    }

    @Test
    void updateClearsTheWritableFieldsItLeavesOutAndKeepsTheHolds() {
        String bucket = BUCKET + "-replace";
        createObject(bucket);
        Map<String, Object> fields = new HashMap<>();
        fields.put("contentType", "text/csv");
        fields.put("contentDisposition", "attachment");
        fields.put("contentEncoding", "identity");
        fields.put("contentLanguage", "en");
        fields.put("cacheControl", "no-cache");
        fields.put("metadata", Map.of("env", "dev"));
        fields.put("temporaryHold", true);
        fields.put("eventBasedHold", true);
        given()
                .contentType("application/json")
                .body(fields)
                .when().patch("/storage/v1/b/" + bucket + "/o/obj.txt")
                .then().statusCode(200);

        given()
                .contentType("application/json")
                .body(Map.of("contentLanguage", "fr"))
                .when().put("/storage/v1/b/" + bucket + "/o/obj.txt")
                .then().statusCode(200)
                .body("contentLanguage", equalTo("fr"))
                .body("$", not(hasKey("contentType")))
                .body("$", not(hasKey("contentDisposition")))
                .body("$", not(hasKey("contentEncoding")))
                .body("$", not(hasKey("cacheControl")))
                .body("$", not(hasKey("metadata")))
                .body("temporaryHold", equalTo(true))
                .body("eventBasedHold", equalTo(true));
        given()
                .when().get("/storage/v1/b/" + bucket + "/o/obj.txt")
                .then().statusCode(200)
                .body("$", not(hasKey("contentType")))
                .body("$", not(hasKey("cacheControl")))
                .body("temporaryHold", equalTo(true));
    }

    @Test
    void updateMustRestateACustomTimeTheObjectHasAndPatchMayLeaveItOut() {
        String bucket = BUCKET + "-custom-time";
        createObject(bucket);
        given()
                .contentType("application/json")
                .body(Map.of("customTime", "2026-01-01T00:00:00Z"))
                .when().patch("/storage/v1/b/" + bucket + "/o/obj.txt")
                .then().statusCode(200);

        given()
                .contentType("application/json")
                .body(Map.of("contentType", "text/csv"))
                .when().put("/storage/v1/b/" + bucket + "/o/obj.txt")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"))
                .body("error.message", containsString("customTime"));
        given()
                .contentType("application/json")
                .body(Map.of("customTime", "2025-01-01T00:00:00Z"))
                .when().put("/storage/v1/b/" + bucket + "/o/obj.txt")
                .then().statusCode(400);
        given()
                .contentType("application/json")
                .body(Map.of("customTime", "2025-01-01T00:00:00Z"))
                .when().patch("/storage/v1/b/" + bucket + "/o/obj.txt")
                .then().statusCode(400);
        given()
                .contentType("application/json")
                .body(Map.of("contentType", "text/csv"))
                .when().patch("/storage/v1/b/" + bucket + "/o/obj.txt")
                .then().statusCode(200)
                .body("customTime", equalTo("2026-01-01T00:00:00Z"));

        given()
                .contentType("application/json")
                .body(Map.of("customTime", "2026-01-01T00:00:00Z"))
                .when().put("/storage/v1/b/" + bucket + "/o/obj.txt")
                .then().statusCode(200)
                .body("customTime", equalTo("2026-01-01T00:00:00Z"))
                .body("$", not(hasKey("contentType")));
    }

    @Test
    void updateWithAnExplicitNullCustomTimeIsRejectedAndChangesNothing() {
        String bucket = BUCKET + "-null-custom-time";
        createObject(bucket);
        String metageneration = given()
                .contentType("application/json")
                .body(Map.of("customTime", "2026-01-01T00:00:00Z", "contentType", "text/csv"))
                .when().patch("/storage/v1/b/" + bucket + "/o/obj.txt")
                .then().statusCode(200)
                .extract().path("metageneration");

        Map<String, Object> body = new HashMap<>();
        body.put("customTime", null);
        given()
                .contentType("application/json")
                .body(body)
                .when().put("/storage/v1/b/" + bucket + "/o/obj.txt")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
        given()
                .when().get("/storage/v1/b/" + bucket + "/o/obj.txt")
                .then().statusCode(200)
                .body("metageneration", equalTo(metageneration))
                .body("contentType", equalTo("text/csv"))
                .body("customTime", equalTo("2026-01-01T00:00:00Z"));
    }

    @Test
    void updateHonoursIfMetagenerationMatchBeforeClearingAnything() {
        String bucket = BUCKET + "-precondition";
        createObject(bucket);
        String metageneration = given()
                .contentType("application/json")
                .body(Map.of("contentType", "text/csv", "cacheControl", "no-cache"))
                .when().patch("/storage/v1/b/" + bucket + "/o/obj.txt")
                .then().statusCode(200)
                .extract().path("metageneration");
        long current = Long.parseLong(metageneration);

        given()
                .contentType("application/json")
                .body(Map.of("contentLanguage", "fr"))
                .queryParam("ifMetagenerationMatch", current - 1)
                .when().put("/storage/v1/b/" + bucket + "/o/obj.txt")
                .then().statusCode(412);
        given()
                .when().get("/storage/v1/b/" + bucket + "/o/obj.txt")
                .then().statusCode(200)
                .body("metageneration", equalTo(metageneration))
                .body("contentType", equalTo("text/csv"))
                .body("cacheControl", equalTo("no-cache"))
                .body("$", not(hasKey("contentLanguage")));

        given()
                .contentType("application/json")
                .body(Map.of("contentLanguage", "fr"))
                .queryParam("ifMetagenerationMatch", current)
                .when().put("/storage/v1/b/" + bucket + "/o/obj.txt")
                .then().statusCode(200)
                .body("metageneration", equalTo(String.valueOf(current + 1)))
                .body("contentLanguage", equalTo("fr"))
                .body("$", not(hasKey("contentType")))
                .body("$", not(hasKey("cacheControl")));
    }

    @Test
    void updateWithAnEmptyBodyIsRejectedAsInvalid() {
        String bucket = BUCKET + "-empty-body";
        createObject(bucket);

        given()
                .contentType("application/json")
                .when().put("/storage/v1/b/" + bucket + "/o/obj.txt")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
        given()
                .when().get("/storage/v1/b/" + bucket + "/o/obj.txt")
                .then().statusCode(200)
                .body("metageneration", equalTo("1"));
    }

    @Test
    void aWronglyTypedFieldIsRejectedAndLeavesTheObjectAsItWas() {
        String bucket = BUCKET + "-wrong-type";
        createObject(bucket);
        String metageneration = given()
                .contentType("application/json")
                .body(Map.of("contentType", "text/csv", "contentDisposition", "inline",
                        "contentLanguage", "fr", "metadata", Map.of("k", "v")))
                .when().patch("/storage/v1/b/" + bucket + "/o/obj.txt")
                .then().statusCode(200)
                .extract().path("metageneration");

        for (Map<String, Object> body : List.<Map<String, Object>>of(
                Map.of("contentLanguage", 5),
                Map.of("metadata", "x"),
                Map.of("temporaryHold", "yes"))) {
            given()
                    .contentType("application/json")
                    .body(body)
                    .when().put("/storage/v1/b/" + bucket + "/o/obj.txt")
                    .then().statusCode(400)
                    .body("error.status", equalTo("INVALID_ARGUMENT"));
            given()
                    .contentType("application/json")
                    .body(Map.of("contentType", "text/html", "cacheControl", 7))
                    .when().patch("/storage/v1/b/" + bucket + "/o/obj.txt")
                    .then().statusCode(400)
                    .body("error.status", equalTo("INVALID_ARGUMENT"));
            given()
                    .when().get("/storage/v1/b/" + bucket + "/o/obj.txt")
                    .then().statusCode(200)
                    .body("metageneration", equalTo(metageneration))
                    .body("contentType", equalTo("text/csv"))
                    .body("contentDisposition", equalTo("inline"))
                    .body("contentLanguage", equalTo("fr"))
                    .body("metadata.k", equalTo("v"));
        }
    }

    private static void createObject(String bucket) {
        given()
                .contentType("application/json")
                .body(Map.of("name", bucket))
                .when().post("/storage/v1/b?project=test-project")
                .then().statusCode(200);
        given()
                .contentType("text/plain")
                .body("data")
                .when().post("/upload/storage/v1/b/" + bucket + "/o?uploadType=media&name=obj.txt")
                .then().statusCode(200);
    }
}
