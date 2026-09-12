package io.floci.gcp.test;

import com.google.cloud.NoCredentials;
import com.google.cloud.storage.*;
import com.google.cloud.storage.multipartupload.model.*;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class GcsXmlMultipartContractTest {
    @Test void nativeMultipartClientRoundTripAndAbort() throws Exception {
        var options = StorageOptions.http().setHost(TestFixtures.endpoint()).setProjectId("test-project").setCredentials(NoCredentials.getInstance()).build();
        Storage storage = options.getService();
        MultipartUploadClient client = MultipartUploadClient.create(MultipartUploadSettings.of(options));
        String bucket = TestFixtures.uniqueName("java-xml"), key = "nested/file.bin";
        storage.create(BucketInfo.of(bucket));
        String upload = null;
        try {
            upload = client.createMultipartUpload(CreateMultipartUploadRequest.builder().bucket(bucket).key(key).contentType("application/octet-stream").build()).uploadId();
            byte[] first = new byte[5 * 1024 * 1024]; Arrays.fill(first, (byte) 71);
            String etag = client.uploadPart(UploadPartRequest.builder().bucket(bucket).key(key).uploadId(upload).partNumber(1).build(), RequestBody.of(ByteBuffer.wrap(first))).eTag();
            assertThat(client.uploadPart(UploadPartRequest.builder().bucket(bucket).key(key).uploadId(upload).partNumber(1).build(), RequestBody.of(ByteBuffer.wrap(first))).eTag()).isEqualTo(etag);
            String last = client.uploadPart(UploadPartRequest.builder().bucket(bucket).key(key).uploadId(upload).partNumber(2).build(), RequestBody.of(ByteBuffer.wrap(new byte[]{0, 1, 2, 3}))).eTag();
            var page = client.listParts(ListPartsRequest.builder().bucket(bucket).key(key).uploadId(upload).maxParts(1).build());
            assertThat(page.truncated()).isTrue();
            assertThat(page.nextPartNumberMarker()).isEqualTo(1);
            assertThat(client.listParts(ListPartsRequest.builder().bucket(bucket).key(key).uploadId(upload).partNumberMarker(1).build()).parts()).hasSize(1);
            String pending = client.createMultipartUpload(CreateMultipartUploadRequest.builder().bucket(bucket).key(key).build()).uploadId();
            try {
                var uploadsPage = client.listMultipartUploads(ListMultipartUploadsRequest.builder().bucket(bucket).maxUploads(1).build());
                assertThat(uploadsPage.truncated()).isTrue();
                var next = client.listMultipartUploads(ListMultipartUploadsRequest.builder().bucket(bucket).maxUploads(1)
                        .keyMarker(uploadsPage.nextKeyMarker()).uploadIdMarker(uploadsPage.nextUploadIdMarker()).build());
                assertThat(next.uploads()).hasSize(1);
                assertThat(next.uploads().getFirst().uploadId()).isNotEqualTo(uploadsPage.uploads().getFirst().uploadId());
            } finally {
                client.abortMultipartUpload(AbortMultipartUploadRequest.builder().bucket(bucket).key(key).uploadId(pending).build());
            }
            assertThat(storage.get(bucket, key)).isNull();
            client.completeMultipartUpload(CompleteMultipartUploadRequest.builder().bucket(bucket).key(key).uploadId(upload)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(List.of(CompletedPart.builder().partNumber(1).eTag(etag).build(), CompletedPart.builder().partNumber(2).eTag(last).build())).build()).build());
            upload = null;
            byte[] expected = Arrays.copyOf(first, first.length + 4);
            System.arraycopy(new byte[]{0, 1, 2, 3}, 0, expected, first.length, 4);
            assertThat(storage.readAllBytes(bucket, key)).isEqualTo(expected);
            assertThat(storage.get(bucket, key).getMd5()).isNull();
            upload = client.createMultipartUpload(CreateMultipartUploadRequest.builder().bucket(bucket).key(key).build()).uploadId();
            client.abortMultipartUpload(AbortMultipartUploadRequest.builder().bucket(bucket).key(key).uploadId(upload).build());
            upload = null;
            assertThat(storage.readAllBytes(bucket, key)).isEqualTo(expected);
        } finally {
            if (upload != null) { client.abortMultipartUpload(AbortMultipartUploadRequest.builder().bucket(bucket).key(key).uploadId(upload).build()); }
            storage.delete(bucket, key); storage.delete(bucket);
        }
    }
}
