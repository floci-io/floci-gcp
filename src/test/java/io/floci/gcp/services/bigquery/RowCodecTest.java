package io.floci.gcp.services.bigquery;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.bigquery.model.ErrorProto;
import io.floci.gcp.services.bigquery.model.TableFieldSchema;
import io.floci.gcp.services.bigquery.model.TableSchema;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RowCodecTest {

    private static List<ErrorProto> normalize(Object value, Map<String, Object> out) {
        TableFieldSchema field = new TableFieldSchema();
        field.setName("n");
        field.setType("INTEGER");
        return RowCodec.normalizeRow(new TableSchema(List.of(field)), Map.of("n", value), false, out);
    }

    private static Object storedJson(Object value, boolean nativeJson) {
        TableFieldSchema field = new TableFieldSchema();
        field.setName("j");
        field.setType("JSON");
        Map<String, Object> out = new LinkedHashMap<>();
        List<ErrorProto> errors = RowCodec.normalizeRow(
                new TableSchema(List.of(field)), Map.of("j", value), false, nativeJson, out);
        assertTrue(errors.isEmpty(), String.valueOf(errors));
        return out.get("j");
    }

    @Test
    void ndjsonLoadStoresEachValueAsItsJsonValue() {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("id", 10);
        object.put("name", "Alice");

        assertEquals("20", storedJson(20, true));
        assertEquals("\"This is a string\"", storedJson("This is a string", true));
        assertEquals("{\"id\":10,\"name\":\"Alice\"}", storedJson(object, true));
        assertEquals("\"{\\\"looks\\\": \\\"like json\\\"}\"", storedJson("{\"looks\": \"like json\"}", true));
        assertEquals("[1,2]", storedJson(List.of(1, 2), true));
    }

    @Test
    void insertAllJsonStringsStayJsonText() {
        assertEquals("{\"a\": 1}", storedJson("{\"a\": 1}", false));
    }

    @Test
    void integersInRangeAreKept() {
        for (Object value : List.of(Long.MAX_VALUE, Long.MIN_VALUE, 7, new BigDecimal("42"), new BigInteger("-3"), 5.0)) {
            Map<String, Object> out = new LinkedHashMap<>();
            assertTrue(normalize(value, out).isEmpty(), String.valueOf(value));
            assertEquals(new BigDecimal(value.toString()).longValueExact(), out.get("n"), String.valueOf(value));
        }
    }

    @Test
    void integersOutsideInt64AreRejectedNotWrapped() {
        for (Object value : List.of(new BigDecimal("18446744073709551615"), new BigInteger("9223372036854775808"),
                1e19, Double.POSITIVE_INFINITY)) {
            List<ErrorProto> errors = normalize(value, new LinkedHashMap<>());
            assertEquals(1, errors.size(), String.valueOf(value));
            assertTrue(errors.get(0).getMessage().contains("Cannot convert value to integer"),
                    errors.get(0).getMessage());
        }
    }

    @Test
    void namelessSchemaFieldsAreRejected() {
        TableFieldSchema field = new TableFieldSchema();
        field.setType("STRING");
        GcpException e = assertThrows(
                GcpException.class,
                () -> RowCodec.normalizeSchema(new TableSchema(List.of(field)))
        );
        assertTrue(e.getMessage().contains("name cannot be empty"), e.getMessage());
        assertEquals("invalid", e.getReason());

        field.setName(" ");
        e = assertThrows(
                GcpException.class,
                () -> RowCodec.normalizeSchema(new TableSchema(List.of(field)))
        );
        assertTrue(e.getMessage().contains("name cannot be empty"), e.getMessage());
        assertEquals("invalid", e.getReason());
    }

    @Test
    void invalidTimestampsAreRejected() {
        TableFieldSchema field = new TableFieldSchema();
        field.setName("ts");
        field.setType("TIMESTAMP");

        // Valid timestamp should not return an error
        Map<String, Object> out = new LinkedHashMap<>();
        List<ErrorProto> errors = RowCodec.normalizeRow(new TableSchema(List.of(field)), Map.of("ts", "2023-10-01 12:00:00"), false, out);
        assertTrue(errors.isEmpty(), "Valid timestamp should be accepted");

        // Valid timestamp without seconds
        out.clear();
        errors = RowCodec.normalizeRow(new TableSchema(List.of(field)), Map.of("ts", "2023-10-01 12:00"), false, out);
        assertTrue(errors.isEmpty(), "Valid timestamp without seconds should be accepted");

        // Valid timestamp with slash separator
        out.clear();
        errors = RowCodec.normalizeRow(new TableSchema(List.of(field)), Map.of("ts", "2023/10/01 12:00:00"), false, out);
        assertTrue(errors.isEmpty(), "Valid timestamp with slash should be accepted");

        // Invalid timestamp (out of bounds year 10000 string) should return an error
        out.clear();
        errors = RowCodec.normalizeRow(new TableSchema(List.of(field)), Map.of("ts", "99999-01-01 00:00:00"), false, out);
        assertEquals(1, errors.size(), "Invalid timestamp should be rejected");
        assertTrue(errors.get(0).getMessage().contains("Could not parse"), errors.get(0).getMessage());

        // Invalid timestamp (out of bounds epoch seconds year 10000) should return an error
        out.clear();
        errors = RowCodec.normalizeRow(new TableSchema(List.of(field)), Map.of("ts", "253402300800"), false, out);
        assertEquals(1, errors.size(), "Invalid timestamp epoch seconds should be rejected");
        assertTrue(errors.get(0).getMessage().contains("out of supported range"), errors.get(0).getMessage());

        // Invalid timestamp (out of bounds year 0) should return an error
        out.clear();
        errors = RowCodec.normalizeRow(new TableSchema(List.of(field)), Map.of("ts", "0000-12-31 23:59:59"), false, out);
        assertEquals(1, errors.size(), "Invalid timestamp year 0 should be rejected");
        assertTrue(errors.get(0).getMessage().contains("out of supported range"), errors.get(0).getMessage());

        // Invalid timestamp (out of bounds below minimum year 1 epoch) should return an error
        out.clear();
        errors = RowCodec.normalizeRow(new TableSchema(List.of(field)), Map.of("ts", "-62135596801"), false, out);
        assertEquals(1, errors.size(), "Invalid timestamp below minimum year 1 epoch should be rejected");
        assertTrue(errors.get(0).getMessage().contains("out of supported range"), errors.get(0).getMessage());

        // Completely unparseable timestamp should return parse error
        out.clear();
        errors = RowCodec.normalizeRow(new TableSchema(List.of(field)), Map.of("ts", "not a date"), false, out);
        assertEquals(1, errors.size(), "Unparseable timestamp should be rejected");
        assertTrue(errors.get(0).getMessage().contains("Could not parse"), errors.get(0).getMessage());
    }

    @Test
    void unparseablePersistentTimestampsDoNotCrashReads() {
        // Simulates a bad timestamp inserted before write-time validation was added
        String bad = "99999-01-01 00:00:00";
        assertEquals(bad, RowCodec.encodeTimestamp(bad, RowCodec.TimestampFormat.ISO8601_STRING));
        assertEquals(bad, RowCodec.encodeTimestamp(bad, RowCodec.TimestampFormat.FLOAT64));
        assertEquals(bad, RowCodec.encodeTimestamp(bad, RowCodec.TimestampFormat.INT64));
    }
}
