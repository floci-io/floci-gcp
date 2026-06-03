package io.floci.gcp.test;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for issue #4: listing with a delimiter omitted the
 * top-level {@code prefixes[]} array, so clients could not enumerate
 * "directories". The Java SDK surfaces prefixes as Blob entries whose names
 * end with the delimiter.
 */
class GcsListPrefixesTest {

    private static final String BUCKET_NAME = TestFixtures.uniqueName("prefixes-bucket");

    private static Storage storage;

    @BeforeAll
    static void setUp() {
        storage = TestFixtures.storageClient();
        storage.create(BucketInfo.of(BUCKET_NAME));
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (storage instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    @Test
    void listWithDelimiterReturnsPrefixes() {
        storage.create(BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, "a/1")).build(),
                "1".getBytes(StandardCharsets.UTF_8));
        storage.create(BlobInfo.newBuilder(BlobId.of(BUCKET_NAME, "b/2")).build(),
                "2".getBytes(StandardCharsets.UTF_8));

        List<String> names = new ArrayList<>();
        storage.list(BUCKET_NAME, Storage.BlobListOption.currentDirectory())
                .iterateAll().forEach(b -> names.add(b.getName()));

        assertThat(names).contains("a/", "b/");
        assertThat(names).doesNotContain("a/1", "b/2");
    }
}
