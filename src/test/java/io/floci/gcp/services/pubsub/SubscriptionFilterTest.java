package io.floci.gcp.services.pubsub;

import io.floci.gcp.core.common.GcpException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionFilterTest {

    @Test
    void blankFilterMatchesEverything() {
        assertTrue(matches(null, Map.of()));
        assertTrue(matches("", Map.of("a", "b")));
        assertTrue(matches("   ", Map.of("a", "b")));
    }

    // ── Existence ──────────────────────────────────────────────────────────────

    @Test
    void existenceMatchesWhenKeyPresent() {
        assertTrue(matches("attributes:name", Map.of("name", "com")));
    }

    @Test
    void existenceMatchesRegardlessOfValue() {
        assertTrue(matches("attributes:name", Map.of("name", "")));
    }

    @Test
    void existenceDoesNotMatchWhenKeyAbsent() {
        assertFalse(matches("attributes:name", Map.of("other", "com")));
    }

    @Test
    void existenceAcceptsQuotedKey() {
        assertTrue(matches("attributes:\"name\"", Map.of("name", "com")));
    }

    @Test
    void existenceAcceptsQuotedKeyWithSpecialCharacters() {
        assertTrue(matches("attributes:\"iana.org/language_tag\"",
                Map.of("iana.org/language_tag", "en")));
        assertFalse(matches("attributes:\"iana.org/language_tag\"", Map.of("iana", "en")));
    }

    @Test
    void existenceKeyIsCaseSensitive() {
        assertFalse(matches("attributes:name", Map.of("Name", "com")));
    }

    // ── Equality ───────────────────────────────────────────────────────────────

    @Test
    void equalityMatchesExactValue() {
        assertTrue(matches("attributes.name = \"com\"", Map.of("name", "com")));
    }

    @Test
    void equalityDoesNotMatchDifferentValue() {
        assertFalse(matches("attributes.name = \"com\"", Map.of("name", "org")));
    }

    @Test
    void equalityDoesNotMatchAbsentKey() {
        assertFalse(matches("attributes.name = \"com\"", Map.of("other", "com")));
    }

    @Test
    void equalityValueIsCaseSensitive() {
        assertFalse(matches("attributes.name = \"com\"", Map.of("name", "COM")));
    }

    @Test
    void equalityKeyIsCaseSensitive() {
        assertFalse(matches("attributes.name = \"com\"", Map.of("NAME", "com")));
    }

    @Test
    void equalityAcceptsQuotedKey() {
        assertTrue(matches("attributes.\"iana.org/language_tag\" = \"en\"",
                Map.of("iana.org/language_tag", "en")));
    }

    @Test
    void equalityMatchesEmptyValue() {
        assertTrue(matches("attributes.name = \"\"", Map.of("name", "")));
    }

    // ── Inequality ─────────────────────────────────────────────────────────────

    @Test
    void inequalityDoesNotMatchEqualValue() {
        assertFalse(matches("attributes.name != \"com\"", Map.of("name", "com")));
    }

    @Test
    void inequalityMatchesDifferentValue() {
        assertTrue(matches("attributes.name != \"com\"", Map.of("name", "org")));
    }

    @Test
    void inequalityMatchesAbsentKey() {
        assertTrue(matches("attributes.name != \"com\"", Map.of("other", "com")));
        assertTrue(matches("attributes.name != \"com\"", Map.of()));
    }

    // ── hasPrefix ──────────────────────────────────────────────────────────────

    @Test
    void hasPrefixMatchesValueWithPrefix() {
        assertTrue(matches("hasPrefix(attributes.name, \"co\")", Map.of("name", "com")));
    }

    @Test
    void hasPrefixMatchesWholeValue() {
        assertTrue(matches("hasPrefix(attributes.name, \"com\")", Map.of("name", "com")));
    }

    @Test
    void hasPrefixDoesNotMatchOtherPrefix() {
        assertFalse(matches("hasPrefix(attributes.name, \"co\")", Map.of("name", "org")));
    }

    @Test
    void hasPrefixDoesNotMatchAbsentKey() {
        assertFalse(matches("hasPrefix(attributes.name, \"co\")", Map.of("other", "com")));
    }

    @Test
    void hasPrefixIsCaseSensitive() {
        assertFalse(matches("hasPrefix(attributes.name, \"CO\")", Map.of("name", "com")));
    }

    @Test
    void hasPrefixAcceptsQuotedKey() {
        assertTrue(matches("hasPrefix(attributes.\"iana.org/language_tag\", \"en\")",
                Map.of("iana.org/language_tag", "en-GB")));
    }

    @Test
    void hasPrefixMatchesEmptyPrefix() {
        assertTrue(matches("hasPrefix(attributes.name, \"\")", Map.of("name", "com")));
    }

    // ── Boolean operators ──────────────────────────────────────────────────────

    @Test
    void andRequiresBothOperands() {
        String filter = "attributes.a = \"1\" AND attributes.b = \"2\"";
        assertTrue(matches(filter, Map.of("a", "1", "b", "2")));
        assertFalse(matches(filter, Map.of("a", "1", "b", "9")));
        assertFalse(matches(filter, Map.of("a", "9", "b", "2")));
    }

    @Test
    void orRequiresEitherOperand() {
        String filter = "attributes.a = \"1\" OR attributes.b = \"2\"";
        assertTrue(matches(filter, Map.of("a", "1")));
        assertTrue(matches(filter, Map.of("b", "2")));
        assertFalse(matches(filter, Map.of("a", "9", "b", "9")));
    }

    @Test
    void notNegatesOperand() {
        assertTrue(matches("NOT attributes:name", Map.of("other", "x")));
        assertFalse(matches("NOT attributes:name", Map.of("name", "x")));
    }

    @Test
    void unaryMinusIsAliasForNot() {
        assertTrue(matches("-attributes:name", Map.of("other", "x")));
        assertFalse(matches("-attributes:name", Map.of("name", "x")));
    }

    @Test
    void notBindsTighterThanAnd() {
        String filter = "NOT attributes:a AND attributes:b";
        assertTrue(matches(filter, Map.of("b", "1")));
        assertFalse(matches(filter, Map.of("a", "1", "b", "1")));
        assertFalse(matches(filter, Map.of("a", "1")));
    }

    @Test
    void andCombinesWithNegatedEquality() {
        String filter = "attributes:\"iana.org/language_tag\" AND NOT attributes.name = \"com\"";
        assertTrue(matches(filter, Map.of("iana.org/language_tag", "en", "name", "org")));
        assertFalse(matches(filter, Map.of("iana.org/language_tag", "en", "name", "com")));
        assertFalse(matches(filter, Map.of("name", "org")));
    }

    @Test
    void parenthesesGroupOrInsideAnd() {
        String filter = "attributes:\"iana.org/language_tag\" "
                + "AND (attributes.name = \"net\" OR attributes.name = \"org\")";
        assertTrue(matches(filter, Map.of("iana.org/language_tag", "en", "name", "net")));
        assertTrue(matches(filter, Map.of("iana.org/language_tag", "en", "name", "org")));
        assertFalse(matches(filter, Map.of("iana.org/language_tag", "en", "name", "com")));
        assertFalse(matches(filter, Map.of("name", "net")));
    }

    @Test
    void unaryMinusCombinesWithAnd() {
        String filter = "attributes.name = \"com\" AND -attributes:\"iana.org/language_tag\"";
        assertTrue(matches(filter, Map.of("name", "com")));
        assertFalse(matches(filter, Map.of("name", "com", "iana.org/language_tag", "en")));
    }

    @Test
    void nestedParenthesesAreSupported() {
        String filter = "((attributes.a = \"1\"))";
        assertTrue(matches(filter, Map.of("a", "1")));
        assertFalse(matches(filter, Map.of("a", "2")));
    }

    @Test
    void doubleNegationIsSupported() {
        assertTrue(matches("NOT NOT attributes:a", Map.of("a", "1")));
        assertFalse(matches("NOT NOT attributes:a", Map.of()));
    }

    // ── String literals and escapes ────────────────────────────────────────────

    @Test
    void unicodeEscapeSequencesInStringLiteral() {
        assertTrue(matches("attributes:\"\\u307F\\u3093\\u306A\"", Map.of("\u307F\u3093\u306A", "x")));
    }

    @Test
    void hexEscapeSequenceInStringLiteral() {
        assertTrue(matches("attributes.name = \"\\x41\"", Map.of("name", "A")));
    }

    @Test
    void octalEscapeSequenceInStringLiteral() {
        assertTrue(matches("attributes.name = \"\\101\"", Map.of("name", "A")));
    }

    @Test
    void escapedQuoteInStringLiteral() {
        assertTrue(matches("attributes.name = \"a\\\"b\"", Map.of("name", "a\"b")));
    }

    @Test
    void escapedBackslashInStringLiteral() {
        assertTrue(matches("attributes.name = \"a\\\\b\"", Map.of("name", "a\\b")));
    }

    @Test
    void commonControlCharacterEscapesInStringLiteral() {
        assertTrue(matches("attributes.name = \"a\\tb\"", Map.of("name", "a\tb")));
        assertTrue(matches("attributes.name = \"a\\nb\"", Map.of("name", "a\nb")));
    }

    // ── Invalid syntax ─────────────────────────────────────────────────────────

    @Test
    void mixingAndWithOrWithoutParenthesesIsRejected() {
        assertInvalid("attributes:\"iana.org/language_tag\" AND attributes.name = \"net\" "
                + "OR attributes.name = \"org\"");
    }

    @Test
    void valueOnLeftHandSideIsRejected() {
        assertInvalid("\"com\" = attributes.name");
    }

    @Test
    void comparingTwoAttributesIsRejected() {
        assertInvalid("attributes.name = attributes.website");
    }

    @Test
    void escapeSequenceOutsideStringLiteralIsRejected() {
        assertInvalid("attributes:\\u307F\\u3093\\u306A");
    }

    @Test
    void bareValuesInParenthesesAreRejected() {
        assertInvalid("attributes.name = \"com\" AND (\"net\" OR \"org\")");
    }

    @Test
    void unbalancedParenthesesAreRejected() {
        assertInvalid("(attributes.name = \"com\"");
        assertInvalid("attributes.name = \"com\")");
    }

    @Test
    void lowercaseBooleanOperatorsAreRejected() {
        assertInvalid("attributes:a and attributes:b");
        assertInvalid("attributes:a or attributes:b");
        assertInvalid("not attributes:a");
    }

    @Test
    void unterminatedStringLiteralIsRejected() {
        assertInvalid("attributes.name = \"com");
    }

    @Test
    void trailingOperatorIsRejected() {
        assertInvalid("attributes:a AND");
        assertInvalid("attributes.name =");
    }

    @Test
    void unknownFunctionIsRejected() {
        assertInvalid("matches(attributes.name, \"co.*\")");
    }

    @Test
    void hasPrefixWithWrongArgumentCountIsRejected() {
        assertInvalid("hasPrefix(attributes.name)");
        assertInvalid("hasPrefix(attributes.name, \"a\", \"b\")");
    }

    @Test
    void hasPrefixWithNonLiteralPrefixIsRejected() {
        assertInvalid("hasPrefix(attributes.name, attributes.other)");
    }

    @Test
    void unsupportedFieldIsRejected() {
        assertInvalid("data = \"com\"");
        assertInvalid("attribute.name = \"com\"");
    }

    @Test
    void unsupportedOperatorIsRejected() {
        assertInvalid("attributes.name > \"com\"");
    }

    @Test
    void emptyParenthesesAreRejected() {
        assertInvalid("()");
    }

    @Test
    void garbageIsRejected() {
        assertInvalid("this is not a filter (((");
    }

    // ── Length limit ───────────────────────────────────────────────────────────

    @Test
    void filterAtTheByteLimitIsAccepted() {
        String filter = filterOfLength(256);
        assertEquals(256, filter.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        SubscriptionFilter.validate(filter);
    }

    @Test
    void filterOverTheByteLimitIsRejected() {
        GcpException ex = assertThrows(GcpException.class,
                () -> SubscriptionFilter.validate(filterOfLength(257)));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    @Test
    void lengthLimitCountsUtf8BytesNotCharacters() {
        String key = "\u00E1".repeat(120);
        String filter = "attributes.name = \"" + key + "\"";
        assertTrue(filter.length() <= 256);
        assertTrue(filter.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 256);

        GcpException ex = assertThrows(GcpException.class, () -> SubscriptionFilter.validate(filter));
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    @Test
    void validateAcceptsBlankFilter() {
        SubscriptionFilter.validate(null);
        SubscriptionFilter.validate("");
    }

    private static String filterOfLength(int totalBytes) {
        String prefix = "attributes.name = \"";
        int padding = totalBytes - prefix.length() - 1;
        return prefix + "x".repeat(padding) + "\"";
    }

    private static boolean matches(String filter, Map<String, String> attributes) {
        return SubscriptionFilter.parse(filter).test(attributes);
    }

    private static void assertInvalid(String filter) {
        GcpException ex = assertThrows(GcpException.class, () -> SubscriptionFilter.parse(filter),
                "expected filter to be rejected: " + filter);
        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }
}
