package io.floci.gcp.core.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class GcpResourceNamesTest {

    @Test
    void parseProject_standardPath() {
        assertEquals("my-project", GcpResourceNames.parseProject("projects/my-project/topics/my-topic"));
    }

    @Test
    void parseProject_pathWithoutTrailingSegment() {
        assertEquals("my-project", GcpResourceNames.parseProject("projects/my-project"));
    }

    @Test
    void parseProject_null() {
        assertNull(GcpResourceNames.parseProject(null));
    }

    @Test
    void parseProject_noProjectsPrefix() {
        assertNull(GcpResourceNames.parseProject("topics/my-topic"));
    }

    @Test
    void parseLocation_standardPath() {
        assertEquals("us-central1",
                GcpResourceNames.parseLocation("projects/p/locations/us-central1/clusters/c1"));
    }

    @Test
    void parseLocation_missingOrNull() {
        assertNull(GcpResourceNames.parseLocation("projects/p/topics/t"));
        assertNull(GcpResourceNames.parseLocation(null));
    }

    @Test
    void parseProject_deepPath() {
        assertEquals("p", GcpResourceNames.parseProject("projects/p/databases/(default)/documents/col/doc"));
    }

    @Test
    void lastSegment_standard() {
        assertEquals("my-topic", GcpResourceNames.lastSegment("projects/my-project/topics/my-topic"));
    }

    @Test
    void lastSegment_singleSegment() {
        assertEquals("my-topic", GcpResourceNames.lastSegment("my-topic"));
    }

    @Test
    void lastSegment_null() {
        assertNull(GcpResourceNames.lastSegment(null));
    }

    @Test
    void lastSegment_empty() {
        assertEquals("", GcpResourceNames.lastSegment(""));
    }

    @Test
    void topic_buildsCorrectName() {
        assertEquals("projects/proj/topics/t", GcpResourceNames.topic("proj", "t"));
    }

    @Test
    void subscription_buildsCorrectName() {
        assertEquals("projects/proj/subscriptions/s", GcpResourceNames.subscription("proj", "s"));
    }

    @Test
    void secret_buildsCorrectName() {
        assertEquals("projects/proj/secrets/sec", GcpResourceNames.secret("proj", "sec"));
    }

    @Test
    void secretVersion_buildsCorrectName() {
        assertEquals("projects/proj/secrets/sec/versions/1", GcpResourceNames.secretVersion("proj", "sec", "1"));
    }

    @Test
    void firestoreDocument_buildsCorrectName() {
        assertEquals(
                "projects/proj/databases/(default)/documents/col/doc",
                GcpResourceNames.firestoreDocument("proj", "(default)", "col/doc"));
    }

    @Test
    void parseLocationParent_wellFormed() {
        GcpResourceNames.ProjectLocation parsed = GcpResourceNames.parseLocationParent("projects/p1/locations/us-east1");
        assertEquals("p1", parsed.project());
        assertEquals("us-east1", parsed.location());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"foo", "projects/p", "projects/p/locations", "projects/p/locations/",
            "projects/p/regions/r", "projects//locations/l", "organizations/p/locations/l",
            "projects/p/locations/l/queues/q", "/projects/p/locations/l"})
    void parseLocationParent_malformedIsInvalidArgument(String parent) {
        GcpException ex = assertThrows(GcpException.class, () -> GcpResourceNames.parseLocationParent(parent));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }
}
