package io.floci.gcp.test;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.DatasetInfo;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.StandardSQLTypeName;
import com.google.cloud.bigquery.StandardTableDefinition;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableInfo;
import com.google.cloud.bigquery.TableResult;
import com.google.cloud.bigquery.storage.v1.AppendRowsResponse;
import com.google.cloud.bigquery.storage.v1.BatchCommitWriteStreamsRequest;
import com.google.cloud.bigquery.storage.v1.BatchCommitWriteStreamsResponse;
import com.google.cloud.bigquery.storage.v1.BigQueryReadClient;
import com.google.cloud.bigquery.storage.v1.BigQueryWriteClient;
import com.google.cloud.bigquery.storage.v1.CreateReadSessionRequest;
import com.google.cloud.bigquery.storage.v1.CreateWriteStreamRequest;
import com.google.cloud.bigquery.storage.v1.DataFormat;
import com.google.cloud.bigquery.storage.v1.Exceptions;
import com.google.cloud.bigquery.storage.v1.JsonStreamWriter;
import com.google.cloud.bigquery.storage.v1.ReadRowsRequest;
import com.google.cloud.bigquery.storage.v1.ReadRowsResponse;
import com.google.cloud.bigquery.storage.v1.ReadSession;
import com.google.cloud.bigquery.storage.v1.TableName;
import com.google.cloud.bigquery.storage.v1.WriteStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates the BigQuery Storage Write and Read APIs against the real
 * {@code google-cloud-bigquerystorage} clients: {@code JsonStreamWriter} on the default stream
 * (which fetches the table schema with GetWriteStream and converts JSON to proto rows), a PENDING
 * stream committed with BatchCommitWriteStreams, offset checks, and a read session reading the
 * rows back.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BigQueryStorageTest {

    private static final String PROJECT_ID = TestFixtures.projectId();
    private static final String DATASET = TestFixtures.uniqueName("storage").replace("-", "_");
    private static final String TABLE = "events";
    private static final String TABLE_NAME = TableName.of(PROJECT_ID, DATASET, TABLE).toString();

    private static BigQuery bigquery;
    private static BigQueryWriteClient writeClient;
    private static BigQueryReadClient readClient;

    @BeforeAll
    static void setUp() throws Exception {
        bigquery = TestFixtures.bigQueryClient();
        writeClient = TestFixtures.bigQueryWriteClient();
        readClient = TestFixtures.bigQueryReadClient();
        bigquery.create(DatasetInfo.newBuilder(DATASET).setLocation("US").build());
        Schema schema = Schema.of(
                Field.newBuilder("name", StandardSQLTypeName.STRING).setMode(Field.Mode.REQUIRED).build(),
                Field.of("amount", StandardSQLTypeName.NUMERIC),
                Field.of("happened_at", StandardSQLTypeName.TIMESTAMP),
                Field.of("day", StandardSQLTypeName.DATE),
                Field.of("local", StandardSQLTypeName.DATETIME),
                Field.newBuilder("tags", StandardSQLTypeName.STRING).setMode(Field.Mode.REPEATED).build());
        bigquery.create(TableInfo.of(TableId.of(DATASET, TABLE), StandardTableDefinition.of(schema)));
    }

    @AfterAll
    static void tearDown() {
        bigquery.delete(DATASET, BigQuery.DatasetDeleteOption.deleteContents());
        writeClient.close();
        readClient.close();
    }

    private static JSONObject row(String name, String amount) {
        return new JSONObject()
                .put("name", name)
                .put("amount", amount)
                .put("happened_at", "2024-05-06T07:08:09.123456Z")
                .put("day", "2024-05-06")
                .put("local", "2024-05-06T07:08:09.5")
                .put("tags", new JSONArray().put("x").put("y"));
    }

    private static List<String> names() throws InterruptedException {
        TableResult result = bigquery.query(QueryJobConfiguration.of(
                "SELECT name FROM `" + DATASET + "." + TABLE + "` ORDER BY name"));
        List<String> names = new ArrayList<>();
        for (FieldValueList row : result.iterateAll()) {
            names.add(row.get("name").getStringValue());
        }
        return names;
    }

    @Test
    @Order(1)
    void jsonStreamWriterAppendsToTheDefaultStream() throws Exception {
        try (JsonStreamWriter writer = JsonStreamWriter.newBuilder(TABLE_NAME, writeClient).build()) {
            AppendRowsResponse response = writer.append(new JSONArray().put(row("ada", "12.5")).put(row("bob", "-3")))
                    .get();
            assertThat(response.hasError()).isFalse();
        }
        TableResult result = bigquery.query(QueryJobConfiguration.of(
                "SELECT amount, happened_at, day, local, tags FROM `" + DATASET + "." + TABLE + "` WHERE name = 'ada'"));
        FieldValueList ada = result.iterateAll().iterator().next();
        assertThat(ada.get("amount").getNumericValue()).isEqualByComparingTo("12.5");
        assertThat(ada.get("happened_at").getTimestampValue()).isEqualTo(1714979289123456L);
        assertThat(ada.get("day").getStringValue()).isEqualTo("2024-05-06");
        assertThat(LocalDateTime.parse(ada.get("local").getStringValue()))
                .isEqualTo(LocalDateTime.of(2024, 5, 6, 7, 8, 9, 500_000_000));
        assertThat(ada.get("tags").getRepeatedValue()).hasSize(2);
    }

    @Test
    @Order(2)
    void pendingStreamCommitsWithBatchCommit() throws Exception {
        WriteStream stream = writeClient.createWriteStream(CreateWriteStreamRequest.newBuilder()
                .setParent(TABLE_NAME)
                .setWriteStream(WriteStream.newBuilder().setType(WriteStream.Type.PENDING))
                .build());
        try (JsonStreamWriter writer = JsonStreamWriter.newBuilder(stream.getName(), stream.getTableSchema(),
                writeClient).build()) {
            assertThat(writer.append(new JSONArray().put(row("cid", "1")), 0).get().getAppendResult()
                    .getOffset().getValue()).isZero();
            assertThat(writer.append(new JSONArray().put(row("dee", "2")), 1).get().getAppendResult()
                    .getOffset().getValue()).isEqualTo(1);
            assertThatThrownBy(() -> writer.append(new JSONArray().put(row("eve", "3")), 0).get())
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(Exceptions.OffsetAlreadyExists.class);
        }
        assertThat(names()).containsExactly("ada", "bob");

        assertThat(writeClient.finalizeWriteStream(stream.getName()).getRowCount()).isEqualTo(2);
        BatchCommitWriteStreamsResponse commit = writeClient.batchCommitWriteStreams(
                BatchCommitWriteStreamsRequest.newBuilder().setParent(TABLE_NAME)
                        .addWriteStreams(stream.getName()).build());
        assertThat(commit.hasCommitTime()).isTrue();
        assertThat(names()).containsExactly("ada", "bob", "cid", "dee");
    }

    @Test
    @Order(3)
    void readClientReadsTheWrittenRows() {
        ReadSession session = readClient.createReadSession(CreateReadSessionRequest.newBuilder()
                .setParent("projects/" + PROJECT_ID)
                .setReadSession(ReadSession.newBuilder().setTable(TABLE_NAME).setDataFormat(DataFormat.AVRO))
                .setMaxStreamCount(1)
                .build());
        long rows = 0;
        for (ReadRowsResponse response : readClient.readRowsCallable().call(
                ReadRowsRequest.newBuilder().setReadStream(session.getStreams(0).getName()).build())) {
            rows += response.getRowCount();
        }
        assertThat(rows).isEqualTo(4);
    }
}
