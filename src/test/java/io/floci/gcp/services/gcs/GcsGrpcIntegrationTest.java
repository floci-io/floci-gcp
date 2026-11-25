package io.floci.gcp.services.gcs;

import com.google.protobuf.FieldMask;
import com.google.storage.v2.Bucket;
import com.google.storage.v2.CreateBucketRequest;
import com.google.storage.v2.DeleteBucketRequest;
import com.google.storage.v2.GetObjectRequest;
import com.google.storage.v2.RewriteObjectRequest;
import com.google.storage.v2.RewriteResponse;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * Server-side copies go through the gRPC RewriteObject RPC. The rewrite token protocol has
     * to answer over the wire the same way the JSON endpoint does.
     */
    @Test
    void grpcRewriteObjectCompletesTheCopy() throws Exception {
        String bucket = "grpc-rewrite-" + UUID.randomUUID().toString().substring(0, 8);
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
            given()
                    .queryParam("uploadType", "media")
                    .queryParam("name", "source.txt")
                    .contentType("text/plain")
                    .body("copied".getBytes(StandardCharsets.UTF_8))
                    .when().post("/upload/storage/v1/b/" + bucket + "/o")
                    .then().statusCode(200);

            RewriteResponse response = storage.rewriteObject(RewriteObjectRequest.newBuilder()
                    .setSourceBucket("projects/_/buckets/" + bucket)
                    .setSourceObject("source.txt")
                    .setDestinationBucket("projects/_/buckets/" + bucket)
                    .setDestinationName("copy.txt")
                    .build());

            assertTrue(response.getDone());
            assertEquals(6, response.getTotalBytesRewritten());
            assertEquals(6, response.getObjectSize());
            assertEquals("", response.getRewriteToken());
            assertEquals("copy.txt", response.getResource().getName());
            assertEquals("projects/_/buckets/" + bucket, response.getResource().getBucket());
            given().when().get("/storage/v1/b/" + bucket + "/o/copy.txt")
                    .then().statusCode(200).body("size", equalTo("6"));
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void grpcRewriteObjectAnswersWithATokenUntilTheCopyCompletes() throws Exception {
        String sourceBucket = "grpc-rewrite-src-" + UUID.randomUUID().toString().substring(0, 8);
        String destinationBucket = "grpc-rewrite-dst-" + UUID.randomUUID().toString().substring(0, 8);
        int oneMiB = 1024 * 1024;
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext()
                .build();
        try {
            StorageGrpc.StorageBlockingStub storage = StorageGrpc.newBlockingStub(channel);
            storage.createBucket(CreateBucketRequest.newBuilder()
                    .setParent("projects/_")
                    .setBucketId(sourceBucket)
                    .setBucket(Bucket.newBuilder().setProject("projects/test-project"))
                    .build());
            storage.createBucket(CreateBucketRequest.newBuilder()
                    .setParent("projects/_")
                    .setBucketId(destinationBucket)
                    .setBucket(Bucket.newBuilder()
                            .setProject("projects/test-project")
                            .setStorageClass("NEARLINE"))
                    .build());
            given()
                    .queryParam("uploadType", "media")
                    .queryParam("name", "big")
                    .contentType("application/octet-stream")
                    .body(new byte[2 * oneMiB])
                    .when().post("/upload/storage/v1/b/" + sourceBucket + "/o")
                    .then().statusCode(200);

            RewriteObjectRequest first = RewriteObjectRequest.newBuilder()
                    .setSourceBucket("projects/_/buckets/" + sourceBucket)
                    .setSourceObject("big")
                    .setDestinationBucket("projects/_/buckets/" + destinationBucket)
                    .setDestinationName("big")
                    .setMaxBytesRewrittenPerCall(oneMiB)
                    .build();
            RewriteResponse partial = storage.rewriteObject(first);

            assertFalse(partial.getDone());
            assertFalse(partial.getRewriteToken().isBlank());
            assertFalse(partial.hasResource());
            assertEquals(oneMiB, partial.getTotalBytesRewritten());
            assertEquals(2L * oneMiB, partial.getObjectSize());
            // The destination only appears once the completing call runs.
            given().when().get("/storage/v1/b/" + destinationBucket + "/o/big")
                    .then().statusCode(404);

            RewriteResponse completed = storage.rewriteObject(first.toBuilder()
                    .setRewriteToken(partial.getRewriteToken())
                    .build());

            assertTrue(completed.getDone());
            assertEquals(2L * oneMiB, completed.getTotalBytesRewritten());
            assertEquals("NEARLINE", completed.getResource().getStorageClass());
            given().when().get("/storage/v1/b/" + destinationBucket + "/o/big")
                    .then().statusCode(200).body("size", equalTo(String.valueOf(2L * oneMiB)));
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
