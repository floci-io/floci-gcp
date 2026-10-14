package io.floci.gcp.core.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProjectContextFilterTest {

    @Test
    void pathSegmentWinsOverQueryAndHeader() {
        assertEquals("path-proj", ProjectContextFilter.explicitProject(
                "/v1/projects/path-proj/topics/t", "query-proj", "project=header-proj"));
        assertEquals("query-proj", ProjectContextFilter.explicitProject(
                "/storage/v1/b", "query-proj", "project=header-proj"));
        assertEquals("header-proj", ProjectContextFilter.explicitProject(
                "/storage/v1/b", null, "foo=bar&project=header-proj"));
        assertNull(ProjectContextFilter.explicitProject("/health", null, null));
    }

    @Test
    void customMethodSuffixIsDropped() {
        assertEquals("my-proj", ProjectContextFilter.explicitProject("/v1/projects/my-proj:getIamPolicy", null, null));
        assertEquals("my-proj", ProjectContextFilter.stripCustomMethod("my-proj:undelete"));
        assertEquals("example.com:my-proj", ProjectContextFilter.stripCustomMethod("example.com:my-proj"));
        assertEquals("example.com:my-proj", ProjectContextFilter.stripCustomMethod("example.com:my-proj:getIamPolicy"));
    }

    @Test
    void encodedSlashesInsideObjectNamesAreNotProjectSegments() {
        assertNull(ProjectContextFilter.explicitProject("/storage/v1/b/bucket/o/a%2Fprojects%2Fevil", null, null));
        assertEquals("example.com:my-proj",
                ProjectContextFilter.explicitProject("/v1/projects/example.com%3Amy-proj/secrets", null, null));
    }
}
