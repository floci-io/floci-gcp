package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class GcsObjectNameRestIntegrationTest {

    private static final String BUCKET = "encoded-object-names";
    private static final byte[] CONTENT = "encoded object".getBytes(StandardCharsets.UTF_8);

    @Test
    void preservesUriEncodedObjectNames() {
        given()
                .contentType("application/json")
                .body(Map.of("name", BUCKET))
                .when().post("/storage/v1/b?project=test-project")
                .then().statusCode(200);

        for (String objectName : List.of(
                "literal%2Fslash",
                "literal%20space",
                "literal%25percent",
                "literal%2Bplus")) {
            String encodedPath = objectName.replace("%", "%25");

            given()
                    .queryParam("uploadType", "media")
                    .queryParam("name", objectName)
                    .contentType("application/octet-stream")
                    .body(CONTENT)
                    .when().post("/upload/storage/v1/b/" + BUCKET + "/o")
                    .then().statusCode(200)
                    .body("name", equalTo(objectName));

            given()
                    .urlEncodingEnabled(false)
                    .when().get("/storage/v1/b/" + BUCKET + "/o/" + encodedPath)
                    .then().statusCode(200)
                    .body("name", equalTo(objectName));

            given()
                    .urlEncodingEnabled(false)
                    .when().get("/download/storage/v1/b/" + BUCKET + "/o/" + encodedPath)
                    .then().statusCode(200)
                    .body(equalTo("encoded object"));

            String xmlObjectName = "xml-" + objectName;
            String encodedXmlPath = xmlObjectName.replace("%", "%25");
            given()
                    .urlEncodingEnabled(false)
                    .contentType("application/octet-stream")
                    .body(CONTENT)
                    .when().put("/" + BUCKET + "/" + encodedXmlPath)
                    .then().statusCode(200)
                    .body("name", equalTo(xmlObjectName));

            given()
                    .urlEncodingEnabled(false)
                    .when().get("/" + BUCKET + "/" + encodedXmlPath)
                    .then().statusCode(200)
                    .body(equalTo("encoded object"));
        }
    }
}
