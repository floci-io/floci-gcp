package io.floci.gcp.services.firebaseauth;

import java.math.BigInteger;
import java.util.regex.Pattern;

final class JsNumber {

    /** {@code StrDecimalLiteral}, without the type suffixes {@link Double#parseDouble} allows. */
    private static final Pattern DECIMAL_LITERAL =
            Pattern.compile("[+-]?(Infinity|(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?)");

    /** {@code NonDecimalIntegerLiteral}, which unlike the decimal form takes no sign. */
    private static final Pattern RADIX_LITERAL =
            Pattern.compile("0[xX][0-9a-fA-F]+|0[bB][01]+|0[oO][0-7]+");

    private JsNumber() {}

    /**
     * Coerces a JSON-decoded value the way {@code Number(value)} would. Absent values arrive as
     * {@code null}: {@code Number(undefined)} is {@code NaN} and {@code Number(null)} is
     * {@code 0}, both falsy, so callers applying a {@code ||} fallback cannot tell them apart.
     */
    static double of(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof Boolean bool) {
            return bool ? 1 : 0;
        }
        if (!(value instanceof String text)) {
            return Double.NaN;
        }
        String trimmed = trim(text);
        if (trimmed.isEmpty()) {
            return 0;
        }
        if (RADIX_LITERAL.matcher(trimmed).matches()) {
            int radix = switch (Character.toLowerCase(trimmed.charAt(1))) {
                case 'x' -> 16;
                case 'b' -> 2;
                default -> 8;
            };
            return new BigInteger(trimmed.substring(2), radix).doubleValue();
        }
        return DECIMAL_LITERAL.matcher(trimmed).matches() ? Double.parseDouble(trimmed) : Double.NaN;
    }

    /**
     * Strips ECMAScript {@code StrWhiteSpace}, which {@link String#trim} (chars {@code <= U+0020})
     * and {@link String#strip} both miss: {@link Character#isWhitespace} deliberately excludes the
     * non-breaking spaces, so {@code "\u00a03600"} would otherwise coerce to {@code NaN}.
     */
    private static String trim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    /** {@code WhiteSpace} and {@code LineTerminator} as ECMAScript defines them, all in the BMP. */
    private static boolean isWhitespace(char c) {
        return switch (c) {
            case '\t', '\n', '\u000B', '\f', '\r', '\u2028', '\u2029', '\uFEFF' -> true;
            // Zs: U+0020, U+00A0, U+1680, U+2000-U+200A, U+202F, U+205F, U+3000. Deliberately
            // not U+200B, which is Cf and which Number() does not strip.
            default -> Character.getType(c) == Character.SPACE_SEPARATOR;
        };
    }
}
