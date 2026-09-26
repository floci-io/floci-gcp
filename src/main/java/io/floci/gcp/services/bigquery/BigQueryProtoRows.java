package io.floci.gcp.services.bigquery;

import com.google.cloud.bigquery.storage.v1.AnnotationsProto;
import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DurationProto;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.TimestampProto;
import com.google.protobuf.WrappersProto;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.bigquery.model.TableFieldSchema;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decodes Storage Write API {@code ProtoRows}. The writer schema is a self-contained
 * {@code DescriptorProto} decoded with proto2 semantics (an unset field is a missing value).
 * Proto fields bind to columns by name, case-insensitively, or by the {@code column_name} field
 * option the client libraries set for column names that are not valid proto identifiers.
 * Values follow the documented protocol buffer type mapping and come out in the JSON shape
 * {@code insertAll} accepts, so rows go through the same validation.
 */
final class BigQueryProtoRows {

    /** An extra proto field with no matching column: StorageError SCHEMA_MISMATCH_EXTRA_FIELDS. */
    static final class ExtraFieldsException extends RuntimeException {
        ExtraFieldsException(String message) {
            super(message);
        }
    }

    /** The writer schema bound to a table's columns. */
    record Schema(Descriptor descriptor, Map<FieldDescriptor, TableFieldSchema> columns) {}

    private static final String FILE_NAME = "floci_bigquery_append_rows.proto";
    private static final FileDescriptor[] WELL_KNOWN = {
            TimestampProto.getDescriptor(), WrappersProto.getDescriptor(), DurationProto.getDescriptor()};
    private static final Set<String> WRAPPERS = Set.of("google.protobuf.DoubleValue", "google.protobuf.FloatValue",
            "google.protobuf.Int64Value", "google.protobuf.UInt64Value", "google.protobuf.Int32Value",
            "google.protobuf.UInt32Value", "google.protobuf.BoolValue", "google.protobuf.StringValue",
            "google.protobuf.BytesValue");
    private static final String TIMESTAMP = "google.protobuf.Timestamp";
    private static final String DURATION = "google.protobuf.Duration";
    private static final ExtensionRegistry COLUMN_NAME = ExtensionRegistry.newInstance();
    private static final DateTimeFormatter DATE_PART = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'");
    private static final DateTimeFormatter TIME_SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final long MIN_DATE = -719162;
    private static final long MAX_DATE = 2932896;

    static {
        COLUMN_NAME.add(AnnotationsProto.columnName);
    }

    private BigQueryProtoRows() {}

    // ── Schema ────────────────────────────────────────────────────────────────

    /**
     * Builds the writer schema and checks it against the table: every proto field needs a
     * column, and each field type must be one BigQuery accepts for that column's type.
     */
    static Schema bind(DescriptorProto proto, List<TableFieldSchema> tableFields) {
        FileDescriptorProto.Builder file = FileDescriptorProto.newBuilder()
                .setName(FILE_NAME).setSyntax("proto2").addMessageType(proto);
        for (FileDescriptor dependency : WELL_KNOWN) {
            file.addDependency(dependency.getName());
        }
        Descriptor descriptor;
        try {
            descriptor = FileDescriptor.buildFrom(file.build(), WELL_KNOWN).findMessageTypeByName(proto.getName());
        } catch (DescriptorValidationException e) {
            throw GcpException.invalidArgument("Invalid proto schema: " + e.getMessage());
        }
        Map<FieldDescriptor, TableFieldSchema> columns = new LinkedHashMap<>();
        List<String> extra = new ArrayList<>();
        bindFields(descriptor, tableFields, "", columns, extra);
        if (!extra.isEmpty()) {
            throw new ExtraFieldsException("Input schema has more fields than BigQuery schema, extra fields: '"
                    + String.join(",", extra) + "'");
        }
        return new Schema(descriptor, columns);
    }

    private static void bindFields(Descriptor message, List<TableFieldSchema> fields, String path,
                                   Map<FieldDescriptor, TableFieldSchema> columns, List<String> extra) {
        for (FieldDescriptor field : message.getFields()) {
            String name = columnName(field);
            TableFieldSchema column = fields.stream().filter(f -> f.getName().equalsIgnoreCase(name))
                    .findFirst().orElse(null);
            if (column == null) {
                extra.add(path + name);
                continue;
            }
            String type = RowCodec.legacyType(column.getType());
            boolean repeated = "REPEATED".equals(column.getMode());
            if (field.isRepeated() != repeated || !accepts(type, field)) {
                throw GcpException.invalidArgument("The proto field mismatched with BigQuery field at "
                        + message.getName() + "." + field.getName() + ", the proto field type "
                        + protoTypeName(field) + ", BigQuery field type " + type);
            }
            columns.put(field, column);
            if ("RECORD".equals(type)) {
                bindFields(field.getMessageType(), column.getFields() != null ? column.getFields() : List.of(),
                        path + column.getName() + ".", columns, extra);
            }
        }
    }

    private static String columnName(FieldDescriptor field) {
        FieldOptions options = field.getOptions();
        if (!options.getUnknownFields().asMap().isEmpty()) {
            try {
                FieldOptions parsed = FieldOptions.parseFrom(options.toByteString(), COLUMN_NAME);
                if (parsed.hasExtension(AnnotationsProto.columnName)) {
                    return parsed.getExtension(AnnotationsProto.columnName);
                }
            } catch (InvalidProtocolBufferException ignored) {
                // no usable column_name option; the field name is the column name
            }
        }
        return field.getName();
    }

    /** The "Supported protocol buffer data types" table. */
    private static boolean accepts(String type, FieldDescriptor field) {
        String proto = protoTypeName(field);
        return switch (type) {
            case "BOOLEAN" -> Set.of("bool", "int32", "int64", "uint32", "uint64", "google.protobuf.BoolValue")
                    .contains(proto);
            case "BYTES" -> Set.of("bytes", "string", "google.protobuf.BytesValue").contains(proto);
            case "DATE" -> Set.of("int32", "int64", "string").contains(proto);
            case "DATETIME", "TIME" -> Set.of("string", "int64").contains(proto);
            case "FLOAT" -> Set.of("double", "float", "google.protobuf.DoubleValue", "google.protobuf.FloatValue")
                    .contains(proto);
            case "INTEGER" -> Set.of("int32", "int64", "uint32", "enum", "google.protobuf.Int32Value",
                    "google.protobuf.Int64Value", "google.protobuf.UInt32Value").contains(proto);
            case "NUMERIC", "BIGNUMERIC" -> Set.of("int32", "int64", "uint32", "uint64", "double", "float",
                    "string", "bytes", "google.protobuf.BytesValue").contains(proto);
            case "STRING" -> Set.of("string", "enum", "google.protobuf.StringValue").contains(proto);
            case "TIMESTAMP" -> Set.of("int64", "int32", "uint32", TIMESTAMP).contains(proto);
            case "INTERVAL" -> Set.of("string", DURATION).contains(proto);
            case "RECORD" -> field.getJavaType() == FieldDescriptor.JavaType.MESSAGE && !isWellKnown(field);
            default -> "string".equals(proto); // GEOGRAPHY, JSON
        };
    }

    private static boolean isWellKnown(FieldDescriptor field) {
        String name = field.getMessageType().getFullName();
        return WRAPPERS.contains(name) || TIMESTAMP.equals(name) || DURATION.equals(name);
    }

    private static String protoTypeName(FieldDescriptor field) {
        return switch (field.getType()) {
            case INT32, SINT32, SFIXED32 -> "int32";
            case INT64, SINT64, SFIXED64 -> "int64";
            case UINT32, FIXED32 -> "uint32";
            case UINT64, FIXED64 -> "uint64";
            case DOUBLE -> "double";
            case FLOAT -> "float";
            case BOOL -> "bool";
            case STRING -> "string";
            case BYTES -> "bytes";
            case ENUM -> "enum";
            case MESSAGE, GROUP -> isWellKnown(field) ? field.getMessageType().getFullName() : "message";
        };
    }

    // ── Rows ──────────────────────────────────────────────────────────────────

    /** Parses one serialized row into an {@code insertAll}-shaped JSON object. */
    static Map<String, Object> decode(Schema schema, ByteString serialized) {
        DynamicMessage message;
        try {
            message = DynamicMessage.parseFrom(schema.descriptor(), serialized);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("Could not parse the serialized row: " + e.getMessage());
        }
        return toJson(schema, message);
    }

    private static Map<String, Object> toJson(Schema schema, Message message) {
        Map<String, Object> json = new LinkedHashMap<>();
        for (FieldDescriptor field : message.getDescriptorForType().getFields()) {
            TableFieldSchema column = schema.columns().get(field);
            if (field.isRepeated()) {
                List<Object> values = new ArrayList<>();
                for (int i = 0; i < message.getRepeatedFieldCount(field); i++) {
                    values.add(value(schema, field, column, message.getRepeatedField(field, i)));
                }
                json.put(column.getName(), values);
            } else if (message.hasField(field)) {
                json.put(column.getName(), value(schema, field, column, message.getField(field)));
            }
        }
        return json;
    }

    private static Object value(Schema schema, FieldDescriptor field, TableFieldSchema column, Object raw) {
        String type = RowCodec.legacyType(column.getType());
        if (raw instanceof Message message) {
            String messageType = message.getDescriptorForType().getFullName();
            if ("RECORD".equals(type)) {
                return toJson(schema, message);
            }
            if (TIMESTAMP.equals(messageType)) {
                long seconds = (Long) message.getField(message.getDescriptorForType().findFieldByName("seconds"));
                int nanos = (Integer) message.getField(message.getDescriptorForType().findFieldByName("nanos"));
                return DuckTypes.microsToSeconds(Math.addExact(Math.multiplyExact(seconds, 1_000_000L), nanos / 1000));
            }
            if (DURATION.equals(messageType)) {
                long seconds = (Long) message.getField(message.getDescriptorForType().findFieldByName("seconds"));
                int nanos = (Integer) message.getField(message.getDescriptorForType().findFieldByName("nanos"));
                return interval(seconds, nanos);
            }
            raw = message.getField(message.getDescriptorForType().findFieldByName("value"));
        }
        if (raw instanceof EnumValueDescriptor enumValue) {
            return "INTEGER".equals(type) ? (Object) (long) enumValue.getNumber() : enumValue.getName();
        }
        boolean unsigned = field.getType() == FieldDescriptor.Type.UINT32 || field.getType() == FieldDescriptor.Type.FIXED32
                || field.getType() == FieldDescriptor.Type.UINT64 || field.getType() == FieldDescriptor.Type.FIXED64;
        if (unsigned && raw instanceof Integer i) {
            raw = Integer.toUnsignedLong(i);
        } else if (unsigned && raw instanceof Long l && l < 0) {
            raw = new BigDecimal(new BigInteger(Long.toUnsignedString(l)));
        }
        return switch (type) {
            case "BOOLEAN" -> raw instanceof Number n ? n.longValue() != 0 : raw;
            case "BYTES" -> Base64.getEncoder().encodeToString(raw instanceof ByteString bytes
                    ? bytes.toByteArray() : raw.toString().getBytes(StandardCharsets.UTF_8));
            case "DATE" -> raw instanceof Number n ? date(n.longValue()) : raw;
            case "DATETIME" -> {
                if (!(raw instanceof Number n)) {
                    yield raw;
                }
                LocalDateTime datetime = decodeDatetimeMicros(n.longValue());
                yield DATE_PART.format(datetime) + time(datetime.toLocalTime());
            }
            case "TIME" -> raw instanceof Number n ? time(decodeTimeMicros(n.longValue())) : raw;
            case "TIMESTAMP" -> DuckTypes.microsToSeconds(((Number) raw).longValue());
            case "NUMERIC" -> decimal(raw, 9);
            case "BIGNUMERIC" -> decimal(raw, 38);
            case "INTEGER", "FLOAT" -> raw;
            default -> raw.toString();
        };
    }

    /** Canonical civil time: six fractional digits, omitted when zero. */
    private static String time(LocalTime time) {
        String seconds = TIME_SECONDS.format(time);
        int micros = time.getNano() / 1000;
        return micros == 0 ? seconds : seconds + String.format(".%06d", micros);
    }

    private static String date(long days) {
        if (days < MIN_DATE || days > MAX_DATE) {
            throw new IllegalArgumentException("Invalid date value: " + days
                    + "; the valid range is " + MIN_DATE + " to " + MAX_DATE);
        }
        return LocalDate.ofEpochDay(days).toString();
    }

    /** NUMERIC and BIGNUMERIC bytes are little-endian two's-complement at scale 9 and 38. */
    private static Object decimal(Object raw, int scale) {
        if (raw instanceof ByteString bytes) {
            byte[] bigEndian = bytes.toByteArray();
            for (int i = 0, j = bigEndian.length - 1; i < j; i++, j--) {
                byte b = bigEndian[i];
                bigEndian[i] = bigEndian[j];
                bigEndian[j] = b;
            }
            if (bigEndian.length == 0) {
                return "0";
            }
            return plain(new BigDecimal(new BigInteger(bigEndian), scale));
        }
        if (raw instanceof Double || raw instanceof Float) {
            return plain(BigDecimal.valueOf(((Number) raw).doubleValue()));
        }
        try {
            return plain(new BigDecimal(raw.toString().trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid NUMERIC value: " + raw);
        }
    }

    private static String plain(BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static String interval(long seconds, int nanos) {
        BigDecimal total = BigDecimal.valueOf(seconds).add(BigDecimal.valueOf(nanos, 9));
        String sign = total.signum() < 0 ? "-" : "";
        total = total.abs();
        long whole = total.longValue();
        String fraction = total.subtract(BigDecimal.valueOf(whole)).setScale(6, RoundingMode.DOWN)
                .stripTrailingZeros().toPlainString();
        return "0-0 0 " + sign + (whole / 3600) + ":" + (whole / 60 % 60) + ":" + (whole % 60)
                + (fraction.equals("0") ? "" : fraction.substring(1));
    }

    // CivilTimeEncoder bit fields: micros in the low 20 bits, then seconds, minutes, hours,
    // and for DATETIME the day, month and year.

    static LocalTime decodeTimeMicros(long packed) {
        if ((packed & ~0x1FFFFFFFFFL) != 0) {
            throw new IllegalArgumentException("Invalid packed TIME value: " + packed);
        }
        return timeOfDay((int) (packed >> 20), (int) (packed & 0xFFFFF));
    }

    static LocalDateTime decodeDatetimeMicros(long packed) {
        if ((packed & ~0xFFFFFFFFFFFFFFFL) != 0) {
            throw new IllegalArgumentException("Invalid packed DATETIME value: " + packed);
        }
        long seconds = packed >> 20;
        try {
            return LocalDateTime.of(LocalDate.of((int) ((seconds & 0xFFFFC000000L) >> 26),
                            (int) ((seconds & 0x3C00000L) >> 22), (int) ((seconds & 0x3E0000L) >> 17)),
                    timeOfDay((int) (seconds & 0x1FFFFL), (int) (packed & 0xFFFFF)));
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Invalid packed DATETIME value: " + packed);
        }
    }

    private static LocalTime timeOfDay(int seconds, int micros) {
        try {
            return LocalTime.of((seconds & 0x1F000) >> 12, (seconds & 0xFC0) >> 6, seconds & 0x3F, micros * 1000);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Invalid packed time of day: " + e.getMessage());
        }
    }
}
