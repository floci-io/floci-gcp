package io.floci.gcp.services.cloudlogging;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.cloudlogging.model.StoredLogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CloudLoggingServiceTest {

    private static final String PROJECT = "projects/p1";
    private static final String LOG = PROJECT + "/logs/applog";

    private CloudLoggingService service;

    @BeforeEach
    void setUp() {
        service = new CloudLoggingService(new InMemoryStorage<>());
    }

    private StoredLogEntry text(String logName, String severity, String payload) {
        StoredLogEntry e = new StoredLogEntry();
        e.setLogName(logName);
        e.setSeverity(severity);
        e.setTextPayload(payload);
        return e;
    }

    private void write(StoredLogEntry entry) {
        service.writeLogEntries(null, null, null, null, List.of(entry), false);
    }

    private List<StoredLogEntry> list(String filter) {
        return service.listLogEntries(List.of(PROJECT), filter, null, 0, null).items();
    }

    @Test
    void writeThenListRoundTrip() {
        StoredLogEntry e = text(LOG, "INFO", "hello world");
        e.setResourceType("gce_instance");
        write(e);

        List<StoredLogEntry> entries = list(null);
        assertEquals(1, entries.size());
        StoredLogEntry got = entries.get(0);
        assertEquals("hello world", got.getTextPayload());
        assertEquals("INFO", got.getSeverity());
        assertEquals("gce_instance", got.getResourceType());
        assertNotNull(got.getInsertId());
        assertNotNull(got.getReceiveTimestamp());
        assertNotNull(got.getTimestamp());
    }

    @Test
    void requestLevelDefaultsApplied() {
        service.writeLogEntries(LOG, "gce_instance", Map.of("zone", "us-central1-a"),
                Map.of("env", "test"), List.of(new StoredLogEntry()), false);

        StoredLogEntry got = list(null).get(0);
        assertEquals(LOG, got.getLogName());
        assertEquals("gce_instance", got.getResourceType());
        assertEquals("us-central1-a", got.getResourceLabels().get("zone"));
        assertEquals("test", got.getLabels().get("env"));
    }

    @Test
    void entryLabelsWinOverRequestDefaults() {
        StoredLogEntry e = text(LOG, "INFO", "x");
        e.setLabels(Map.of("env", "prod"));
        service.writeLogEntries(null, null, null, Map.of("env", "test", "team", "core"), List.of(e), false);

        StoredLogEntry got = list(null).get(0);
        assertEquals("prod", got.getLabels().get("env"));
        assertEquals("core", got.getLabels().get("team"));
    }

    @Test
    void missingResourceDefaultsToGlobal() {
        write(text(LOG, "INFO", "x"));
        assertEquals("global", list(null).get(0).getResourceType());
    }

    @Test
    void severityFilterSelectsMatchingEntries() {
        write(text(LOG, "INFO", "info-msg"));
        write(text(LOG, "ERROR", "error-msg"));

        List<StoredLogEntry> matched = list("severity >= WARNING");
        assertEquals(1, matched.size());
        assertEquals("error-msg", matched.get(0).getTextPayload());
    }

    @Test
    void logNameAndResourceTypeFilters() {
        StoredLogEntry a = text(PROJECT + "/logs/a", "INFO", "a");
        a.setResourceType("gce_instance");
        StoredLogEntry b = text(PROJECT + "/logs/b", "INFO", "b");
        b.setResourceType("k8s_container");
        write(a);
        write(b);

        assertEquals(1, list("logName = \"" + PROJECT + "/logs/a\"").size());
        assertEquals(1, list("resource.type = \"k8s_container\"").size());
        assertEquals("b", list("resource.type = \"k8s_container\"").get(0).getTextPayload());
    }

    @Test
    void orderByDescReversesOrder() {
        write(text(LOG, "INFO", "first"));
        write(text(LOG, "INFO", "second"));
        write(text(LOG, "INFO", "third"));

        List<StoredLogEntry> desc =
                service.listLogEntries(List.of(PROJECT), null, "timestamp desc", 0, null).items();
        assertEquals("third", desc.get(0).getTextPayload());
        assertEquals("first", desc.get(2).getTextPayload());
    }

    @Test
    void paginationReturnsToken() {
        write(text(LOG, "INFO", "1"));
        write(text(LOG, "INFO", "2"));
        write(text(LOG, "INFO", "3"));

        PageToken.Page<StoredLogEntry> page1 =
                service.listLogEntries(List.of(PROJECT), null, null, 2, null);
        assertEquals(2, page1.items().size());
        assertNotNull(page1.nextPageToken());

        PageToken.Page<StoredLogEntry> page2 =
                service.listLogEntries(List.of(PROJECT), null, null, 2, page1.nextPageToken());
        assertEquals(1, page2.items().size());
        assertNull(page2.nextPageToken());
    }

    @Test
    void listLogsReturnsDistinctNames() {
        write(text(PROJECT + "/logs/a", "INFO", "x"));
        write(text(PROJECT + "/logs/a", "INFO", "y"));
        write(text(PROJECT + "/logs/b", "INFO", "z"));

        List<String> logs = service.listLogs(PROJECT);
        assertEquals(List.of(PROJECT + "/logs/a", PROJECT + "/logs/b"), logs);
    }

    @Test
    void deleteLogRemovesEntries() {
        write(text(LOG, "INFO", "x"));
        service.deleteLog(LOG);
        assertTrue(list(null).isEmpty());

        GcpException ex = assertThrows(GcpException.class, () -> service.deleteLog(LOG));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    @Test
    void dryRunDoesNotPersist() {
        service.writeLogEntries(null, null, null, null, List.of(text(LOG, "INFO", "x")), true);
        assertTrue(list(null).isEmpty());
    }

    @Test
    void jsonPayloadRoundTrip() {
        StoredLogEntry e = new StoredLogEntry();
        e.setLogName(LOG);
        e.setSeverity("INFO");
        e.setJsonPayload(Map.of("user", "alice", "count", 3));
        write(e);

        Object payload = list(null).get(0).getJsonPayload();
        assertInstanceOf(Map.class, payload);
        assertEquals("alice", ((Map<?, ?>) payload).get("user"));
    }

    @Test
    void listRequiresResourceNames() {
        GcpException ex = assertThrows(GcpException.class,
                () -> service.listLogEntries(List.of(), null, null, 0, null));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }
}
