package io.floci.gcp.services.bigquery;

import com.google.cloud.bigquery.storage.v1.BigQueryReadGrpc;
import com.google.cloud.bigquery.storage.v1.BigQueryWriteGrpc;
import com.google.cloud.bigquery.storage.v1.CreateReadSessionRequest;
import com.google.cloud.bigquery.storage.v1.CreateWriteStreamRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(BigQueryDisabledRestIntegrationTest.DisabledBigQueryProfile.class)
class BigQueryDisabledRestIntegrationTest {

    @TestHTTPResource
    URI endpoint;

    @Test
    void disabledBigQueryRestServiceReturnsUnavailableWrapper() {
        given()
                .when().get("/bigquery/v2/projects/bq-disabled/datasets")
                .then()
                .statusCode(503)
                .body("error.status", equalTo("UNAVAILABLE"))
                .body("error.message", equalTo("Service bigquery is not enabled."));
    }

    @Test
    void disabledBigQueryDoesNotServeTheStorageApis() {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext().build();
        try {
            StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
                    () -> BigQueryReadGrpc.newBlockingStub(channel).createReadSession(
                            CreateReadSessionRequest.newBuilder().setParent("projects/bq-disabled").build()));
            assertEquals(Status.Code.UNIMPLEMENTED, e.getStatus().getCode());
            StatusRuntimeException write = assertThrows(StatusRuntimeException.class,
                    () -> BigQueryWriteGrpc.newBlockingStub(channel).createWriteStream(
                            CreateWriteStreamRequest.newBuilder().setParent("projects/bq-disabled").build()));
            assertEquals(Status.Code.UNIMPLEMENTED, write.getStatus().getCode());
        } finally {
            channel.shutdownNow();
        }
    }

    public static class DisabledBigQueryProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.bigquery.enabled", "false");
        }
    }
}
