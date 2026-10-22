package io.floci.gcp.services.compute;

import io.floci.gcp.core.common.ProjectNumbers;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ComputeProjectMetadataIntegrationTest extends ComputeTestSupport {

    @Test void projectCarriesItsNumberAndAnEmptyFingerprintedMetadata() {
        String project = "compute-" + UUID.randomUUID(), root = "/compute/v1/projects/" + project;
        var body = given().get(root).then().statusCode(200)
                .body("kind", equalTo("compute#project"))
                .body("name", equalTo(project))
                .body("id", equalTo(ProjectNumbers.of(project)))
                .body("selfLink", equalTo("https://www.googleapis.com" + root))
                .body("defaultServiceAccount", equalTo(ProjectNumbers.of(project) + "-compute@developer.gserviceaccount.com"))
                .body("commonInstanceMetadata.kind", equalTo("compute#metadata"))
                .extract().jsonPath();
        assertFalse(body.getString("commonInstanceMetadata.fingerprint").isBlank());
        assertNull(body.get("commonInstanceMetadata.items"));
        assertEquals(body.getString("creationTimestamp"),
                given().get(root).then().statusCode(200).extract().jsonPath().getString("creationTimestamp"));
    }

    @Test void setCommonInstanceMetadataFollowsTheTerraformFlow() throws Exception {
        String project = "compute-" + UUID.randomUUID(), root = "/compute/v1/projects/" + project;
        String fingerprint = given().get(root).then().statusCode(200).extract().jsonPath()
                .getString("commonInstanceMetadata.fingerprint");
        var items = List.of(Map.of("key", "ssh-keys", "value", "alice:ssh-ed25519 AAAA alice"),
                Map.of("key", "enable-oslogin", "value", "FALSE"));

        var set = post(root + "/setCommonInstanceMetadata", Map.of("fingerprint", fingerprint, "items", items));
        set.then().statusCode(200)
                .body("kind", equalTo("compute#operation"))
                .body("operationType", equalTo("setCommonInstanceMetadata"))
                .body("targetLink", equalTo("https://www.googleapis.com" + root))
                .body("targetId", equalTo(ProjectNumbers.of(project)))
                .body("selfLink", startsWith("https://www.googleapis.com" + root + "/global/operations/"));
        done(root, set);

        var metadata = given().get(root).then().statusCode(200).extract().jsonPath();
        assertEquals(List.of("ssh-keys", "enable-oslogin"), metadata.getList("commonInstanceMetadata.items.key"));
        assertEquals("alice:ssh-ed25519 AAAA alice", metadata.getString("commonInstanceMetadata.items[0].value"));
        assertNotEquals(fingerprint, metadata.getString("commonInstanceMetadata.fingerprint"));

        String latest = metadata.getString("commonInstanceMetadata.fingerprint");
        done(root, post(root + "/setCommonInstanceMetadata", Map.of("fingerprint", latest)));
        assertNull(given().get(root).then().statusCode(200).extract().jsonPath().get("commonInstanceMetadata.items"));
    }

    @Test void staleOrMissingFingerprintIsRejectedWith412() {
        String root = root();
        post(root + "/setCommonInstanceMetadata", Map.of("items", List.of(Map.of("key", "a", "value", "b"))))
                .then().statusCode(412);
        post(root + "/setCommonInstanceMetadata", Map.of("fingerprint", "c3RhbGU=",
                "items", List.of(Map.of("key", "a", "value", "b")))).then().statusCode(412);
    }

    @Test void invalidItemsAreRejected() {
        String root = root();
        String fingerprint = given().get(root).then().statusCode(200).extract().jsonPath()
                .getString("commonInstanceMetadata.fingerprint");
        for (Object items : List.of(
                List.of(Map.of("key", "bad key", "value", "x")),
                List.of(Map.of("key", "k".repeat(129), "value", "x")),
                List.of(Map.of("key", "missing-value")),
                List.of(Map.of("key", "dup", "value", "1"), Map.of("key", "dup", "value", "2")),
                List.of(Map.of("key", "big", "value", "x".repeat(262145))))) {
            post(root + "/setCommonInstanceMetadata", Map.of("fingerprint", fingerprint, "items", items))
                    .then().statusCode(400);
        }
        assertEquals(fingerprint, given().get(root).then().statusCode(200).extract().jsonPath()
                .getString("commonInstanceMetadata.fingerprint"));
    }

    @Test void requestIdReturnsTheSameOperation() {
        String root = root(), requestId = UUID.randomUUID().toString();
        String fingerprint = given().get(root).then().statusCode(200).extract().jsonPath()
                .getString("commonInstanceMetadata.fingerprint");
        var first = given().contentType("application/json").queryParam("requestId", requestId)
                .body(Map.of("fingerprint", fingerprint)).post(root + "/setCommonInstanceMetadata")
                .then().statusCode(200).extract().jsonPath();
        var second = given().contentType("application/json").queryParam("requestId", requestId)
                .body(Map.of("fingerprint", fingerprint)).post(root + "/setCommonInstanceMetadata")
                .then().statusCode(200).extract().jsonPath();
        assertEquals(first.getString("name"), second.getString("name"));
        assertEquals(requestId, second.getString("clientOperationId"));
    }
}
