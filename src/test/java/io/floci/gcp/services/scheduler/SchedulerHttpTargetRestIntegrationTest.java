package io.floci.gcp.services.scheduler;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class SchedulerHttpTargetRestIntegrationTest {
    private static final String EMAIL = "sa@p.iam.gserviceaccount.com";

    private final String jobs = "/v1/projects/sched-" + UUID.randomUUID() + "/locations/us-central1/jobs";

    private Response create(String id, Map<String, Object> httpTarget) {
        return given().contentType("application/json")
                .body(Map.of("name", id, "schedule", "0 0 1 1 *", "httpTarget", httpTarget)).post(jobs);
    }

    private Response patch(String id, String mask, Map<String, Object> body) {
        return given().contentType("application/json").body(body).patch(jobs + "/" + id + (mask == null ? "" : "?updateMask=" + mask));
    }

    private JsonPath get(String id) {
        return given().get(jobs + "/" + id).then().statusCode(200).extract().jsonPath();
    }

    @Test void oidcTokenRoundTripsAndGcloudNestedMasksUpdateIt() {
        create("oidc", Map.of("uri", "https://example.test/a",
                "oidcToken", Map.of("serviceAccountEmail", EMAIL, "audience", "aud"))).then().statusCode(200);
        assertEquals(EMAIL, get("oidc").getString("httpTarget.oidcToken.serviceAccountEmail"));
        assertEquals("aud", get("oidc").getString("httpTarget.oidcToken.audience"));

        patch("oidc", "httpTarget.oidcToken.audience", Map.of("httpTarget", Map.of("oidcToken", Map.of("audience", "new"))))
                .then().statusCode(200);
        assertEquals("new", get("oidc").getString("httpTarget.oidcToken.audience"));
        assertEquals(EMAIL, get("oidc").getString("httpTarget.oidcToken.serviceAccountEmail"));
        assertEquals("https://example.test/a", get("oidc").getString("httpTarget.uri"));

        patch("oidc", "httpTarget.oauthToken.serviceAccountEmail",
                Map.of("httpTarget", Map.of("oauthToken", Map.of("serviceAccountEmail", EMAIL)))).then().statusCode(200);
        assertEquals(EMAIL, get("oidc").getString("httpTarget.oauthToken.serviceAccountEmail"));
        assertNull(get("oidc").get("httpTarget.oidcToken"));

        patch("oidc", "httpTarget.oauthToken.serviceAccountEmail,httpTarget.oauthToken.scope", Map.of()).then().statusCode(200);
        assertNull(get("oidc").get("httpTarget.oauthToken"));
        assertEquals("https://example.test/a", get("oidc").getString("httpTarget.uri"));
    }

    @Test void patchWithoutMaskReplacesTheTargetLikeTerraform() {
        create("full", Map.of("uri", "https://example.test/a",
                "oauthToken", Map.of("serviceAccountEmail", EMAIL, "scope", "s"))).then().statusCode(200);
        patch("full", null, Map.of("schedule", "0 0 1 2 *", "httpTarget", Map.of("uri", "https://example.test/b",
                "oidcToken", Map.of("serviceAccountEmail", EMAIL)))).then().statusCode(200);
        JsonPath job = get("full");
        assertEquals("https://example.test/b", job.getString("httpTarget.uri"));
        assertEquals(EMAIL, job.getString("httpTarget.oidcToken.serviceAccountEmail"));
        assertNull(job.get("httpTarget.oauthToken"));
    }

    @Test void retryConfigIsReturnedOnlyWhenSet() {
        create("plain", Map.of("uri", "https://example.test/a")).then().statusCode(200);
        assertNull(get("plain").get("retryConfig"));

        given().contentType("application/json").body(Map.of("name", "retried", "schedule", "0 0 1 1 *",
                "httpTarget", Map.of("uri", "https://example.test/a"), "retryConfig", Map.of("retryCount", 2)))
                .post(jobs).then().statusCode(200);
        assertEquals(2, get("retried").getInt("retryConfig.retryCount"));
    }

    @Test void invalidUriIsRejectedAndLeavesTheJobUnchanged() {
        create("bad", Map.of("uri", "ftp://example.test")).then().statusCode(400);
        given().get(jobs + "/bad").then().statusCode(404);
        create("kept", Map.of("uri", "https://example.test/a")).then().statusCode(200);
        patch("kept", "description,httpTarget.uri", Map.of("description", "changed", "httpTarget", Map.of("uri", "nope")))
                .then().statusCode(400);
        JsonPath job = get("kept");
        assertEquals("https://example.test/a", job.getString("httpTarget.uri"));
        assertNull(job.get("description"));
    }
}
