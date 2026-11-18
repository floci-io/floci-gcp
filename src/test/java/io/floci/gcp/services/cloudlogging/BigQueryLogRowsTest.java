package io.floci.gcp.services.cloudlogging;

import io.floci.gcp.services.cloudlogging.model.StoredLogEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BigQueryLogRowsTest {

    @Test
    void tableNamesFollowTheExportDocumentationExamples() {
        assertEquals("syslog_20170523", BigQueryLogRows.tableId(entry("syslog", "2017-05-23T10:00:00Z"), false));
        assertEquals("syslog", BigQueryLogRows.tableId(entry("syslog", "2017-05-23T10:00:00Z"), true));
        assertEquals("apache_access_20170101", BigQueryLogRows.tableId(entry("apache-access", "2017-01-01T00:00:00Z"), false));
        assertEquals("compute_googleapis_com_activity_log_20171231",
                BigQueryLogRows.tableId(entry("compute.googleapis.com%2Factivity_log", "2017-12-31T23:59:59Z"), false));
        assertEquals("export_errors_20171231", BigQueryLogRows.errorsTableId(entry("x", "2017-12-31T23:59:59Z"), false));
        assertEquals("export_errors", BigQueryLogRows.errorsTableId(entry("x", "2017-12-31T23:59:59Z"), true));
    }

    @Test
    void rowsKeepLogEntryNamesAndNormalizeUserAndPayloadKeys() {
        StoredLogEntry entry = entry("app", "2026-11-17T10:00:00Z");
        entry.setInsertId("abc");
        entry.setLabels(Map.of("Env", "prod"));
        entry.setResourceLabels(Map.of("project_id", "p"));
        entry.setJsonPayload(Map.of("Status-Code", 200, "_hidden", "x", "foo%%", "y", "nested", Map.of("Inner.Key", true)));

        Map<String, Object> row = BigQueryLogRows.row(entry);

        assertEquals("abc", row.get("insertId"));
        assertEquals("DEFAULT", row.get("severity"));
        assertEquals(Map.of("type", "global", "labels", Map.of("project_id", "p")), row.get("resource"));
        assertEquals(Map.of("env", "prod"), row.get("labels"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) row.get("jsonPayload");
        assertEquals(200, payload.get("status_code"));
        assertEquals("x", payload.get("hidden"));
        assertEquals("y", payload.get("foo__"));
        assertEquals(Map.of("inner_key", true), payload.get("nested"));
        assertFalse(row.containsKey("textPayload"));
    }

    @Test
    void schemaInferenceAndAdditiveMergeReportTypeChanges() {
        StoredLogEntry first = entry("app", "2026-11-17T10:00:00Z");
        first.setJsonPayload(Map.of("count", 3, "tags", List.of("a", "b")));
        List<Map<String, Object>> schema = BigQueryLogRows.schema(BigQueryLogRows.row(first));
        assertTrue(schema.contains(Map.of("name", "timestamp", "type", "TIMESTAMP", "mode", "NULLABLE")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> payload = (List<Map<String, Object>>) schema.stream()
                .filter(field -> field.get("name").equals("jsonPayload")).findFirst().orElseThrow().get("fields");
        assertTrue(payload.contains(Map.of("name", "count", "type", "FLOAT", "mode", "NULLABLE")));
        assertTrue(payload.contains(Map.of("name", "tags", "type", "STRING", "mode", "REPEATED")));

        StoredLogEntry added = entry("app", "2026-11-17T10:00:00Z");
        added.setJsonPayload(Map.of("count", 4.5, "user", "u"));
        Optional<List<Map<String, Object>>> merged = BigQueryLogRows.merge(schema,
                BigQueryLogRows.schema(BigQueryLogRows.row(added)), new StringBuilder(), "");
        assertTrue(merged.isPresent());
        assertTrue(merged.get().toString().contains("name=user"));

        StoredLogEntry changed = entry("app", "2026-11-17T10:00:00Z");
        changed.setJsonPayload(Map.of("count", "three"));
        StringBuilder conflict = new StringBuilder();
        assertTrue(BigQueryLogRows.merge(schema, BigQueryLogRows.schema(BigQueryLogRows.row(changed)), conflict, "")
                .isEmpty());
        assertTrue(conflict.toString().contains("jsonPayload.count"), conflict.toString());
    }

    private static StoredLogEntry entry(String logId, String timestamp) {
        StoredLogEntry entry = new StoredLogEntry();
        entry.setLogName("projects/test-project/logs/" + logId);
        entry.setResourceType("global");
        entry.setTimestamp(timestamp);
        entry.setReceiveTimestamp(timestamp);
        return entry;
    }
}
