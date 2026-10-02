package io.floci.gcp.services.cloudsql;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class CloudSqlRestIntegrationTest {

    @Test
    void cloudSqlPostgresInstanceDatabaseUserAndOperationsUseSqlAdminJsonShapes() {
        String project = "sql-it-1";
        String instance = "pg-main";
        String base = "/v1/projects/" + project;

        String operation = given()
                .contentType("application/json")
                .body("""
                        {
                          "name": "pg-main",
                          "databaseVersion": "POSTGRES_18",
                          "region": "us-central1",
                          "settings": {"tier": "db-custom-1-3840"}
                        }
                        """)
                .when().post(base + "/instances")
                .then()
                .statusCode(200)
                .body("kind", equalTo("sql#operation"))
                .body("status", equalTo("DONE"))
                .body("operationType", equalTo("CREATE"))
                .body("targetId", equalTo(instance))
                .body("targetProject", equalTo(project))
                .extract().path("name");

        given()
                .when().get(base + "/operations/" + operation)
                .then()
                .statusCode(200)
                .body("kind", equalTo("sql#operation"))
                .body("name", equalTo(operation))
                .body("status", equalTo("DONE"));

        given()
                .when().get(base + "/instances/" + instance)
                .then()
                .statusCode(200)
                .body("kind", equalTo("sql#instance"))
                .body("name", equalTo(instance))
                .body("databaseVersion", equalTo("POSTGRES_18"))
                .body("state", equalTo("RUNNABLE"))
                .body("connectionName", equalTo(project + ":us-central1:" + instance))
                .body("settings.tier", equalTo("db-custom-1-3840"));

        given()
                .when().get(base + "/instances/" + instance + "/connectSettings")
                .then()
                .statusCode(200)
                .body("kind", equalTo("sql#connectSettings"))
                .body("backendType", equalTo("SECOND_GEN"))
                .body("region", equalTo("us-central1"));

        given()
                .when().get(base + "/instances")
                .then()
                .statusCode(200)
                .body("kind", equalTo("sql#instancesList"))
                .body("items.name", hasItem(instance));

        given()
                .when().get(base + "/tiers")
                .then()
                .statusCode(200)
                .body("kind", equalTo("sql#tiersList"))
                .body("items.tier", hasItem("db-custom-1-3840"));

        given()
                .when().get("/v1/flags")
                .then()
                .statusCode(200)
                .body("kind", equalTo("sql#flagsList"))
                .body("items.name", hasItem("max_connections"));

        given()
                .when().get(base + "/instances/" + instance + "/databases")
                .then()
                .statusCode(200)
                .body("kind", equalTo("sql#databasesList"))
                .body("items.name", hasItem("postgres"));

        given()
                .when().delete(base + "/instances/" + instance + "/databases/postgres")
                .then()
                .statusCode(400)
                .body("error.status", equalTo("FAILED_PRECONDITION"));

        given()
                .contentType("application/json")
                .body("{\"name\":\"appdb\"}")
                .when().post(base + "/instances/" + instance + "/databases")
                .then()
                .statusCode(200)
                .body("kind", equalTo("sql#operation"))
                .body("operationType", equalTo("CREATE_DATABASE"));

        given()
                .when().get(base + "/instances/" + instance + "/databases/appdb")
                .then()
                .statusCode(200)
                .body("kind", equalTo("sql#database"))
                .body("name", equalTo("appdb"))
                .body("instance", equalTo(instance))
                .body("charset", equalTo("UTF8"));

        given()
                .contentType("application/json")
                .body("{\"collation\":\"en_US.UTF8\"}")
                .when().put(base + "/instances/" + instance + "/databases/appdb")
                .then()
                .statusCode(200)
                .body("kind", equalTo("sql#operation"))
                .body("operationType", equalTo("UPDATE_DATABASE"));

        given()
                .contentType("application/json")
                .body("{\"charset\":\"UTF8\"}")
                .when().patch(base + "/instances/" + instance + "/databases/appdb")
                .then()
                .statusCode(200)
                .body("kind", equalTo("sql#operation"))
                .body("operationType", equalTo("UPDATE_DATABASE"));

        given()
                .contentType("application/json")
                .body("{\"name\":\"app\",\"password\":\"secret\"}")
                .when().post(base + "/instances/" + instance + "/users")
                .then()
                .statusCode(200)
                .body("kind", equalTo("sql#operation"))
                .body("operationType", equalTo("CREATE_USER"));

        given()
                .contentType("application/json")
                .body("{\"name\":\"hosted\",\"host\":\"%\",\"password\":\"secret\"}")
                .when().post(base + "/instances/" + instance + "/users")
                .then()
                .statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));

        given()
                .when().get(base + "/instances/" + instance + "/users")
                .then()
                .statusCode(200)
                .body("kind", equalTo("sql#usersList"))
                .body("items.name", hasItem("app"))
                // the built-in admin role is listed like real Cloud SQL does
                .body("items.find { it.name == 'postgres' }.type", equalTo("BUILT_IN"))
                .body("items.password", everyItem(nullValue()));

        given()
                .when().get(base + "/instances/" + instance + "/users/app")
                .then()
                .statusCode(200)
                .body("kind", equalTo("sql#user"))
                .body("name", equalTo("app"))
                .body("password", nullValue());

        given()
                .contentType("application/json")
                .body("{\"password\":\"new-secret\"}")
                .when().put(base + "/instances/" + instance + "/users?name=app")
                .then()
                .statusCode(200)
                .body("kind", equalTo("sql#operation"))
                .body("operationType", equalTo("UPDATE_USER"));

        given()
                .when().get(base + "/instances/" + instance + "/users/app")
                .then()
                .statusCode(200)
                .body("kind", equalTo("sql#user"))
                .body("name", equalTo("app"))
                .body("password", nullValue());

        given()
                .contentType("application/json")
                .body("{\"settings\":{\"userLabels\":{\"env\":\"test\"}}}")
                .when().patch(base + "/instances/" + instance)
                .then()
                .statusCode(200)
                .body("operationType", equalTo("UPDATE"));

        given()
                .contentType("application/json")
                .body("{\"settings\":{\"userLabels\":{\"env\":\"put-test\"}}}")
                .when().put(base + "/instances/" + instance)
                .then()
                .statusCode(200)
                .body("operationType", equalTo("UPDATE"));

        given()
                .when().get(base + "/instances/" + instance)
                .then()
                .statusCode(200)
                .body("settings.tier", equalTo("db-custom-1-3840"))
                .body("settings.userLabels.env", equalTo("put-test"));

        given()
                .when().get(base + "/operations")
                .then()
                .statusCode(200)
                .body("kind", equalTo("sql#operationsList"))
                .body("items.name", hasItem(operation));

        given()
                .when().delete(base + "/instances/" + instance + "/users?name=app")
                .then()
                .statusCode(200)
                .body("operationType", equalTo("DELETE_USER"));

        given()
                .when().delete(base + "/instances/" + instance + "/databases/appdb")
                .then()
                .statusCode(200)
                .body("operationType", equalTo("DELETE_DATABASE"));

        given()
                .when().delete(base + "/instances/" + instance)
                .then()
                .statusCode(200)
                .body("operationType", equalTo("DELETE"));

        given()
                .when().get(base + "/instances/" + instance)
                .then()
                .statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"));
    }

    @Test
    void cloudSqlLocationPreferenceDrivesServingZonesAndIsEchoedBack() {
        String base = "/v1/projects/sql-it-location";

        given()
                .contentType("application/json")
                .body("""
                        {
                          "name": "pg-ha",
                          "databaseVersion": "POSTGRES_16",
                          "region": "europe-west1",
                          "settings": {
                            "tier": "db-custom-1-3840",
                            "availabilityType": "REGIONAL",
                            "locationPreference": {"zone": "europe-west1-c", "secondaryZone": "europe-west1-d"}
                          }
                        }
                        """)
                .when().post(base + "/instances")
                .then()
                .statusCode(200);

        given()
                .when().get(base + "/instances/pg-ha")
                .then()
                .statusCode(200)
                .body("gceZone", equalTo("europe-west1-c"))
                .body("secondaryGceZone", equalTo("europe-west1-d"))
                .body("settings.locationPreference.zone", equalTo("europe-west1-c"))
                .body("settings.locationPreference.secondaryZone", equalTo("europe-west1-d"))
                .body("settings.locationPreference.kind", equalTo("sql#locationPreference"));

        given()
                .contentType("application/json")
                .body("""
                        {"settings": {"locationPreference": {"zone": "europe-west1-b", "secondaryZone": "no_secondary_zone"}}}
                        """)
                .when().patch(base + "/instances/pg-ha")
                .then()
                .statusCode(200);

        given()
                .when().get(base + "/instances/pg-ha")
                .then()
                .statusCode(200)
                .body("gceZone", equalTo("europe-west1-b"))
                .body("secondaryGceZone", nullValue())
                .body("settings.locationPreference.secondaryZone", equalTo("no_secondary_zone"));

        given()
                .contentType("application/json")
                .body("""
                        {"name": "pg-plain", "databaseVersion": "POSTGRES_16"}
                        """)
                .when().post(base + "/instances")
                .then()
                .statusCode(200);

        given()
                .when().get(base + "/instances/pg-plain")
                .then()
                .statusCode(200)
                .body("region", equalTo("us-central1"))
                .body("gceZone", equalTo("us-central1-a"))
                .body("secondaryGceZone", nullValue());
    }

    @Test
    void cloudSqlSupportsV1Beta4AndLegacySqlBasePaths() {
        String project = "sql-it-legacy";

        given()
                .contentType("application/json")
                .body("{\"name\":\"pg-v1beta4\",\"databaseVersion\":\"POSTGRES_16\"}")
                .when().post("/v1beta4/projects/" + project + "/instances")
                .then()
                .statusCode(200)
                .body("operationType", equalTo("CREATE"));

        given()
                .when().get("/sql/v1beta4/projects/" + project + "/instances/pg-v1beta4")
                .then()
                .statusCode(200)
                .body("name", equalTo("pg-v1beta4"))
                .body("databaseVersion", equalTo("POSTGRES_16"));
    }

    @Test
    void cloudSqlScopesInstancesByProject() {
        given()
                .contentType("application/json")
                .body("{\"name\":\"pg-shared\",\"databaseVersion\":\"POSTGRES_18\"}")
                .when().post("/v1/projects/sql-project-a/instances")
                .then()
                .statusCode(200);

        given()
                .contentType("application/json")
                .body("{\"name\":\"pg-shared\",\"databaseVersion\":\"POSTGRES_16\"}")
                .when().post("/v1/projects/sql-project-b/instances")
                .then()
                .statusCode(200);

        given()
                .when().get("/v1/projects/sql-project-a/instances/pg-shared")
                .then()
                .statusCode(200)
                .body("project", equalTo("sql-project-a"))
                .body("databaseVersion", equalTo("POSTGRES_18"));

        given()
                .when().get("/v1/projects/sql-project-b/instances/pg-shared")
                .then()
                .statusCode(200)
                .body("project", equalTo("sql-project-b"))
                .body("databaseVersion", equalTo("POSTGRES_16"));
    }

    @Test
    void cloudSqlRejectsUnsupportedEnginesAndMissingResourcesUseGcpErrors() {
        String project = "sql-it-errors";

        given()
                .contentType("application/json")
                .body("{\"name\":\"mssql-main\",\"databaseVersion\":\"SQLSERVER_2019_STANDARD\"}")
                .when().post("/v1/projects/" + project + "/instances")
                .then()
                .statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));

        given()
                .when().get("/v1/projects/" + project + "/instances/missing")
                .then()
                .statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"))
                .body("error.errors[0].reason", equalTo("notFound"));
    }
}
