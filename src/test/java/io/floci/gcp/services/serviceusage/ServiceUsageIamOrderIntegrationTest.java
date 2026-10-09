package io.floci.gcp.services.serviceusage;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/** With both enforcements on, a call IAM would deny still reports SERVICE_DISABLED while the API is off. */
@QuarkusTest
@TestProfile(ServiceUsageIamOrderIntegrationTest.BothEnforcedProfile.class)
class ServiceUsageIamOrderIntegrationTest {

    @Inject
    CredentialTokenService tokens;
    @Inject
    EmulatorConfig config;

    @Test
    void disabledApiIsReportedBeforeAnIamDenial() {
        // Bucket routes carry no project, so the request's project is the default one.
        String project = config.defaultProjectId();
        String bucket = "su-iam-order-bucket";
        String credential = "Bearer " + tokens.mintImpersonatedToken(
                "nogrants@" + project + ".iam.gserviceaccount.com", Instant.now().plusSeconds(300)).getTokenValue();
        given().contentType("application/json").body(Map.of("name", bucket))
                .when().post("/storage/v1/b?project=" + project)
                .then().statusCode(200);

        setStorage(project, "disable");
        given().header("Authorization", credential)
                .when().get("/storage/v1/b/" + bucket)
                .then().statusCode(403)
                .body("error.details[0].reason", equalTo("SERVICE_DISABLED"));

        setStorage(project, "enable");
        given().header("Authorization", credential)
                .when().get("/storage/v1/b/" + bucket)
                .then().statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.details[0].reason", not(equalTo("SERVICE_DISABLED")));
    }

    private static void setStorage(String project, String verb) {
        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .when().post("/v1/projects/" + project + "/services/storage.googleapis.com:" + verb)
                .then().statusCode(200);
    }

    public static class BothEnforcedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.serviceusage.enforce", "true",
                    "floci-gcp.services.iam.authorization-mode", "enforce");
        }
    }
}
