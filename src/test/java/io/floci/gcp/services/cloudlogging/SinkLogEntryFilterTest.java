package io.floci.gcp.services.cloudlogging;

import io.floci.gcp.services.cloudlogging.model.StoredLogEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @Test
    void incompatibleValuesAreFalseWithinBooleanExpressionsAndArrays() {
        StoredLogEntry entry = entry();
        for (Object value : List.of(1, true, Map.of("nested", "match"))) {
            entry.setJsonPayload(Map.of("kind", value));
            assertFalse(SinkLogEntryFilter.parse("jsonPayload.kind=match").test(entry));
            assertFalse(SinkLogEntryFilter.parse("jsonPayload.kind!=match").test(entry));
            assertTrue(SinkLogEntryFilter.parse("jsonPayload.kind=match OR severity=ERROR").test(entry));
            assertTrue(SinkLogEntryFilter.parse("NOT jsonPayload.kind=match").test(entry));
        }
        entry.setJsonPayload(Map.of("kind", List.of(Map.of("nested", "match"), 1, true, "match")));
        assertTrue(SinkLogEntryFilter.parse("jsonPayload.kind=match").test(entry));
    }

    @Test
    void stringsUseUnicodeNfkcCaseFoldingForEveryComparison() {
        StoredLogEntry entry = entry();
        entry.setTextPayload("Straße");
        assertTrue(SinkLogEntryFilter.parse("textPayload=STRASSE").test(entry));
        assertTrue(SinkLogEntryFilter.parse("textPayload:STRASSE").test(entry));
        assertTrue(SinkLogEntryFilter.parse("textPayload>=STRASSE AND textPayload<=STRASSE").test(entry));
        assertFalse(SinkLogEntryFilter.parse("textPayload!=STRASSE").test(entry));
        entry.setTextPayload("ＦＯＯ\u00adΣς");
        assertTrue(SinkLogEntryFilter.parse("textPayload=\"fooσσ\"").test(entry));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "true"})
    void containmentOnlyMatchesStringsInMixedJsonValues(String expected) {
        StoredLogEntry entry = entry();
        String filter = "jsonPayload.kind:" + expected;
        for (Object value : List.of(1, true, Map.of("nested", expected))) {
            entry.setJsonPayload(Map.of("kind", value));
            assertFalse(SinkLogEntryFilter.parse(filter).test(entry));
            assertTrue(SinkLogEntryFilter.parse(filter + " OR severity=ERROR").test(entry));
            assertTrue(SinkLogEntryFilter.parse("NOT " + filter).test(entry));
        }
        entry.setJsonPayload(Map.of("kind", List.of(1, true, "a" + expected)));
        assertTrue(SinkLogEntryFilter.parse(filter).test(entry));
    }

    @ParameterizedTest
    @ValueSource(strings = {"timestamp", "receiveTimestamp"})
    void dateOnlyTimestampComparisonsUseUtcMidnight(String field) {
        StoredLogEntry entry = entry();
        for (String timestamp : List.of("2026-10-19T23:59:59.999999999Z", "2026-10-20T00:00:00Z",
                "2026-10-20T00:00:00.000000001Z")) {
            entry.setTimestamp(timestamp);
            entry.setReceiveTimestamp(timestamp);
            for (String operator : List.of("=", "!=", "<", "<=", ">", ">=")) {
                assertEquals(SinkLogEntryFilter.parse(field + operator + "\"2026-10-20T00:00:00Z\"").test(entry),
                        SinkLogEntryFilter.parse(field + operator + "\"2026-10-20\"").test(entry));
            }
            assertEquals(SinkLogEntryFilter.parse(field + "=\"2026-10-19T17:00:00-07:00\"").test(entry),
                    SinkLogEntryFilter.parse(field + "=\"2026-10-20\"").test(entry));
        }
        for (String invalid : List.of("2026-02-30", "2026-13-01", "2026-10-20junk")) {
            assertThrows(RuntimeException.class, () -> SinkLogEntryFilter.parse(field + "=\"" + invalid + "\""));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"=", "!=", "<", "<=", ">", ">=", ":"})
    void operatorTypeMatrixPreservesAtomicComparisonResults(String operator) {
        StoredLogEntry entry = entry();
        for (ComparisonCase comparison : List.of(
                new ComparisonCase("Straße", "STRASSE", true, true),
                new ComparisonCase(1, "1", true, false),
                new ComparisonCase(true, "true", true, false),
                new ComparisonCase(Map.of("nested", "STRASSE"), "STRASSE", false, false),
                new ComparisonCase(null, "STRASSE", false, false),
                new ComparisonCase(List.of(Map.of("nested", "STRASSE"), "Straße"), "STRASSE", true, true))) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("kind", comparison.value());
            entry.setJsonPayload(payload);
            boolean matches = comparison.comparable() && switch (operator) {
                case "=", "<=", ">=" -> true;
                case ":" -> comparison.contains();
                default -> false;
            };
            String filter = "jsonPayload.kind" + operator + comparison.expected();
            assertEquals(matches, SinkLogEntryFilter.parse(filter).test(entry), filter + " against " + comparison.value());
            assertEquals(!matches, SinkLogEntryFilter.parse("NOT " + filter).test(entry));
            assertTrue(SinkLogEntryFilter.parse(filter + " OR severity=ERROR").test(entry));
            assertTrue(SinkLogEntryFilter.parse("jsonPayload.kind:*").test(entry));
        }
    }

    private record ComparisonCase(Object value, String expected, boolean comparable, boolean contains) {}

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
