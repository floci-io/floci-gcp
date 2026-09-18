package io.floci.gcp.services.bigquery;

import io.floci.gcp.services.bigquery.model.TableFieldSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuckTypesTest {

    private static TableFieldSchema field(String name, String type, String mode, List<TableFieldSchema> fields) {
        TableFieldSchema f = new TableFieldSchema();
        f.setName(name);
        f.setType(type);
        f.setMode(mode);
        f.setFields(fields);
        return f;
    }

    @Test
    void parsesScalarListAndNestedStructTypes() {
        DuckTypes.DuckType type = DuckTypes.parse("STRUCT(a INTEGER[], \"b c\" TIMESTAMP WITH TIME ZONE)[]");
        assertTrue(type.isList());
        DuckTypes.DuckType element = type.element();
        assertTrue(element.isStruct());
        assertEquals("a", element.fields().get(0).name());
        assertTrue(element.fields().get(0).type().isList());
        assertEquals("b c", element.fields().get(1).name());
        assertEquals("TIMESTAMP WITH TIME ZONE", element.fields().get(1).type().base());
        assertEquals("DECIMAL", DuckTypes.parse("DECIMAL(38,9)").base());
    }

    @Test
    void mapsDuckTypesToBigQueryFields() {
        assertEquals("INTEGER", DuckTypes.toField("n", DuckTypes.parse("HUGEINT")).getType());
        assertEquals("FLOAT", DuckTypes.toField("n", DuckTypes.parse("DOUBLE")).getType());
        assertEquals("NUMERIC", DuckTypes.toField("n", DuckTypes.parse("DECIMAL(18,3)")).getType());
        assertEquals("TIMESTAMP", DuckTypes.toField("n", DuckTypes.parse("TIMESTAMP WITH TIME ZONE")).getType());
        assertEquals("DATETIME", DuckTypes.toField("n", DuckTypes.parse("TIMESTAMP")).getType());

        TableFieldSchema repeated = DuckTypes.toField("tags", DuckTypes.parse("VARCHAR[]"));
        assertEquals("STRING", repeated.getType());
        assertEquals("REPEATED", repeated.getMode());

        TableFieldSchema record = DuckTypes.toField("s", DuckTypes.parse("STRUCT(x BIGINT, y VARCHAR)"));
        assertEquals("RECORD", record.getType());
        assertEquals("NULLABLE", record.getMode());
        assertEquals(List.of("x", "y"), record.getFields().stream().map(TableFieldSchema::getName).toList());
    }

    @Test
    void mapsBigQueryFieldsToDuckTypes() {
        TableFieldSchema nested = field("s", "RECORD", "REPEATED", List.of(
                field("x", "INTEGER", "NULLABLE", null), field("t", "TIMESTAMP", "NULLABLE", null)));
        assertEquals("STRUCT(\"x\" BIGINT, \"t\" TIMESTAMPTZ)[]", DuckTypes.duckType(nested));
        assertEquals("VARCHAR[]", DuckTypes.duckType(field("tags", "STRING", "REPEATED", null)));
    }

    @Test
    void decodesProjectedValuesIntoStoredRows() {
        TableFieldSchema ts = field("ts", "TIMESTAMP", "NULLABLE", null);
        assertEquals("1704164645.123456", DuckTypes.decode("1704164645123456", DuckTypes.Projection.EPOCH_MICROS,
                DuckTypes.parse("TIMESTAMPTZ"), ts));

        TableFieldSchema num = field("n", "NUMERIC", "NULLABLE", null);
        assertEquals("1.25", DuckTypes.decode("1.250000000", DuckTypes.Projection.VARCHAR,
                DuckTypes.parse("DECIMAL(38,9)"), num));

        TableFieldSchema dt = field("d", "DATETIME", "NULLABLE", null);
        assertEquals("2024-01-02T03:04:05", DuckTypes.decode("2024-01-02 03:04:05", DuckTypes.Projection.VARCHAR,
                DuckTypes.parse("TIMESTAMP"), dt));

        TableFieldSchema count = field("c", "INTEGER", "NULLABLE", null);
        assertEquals(3L, DuckTypes.decode("3", DuckTypes.Projection.VARCHAR, DuckTypes.parse("HUGEINT"), count));
        assertNull(DuckTypes.decode(null, DuckTypes.Projection.RAW, DuckTypes.parse("BIGINT"), count));
    }

    @Test
    void decodesNestedJsonWithTypedLeaves() {
        DuckTypes.DuckType type = DuckTypes.parse("STRUCT(id BIGINT, at TIMESTAMP WITH TIME ZONE, tags VARCHAR[])");
        TableFieldSchema field = DuckTypes.toField("s", type);
        Object decoded = DuckTypes.decode("{\"id\":7,\"at\":\"2024-01-02 03:04:05.5+00\",\"tags\":[\"a\"]}",
                DuckTypes.Projection.JSON, type, field);
        assertEquals(Map.of("id", 7L, "at", "1704164645.5", "tags", List.of("a")), decoded);
    }

    @Test
    void decodesTypedValuesFromFlociDuck() {
        assertEquals("1704164645.123456", DuckTypes.decodeTyped("2024-01-02T03:04:05.123456Z",
                DuckTypes.parse("TIMESTAMP WITH TIME ZONE"), field("t", "TIMESTAMP", "NULLABLE", null)));
        assertEquals("2024-01-02T03:04:05", DuckTypes.decodeTyped("2024-01-02T03:04:05",
                DuckTypes.parse("TIMESTAMP"), field("d", "DATETIME", "NULLABLE", null)));
        assertEquals("1.25", DuckTypes.decodeTyped("1.250000000", DuckTypes.parse("DECIMAL(38,9)"),
                field("n", "NUMERIC", "NULLABLE", null)));
        assertEquals(2L, DuckTypes.decodeTyped("2", DuckTypes.parse("HUGEINT"),
                field("c", "INTEGER", "NULLABLE", null)));
        assertTrue(Double.isNaN((Double) DuckTypes.decodeTyped("NaN", DuckTypes.parse("DOUBLE"),
                field("f", "FLOAT", "NULLABLE", null))));

        DuckTypes.DuckType type = DuckTypes.parse("STRUCT(\"k\" BIGINT, \"at\" TIMESTAMP WITH TIME ZONE)[]");
        TableFieldSchema repeated = DuckTypes.toField("r", type);
        assertEquals(List.of(Map.of("k", 1L, "at", "1704164645")), DuckTypes.decodeTyped(
                List.of(Map.of("k", 1, "at", "2024-01-02T03:04:05Z")), type, repeated));
    }

    @Test
    void timestampTextCarriesItsOffset() {
        assertEquals("1704164645", DuckTypes.timestampTextToSeconds("2024-01-02 03:04:05+00"));
        assertEquals("1704164645", DuckTypes.timestampTextToSeconds("2024-01-02T04:04:05+01:00"));
        assertEquals("1704164645", DuckTypes.timestampTextToSeconds("2024-01-02T03:04:05Z"));
    }

    @Test
    void timestampWireFormatsFollowFormatOptions() {
        assertEquals("1704164645.5", RowCodec.encodeTimestamp("2024-01-02T03:04:05.5Z", RowCodec.TimestampFormat.FLOAT64));
        assertEquals("1704164645500000", RowCodec.encodeTimestamp("1704164645.5", RowCodec.TimestampFormat.INT64));
        assertEquals("2024-01-02T03:04:05.500000Z",
                RowCodec.encodeTimestamp("1704164645.5", RowCodec.TimestampFormat.ISO8601_STRING));
        assertEquals("not a time", RowCodec.encodeTimestamp("not a time", RowCodec.TimestampFormat.FLOAT64));
        assertEquals(RowCodec.TimestampFormat.INT64, RowCodec.TimestampFormat.of(true, null));
        assertEquals(RowCodec.TimestampFormat.ISO8601_STRING, RowCodec.TimestampFormat.of(true, "ISO8601_STRING"));
    }
}
