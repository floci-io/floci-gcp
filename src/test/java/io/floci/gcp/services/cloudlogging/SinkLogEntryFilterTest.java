package io.floci.gcp.services.cloudlogging;

import io.floci.gcp.services.cloudlogging.model.StoredLogEntry;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SinkLogEntryFilterTest {
    @Test
    void booleanGroupingImplicitConjunctionAndOrPrecedenceMatchLogging() {
        StoredLogEntry entry = entry();
        assertTrue(SinkLogEntryFilter.parse("severity>=ERROR\nresource.type=global").test(entry));
        assertTrue(SinkLogEntryFilter.parse("(severity=ERROR OR severity=WARNING) AND NOT labels.env=dev").test(entry));
        assertFalse(SinkLogEntryFilter.parse("severity=DEBUG AND severity=ERROR OR resource.type=global").test(entry));
        assertTrue(SinkLogEntryFilter.parse("textPayload:\"AND OR NOT\"").test(entry));
        assertFalse(SinkLogEntryFilter.parse("labels.absent!=value").test(entry));
        assertTrue(SinkLogEntryFilter.parse("NOT labels.absent=value").test(entry));
    }

    @Test
    void jsonAndResourceLabelsAreEvaluatedWithoutInternalFields() {
        StoredLogEntry entry = entry();
        assertTrue(SinkLogEntryFilter.parse("jsonPayload.count>=2 AND jsonPayload.nested.\"a.b\"=yes").test(entry));
        assertTrue(SinkLogEntryFilter.parse("jsonPayload.items=second").test(entry));
        assertTrue(SinkLogEntryFilter.parse("jsonPayload.count:* AND resource.labels.\"project_id\"=source").test(entry));
        assertFalse(SinkLogEntryFilter.parse("jsonPayload.count<2").test(entry));
        assertTrue(SinkLogEntryFilter.parse("timestamp>=\"2026-10-20T00:00:00Z\"").test(entry));
        assertTrue(SinkLogEntryFilter.parse("labels.env=PROD").test(entry));
        assertTrue(SinkLogEntryFilter.parse("textPayload:\"quoted \\\"value\\\"\"").test(entry));
    }

    @Test
    void unsupportedExpressionsNeverFallThroughToMatchAll() {
        for (String filter : List.of("protoPayload.methodName:Group", "sample(insertId, 0.5)",
                "textPayload=~\"secret.*\"", "severity=ERROR OR", "labels.env=prod garbage",
                "(severity=ERROR", "textPayload=\"unterminated", "jsonPayload..field=1", "sequence=1", "labels.env=:", "labels.env=\"prod\"garbage")) {
            assertThrows(RuntimeException.class, () -> SinkLogEntryFilter.parse(filter), filter);
        }
    }

    @Test
    void presenceDistinguishesExplicitJsonNullFromMissingFields() {
        StoredLogEntry entry = entry();
        Map<String, Object> payload = new HashMap<>();
        payload.put("presentNull", null);
        payload.put("items", List.of(Map.of("nested", "exists")));
        entry.setJsonPayload(payload);
        assertTrue(SinkLogEntryFilter.parse("jsonPayload.presentNull:*").test(entry));
        assertFalse(SinkLogEntryFilter.parse("NOT jsonPayload.presentNull:*").test(entry));
        assertFalse(SinkLogEntryFilter.parse("jsonPayload.missing:*").test(entry));
        assertTrue(SinkLogEntryFilter.parse("jsonPayload.items.nested:*").test(entry));
    }

    @Test
    void defaultedScalarStringsDifferFromMissingMapFields() {
        StoredLogEntry entry = entry();
        assertTrue(SinkLogEntryFilter.parse("trace=\"\" AND spanId=\"\"").test(entry));
        assertFalse(SinkLogEntryFilter.parse("trace!=\"\"").test(entry));
        assertFalse(SinkLogEntryFilter.parse("labels.missing=\"\"").test(entry));
        assertFalse(SinkLogEntryFilter.parse("trace:*").test(entry));
        assertThrows(RuntimeException.class, () -> SinkLogEntryFilter.parse("severity:*"));
    }

    private static StoredLogEntry entry() {
        StoredLogEntry entry = new StoredLogEntry();
        entry.setSeverity("ERROR");
        entry.setResourceType("global");
        entry.setResourceLabels(Map.of("project_id", "source"));
        entry.setLabels(Map.of("env", "prod"));
        entry.setTimestamp("2026-10-20T01:00:00Z");
        entry.setTextPayload("AND OR NOT and quoted \"value\"");
        entry.setJsonPayload(Map.of("count", 2, "nested", Map.of("a.b", "yes"), "items", List.of("first", "second")));
        return entry;
    }
}
