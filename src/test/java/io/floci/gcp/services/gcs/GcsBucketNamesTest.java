package io.floci.gcp.services.gcs;

import io.floci.gcp.core.common.GcpException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Bucket-name rules. Enforced locally because bucket names are usually baked into config, so a
 * name accepted here and rejected by GCS fails at deploy rather than during development.
 */
class GcsBucketNamesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "ab",
            "Invalid-Bucket",
            "has space",
            "_leading-underscore",
            "-leading-hyphen",
            "trailing-hyphen-",
            "trailing-underscore_",
            "double..dot",
            "192.168.5.4",
            "goog-reserved",
            "my-google-bucket",
            "contains/slash",
    })
    void rejectsInvalidNames(String name) {
        assertThrows(GcpException.class, () -> GcsBucketNames.validate(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "abc",
            "my-bucket-123",
            "my.bucket.name",
            "with_underscore",
            "1234567890",
    })
    void acceptsValidNames(String name) {
        assertDoesNotThrow(() -> GcsBucketNames.validate(name));
    }

    @Test
    void rejectsANameLongerThan63WithoutDots() {
        assertThrows(GcpException.class, () -> GcsBucketNames.validate("a".repeat(64)));
    }

    @Test
    void acceptsADottedNameLongerThan63() {
        // Dot-separated names may reach 222 characters provided each label is <= 63.
        String name = String.join(".", "a".repeat(60), "b".repeat(60), "c".repeat(60));
        assertDoesNotThrow(() -> GcsBucketNames.validate(name));
    }

    @Test
    void rejectsADottedNameWithAnOverlongLabel() {
        assertThrows(GcpException.class,
                () -> GcsBucketNames.validate("a".repeat(64) + ".suffix"));
    }

    @Test
    void reportsInvalidAsTheErrorReason() {
        GcpException ex = assertThrows(GcpException.class, () -> GcsBucketNames.validate("AB"));
        assertEquals("invalid", ex.getReason());
    }
}
