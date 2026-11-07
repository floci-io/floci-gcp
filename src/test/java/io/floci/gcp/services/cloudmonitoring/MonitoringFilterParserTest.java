package io.floci.gcp.services.cloudmonitoring;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.cloudmonitoring.MonitoringFilterParser.Scope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonitoringFilterParserTest {

    private static final Map<String, String> ITEM = Map.of(
            "project", "p1",
            "metric.type", "custom.googleapis.com/cpu",
            "metric.labels.env", "prod-1",
            "metric.labels.count", "5",
            "metric.labels.flag", "true",
            "resource.type", "gce_instance",
            "resource.labels.zone", "us-east1-b");

    private static boolean matches(String filter) {
        return MonitoringFilterParser.parse(filter, Scope.TIME_SERIES).matches(ITEM::get);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "metric.type = \"custom.googleapis.com/cpu\"",
            "metric.type=\"custom.googleapis.com/cpu\"",
            "metric.type = starts_with(\"custom.\")",
            "metric.type = ends_with(\"/cpu\")",
            "metric.type : \"googleapis\"",
            "metric.labels.env = has_substring(\"od-\")",
            "metric.labels.env = one_of(\"a\", \"prod-1\")",
            "metric.labels.env = monitoring.regex.full_match(\"prod-[0-9]\")",
            "metric.labels.env != \"other\"",
            "metric.labels.missing != \"other\"",
            "metric.labels.env != starts_with(\"dev\")",
            "metric.labels.count = 5",
            "metric.labels.count > 4",
            "metric.labels.count <= 5",
            "metric.labels.flag = true",
            "resource.labels:zone",
            "resource.labels:\"zone\"",
            "project = \"p1\"",
            "metric.type = starts_with(\"custom.\") resource.type = \"gce_instance\"",
            "metric.type = starts_with(\"custom.\") AND resource.type = \"gce_instance\"",
            "resource.type = \"gce_instance\" AND (resource.labels.zone = starts_with(\"eu\") OR resource.labels.zone = starts_with(\"us\"))",
            // OR binds tighter than AND: (a OR b) AND c.
            "resource.type = \"x\" OR resource.type = \"gce_instance\" AND metric.labels.count = 5",
    })
    void matchingFilters(String filter) {
        assertTrue(matches(filter), filter);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "metric.type = \"custom.googleapis.com/cp\"",
            "metric.type = starts_with(\"compute.\")",
            "metric.labels.missing = \"x\"",
            "metric.labels.missing : \"x\"",
            "metric.labels.env = one_of(\"a\", \"b\")",
            // Fully matched, not a search.
            "metric.labels.env = monitoring.regex.full_match(\"prod\")",
            "metric.labels.env != \"prod-1\"",
            "metric.labels.count > 5",
            "metric.labels.env > 1",
            "resource.labels:missing",
            "project = \"elsewhere\"",
            "group.id = \"123\"",
            "group.id != \"123\"",
            // OR binds tighter than AND: without that this would match on its last comparison.
            "resource.type = \"x\" AND resource.type = \"y\" OR resource.type = \"gce_instance\"",
    })
    void nonMatchingFilters(String filter) {
        assertFalse(matches(filter), filter);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "metric.type",
            "metric.type =",
            "= \"x\"",
            "metric.type = x",
            "metric.type = 'x'",
            "metric.type = \"x",
            "metric.type = \"x\" and resource.type = \"y\"",
            "metric.type = \"x\" or resource.type = \"y\"",
            "metric.type = \"x\" AND",
            "AND metric.type = \"x\"",
            "(metric.type = \"x\"",
            "metric.type = \"x\")",
            "NOT metric.type = \"x\"",
            "metadata.user_labels.a = \"x\"",
            "metric.labels.a < \"x\"",
            "metric.labels.a : starts_with(\"x\")",
            "metric.labels.a > starts_with(\"x\")",
            "metric.labels.a = unknown(\"x\")",
            "metric.labels.a = starts_with(\"x\", \"y\")",
            "metric.labels.a = has_substring(\"x\", true)",
            "metric.labels.a = monitoring.regex.full_match(\"(\")",
            "metric.type = \"x\" OR resource.type = \"y\"",
            "metric.labels.a = !\"x\"",
            "metric.labels.a = 1.2.3",
    })
    void rejectedFilters(String filter) {
        GcpException error = assertThrows(GcpException.class, () -> matches(filter), filter);
        assertEquals("INVALID_ARGUMENT", error.getGcpStatus());
        assertTrue(error.getMessage().startsWith("Invalid monitoring filter: "), error.getMessage());
    }

    @Test
    void oneOfTakesUpToAHundredStrings() {
        assertFalse(matches(oneOf(100)));
        assertThrows(GcpException.class, () -> matches(oneOf(101)));
    }

    private static String oneOf(int count) {
        return IntStream.range(0, count).mapToObj(i -> "\"v" + i + "\"")
                .collect(Collectors.joining(",", "metric.labels.env = one_of(", ")"));
    }

    @Test
    void eachMethodNamesItsOwnObjects() {
        assertThrows(GcpException.class,
                () -> MonitoringFilterParser.parse("metric.labels.a = \"x\"", Scope.METRIC_DESCRIPTORS));
        assertThrows(GcpException.class,
                () -> MonitoringFilterParser.parse("resource.labels.a = \"x\"", Scope.RESOURCE_DESCRIPTORS));
        assertThrows(GcpException.class,
                () -> MonitoringFilterParser.parse("project = \"p\"", Scope.RESOURCE_DESCRIPTORS));
        assertTrue(MonitoringFilterParser.parse("resource.label:zone", Scope.RESOURCE_DESCRIPTORS)
                .matches(ITEM::get));
    }

    @Test
    void hasSubstringIgnoresCaseOutsideTimeSeriesOnly() {
        assertTrue(MonitoringFilterParser.parse("metric.type = has_substring(\"CUSTOM\", true)",
                Scope.METRIC_DESCRIPTORS).matches(ITEM::get));
        assertFalse(MonitoringFilterParser.parse("metric.type = has_substring(\"CUSTOM\", false)",
                Scope.METRIC_DESCRIPTORS).matches(ITEM::get));
    }

    @Test
    void anEmptyFilterMatchesEverything() {
        assertTrue(MonitoringFilterParser.parse(" ", Scope.METRIC_DESCRIPTORS).matches(ITEM::get));
    }

    @Test
    void requiredMetricTypeComesOnlyFromATopLevelEquality() {
        assertEquals(Optional.of("a"), required("metric.type = \"a\" AND resource.type = \"r\""));
        assertEquals(Optional.of("a"), required("(metric.type = \"a\") AND (resource.type = \"r\")"));
        assertEquals(Optional.empty(), required("metric.type = starts_with(\"a\")"));
        assertEquals(Optional.empty(), required("metric.type != \"a\""));
        assertEquals(Optional.empty(), required("metric.type = \"a\" OR metric.type = \"b\""));
        assertEquals(Optional.empty(), required("metric.type = \"a\" AND metric.type = \"b\""));
        assertEquals(Optional.empty(), required("resource.type = \"r\""));
    }

    private static Optional<String> required(String filter) {
        return MonitoringFilterParser.parse(filter, Scope.TIME_SERIES).requiredMetricType();
    }
}
