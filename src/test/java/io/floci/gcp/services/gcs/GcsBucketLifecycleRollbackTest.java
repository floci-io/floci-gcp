package io.floci.gcp.services.gcs;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.core.storage.StorageException;
import io.floci.gcp.services.gcs.model.GcsBucket;
import io.floci.gcp.services.iam.IamBucketLifecycleService;
import io.floci.gcp.services.iam.IamBucketPolicyBootstrapService;
import io.floci.gcp.services.iam.IamService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GcsBucketLifecycleRollbackTest {

    @Test
    void failedPolicyCheckpointRollsBackBucketAndAllowsRetry() {
        IamService iamService = mock(IamService.class);
        when(iamService.createResourceAndPolicy(
                eq("buckets/checkpoint-failure-bucket"), isNull(), any(), any()))
                .thenAnswer(invocation -> {
                    Supplier<GcsBucket> createBucket = invocation.getArgument(2);
                    Consumer<GcsBucket> rollbackBucket = invocation.getArgument(3);
                    GcsBucket created = createBucket.get();
                    rollbackBucket.accept(created);
                    throw new StorageException("checkpoint failed", new IllegalStateException("test failure"));
                })
                .thenAnswer(invocation -> {
                    Supplier<GcsBucket> createBucket = invocation.getArgument(2);
                    return createBucket.get();
                });
        IamBucketLifecycleService lifecycleService = new IamBucketLifecycleService(
                iamService, mock(IamBucketPolicyBootstrapService.class));
        GcsService service = new GcsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                "test-project",
                lifecycleService);

        assertThrows(StorageException.class, () -> service.createBucket(
                "checkpoint-failure-bucket", "test-project", "http://localhost:4588", Map.of()));
        GcpException missing = assertThrows(
                GcpException.class, () -> service.getBucket("checkpoint-failure-bucket"));
        assertEquals(404, missing.getHttpStatus());

        service.createBucket(
                "checkpoint-failure-bucket", "test-project", "http://localhost:4588", Map.of());
        assertEquals("checkpoint-failure-bucket", service.getBucket("checkpoint-failure-bucket").getName());
    }
}
