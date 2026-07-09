package io.floci.gcp.test;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.DatasetId;
import com.google.cloud.bigquery.DatasetInfo;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldValue;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.InsertAllRequest;
import com.google.cloud.bigquery.InsertAllResponse;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.StandardSQLTypeName;
import com.google.cloud.bigquery.StandardTableDefinition;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableInfo;
import com.google.cloud.bigquery.TableResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates the Phase 1 BigQuery REST surface against the real {@code google-cloud-bigquery}
 * SDK: dataset/table metadata, {@code insertAll}, the {@code jobs.query} fast path, the
 * {@code jobs.insert} + {@code Job.waitFor()} + {@code Job.getQueryResults()} path (which
 * reads rows from the job's destination table), and error surfaces. The SDK targets the
 * emulator via {@code setHost} (see {@link TestFixtures#bigQueryClient()}).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BigQueryTest {

    private static final String PROJECT_ID = TestFixtures.projectId();
    private static final String DATASET = TestFixtures.uniqueName("ds").replace("-", "_");
    private static final String TABLE = "people";

    private static BigQuery bigquery;

    @BeforeAll
    static void setUp() {
        bigquery = TestFixtures.bigQueryClient();
    }

    @Test
    @Order(1)
    void createDataset() {
        com.google.cloud.bigquery.Dataset dataset =
                bigquery.create(DatasetInfo.newBuilder(DATASET).setLocation("US").build());
        assertThat(dataset.getDatasetId().getDataset()).isEqualTo(DATASET);
    }

    @Test
    @Order(2)
    void duplicateDatasetSurfacesDuplicateReason() {
        assertThatThrownBy(() -> bigquery.create(DatasetInfo.newBuilder(DATASET).build()))
                .isInstanceOfSatisfying(BigQueryException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getError().getReason()).isEqualTo("duplicate");
                });
    }

    @Test
    @Order(3)
    void createTableWithTypedSchema() {
        Schema schema = Schema.of(
                Field.of("name", StandardSQLTypeName.STRING),
                Field.of("age", StandardSQLTypeName.INT64),
                Field.of("score", StandardSQLTypeName.FLOAT64),
                Field.of("active", StandardSQLTypeName.BOOL),
                Field.newBuilder("tags", StandardSQLTypeName.STRING)
                        .setMode(Field.Mode.REPEATED).build());
        com.google.cloud.bigquery.Table table = bigquery.create(TableInfo.newBuilder(
                TableId.of(DATASET, TABLE), StandardTableDefinition.of(schema)).build());
        assertThat(table.getTableId().getTable()).isEqualTo(TABLE);

        Schema fetched = bigquery.getTable(TableId.of(DATASET, TABLE))
                .getDefinition().getSchema();
        assertThat(fetched.getFields().get("tags").getMode()).isEqualTo(Field.Mode.REPEATED);
    }

    @Test
    @Order(4)
    void insertAllRowsAndPerRowErrors() {
        InsertAllResponse ok = bigquery.insertAll(InsertAllRequest.newBuilder(TableId.of(DATASET, TABLE))
                .addRow(Map.of("name", "alice", "age", 30, "score", 9.5, "active", true,
                        "tags", List.of("admin", "dev")))
                .addRow(Map.of("name", "bob", "age", 25, "score", 7.0, "active", false,
                        "tags", List.of()))
                .build());
        assertThat(ok.hasErrors()).isFalse();

        InsertAllResponse bad = bigquery.insertAll(InsertAllRequest.newBuilder(TableId.of(DATASET, TABLE))
                .addRow(Map.of("name", "x", "bogus", 1))
                .build());
        assertThat(bad.hasErrors()).isTrue();
        assertThat(bad.getInsertErrors().get(0L).get(0).getReason()).isEqualTo("invalid");
    }

    @Test
    @Order(5)
    void selectStarReturnsTypedValues() throws InterruptedException {
        String sql = "SELECT * FROM `" + PROJECT_ID + "." + DATASET + "." + TABLE + "`";
        TableResult result = bigquery.query(QueryJobConfiguration.newBuilder(sql).build());
        assertThat(result.getTotalRows()).isEqualTo(2);

        FieldValueList alice = null;
        for (FieldValueList row : result.iterateAll()) {
            if ("alice".equals(row.get("name").getStringValue())) {
                alice = row;
            }
        }
        assertThat(alice).isNotNull();
        assertThat(alice.get("age").getLongValue()).isEqualTo(30L);
        assertThat(alice.get("score").getDoubleValue()).isEqualTo(9.5);
        assertThat(alice.get("active").getBooleanValue()).isTrue();
        assertThat(alice.get("tags").getRepeatedValue().stream()
                .map(FieldValue::getStringValue).toList())
                .containsExactly("admin", "dev");
    }

    @Test
    @Order(6)
    void whereAndCountQueries() throws InterruptedException {
        TableResult filtered = bigquery.query(QueryJobConfiguration.newBuilder(
                "SELECT name FROM `" + PROJECT_ID + "." + DATASET + "." + TABLE + "` WHERE age = 30")
                .build());
        List<String> names = new ArrayList<>();
        filtered.iterateAll().forEach(row -> names.add(row.get("name").getStringValue()));
        assertThat(names).containsExactly("alice");

        TableResult count = bigquery.query(QueryJobConfiguration.newBuilder(
                "SELECT COUNT(*) FROM `" + PROJECT_ID + "." + DATASET + "." + TABLE + "`").build());
        assertThat(count.iterateAll().iterator().next().get(0).getLongValue()).isEqualTo(2L);
    }

    @Test
    @Order(7)
    void jobInsertWaitForAndQueryResults() throws InterruptedException {
        // Exercises jobs.insert + polling + tabledata.list on the job's destination table.
        String sql = "SELECT name, age FROM `" + PROJECT_ID + "." + DATASET + "." + TABLE
                + "` WHERE active = TRUE";
        Job job = bigquery.create(JobInfo.of(QueryJobConfiguration.newBuilder(sql).build()));
        job = job.waitFor();

        assertThat(job.getStatus().getError()).isNull();
        TableResult result = job.getQueryResults();
        assertThat(result.getTotalRows()).isEqualTo(1);
        FieldValueList row = result.iterateAll().iterator().next();
        assertThat(row.get("name").getStringValue()).isEqualTo("alice");
        assertThat(row.get("age").getLongValue()).isEqualTo(30L);
    }

    @Test
    @Order(8)
    void invalidSqlViaFastPathThrowsInvalidQuery() {
        String sql = "SELECT name FROM `" + PROJECT_ID + "." + DATASET + "." + TABLE + "` GROUP BY name";
        assertThatThrownBy(() -> bigquery.query(QueryJobConfiguration.newBuilder(sql).build()))
                .isInstanceOfSatisfying(BigQueryException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(400);
                    assertThat(e.getError().getReason()).isEqualTo("invalidQuery");
                });
    }

    @Test
    @Order(9)
    void invalidSqlViaJobReportsErrorInStatus() {
        String sql = "SELECT name FROM `" + PROJECT_ID + "." + DATASET + "." + TABLE + "` ORDER BY name";
        Job job = bigquery.create(JobInfo.of(QueryJobConfiguration.newBuilder(sql).build()));

        // jobs.insert succeeds; the SQL failure lives in the job status (jobs.get)...
        Job fetched = bigquery.getJob(job.getJobId());
        assertThat(fetched.getStatus().getState().toString()).isEqualTo("DONE");
        assertThat(fetched.getStatus().getError()).isNotNull();
        assertThat(fetched.getStatus().getError().getReason()).isEqualTo("invalidQuery");

        // ...and waitFor() throws because getQueryResults returns HTTP 400 for failed jobs.
        assertThatThrownBy(job::waitFor)
                .isInstanceOfSatisfying(BigQueryException.class,
                        e -> assertThat(e.getError().getReason()).isEqualTo("invalidQuery"));
    }

    @Test
    @Order(10)
    void listTableDataPaginates() {
        TableResult page = bigquery.listTableData(TableId.of(DATASET, TABLE),
                BigQuery.TableDataListOption.pageSize(1));
        assertThat(page.getValues()).hasSize(1);

        List<FieldValueList> all = new ArrayList<>();
        page.iterateAll().forEach(all::add);
        assertThat(all).hasSize(2);
    }

    @Test
    @Order(11)
    void missingResourcesReturnNull() {
        assertThat(bigquery.getDataset("missing_dataset_xyz")).isNull();
        assertThat(bigquery.getTable(TableId.of(DATASET, "missing_table_xyz"))).isNull();
    }

    @Test
    @Order(12)
    void deleteDataset() {
        boolean deleted = bigquery.delete(DatasetId.of(PROJECT_ID, DATASET),
                BigQuery.DatasetDeleteOption.deleteContents());
        assertThat(deleted).isTrue();
        assertThat(bigquery.delete(DatasetId.of(PROJECT_ID, DATASET))).isFalse();
    }
}
