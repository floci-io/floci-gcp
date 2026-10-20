package io.floci.gcp.services.cloudlogging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.ibm.icu.text.Normalizer2;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.cloudlogging.model.StoredLogEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/** Strict routing subset: unsupported syntax rejects the whole sink evaluation, including exclusions. */
final class SinkLogEntryFilter {
    private static final Normalizer2 STRING_NORMALIZER = Normalizer2.getNFKCCasefoldInstance();
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final List<String> SEVERITIES = List.of(
            "DEFAULT", "DEBUG", "INFO", "NOTICE", "WARNING", "ERROR", "CRITICAL", "ALERT", "EMERGENCY");

    private final List<String> tokens;
    private int position;

    private SinkLogEntryFilter(String filter) {
        tokens = tokenize(filter);
    }

    static Predicate<StoredLogEntry> parse(String filter) {
        if (filter == null || filter.isBlank()) {
            return entry -> true;
        }
        SinkLogEntryFilter parser = new SinkLogEntryFilter(filter);
        Predicate<StoredLogEntry> result = parser.conjunction();
        if (parser.position != parser.tokens.size()) {
            throw unsupported();
        }
        return result;
    }

    // Logging gives OR higher precedence than AND, unlike SQL.
    private Predicate<StoredLogEntry> conjunction() {
        Predicate<StoredLogEntry> result = disjunction();
        while (position < tokens.size() && !peek(")")) {
            take("AND"); // Adjacent expressions are implicitly AND-combined.
            result = result.and(disjunction());
        }
        return result;
    }

    private Predicate<StoredLogEntry> disjunction() {
        Predicate<StoredLogEntry> result = expression();
        while (take("OR")) {
            result = result.or(expression());
        }
        return result;
    }

    private Predicate<StoredLogEntry> expression() {
        if (take("NOT")) {
            return expression().negate();
        }
        if (take("(")) {
            Predicate<StoredLogEntry> result = conjunction();
            if (!take(")")) {
                throw unsupported();
            }
            return result;
        }
        String field = next();
        String operator = next();
        if (!List.of("=", "!=", ":", ">", ">=", "<", "<=").contains(operator)) {
            throw unsupported();
        }
        String raw = next();
        if (raw.equals("NULL_VALUE") || raw.equals("null")) {
            throw unsupported();
        }
        if (!raw.startsWith("\"") && (!raw.matches("[A-Za-z0-9_./%+@*\\-]+")
                || List.of("AND", "OR", "NOT").contains(raw))) {
            throw unsupported();
        }
        String expected = decode(raw);
        Function<StoredLogEntry, Object> accessor = accessor(field);
        if (operator.equals(":") && raw.equals("*")) {
            if (field.equals("severity")) {
                throw unsupported();
            }
            if (field.startsWith("jsonPayload.")) {
                String[] path = jsonPath(field.substring(12));
                return entry -> hasPath(entry.getJsonPayload(), path, 0);
            }
            return entry -> accessor.apply(entry) != null;
        }
        if (field.equals("severity")) {
            int target = severity(expected);
            return entry -> compare(Integer.compare(severity(entry.getSeverity()), target), operator);
        }
        if (field.equals("timestamp") || field.equals("receiveTimestamp")) {
            Instant target = Instant.parse(expected);
            return entry -> accessor.apply(entry) instanceof String actual
                    && compare(Instant.parse(actual).compareTo(target), operator);
        }
        return entry -> {
            Object actual = accessor.apply(entry);
            if (actual == null && List.of("textPayload", "trace", "spanId").contains(field)) {
                actual = "";
            }
            return matches(actual, operator, expected);
        };
    }

    private static Function<StoredLogEntry, Object> accessor(String field) {
        return switch (field) {
            case "logName" -> StoredLogEntry::getLogName;
            case "severity" -> StoredLogEntry::getSeverity;
            case "textPayload" -> StoredLogEntry::getTextPayload;
            case "timestamp" -> StoredLogEntry::getTimestamp;
            case "receiveTimestamp" -> StoredLogEntry::getReceiveTimestamp;
            case "insertId" -> StoredLogEntry::getInsertId;
            case "trace" -> StoredLogEntry::getTrace;
            case "spanId" -> StoredLogEntry::getSpanId;
            case "resource.type" -> StoredLogEntry::getResourceType;
            default -> {
                if (field.startsWith("labels.")) {
                    String key = mapKey(field.substring(7));
                    yield entry -> entry.getLabels() == null ? null : entry.getLabels().get(key);
                }
                if (field.startsWith("resource.labels.")) {
                    String key = mapKey(field.substring(16));
                    yield entry -> entry.getResourceLabels() == null ? null : entry.getResourceLabels().get(key);
                }
                if (field.startsWith("jsonPayload.")) {
                    String[] path = jsonPath(field.substring(12));
                    yield entry -> lookup(entry.getJsonPayload(), path, 0);
                }
                throw unsupported();
            }
        };
    }

    private static String mapKey(String key) {
        if (!key.startsWith("\"") && !key.matches("[A-Za-z_][A-Za-z0-9_-]*")) {
            throw unsupported();
        }
        return decode(key);
    }

    private static String[] jsonPath(String field) {
        List<String> path = new ArrayList<>();
        int start = 0;
        boolean quoted = false;
        for (int index = 0; index <= field.length(); index++) {
            if (index < field.length() && field.charAt(index) == '\\' && quoted) {
                index++;
                continue;
            }
            if (index < field.length() && field.charAt(index) == '"') {
                quoted = !quoted;
            }
            if (index == field.length() || (!quoted && field.charAt(index) == '.')) {
                String part = field.substring(start, index);
                if (!part.startsWith("\"") && !part.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw unsupported();
                }
                path.add(decode(part));
                start = index + 1;
            }
        }
        return path.toArray(String[]::new);
    }

    private static boolean hasPath(Object value, String[] path, int index) {
        if (index == path.length) {
            return true;
        }
        if (value instanceof Map<?, ?> map) {
            return map.containsKey(path[index]) && hasPath(map.get(path[index]), path, index + 1);
        }
        if (value instanceof List<?> list) {
            return list.stream().anyMatch(item -> hasPath(item, path, index));
        }
        return false;
    }

    private static Object lookup(Object value, String[] path, int index) {
        if (index == path.length) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return lookup(map.get(path[index]), path, index + 1);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> lookup(item, path, index)).toList();
        }
        return null;
    }

    private static boolean matches(Object value, String operator, String expected) {
        if (value instanceof List<?> list) {
            return list.stream().anyMatch(item -> matches(item, operator, expected));
        }
        if (value == null || value instanceof Map<?, ?>) {
            return false;
        }
        if (value instanceof Boolean actual) {
            if (!expected.equalsIgnoreCase("true") && !expected.equalsIgnoreCase("false")) {
                return false;
            }
            return compare(Boolean.compare(actual, Boolean.parseBoolean(expected)), operator);
        }
        if (value instanceof Number number) {
            try {
                return compare(new BigDecimal(number.toString()).compareTo(new BigDecimal(expected)), operator);
            } catch (NumberFormatException expectedFailure) {
                // Logging treats failed scalar conversions as nonmatching comparisons.
                return false;
            }
        }
        String actual = STRING_NORMALIZER.normalize(value.toString());
        String target = STRING_NORMALIZER.normalize(expected);
        return operator.equals(":") ? actual.contains(target) : compare(actual.compareTo(target), operator);
    }

    private static int severity(String value) {
        if (value == null) {
            return 0;
        }
        int rank = SEVERITIES.indexOf(value.toUpperCase(Locale.ROOT));
        return rank >= 0 ? rank * 100 : Integer.parseInt(value);
    }

    private static boolean compare(int comparison, String operator) {
        return switch (operator) {
            case "=" -> comparison == 0;
            case "!=" -> comparison != 0;
            case ">" -> comparison > 0;
            case ">=" -> comparison >= 0;
            case "<" -> comparison < 0;
            case "<=" -> comparison <= 0;
            default -> throw unsupported();
        };
    }

    private boolean peek(String token) {
        return position < tokens.size() && tokens.get(position).equals(token);
    }

    private boolean take(String token) {
        if (!peek(token)) {
            return false;
        }
        position++;
        return true;
    }

    private String next() {
        if (position == tokens.size()) {
            throw unsupported();
        }
        return tokens.get(position++);
    }

    private static String decode(String value) {
        if (!value.startsWith("\"")) {
            return value;
        }
        try {
            return JSON.readValue(value, String.class);
        } catch (JsonProcessingException exception) {
            throw unsupported();
        }
    }

    private static List<String> tokenize(String filter) {
        List<String> result = new ArrayList<>();
        int index = 0;
        while (index < filter.length()) {
            char first = filter.charAt(index);
            if (Character.isWhitespace(first)) {
                index++;
                continue;
            }
            int start = index;
            if ("():=<>!".indexOf(first) >= 0) {
                index++;
                if (index < filter.length() && (filter.charAt(index) == '=' || filter.charAt(index) == '~')) {
                    index++;
                }
            } else {
                boolean quoted = false;
                while (index < filter.length()) {
                    char character = filter.charAt(index);
                    if (character == '\\' && quoted) {
                        index += 2;
                        continue;
                    }
                    if (character == '"') {
                        quoted = !quoted;
                    }
                    if (!quoted && (Character.isWhitespace(character) || "():=<>!".indexOf(character) >= 0)) {
                        break;
                    }
                    index++;
                }
                if (quoted || index > filter.length()) {
                    throw unsupported();
                }
            }
            result.add(filter.substring(start, index));
        }
        return result;
    }

    private static GcpException unsupported() {
        return GcpException.invalidArgument("Unsupported Cloud Logging sink filter expression");
    }
}
