package io.floci.gcp.services.bigquery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.cloud.bigquery.storage.v1.AppendRowsRequest;
import com.google.cloud.bigquery.storage.v1.AppendRowsResponse;
import com.google.cloud.bigquery.storage.v1.BatchCommitWriteStreamsRequest;
import com.google.cloud.bigquery.storage.v1.BatchCommitWriteStreamsResponse;
import com.google.cloud.bigquery.storage.v1.CreateWriteStreamRequest;
import com.google.cloud.bigquery.storage.v1.FinalizeWriteStreamResponse;
import com.google.cloud.bigquery.storage.v1.FlushRowsRequest;
import com.google.cloud.bigquery.storage.v1.FlushRowsResponse;
import com.google.cloud.bigquery.storage.v1.GetWriteStreamRequest;
import com.google.cloud.bigquery.storage.v1.RowError;
import com.google.cloud.bigquery.storage.v1.StorageError.StorageErrorCode;
import com.google.cloud.bigquery.storage.v1.StorageError;
import com.google.cloud.bigquery.storage.v1.TableFieldSchema.Mode;
import com.google.cloud.bigquery.storage.v1.TableSchema;
import com.google.cloud.bigquery.storage.v1.WriteStream;
import com.google.cloud.bigquery.storage.v1.WriteStreamView;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Int64Value;
import com.google.protobuf.Timestamp;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.lifecycle.GrpcServerManager;
import io.floci.gcp.services.bigquery.model.Dataset;
import io.floci.gcp.services.bigquery.model.ErrorProto;
import io.floci.gcp.services.bigquery.model.StoredWriteStream;
import io.floci.gcp.services.bigquery.model.Table;
import io.floci.gcp.services.bigquery.model.TableFieldSchema;
import io.grpc.Status;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BigQuery Storage Write API ({@code google.cloud.bigquery.storage.v1.BigQueryWrite}). The
 * {@code _default} stream and COMMITTED streams append to the table as each request is
 * acknowledged; PENDING streams hold their rows until {@code BatchCommitWriteStreams}, and
 * BUFFERED streams until {@code FlushRows}. Stream state, uncommitted rows included, is written
 * through to a {@link StorageFactory} store on every change, so it follows the configured storage
 * mode; the in-memory map only holds the live objects whose monitors guard each stream.
 *
 * <p>{@code StorageError} and {@code RowError} are nested message fields of RPC responses that
 * quarkus-grpc did not register for native-image reflection. The native binary failed on
 * {@code StorageError} with a missing {@code getCode}; {@code RowError} sits at the same depth on
 * {@code AppendRowsResponse} and no compat case exercises a rejected append natively, so it is
 * registered here rather than left to be discovered in a release build.
 */
@ApplicationScoped
@RegisterForReflection(targets = {StorageError.class, StorageError.Builder.class,
        RowError.class, RowError.Builder.class})
public class BigQueryStorageWrite {

    static final String DEFAULT_STREAM = "_default";

    private static final Pattern TABLE = Pattern.compile("projects/([^/]+)/datasets/([^/]+)/tables/([^/]+)");
    private static final Pattern STREAM = Pattern.compile(
            "(projects/([^/]+)/datasets/([^/]+)/tables/([^/]+))/(?:streams/([^/]+)|(_default))");

    private record TableRef(String projectId, String datasetId, String tableId, String name) {}

    /** An application-created stream. */
    private static final class Stream {
        final String name;
        final TableRef table;
        final WriteStream.Type type;
        final Instant created;
        final List<Map<String, Object>> uncommitted = new ArrayList<>();
        long rowCount;
        long flushed;
        Instant finalized;
        Instant committed;

        Stream(String name, TableRef table, WriteStream.Type type, Instant created) {
            this.name = name;
            this.table = table;
            this.type = type;
            this.created = created;
        }

        static Stream from(StoredWriteStream stored) {
            TableRef table = new TableRef(stored.getProjectId(), stored.getDatasetId(), stored.getTableId(),
                    "projects/" + stored.getProjectId() + "/datasets/" + stored.getDatasetId()
                            + "/tables/" + stored.getTableId());
            Stream stream = new Stream(stored.getName(), table, WriteStream.Type.valueOf(stored.getType()),
                    Instant.parse(stored.getCreated()));
            stream.uncommitted.addAll(stored.getUncommitted());
            stream.rowCount = stored.getRowCount();
            stream.flushed = stored.getFlushed();
            stream.finalized = stored.getFinalized() != null ? Instant.parse(stored.getFinalized()) : null;
            stream.committed = stored.getCommitted() != null ? Instant.parse(stored.getCommitted()) : null;
            return stream;
        }

        StoredWriteStream toStored() {
            StoredWriteStream stored = new StoredWriteStream();
            stored.setName(name);
            stored.setProjectId(table.projectId());
            stored.setDatasetId(table.datasetId());
            stored.setTableId(table.tableId());
            stored.setType(type.name());
            stored.setCreated(created.toString());
            stored.setFinalized(finalized != null ? finalized.toString() : null);
            stored.setCommitted(committed != null ? committed.toString() : null);
            stored.setRowCount(rowCount);
            stored.setFlushed(flushed);
            stored.setUncommitted(new ArrayList<>(uncommitted));
            return stored;
        }
    }

    /** An append rejected in-stream; the connection stays open for further requests. */
    static final class AppendException extends RuntimeException {
        final Status.Code code;
        final StorageErrorCode storageCode;
        final String entity;
        final List<RowError> rowErrors;

        AppendException(Status.Code code, StorageErrorCode storageCode, String entity, String message,
                        List<RowError> rowErrors) {
            super(message);
            this.code = code;
            this.storageCode = storageCode;
            this.entity = entity;
            this.rowErrors = rowErrors;
        }

        AppendException(Status.Code code, StorageErrorCode storageCode, String entity, String message) {
            this(code, storageCode, entity, message, List.of());
        }

        AppendRowsResponse toResponse(String writeStream) {
            // com.google.rpc.Status is qualified: io.grpc.Status is imported for the call status codes.
            com.google.rpc.Status.Builder status = com.google.rpc.Status.newBuilder()
                    .setCode(code.value()).setMessage(getMessage());
            if (storageCode != null) {
                status.addDetails(Any.pack(StorageError.newBuilder().setCode(storageCode)
                        .setEntity(entity).setErrorMessage(getMessage()).build()));
            }
            return AppendRowsResponse.newBuilder().setError(status).addAllRowErrors(rowErrors)
                    .setWriteStream(writeStream).build();
        }
    }

    /** The per-connection state of an AppendRows call: destination and writer schema. */
    static final class Connection {
        String writeStream;
        String schemaStream;
        BigQueryProtoRows.Schema schema;
    }

    private final BigQueryService service;
    private final GrpcServerManager grpcServerManager;
    private final EmulatorConfig config;
    private final StorageBackend<String, StoredWriteStream> streamStore;
    private final Map<String, Stream> streams = new ConcurrentHashMap<>();

    @Inject
    public BigQueryStorageWrite(BigQueryService service, GrpcServerManager grpcServerManager, EmulatorConfig config,
                                StorageFactory storageFactory) {
        this.service = service;
        this.grpcServerManager = grpcServerManager;
        this.config = config;
        // Global, keyed by the full stream name: the name already carries the project, and gRPC
        // calls do not pass through the REST project filter.
        this.streamStore = storageFactory.createGlobal("bigquery-write-streams", "bigquery-write-streams.json",
                new TypeReference<Map<String, StoredWriteStream>>() {});
    }

    void onStart(@Observes StartupEvent event) {
        if (config.services().bigquery().enabled()) {
            grpcServerManager.bind(new BigQueryWriteController(this));
        }
    }

    /** The project whose storage a table or stream name lives in. */
    static String projectOf(String name) {
        Matcher stream = STREAM.matcher(name);
        if (stream.matches()) {
            return stream.group(2);
        }
        Matcher table = TABLE.matcher(name);
        return table.matches() ? table.group(1) : null;
    }

    // ── Streams ──────────────────────────────────────────────────────────────

    public WriteStream createWriteStream(CreateWriteStreamRequest request) {
        TableRef table = table(request.getParent());
        WriteStream.Type type = request.getWriteStream().getType();
        if (type != WriteStream.Type.COMMITTED && type != WriteStream.Type.PENDING
                && type != WriteStream.Type.BUFFERED) {
            throw GcpException.invalidArgument("write_stream.type must be COMMITTED, PENDING or BUFFERED");
        }
        Table resource = service.getTable(table.projectId(), table.datasetId(), table.tableId());
        String name = table.name() + "/streams/" + UUID.randomUUID().toString().replace("-", "");
        Stream stream = new Stream(name, table, type, Instant.now());
        if (type == WriteStream.Type.COMMITTED) {
            stream.committed = stream.created;
        }
        synchronized (stream) {
            streams.put(name, stream);
            persist(stream);
        }
        return resource(stream, resource, WriteStreamView.FULL);
    }

    public WriteStream getWriteStream(GetWriteStreamRequest request) {
        WriteStreamView view = request.getView() == WriteStreamView.FULL ? WriteStreamView.FULL : WriteStreamView.BASIC;
        Matcher name = streamName(request.getName());
        TableRef table = new TableRef(name.group(2), name.group(3), name.group(4), name.group(1));
        Table resource = service.getTable(table.projectId(), table.datasetId(), table.tableId());
        if (isDefault(name)) {
            Instant created = Instant.ofEpochMilli(Long.parseLong(resource.getCreationTime()));
            Stream stream = new Stream(table.name() + "/streams/" + DEFAULT_STREAM, table,
                    WriteStream.Type.COMMITTED, created);
            stream.committed = created;
            return resource(stream, resource, view);
        }
        return resource(stream(request.getName()), resource, view);
    }

    public FinalizeWriteStreamResponse finalizeWriteStream(String name) {
        if (isDefault(streamName(name))) {
            throw GcpException.invalidArgument("FinalizeWriteStream is not supported on the _default stream: " + name);
        }
        Stream stream = stream(name);
        synchronized (stream) {
            if (stream.finalized == null) {
                stream.finalized = Instant.now();
                persist(stream);
            }
            return FinalizeWriteStreamResponse.newBuilder().setRowCount(stream.rowCount).build();
        }
    }

    public BatchCommitWriteStreamsResponse batchCommitWriteStreams(BatchCommitWriteStreamsRequest request) {
        TableRef table = table(request.getParent());
        if (request.getWriteStreamsCount() == 0) {
            throw GcpException.invalidArgument("write_streams must not be empty");
        }
        service.getTable(table.projectId(), table.datasetId(), table.tableId());
        List<StorageError> errors = new ArrayList<>();
        List<Stream> pending = new ArrayList<>();
        // A stream named twice is committed once: streams "cannot be committed multiple times".
        for (String name : new LinkedHashSet<>(request.getWriteStreamsList())) {
            Stream stream = lookup(name);
            if (stream == null || !stream.table.name().equals(table.name())) {
                errors.add(storageError(StorageErrorCode.STREAM_NOT_FOUND, name, "Stream is not found: " + name));
            } else if (stream.type != WriteStream.Type.PENDING) {
                errors.add(storageError(StorageErrorCode.INVALID_STREAM_TYPE, name,
                        "Stream is not a PENDING stream: " + name));
            } else {
                pending.add(stream);
            }
        }
        if (!errors.isEmpty()) {
            return BatchCommitWriteStreamsResponse.newBuilder().addAllStreamErrors(errors).build();
        }
        List<Stream> ordered = new ArrayList<>(pending);
        ordered.sort(Comparator.comparing(stream -> stream.name));
        Instant now = Instant.now();
        errors = commit(ordered, 0, now, rows ->
                service.appendRows(table.projectId(), table.datasetId(), table.tableId(), rows));
        if (!errors.isEmpty()) {
            return BatchCommitWriteStreamsResponse.newBuilder().addAllStreamErrors(errors).build();
        }
        return BatchCommitWriteStreamsResponse.newBuilder().setCommitTime(timestamp(now)).build();
    }

    /**
     * Takes every pending stream's monitor, in the caller's order, then checks and drains them in
     * one go. Recursion is how the locks are held together without a lock-ordering mistake: the
     * streams arrive sorted by name, so two concurrent commits acquire them in the same order. The
     * committed and finalized checks run only once every monitor is held, so of two concurrent
     * commits of one stream exactly one succeeds, and a failed check commits nothing.
     */
    private List<StorageError> commit(List<Stream> ordered, int index, Instant now,
                                      Consumer<List<Map<String, Object>>> write) {
        if (index == ordered.size()) {
            List<StorageError> errors = new ArrayList<>();
            for (Stream stream : ordered) {
                if (stream.committed != null) {
                    errors.add(storageError(StorageErrorCode.STREAM_ALREADY_COMMITTED, stream.name,
                            "Stream is already committed: " + stream.name));
                } else if (stream.finalized == null) {
                    errors.add(storageError(StorageErrorCode.INVALID_STREAM_STATE, stream.name,
                            "Stream is not finalized: " + stream.name));
                }
            }
            if (!errors.isEmpty()) {
                return errors;
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Stream stream : ordered) {
                rows.addAll(stream.uncommitted);
            }
            write.accept(rows);
            for (Stream stream : ordered) {
                stream.committed = now;
                stream.uncommitted.clear();
                persist(stream);
            }
            return errors;
        }
        synchronized (ordered.get(index)) {
            return commit(ordered, index + 1, now, write);
        }
    }

    public FlushRowsResponse flushRows(FlushRowsRequest request) {
        if (isDefault(streamName(request.getWriteStream()))) {
            throw GcpException.invalidArgument("FlushRows is not supported on the _default stream: "
                    + request.getWriteStream());
        }
        Stream stream = stream(request.getWriteStream());
        if (stream.type != WriteStream.Type.BUFFERED) {
            throw GcpException.invalidArgument("FlushRows is only supported on BUFFERED streams: " + stream.name);
        }
        synchronized (stream) {
            long offset = request.hasOffset() ? request.getOffset().getValue() : stream.rowCount - 1;
            if (offset < 0 || offset >= stream.rowCount) {
                throw GcpException.outOfRange("Flush offset " + offset + " is beyond the end of stream "
                        + stream.name + " (" + stream.rowCount + " rows)");
            }
            if (offset >= stream.flushed) {
                List<Map<String, Object>> rows = stream.uncommitted.subList(0, (int) (offset + 1 - stream.flushed));
                service.appendRows(stream.table.projectId(), stream.table.datasetId(), stream.table.tableId(),
                        new ArrayList<>(rows));
                rows.clear();
                stream.flushed = offset + 1;
                persist(stream);
            }
            return FlushRowsResponse.newBuilder().setOffset(offset).build();
        }
    }

    // ── AppendRows ───────────────────────────────────────────────────────────

    /** Handles one request of an AppendRows connection; rejections come back as AppendException. */
    public AppendRowsResponse append(Connection connection, AppendRowsRequest request) {
        if (!request.getWriteStream().isEmpty()) {
            connection.writeStream = request.getWriteStream();
        }
        if (connection.writeStream == null) {
            throw GcpException.invalidArgument("write_stream must be set on the first request of a connection");
        }
        String streamName = connection.writeStream;
        Matcher name = streamName(streamName);
        TableRef table = new TableRef(name.group(2), name.group(3), name.group(4), name.group(1));
        if (request.hasArrowRows()) {
            throw new AppendException(Status.Code.UNIMPLEMENTED, null, streamName,
                    "Arrow rows are not supported by the floci BigQuery emulator; send proto_rows");
        }
        if (!request.hasProtoRows()) {
            throw new AppendException(Status.Code.INVALID_ARGUMENT, null, streamName, "proto_rows must be set");
        }
        Table resource;
        try {
            resource = service.getTable(table.projectId(), table.datasetId(), table.tableId());
        } catch (GcpException e) {
            throw new AppendException(Status.Code.NOT_FOUND, StorageErrorCode.TABLE_NOT_FOUND, table.name(),
                    "Table is not found: " + table.name());
        }
        List<TableFieldSchema> fields = resource.getSchema() != null && resource.getSchema().getFields() != null
                ? resource.getSchema().getFields() : List.of();
        if (request.getProtoRows().hasWriterSchema()) {
            try {
                connection.schema = BigQueryProtoRows.bind(
                        request.getProtoRows().getWriterSchema().getProtoDescriptor(), fields);
                connection.schemaStream = streamName;
            } catch (BigQueryProtoRows.ExtraFieldsException e) {
                throw new AppendException(Status.Code.INVALID_ARGUMENT, StorageErrorCode.SCHEMA_MISMATCH_EXTRA_FIELDS,
                        streamName, e.getMessage());
            } catch (GcpException e) {
                throw new AppendException(Status.Code.INVALID_ARGUMENT, null, streamName, e.getMessage());
            }
        } else if (connection.schema == null || !streamName.equals(connection.schemaStream)) {
            throw new AppendException(Status.Code.INVALID_ARGUMENT, null, streamName,
                    "writer_schema must be set on the first request to " + streamName);
        }
        List<Map<String, Object>> rows = decodeRows(connection.schema, request, fields, streamName);

        if (isDefault(name)) {
            if (request.hasOffset()) {
                throw new AppendException(Status.Code.INVALID_ARGUMENT, null, streamName,
                        "offset is not allowed when appending to the _default stream");
            }
            service.appendRows(table.projectId(), table.datasetId(), table.tableId(), rows);
            return AppendRowsResponse.newBuilder()
                    .setAppendResult(AppendRowsResponse.AppendResult.getDefaultInstance())
                    .setWriteStream(streamName).build();
        }
        Stream stream = lookup(streamName);
        if (stream == null) {
            throw new AppendException(Status.Code.NOT_FOUND, StorageErrorCode.STREAM_NOT_FOUND, streamName,
                    "Stream is not found: " + streamName);
        }
        synchronized (stream) {
            if (stream.finalized != null) {
                throw new AppendException(Status.Code.INVALID_ARGUMENT, StorageErrorCode.STREAM_FINALIZED,
                        streamName, "Stream is finalized: " + streamName);
            }
            long offset = stream.rowCount;
            if (request.hasOffset() && request.getOffset().getValue() != offset) {
                long received = request.getOffset().getValue();
                boolean behind = received < offset;
                throw new AppendException(behind ? Status.Code.ALREADY_EXISTS : Status.Code.OUT_OF_RANGE,
                        behind ? StorageErrorCode.OFFSET_ALREADY_EXISTS : StorageErrorCode.OFFSET_OUT_OF_RANGE,
                        streamName, "The offset is " + (behind ? "within stream" : "beyond stream")
                        + ", expected offset " + offset + ", received " + received);
            }
            if (stream.type == WriteStream.Type.COMMITTED) {
                service.appendRows(table.projectId(), table.datasetId(), table.tableId(), rows);
            } else {
                stream.uncommitted.addAll(rows);
            }
            stream.rowCount += rows.size();
            persist(stream);
            return AppendRowsResponse.newBuilder()
                    .setAppendResult(AppendRowsResponse.AppendResult.newBuilder().setOffset(Int64Value.of(offset)))
                    .setWriteStream(streamName).build();
        }
    }

    /** Decodes and validates every row; one bad row rejects the whole request with row errors. */
    private static List<Map<String, Object>> decodeRows(BigQueryProtoRows.Schema schema, AppendRowsRequest request,
                                                        List<TableFieldSchema> fields, String streamName) {
        // The model TableSchema is qualified: the storage v1 TableSchema is imported for the wire.
        io.floci.gcp.services.bigquery.model.TableSchema tableSchema =
                new io.floci.gcp.services.bigquery.model.TableSchema(fields);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<RowError> rowErrors = new ArrayList<>();
        List<ByteString> serialized = request.getProtoRows().getRows().getSerializedRowsList();
        for (int i = 0; i < serialized.size(); i++) {
            try {
                Map<String, Object> normalized = new LinkedHashMap<>();
                List<ErrorProto> errors = RowCodec.normalizeRow(tableSchema,
                        BigQueryProtoRows.decode(schema, serialized.get(i)), true, normalized);
                if (errors.isEmpty()) {
                    rows.add(normalized);
                } else {
                    rowErrors.add(rowError(i, errors.get(0).getMessage()));
                }
            } catch (IllegalArgumentException | ArithmeticException | ClassCastException e) {
                rowErrors.add(rowError(i, e.getMessage()));
            }
        }
        if (!rowErrors.isEmpty()) {
            throw new AppendException(Status.Code.INVALID_ARGUMENT, null, streamName,
                    "Errors found while processing rows. Please refer to the row_errors field for details."
                            + " The list may not be complete because of the size limitations.", rowErrors);
        }
        return rows;
    }

    private static RowError rowError(int index, String message) {
        return RowError.newBuilder().setIndex(index).setCode(RowError.RowErrorCode.FIELDS_ERROR)
                .setMessage(message != null ? message : "Invalid row").build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * The live stream for a name, loading it from the store after a restart. computeIfAbsent keeps
     * one live object per name, so every caller synchronizes on the same monitor.
     */
    private Stream lookup(String name) {
        return streams.computeIfAbsent(name, key -> streamStore.get(key).map(Stream::from).orElse(null));
    }

    /** Drops the live stream objects, as a restart does; the next lookup reloads them from the store. */
    void forgetLiveStreams() {
        streams.clear();
    }

    /** Writes the stream through to the store; callers hold its monitor. */
    private void persist(Stream stream) {
        streamStore.put(stream.name, stream.toStored());
    }

    private Stream stream(String name) {
        Stream stream = lookup(name);
        if (stream == null) {
            streamName(name);
            throw GcpException.notFound("Stream is not found: " + name);
        }
        return stream;
    }

    private static Matcher streamName(String name) {
        Matcher matcher = STREAM.matcher(name);
        if (!matcher.matches()) {
            throw GcpException.invalidArgument(
                    "Invalid stream name " + name + "; expected projects/{project}/datasets/{dataset}/tables/{table}"
                            + "/streams/{stream}");
        }
        return matcher;
    }

    private static boolean isDefault(Matcher name) {
        return name.group(6) != null || DEFAULT_STREAM.equals(name.group(5));
    }

    private static TableRef table(String parent) {
        Matcher matcher = TABLE.matcher(parent);
        if (!matcher.matches()) {
            throw GcpException.invalidArgument(
                    "parent must be of the form projects/{project}/datasets/{dataset}/tables/{table}");
        }
        return new TableRef(matcher.group(1), matcher.group(2), matcher.group(3), parent);
    }

    private WriteStream resource(Stream stream, Table table, WriteStreamView view) {
        WriteStream.Builder resource = WriteStream.newBuilder()
                .setName(stream.name)
                .setType(stream.type)
                .setCreateTime(timestamp(stream.created))
                .setWriteMode(WriteStream.WriteMode.INSERT)
                .setLocation(location(stream.table));
        if (stream.committed != null) {
            resource.setCommitTime(timestamp(stream.committed));
        }
        if (view == WriteStreamView.FULL) {
            resource.setTableSchema(tableSchema(table));
        }
        return resource.build();
    }

    private String location(TableRef table) {
        Dataset dataset = service.getDataset(table.projectId(), table.datasetId());
        String location = dataset.getLocation() != null && !dataset.getLocation().isBlank() ? dataset.getLocation() : "US";
        return location.toLowerCase(Locale.ROOT);
    }

    static TableSchema tableSchema(Table table) {
        TableSchema.Builder schema = TableSchema.newBuilder();
        if (table.getSchema() != null && table.getSchema().getFields() != null) {
            table.getSchema().getFields().forEach(field -> schema.addFields(field(field)));
        }
        return schema.build();
    }

    // The storage v1 TableFieldSchema is qualified here and below: the model one is imported.
    private static com.google.cloud.bigquery.storage.v1.TableFieldSchema field(TableFieldSchema field) {
        com.google.cloud.bigquery.storage.v1.TableFieldSchema.Builder proto =
                com.google.cloud.bigquery.storage.v1.TableFieldSchema.newBuilder()
                        .setName(field.getName())
                        .setType(storageType(field.getType()))
                        .setMode(field.getMode() == null ? Mode.NULLABLE : Mode.valueOf(field.getMode().toUpperCase(Locale.ROOT)));
        if (field.getDescription() != null) {
            proto.setDescription(field.getDescription());
        }
        if (field.getFields() != null) {
            field.getFields().forEach(child -> proto.addFields(field(child)));
        }
        return proto.build();
    }

    private static com.google.cloud.bigquery.storage.v1.TableFieldSchema.Type storageType(String type) {
        return switch (RowCodec.legacyType(type)) {
            case "INTEGER" -> com.google.cloud.bigquery.storage.v1.TableFieldSchema.Type.INT64;
            case "FLOAT" -> com.google.cloud.bigquery.storage.v1.TableFieldSchema.Type.DOUBLE;
            case "BOOLEAN" -> com.google.cloud.bigquery.storage.v1.TableFieldSchema.Type.BOOL;
            case "RECORD" -> com.google.cloud.bigquery.storage.v1.TableFieldSchema.Type.STRUCT;
            default -> {
                try {
                    yield com.google.cloud.bigquery.storage.v1.TableFieldSchema.Type.valueOf(RowCodec.legacyType(type));
                } catch (IllegalArgumentException e) {
                    yield com.google.cloud.bigquery.storage.v1.TableFieldSchema.Type.TYPE_UNSPECIFIED;
                }
            }
        };
    }

    private static StorageError storageError(StorageErrorCode code, String entity, String message) {
        return StorageError.newBuilder().setCode(code).setEntity(entity).setErrorMessage(message).build();
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }
}
