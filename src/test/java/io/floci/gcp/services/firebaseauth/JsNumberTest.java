package io.floci.gcp.services.firebaseauth;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every expectation here is what {@code Number(value)} returns in a JavaScript engine, since the
 * Firebase Auth emulator relies on that coercion for request fields.
 */
class JsNumberTest {

    @Test
    void coercesNumbersAndBooleans() {
        assertEquals(3600, JsNumber.of(3600));
        assertEquals(0.5, JsNumber.of(0.5));
        assertEquals(0, JsNumber.of(0));
        assertEquals(1, JsNumber.of(true));
        assertEquals(0, JsNumber.of(false));
    }

    @Test
    void coercesBlankAndAbsentValues() {
        assertTrue(Double.isNaN(JsNumber.of(null)));
        assertEquals(0, JsNumber.of(""));
        assertEquals(0, JsNumber.of("   "));
        assertEquals(0, JsNumber.of("\u00a0\u3000"));
    }

    @Test
    void stripsEcmaScriptWhitespaceRatherThanJavaWhitespace() {
        assertEquals(3600, JsNumber.of(" 3600 "));
        assertEquals(3600, JsNumber.of("\t3600\n"));
        // String.trim() stops at U+0020 and Character.isWhitespace excludes the non-breaking
        // spaces, so these are the cases a naive trim gets wrong.
        assertEquals(3600, JsNumber.of("\u00a03600\u00a0"));
        assertEquals(3600, JsNumber.of("\u30003600"));
        assertEquals(3600, JsNumber.of("\u20283600\ufeff"));
        assertEquals(3600, JsNumber.of("\u205f3600\u202f"));
        // U+200B is Cf, not whitespace: Number() leaves it in place and yields NaN.
        assertTrue(Double.isNaN(JsNumber.of("\u200b3600")));
    }

    @Test
    void readsRadixLiteralsThatParseDoubleRejects() {
        assertEquals(16, JsNumber.of("0x10"));
        assertEquals(4096, JsNumber.of("0x1000"));
        assertEquals(10, JsNumber.of("0b1010"));
        assertEquals(15, JsNumber.of("0o17"));
        // A sign is not permitted on a non-decimal literal.
        assertTrue(Double.isNaN(JsNumber.of("-0x10")));
    }

    @Test
    void rejectsJavaTypeSuffixesThatParseDoubleAccepts() {
        assertTrue(Double.isNaN(JsNumber.of("3600d")));
        assertTrue(Double.isNaN(JsNumber.of("3600f")));
        assertTrue(Double.isNaN(JsNumber.of("3600s")));
        assertTrue(Double.isNaN(JsNumber.of("0x1p3")));
    }

    @Test
    void readsDecimalLiterals() {
        assertEquals(1000, JsNumber.of("1e3"));
        assertEquals(3600, JsNumber.of("3.6e3"));
        assertEquals(0.001, JsNumber.of("1e-3"));
        assertEquals(0.5, JsNumber.of(".5"));
        assertEquals(300, JsNumber.of("300."));
        assertEquals(300, JsNumber.of("+300"));
        assertEquals(Double.POSITIVE_INFINITY, JsNumber.of("Infinity"));
        assertEquals(Double.NEGATIVE_INFINITY, JsNumber.of("-Infinity"));
        assertTrue(Double.isNaN(JsNumber.of("NaN")));
        assertTrue(Double.isNaN(JsNumber.of("not-a-number")));
    }

    @Test
    void treatsStructuredValuesAsNotANumber() {
        assertTrue(Double.isNaN(JsNumber.of(Map.of("a", 1))));
        assertTrue(Double.isNaN(JsNumber.of(List.of(1, 2))));
    }
}
