package io.floci.gcp.test;

import com.google.cloud.WriteChannel;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class GcsGrpcTest {

    @Test
    void currentJavaGrpcClientCompletesBucketAndObjectWorkflow() throws Exception {
        Storage storage = TestFixtures.storageGrpcClient();
        String bucketName = TestFixtures.uniqueName("java-gcs-grpc");
        String directName = "direct.txt";
        String streamedName = "streamed.txt";
        String composedName = "composed.txt";
        byte[] direct = "direct grpc payload".getBytes(StandardCharsets.UTF_8);
        byte[] streamed = "streamed grpc payload".getBytes(StandardCharsets.UTF_8);

        try {
            storage.create(BucketInfo.newBuilder(bucketName)
                    .setLocation("US")
                    .setLabels(Map.of("transport", "grpc"))
                    .build());

            assertThat(storage.get(bucketName).getLabels()).containsEntry("transport", "grpc");
            assertThat(StreamSupport.stream(storage.list().iterateAll().spliterator(), false)
                    .map(bucket -> bucket.getName())).contains(bucketName);
            assertThat(storage.update(storage.get(bucketName).toBuilder()
                    .setLabels(Map.of("updated", "true"))
                    .build()).getLabels()).containsEntry("updated", "true");

            Blob directBlob = storage.create(BlobInfo.newBuilder(bucketName, directName)
                    .setContentType("text/plain")
                    .setMetadata(Map.of("mode", "direct"))
                    .build(), direct);
            assertThat(directBlob.getSize()).isEqualTo(direct.length);
            assertThat(storage.readAllBytes(bucketName, directName)).isEqualTo(direct);

            BlobInfo streamedInfo = BlobInfo.newBuilder(bucketName, streamedName)
                    .setContentType("text/plain")
                    .setMetadata(Map.of("mode", "resumable"))
                    .build();
            try (WriteChannel writer = storage.writer(streamedInfo)) {
                writer.write(ByteBuffer.wrap(streamed));
            }
            assertThat(storage.readAllBytes(bucketName, streamedName)).isEqualTo(streamed);

            Blob updated = storage.update(storage.get(BlobId.of(bucketName, directName)).toBuilder()
                    .setMetadata(Map.of("updated", "true"))
                    .build());
            assertThat(updated.getMetadata()).containsEntry("updated", "true");

            Storage.ComposeRequest compose = Storage.ComposeRequest.newBuilder()
                    .addSource(directName, streamedName)
                    .setTarget(BlobInfo.newBuilder(bucketName, composedName)
                            .setContentType("text/plain").build())
                    .build();
            Blob composed = storage.compose(compose);
            assertThat(composed.getComponentCount()).isEqualTo(2);
            assertThat(storage.readAllBytes(bucketName, composedName))
                    .isEqualTo(concat(direct, streamed));

            List<String> names = StreamSupport.stream(
                    storage.list(bucketName).iterateAll().spliterator(), false)
                    .map(Blob::getName).toList();
            assertThat(names).contains(directName, streamedName, composedName);
        } finally {
            for (String object : List.of(directName, streamedName, composedName)) {
                storage.delete(bucketName, object);
            }
            storage.delete(bucketName);
        }
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
