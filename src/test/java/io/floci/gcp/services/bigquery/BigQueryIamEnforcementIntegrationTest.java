package io.floci.gcp.services.bigquery;

import io.floci.gcp.core.common.TokenRegistry;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(BigQueryIamEnforcementIntegrationTest.CtfIamProfile.class)
class BigQueryIamEnforcementIntegrationTest {

    private static final String PROJECT = "test-project";
    private static final String BASE = "/bigquery/v2/projects/" + PROJECT;
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
    void playerWithoutBindingIsDeniedListDatasets() {
        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(BASE + "/datasets")
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void playerWithBigQueryAdminCanListDatasets() {
        bindPlayer("roles/bigquery.admin");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(BASE + "/datasets")
                .then()
                .statusCode(200);
    }

    @Test
    void playerWithJobUserIsDeniedListDatasets() {
        bindPlayer("roles/bigquery.jobUser");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(BASE + "/datasets")
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void playerWithJobUserCanRunQuery() {
        String datasetId = "ctf_ds_" + UUID.randomUUID().toString().substring(0, 8);
        String tableId = "ctf_t_" + UUID.randomUUID().toString().substring(0, 8);
        seedDatasetAndTable(datasetId, tableId);
        bindPlayer("roles/bigquery.jobUser");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body(Map.of(
                        "query", "SELECT name FROM " + datasetId + "." + tableId,
                        "useLegacySql", false))
                .when().post(BASE + "/queries")
                .then()
                .statusCode(200);
    }

    @Test
    void playerWithDataViewerCanListButNotCreateDataset() {
        bindPlayer("roles/bigquery.dataViewer");

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .when().get(BASE + "/datasets")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", "Bearer " + PLAYER_TOKEN)
                .contentType("application/json")
                .body(Map.of("datasetReference", Map.of("datasetId", "denied-create")))
                .when().post(BASE + "/datasets")
                .then()
                .statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"));
    }

    @Test
    void rootTokenAlwaysCanListDatasets() {
        given()
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .when().get(BASE + "/datasets")
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

    private void seedDatasetAndTable(String datasetId, String tableId) {
        given()
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .contentType("application/json")
                .body(Map.of("datasetReference", Map.of("datasetId", datasetId)))
                .when().post(BASE + "/datasets")
                .then()
                .statusCode(200);

        given()
                .header("Authorization", "Bearer " + ROOT_TOKEN)
                .contentType("application/json")
                .body(Map.of(
                        "tableReference", Map.of("tableId", tableId),
                        "schema", Map.of("fields", List.of(Map.of("name", "name", "type", "STRING")))))
                .when().post(BASE + "/datasets/" + datasetId + "/tables")
                .then()
                .statusCode(200);
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
