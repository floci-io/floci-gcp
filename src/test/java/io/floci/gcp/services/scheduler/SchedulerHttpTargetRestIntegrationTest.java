package io.floci.gcp.services.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private record Received(String uri, String authorization, JsonNode claims) {}

    private Received runAgainstServerAndDecodeBearer(Map<String, Object> target) throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> uri = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            Map<String, Object> httpTarget = new HashMap<>(target);
            uri.set("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
            httpTarget.put("uri", uri.get());
            create("target", httpTarget).then().statusCode(200);
            given().urlEncodingEnabled(false).contentType("application/json").body("{}").post(jobs + "/target:run").then().statusCode(200);
        } finally {
            server.stop(0);
        }
        assertNotNull(authorization.get(), "no Authorization header received");
        assertTrue(authorization.get().startsWith("Bearer "), authorization.get());
        String jwt = authorization.get().substring("Bearer ".length());
        return new Received(uri.get(), authorization.get(),
                new ObjectMapper().readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1])));
    }

    @Test void httpTargetWithOidcTokenSendsBearer() throws Exception {
        Received received = runAgainstServerAndDecodeBearer(Map.of("oidcToken", Map.of("serviceAccountEmail", EMAIL)));

        assertEquals(EMAIL, received.claims().get("email").asText());
        assertEquals(received.uri(), received.claims().get("aud").asText());
    }

    @Test void httpTargetOidcAudienceAndAuthorizationHeaderAreReplaced() throws Exception {
        Received received = runAgainstServerAndDecodeBearer(Map.of(
                "headers", Map.of("Authorization", "Bearer static"),
                "oidcToken", Map.of("serviceAccountEmail", EMAIL, "audience", "https://aud.test")));

        assertNotEquals("Bearer static", received.authorization());
        assertEquals("https://aud.test", received.claims().get("aud").asText());
    }
}
