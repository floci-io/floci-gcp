package io.floci.gcp.services.gcs;

import com.google.protobuf.FieldMask;
import com.google.storage.v2.Bucket;
import com.google.storage.v2.CreateBucketRequest;
import com.google.storage.v2.DeleteBucketRequest;
import com.google.storage.v2.GetObjectRequest;
import com.google.storage.v2.StorageGrpc;
import com.google.storage.v2.UpdateBucketRequest;
import com.google.storage.v2.UpdateObjectRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class GcsGrpcIntegrationTest {

    @TestHTTPResource
    URI endpoint;

    @Test
    void grpcDeleteAndRecreateCannotInheritThePreviousBucketPolicy() throws Exception {
        String bucket = "grpc-policy-lifecycle-" + UUID.randomUUID().toString().substring(0, 8);
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext()
                .build();
        try {
            StorageGrpc.StorageBlockingStub storage = StorageGrpc.newBlockingStub(channel);
            CreateBucketRequest create = CreateBucketRequest.newBuilder()
                    .setParent("projects/_")
                    .setBucketId(bucket)
                    .setBucket(Bucket.newBuilder().setProject("projects/test-project"))
                    .build();
            storage.createBucket(create);
            given().contentType("application/json").body(Map.of(
                    "version", 1,
                    "bindings", List.of(Map.of(
                            "role", "roles/storage.admin",
                            "members", List.of("allUsers")))))
                    .when().put("/storage/v1/b/" + bucket + "/iam")
                    .then().statusCode(200);

            storage.deleteBucket(DeleteBucketRequest.newBuilder()
                    .setName("projects/_/buckets/" + bucket)
                    .build());
            given().when().get("/storage/v1/b/" + bucket + "/iam")
                    .then().statusCode(404);

            storage.createBucket(create);
            given().when().get("/storage/v1/b/" + bucket + "/iam")
                    .then().statusCode(200).body("bindings.size()", equalTo(0));
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void grpcCannotDisableUblaWhileConditionalPolicyExists() throws Exception {
        String bucket = "grpc-conditional-policy-" + UUID.randomUUID().toString().substring(0, 8);
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext()
                .build();
        try {
            StorageGrpc.StorageBlockingStub storage = StorageGrpc.newBlockingStub(channel);
            Bucket created = storage.createBucket(CreateBucketRequest.newBuilder()
                    .setParent("projects/_")
                    .setBucketId(bucket)
                    .setBucket(Bucket.newBuilder()
                            .setProject("projects/test-project")
                            .setIamConfig(Bucket.IamConfig.newBuilder()
                                    .setUniformBucketLevelAccess(
                                            Bucket.IamConfig.UniformBucketLevelAccess.newBuilder()
                                                    .setEnabled(true))))
                    .build());
            given().contentType("application/json").body(Map.of(
                    "version", 3,
                    "bindings", List.of(Map.of(
                            "role", "roles/storage.objectViewer",
                            "members", List.of("serviceAccount:reader@example.test"),
                            "condition", Map.of(
                                    "title", "reports",
                                    "expression", "resource.name.startsWith('projects/_/buckets/"
                                            + bucket + "/objects/reports/')")))))
                    .when().put("/storage/v1/b/" + bucket + "/iam")
                    .then().statusCode(200);

            Bucket update = created.toBuilder()
                    .setIamConfig(created.getIamConfig().toBuilder()
                            .setUniformBucketLevelAccess(
                                    created.getIamConfig().getUniformBucketLevelAccess().toBuilder()
                                            .setEnabled(false)))
                    .build();
            StatusRuntimeException error = assertThrows(StatusRuntimeException.class,
                    () -> storage.updateBucket(UpdateBucketRequest.newBuilder()
                            .setBucket(update)
                            .setUpdateMask(FieldMask.newBuilder()
                                    .addPaths("iam_config.uniform_bucket_level_access.enabled"))
                            .build()));
            assertEquals(Status.Code.INVALID_ARGUMENT, error.getStatus().getCode());

            given().when().get("/storage/v1/b/" + bucket)
                    .then().statusCode(200)
                    .body("iamConfiguration.uniformBucketLevelAccess.enabled", equalTo(true));
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void grpcAndRestShareTheSinglePortAndStorageState() throws Exception {
        String bucket = "grpc-shared-port-bucket";
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext()
                .build();
        try {
            StorageGrpc.StorageBlockingStub storage = StorageGrpc.newBlockingStub(channel);
            Bucket created = storage.createBucket(CreateBucketRequest.newBuilder()
                    .setParent("projects/_")
                    .setBucketId(bucket)
                    .setBucket(Bucket.newBuilder().setProject("projects/test-project"))
                    .build());

            String projectNumber = given().when().get("/storage/v1/b/" + bucket)
                    .then().statusCode(200).extract().path("projectNumber");
            assertEquals("projects/" + projectNumber, created.getProject());

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

    /**
     * cacheControl only reaches the emulator over REST. A gRPC-only client never sees the
     * REST representation, so the object it reads back has to carry the field too.
     */
    @Test
    void grpcGetObjectReportsCacheControlSetByARestUpload() throws Exception {
        String bucket = "grpc-cache-control-bucket";
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

            String body = """
                    --sysmeta
                    Content-Type: application/json

                    {"name":"cached.txt","cacheControl":"public, max-age=3600"}
                    --sysmeta
                    Content-Type: text/plain

                    cached
                    --sysmeta--
                    """.replace("\n", "\r\n");
            given()
                    .queryParam("uploadType", "multipart")
                    .header("Content-Type", "multipart/related; boundary=sysmeta")
                    .body(body.getBytes(StandardCharsets.UTF_8))
                    .when().post("/upload/storage/v1/b/" + bucket + "/o")
                    .then().statusCode(200);

            com.google.storage.v2.Object object = storage.getObject(GetObjectRequest.newBuilder()
                    .setBucket("projects/_/buckets/" + bucket)
                    .setObject("cached.txt")
                    .build());
            assertEquals("public, max-age=3600", object.getCacheControl());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void grpcMetadataKeyMaskMergesAndDeletesIndividualKeys() throws Exception {
        String bucket = "grpc-metadata-mask-bucket";
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

            String body = """
                    --metadata
                    Content-Type: application/json

                    {"name":"object","metadata":{"updated":"old","removed":"old","preserved":"old"}}
                    --metadata
                    Content-Type: text/plain

                    content
                    --metadata--
                    """.replace("\n", "\r\n");
            given()
                    .queryParam("uploadType", "multipart")
                    .header("Content-Type", "multipart/related; boundary=metadata")
                    .body(body.getBytes(StandardCharsets.UTF_8))
                    .when().post("/upload/storage/v1/b/" + bucket + "/o")
                    .then().statusCode(200);

            com.google.storage.v2.Object updated = storage.updateObject(UpdateObjectRequest.newBuilder()
                    .setObject(com.google.storage.v2.Object.newBuilder()
                            .setBucket("projects/_/buckets/" + bucket)
                            .setName("object")
                            .putMetadata("updated", "new")
                            .putMetadata("added", "new"))
                    .setUpdateMask(FieldMask.newBuilder()
                            .addPaths("metadata.updated")
                            .addPaths("metadata.removed")
                            .addPaths("metadata.added"))
                    .build());

            assertEquals(Map.of("updated", "new", "preserved", "old", "added", "new"),
                    updated.getMetadataMap());
            assertEquals(updated.getMetadataMap(), storage.getObject(GetObjectRequest.newBuilder()
                    .setBucket("projects/_/buckets/" + bucket)
                    .setObject("object")
                    .build()).getMetadataMap());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
