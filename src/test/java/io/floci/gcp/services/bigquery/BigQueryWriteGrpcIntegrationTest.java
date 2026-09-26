package io.floci.gcp.services.bigquery;

import com.google.cloud.bigquery.storage.v1.AnnotationsProto;
import com.google.cloud.bigquery.storage.v1.AppendRowsRequest;
import com.google.cloud.bigquery.storage.v1.AppendRowsResponse;
import com.google.cloud.bigquery.storage.v1.BatchCommitWriteStreamsRequest;
import com.google.cloud.bigquery.storage.v1.BatchCommitWriteStreamsResponse;
import com.google.cloud.bigquery.storage.v1.BigQueryWriteGrpc;
import com.google.cloud.bigquery.storage.v1.CreateWriteStreamRequest;
import com.google.cloud.bigquery.storage.v1.FinalizeWriteStreamRequest;
import com.google.cloud.bigquery.storage.v1.FlushRowsRequest;
import com.google.cloud.bigquery.storage.v1.GetWriteStreamRequest;
import com.google.cloud.bigquery.storage.v1.ProtoRows;
import com.google.cloud.bigquery.storage.v1.ProtoSchema;
import com.google.cloud.bigquery.storage.v1.StorageError;
import com.google.cloud.bigquery.storage.v1.TableFieldSchema;
import com.google.cloud.bigquery.storage.v1.WriteStream;
import com.google.cloud.bigquery.storage.v1.WriteStreamView;
import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Int64Value;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Storage Write API over gRPC on the shared port, in mock mode. Rows are written as proto rows
 * with a hand-built DescriptorProto (what the client libraries send) and read back over REST.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BigQueryWriteGrpcIntegrationTest {

    private static final String PROJECT = "bq-write-it";
    private static final String BASE = "/bigquery/v2/projects/" + PROJECT;
    private static final String TABLE = "projects/" + PROJECT + "/datasets/writes/tables/events";

    private static final DescriptorProto ROW = DescriptorProto.newBuilder()
            .setName("Row")
            .addField(field("name", 1, FieldDescriptorProto.Type.TYPE_STRING))
            .addField(field("age", 2, FieldDescriptorProto.Type.TYPE_INT64))
            .addField(field("ts", 3, FieldDescriptorProto.Type.TYPE_INT64))
            .addField(field("d", 4, FieldDescriptorProto.Type.TYPE_INT32))
            .addField(field("dt", 5, FieldDescriptorProto.Type.TYPE_INT64))
            .addField(field("n", 6, FieldDescriptorProto.Type.TYPE_BYTES))
            .addField(field("tags", 7, FieldDescriptorProto.Type.TYPE_STRING)
                    .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED))
            .addField(field("addr", 8, FieldDescriptorProto.Type.TYPE_MESSAGE).setTypeName("Addr"))
            .addField(field("col_dW5pdCBwcmljZQ", 9, FieldDescriptorProto.Type.TYPE_DOUBLE)
                    .setOptions(columnName("unit price")))
            .addNestedType(DescriptorProto.newBuilder().setName("Addr")
                    .addField(field("city", 1, FieldDescriptorProto.Type.TYPE_STRING)))
            .build();
    private static final Descriptor DESCRIPTOR = descriptor(ROW);

    @TestHTTPResource
    URI endpoint;

    private ManagedChannel channel;
    private BigQueryWriteGrpc.BigQueryWriteBlockingStub write;

    private static FieldDescriptorProto.Builder field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }

    private static FieldOptions columnName(String column) {
        return FieldOptions.newBuilder().setExtension(AnnotationsProto.columnName, column).build();
    }

    private static Descriptor descriptor(DescriptorProto proto) {
        try {
            return FileDescriptor.buildFrom(FileDescriptorProto.newBuilder().setName("t.proto")
                    .addMessageType(proto).build(), new FileDescriptor[0]).findMessageTypeByName(proto.getName());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** NUMERIC bytes as BigDecimalByteStringEncoder writes them: little-endian, scale 9. */
    private static ByteString numeric(String value) {
        byte[] bytes = new BigDecimal(value).setScale(9).unscaledValue().toByteArray();
        for (int i = 0, j = bytes.length - 1; i < j; i++, j--) {
            byte b = bytes[i];
            bytes[i] = bytes[j];
            bytes[j] = b;
        }
        return ByteString.copyFrom(bytes);
    }

    /** CivilTimeEncoder.encodePacked64DatetimeMicros. */
    private static long datetime(int year, int month, int day, int hour, int minute, int second, int micros) {
        long seconds = ((long) year << 26) | ((long) month << 22) | ((long) day << 17)
                | ((long) hour << 12) | ((long) minute << 6) | second;
        return (seconds << 20) | micros;
    }

    private static ByteString row(String name, Long age) {
        DynamicMessage.Builder row = DynamicMessage.newBuilder(DESCRIPTOR);
        if (name != null) {
            row.setField(DESCRIPTOR.findFieldByName("name"), name);
        }
        if (age != null) {
            row.setField(DESCRIPTOR.findFieldByName("age"), age);
        }
        return row.build().toByteString();
    }

    private static AppendRowsRequest.Builder request(String stream, DescriptorProto schema, ByteString... rows) {
        AppendRowsRequest.ProtoData.Builder data = AppendRowsRequest.ProtoData.newBuilder()
                .setRows(ProtoRows.newBuilder().addAllSerializedRows(Arrays.asList(rows)));
        if (schema != null) {
            data.setWriterSchema(ProtoSchema.newBuilder().setProtoDescriptor(schema));
        }
        AppendRowsRequest.Builder request = AppendRowsRequest.newBuilder().setProtoRows(data);
        if (stream != null) {
            request.setWriteStream(stream);
        }
        return request;
    }

    /** One AppendRows connection; each request waits for its response. */
    private final class Connection implements AutoCloseable {
        private final BlockingQueue<Object> responses = new LinkedBlockingQueue<>();
        private final StreamObserver<AppendRowsRequest> requests;

        Connection() {
            requests = BigQueryWriteGrpc.newStub(channel).appendRows(new StreamObserver<>() {
                @Override
                public void onNext(AppendRowsResponse value) {
                    responses.add(value);
                }

                @Override
                public void onError(Throwable t) {
                    responses.add(t);
                }

                @Override
                public void onCompleted() {
                    responses.add("completed");
                }
            });
        }

        AppendRowsResponse send(AppendRowsRequest.Builder request) throws Exception {
            requests.onNext(request.build());
            Object response = responses.poll(10, TimeUnit.SECONDS);
            if (response instanceof Throwable t) {
                throw (Exception) t;
            }
            return (AppendRowsResponse) response;
        }

        @Override
        public void close() throws Exception {
            requests.onCompleted();
            assertEquals("completed", responses.poll(10, TimeUnit.SECONDS));
        }
    }

    private static List<List<Object>> tableRows() {
        return given().queryParam("formatOptions.useInt64Timestamp", true)
                .when().get(BASE + "/datasets/writes/tables/events/data")
                .then().statusCode(200).extract().jsonPath().getList("rows.f.v");
    }

    private static StorageError storageError(AppendRowsResponse response) throws Exception {
        return response.getError().getDetails(0).unpack(StorageError.class);
    }

    @BeforeEach
    void connect() {
        channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
        write = BigQueryWriteGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void close() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    @Order(1)
    void seed() {
        given().contentType("application/json").body("{\"datasetReference\": {\"datasetId\": \"writes\"}}")
                .when().post(BASE + "/datasets").then().statusCode(200);
        given().contentType("application/json").body("""
                {"tableReference": {"tableId": "events"}, "schema": {"fields": [
                  {"name": "name", "type": "STRING", "mode": "REQUIRED"},
                  {"name": "age", "type": "INT64"},
                  {"name": "ts", "type": "TIMESTAMP"},
                  {"name": "d", "type": "DATE"},
                  {"name": "dt", "type": "DATETIME"},
                  {"name": "n", "type": "NUMERIC"},
                  {"name": "tags", "type": "STRING", "mode": "REPEATED"},
                  {"name": "addr", "type": "RECORD", "fields": [{"name": "city", "type": "STRING"}]},
                  {"name": "unit price", "type": "FLOAT64"}]}}
                """).when().post(BASE + "/datasets/writes/tables").then().statusCode(200);
    }

    @Test
    @Order(2)
    void defaultStreamAppendsTypedRows() throws Exception {
        DynamicMessage.Builder full = DynamicMessage.newBuilder(DESCRIPTOR)
                .setField(DESCRIPTOR.findFieldByName("name"), "ada")
                .setField(DESCRIPTOR.findFieldByName("age"), 36L)
                .setField(DESCRIPTOR.findFieldByName("ts"), 1_700_000_000_123_456L)
                .setField(DESCRIPTOR.findFieldByName("d"), 19_723)
                .setField(DESCRIPTOR.findFieldByName("dt"), datetime(2024, 1, 2, 3, 4, 5, 6))
                .setField(DESCRIPTOR.findFieldByName("n"), numeric("-12.5"))
                .addRepeatedField(DESCRIPTOR.findFieldByName("tags"), "a")
                .addRepeatedField(DESCRIPTOR.findFieldByName("tags"), "b")
                .setField(DESCRIPTOR.findFieldByName("addr"), DynamicMessage.newBuilder(
                                DESCRIPTOR.findNestedTypeByName("Addr"))
                        .setField(DESCRIPTOR.findNestedTypeByName("Addr").findFieldByName("city"), "London").build())
                .setField(DESCRIPTOR.findFieldByName("col_dW5pdCBwcmljZQ"), 9.5);
        try (Connection connection = new Connection()) {
            // The Java client names the default stream "<table>/_default".
            AppendRowsResponse first = connection.send(request(TABLE + "/_default", ROW, full.build().toByteString()));
            assertTrue(first.hasAppendResult(), first.toString());
            assertFalse(first.getAppendResult().hasOffset());
            assertEquals(TABLE + "/_default", first.getWriteStream());
            // Later requests on the connection may omit the stream and the schema.
            assertTrue(connection.send(request(null, null, row("bob", null))).hasAppendResult());
        }
        assertEquals(List.of(
                Arrays.asList("ada", "36", "1700000000123456", "2024-01-01", "2024-01-02T03:04:05.000006", "-12.5",
                        List.of(Map.of("v", "a"), Map.of("v", "b")), Map.of("f", List.of(Map.of("v", "London"))), "9.5"),
                Arrays.asList("bob", null, null, null, null, null, List.of(), null, null)), tableRows());
    }

    @Test
    @Order(3)
    void getWriteStreamDescribesTheDefaultStream() {
        WriteStream full = write.getWriteStream(GetWriteStreamRequest.newBuilder()
                .setName(TABLE + "/streams/_default").setView(WriteStreamView.FULL).build());
        assertEquals(TABLE + "/streams/_default", full.getName());
        assertEquals(WriteStream.Type.COMMITTED, full.getType());
        assertEquals("us", full.getLocation());
        assertEquals(full.getCreateTime(), full.getCommitTime());
        assertEquals(TableFieldSchema.Type.INT64, full.getTableSchema().getFields(1).getType());
        assertEquals(TableFieldSchema.Mode.REQUIRED, full.getTableSchema().getFields(0).getMode());
        assertEquals(TableFieldSchema.Type.STRUCT, full.getTableSchema().getFields(7).getType());
        assertEquals(TableFieldSchema.Type.DOUBLE, full.getTableSchema().getFields(8).getType());
        assertFalse(write.getWriteStream(GetWriteStreamRequest.newBuilder().setName(TABLE + "/_default").build())
                .hasTableSchema());
    }

    @Test
    @Order(4)
    void committedStreamEnforcesOffsets() throws Exception {
        WriteStream stream = write.createWriteStream(CreateWriteStreamRequest.newBuilder().setParent(TABLE)
                .setWriteStream(WriteStream.newBuilder().setType(WriteStream.Type.COMMITTED)).build());
        assertTrue(stream.getName().startsWith(TABLE + "/streams/"));
        assertTrue(stream.hasTableSchema());
        try (Connection connection = new Connection()) {
            AppendRowsResponse first = connection.send(request(stream.getName(), ROW, row("c1", 1L), row("c2", 2L))
                    .setOffset(Int64Value.of(0)));
            assertEquals(0, first.getAppendResult().getOffset().getValue());
            assertEquals(2, connection.send(request(null, null, row("c3", 3L))).getAppendResult().getOffset().getValue());

            AppendRowsResponse retry = connection.send(request(null, null, row("c1", 1L)).setOffset(Int64Value.of(0)));
            assertEquals(Status.Code.ALREADY_EXISTS.value(), retry.getError().getCode());
            assertEquals(StorageError.StorageErrorCode.OFFSET_ALREADY_EXISTS, storageError(retry).getCode());
            assertTrue(retry.getError().getMessage().contains("expected offset 3, received 0"));

            AppendRowsResponse ahead = connection.send(request(null, null, row("c9", 9L)).setOffset(Int64Value.of(10)));
            assertEquals(Status.Code.OUT_OF_RANGE.value(), ahead.getError().getCode());
            assertEquals(StorageError.StorageErrorCode.OFFSET_OUT_OF_RANGE, storageError(ahead).getCode());
        }
        assertEquals(5, tableRows().size(), "committed rows are visible immediately");
        assertEquals(3, write.finalizeWriteStream(FinalizeWriteStreamRequest.newBuilder()
                .setName(stream.getName()).build()).getRowCount());
        try (Connection connection = new Connection()) {
            AppendRowsResponse finalized = connection.send(request(stream.getName(), ROW, row("c4", 4L)));
            assertEquals(StorageError.StorageErrorCode.STREAM_FINALIZED, storageError(finalized).getCode());
        }
    }

    @Test
    @Order(5)
    void pendingStreamsCommitAtomically() throws Exception {
        List<String> names = new ArrayList<>();
        for (String prefix : List.of("p", "q")) {
            WriteStream stream = write.createWriteStream(CreateWriteStreamRequest.newBuilder().setParent(TABLE)
                    .setWriteStream(WriteStream.newBuilder().setType(WriteStream.Type.PENDING)).build());
            assertFalse(stream.hasCommitTime());
            try (Connection connection = new Connection()) {
                connection.send(request(stream.getName(), ROW, row(prefix + "1", 1L), row(prefix + "2", 2L)));
            }
            names.add(stream.getName());
        }
        assertEquals(5, tableRows().size(), "pending rows stay invisible");
        BatchCommitWriteStreamsRequest commit = BatchCommitWriteStreamsRequest.newBuilder().setParent(TABLE)
                .addAllWriteStreams(names).build();
        BatchCommitWriteStreamsResponse notFinalized = write.batchCommitWriteStreams(commit);
        assertFalse(notFinalized.hasCommitTime());
        assertEquals(StorageError.StorageErrorCode.INVALID_STREAM_STATE, notFinalized.getStreamErrors(0).getCode());

        names.forEach(name -> write.finalizeWriteStream(FinalizeWriteStreamRequest.newBuilder().setName(name).build()));
        BatchCommitWriteStreamsResponse committed = write.batchCommitWriteStreams(commit);
        assertTrue(committed.hasCommitTime());
        assertEquals(9, tableRows().size());
        assertTrue(write.getWriteStream(GetWriteStreamRequest.newBuilder().setName(names.get(0)).build())
                .hasCommitTime());
        assertEquals(StorageError.StorageErrorCode.STREAM_ALREADY_COMMITTED,
                write.batchCommitWriteStreams(commit).getStreamErrors(0).getCode());
    }

    @Test
    @Order(6)
    void bufferedStreamShowsRowsUpToTheFlushedOffset() throws Exception {
        WriteStream stream = write.createWriteStream(CreateWriteStreamRequest.newBuilder().setParent(TABLE)
                .setWriteStream(WriteStream.newBuilder().setType(WriteStream.Type.BUFFERED)).build());
        try (Connection connection = new Connection()) {
            connection.send(request(stream.getName(), ROW, row("b1", 1L), row("b2", 2L), row("b3", 3L)));
        }
        assertEquals(1, write.flushRows(FlushRowsRequest.newBuilder().setWriteStream(stream.getName())
                .setOffset(Int64Value.of(1)).build()).getOffset());
        assertEquals(11, tableRows().size());
        assertEquals(Status.Code.OUT_OF_RANGE, assertThrows(StatusRuntimeException.class,
                () -> write.flushRows(FlushRowsRequest.newBuilder().setWriteStream(stream.getName())
                        .setOffset(Int64Value.of(3)).build())).getStatus().getCode());
        write.flushRows(FlushRowsRequest.newBuilder().setWriteStream(stream.getName())
                .setOffset(Int64Value.of(2)).build());
        assertEquals(12, tableRows().size());
    }

    @Test
    @Order(7)
    void rejectedAppendsKeepTheConnectionOpen() throws Exception {
        DescriptorProto extra = ROW.toBuilder().addField(field("nope", 20, FieldDescriptorProto.Type.TYPE_STRING))
                .build();
        DescriptorProto mismatched = DescriptorProto.newBuilder().setName("Row")
                .addField(field("age", 1, FieldDescriptorProto.Type.TYPE_STRING)).build();
        try (Connection connection = new Connection()) {
            AppendRowsResponse extraFields = connection.send(request(TABLE + "/streams/_default", extra, row("x", 1L)));
            assertEquals(Status.Code.INVALID_ARGUMENT.value(), extraFields.getError().getCode());
            assertEquals(StorageError.StorageErrorCode.SCHEMA_MISMATCH_EXTRA_FIELDS, storageError(extraFields).getCode());
            assertTrue(extraFields.getError().getMessage().contains("'nope'"));

            AppendRowsResponse wrongType = connection.send(request(TABLE + "/streams/_default", mismatched));
            assertTrue(wrongType.getError().getMessage().contains("proto field type string"),
                    wrongType.getError().getMessage());

            AppendRowsResponse missingRequired = connection.send(
                    request(TABLE + "/streams/_default", ROW, row("ok", 1L), row(null, 2L)));
            assertEquals(Status.Code.INVALID_ARGUMENT.value(), missingRequired.getError().getCode());
            assertEquals(1, missingRequired.getRowErrorsCount());
            assertEquals(1, missingRequired.getRowErrors(0).getIndex());

            AppendRowsResponse unknown = connection.send(request(TABLE + "/streams/missing", ROW, row("x", 1L)));
            assertEquals(StorageError.StorageErrorCode.STREAM_NOT_FOUND, storageError(unknown).getCode());

            AppendRowsResponse arrow = connection.send(AppendRowsRequest.newBuilder()
                    .setWriteStream(TABLE + "/_default").setArrowRows(AppendRowsRequest.ArrowData.getDefaultInstance()));
            assertEquals(Status.Code.UNIMPLEMENTED.value(), arrow.getError().getCode());
        }
        assertEquals(12, tableRows().size(), "a request with a bad row appends nothing");

        try (Connection connection = new Connection()) {
            AppendRowsResponse noSchema = connection.send(request(TABLE + "/_default", null, row("x", 1L)));
            assertTrue(noSchema.getError().getMessage().contains("writer_schema"));
        }
        StatusRuntimeException noStream = assertThrows(StatusRuntimeException.class, () -> {
            try (Connection connection = new Connection()) {
                connection.send(request(null, ROW, row("x", 1L)));
            }
        });
        assertEquals(Status.Code.INVALID_ARGUMENT, noStream.getStatus().getCode());
    }

    @Test
    @Order(8)
    void unaryErrorsMapToGrpcCodes() {
        assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class,
                () -> write.createWriteStream(CreateWriteStreamRequest.newBuilder().setParent(TABLE)
                        .setWriteStream(WriteStream.getDefaultInstance()).build())).getStatus().getCode());
        assertEquals(Status.Code.NOT_FOUND, assertThrows(StatusRuntimeException.class,
                () -> write.createWriteStream(CreateWriteStreamRequest.newBuilder()
                        .setParent("projects/" + PROJECT + "/datasets/writes/tables/missing")
                        .setWriteStream(WriteStream.newBuilder().setType(WriteStream.Type.PENDING)).build()))
                .getStatus().getCode());
        assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class,
                () -> write.finalizeWriteStream(FinalizeWriteStreamRequest.newBuilder()
                        .setName(TABLE + "/streams/_default").build())).getStatus().getCode());
        assertEquals(Status.Code.NOT_FOUND, assertThrows(StatusRuntimeException.class,
                () -> write.getWriteStream(GetWriteStreamRequest.newBuilder()
                        .setName(TABLE + "/streams/nope").build())).getStatus().getCode());
        assertNotNull(write.getWriteStream(GetWriteStreamRequest.newBuilder().setName(TABLE + "/_default").build()));
    }

    private String finalizedPendingStream(String prefix) throws Exception {
        WriteStream stream = write.createWriteStream(CreateWriteStreamRequest.newBuilder().setParent(TABLE)
                .setWriteStream(WriteStream.newBuilder().setType(WriteStream.Type.PENDING)).build());
        try (Connection connection = new Connection()) {
            connection.send(request(stream.getName(), ROW, row(prefix + "1", 1L), row(prefix + "2", 2L)));
        }
        write.finalizeWriteStream(FinalizeWriteStreamRequest.newBuilder().setName(stream.getName()).build());
        return stream.getName();
    }

    @Test
    @Order(9)
    void aStreamNamedTwiceIsCommittedOnce() throws Exception {
        String name = finalizedPendingStream("dup");
        int before = tableRows().size();
        BatchCommitWriteStreamsResponse committed = write.batchCommitWriteStreams(BatchCommitWriteStreamsRequest
                .newBuilder().setParent(TABLE).addWriteStreams(name).addWriteStreams(name).build());
        assertTrue(committed.hasCommitTime());
        assertEquals(before + 2, tableRows().size());
    }

    @Test
    @Order(10)
    void concurrentCommitsOfOneStreamCommitItOnce() throws Exception {
        String name = finalizedPendingStream("race");
        int before = tableRows().size();
        BatchCommitWriteStreamsRequest commit = BatchCommitWriteStreamsRequest.newBuilder().setParent(TABLE)
                .addWriteStreams(name).build();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<BatchCommitWriteStreamsResponse>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> write.batchCommitWriteStreams(commit)));
            }
            int succeeded = 0;
            for (Future<BatchCommitWriteStreamsResponse> result : results) {
                BatchCommitWriteStreamsResponse response = result.get(30, TimeUnit.SECONDS);
                if (response.hasCommitTime()) {
                    succeeded++;
                } else {
                    assertEquals(StorageError.StorageErrorCode.STREAM_ALREADY_COMMITTED,
                            response.getStreamErrors(0).getCode());
                }
            }
            assertEquals(1, succeeded);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(before + 2, tableRows().size());
    }
}
