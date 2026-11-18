package io.floci.gcp.services.cloudmonitoring;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class AlertEvaluationIntegrationTest {
    private static final String PARENT = "projects/test-project";

    @Inject
    AlertPolicyEvaluator evaluator;

    @Test
    void aBreachingSeriesOpensAnAlertTheRestApiListsAndTheWebhookReceives() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        String metric = "custom.googleapis.com/alerting_" + UUID.randomUUID().toString().replace("-", "");
        String channel = given().contentType("application/json").body(Map.of("type", "webhook_tokenauth",
                        "labels", Map.of("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/hook?auth_token=x")))
                .post("/v3/" + PARENT + "/notificationChannels").then().statusCode(200).extract().path("name");
        String policy = given().contentType("application/json").body(Map.of("displayName", "integration CPU",
                        "notificationChannels", List.of(channel),
                        "conditions", List.of(Map.of("displayName", "cpu", "conditionThreshold", Map.of(
                                "filter", "metric.type=\"" + metric + "\"", "comparison", "COMPARISON_GT",
                                "thresholdValue", 0.9, "duration", "0s")))))
                .post("/v3/" + PARENT + "/alertPolicies").then().statusCode(200).extract().path("name");
        try {
            Instant now = Instant.now();
            given().contentType("application/json").body(Map.of("timeSeries", List.of(Map.of(
                            "metric", Map.of("type", metric),
                            "resource", Map.of("type", "global", "labels", Map.of("project_id", "test-project")),
                            "points", List.of(Map.of("interval", Map.of("endTime", now.minusSeconds(5).toString()),
                                    "value", Map.of("doubleValue", 0.97)))))))
                    .post("/v3/" + PARENT + "/timeSeries").then().statusCode(200);

            evaluator.tick(now);

            String alert = given().queryParam("filter", "policy.name=\"" + policy + "\"")
                    .get("/v3/" + PARENT + "/alerts").then().statusCode(200)
                    .body("alerts", hasSize(1)).body("alerts[0].state", equalTo("OPEN"))
                    .body("alerts[0].metric.type", equalTo(metric))
                    .body("alerts[0].policy.displayName", equalTo("integration CPU"))
                    .body("totalSize", equalTo(1)).extract().path("alerts[0].name");
            given().get("/v3/" + alert).then().statusCode(200).body("name", equalTo(alert))
                    .body("_key", nullValue());
            assertEquals(1, received.size());
            assertTrue(received.getFirst().contains("\"state\":\"open\""), received.getFirst());
            assertTrue(received.getFirst().contains("\"version\":\"1.2\""));
            given().get("/v3/" + PARENT + "/alerts/does-not-exist").then().statusCode(404)
                    .body("error.status", equalTo("NOT_FOUND"));
        } finally {
            given().delete("/v3/" + policy).then().statusCode(200);
            given().queryParam("force", true).delete("/v3/" + channel).then().statusCode(200);
            server.stop(0);
        }
    }
}
