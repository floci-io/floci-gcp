package io.floci.gcp.services.gcs;

import com.google.storage.v2.Bucket;
import com.google.storage.v2.CreateBucketRequest;
import com.google.storage.v2.GetObjectRequest;
import com.google.storage.v2.StorageGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class GcsGrpcIntegrationTest {

    @TestHTTPResource
    URI endpoint;

    @Test
    void grpcAndRestShareTheSinglePortAndStorageState() throws Exception {
        String bucket = "grpc-shared-port-bucket";
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext()
                .build();
        try {
            StorageGrpc.StorageBlockingStub storage = StorageGrpc.newBlockingStub(channel);
            storage.createBucket(CreateBucketRequest.newBuilder()
                    .setParent("projects/_")
                    .setBucketId(bucket)
                    .setBucket(Bucket.newBuilder().setProject("projects/test-project"))
                    .build());

            given().when().get("/storage/v1/b/" + bucket)
                    .then().statusCode(200);

            given()
                    .queryParam("uploadType", "media")
                    .queryParam("name", "rest-created.txt")
                    .contentType("text/plain")
                    .body("shared state".getBytes(StandardCharsets.UTF_8))
                    .when().post("/upload/storage/v1/b/" + bucket + "/o")
                    .then().statusCode(200);

            com.google.storage.v2.Object object = storage.getObject(GetObjectRequest.newBuilder()
                    .setBucket("projects/_/buckets/" + bucket)
                    .setObject("rest-created.txt")
                    .build());
            assertEquals("rest-created.txt", object.getName());
            assertEquals("shared state".length(), object.getSize());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
