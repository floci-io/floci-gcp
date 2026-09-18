package io.floci.gcp.services.bigquery;

import com.google.protobuf.ByteString;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.bigquery.model.TableFieldSchema;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Decodes Arrow IPC messages written by pyarrow (see {@code bigquery/arrow/generate.py}). */
class BigQueryArrowRowsTest {

    private static final List<TableFieldSchema> TABLE = List.of(
            column("name", "STRING", "REQUIRED"), column("age", "INT64", null), column("ts", "TIMESTAMP", null),
            column("d", "DATE", null), column("dt", "DATETIME", null), column("n", "NUMERIC", null),
            column("big", "BIGNUMERIC", null), column("tags", "STRING", "REPEATED"),
            column("addr", "RECORD", null, column("city", "STRING", null)), column("f", "FLOAT64", null),
            column("ok", "BOOL", null), column("raw", "BYTES", null), column("t", "TIME", null),
            column("iv", "INTERVAL", null), column("small", "INTEGER", null));

    private static TableFieldSchema column(String name, String type, String mode, TableFieldSchema... children) {
        TableFieldSchema field = new TableFieldSchema();
        field.setName(name);
        field.setType(type);
        field.setMode(mode);
        if (children.length > 0) {
            field.setFields(List.of(children));
        }
        return field;
    }

    private static ByteString fixture(String name) {
        try (InputStream in = BigQueryArrowRowsTest.class.getResourceAsStream("/bigquery/arrow/" + name)) {
            return ByteString.readFrom(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    @Test
    void decodesEveryDocumentedType() {
        BigQueryArrowRows.Batch batch = BigQueryArrowRows.decode(
                BigQueryArrowRows.bind(fixture("schema.bin"), TABLE), fixture("batch.bin"));
        assertEquals(3, batch.size());
        assertEquals(row("name", "ada", "age", 36L, "ts", "1704164645.123456", "d", "2024-01-01",
                "dt", "2024-01-02T03:04:05.000006", "n", "12.5", "big", "1.25", "tags", List.of("a", "b"),
                "addr", Map.of("city", "London"), "f", 1.5, "ok", true, "raw", "AQI=", "t", "01:02:03.500000",
                "iv", "1-2 3 1:2:3.5", "small", 255L), batch.row(0));
        // Nulls are missing values; a null list is an empty array.
        assertEquals(row("name", "bob", "tags", List.of()), batch.row(1));
        assertEquals(row("name", "cid", "age", -7L, "ts", "-0.000001", "d", "0001-01-01", "dt", "2024-01-02T03:04:05",
                "n", "-0.000000001", "big", "-3", "tags", List.of(), "addr", Map.of(), "f", -2.25, "ok", false,
                "raw", "", "t", "00:00:00", "iv", "-0-1 0 0:0:0", "small", 0L), batch.row(2));
    }

    @Test
    void schemaMismatchesAreRejected() {
        BigQueryProtoRows.ExtraFieldsException extra = assertThrows(BigQueryProtoRows.ExtraFieldsException.class,
                () -> BigQueryArrowRows.bind(fixture("schema.bin"), TABLE.subList(0, 14)));
        assertTrue(extra.getMessage().contains("'small'"), extra.getMessage());

        GcpException nanos = assertThrows(GcpException.class,
                () -> BigQueryArrowRows.bind(fixture("schema-nanos.bin"), List.of(column("ts", "TIMESTAMP", null))));
        assertTrue(nanos.getMessage().contains("nanosecond timestamps"), nanos.getMessage());
        assertTrue(nanos.getMessage().contains("microsecond precision"), nanos.getMessage());

        GcpException repeated = assertThrows(GcpException.class,
                () -> BigQueryArrowRows.bind(fixture("schema-name.bin"), List.of(column("name", "STRING", "REPEATED"))));
        assertTrue(repeated.getMessage().contains("STRING REPEATED"), repeated.getMessage());
    }

    @Test
    void compressedBatchesAndNonBatchMessagesAreRejected() {
        BigQueryArrowRows.BoundSchema schema = BigQueryArrowRows.bind(fixture("schema-name.bin"),
                List.of(column("name", "STRING", null)));
        assertTrue(assertThrows(GcpException.class, () -> BigQueryArrowRows.decode(schema, fixture("batch-lz4.bin")))
                .getMessage().contains("Compressed"));
        assertTrue(assertThrows(GcpException.class, () -> BigQueryArrowRows.decode(schema, fixture("schema.bin")))
                .getMessage().contains("record batch"));
        assertTrue(assertThrows(GcpException.class,
                () -> BigQueryArrowRows.bind(ByteString.copyFromUtf8("nope"), List.of())).getMessage()
                .contains("Malformed"));
    }
}
