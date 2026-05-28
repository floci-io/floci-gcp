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
class GcsVersioningTest {

    private static final String BUCKET_NAME = TestFixtures.uniqueName("versioned-bucket");
    private static final String OBJECT_NAME = "versioned/object.txt";
    private static final String CONTENT_V1 = "version one content";
    private static final String CONTENT_V2 = "version two content";

    private static Storage storage;
    private static Long v1Generation;
    private static Long v2Generation;

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
    void createVersionedBucket() {
        Bucket bucket = storage.create(
                BucketInfo.newBuilder(BUCKET_NAME)
                        .setVersioningEnabled(true)
                        .build());
        assertThat(bucket.getName()).isEqualTo(BUCKET_NAME);
        assertThat(bucket.versioningEnabled()).isTrue();
    }

    @Test
    @Order(2)
    void uploadVersionOne() {
        BlobInfo info = BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, OBJECT_NAME))
                .setContentType("text/plain")
                .build();
        Blob blob = storage.create(info, CONTENT_V1.getBytes(StandardCharsets.UTF_8));
        v1Generation = blob.getGeneration();
        assertThat(v1Generation).isNotNull();
        assertThat(blob.getName()).isEqualTo(OBJECT_NAME);
    }

    @Test
    @Order(3)
    void uploadVersionTwo() {
        BlobInfo info = BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, OBJECT_NAME))
                .setContentType("text/plain")
                .build();
        Blob blob = storage.create(info, CONTENT_V2.getBytes(StandardCharsets.UTF_8));
        v2Generation = blob.getGeneration();
        assertThat(v2Generation).isNotNull();
        assertThat(v2Generation).isGreaterThan(v1Generation);
    }

    @Test
    @Order(4)
    void listVersionsShowsBothGenerations() {
        List<Long> generations = new ArrayList<>();
        storage.list(BUCKET_NAME, Storage.BlobListOption.versions(true))
                .iterateAll()
                .forEach(b -> {
                    if (OBJECT_NAME.equals(b.getName())) {
                        generations.add(b.getGeneration());
                    }
                });
        assertThat(generations).hasSize(2);
        assertThat(generations).contains(v1Generation, v2Generation);
    }

    @Test
    @Order(5)
    void readCurrentVersionReturnsV2() {
        Blob current = storage.get(BlobId.of(BUCKET_NAME, OBJECT_NAME));
        assertThat(current).isNotNull();
        String content = new String(current.getContent(), StandardCharsets.UTF_8);
        assertThat(content).isEqualTo(CONTENT_V2);
        assertThat(current.getGeneration()).isEqualTo(v2Generation);
    }

    @Test
    @Order(6)
    void readSpecificGenerationReturnsOldContent() {
        Blob v1 = storage.get(BlobId.of(BUCKET_NAME, OBJECT_NAME, v1Generation));
        assertThat(v1).isNotNull();
        String content = new String(v1.getContent(), StandardCharsets.UTF_8);
        assertThat(content).isEqualTo(CONTENT_V1);
    }

    @Test
    @Order(7)
    void deleteSpecificVersionRemovesOnlyThatVersion() {
        storage.delete(BlobId.of(BUCKET_NAME, OBJECT_NAME, v1Generation));

        List<Long> remaining = new ArrayList<>();
        storage.list(BUCKET_NAME, Storage.BlobListOption.versions(true))
                .iterateAll()
                .forEach(b -> {
                    if (OBJECT_NAME.equals(b.getName())) {
                        remaining.add(b.getGeneration());
                    }
                });
        assertThat(remaining).hasSize(1);
        assertThat(remaining).contains(v2Generation);

        Blob current = storage.get(BlobId.of(BUCKET_NAME, OBJECT_NAME));
        assertThat(current).isNotNull();
        assertThat(current.getGeneration()).isEqualTo(v2Generation);
    }

    @Test
    @Order(8)
    void deleteCurrentObjectCreatesDeleteMarker() {
        storage.delete(BlobId.of(BUCKET_NAME, OBJECT_NAME));

        Blob gone = storage.get(BlobId.of(BUCKET_NAME, OBJECT_NAME));
        assertThat(gone).isNull();

        List<Blob> allVersions = new ArrayList<>();
        storage.list(BUCKET_NAME, Storage.BlobListOption.versions(true))
                .iterateAll()
                .forEach(b -> {
                    if (OBJECT_NAME.equals(b.getName())) {
                        allVersions.add(b);
                    }
                });
        assertThat(allVersions).isNotEmpty();
    }

    @Test
    @Order(9)
    void cleanupVersionedBucket() {
        storage.list(BUCKET_NAME, Storage.BlobListOption.versions(true))
                .iterateAll()
                .forEach(b -> storage.delete(BlobId.of(BUCKET_NAME, b.getName(), b.getGeneration())));
        Bucket bucket = storage.get(BUCKET_NAME);
        assertThat(bucket).isNotNull();
        assertThat(bucket.delete()).isTrue();
    }
}
