package io.floci.gcp.services.cloudsql;

import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@QuarkusTest
@TestProfile(CloudSqlDataPlaneIntegrationTest.DataPlaneProfile.class)
class CloudSqlDataPlaneIntegrationTest {

    private static final RestAssuredConfig DATA_PLANE_TIMEOUT = RestAssuredConfig.config()
            .httpClient(HttpClientConfig.httpClientConfig()
                    .setParam("http.connection.timeout", 300_000)
                    .setParam("http.socket.timeout", 300_000));

    @Test
    void cloudSqlInstancesReturnUsablePostgresEndpointsForLatestPostgresVersions() throws Exception {
        for (String databaseVersion : List.of("POSTGRES_16", "POSTGRES_17", "POSTGRES_18")) {
            cloudSqlInstanceReturnsUsablePostgresEndpoint(databaseVersion);
        }
    }

    private void cloudSqlInstanceReturnsUsablePostgresEndpoint(String databaseVersion) throws Exception {
        assumeTrue(dockerAvailable(), "Docker is required for Cloud SQL PostgreSQL data-plane tests");

        String majorVersion = databaseVersion.substring("POSTGRES_".length());
        String project = "sql-dataplane-" + majorVersion;
        String instance = "pg-dataplane-" + majorVersion;
        String database = "appdb";
        String user = "app";
        String password = "secret";
        String base = "/v1/projects/" + project + "/instances/" + instance;

        try {
            given()
                    .config(DATA_PLANE_TIMEOUT)
                    .contentType("application/json")
                    .body("""
                            {
                              "name": "%s",
                              "databaseVersion": "%s",
                              "region": "us-central1",
                              "settings": {"tier": "db-custom-1-3840"}
                            }
                            """.formatted(instance, databaseVersion))
                    .when().post("/v1/projects/" + project + "/instances")
                    .then()
                    .statusCode(200)
                    .body("kind", equalTo("sql#operation"))
                    .body("status", equalTo("DONE"));

            given()
                    .config(DATA_PLANE_TIMEOUT)
                    .when().get(base)
                    .then()
                    .statusCode(200)
                    .body("databaseVersion", equalTo(databaseVersion));

            given()
                    .config(DATA_PLANE_TIMEOUT)
                    .contentType("application/json")
                    .body("{\"name\":\"" + database + "\"}")
                    .when().post(base + "/databases")
                    .then()
                    .statusCode(200)
                    .body("operationType", equalTo("CREATE_DATABASE"));

            given()
                    .config(DATA_PLANE_TIMEOUT)
                    .contentType("application/json")
                    .body("{\"name\":\"" + user + "\",\"password\":\"" + password + "\"}")
                    .when().post(base + "/users")
                    .then()
                    .statusCode(200)
                    .body("operationType", equalTo("CREATE_USER"));

            String host = given()
                    .config(DATA_PLANE_TIMEOUT)
                    .when().get(base + "/connectSettings")
                    .then()
                    .statusCode(200)
                    .body("ipAddresses[0].type", equalTo("PRIMARY"))
                    .extract().path("ipAddresses[0].ipAddress");
            Integer port = given()
                    .config(DATA_PLANE_TIMEOUT)
                    .when().get(base + "/connectSettings")
                    .then()
                    .statusCode(200)
                    .extract().path("ipAddresses[0].port");

            try (var connection = DriverManager.getConnection(
                    "jdbc:postgresql://" + host + ":" + port + "/" + database, user, password);
                 var statement = connection.createStatement()) {
                try (var result = statement.executeQuery("SHOW server_version")) {
                    assertTrue(result.next(), "expected server version row");
                    assertTrue(result.getString(1).startsWith(majorVersion + "."));
                }
                statement.execute("CREATE TABLE phase2_probe (id INT PRIMARY KEY, note TEXT)");
                statement.execute("INSERT INTO phase2_probe (id, note) VALUES (1, 'ready')");
                try (var result = statement.executeQuery("SELECT note FROM phase2_probe WHERE id = 1")) {
                    assertTrue(result.next(), "expected inserted row");
                    assertEquals("ready", result.getString(1));
                }
            }
        } finally {
            given()
                    .config(DATA_PLANE_TIMEOUT)
                    .when().delete(base)
                    .then()
                    .statusCode(org.hamcrest.Matchers.anyOf(equalTo(200), equalTo(404)));
        }
    }

    private static boolean dockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "info")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static class DataPlaneProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.cloudsql.data-plane-enabled", "true");
        }
    }
}
