package io.floci.gcp.services.bigquery;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Duration;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Int64Value;
import com.google.protobuf.Timestamp;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.bigquery.model.TableFieldSchema;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BigQueryProtoRowsTest {

    private static TableFieldSchema column(String name, String type, TableFieldSchema... children) {
        TableFieldSchema field = new TableFieldSchema();
        field.setName(name);
        field.setType(type);
        if (children.length > 0) {
            field.setFields(List.of(children));
        }
        return field;
    }

    private static FieldDescriptorProto.Builder field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }

    /** CivilTimeEncoder.encodePacked64TimeMicros. */
    private static long packedTime(int hour, int minute, int second, int micros) {
        return (((long) hour << 12 | (long) minute << 6 | second) << 20) | micros;
    }

    @Test
    void civilTimeDecodingMatchesTheEncoderLayout() {
        assertEquals(LocalTime.of(23, 59, 58, 123_456_000), BigQueryProtoRows.decodeTimeMicros(
                packedTime(23, 59, 58, 123_456)));
        long datetime = ((2024L << 26 | 2L << 22 | 29L << 17 | 1L << 12 | 2L << 6 | 3L) << 20) | 4;
        assertEquals(LocalDateTime.of(2024, 2, 29, 1, 2, 3, 4000), BigQueryProtoRows.decodeDatetimeMicros(datetime));
        assertThrows(IllegalArgumentException.class, () -> BigQueryProtoRows.decodeTimeMicros(packedTime(24, 0, 0, 0)));
    }

    @Test
    void wellKnownTypesEnumsAndUnsignedValues() {
        DescriptorProto proto = DescriptorProto.newBuilder().setName("Row")
                .addField(field("ts", 1, FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".google.protobuf.Timestamp"))
                .addField(field("n", 2, FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".google.protobuf.Int64Value"))
                .addField(field("color", 3, FieldDescriptorProto.Type.TYPE_ENUM).setTypeName("Color"))
                .addField(field("code", 4, FieldDescriptorProto.Type.TYPE_ENUM).setTypeName("Color"))
                .addField(field("big", 5, FieldDescriptorProto.Type.TYPE_UINT32))
                .addField(field("t", 6, FieldDescriptorProto.Type.TYPE_INT64))
                .addField(field("flag", 7, FieldDescriptorProto.Type.TYPE_INT32))
                .addEnumType(EnumDescriptorProto.newBuilder().setName("Color")
                        .addValue(EnumValueDescriptorProto.newBuilder().setName("RED").setNumber(0))
                        .addValue(EnumValueDescriptorProto.newBuilder().setName("BLUE").setNumber(7)))
                .build();
        BigQueryProtoRows.Schema schema = BigQueryProtoRows.bind(proto, List.of(
                column("ts", "TIMESTAMP"), column("n", "INTEGER"), column("color", "STRING"),
                column("code", "INT64"), column("big", "INTEGER"), column("t", "TIME"), column("flag", "BOOL")));
        Descriptor descriptor = schema.descriptor();
        DynamicMessage row = DynamicMessage.newBuilder(descriptor)
                .setField(descriptor.findFieldByName("ts"), Timestamp.newBuilder().setSeconds(10).setNanos(500_000).build())
                .setField(descriptor.findFieldByName("n"), Int64Value.of(-4))
                .setField(descriptor.findFieldByName("color"), descriptor.findEnumTypeByName("Color").findValueByNumber(7))
                .setField(descriptor.findFieldByName("code"), descriptor.findEnumTypeByName("Color").findValueByNumber(7))
                .setField(descriptor.findFieldByName("big"), -1)
                .setField(descriptor.findFieldByName("t"), packedTime(1, 2, 3, 0))
                .setField(descriptor.findFieldByName("flag"), 2)
                .build();
        Map<String, Object> json = BigQueryProtoRows.decode(schema, row.toByteString());
        assertEquals("10.0005", json.get("ts"));
        assertEquals(-4L, json.get("n"));
        assertEquals("BLUE", json.get("color"));
        assertEquals(7L, json.get("code"));
        assertEquals(4_294_967_295L, json.get("big"));
        assertEquals("01:02:03", json.get("t"));
        assertEquals(true, json.get("flag"));
    }

    @Test
    void schemaMismatchesAreRejected() {
        DescriptorProto nested = DescriptorProto.newBuilder().setName("Row")
                .addField(field("addr", 1, FieldDescriptorProto.Type.TYPE_MESSAGE).setTypeName("Addr"))
                .addNestedType(DescriptorProto.newBuilder().setName("Addr")
                        .addField(field("city", 1, FieldDescriptorProto.Type.TYPE_STRING))
                        .addField(field("zip", 2, FieldDescriptorProto.Type.TYPE_STRING)))
                .build();
        BigQueryProtoRows.ExtraFieldsException extra = assertThrows(BigQueryProtoRows.ExtraFieldsException.class,
                () -> BigQueryProtoRows.bind(nested, List.of(column("addr", "RECORD", column("city", "STRING")))));
        assertTrue(extra.getMessage().contains("'addr.zip'"), extra.getMessage());

        DescriptorProto uint64 = DescriptorProto.newBuilder().setName("Row")
                .addField(field("n", 1, FieldDescriptorProto.Type.TYPE_UINT64)).build();
        GcpException mismatch = assertThrows(GcpException.class,
                () -> BigQueryProtoRows.bind(uint64, List.of(column("n", "INTEGER"))));
        assertTrue(mismatch.getMessage().contains("proto field type uint64, BigQuery field type INTEGER"),
                mismatch.getMessage());
    }

    @Test
    void durationsKeepBigQuerysFullIntervalRange() {
        DescriptorProto proto = DescriptorProto.newBuilder().setName("Row")
                .addField(field("iv", 1, FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".google.protobuf.Duration"))
                .build();
        BigQueryProtoRows.Schema schema = BigQueryProtoRows.bind(proto, List.of(column("iv", "INTERVAL")));
        Descriptor descriptor = schema.descriptor();
        // 87,840,000 hours is BigQuery's largest time part; in nanoseconds it overflows a long.
        long maxSeconds = 87_840_000L * 3600;
        for (Duration duration : List.of(Duration.newBuilder().setSeconds(maxSeconds).setNanos(500_000_000).build(),
                Duration.newBuilder().setSeconds(-maxSeconds).setNanos(-500_000_000).build())) {
            DynamicMessage row = DynamicMessage.newBuilder(descriptor)
                    .setField(descriptor.findFieldByName("iv"), duration).build();
            String sign = duration.getSeconds() < 0 ? "-" : "";
            assertEquals("0-0 0 " + sign + "87840000:0:0.5",
                    BigQueryProtoRows.decode(schema, row.toByteString()).get("iv"));
        }
    }
}
