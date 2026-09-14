package io.floci.gcp.services.compute;

import io.restassured.response.Response;
import java.util.UUID;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

abstract class ComputeTestSupport {
    String root() { return "/compute/v1/projects/compute-" + UUID.randomUUID(); }
    Response post(String path, Object body) { return given().contentType("application/json").body(body).post(path); }
    void done(String root, Response response) throws Exception {
        response.then().statusCode(200);
        String link = response.jsonPath().getString("selfLink");
        assertNotNull(link);
        String operation = link.substring(link.indexOf("/compute/v1/"));
        for (int i = 0; i < 100; i++) {
            var result = given().get(operation).then().statusCode(200).extract().response();
            if ("DONE".equals(result.jsonPath().getString("status"))) { return; }
            Thread.sleep(10);
        }
        fail("Compute operation did not finish");
    }
}
