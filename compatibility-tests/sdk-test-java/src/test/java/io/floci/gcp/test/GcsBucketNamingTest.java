package io.floci.gcp.test;

import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bucket names are usually baked into configuration, so a name the emulator accepts and real
 * GCS refuses fails at deploy rather than during development. Every name below is rejected by
 * the documented Cloud Storage naming rules
 * (https://cloud.google.com/storage/docs/buckets#naming).
 */
class GcsBucketNamingTest {

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
    void invalidBucketNamesAreRejectedWith400() {
        List<String> invalid = List.of(
                "ab",                       // shorter than 3 characters
                "UpperCase",                // must be lowercase
                "has space",                // no spaces
                "-leading-hyphen",          // must start with a letter or number
                "trailing-hyphen-",         // must end with a letter or number
                "double..dot",              // no consecutive dots
                "192.168.5.4",              // must not be formatted as an IP address
                "goog-reserved",            // the 'goog' prefix is reserved
                "contains-google-name",     // 'google' is reserved
                "a".repeat(64));            // over 63 characters without dot separation

        for (String name : invalid) {
            assertThatThrownBy(() -> storage.create(BucketInfo.of(name)))
                    .describedAs("bucket name %s should be rejected", name)
                    .isInstanceOf(StorageException.class)
                    .satisfies(e -> assertThat(((StorageException) e).getCode()).isEqualTo(400));
        }
    }

    @Test
    void dotSeparatedNamesMayExceedSixtyThreeCharacters() {
        // Legal in GCS above 63 characters only when every dot-separated label is at most 63,
        // with the whole name at most 222.
        String label = "a".repeat(60);
        String name = String.join(".", label, label, label);
        assertThat(name.length()).isGreaterThan(63).isLessThanOrEqualTo(222);

        try {
            assertThat(storage.create(BucketInfo.of(name)).getName()).isEqualTo(name);
        } finally {
            storage.delete(name);
        }
    }

    @Test
    void duplicateBucketReportsConflictReason() {
        String name = TestFixtures.uniqueName("duplicate-bucket");
        storage.create(BucketInfo.of(name));
        try {
            // GCS documents 'conflict' as the only reason it returns for a 409 here, and
            // clients branch on it rather than on the status code alone.
            assertThatThrownBy(() -> storage.create(BucketInfo.of(name)))
                    .isInstanceOf(StorageException.class)
                    .satisfies(e -> {
                        StorageException se = (StorageException) e;
                        assertThat(se.getCode()).isEqualTo(409);
                        assertThat(se.getReason()).isEqualTo("conflict");
                    });
        } finally {
            storage.delete(name);
        }
    }
}
