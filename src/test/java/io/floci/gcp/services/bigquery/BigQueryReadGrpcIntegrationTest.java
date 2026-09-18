package io.floci.gcp.services.bigquery;

import com.google.cloud.bigquery.storage.v1.BigQueryReadGrpc;
import com.google.cloud.bigquery.storage.v1.CreateReadSessionRequest;
import com.google.cloud.bigquery.storage.v1.DataFormat;
import com.google.cloud.bigquery.storage.v1.ReadRowsRequest;
import com.google.cloud.bigquery.storage.v1.ReadRowsResponse;
import com.google.cloud.bigquery.storage.v1.ReadSession;
import com.google.cloud.bigquery.storage.v1.SplitReadStreamRequest;
import com.google.cloud.bigquery.storage.v1.SplitReadStreamResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Storage Read API over gRPC on the shared port, in mock mode (AVRO needs no SQL engine for a
 * plain table scan). The table lives outside the default project, which proves sessions read the
 * table's own project.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BigQueryReadGrpcIntegrationTest {

    private static final String PROJECT = "bq-read-it";
    private static final String TABLE = "projects/" + PROJECT + "/datasets/reads/tables/people";

    @TestHTTPResource
    URI endpoint;

    private ManagedChannel channel;
    private BigQueryReadGrpc.BigQueryReadBlockingStub read;

    @BeforeEach
    void connect() {
        channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
        read = BigQueryReadGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void close() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    private ReadSession session(DataFormat format, String restriction) {
        ReadSession.Builder requested = ReadSession.newBuilder().setTable(TABLE).setDataFormat(format);
        if (restriction != null) {
            requested.getReadOptionsBuilder().setRowRestriction(restriction);
        }
        return read.createReadSession(CreateReadSessionRequest.newBuilder()
                .setParent("projects/" + PROJECT).setReadSession(requested).setMaxStreamCount(4).build());
    }

    private List<ReadRowsResponse> readAll(String stream, long offset) {
        List<ReadRowsResponse> responses = new ArrayList<>();
        read.readRows(ReadRowsRequest.newBuilder().setReadStream(stream).setOffset(offset).build())
                .forEachRemaining(responses::add);
        return responses;
    }

    @Test
    @Order(1)
    void seed() {
        String base = "/bigquery/v2/projects/" + PROJECT;
        given().contentType("application/json").body("{\"datasetReference\": {\"datasetId\": \"reads\"}}")
                .when().post(base + "/datasets").then().statusCode(200);
        given().contentType("application/json").body("""
                {"tableReference": {"tableId": "people"}, "schema": {"fields": [
                  {"name": "name", "type": "STRING"}, {"name": "age", "type": "INT64"}]}}
                """).when().post(base + "/datasets/reads/tables").then().statusCode(200);
        StringBuilder rows = new StringBuilder("{\"rows\": [");
        for (int i = 0; i < 1500; i++) {
            rows.append(i > 0 ? "," : "").append("{\"json\": {\"name\": \"p").append(i).append("\", \"age\": ")
                    .append(i % 50).append("}}");
        }
        given().contentType("application/json").body(rows.append("]}").toString())
                .when().post(base + "/datasets/reads/tables/people/insertAll").then().statusCode(200);
        given().contentType("application/json").body("{\"view\": {\"query\": \"SELECT 1 AS x\"},"
                        + " \"tableReference\": {\"tableId\": \"v\"}}")
                .when().post(base + "/datasets/reads/tables").then().statusCode(200);
    }

    @Test
    @Order(2)
    void avroSessionStreamsEveryRowInBlocks() {
        ReadSession session = session(DataFormat.AVRO, null);
        assertTrue(session.getName().startsWith("projects/" + PROJECT + "/locations/us/sessions/"), session.getName());
        assertEquals(1, session.getStreamsCount());
        assertEquals(1500, session.getEstimatedRowCount());
        assertTrue(session.getAvroSchema().getSchema().contains("\"name\":\"age\""));
        assertTrue(session.getExpireTime().getSeconds() > System.currentTimeMillis() / 1000);

        List<ReadRowsResponse> responses = readAll(session.getStreams(0).getName(), 0);
        assertEquals(2, responses.size());
        assertEquals(1500, responses.stream().mapToLong(ReadRowsResponse::getRowCount).sum());
        assertTrue(responses.get(0).hasAvroSchema());
        assertFalse(responses.get(1).hasAvroSchema());
        assertEquals(1.0, responses.get(1).getStats().getProgress().getAtResponseEnd(), 1e-9);

        // Resuming from an offset skips the rows already read.
        List<ReadRowsResponse> resumed = readAll(session.getStreams(0).getName(), 1200);
        assertEquals(300, resumed.stream().mapToLong(ReadRowsResponse::getRowCount).sum());
    }

    @Test
    @Order(3)
    void rowRestrictionFiltersTheSnapshot() {
        ReadSession session = session(DataFormat.AVRO, "age = 7");
        assertEquals(30, session.getEstimatedRowCount());
        byte[] rows = readAll(session.getStreams(0).getName(), 0).get(0).getAvroRows()
                .getSerializedBinaryRows().toByteArray();
        // First record: union branch 1, string "p7".
        assertEquals(0x02, rows[0]);
        assertEquals("p7", new String(rows, 2, rows[1] / 2));
    }

    @Test
    @Order(4)
    void splitReadStreamCannotSplitTheSingleStream() {
        ReadSession session = session(DataFormat.AVRO, null);
        SplitReadStreamResponse split = read.splitReadStream(SplitReadStreamRequest.newBuilder()
                .setName(session.getStreams(0).getName()).setFraction(0.5).build());
        assertFalse(split.hasPrimaryStream());
        assertFalse(split.hasRemainderStream());
    }

    @Test
    @Order(5)
    void errorsMapToGrpcCodes() {
        assertEquals(Status.Code.FAILED_PRECONDITION,
                assertThrows(StatusRuntimeException.class, () -> session(DataFormat.ARROW, null)).getStatus().getCode(),
                "ARROW needs the DuckDB engine, which mock mode does not run");
        assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class,
                () -> session(DataFormat.DATA_FORMAT_UNSPECIFIED, null)).getStatus().getCode());
        assertEquals(Status.Code.NOT_FOUND, assertThrows(StatusRuntimeException.class,
                () -> read.createReadSession(CreateReadSessionRequest.newBuilder().setParent("projects/" + PROJECT)
                        .setReadSession(ReadSession.newBuilder().setDataFormat(DataFormat.AVRO)
                                .setTable("projects/" + PROJECT + "/datasets/reads/tables/missing")).build()))
                .getStatus().getCode());
        assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class,
                () -> read.createReadSession(CreateReadSessionRequest.newBuilder().setParent("projects/" + PROJECT)
                        .setReadSession(ReadSession.newBuilder().setDataFormat(DataFormat.AVRO)
                                .setTable("projects/" + PROJECT + "/datasets/reads/tables/v")).build()))
                .getStatus().getCode());
        assertEquals(Status.Code.NOT_FOUND, assertThrows(StatusRuntimeException.class,
                () -> readAll("projects/" + PROJECT + "/locations/us/sessions/nope/streams/0", 0))
                .getStatus().getCode());
    }
}
