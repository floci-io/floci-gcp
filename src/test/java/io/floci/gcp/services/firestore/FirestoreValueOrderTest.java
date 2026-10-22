package io.floci.gcp.services.firestore;

import com.google.firestore.v1.ArrayValue;
import com.google.firestore.v1.MapValue;
import com.google.firestore.v1.Value;
import com.google.protobuf.ByteString;
import com.google.protobuf.NullValue;
import com.google.protobuf.Timestamp;
import com.google.type.LatLng;
import io.floci.gcp.services.firestore.model.StoredValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FirestoreValueOrderTest {

    private static final String DOCS = "projects/p1/databases/(default)/documents/";

    @Test
    void typesOrderBeforeValues() {
        List<Value> ascending = List.of(
                Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build(),
                Value.newBuilder().setBooleanValue(true).build(),
                dbl(Double.NaN),
                integer(Long.MAX_VALUE),
                Value.newBuilder().setTimestampValue(Timestamp.newBuilder().setSeconds(0)).build(),
                Value.newBuilder().setStringValue("").build(),
                bytes(0x00),
                Value.newBuilder().setReferenceValue(DOCS + "c/a").build(),
                Value.newBuilder().setGeoPointValue(LatLng.newBuilder().setLatitude(-90)).build(),
                array(),
                map());
        for (int i = 0; i + 1 < ascending.size(); i++) {
            assertBefore(ascending.get(i), ascending.get(i + 1));
        }
    }

    @Test
    void booleansOrderFalseFirst() {
        assertBefore(Value.newBuilder().setBooleanValue(false).build(),
                Value.newBuilder().setBooleanValue(true).build());
    }

    @Test
    void numbersOrderNaNFirstAndCompareAcrossIntegerAndDouble() {
        assertBefore(dbl(Double.NaN), dbl(Double.NEGATIVE_INFINITY));
        assertEquals(0, compare(dbl(Double.NaN), dbl(Double.NaN)));
        assertEquals(0, compare(dbl(-0.0), dbl(0.0)));
        assertEquals(0, compare(dbl(-0.0), integer(0)));
        assertBefore(integer(1), dbl(1.5));
        assertBefore(dbl(1.5), integer(2));
        assertBefore(dbl(9007199254740992.0), integer(9007199254740993L));
        assertBefore(integer(Long.MAX_VALUE), dbl(Double.POSITIVE_INFINITY));
    }

    @Test
    void stringsOrderByUtf8Bytes() {
        assertBefore(string("�"), string("😀"));
        assertBefore(string("a"), string("ab"));
        assertBefore(string("Z"), string("a"));
    }

    @Test
    void bytesCompareUnsignedThenByLength() {
        assertBefore(bytes(0x7f), bytes(0x80));
        assertBefore(bytes(0x01), bytes(0x01, 0x00));
    }

    @Test
    void referencesCompareSegmentBySegment() {
        assertBefore(reference("c/a/sub/x"), reference("c/a-b"));
        assertBefore(reference("c/__id2__"), reference("c/__id10__"));
        assertBefore(reference("c/__id99__"), reference("c/a"));
        assertBefore(reference("c/a"), reference("c/a/sub/x"));
    }

    @Test
    void geoPointsCompareLatitudeThenLongitude() {
        assertBefore(geoPoint(0, 50), geoPoint(10, -5));
        assertBefore(geoPoint(10, -5), geoPoint(10, 5));
    }

    @Test
    void arraysCompareElementsThenLength() {
        assertBefore(array(integer(1), integer(9)), array(integer(2)));
        assertBefore(array(integer(1)), array(integer(1), integer(0)));
        assertBefore(array(integer(9)), array(string("a")));
    }

    @Test
    void mapsCompareSortedKeysThenValuesThenSize() {
        assertBefore(map("a", integer(9)), map("b", integer(1)));
        assertBefore(map("a", integer(1), "z", integer(1)), map("a", integer(2)));
        assertBefore(map("a", integer(1)), map("a", integer(1), "b", integer(0)));
        assertEquals(0, compare(map("b", integer(1), "a", integer(2)), map("a", integer(2), "b", integer(1))));
    }

    @Test
    void mapKeysOrderByUtf8BytesNotUtf16Units() {
        assertBefore(map("\uE000", integer(1), "\uD83D\uDE00", integer(1)), map("\uD83D\uDE00", integer(1)));
    }

    private static void assertBefore(Value lower, Value higher) {
        assertTrue(compare(lower, higher) < 0, lower + " should order before " + higher);
        assertTrue(compare(higher, lower) > 0, higher + " should order after " + lower);
    }

    private static int compare(Value left, Value right) {
        return FirestoreValueOrder.compare(StoredValue.fromProto(left), StoredValue.fromProto(right));
    }

    private static Value integer(long value) {
        return Value.newBuilder().setIntegerValue(value).build();
    }

    private static Value dbl(double value) {
        return Value.newBuilder().setDoubleValue(value).build();
    }

    private static Value string(String value) {
        return Value.newBuilder().setStringValue(value).build();
    }

    private static Value bytes(int... values) {
        byte[] raw = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            raw[i] = (byte) values[i];
        }
        return Value.newBuilder().setBytesValue(ByteString.copyFrom(raw)).build();
    }

    private static Value reference(String path) {
        return Value.newBuilder().setReferenceValue(DOCS + path).build();
    }

    private static Value geoPoint(double latitude, double longitude) {
        return Value.newBuilder()
                .setGeoPointValue(LatLng.newBuilder().setLatitude(latitude).setLongitude(longitude))
                .build();
    }

    private static Value array(Value... values) {
        return Value.newBuilder().setArrayValue(ArrayValue.newBuilder().addAllValues(List.of(values))).build();
    }

    private static Value map(Object... keysAndValues) {
        MapValue.Builder map = MapValue.newBuilder();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.putFields((String) keysAndValues[i], (Value) keysAndValues[i + 1]);
        }
        return Value.newBuilder().setMapValue(map).build();
    }
}
