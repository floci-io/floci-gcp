package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class GcsXmlErrorsRestIntegrationTest {

    private String bucket;

    @BeforeEach
    void createBucket() {
        bucket = "xml-errors-" + System.nanoTime();
        given().contentType("application/json").queryParam("project", "test-project")
                .body(Map.of("name", bucket)).post("/storage/v1/b").then().statusCode(200);
    }

    @Test
    void resourceErrorsUseContextAcrossOrdinaryAndMultipartRoutes() {
        for (String accept : new String[] {null, "*/*", "application/xml"}) {
            xml(request(accept).get("/" + bucket + "/missing").then(), 404, "NoSuchKey");
            xml(request(accept).delete("/" + bucket + "/missing").then(), 404, "NoSuchKey");
            xml(request(accept).get("/" + bucket + "/missing?generation=123").then(), 404, "NoSuchKey");
            xml(request(accept).delete("/" + bucket + "/missing?generation=123").then(), 404, "NoSuchKey");
            xml(request(accept).get("/" + bucket + "-missing").then(), 404, "NoSuchBucket");
            xml(request(accept).get("/" + bucket + "-missing/object").then(), 404, "NoSuchBucket");
            xml(request(accept).put("/" + bucket + "-missing/object").then(), 404, "NoSuchBucket");
            xml(request(accept).delete("/" + bucket + "-missing/object").then(), 404, "NoSuchBucket");
            xml(request(accept).get("/" + bucket + "-missing?uploads").then(), 404, "NoSuchBucket");
            xml(request(accept).post("/" + bucket + "-missing/object?uploads").then(), 404, "NoSuchBucket");
            for (String method : new String[] {"GET", "PUT", "POST", "DELETE"}) {
                String path = "/object?uploadId=missing&partNumber=1";
                RequestSpecification request = request(accept);
                if (method.equals("POST")) {
                    request.body("<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>x</ETag></Part></CompleteMultipartUpload>");
                }
                xml(request.request(method, "/" + bucket + path).then(), 404, "NoSuchUpload");
                xml(request.request(method, "/" + bucket + "-missing" + path).then(), 404, "NoSuchBucket");
            }
        }
    }

    @Test
    void invalidRangesAndMultipartValidationUseXml() {
        given().body("contents").put("/" + bucket + "/object").then().statusCode(200);
        xml(given().header("Range", "bytes=100-").get("/" + bucket + "/object").then(), 416, "InvalidRange");
        xml(given().header("Range", "invalid").get("/" + bucket + "/object").then(), 400, "InvalidArgument");
        xml(given().get("/" + bucket + "?uploads&max-uploads=invalid").then(), 400, "InvalidArgument");
        xml(given().get("/" + bucket + "?uploads&delimiter=/").then(), 400, "NotImplemented");
    }

    @Test
    void jsonErrorsRetainTheirEnvelopeAndReason() {
        given().get("/storage/v1/b/" + bucket + "/o/missing").then().statusCode(404)
                .contentType("application/json")
                .body("error.code", equalTo(404))
                .body("error.status", equalTo("NOT_FOUND"))
                .body("error.message", equalTo("Object not found: missing"))
                .body("error.errors[0].reason", equalTo("notFound"));
        given().get("/storage/v1/b/" + bucket + "-missing").then().statusCode(404)
                .contentType("application/json").body("error.errors[0].reason", equalTo("notFound"));
    }

    private static RequestSpecification request(String accept) {
        RequestSpecification request = given();
        return accept == null ? request : request.accept(accept);
    }

    private static void xml(ValidatableResponse response, int status, String code) {
        response.statusCode(status).contentType(containsString("application/xml"))
                .body("Error.Code", equalTo(code))
                .body(not(containsString("\"error\"")));
    }
}
