package io.floci.gcp.services.bigquery;

import com.google.cloud.bigquery.storage.v1.BigQueryReadGrpc;
import com.google.cloud.bigquery.storage.v1.CreateReadSessionRequest;
import com.google.cloud.bigquery.storage.v1.DataFormat;
import com.google.cloud.bigquery.storage.v1.ReadRowsRequest;
import com.google.cloud.bigquery.storage.v1.ReadRowsResponse;
import com.google.cloud.bigquery.storage.v1.ReadSession;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ARROW read sessions end to end on the real floci-duck sidecar. Needs a floci-duck image that
 * returns Arrow IPC, which the default image does, so this runs whenever Docker is available.
 * {@code -Dfloci.duck.image=...} selects another image.
 */
@QuarkusTest
@TestProfile(BigQueryDuckStorageReadIntegrationTest.ReadProfile.class)
@EnabledIf("io.floci.gcp.services.bigquery.BigQueryDuckIntegrationTest#dockerAvailable")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BigQueryDuckStorageReadIntegrationTest {

    private static final String PROJECT = "bq-read-duck-it";
    private static final String BASE = "/bigquery/v2/projects/" + PROJECT;
    private static final byte[] CONTINUATION = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

    public static class ReadProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-gcp.services.bigquery.mock", "false",
                    "floci-gcp.services.bigquery.duck.default-image",
                    System.getProperty("floci.duck.image", "floci/floci-duck:latest"),
                    "quarkus.http.test-port", "18592",
                    "floci-gcp.port", "18592",
                    "floci-gcp.docker.resource-namespace", "bq-read-duck-it");
        }
    }

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

    private static void query(String sql) {
        given().contentType("application/json").body(Map.of("query", sql, "useLegacySql", false))
                .when().post(BASE + "/queries").then().statusCode(200);
    }

    private ReadSession arrowSession(String table, List<String> fields, String restriction) {
        ReadSession.Builder requested = ReadSession.newBuilder()
                .setTable("projects/" + PROJECT + "/datasets/" + table).setDataFormat(DataFormat.ARROW);
        requested.getReadOptionsBuilder().addAllSelectedFields(fields);
        if (restriction != null) {
            requested.getReadOptionsBuilder().setRowRestriction(restriction);
        }
        return read.createReadSession(CreateReadSessionRequest.newBuilder().setParent("projects/" + PROJECT)
                .setReadSession(requested).build());
    }

    private List<ReadRowsResponse> readAll(String stream, long offset) {
        List<ReadRowsResponse> responses = new ArrayList<>();
        read.readRows(ReadRowsRequest.newBuilder().setReadStream(stream).setOffset(offset).build())
                .forEachRemaining(responses::add);
        return responses;
    }

    private static byte[] prefix(com.google.protobuf.ByteString bytes) {
        return bytes.substring(0, 4).toByteArray();
    }

    @Test
    @Order(1)
    void seed() {
        query("CREATE SCHEMA shop");
        query("CREATE TABLE shop.items AS SELECT n AS id, CONCAT('item-', CAST(n AS STRING)) AS name,"
                + " CAST(n AS NUMERIC) / 4 AS price FROM UNNEST(GENERATE_ARRAY(1, 5000)) AS n");
    }

    @Test
    @Order(2)
    void arrowSessionReturnsIpcMessages() {
        ReadSession session = arrowSession("shop/tables/items", List.of("name", "id"), "id <= 3000 AND name LIKE 'item-1%'");
        assertArrayEquals(CONTINUATION, prefix(session.getArrowSchema().getSerializedSchema()));
        // item-1, item-10..19, item-100..199, item-1000..1999
        assertEquals(1 + 10 + 100 + 1000, session.getEstimatedRowCount());

        List<ReadRowsResponse> responses = readAll(session.getStreams(0).getName(), 0);
        assertEquals(1111, responses.stream().mapToLong(ReadRowsResponse::getRowCount).sum());
        assertTrue(responses.get(0).hasArrowSchema());
        for (ReadRowsResponse response : responses) {
            assertArrayEquals(CONTINUATION, prefix(response.getArrowRecordBatch().getSerializedRecordBatch()));
        }
    }

    @Test
    @Order(3)
    void arrowOffsetsResumeOnlyBetweenBatches() {
        ReadSession session = arrowSession("shop/tables/items", List.of(), null);
        List<ReadRowsResponse> all = readAll(session.getStreams(0).getName(), 0);
        assertEquals(5000, all.stream().mapToLong(ReadRowsResponse::getRowCount).sum());
        long firstBatch = all.get(0).getRowCount();
        assertEquals(5000 - firstBatch, readAll(session.getStreams(0).getName(), firstBatch).stream()
                .mapToLong(ReadRowsResponse::getRowCount).sum());
        assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class,
                () -> readAll(session.getStreams(0).getName(), 1)).getStatus().getCode());
    }

    @Test
    @Order(4)
    void queryResultTablesAreReadable() {
        String jobId = given().contentType("application/json")
                .body(Map.of("query", "SELECT COUNT(*) AS n FROM shop.items", "useLegacySql", false))
                .when().post(BASE + "/queries").then().statusCode(200)
                .extract().jsonPath().getString("jobReference.jobId");
        String destination = given().when().get(BASE + "/jobs/" + jobId).then().statusCode(200)
                .extract().jsonPath().getString("configuration.query.destinationTable.tableId");
        ReadSession session = arrowSession("_floci_anon/tables/" + destination, List.of(), null);
        assertEquals(1, session.getEstimatedRowCount());
    }
}
