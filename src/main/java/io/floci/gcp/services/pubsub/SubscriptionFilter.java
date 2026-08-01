package io.floci.gcp.services.pubsub;

import io.floci.gcp.core.common.GcpException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Parses the Pub/Sub subscription filter language into a predicate over message attributes.
 * Supports attribute existence ({@code attributes:key}), {@code =}, {@code !=},
 * {@code hasPrefix(attributes.key, "prefix")}, the uppercase boolean operators {@code AND},
 * {@code OR} and {@code NOT} with {@code -} as a unary alias for {@code NOT}, parentheses, and
 * quoted keys containing unicode, hexadecimal or octal escape sequences. Keys and values are
 * case-sensitive. As in GCP, {@code AND} and {@code OR} cannot be combined at the same level
 * without parentheses, and an unparseable filter is rejected with {@code INVALID_ARGUMENT}
 * rather than silently ignored.
 */
final class SubscriptionFilter {

    private static final int MAX_FILTER_BYTES = 256;

    private final List<Token> tokens;
    private int position;

    private SubscriptionFilter(List<Token> tokens) {
        this.tokens = tokens;
    }

    static Predicate<Map<String, String>> parse(String filter) {
        if (filter == null || filter.isBlank()) {
            return attributes -> true;
        }
        SubscriptionFilter parser = new SubscriptionFilter(tokenize(filter));
        Predicate<Map<String, String>> predicate = parser.expression();
        parser.expect(TokenType.END, "end of filter");
        return predicate;
    }

    static void validate(String filter) {
        if (filter == null || filter.isBlank()) {
            return;
        }
        int bytes = filter.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_FILTER_BYTES) {
            throw GcpException.invalidArgument("Invalid subscription filter: the filter must be at most "
                    + MAX_FILTER_BYTES + " bytes, but was " + bytes + " bytes");
        }
        parse(filter);
    }

    // ── Grammar ────────────────────────────────────────────────────────────────

    private Predicate<Map<String, String>> expression() {
        Predicate<Map<String, String>> predicate = term();
        String operator = null;
        while (peek().type() == TokenType.IDENT && isBooleanOperator(peek().text())) {
            String next = advance().text();
            if (operator != null && !operator.equals(next)) {
                throw invalid("AND and OR cannot be combined without parentheses");
            }
            operator = next;
            Predicate<Map<String, String>> right = term();
            predicate = "AND".equals(operator) ? predicate.and(right) : predicate.or(right);
        }
        return predicate;
    }

    private Predicate<Map<String, String>> term() {
        if (peek().type() == TokenType.MINUS || isKeyword(peek(), "NOT")) {
            advance();
            return term().negate();
        }
        return primary();
    }

    private Predicate<Map<String, String>> primary() {
        Token token = peek();
        if (token.type() == TokenType.LPAREN) {
            advance();
            Predicate<Map<String, String>> predicate = expression();
            expect(TokenType.RPAREN, "')'");
            return predicate;
        }
        if (isKeyword(token, "attributes")) {
            advance();
            return attributeComparison();
        }
        if (isKeyword(token, "hasPrefix")) {
            advance();
            return hasPrefix();
        }
        throw invalid("expected an attribute comparison but found " + describe(token));
    }

    private Predicate<Map<String, String>> attributeComparison() {
        Token token = advance();
        if (token.type() == TokenType.COLON) {
            String key = key();
            return attributes -> attributes.containsKey(key);
        }
        if (token.type() == TokenType.DOT) {
            String key = key();
            Token operator = advance();
            if (operator.type() != TokenType.EQ && operator.type() != TokenType.NE) {
                throw invalid("expected '=' or '!=' but found " + describe(operator));
            }
            String value = string();
            return operator.type() == TokenType.EQ
                    ? attributes -> value.equals(attributes.get(key))
                    : attributes -> !value.equals(attributes.get(key));
        }
        throw invalid("expected ':' or '.' after 'attributes' but found " + describe(token));
    }

    private Predicate<Map<String, String>> hasPrefix() {
        expect(TokenType.LPAREN, "'('");
        Token attributes = advance();
        if (!isKeyword(attributes, "attributes")) {
            throw invalid("hasPrefix expects an attribute as its first argument but found "
                    + describe(attributes));
        }
        expect(TokenType.DOT, "'.'");
        String key = key();
        expect(TokenType.COMMA, "','");
        String prefix = string();
        expect(TokenType.RPAREN, "')'");
        return values -> {
            String value = values.get(key);
            return value != null && value.startsWith(prefix);
        };
    }

    private String key() {
        Token token = advance();
        if (token.type() == TokenType.IDENT || token.type() == TokenType.STRING) {
            return token.text();
        }
        throw invalid("expected an attribute key but found " + describe(token));
    }

    private String string() {
        Token token = advance();
        if (token.type() != TokenType.STRING) {
            throw invalid("expected a quoted string but found " + describe(token));
        }
        return token.text();
    }

    private static boolean isBooleanOperator(String text) {
        return "AND".equals(text) || "OR".equals(text);
    }

    private static boolean isKeyword(Token token, String keyword) {
        return token.type() == TokenType.IDENT && keyword.equals(token.text());
    }

    private Token peek() {
        return tokens.get(position);
    }

    private Token advance() {
        Token token = tokens.get(position);
        if (token.type() != TokenType.END) {
            position++;
        }
        return token;
    }

    private void expect(TokenType type, String expected) {
        Token token = advance();
        if (token.type() != type) {
            throw invalid("expected " + expected + " but found " + describe(token));
        }
    }

    // ── Tokenizer ──────────────────────────────────────────────────────────────

    private enum TokenType { IDENT, STRING, COLON, DOT, EQ, NE, LPAREN, RPAREN, COMMA, MINUS, END }

    private record Token(TokenType type, String text) {}

    private static List<Token> tokenize(String filter) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < filter.length()) {
            char c = filter.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            switch (c) {
                case ':' -> { tokens.add(new Token(TokenType.COLON, ":")); i++; }
                case '.' -> { tokens.add(new Token(TokenType.DOT, ".")); i++; }
                case '(' -> { tokens.add(new Token(TokenType.LPAREN, "(")); i++; }
                case ')' -> { tokens.add(new Token(TokenType.RPAREN, ")")); i++; }
                case ',' -> { tokens.add(new Token(TokenType.COMMA, ",")); i++; }
                case '-' -> { tokens.add(new Token(TokenType.MINUS, "-")); i++; }
                case '=' -> { tokens.add(new Token(TokenType.EQ, "=")); i++; }
                case '!' -> {
                    if (i + 1 >= filter.length() || filter.charAt(i + 1) != '=') {
                        throw invalid("unexpected character '!'");
                    }
                    tokens.add(new Token(TokenType.NE, "!="));
                    i += 2;
                }
                case '"' -> {
                    StringBuilder value = new StringBuilder();
                    i = readStringLiteral(filter, i, value);
                    tokens.add(new Token(TokenType.STRING, value.toString()));
                }
                default -> {
                    if (!isIdentifierStart(c)) {
                        throw invalid("unexpected character '" + c + "'");
                    }
                    int start = i;
                    while (i < filter.length() && isIdentifierPart(filter.charAt(i))) {
                        i++;
                    }
                    tokens.add(new Token(TokenType.IDENT, filter.substring(start, i)));
                }
            }
        }
        tokens.add(new Token(TokenType.END, ""));
        return tokens;
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isIdentifierPart(char c) {
        return isIdentifierStart(c) || c == '-';
    }

    private static int readStringLiteral(String filter, int start, StringBuilder out) {
        int i = start + 1;
        while (i < filter.length()) {
            char c = filter.charAt(i);
            if (c == '"') {
                return i + 1;
            }
            if (c == '\\') {
                i = readEscapeSequence(filter, i, out);
                continue;
            }
            out.append(c);
            i++;
        }
        throw invalid("unterminated string literal");
    }

    private static int readEscapeSequence(String filter, int start, StringBuilder out) {
        int i = start + 1;
        if (i >= filter.length()) {
            throw invalid("unterminated escape sequence");
        }
        char c = filter.charAt(i);
        switch (c) {
            case 'n' -> out.append('\n');
            case 't' -> out.append('\t');
            case 'r' -> out.append('\r');
            case 'b' -> out.append('\b');
            case 'f' -> out.append('\f');
            case '\\' -> out.append('\\');
            case '"' -> out.append('"');
            case '\'' -> out.append('\'');
            case '/' -> out.append('/');
            case 'u' -> { return appendEscapedCodePoint(filter, i + 1, 4, 16, out); }
            case 'U' -> { return appendEscapedCodePoint(filter, i + 1, 8, 16, out); }
            case 'x', 'X' -> { return appendEscapedCodePoint(filter, i + 1, 2, 16, out); }
            default -> {
                if (c >= '0' && c <= '7') {
                    return appendEscapedCodePoint(filter, i, 3, 8, out);
                }
                throw invalid("unsupported escape sequence '\\" + c + "'");
            }
        }
        return i + 1;
    }

    private static int appendEscapedCodePoint(String filter, int start, int digits, int radix,
            StringBuilder out) {
        if (start + digits > filter.length()) {
            throw invalid("truncated escape sequence");
        }
        String text = filter.substring(start, start + digits);
        try {
            out.appendCodePoint(Integer.parseInt(text, radix));
        } catch (IllegalArgumentException e) {
            throw invalid("invalid escape sequence '" + text + "'");
        }
        return start + digits;
    }

    private static String describe(Token token) {
        return token.type() == TokenType.END ? "end of filter" : "'" + token.text() + "'";
    }

    private static GcpException invalid(String detail) {
        return GcpException.invalidArgument("Invalid subscription filter: " + detail);
    }
}
