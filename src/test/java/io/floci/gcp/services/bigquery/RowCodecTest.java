package io.floci.gcp.services.bigquery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.bigquery.model.ErrorProto;
import io.floci.gcp.services.bigquery.model.TableCell;
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
    void nullRepeatedCellEncodesAsEmptyArray() {
        TableFieldSchema tags = new TableFieldSchema();
        tags.setName("tags");
        tags.setType("STRING");
        tags.setMode("REPEATED");
        TableFieldSchema name = new TableFieldSchema();
        name.setName("name");
        name.setType("STRING");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tags", null);
        row.put("name", null);

        List<TableCell> cells = RowCodec.encodeRow(new TableSchema(List.of(tags, name)), row,
                RowCodec.TimestampFormat.ISO8601_STRING).getF();

        assertEquals(List.of(), cells.get(0).getV());
        assertEquals(null, cells.get(1).getV());
    }

    @Test
    void insertAllJsonStringsStayJsonText() {
        assertEquals("{\"a\": 1}", storedJson("{\"a\": 1}", false));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ndjsonLoadStoresNestedJsonAsTextAndStagesItAsJson() throws Exception {
        TableFieldSchema child = new TableFieldSchema();
        child.setName("j");
        child.setType("JSON");
        TableFieldSchema rec = new TableFieldSchema();
        rec.setName("rec");
        rec.setType("RECORD");
        rec.setFields(List.of(child));
        TableFieldSchema arr = new TableFieldSchema();
        arr.setName("arr");
        arr.setType("JSON");
        arr.setMode("REPEATED");
        TableFieldSchema top = new TableFieldSchema();
        top.setName("top");
        top.setType("JSON");
        TableSchema schema = new TableSchema(List.of(rec, arr, top));

        Map<String, Object> out = new LinkedHashMap<>();
        List<ErrorProto> errors = RowCodec.normalizeRow(schema, Map.of("rec", Map.of("j", 20),
                "arr", List.of(20, "This is a string", Map.of("a", 1)), "top", 20), false, true, out);

        assertTrue(errors.isEmpty(), String.valueOf(errors));
        assertEquals("20", ((Map<String, Object>) out.get("rec")).get("j"));
        assertEquals(List.of("20", "\"This is a string\"", "{\"a\":1}"), out.get("arr"));

        ObjectMapper json = new ObjectMapper();
        Map<String, Object> staged = RowCodec.stagingRow(schema, out);
        assertEquals(json.readTree("20"), ((Map<String, Object>) staged.get("rec")).get("j"));
        assertEquals(List.of(json.readTree("20"), json.readTree("\"This is a string\""), json.readTree("{\"a\":1}")),
                staged.get("arr"));
        assertEquals("20", staged.get("top"));
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

    @Test
    void validTimestampsAreNormalizedToIsoString() {
        TableFieldSchema field = new TableFieldSchema();
        field.setName("ts");
        field.setType("TIMESTAMP");

        String expected = "2023-10-01T12:00:00.000000Z";
        for (String input : List.of(
                "2023-10-01 12:00 UTC",
                "2023-10-01 14:00+02:00",
                "2023-10-01T12:00Z",
                "2023-10-01 14:00:00 +02:00"
        )) {
            Map<String, Object> out = new LinkedHashMap<>();
            List<ErrorProto> errors = RowCodec.normalizeRow(new TableSchema(List.of(field)), Map.of("ts", input), false, out);
            assertTrue(errors.isEmpty(), "Input should be accepted: " + input);
            assertEquals(expected, out.get("ts"), "Input should be normalized to ISO string: " + input);
        }
    }
    @Test
    void formatForDuckNormalizesTimestampsAndPreservesOthers() {
        TableFieldSchema tsField = new TableFieldSchema();
        tsField.setName("ts");
        tsField.setType("TIMESTAMP");

        TableFieldSchema strField = new TableFieldSchema();
        strField.setName("s");
        strField.setType("STRING");

        TableFieldSchema nestedField = new TableFieldSchema();
        nestedField.setName("nested");
        nestedField.setType("RECORD");
        nestedField.setFields(List.of(tsField));

        TableFieldSchema repeatedField = new TableFieldSchema();
        repeatedField.setName("arr");
        repeatedField.setType("TIMESTAMP");
        repeatedField.setMode("REPEATED");

        TableSchema schema = new TableSchema(List.of(tsField, strField, nestedField, repeatedField));

        Map<String, Object> input = Map.of(
                "ts", "2023-10-01 12:00 UTC",
                "s", "hello",
                "nested", Map.of("ts", "1704164645.5"),
                "arr", List.of("2023-10-01 14:00+02:00", "not a date")
        );

        Map<String, Object> output = RowCodec.formatForDuck(schema, input);

        assertEquals("2023-10-01T12:00:00.000000Z", output.get("ts"));
        assertEquals("hello", output.get("s"));

        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) output.get("nested");
        assertEquals("2024-01-02T03:04:05.500000Z", nested.get("ts"));

        @SuppressWarnings("unchecked")
        List<Object> arr = (List<Object>) output.get("arr");
        assertEquals("2023-10-01T12:00:00.000000Z", arr.get(0));
        assertEquals("not a date", arr.get(1)); // unparseable falls back to original text
    }
}
