package io.floci.gcp.services.gcs;

import io.floci.gcp.core.common.GcpException;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Compiles a Cloud Storage {@code matchGlob} pattern into a regex.
 *
 * <p>The syntax is documented on objects.list:
 * <pre>
 *   ?           a single character, excluding "/"
 *   *           zero or more characters, excluding "/"
 *   **          zero or more characters, including "/"
 *   [abc] [a-z] a character class; [!abc] negates it
 *   {abc,xyz}   brace alternation
 * </pre>
 *
 * <p>The distinction that matters is {@code *} vs {@code **}: {@code logs/*.json} must not
 * reach across a path segment while {@code a/**} must. A naive translation of {@code *} to
 * {@code .*} collapses the two and makes every pattern behave like {@code **}.
 */
final class GcsObjectGlob {

    private GcsObjectGlob() {
    }

    static Pattern compile(String glob) {
        if (glob == null || glob.isEmpty()) {
            return null;
        }
        if (glob.length() > 1024) {
            throw GcpException.invalidArgument("matchGlob exceeds the 1024 byte limit");
        }

        StringBuilder out = new StringBuilder(glob.length() * 2);
        int depth = 0;

        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        i++;
                        // "**/" should also match zero segments, so "**/x" matches a bare "x".
                        String token = i + 1 < glob.length() && glob.charAt(i + 1) == '/'
                                ? "(?:.*/)?" : ".*";
                        if (token.equals("(?:.*/)?")) {
                            i++;
                        }
                        // Adjacent duplicates are redundant (".*.*" matches exactly what ".*"
                        // matches) but each one multiplies the backtracking the engine does on a
                        // name that does not match, so collapse them instead of emitting both.
                        if (!endsWith(out, token)) {
                            out.append(token);
                        }
                    } else {
                        if (!endsWith(out, "[^/]*")) {
                            out.append("[^/]*");
                        }
                    }
                }
                case '?' -> out.append("[^/]");
                case '[' -> i = appendCharClass(glob, i, out);
                case '{' -> {
                    depth++;
                    out.append("(?:");
                }
                case '}' -> {
                    if (depth > 0) {
                        depth--;
                        out.append(')');
                    } else {
                        out.append("\\}");
                    }
                }
                case ',' -> out.append(depth > 0 ? "|" : "\\,");
                default -> appendLiteral(out, c);
            }
        }

        if (depth != 0) {
            throw GcpException.invalidArgument("unbalanced braces in matchGlob: " + glob);
        }

        try {
            return Pattern.compile(out.toString(), Pattern.DOTALL);
        } catch (PatternSyntaxException e) {
            throw GcpException.invalidArgument("invalid matchGlob: " + glob);
        }
    }

    /** Copies a [...] class through, translating a leading "!" to regex "^". Returns the index of ']'. */
    private static int appendCharClass(String glob, int open, StringBuilder out) {
        int close = glob.indexOf(']', open + 1);
        if (close < 0) {
            // An unterminated "[" is a literal, matching shell behavior.
            out.append("\\[");
            return open;
        }
        String body = glob.substring(open + 1, close);
        out.append('[');
        if (body.startsWith("!")) {
            out.append('^').append(Pattern.quote(body.substring(1)).replace("\\Q", "").replace("\\E", ""));
        } else {
            out.append(body.replace("\\", "\\\\"));
        }
        out.append(']');
        return close;
    }

    /**
     * Character reads one {@code objects.list} request may spend on glob matching, across every
     * object it examines. Java's regex engine backtracks combinatorially across adjacent
     * unbounded groups, so a request-controlled glob can otherwise stall a request thread:
     * measured against a long object name, seven `*` groups inside one segment never finish, and
     * `*` is no safer than `**` despite being segment-bounded.
     *
     * <p>The budget is per request rather than per object on purpose. A per-object budget bounds
     * one match but not the listing: the pattern is evaluated against every candidate before
     * pagination, so a large bucket multiplies a just-under-budget match back into the same
     * request-level stall. One counter for the whole listing bounds the total instead.
     *
     * <p>It bounds every pattern rather than guessing which shapes are dangerous, which a
     * wildcard count cannot do once `{a,b}` alternation and `[...]` classes are in play, and it
     * leaves globs real GCS accepts working: an ordinary pattern spends about 30 reads per
     * object, so ten thousand objects come to roughly 300,000 against this budget.
     */
    private static final int REQUEST_STEP_BUDGET = 5_000_000;

    /** A glob bound to one request, carrying that request's remaining matching budget. */
    static GlobMatcher matcher(Pattern pattern) {
        return new GlobMatcher(pattern);
    }

    static final class GlobMatcher {
        private final Pattern pattern;
        private int steps;

        private GlobMatcher(Pattern pattern) {
            this.pattern = pattern;
        }

        boolean matches(String name) {
            if (pattern == null) {
                return true;
            }
            try {
                return pattern.matcher(new BoundedCharSequence(name)).matches();
            } catch (StepBudgetExceeded e) {
                throw GcpException.invalidArgument(
                        "matchGlob is too complex to evaluate over this bucket; simplify the pattern");
            }
        }

        private final class BoundedCharSequence implements CharSequence {
            private final CharSequence delegate;

            BoundedCharSequence(CharSequence delegate) {
                this.delegate = delegate;
            }

            @Override
            public int length() {
                return delegate.length();
            }

            @Override
            public char charAt(int index) {
                if (++steps > REQUEST_STEP_BUDGET) {
                    throw new StepBudgetExceeded();
                }
                return delegate.charAt(index);
            }

            @Override
            public CharSequence subSequence(int start, int end) {
                return delegate.subSequence(start, end);
            }

            @Override
            public String toString() {
                return delegate.toString();
            }
        }
    }

    private static final class StepBudgetExceeded extends RuntimeException {
        StepBudgetExceeded() {
            super(null, null, false, false);
        }
    }

    private static boolean endsWith(StringBuilder out, String token) {
        int start = out.length() - token.length();
        return start >= 0 && out.indexOf(token, start) == start;
    }

    private static void appendLiteral(StringBuilder out, char c) {
        if ("\\.^$|()+".indexOf(c) >= 0) {
            out.append('\\');
        }
        out.append(c);
    }
}
