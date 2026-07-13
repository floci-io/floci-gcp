package io.floci.gcp.services.cloudmonitoring;

import io.floci.gcp.core.common.TokenRegistry;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(CloudMonitoringIamEnforcementIntegrationTest.CtfIamProfile.class)
class CloudMonitoringIamEnforcementIntegrationTest {

    private static final String PROJECT = "test-project";
    private static final String BASE = "/v3/projects/" + PROJECT;
    private static final String TIME_SERIES = BASE + "/timeSeries";
    private static final String METRIC_DESCRIPTORS = BASE + "/metricDescriptors";
    private static final String PLAYER_EMAIL = "player@test-project.iam.gserviceaccount.com";
    private static final String PLAYER_TOKEN = "player-token";
    private static final String ROOT_TOKEN = "root-token-test";

    @Inject
    TokenRegistry tokenRegistry;

    @Inject
    IamService iamService;

    @BeforeEach
    void setUp() {
        tokenRegistry.register(PLAYER_TOKEN, PLAYER_EMAIL);
        iamService.setPolicy("projects/" + PROJECT, new StoredPolicy());
    }

    @Test
    void playerWithoutBindingIsDeniedListTimeSeries() {
        Instant end = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant start = end.minusSeconds(3600);

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .queryParam("filter", "metric.type=\"custom.googleapis.com/ctf\"")
                .queryParam("interval.startTime", start.toString())
                .queryParam("interval.endTime", end.toString())
                .when().get(TIME_SERIES)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", equalTo(
                        "Permission 'monitoring.timeSeries.list' denied on resource 'projects/"
                                + PROJECT + "'."));
    }

    @Test
    void playerWithMonitoringViewerCanListMetricDescriptorsButNotCreateTimeSeries() {
        bindPlayer("roles/monitoring.viewer");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(METRIC_DESCRIPTORS)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body("""
                        {
                          "timeSeries": [{
                            "metric": {"type": "custom.googleapis.com/ctf"},
                            "resource": {"type": "global"},
                            "points": [{
                              "interval": {"endTime": "%s"},
                              "value": {"doubleValue": 1.0}
                            }]
                          }]
                        }
                        """.formatted(Instant.now().truncatedTo(ChronoUnit.SECONDS)))
                .when().post(TIME_SERIES)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void playerWithMetricWriterCanCreateTimeSeriesButNotList() {
        bindPlayer("roles/monitoring.metricWriter");

        Instant end = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant start = end.minusSeconds(3600);

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body("""
                        {
                          "timeSeries": [{
                            "metric": {"type": "custom.googleapis.com/ctf_writer"},
                            "resource": {"type": "global"},
                            "points": [{
                              "interval": {"endTime": "%s"},
                              "value": {"doubleValue": 2.0}
                            }]
                          }]
                        }
                        """.formatted(end))
                .when().post(TIME_SERIES)
                .then()
                .statusCode(200);

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .queryParam("filter", "metric.type=\"custom.googleapis.com/ctf_writer\"")
                .queryParam("interval.startTime", start.toString())
                .queryParam("interval.endTime", end.toString())
                .when().get(TIME_SERIES)
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void playerWithMonitoringAdminCanListTimeSeries() {
        Instant end = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant start = end.minusSeconds(3600);
        bindPlayer("roles/monitoring.admin");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .queryParam("filter", "metric.type=\"custom.googleapis.com/ctf\"")
                .queryParam("interval.startTime", start.toString())
                .queryParam("interval.endTime", end.toString())
                .when().get(TIME_SERIES)
                .then()
                .statusCode(200);
    }

    @Test
    void rootTokenAlwaysCanListMetricDescriptors() {
        given()
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .when().get(METRIC_DESCRIPTORS)
                .then()
                .statusCode(200);
    }

    private void bindPlayer(String role) {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of(
                "role", role,
                "members", List.of("serviceAccount:" + PLAYER_EMAIL))));
        iamService.setPolicy("projects/" + PROJECT, policy);
    }

    public static class CtfIamProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-gcp.auth.validate-tokens", "true",
                    "floci-gcp.auth.root-service-account",
                    "operator@test-project.iam.gserviceaccount.com",
                    "floci-gcp.auth.root-access-token", ROOT_TOKEN,
                    "floci-gcp.services.iam.enforcement-enabled", "true",
                    "floci-gcp.services.iam.strict-enforcement-enabled", "true");
        }
    }
}
