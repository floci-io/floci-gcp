package io.floci.gcp.services.cloudlogging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.cloudlogging.model.StoredLogEntry;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A log-based metric extractor: {@code EXTRACT(field)} or {@code REGEXP_EXTRACT(field, "regex")}.
 * The field value is converted to a string before the regular expression applies, and the regular
 * expression must have exactly one capture group. Uses java.util.regex rather than RE2.
 */
final class LogMetricExtractor {
    private static final Pattern EXTRACT = Pattern.compile("(?s)\\s*EXTRACT\\s*\\((.+)\\)\\s*");
    private static final Pattern REGEXP_EXTRACT = Pattern.compile(
            "(?s)\\s*REGEXP_EXTRACT\\s*\\((.+?),\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\)\\s*");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Function<StoredLogEntry, Object> field;
    private final Pattern regex;

    private LogMetricExtractor(Function<StoredLogEntry, Object> field, Pattern regex) {
        this.field = field;
        this.regex = regex;
    }

    static LogMetricExtractor parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw GcpException.invalidArgument("A log-based metric extractor expression is required");
        }
        Matcher regexp = REGEXP_EXTRACT.matcher(expression);
        if (regexp.matches()) {
            Pattern compiled;
            try {
                compiled = Pattern.compile(unescape(regexp.group(2)));
            } catch (IllegalArgumentException e) {
                throw GcpException.invalidArgument("Invalid regular expression in extractor: " + expression);
            }
            if (compiled.matcher("").groupCount() != 1) {
                throw GcpException.invalidArgument(
                        "The regular expression must include exactly one capture group: " + expression);
            }
            return new LogMetricExtractor(field(regexp.group(1), expression), compiled);
        }
        Matcher extract = EXTRACT.matcher(expression);
        if (extract.matches()) {
            return new LogMetricExtractor(field(extract.group(1), expression), null);
        }
        throw GcpException.invalidArgument(
                "Extractors must be EXTRACT(field) or REGEXP_EXTRACT(field, regex): " + expression);
    }

    /** The extracted string, or empty when the field is absent or the regular expression does not match. */
    Optional<String> extract(StoredLogEntry entry) {
        Object value = field.apply(entry);
        if (value == null) {
            return Optional.empty();
        }
        String text = stringify(value);
        if (regex == null) {
            return Optional.of(text);
        }
        Matcher matcher = regex.matcher(text);
        if (!matcher.find() || matcher.group(1) == null) {
            return Optional.empty();
        }
        return Optional.of(matcher.group(1));
    }

    private static Function<StoredLogEntry, Object> field(String name, String expression) {
        try {
            return SinkLogEntryFilter.accessor(name.trim());
        } catch (GcpException e) {
            throw GcpException.invalidArgument("Unsupported field in extractor: " + expression);
        }
    }

    private static String stringify(Object value) {
        if (value instanceof Map<?, ?> || value instanceof Collection<?>) {
            try {
                return JSON.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                return String.valueOf(value);
            }
        }
        return String.valueOf(value);
    }

    private static String unescape(String quoted) {
        StringBuilder result = new StringBuilder(quoted.length());
        for (int i = 0; i < quoted.length(); i++) {
            char c = quoted.charAt(i);
            if (c == '\\' && i + 1 < quoted.length() && (quoted.charAt(i + 1) == '"' || quoted.charAt(i + 1) == '\\')) {
                result.append(quoted.charAt(++i));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}
