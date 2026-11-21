package io.floci.gcp.services.gcs;

import com.google.storage.v2.GetBucketRequest;
import com.google.storage.v2.StorageGrpc;
import io.floci.gcp.testing.GcsDisabledProfile;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(GcsDisabledProfile.class)
class GcsGrpcDisabledIntegrationTest {

    @TestHTTPResource
    URI endpoint;

    @Test
    void disabledGcsDoesNotBindGrpcService() throws Exception {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext()
                .build();
        try {
            StatusRuntimeException exception = assertThrows(StatusRuntimeException.class,
                    () -> StorageGrpc.newBlockingStub(channel).getBucket(GetBucketRequest.newBuilder()
                            .setName("projects/_/buckets/disabled-bucket").build()));
            assertEquals(Status.Code.UNIMPLEMENTED, exception.getStatus().getCode());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
