package io.floci.gcp.services.gcs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GcsXmlDelimiterRollupTest {

    @Test
    void namesUnderADirectoryRollUpToTheFirstDelimiterAfterThePrefix() {
        assertEquals("d1/", GcsXmlDelimiterRollup.commonPrefix("d1/x.txt", "", "/"));
        assertEquals("d1/", GcsXmlDelimiterRollup.commonPrefix("d1/sub/y.txt", "", "/"));
        assertEquals("p/d1/", GcsXmlDelimiterRollup.commonPrefix("p/d1/x.txt", "p/", "/"));
        assertEquals("d1/", GcsXmlDelimiterRollup.commonPrefix("d1/", "", "/"));
    }

    @Test
    void aMultiCharacterDelimiterIsKeptWhole() {
        assertEquals("a--", GcsXmlDelimiterRollup.commonPrefix("a--b--c", "", "--"));
        assertEquals("p/d", GcsXmlDelimiterRollup.commonPrefix("p/d1/x.txt", "p/", "d"));
    }

    @Test
    void namesWithoutADelimiterAfterThePrefixStayTheirOwnEntry() {
        assertNull(GcsXmlDelimiterRollup.commonPrefix("a.txt", "", "/"));
        assertNull(GcsXmlDelimiterRollup.commonPrefix("p/a.txt", "p/", "/"));
        assertNull(GcsXmlDelimiterRollup.commonPrefix("d1/x.txt", "", null));
    }
}
