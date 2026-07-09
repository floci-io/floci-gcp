package io.floci.gcp.services.cloudmonitoring;

import io.floci.gcp.services.cloudmonitoring.model.StoredTimeSeriesPoint;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

record SeriesKey(
        String metricType,
        Map<String, String> metricLabels,
        String resourceType,
        Map<String, String> resourceLabels,
        String metricKind,
        String valueType
) {

    static SeriesKey of(StoredTimeSeriesPoint pt) {
        return new SeriesKey(
                pt.getMetricType(),
                pt.getMetricLabels() != null ? pt.getMetricLabels() : Map.of(),
                pt.getResourceType(),
                pt.getResourceLabels() != null ? pt.getResourceLabels() : Map.of(),
                pt.getMetricKind(),
                pt.getValueType());
    }

    String identityString() {
        return metricType + "|" + resourceType
                + "|" + sortedLabels(metricLabels)
                + "|" + sortedLabels(resourceLabels);
    }

    String canonicalString() {
        return identityString() + "|" + metricKind + "|" + valueType;
    }

    private static String sortedLabels(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return "";
        }
        return new TreeMap<>(labels).entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
    }
}
