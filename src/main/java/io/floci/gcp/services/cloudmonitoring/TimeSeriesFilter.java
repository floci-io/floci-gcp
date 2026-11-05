package io.floci.gcp.services.cloudmonitoring;

import io.floci.gcp.services.cloudmonitoring.model.StoredTimeSeriesPoint;

import java.util.Map;
import java.util.function.Predicate;

/** Reads a {@code timeSeries.list} filter against stored points of one project. */
public final class TimeSeriesFilter {

    private static final String METRIC_LABELS = "metric.labels.";
    private static final String RESOURCE_LABELS = "resource.labels.";

    public record ParsedFilter(Predicate<StoredTimeSeriesPoint> predicate, String metricTypeEquality) {}

    private TimeSeriesFilter() {}

    public static ParsedFilter parseFilter(String filter, String project) {
        MonitoringFilterParser.Filter parsed =
                MonitoringFilterParser.parse(filter, MonitoringFilterParser.Scope.TIME_SERIES);
        return new ParsedFilter(point -> parsed.matches(object -> value(point, object, project)),
                parsed.requiredMetricType().orElse(null));
    }

    private static String value(StoredTimeSeriesPoint point, String object, String project) {
        if (object.equals("project")) {
            return project;
        }
        if (object.equals("metric.type")) {
            return point.getMetricType();
        }
        if (object.equals("resource.type")) {
            return point.getResourceType();
        }
        if (object.startsWith(METRIC_LABELS)) {
            return label(point.getMetricLabels(), object.substring(METRIC_LABELS.length()));
        }
        if (object.startsWith(RESOURCE_LABELS)) {
            return label(point.getResourceLabels(), object.substring(RESOURCE_LABELS.length()));
        }
        return null;
    }

    private static String label(Map<String, String> labels, String key) {
        return labels != null ? labels.get(key) : null;
    }
}
