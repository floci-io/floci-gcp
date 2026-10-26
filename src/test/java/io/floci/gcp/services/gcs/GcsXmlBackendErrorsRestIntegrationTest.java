package io.floci.gcp.services.gcs;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.StorageException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

@QuarkusTest
@TestProfile(GcsXmlBackendErrorsRestIntegrationTest.BackendProfile.class)
class GcsXmlBackendErrorsRestIntegrationTest {

    @InjectMock GcsService service;
    @InjectMock GcsMultipartService multipart;

    // Isolate mocked construction dependencies from the real multipart service in other tests.
    public static class BackendProfile implements QuarkusTestProfile {
    }

    @Test
    void backendFailuresAreSanitizedAcrossXmlRoutes() {
        for (RuntimeException failure : new RuntimeException[] {
                new StorageException("/private/storage/secrets.json", new IOException("secret backend detail")),
                new IllegalStateException("secret backend detail"),
                GcpException.internal("secret backend detail")}) {
            doThrow(failure).when(service).getBucket("backend-failure");
            doThrow(failure).when(multipart).list(anyString(), anyString());
            doThrow(failure).when(multipart).initiate(anyString(), anyString(), any(), any());
            for (String path : new String[] {"/backend-failure", "/backend-failure?uploads",
                    "/backend-failure/object?uploads"}) {
                given().request(path.endsWith("object?uploads") ? "POST" : "GET", path).then()
                        .statusCode(500).contentType(containsString("application/xml"))
                        .body("Error.Code", equalTo("InternalError"))
                        .body(not(containsString("private")))
                        .body(not(containsString("secret backend detail")));
            }
        }
    }
}
