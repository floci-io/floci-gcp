package io.floci.gcp.services.bigquery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.services.bigquery.model.TableFieldSchema;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BigQueryAvroTest {

    private static TableFieldSchema field(String name, String type, String mode, TableFieldSchema... children) {
        TableFieldSchema f = new TableFieldSchema();
        f.setName(name);
        f.setType(type);
        f.setMode(mode);
        if (children.length > 0) {
            f.setFields(List.of(children));
        }
        return f;
    }

    @Test
    void schemaFollowsTheDocumentedTypeMapping() throws Exception {
        JsonNode schema = new ObjectMapper().readTree(BigQueryAvro.schema(List.of(
                field("id", "INTEGER", "REQUIRED"),
                field("amount", "NUMERIC", "NULLABLE"),
                field("at", "TIMESTAMP", "NULLABLE"),
                field("local", "DATETIME", "NULLABLE"),
                field("tags", "STRING", "REPEATED"),
                field("place", "RECORD", "NULLABLE", field("city", "STRING", "NULLABLE")))));
        assertEquals("record", schema.get("type").asText());
        JsonNode fields = schema.get("fields");
        assertEquals("long", fields.get(0).get("type").asText());
        assertEquals("null", fields.get(1).get("type").get(0).asText());
        assertEquals("decimal", fields.get(1).get("type").get(1).get("logicalType").asText());
        assertEquals(9, fields.get(1).get("type").get(1).get("scale").asInt());
        assertEquals("timestamp-micros", fields.get(2).get("type").get(1).get("logicalType").asText());
        assertEquals("datetime", fields.get(3).get("type").get(1).get("logicalType").asText());
        assertEquals("array", fields.get(4).get("type").get("type").asText());
        assertEquals("record", fields.get(5).get("type").get(1).get("type").asText());
    }

    @Test
    void rowsAreAvroBinaryEncoded() {
        List<TableFieldSchema> fields = List.of(
                field("id", "INTEGER", "REQUIRED"),
                field("name", "STRING", "NULLABLE"),
                field("amount", "NUMERIC", "NULLABLE"),
                field("tags", "STRING", "REPEATED"),
                field("place", "RECORD", "NULLABLE", field("city", "STRING", "NULLABLE")));
        Map<String, Object> row = new HashMap<>();
        row.put("id", 1L);
        row.put("name", "a");
        row.put("amount", "1.25");
        row.put("tags", List.of("x"));
        row.put("place", null);
        byte[] expected = {
                0x02,                               // id = 1 (zig-zag)
                0x02, 0x02, 'a',                    // union branch 1, length 1, "a"
                0x02, 0x08, 0x4A, (byte) 0x81, 0x7C, (byte) 0x80, // branch 1, 4 bytes, 1250000000 unscaled
                0x02, 0x02, 'x', 0x00,              // array block of 1 item "x", end of array
                0x00                                // place is null
        };
        assertArrayEquals(expected, BigQueryAvro.encode(fields, List.of(row)), Arrays.toString(
                BigQueryAvro.encode(fields, List.of(row))));
    }

    @Test
    void temporalValuesUseTheirLogicalEncodings() {
        List<TableFieldSchema> fields = List.of(field("d", "DATE", "REQUIRED"), field("ts", "TIMESTAMP", "REQUIRED"));
        byte[] encoded = BigQueryAvro.encode(fields, List.of(Map.of("d", "1970-01-02", "ts", "0.000001")));
        assertArrayEquals(new byte[] {0x02, 0x02}, encoded); // day 1, 1 microsecond
    }
}
