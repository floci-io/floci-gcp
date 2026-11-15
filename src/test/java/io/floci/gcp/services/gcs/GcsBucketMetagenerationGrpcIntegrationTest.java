package io.floci.gcp.services.gcs;

import com.google.protobuf.FieldMask;
import com.google.storage.v2.Bucket;
import com.google.storage.v2.CreateBucketRequest;
import com.google.storage.v2.GetBucketRequest;
import com.google.storage.v2.StorageGrpc;
import com.google.storage.v2.UpdateBucketRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class GcsBucketMetagenerationGrpcIntegrationTest {

    private static final String BUCKET = "metageneration-grpc-bucket";

    @TestHTTPResource
    URI endpoint;

    @Test
    void updateBucketBumpsMetageneration() throws Exception {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext()
                .build();
        try {
            StorageGrpc.StorageBlockingStub storage = StorageGrpc.newBlockingStub(channel);
            Bucket created = storage.createBucket(CreateBucketRequest.newBuilder()
                    .setParent("projects/_")
                    .setBucketId(BUCKET)
                    .setBucket(Bucket.newBuilder().setProject("projects/test-project"))
                    .build());
            assertEquals(1L, created.getMetageneration());

            Bucket firstUpdate = storage.updateBucket(updateLabel(created.getName(), "dev"));
            assertEquals(2L, firstUpdate.getMetageneration());
            assertEquals(2L, getBucket(storage, created.getName()).getMetageneration());

            Bucket secondUpdate = storage.updateBucket(updateLabel(created.getName(), "prod"));
            assertEquals(3L, secondUpdate.getMetageneration());
            assertEquals(3L, getBucket(storage, created.getName()).getMetageneration());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static UpdateBucketRequest updateLabel(String bucketName, String env) {
        return UpdateBucketRequest.newBuilder()
                .setBucket(Bucket.newBuilder().setName(bucketName).putLabels("env", env))
                .setUpdateMask(FieldMask.newBuilder().addPaths("labels"))
                .build();
    }

    private static Bucket getBucket(StorageGrpc.StorageBlockingStub storage, String bucketName) {
        return storage.getBucket(GetBucketRequest.newBuilder().setName(bucketName).build());
    }
}
