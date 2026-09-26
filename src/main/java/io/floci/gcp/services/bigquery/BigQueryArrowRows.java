package io.floci.gcp.services.bigquery;

import com.google.protobuf.ByteString;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.bigquery.model.TableFieldSchema;
import org.apache.arrow.flatbuf.Buffer;
import org.apache.arrow.flatbuf.Date;
import org.apache.arrow.flatbuf.DateUnit;
import org.apache.arrow.flatbuf.Decimal;
import org.apache.arrow.flatbuf.Field;
import org.apache.arrow.flatbuf.FieldNode;
import org.apache.arrow.flatbuf.FloatingPoint;
import org.apache.arrow.flatbuf.Int;
import org.apache.arrow.flatbuf.Interval;
import org.apache.arrow.flatbuf.IntervalUnit;
import org.apache.arrow.flatbuf.Message;
import org.apache.arrow.flatbuf.MessageHeader;
import org.apache.arrow.flatbuf.Precision;
import org.apache.arrow.flatbuf.RecordBatch;
import org.apache.arrow.flatbuf.Schema;
import org.apache.arrow.flatbuf.Time;
import org.apache.arrow.flatbuf.TimeUnit;
import org.apache.arrow.flatbuf.Timestamp;
import org.apache.arrow.flatbuf.Type;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decodes Storage Write API {@code arrow_rows}: an Arrow IPC schema message and record batch
 * messages, as Arrow's {@code MessageSerializer} and pyarrow's {@code serialize()} write them.
 * Only the flatbuffer metadata comes from {@code arrow-format}; buffers are read directly, so no
 * Arrow memory runtime is involved. Fields bind to columns by name, case-insensitively, with the
 * documented Arrow type for each column type, and values come out in the JSON shape
 * {@code insertAll} accepts, like {@link BigQueryProtoRows}.
 */
final class BigQueryArrowRows {

    /** One Arrow field bound to its column. */
    record ArrowField(String name, byte type, int bitWidth, boolean signed, short unit, int scale,
                      TableFieldSchema column, List<ArrowField> children) {}

    /** The writer schema bound to a table's columns. */
    record BoundSchema(List<ArrowField> fields) {}

    /** A decoded record batch: {@link #row} decodes one row, throwing for an invalid value. */
    static final class Batch {
        private final List<ArrowField> fields;
        private final List<Column> columns;
        private final int length;

        private Batch(List<ArrowField> fields, List<Column> columns, int length) {
            this.fields = fields;
            this.columns = columns;
            this.length = length;
        }

        int size() {
            return length;
        }

        Map<String, Object> row(int index) {
            Map<String, Object> json = new LinkedHashMap<>();
            for (int i = 0; i < fields.size(); i++) {
                Object value = columns.get(i).value(index);
                if (value != null) {
                    json.put(fields.get(i).column().getName(), value);
                }
            }
            return json;
        }
    }

    /** Column types that accept Arrow Utf8 values. */
    private static final Set<String> UTF8_TYPES = Set.of("STRING", "JSON", "GEOGRAPHY", "DATE", "TIME", "INTERVAL");
    private static final DateTimeFormatter DATE_PART = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'");

    private BigQueryArrowRows() {}

    // ── Schema ────────────────────────────────────────────────────────────────

    static BoundSchema bind(ByteString serializedSchema, List<TableFieldSchema> tableFields) {
        try {
            return bindSchema(serializedSchema, tableFields);
        } catch (GcpException | BigQueryProtoRows.ExtraFieldsException e) {
            throw e;
        } catch (RuntimeException e) {
            // Flatbuffer offsets are read straight out of the client's bytes, so a crafted or
            // truncated schema can fail in any number of ways. None of them is an internal error.
            throw GcpException.invalidArgument("Malformed Arrow schema: " + e);
        }
    }

    private static BoundSchema bindSchema(ByteString serializedSchema, List<TableFieldSchema> tableFields) {
        Message message = message(serializedSchema).message();
        if (message.headerType() != MessageHeader.Schema) {
            throw GcpException.invalidArgument("arrow_rows.writer_schema is not an Arrow IPC schema message");
        }
        Schema schema = (Schema) message.header(new Schema());
        if (schema == null) {
            throw GcpException.invalidArgument("arrow_rows.writer_schema carries no Arrow IPC schema header");
        }
        List<Field> fields = new ArrayList<>();
        for (int i = 0; i < schema.fieldsLength(); i++) {
            fields.add(schema.fields(i));
        }
        List<String> extra = new ArrayList<>();
        List<ArrowField> bound = bindFields(fields, tableFields, "", extra);
        if (!extra.isEmpty()) {
            throw new BigQueryProtoRows.ExtraFieldsException(
                    "Input schema has more fields than BigQuery schema, extra fields: '"
                            + String.join(",", extra) + "'");
        }
        return new BoundSchema(bound);
    }

    private static List<ArrowField> bindFields(List<Field> fields, List<TableFieldSchema> columns, String path,
                                               List<String> extra) {
        List<ArrowField> bound = new ArrayList<>();
        for (Field field : fields) {
            TableFieldSchema column = columns.stream().filter(c -> c.getName().equalsIgnoreCase(field.name()))
                    .findFirst().orElse(null);
            if (column == null) {
                extra.add(path + field.name());
                continue;
            }
            boolean repeated = "REPEATED".equals(column.getMode());
            if (repeated != (field.typeType() == Type.List)) {
                throw mismatch(field, column);
            }
            if (repeated) {
                Field element = field.children(0);
                ArrowField item = bindValue(element, column, path, extra);
                bound.add(new ArrowField(field.name(), Type.List, 0, false, (short) 0, 0, column, List.of(item)));
            } else {
                bound.add(bindValue(field, column, path, extra));
            }
        }
        return bound;
    }

    /** Checks one value type against the "Supported Apache Arrow data types" table. */
    private static ArrowField bindValue(Field field, TableFieldSchema column, String path, List<String> extra) {
        if (field.dictionary() != null) {
            throw GcpException.invalidArgument("Dictionary-encoded Arrow field " + field.name()
                    + " is not supported by the floci BigQuery emulator");
        }
        String type = RowCodec.legacyType(column.getType());
        byte arrowType = field.typeType();
        int bitWidth = 0;
        boolean signed = true;
        short unit = 0;
        int scale = 0;
        boolean ok;
        switch (arrowType) {
            case Type.Int -> {
                Int t = (Int) field.type(new Int());
                bitWidth = t.bitWidth();
                signed = t.isSigned();
                ok = "INTEGER".equals(type) || ("DATE".equals(type) && bitWidth == 32 && signed);
            }
            case Type.FloatingPoint -> {
                unit = ((FloatingPoint) field.type(new FloatingPoint())).precision();
                ok = "FLOAT".equals(type) && unit != Precision.HALF;
            }
            case Type.Bool -> ok = "BOOLEAN".equals(type);
            case Type.Binary -> ok = "BYTES".equals(type);
            case Type.Utf8 -> ok = UTF8_TYPES.contains(type);
            case Type.Date -> {
                unit = ((Date) field.type(new Date())).unit();
                ok = "DATE".equals(type) && unit == DateUnit.DAY;
            }
            case Type.Timestamp -> {
                Timestamp t = (Timestamp) field.type(new Timestamp());
                unit = t.unit();
                boolean zoned = t.timezone() != null && !t.timezone().isEmpty();
                if (unit != TimeUnit.MICROSECOND && ("TIMESTAMP".equals(type) || "DATETIME".equals(type))) {
                    throw GcpException.invalidArgument("Arrow field " + path + field.name() + " uses "
                            + timeUnitName(unit) + " timestamps; BigQuery " + type + " is microsecond"
                            + " precision, so the floci BigQuery emulator cannot accept it");
                }
                ok = (("TIMESTAMP".equals(type) && zoned) || ("DATETIME".equals(type) && !zoned));
            }
            case Type.Time -> {
                Time t = (Time) field.type(new Time());
                unit = t.unit();
                bitWidth = t.bitWidth();
                ok = "TIME".equals(type) && unit == TimeUnit.MICROSECOND && bitWidth == 64;
            }
            case Type.Decimal -> {
                Decimal t = (Decimal) field.type(new Decimal());
                bitWidth = t.bitWidth();
                scale = t.scale();
                ok = ("NUMERIC".equals(type) && bitWidth == 128) || ("BIGNUMERIC".equals(type) && bitWidth == 256);
            }
            case Type.Interval -> {
                unit = ((Interval) field.type(new Interval())).unit();
                ok = "INTERVAL".equals(type);
            }
            case Type.Struct_ -> {
                ok = "RECORD".equals(type);
                if (ok) {
                    List<Field> children = new ArrayList<>();
                    for (int i = 0; i < field.childrenLength(); i++) {
                        children.add(field.children(i));
                    }
                    List<ArrowField> bound = bindFields(children,
                            column.getFields() != null ? column.getFields() : List.of(),
                            path + column.getName() + ".", extra);
                    return new ArrowField(field.name(), arrowType, 0, false, (short) 0, 0, column, bound);
                }
            }
            default -> ok = false;
        }
        if (!ok) {
            throw mismatch(field, column);
        }
        return new ArrowField(field.name(), arrowType, bitWidth, signed, unit, scale, column, List.of());
    }

    private static String timeUnitName(short unit) {
        return switch (unit) {
            case TimeUnit.SECOND -> "second";
            case TimeUnit.MILLISECOND -> "millisecond";
            case TimeUnit.MICROSECOND -> "microsecond";
            case TimeUnit.NANOSECOND -> "nanosecond";
            default -> "unit " + unit;
        };
    }

    private static GcpException mismatch(Field field, TableFieldSchema column) {
        String arrowType = field.typeType() >= 0 && field.typeType() < Type.names.length
                ? Type.names[field.typeType()] : String.valueOf(field.typeType());
        return GcpException.invalidArgument("The Arrow field mismatched with BigQuery field at " + field.name()
                + ", the Arrow field type " + arrowType + ", BigQuery field type "
                + RowCodec.legacyType(column.getType()) + ("REPEATED".equals(column.getMode()) ? " REPEATED" : ""));
    }

    // ── Record batches ────────────────────────────────────────────────────────

    static Batch decode(BoundSchema schema, ByteString serializedBatch) {
        try {
            Encapsulated encapsulated = message(serializedBatch);
            Message message = encapsulated.message();
            if (message.headerType() != MessageHeader.RecordBatch) {
                throw GcpException.invalidArgument("arrow_rows.rows is not an Arrow IPC record batch message");
            }
            RecordBatch batch = (RecordBatch) message.header(new RecordBatch());
            if (batch == null) {
                throw GcpException.invalidArgument("arrow_rows.rows carries no Arrow IPC record batch header");
            }
            if (batch.compression() != null) {
                throw GcpException.invalidArgument(
                        "Compressed Arrow record batches are not supported by the floci BigQuery emulator");
            }
            // Every value takes at least one bit of some buffer, so a length the body cannot hold
            // is a lie; checking it first keeps a tiny request from claiming billions of rows.
            if (batch.length() < 0 || batch.length() > 8L * encapsulated.body().capacity()) {
                throw malformed("its length " + batch.length() + " exceeds what its "
                        + encapsulated.body().capacity() + "-byte body can hold");
            }
            int length = (int) batch.length();
            Reader reader = new Reader(batch, encapsulated.body());
            List<Column> columns = new ArrayList<>();
            for (ArrowField field : schema.fields()) {
                columns.add(reader.read(field, length, true));
            }
            return new Batch(schema.fields(), columns, length);
        } catch (RuntimeException e) {
            if (e instanceof GcpException) {
                throw e;
            }
            throw GcpException.invalidArgument("Malformed Arrow record batch: " + e);
        }
    }

    private record Encapsulated(Message message, ByteBuffer body) {}

    /** An encapsulated IPC message: [0xFFFFFFFF] int32 length, flatbuffer Message, body. */
    private static Encapsulated message(ByteString serialized) {
        try {
            ByteBuffer buffer = serialized.asReadOnlyByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            int length = buffer.getInt();
            if (length == -1) {
                length = buffer.getInt();
            }
            int start = buffer.position();
            ByteBuffer metadata = buffer.slice(start, length).order(ByteOrder.LITTLE_ENDIAN);
            Message message = Message.getRootAsMessage(metadata);
            ByteBuffer body = buffer.slice(start + length, Math.toIntExact(message.bodyLength()))
                    .order(ByteOrder.LITTLE_ENDIAN);
            return new Encapsulated(message, body);
        } catch (RuntimeException e) {
            throw GcpException.invalidArgument("Malformed Arrow IPC message: " + e.getMessage());
        }
    }

    private static GcpException malformed(String reason) {
        return GcpException.invalidArgument("Malformed Arrow record batch: " + reason);
    }

    /**
     * Walks the batch's field nodes and buffers in schema order (depth first), checking each
     * node's length and each buffer's size against it, so reading a value can never run past a
     * buffer or allocate more than the request carried.
     */
    private static final class Reader {
        private final RecordBatch batch;
        private final ByteBuffer body;
        private int node;
        private int buffer;

        Reader(RecordBatch batch, ByteBuffer body) {
            this.batch = batch;
            this.body = body;
        }

        /** Reads a node of exactly {@code length} values, or, for a list's items, at least that many. */
        Column read(ArrowField field, int length, boolean exact) {
            FieldNode fieldNode = batch.nodes(node++);
            String name = field.column().getName();
            if (exact ? fieldNode.length() != length : fieldNode.length() < length) {
                throw malformed("field " + name + " has " + fieldNode.length() + " values, expected "
                        + (exact ? "" : "at least ") + length);
            }
            if (fieldNode.length() > 8L * body.capacity()) {
                throw malformed("field " + name + " claims " + fieldNode.length() + " values, more than its "
                        + body.capacity() + "-byte body can hold");
            }
            int count = (int) fieldNode.length();
            ByteBuffer validity = nextBuffer();
            if (fieldNode.nullCount() > 0) {
                requireBytes(validity, (count + 7L) / 8, name, "validity");
            }
            ByteBuffer offsets = null;
            ByteBuffer data = null;
            List<Column> children = new ArrayList<>();
            switch (field.type()) {
                case Type.Utf8, Type.Binary -> {
                    offsets = nextBuffer();
                    data = nextBuffer();
                    requireOffsets(offsets, count, data.capacity(), name);
                }
                case Type.List -> {
                    offsets = nextBuffer();
                    int items = requireOffsets(offsets, count, Integer.MAX_VALUE, name);
                    children.add(read(field.children().get(0), items, false));
                }
                case Type.Struct_ -> {
                    for (ArrowField child : field.children()) {
                        children.add(read(child, count, true));
                    }
                }
                default -> {
                    data = nextBuffer();
                    requireBytes(data, dataBytes(field, count), name, "data");
                }
            }
            return new Column(field, fieldNode.nullCount() > 0 ? validity : null, offsets, data, children);
        }

        /** Bytes a fixed-width buffer needs for {@code count} values, as {@link Column#value} reads them. */
        private static long dataBytes(ArrowField field, long count) {
            return switch (field.type()) {
                case Type.Bool -> (count + 7) / 8;
                case Type.Int -> count * switch (field.bitWidth()) {
                    case 8 -> 1;
                    case 16 -> 2;
                    case 32 -> 4;
                    default -> 8;
                };
                case Type.FloatingPoint -> count * (field.unit() == Precision.SINGLE ? 4 : 8);
                case Type.Date -> count * 4;
                case Type.Decimal -> count * (field.bitWidth() / 8);
                case Type.Interval -> count * switch (field.unit()) {
                    case IntervalUnit.YEAR_MONTH -> 4;
                    case IntervalUnit.DAY_TIME -> 8;
                    default -> 16;
                };
                default -> count * 8;
            };
        }

        private static void requireBytes(ByteBuffer buffer, long bytes, String name, String kind) {
            if (buffer.capacity() < bytes) {
                throw malformed("field " + name + " has a " + buffer.capacity() + "-byte " + kind
                        + " buffer; its values need " + bytes);
            }
        }

        /** Checks {@code count + 1} offsets are non-decreasing and within {@code limit}; returns the last. */
        private static int requireOffsets(ByteBuffer offsets, int count, int limit, String name) {
            requireBytes(offsets, (count + 1L) * 4, name, "offsets");
            int previous = offsets.getInt(0);
            if (previous < 0) {
                throw malformed("field " + name + " has a negative offset");
            }
            for (int i = 1; i <= count; i++) {
                int next = offsets.getInt(i * 4);
                if (next < previous) {
                    throw malformed("field " + name + " has decreasing offsets");
                }
                previous = next;
            }
            if (previous > limit) {
                throw malformed("field " + name + " has offsets past the end of its values");
            }
            return previous;
        }

        private ByteBuffer nextBuffer() {
            Buffer spec = batch.buffers(buffer++);
            return body.slice(Math.toIntExact(spec.offset()), Math.toIntExact(spec.length()))
                    .order(ByteOrder.LITTLE_ENDIAN);
        }
    }

    /** One decoded array; values are produced lazily per row. */
    private record Column(ArrowField field, ByteBuffer validity, ByteBuffer offsets, ByteBuffer data,
                          List<Column> children) {

        Object value(int i) {
            if (validity != null && validity.capacity() > 0 && (validity.get(i >> 3) & (1 << (i & 7))) == 0) {
                return field.type() == Type.List ? List.of() : null;
            }
            String type = RowCodec.legacyType(field.column().getType());
            return switch (field.type()) {
                case Type.Int -> integer(i, type);
                case Type.FloatingPoint -> field.unit() == Precision.SINGLE
                        ? (Object) (double) data.getFloat(i * 4) : data.getDouble(i * 8);
                case Type.Bool -> (data.get(i >> 3) & (1 << (i & 7))) != 0;
                case Type.Utf8 -> new String(bytes(i), StandardCharsets.UTF_8);
                case Type.Binary -> Base64.getEncoder().encodeToString(bytes(i));
                case Type.Date -> BigQueryProtoRows.date(data.getInt(i * 4));
                case Type.Timestamp -> timestamp(data.getLong(i * 8), type);
                case Type.Time -> BigQueryProtoRows.time(LocalTime.ofNanoOfDay(Math.multiplyExact(data.getLong(i * 8), 1000L)));
                case Type.Decimal -> decimal(i);
                case Type.Interval -> interval(i);
                case Type.List -> list(i);
                case Type.Struct_ -> struct(i);
                default -> throw new IllegalArgumentException("Unsupported Arrow type " + field.type());
            };
        }

        private Object integer(int i, String type) {
            long value = switch (field.bitWidth()) {
                case 8 -> field.signed() ? data.get(i) : Byte.toUnsignedLong(data.get(i));
                case 16 -> field.signed() ? data.getShort(i * 2) : Short.toUnsignedLong(data.getShort(i * 2));
                case 32 -> field.signed() ? data.getInt(i * 4) : Integer.toUnsignedLong(data.getInt(i * 4));
                default -> data.getLong(i * 8);
            };
            if (field.bitWidth() == 64 && !field.signed() && value < 0) {
                return new BigDecimal(new BigInteger(Long.toUnsignedString(value)));
            }
            return "DATE".equals(type) ? BigQueryProtoRows.date(value) : (Object) value;
        }

        private byte[] bytes(int i) {
            int start = offsets.getInt(i * 4);
            int end = offsets.getInt((i + 1) * 4);
            byte[] bytes = new byte[end - start];
            data.get(start, bytes);
            return bytes;
        }

        private static String timestamp(long micros, String type) {
            if ("TIMESTAMP".equals(type)) {
                return DuckTypes.microsToSeconds(micros);
            }
            LocalDateTime datetime = LocalDateTime.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                    (int) Math.floorMod(micros, 1_000_000L) * 1000, ZoneOffset.UTC);
            return DATE_PART.format(datetime) + BigQueryProtoRows.time(datetime.toLocalTime());
        }

        /** Decimal128/256: little-endian two's-complement unscaled value at the field's scale. */
        private String decimal(int i) {
            int width = field.bitWidth() / 8;
            byte[] bigEndian = new byte[width];
            for (int b = 0; b < width; b++) {
                bigEndian[width - 1 - b] = data.get(i * width + b);
            }
            return BigQueryProtoRows.plain(new BigDecimal(new BigInteger(bigEndian), field.scale()));
        }

        private String interval(int i) {
            return switch (field.unit()) {
                case IntervalUnit.YEAR_MONTH -> BigQueryProtoRows.interval(data.getInt(i * 4), 0, BigDecimal.ZERO);
                case IntervalUnit.DAY_TIME -> BigQueryProtoRows.interval(0, data.getInt(i * 8),
                        BigDecimal.valueOf(data.getInt(i * 8 + 4), 3));
                default -> BigQueryProtoRows.interval(data.getInt(i * 16), data.getInt(i * 16 + 4),
                        BigDecimal.valueOf(data.getLong(i * 16 + 8), 9));
            };
        }

        private List<Object> list(int i) {
            Column items = children.get(0);
            List<Object> values = new ArrayList<>();
            for (int j = offsets.getInt(i * 4); j < offsets.getInt((i + 1) * 4); j++) {
                Object value = items.value(j);
                if (value == null) {
                    throw new IllegalArgumentException("Array " + field.column().getName()
                            + " cannot have a null element");
                }
                values.add(value);
            }
            return values;
        }

        private Map<String, Object> struct(int i) {
            Map<String, Object> json = new LinkedHashMap<>();
            for (Column child : children) {
                Object value = child.value(i);
                if (value != null) {
                    json.put(child.field().column().getName(), value);
                }
            }
            return json;
        }
    }
}
