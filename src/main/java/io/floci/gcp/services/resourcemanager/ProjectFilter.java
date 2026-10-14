package io.floci.gcp.services.resourcemanager;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ProjectRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The v1 {@code projects.list} filter: whitespace-separated {@code field:value} terms, OR'd
 * together, case-insensitive, with {@code *} as a wildcard. Fields are {@code name}, {@code id},
 * {@code labels.<key>}, {@code parent.type}, {@code parent.id} and {@code lifecycleState}.
 */
final class ProjectFilter {

    private final List<Term> terms;

    private ProjectFilter(List<Term> terms) {
        this.terms = terms;
    }

    static ProjectFilter parse(String filter) {
        List<Term> terms = new ArrayList<>();
        if (filter == null || filter.isBlank()) {
            return new ProjectFilter(terms);
        }
        for (String token : filter.trim().split("\\s+")) {
            if (token.equalsIgnoreCase("OR") || token.equalsIgnoreCase("AND")) {
                continue;
            }
            int separator = separatorIndex(token);
            if (separator <= 0) {
                throw GcpException.invalidArgument("Invalid filter term '" + token + "'.");
            }
            String field = token.substring(0, separator).toLowerCase(Locale.ROOT);
            String value = unquote(token.substring(separator + 1));
            if (!isKnownField(field)) {
                throw GcpException.invalidArgument("Invalid filter field '" + token.substring(0, separator) + "'.");
            }
            terms.add(new Term(field, glob(value)));
        }
        return new ProjectFilter(terms);
    }

    boolean matches(ProjectRecord project) {
        if (terms.isEmpty()) {
            return true;
        }
        for (Term term : terms) {
            String actual = fieldValue(project, term.field());
            if (actual != null && term.value().matcher(actual).matches()) {
                return true;
            }
        }
        return false;
    }

    private static String fieldValue(ProjectRecord project, String field) {
        if (field.startsWith("labels.")) {
            String key = field.substring("labels.".length());
            return project.getLabels().entrySet().stream()
                    .filter(e -> e.getKey().toLowerCase(Locale.ROOT).equals(key))
                    .map(e -> e.getValue())
                    .findFirst()
                    .orElse(null);
        }
        return switch (field) {
            case "name" -> project.getName();
            case "id", "projectid" -> project.getProjectId();
            case "parent.type" -> project.hasParent() ? project.getParentType() : null;
            case "parent.id" -> project.hasParent() ? project.getParentId() : null;
            case "lifecyclestate" -> project.getLifecycleState();
            default -> null;
        };
    }

    private static boolean isKnownField(String field) {
        return (field.startsWith("labels.") && field.length() > "labels.".length())
                || switch (field) {
                    case "name", "id", "projectid", "parent.type", "parent.id", "lifecyclestate" -> true;
                    default -> false;
                };
    }

    private static int separatorIndex(String token) {
        int colon = token.indexOf(':');
        int equals = token.indexOf('=');
        if (colon < 0) {
            return equals;
        }
        return equals < 0 ? colon : Math.min(colon, equals);
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static Pattern glob(String value) {
        List<String> parts = new ArrayList<>();
        for (String part : value.split("\\*", -1)) {
            parts.add(Pattern.quote(part));
        }
        return Pattern.compile(String.join(".*", parts), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private record Term(String field, Pattern value) {}
}
