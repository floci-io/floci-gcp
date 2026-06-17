package io.floci.gcp.services.cloudmonitoring;

import io.floci.gcp.services.cloudmonitoring.model.StoredTimeSeriesPoint;
import org.jboss.logging.Logger;

import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TimeSeriesFilter {

    private static final Logger LOG = Logger.getLogger(TimeSeriesFilter.class);

    private static final Pattern CLAUSE =
            Pattern.compile("^(\\S+?)\\s*(=)\\s*(.+)$");

    private TimeSeriesFilter() {}

    public static Predicate<StoredTimeSeriesPoint> parse(String filter) {
        if (filter == null || filter.isBlank()) {
            return point -> true;
        }
        Predicate<StoredTimeSeriesPoint> predicate = point -> true;
        // Split by AND, keeping in mind case insensitivity
        for (String clause : filter.split("(?i)\\s+AND\\s+")) {
            String trimmed = clause.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            predicate = predicate.and(parseClause(trimmed));
        }
        return predicate;
    }

    private static Predicate<StoredTimeSeriesPoint> parseClause(String clause) {
        Matcher m = CLAUSE.matcher(clause);
        if (!m.matches()) {
            LOG.debugf("Ignoring unrecognized monitoring filter clause: %s", clause);
            return point -> true;
        }
        String field = m.group(1);
        String op = m.group(2);
        String value = unquote(m.group(3).trim());

        if (field.equalsIgnoreCase("metric.type")) {
            return point -> value.equals(point.getMetricType());
        }
        if (field.equalsIgnoreCase("resource.type")) {
            return point -> value.equals(point.getResourceType());
        }
        if (field.startsWith("metric.labels.")) {
            String labelKey = field.substring("metric.labels.".length());
            return point -> point.getMetricLabels() != null && value.equals(point.getMetricLabels().get(labelKey));
        }
        if (field.startsWith("resource.labels.")) {
            String labelKey = field.substring("resource.labels.".length());
            return point -> point.getResourceLabels() != null && value.equals(point.getResourceLabels().get(labelKey));
        }

        LOG.debugf("Ignoring unsupported monitoring filter field: %s", field);
        return point -> true;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        if (s.length() >= 2 && s.startsWith("'") && s.endsWith("'")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
