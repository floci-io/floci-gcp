package io.floci.gcp.services.cloudlogging;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.cloudlogging.model.StoredLogEntry;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a practical subset of the Cloud Logging filter language into a predicate over
 * {@link StoredLogEntry}. Supports AND-combined comparisons on:
 * {@code logName =}, {@code severity <op>}, {@code resource.type =}, {@code timestamp <op>},
 * and {@code labels.<key> =}. An empty filter matches everything. Unrecognized clauses are
 * treated leniently (match-all) and logged at debug. OR/NOT/functions/regex are not supported.
 */
final class LogEntryFilter {

    private static final Logger LOG = Logger.getLogger(LogEntryFilter.class);

    private static final Pattern CLAUSE =
            Pattern.compile("^(\\S+?)\\s*(>=|<=|=|>|<)\\s*(.+)$");

    private static final Map<String, Integer> SEVERITY_RANK = Map.ofEntries(
            Map.entry("DEFAULT", 0), Map.entry("DEBUG", 100), Map.entry("INFO", 200),
            Map.entry("NOTICE", 300), Map.entry("WARNING", 400), Map.entry("ERROR", 500),
            Map.entry("CRITICAL", 600), Map.entry("ALERT", 700), Map.entry("EMERGENCY", 800));

    private LogEntryFilter() {}

    static Predicate<StoredLogEntry> parse(String filter) {
        if (filter == null || filter.isBlank()) {
            return entry -> true;
        }
        Predicate<StoredLogEntry> predicate = entry -> true;
        for (String clause : filter.split("(?i)\\s+AND\\s+")) {
            String trimmed = clause.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            predicate = predicate.and(parseClause(trimmed));
        }
        return predicate;
    }

    private static Predicate<StoredLogEntry> parseClause(String clause) {
        Matcher m = CLAUSE.matcher(clause);
        if (!m.matches()) {
            LOG.debugf("Ignoring unrecognized log filter clause: %s", clause);
            return entry -> true;
        }
        String field = m.group(1);
        String op = m.group(2);
        String value = unquote(m.group(3).trim());

        if (field.equalsIgnoreCase("logName") || field.equalsIgnoreCase("log_name")) {
            return entry -> "=".equals(op) && value.equals(entry.getLogName());
        }
        if (field.equalsIgnoreCase("severity")) {
            return severityPredicate(op, value);
        }
        if (field.equalsIgnoreCase("resource.type")) {
            return entry -> "=".equals(op) && value.equals(entry.getResourceType());
        }
        if (field.equalsIgnoreCase("timestamp")) {
            return timestampPredicate(op, value);
        }
        if (field.regionMatches(true, 0, "labels.", 0, "labels.".length())) {
            String key = field.substring("labels.".length());
            return entry -> "=".equals(op) && entry.getLabels() != null
                    && value.equals(entry.getLabels().get(key));
        }
        LOG.debugf("Ignoring unsupported log filter field: %s", field);
        return entry -> true;
    }

    private static Predicate<StoredLogEntry> severityPredicate(String op, String value) {
        Integer expected = SEVERITY_RANK.get(value.toUpperCase());
        if (expected == null) {
            try {
                expected = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw GcpException.invalidArgument("Invalid severity in filter: " + value);
            }
        }
        final int target = expected;
        return entry -> {
            int actual = SEVERITY_RANK.getOrDefault(
                    entry.getSeverity() == null ? "DEFAULT" : entry.getSeverity().toUpperCase(), 0);
            return compare(Integer.compare(actual, target), op);
        };
    }

    private static Predicate<StoredLogEntry> timestampPredicate(String op, String value) {
        final Instant target;
        try {
            target = Instant.parse(value);
        } catch (Exception e) {
            throw GcpException.invalidArgument("Invalid timestamp in filter: " + value);
        }
        return entry -> {
            if (entry.getTimestamp() == null) {
                return false;
            }
            try {
                return compare(Instant.parse(entry.getTimestamp()).compareTo(target), op);
            } catch (Exception e) {
                return false;
            }
        };
    }

    private static boolean compare(int cmp, String op) {
        return switch (op) {
            case "=" -> cmp == 0;
            case ">" -> cmp > 0;
            case ">=" -> cmp >= 0;
            case "<" -> cmp < 0;
            case "<=" -> cmp <= 0;
            default -> false;
        };
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
