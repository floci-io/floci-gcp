package io.floci.gcp.core.common;

import io.floci.gcp.core.storage.StorageException;
import io.floci.gcp.services.gcs.GcsService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@QuarkusTest
class StorageExceptionRestIntegrationTest {

    private static final String FAILED_BUCKET = "storage-failure-bucket";

    @InjectMock
    GcsService gcsService;

    @Test
    void storageFailureUsesSanitizedGcpJsonError() {
        when(gcsService.createBucket(eq(FAILED_BUCKET), eq("test-project"), anyString(), anyMap(), isNull()))
                .thenThrow(new StorageException(
                        "Failed to persist data to /private/storage/iam-policies.json",
                        new IOException("injected failure")));

        Response response = given().contentType("application/json")
                .body(Map.of("name", FAILED_BUCKET))
                .when().post("/storage/v1/b?project=test-project");

        response.then()
                .statusCode(500)
                .contentType("application/json")
                .body("error.code", equalTo(500))
                .body("error.message", equalTo("Internal server error."))
                .body("error.status", equalTo("INTERNAL"))
                .body("error.errors[0].reason", equalTo("internalError"));
        assertFalse(response.asString().contains("/private/storage"));
    }
}
