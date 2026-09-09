package io.floci.gcp.test;

import com.google.cloud.storage.*;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.*;

class StorageContractTest {
    @Test void bytesResumableStreamingMetadataAndPages() throws Exception {
        Storage storage = TestFixtures.storageClient();
        String bucket = TestFixtures.uniqueName("java-storage-contract");
        storage.create(BucketInfo.of(bucket));
        byte[] bytes = new byte[1024 * 1024]; Arrays.fill(bytes, (byte) 199);
        try {
            var id = BlobId.of(bucket, "nested/data.bin");
            try (var writer = storage.writer(BlobInfo.newBuilder(id).setContentType("application/octet-stream").build())) {
                writer.write(ByteBuffer.wrap(bytes));
            }
            storage.create(BlobInfo.newBuilder(bucket, "nested/second").build(), new byte[]{1,2,3});
            assertThat(storage.get(id).getSize()).isEqualTo(bytes.length);
            assertThat(storage.readAllBytes(id)).isEqualTo(bytes);
            assertThat(storage.list(bucket, Storage.BlobListOption.prefix("nested/"), Storage.BlobListOption.pageSize(1)).iterateAll()).hasSize(2);
            try (var reader = storage.reader(id)) {
                reader.seek(100); reader.limit(110);
                ByteBuffer range = ByteBuffer.allocate(10);
                reader.read(range);
                assertThat(range.array()).isEqualTo(Arrays.copyOfRange(bytes, 100, 110));
            }
        } finally {
            for (var blob : storage.list(bucket).iterateAll()) { blob.delete(); }
            storage.delete(bucket);
        }
    }
}
