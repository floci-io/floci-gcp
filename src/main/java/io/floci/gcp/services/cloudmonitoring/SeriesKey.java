package io.floci.gcp.services.cloudmonitoring;

import io.floci.gcp.services.cloudmonitoring.model.StoredTimeSeriesPoint;

import java.util.Map;

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

    /** This series without its kind and value type, which do not tell two series apart. */
    SeriesKey identity() {
        return new SeriesKey(metricType, metricLabels, resourceType, resourceLabels, "", "");
    }
}
