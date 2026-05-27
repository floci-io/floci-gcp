package io.floci.gcp.test;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GcsTest {

    private static final String BUCKET_NAME = TestFixtures.uniqueName("test-bucket");
    private static final String OBJECT_NAME = "test/object.txt";
    private static final String SECOND_OBJECT_NAME = "test/second.txt";
    private static final String OBJECT_CONTENT = "Hello, GCP Cloud Storage!";

    private static Storage storage;

    @BeforeAll
    static void setUp() {
        storage = TestFixtures.storageClient();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (storage instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    @Test
    @Order(1)
    void createBucket() {
        Bucket bucket = storage.create(BucketInfo.of(BUCKET_NAME));
        assertThat(bucket.getName()).isEqualTo(BUCKET_NAME);
    }

    @Test
    @Order(2)
    void getBucket() {
        Bucket bucket = storage.get(BUCKET_NAME);
        assertThat(bucket).isNotNull();
        assertThat(bucket.getName()).isEqualTo(BUCKET_NAME);
    }

    @Test
    @Order(3)
    void listBuckets() {
        List<String> names = new ArrayList<>();
        storage.list().iterateAll().forEach(b -> names.add(b.getName()));
        assertThat(names).contains(BUCKET_NAME);
    }

    @Test
    @Order(4)
    void uploadObject() {
        BlobId blobId = BlobId.of(BUCKET_NAME, OBJECT_NAME);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType("text/plain")
                .build();

        Blob blob = storage.create(blobInfo, OBJECT_CONTENT.getBytes(StandardCharsets.UTF_8));

        assertThat(blob.getName()).isEqualTo(OBJECT_NAME);
        assertThat(blob.getBucket()).isEqualTo(BUCKET_NAME);
        assertThat(blob.getContentType()).isEqualTo("text/plain");
        assertThat(blob.getSize()).isEqualTo(OBJECT_CONTENT.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @Order(5)
    void getObjectMetadata() {
        Blob blob = storage.get(BlobId.of(BUCKET_NAME, OBJECT_NAME));
        assertThat(blob).isNotNull();
        assertThat(blob.getContentType()).isEqualTo("text/plain");
        assertThat(blob.getCreateTime()).isNotNull();
    }

    @Test
    @Order(6)
    void downloadAndVerifyObjectContent() {
        BlobId blobId = BlobId.of(BUCKET_NAME, OBJECT_NAME);
        Blob blob = storage.get(blobId);

        assertThat(blob).isNotNull();

        String downloadedContent = new String(blob.getContent(), StandardCharsets.UTF_8);
        assertThat(downloadedContent).isEqualTo(OBJECT_CONTENT);
    }

    @Test
    @Order(7)
    void listObjectsInBucket() {
        List<String> objectNames = new ArrayList<>();
        storage.list(BUCKET_NAME).iterateAll().forEach(blob -> objectNames.add(blob.getName()));
        assertThat(objectNames).contains(OBJECT_NAME);
    }

    @Test
    @Order(8)
    void uploadSecondObjectAndListWithPrefix() {
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, SECOND_OBJECT_NAME)).build();
        storage.create(blobInfo, "second content".getBytes(StandardCharsets.UTF_8));

        List<String> names = new ArrayList<>();
        storage.list(BUCKET_NAME, Storage.BlobListOption.prefix("test/"))
                .iterateAll().forEach(b -> names.add(b.getName()));

        assertThat(names).contains(OBJECT_NAME, SECOND_OBJECT_NAME);
    }

    @Test
    @Order(9)
    void deleteObjectsAndBucket() {
        assertThat(storage.delete(BlobId.of(BUCKET_NAME, OBJECT_NAME))).isTrue();
        assertThat(storage.get(BlobId.of(BUCKET_NAME, OBJECT_NAME))).isNull();

        assertThat(storage.delete(BlobId.of(BUCKET_NAME, SECOND_OBJECT_NAME))).isTrue();

        Bucket bucket = storage.get(BUCKET_NAME);
        assertThat(bucket).isNotNull();
        assertThat(bucket.delete()).isTrue();
        assertThat(storage.get(BUCKET_NAME)).isNull();
    }
}
