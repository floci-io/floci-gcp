package io.floci.gcp.services.serviceusage;

import com.google.datastore.v1.LookupRequest;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * http.proto defines the verb as a literal {@code ":" LITERAL} and expands a colon inside a single-segment path
 * variable as {@code %3A}, so {@code services/x%3Aenable} names a resource {@code x:enable} and is not the
 * {@code :enable} verb. No route serves such a name, so it is a plain 404 that changes nothing, and it is never
 * answered by another API's catch-all.
 */
@QuarkusTest
class ServiceUsageEncodedVerbIntegrationTest {

    private static final List<String> ENCODED_COLONS = List.of("%3A", "%3a");

    @Test
    void encodedEnableAndDisableAreNotFoundAndLeaveTheStateAlone() {
        String project = "su-encoded-state";
        String service = "/v1/projects/" + project + "/services/pubsub.googleapis.com";

        for (String colon : ENCODED_COLONS) {
            postJson(service + colon + "enable", "{}").then().statusCode(404).body(containsString("Resource not found"));
        }
        assertState(service, "DISABLED");

        postJson(service + ":enable", "{}").then().statusCode(200);
        assertState(service, "ENABLED");

        for (String colon : ENCODED_COLONS) {
            postJson(service + colon + "disable", "{}").then().statusCode(404).body(containsString("Resource not found"));
        }
        assertState(service, "ENABLED");

        postJson(service + ":disable", "{}").then().statusCode(200);
        assertState(service, "DISABLED");
    }

    @Test
    void encodedBatchRoutesAreNotFound() {
        String project = "su-encoded-batch";
        String services = "/v1/projects/" + project + "/services";
        String batchEnable = "{\"serviceIds\":[\"logging.googleapis.com\"]}";
        String batchGet = "batchGet?names=projects/" + project + "/services/logging.googleapis.com";

        for (String colon : ENCODED_COLONS) {
            postJson(services + colon + "batchEnable", batchEnable).then().statusCode(404).body(containsString("Resource not found"));
            given().urlEncodingEnabled(false)
                    .when().get(services + colon + batchGet)
                    .then().statusCode(404);
        }

        postJson(services + ":batchEnable", batchEnable).then().statusCode(200);
        given().urlEncodingEnabled(false)
                .when().get(services + ":" + batchGet)
                .then().statusCode(200)
                .body("services[0].state", equalTo("ENABLED"));
    }

    @Test
    void literalVerbRoutesOfOtherServicesBehaveTheSame() {
        String project = "su-encoded-others";
        String root = "/v1/projects/" + project;
        postJson(root + "/secrets?secretId=s1", "{\"replication\":{\"automatic\":{}}}").then().statusCode(200);
        postJson(root + "/locations/global/keyRings?keyRingId=r1", "{}").then().statusCode(200);
        String key = root + "/locations/global/keyRings/r1/cryptoKeys/k1";
        postJson(root + "/locations/global/keyRings/r1/cryptoKeys?cryptoKeyId=k1",
                "{\"purpose\":\"ENCRYPT_DECRYPT\"}").then().statusCode(200);
        given().contentType("application/json").body(Map.of())
                .when().put(root + "/topics/t1")
                .then().statusCode(200);
        Map<String, String> verbs = Map.of(
                root + "/topics/t1|publish", "{\"messages\":[{\"data\":\"eA==\"}]}",
                root + "/secrets/s1|addVersion", "{\"payload\":{\"data\":\"eA==\"}}",
                key + "|encrypt", "{\"plaintext\":\"eA==\"}");

        verbs.forEach((target, body) -> {
            String resource = target.substring(0, target.indexOf('|'));
            String verb = target.substring(target.indexOf('|') + 1);
            for (String colon : ENCODED_COLONS) {
                postJson(resource + colon + verb, body).then().statusCode(404).body(containsString("Resource not found"));
            }
            postJson(resource + ":" + verb, body).then().statusCode(200);
        });
    }

    @Test
    void encodedVerbOutsideTheProjectsCatchAllIsAPlainNotFound() {
        String body = "{\"logName\":\"projects/su-encoded-logging/logs/a\",\"entries\":[{\"textPayload\":\"a\"}]}";

        for (String colon : ENCODED_COLONS) {
            postJson("/v2/entries" + colon + "write", body).then().statusCode(404)
                    .body(containsString("Resource not found"));
        }
        postJson("/v2/entries:write", body).then().statusCode(200);
    }

    @Test
    void catchAllRoutesThatParseTheDecodedPathAcceptBothForms() {
        String project = "su-encoded-catchall";
        String email = postJson("/v1/projects/" + project + "/serviceAccounts", "{\"accountId\":\"encoded-sa\"}")
                .then().statusCode(200)
                .extract().path("email");
        String account = "/v1/projects/" + project + "/serviceAccounts/" + email;
        byte[] lookup = LookupRequest.newBuilder().setProjectId(project).build().toByteArray();

        for (String colon : List.of(":", "%3A", "%3a")) {
            postJson(account + colon + "getIamPolicy", "{}").then().statusCode(200)
                    .body("etag", equalTo("ACAB"));
            given().urlEncodingEnabled(false).contentType("application/x-protobuf").body(lookup)
                    .when().post("/v1/projects/" + project + colon + "lookup")
                    .then().statusCode(200);
        }
    }

    private static io.restassured.response.Response postJson(String path, String body) {
        return given().urlEncodingEnabled(false).contentType("application/json").body(body)
                .when().post(path);
    }

    private static void assertState(String service, String state) {
        given().when().get(service)
                .then().statusCode(200)
                .body("state", equalTo(state));
    }
}
