package io.floci.gcp.services.cloudmonitoring;

import io.floci.gcp.core.common.GcpException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Parses the monitoring filter language strictly, so a filter the emulator cannot read answers
 * INVALID_ARGUMENT rather than matching everything: comparisons of an object with a value, {@code AND}
 * (optional), {@code OR} (binds tighter), and parentheses. {@code NOT} and the metadata objects
 * exist only in group definitions, which no method here accepts.
 */
final class MonitoringFilterParser {

    private static final int MAX_ONE_OF = 100;
    private static final String REGEX = "monitoring.regex.full_match";

    /** A method taking a filter, and which objects its filter may name. */
    enum Scope {
        TIME_SERIES(object -> object.equals("project") || object.equals("group.id") || object.equals("metric.type")
                || object.equals("resource.type") || object.startsWith("metric.labels.")
                || object.startsWith("resource.labels.")),
        METRIC_DESCRIPTORS(object -> object.equals("project") || object.equals("metric.type")),
        // The proto allows labels here ("resource.label:id"); a descriptor has keys but no values to compare.
        RESOURCE_DESCRIPTORS(object -> object.equals("resource.type"));

        private final Predicate<String> objects;

        Scope(Predicate<String> objects) {
            this.objects = objects;
        }

        boolean allows(String object) {
            return objects.test(object);
        }

        boolean allowsKeyTest(String labelObject) {
            return this == RESOURCE_DESCRIPTORS ? labelObject.startsWith("resource.labels.") : allows(labelObject);
        }
    }

    /** A parsed filter. {@code values} maps an object such as {@code metric.labels.zone} to its value, or null. */
    record Filter(Node root) {

        boolean matches(Function<String, String> values) {
            return root == null || root.matches(values);
        }

        /** The value of a {@code metric.type = "..."} comparison every match must satisfy, if any. */
        Optional<String> requiredMetricType() {
            List<String> types = new ArrayList<>();
            collectRequiredMetricTypes(root, types);
            return types.stream().distinct().count() == 1 ? Optional.of(types.getFirst()) : Optional.empty();
        }

        private static void collectRequiredMetricTypes(Node node, List<String> types) {
            if (node instanceof All all) {
                all.nodes().forEach(child -> collectRequiredMetricTypes(child, types));
            } else if (node instanceof Equals equals && equals.object().equals("metric.type")) {
                types.add(equals.value());
            }
        }
    }

    private sealed interface Node {
        boolean matches(Function<String, String> values);

        default void collectObjects(List<String> objects) {}
    }

    private record All(List<Node> nodes) implements Node {
        @Override
        public boolean matches(Function<String, String> values) {
            return nodes.stream().allMatch(node -> node.matches(values));
        }

        @Override
        public void collectObjects(List<String> objects) {
            nodes.forEach(node -> node.collectObjects(objects));
        }
    }

    private record Any(List<Node> nodes) implements Node {
        @Override
        public boolean matches(Function<String, String> values) {
            return nodes.stream().anyMatch(node -> node.matches(values));
        }

        @Override
        public void collectObjects(List<String> objects) {
            nodes.forEach(node -> node.collectObjects(objects));
        }
    }

    /** An equality with a string, kept apart so the required metric type can be read back. */
    private record Equals(String object, String value) implements Node {
        @Override
        public boolean matches(Function<String, String> values) {
            return value.equals(values.apply(object));
        }

        @Override
        public void collectObjects(List<String> objects) {
            objects.add(object);
        }
    }

    private record Comparison(String object, Predicate<String> test) implements Node {
        @Override
        public boolean matches(Function<String, String> values) {
            return test.test(values.apply(object));
        }

        @Override
        public void collectObjects(List<String> objects) {
            objects.add(object);
        }
    }

    private enum TokenType { WORD, STRING, NUMBER, OPERATOR, OPEN, CLOSE, COMMA, END }

    private record Token(TokenType type, String text) {
        boolean isWord(String word) {
            return type == TokenType.WORD && text.equals(word);
        }
    }

    private final String filter;
    private final Scope scope;
    private final List<Token> tokens;
    private int position;

    private MonitoringFilterParser(String filter, Scope scope) {
        this.filter = filter;
        this.scope = scope;
        this.tokens = tokenize();
    }

    static Filter parse(String filter, Scope scope) {
        if (filter == null || filter.isBlank()) {
            return new Filter(null);
        }
        MonitoringFilterParser parser = new MonitoringFilterParser(filter, scope);
        Node root = parser.conjunction();
        if (parser.peek().type() != TokenType.END) {
            throw parser.invalid("unexpected '" + parser.peek().text() + "'");
        }
        return new Filter(root);
    }

    private Node conjunction() {
        List<Node> nodes = new ArrayList<>();
        nodes.add(disjunction());
        while (true) {
            Token next = peek();
            if (next.isWord("AND")) {
                position++;
            } else if (next.type() == TokenType.END || next.type() == TokenType.CLOSE) {
                break;
            }
            nodes.add(disjunction());
        }
        return nodes.size() == 1 ? nodes.getFirst() : new All(List.copyOf(nodes));
    }

    private Node disjunction() {
        List<Node> nodes = new ArrayList<>();
        nodes.add(operand());
        while (peek().isWord("OR")) {
            position++;
            nodes.add(operand());
        }
        if (nodes.size() == 1) {
            return nodes.getFirst();
        }
        Any any = new Any(List.copyOf(nodes));
        requireOneSelector(any);
        return any;
    }

    /** The reference: "comparisons for different selectors must not be intermingled". */
    private void requireOneSelector(Node node) {
        List<String> objects = new ArrayList<>();
        node.collectObjects(objects);
        if (objects.stream().map(object -> object.split("\\.")[0]).distinct().count() > 1) {
            throw invalid("comparisons of different selectors joined by OR");
        }
    }

    private Node operand() {
        Token token = next();
        if (token.type() == TokenType.OPEN) {
            Node inner = conjunction();
            expect(TokenType.CLOSE, "')'");
            return inner;
        }
        if (token.isWord("NOT")) {
            throw invalid("NOT is only valid in group definitions");
        }
        if (token.type() != TokenType.WORD || token.isWord("AND") || token.isWord("OR")) {
            throw invalid("expected a filter object, found '" + token.text() + "'");
        }
        return comparison(token.text());
    }

    private Node comparison(String object) {
        String operator = expect(TokenType.OPERATOR, "a comparison operator").text();
        boolean keyTest = operator.equals(":")
                && (object.equals("resource.labels") || object.equals("resource.label")
                || object.equals("metric.labels") || object.equals("metric.label"));
        if (keyTest) {
            return keyTest(object);
        }
        if (!scope.allows(object)) {
            throw invalid("'" + object + "' is not valid for this method");
        }
        Node comparison = switch (operator) {
            case "=", "!=" -> equality(object, operator.equals("!="));
            case ":" -> substring(object);
            default -> ordering(object, operator);
        };
        // Group membership is not modeled, so no item belongs to a group.
        return object.equals("group.id") ? new Comparison(object, value -> false) : comparison;
    }

    private Node keyTest(String object) {
        String prefix = object.startsWith("resource") ? "resource.labels." : "metric.labels.";
        Token key = next();
        if (key.type() != TokenType.WORD && key.type() != TokenType.STRING) {
            throw invalid("expected a label key after ':'");
        }
        String labelObject = prefix + key.text();
        if (!scope.allowsKeyTest(labelObject)) {
            throw invalid("'" + object + "' is not valid for this method");
        }
        return new Comparison(labelObject, value -> value != null);
    }

    private Node equality(String object, boolean negated) {
        Token value = next();
        Predicate<String> test = switch (value.type()) {
            case STRING -> {
                if (!negated) {
                    yield null;
                }
                yield value.text()::equals;
            }
            case NUMBER -> {
                double expected = Double.parseDouble(value.text());
                yield actual -> {
                    Double number = number(actual);
                    return number != null && number == expected;
                };
            }
            case WORD -> value.text().equals("true") || value.text().equals("false")
                    ? value.text()::equals
                    : function(value.text());
            default -> throw invalid("expected a value after '=', found '" + value.text() + "'");
        };
        if (test == null) {
            return new Equals(object, value.text());
        }
        return new Comparison(object, negated ? test.negate() : test);
    }

    private Node substring(String object) {
        String expected = expect(TokenType.STRING, "a quoted string after ':'").text();
        return new Comparison(object, actual -> actual != null && actual.contains(expected));
    }

    private Node ordering(String object, String operator) {
        double expected = Double.parseDouble(expect(TokenType.NUMBER, "a number after '" + operator + "'").text());
        return new Comparison(object, actual -> {
            Double number = number(actual);
            if (number == null) {
                return false;
            }
            return switch (operator) {
                case "<" -> number < expected;
                case "<=" -> number <= expected;
                case ">" -> number > expected;
                default -> number >= expected;
            };
        });
    }

    private Predicate<String> function(String name) {
        if (!name.equals("starts_with") && !name.equals("ends_with") && !name.equals("has_substring")
                && !name.equals("one_of") && !name.equals(REGEX)) {
            throw invalid("unknown function or value '" + name + "'");
        }
        expect(TokenType.OPEN, "'(' after " + name);
        List<String> arguments = new ArrayList<>();
        arguments.add(expect(TokenType.STRING, "a quoted string").text());
        boolean ignoreCase = false;
        while (peek().type() == TokenType.COMMA) {
            position++;
            if (name.equals("has_substring") && scope != Scope.TIME_SERIES && arguments.size() == 1
                    && (peek().isWord("true") || peek().isWord("false"))) {
                ignoreCase = next().text().equals("true");
                break;
            }
            arguments.add(expect(TokenType.STRING, "a quoted string").text());
        }
        expect(TokenType.CLOSE, "')' after the arguments of " + name);
        if (!name.equals("one_of") && arguments.size() > 1) {
            throw invalid(name + " takes a single string");
        }
        if (arguments.size() > MAX_ONE_OF) {
            throw invalid("one_of takes up to " + MAX_ONE_OF + " strings");
        }
        String first = arguments.getFirst();
        boolean insensitive = ignoreCase;
        return switch (name) {
            case "starts_with" -> actual -> actual != null && actual.startsWith(first);
            case "ends_with" -> actual -> actual != null && actual.endsWith(first);
            case "has_substring" -> actual -> actual != null && (insensitive
                    ? actual.toLowerCase().contains(first.toLowerCase()) : actual.contains(first));
            case "one_of" -> actual -> actual != null && arguments.contains(actual);
            default -> regex(first);
        };
    }

    private Predicate<String> regex(String expression) {
        try {
            Pattern pattern = Pattern.compile(expression);
            return actual -> actual != null && pattern.matcher(actual).matches();
        } catch (PatternSyntaxException e) {
            throw invalid("invalid regular expression '" + expression + "'");
        }
    }

    private static Double number(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Token peek() {
        return tokens.get(position);
    }

    private Token next() {
        Token token = tokens.get(position);
        if (token.type() == TokenType.END) {
            throw invalid("unexpected end of filter");
        }
        position++;
        return token;
    }

    private Token expect(TokenType type, String description) {
        Token token = peek();
        if (token.type() != type) {
            throw invalid("expected " + description + ", found '" + token.text() + "'");
        }
        position++;
        return token;
    }

    private List<Token> tokenize() {
        List<Token> found = new ArrayList<>();
        int i = 0;
        while (i < filter.length()) {
            char c = filter.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '(') {
                found.add(new Token(TokenType.OPEN, "("));
                i++;
            } else if (c == ')') {
                found.add(new Token(TokenType.CLOSE, ")"));
                i++;
            } else if (c == ',') {
                found.add(new Token(TokenType.COMMA, ","));
                i++;
            } else if (c == '"') {
                i = quoted(i, found);
            } else if (c == '\'') {
                throw invalid("strings must be quoted with quotation marks, not apostrophes");
            } else if (c == '=' || c == ':') {
                found.add(new Token(TokenType.OPERATOR, String.valueOf(c)));
                i++;
            } else if (c == '!' || c == '<' || c == '>') {
                boolean equal = i + 1 < filter.length() && filter.charAt(i + 1) == '=';
                if (c == '!' && !equal) {
                    throw invalid("unexpected '!'");
                }
                found.add(new Token(TokenType.OPERATOR, equal ? c + "=" : String.valueOf(c)));
                i += equal ? 2 : 1;
            } else if (Character.isDigit(c) || c == '-') {
                int end = i + 1;
                while (end < filter.length() && (Character.isDigit(filter.charAt(end)) || filter.charAt(end) == '.')) {
                    end++;
                }
                String number = filter.substring(i, end);
                if (number(number) == null) {
                    throw invalid("invalid number '" + number + "'");
                }
                found.add(new Token(TokenType.NUMBER, number));
                i = end;
            } else if (Character.isLetter(c) || c == '_') {
                int end = i + 1;
                while (end < filter.length() && isWordPart(filter.charAt(end))) {
                    end++;
                }
                found.add(new Token(TokenType.WORD, filter.substring(i, end)));
                i = end;
            } else {
                throw invalid("unexpected '" + c + "'");
            }
        }
        found.add(new Token(TokenType.END, "end of filter"));
        return found;
    }

    private int quoted(int start, List<Token> found) {
        StringBuilder value = new StringBuilder();
        int i = start + 1;
        while (i < filter.length()) {
            char c = filter.charAt(i);
            if (c == '"') {
                found.add(new Token(TokenType.STRING, value.toString()));
                return i + 1;
            }
            if (c == '\\' && i + 1 < filter.length()) {
                i++;
                c = filter.charAt(i);
            }
            value.append(c);
            i++;
        }
        throw invalid("unterminated string");
    }

    private static boolean isWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-';
    }

    private GcpException invalid(String reason) {
        return GcpException.invalidArgument("Invalid monitoring filter: " + reason + " in '" + filter + "'");
    }
}
