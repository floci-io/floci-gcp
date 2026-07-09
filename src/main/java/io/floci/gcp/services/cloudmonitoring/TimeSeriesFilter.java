package io.floci.gcp.services.cloudmonitoring;

import io.floci.gcp.services.cloudmonitoring.model.StoredTimeSeriesPoint;
import org.jboss.logging.Logger;

import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TimeSeriesFilter {

    private static final Logger LOG = Logger.getLogger(TimeSeriesFilter.class);

    private static final Pattern CLAUSE =
            Pattern.compile("^(\\S+?)\\s*(=)\\s*(.+)$");
    private static final Pattern STARTS_WITH =
            Pattern.compile("^starts_with\\(\\s*\"(.*)\"\\s*\\)$");

    public record ParsedFilter(Predicate<StoredTimeSeriesPoint> predicate, String metricTypeEquality) {}

    private TimeSeriesFilter() {}

    public static Predicate<StoredTimeSeriesPoint> parse(String filter) {
        return parseFilter(filter).predicate();
    }

    public static ParsedFilter parseFilter(String filter) {
        if (filter == null || filter.isBlank()) {
            return new ParsedFilter(point -> true, null);
        }
        Predicate<StoredTimeSeriesPoint> predicate = point -> true;
        String metricTypeEquality = null;
        // Split by AND, keeping in mind case insensitivity
        for (String clause : filter.split("(?i)\\s+AND\\s+")) {
            String trimmed = clause.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            ParsedClause parsed = parseClause(trimmed);
            predicate = predicate.and(parsed.predicate());
            if (parsed.metricTypeEquality() != null) {
                metricTypeEquality = parsed.metricTypeEquality();
            }
        }
        return new ParsedFilter(predicate, metricTypeEquality);
    }

    private record ParsedClause(Predicate<StoredTimeSeriesPoint> predicate, String metricTypeEquality) {}

    private static ParsedClause parseClause(String clause) {
        Matcher m = CLAUSE.matcher(clause);
        if (!m.matches()) {
            LOG.debugf("Ignoring unrecognized monitoring filter clause: %s", clause);
            return new ParsedClause(point -> true, null);
        }
        String field = m.group(1);
        String rawValue = m.group(3).trim();

        Matcher sw = STARTS_WITH.matcher(rawValue);
        boolean startsWith = sw.matches();
        String value = startsWith ? sw.group(1) : unquote(rawValue);

        Function<StoredTimeSeriesPoint, String> extractor = extractor(field);
        if (extractor == null) {
            LOG.debugf("Ignoring unsupported monitoring filter field: %s", field);
            return new ParsedClause(point -> true, null);
        }

        Predicate<StoredTimeSeriesPoint> predicate = startsWith
                ? point -> {
                    String actual = extractor.apply(point);
                    return actual != null && actual.startsWith(value);
                }
                : point -> value.equals(extractor.apply(point));

        String metricTypeEquality = !startsWith && field.equalsIgnoreCase("metric.type") ? value : null;
        return new ParsedClause(predicate, metricTypeEquality);
    }

    private static Function<StoredTimeSeriesPoint, String> extractor(String field) {
        if (field.equalsIgnoreCase("metric.type")) {
            return StoredTimeSeriesPoint::getMetricType;
        }
        if (field.equalsIgnoreCase("resource.type")) {
            return StoredTimeSeriesPoint::getResourceType;
        }
        if (field.startsWith("metric.labels.")) {
            String labelKey = field.substring("metric.labels.".length());
            return point -> point.getMetricLabels() != null ? point.getMetricLabels().get(labelKey) : null;
        }
        if (field.startsWith("resource.labels.")) {
            String labelKey = field.substring("resource.labels.".length());
            return point -> point.getResourceLabels() != null ? point.getResourceLabels().get(labelKey) : null;
        }
        return null;
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
