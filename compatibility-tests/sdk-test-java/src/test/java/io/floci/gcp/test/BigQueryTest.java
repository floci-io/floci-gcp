package io.floci.gcp.test;

import com.google.cloud.NoCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.DatasetId;
import com.google.cloud.bigquery.DatasetInfo;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldValue;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.FormatOptions;
import com.google.cloud.bigquery.InsertAllRequest;
import com.google.cloud.bigquery.InsertAllResponse;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.JobStatistics;
import com.google.cloud.bigquery.LoadJobConfiguration;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.QueryParameterValue;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.StandardSQLTypeName;
import com.google.cloud.bigquery.StandardTableDefinition;
import com.google.cloud.bigquery.TableDataWriteChannel;
import com.google.cloud.bigquery.TableDefinition;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableInfo;
import com.google.cloud.bigquery.TableResult;
import com.google.cloud.bigquery.WriteChannelConfiguration;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates the BigQuery REST surface against the real {@code google-cloud-bigquery} SDK:
 * dataset/table metadata, {@code insertAll}, the {@code jobs.query} fast path, the
 * {@code jobs.insert} + {@code Job.waitFor()} + {@code Job.getQueryResults()} path (which
 * reads rows from the job's destination table), GoogleSQL on the DuckDB engine (joins,
 * aggregation, parameters, dry runs, typed columns) and error surfaces. The SDK targets the
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
        String sql = "SELECT no_such_column FROM `" + PROJECT_ID + "." + DATASET + "." + TABLE + "`";
        assertThatThrownBy(() -> bigquery.query(QueryJobConfiguration.newBuilder(sql).build()))
                .isInstanceOfSatisfying(BigQueryException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(400);
                    assertThat(e.getError().getReason()).isEqualTo("invalidQuery");
                });
    }

    @Test
    @Order(9)
    void invalidSqlViaJobReportsErrorInStatus() {
        String sql = "SELECT name FROM `" + PROJECT_ID + "." + DATASET + "." + TABLE + "` ORDER BY no_such_column";
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
    void groupByOrderByAndAnonymousColumns() throws InterruptedException {
        String sql = "SELECT active, COUNT(*), MAX(age) AS oldest FROM `" + PROJECT_ID + "." + DATASET + "."
                + TABLE + "` GROUP BY active ORDER BY oldest DESC";
        TableResult result = bigquery.query(QueryJobConfiguration.newBuilder(sql).build());

        assertThat(result.getSchema().getFields().stream().map(Field::getName).toList())
                .containsExactly("active", "f0_", "oldest");
        List<FieldValueList> rows = new ArrayList<>();
        result.iterateAll().forEach(rows::add);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("active").getBooleanValue()).isTrue();
        assertThat(rows.get(0).get("oldest").getLongValue()).isEqualTo(30L);
        assertThat(rows.get(1).get("f0_").getLongValue()).isEqualTo(1L);
    }

    @Test
    @Order(13)
    void namedAndArrayParameters() throws InterruptedException {
        String sql = "SELECT name FROM `" + PROJECT_ID + "." + DATASET + "." + TABLE
                + "` WHERE age >= @min_age AND 'admin' IN UNNEST(tags) AND name IN UNNEST(@names)";
        TableResult result = bigquery.query(QueryJobConfiguration.newBuilder(sql)
                .addNamedParameter("min_age", QueryParameterValue.int64(18))
                .addNamedParameter("names", QueryParameterValue.array(new String[] {"alice", "carol"}, String.class))
                .build());
        List<String> names = new ArrayList<>();
        result.iterateAll().forEach(row -> names.add(row.get("name").getStringValue()));
        assertThat(names).containsExactly("alice");
    }

    @Test
    @Order(14)
    void dryRunReportsSchemaWithoutRunning() {
        String sql = "SELECT name, age FROM `" + PROJECT_ID + "." + DATASET + "." + TABLE + "`";
        Job job = bigquery.create(JobInfo.of(QueryJobConfiguration.newBuilder(sql).setDryRun(true).build()));

        JobStatistics.QueryStatistics stats = job.getStatistics();
        assertThat(stats.getStatementType()).isEqualTo(JobStatistics.QueryStatistics.StatementType.SELECT);
        assertThat(stats.getSchema().getFields().stream().map(Field::getName).toList())
                .containsExactly("name", "age");
    }

    @Test
    @Order(15)
    void timestampAndRecordColumnsRoundTrip() throws InterruptedException {
        Schema schema = Schema.of(
                Field.of("id", StandardSQLTypeName.INT64),
                Field.of("occurred_at", StandardSQLTypeName.TIMESTAMP),
                Field.of("amount", StandardSQLTypeName.NUMERIC),
                Field.of("place", StandardSQLTypeName.STRUCT, Field.of("city", StandardSQLTypeName.STRING)));
        bigquery.create(TableInfo.newBuilder(TableId.of(DATASET, "events"), StandardTableDefinition.of(schema))
                .build());
        InsertAllResponse inserted = bigquery.insertAll(InsertAllRequest.newBuilder(TableId.of(DATASET, "events"))
                .addRow(Map.of("id", 1, "occurred_at", "2024-01-02T03:04:05.250Z", "amount", "12.50",
                        "place", Map.of("city", "Lima")))
                .build());
        assertThat(inserted.hasErrors()).isFalse();

        TableResult result = bigquery.query(QueryJobConfiguration.newBuilder(
                "SELECT occurred_at, TIMESTAMP_ADD(occurred_at, INTERVAL 1 HOUR) AS later, amount * 2 AS doubled, place"
                        + " FROM `" + PROJECT_ID + "." + DATASET + ".events`").build());
        FieldValueList row = result.iterateAll().iterator().next();
        assertThat(row.get("occurred_at").getTimestampInstant()).isEqualTo(Instant.parse("2024-01-02T03:04:05.250Z"));
        assertThat(row.get("later").getTimestampInstant()).isEqualTo(Instant.parse("2024-01-02T04:04:05.250Z"));
        assertThat(row.get("doubled").getNumericValue()).isEqualByComparingTo(new BigDecimal("25"));
        assertThat(row.get("place").getRecordValue().get(0).getStringValue()).isEqualTo("Lima");
    }

    @Test
    @Order(16)
    void nullCellsKeepTheirValueKey() {
        String table = "null_cells";
        Schema schema = Schema.of(Field.of("name", StandardSQLTypeName.STRING),
                Field.of("score", StandardSQLTypeName.FLOAT64));
        bigquery.create(TableInfo.of(TableId.of(DATASET, table), StandardTableDefinition.of(schema)));
        InsertAllResponse inserted = bigquery.insertAll(InsertAllRequest.newBuilder(TableId.of(DATASET, table))
                .addRow(Map.of("name", "carol"))
                .build());
        assertThat(inserted.hasErrors()).isFalse();

        // A NULL cell has to keep its "v" key: FieldValue.fromPb throws "Unexpected table cell
        // format" on a cell object carrying neither "f" nor "v", so an omitted key breaks reads
        // for the Java client, not only for Python.
        TableResult rows = bigquery.listTableData(TableId.of(DATASET, table), schema);
        FieldValueList row = rows.getValues().iterator().next();
        assertThat(row.get("name").getStringValue()).isEqualTo("carol");
        assertThat(row.get("score").isNull()).isTrue();
    }

    @Test
    @Order(17)
    void partitioningClusteringAndDefaultsRoundTrip() {
        String dataset = DATASET + "_meta";
        bigquery.create(DatasetInfo.newBuilder(dataset)
                .setDefaultTableLifetime(7_200_000L)
                .setDefaultPartitionExpirationMs(86_400_000L)
                .setDefaultCollation("und:ci")
                .build());
        com.google.cloud.bigquery.Dataset fetchedDataset = bigquery.getDataset(dataset);
        assertThat(fetchedDataset.getDefaultTableLifetime()).isEqualTo(7_200_000L);
        assertThat(fetchedDataset.getDefaultPartitionExpirationMs()).isEqualTo(86_400_000L);
        assertThat(fetchedDataset.getDefaultCollation()).isEqualTo("und:ci");

        Schema schema = Schema.of(
                Field.of("occurred_at", StandardSQLTypeName.TIMESTAMP),
                Field.of("user_id", StandardSQLTypeName.STRING));
        StandardTableDefinition definition = StandardTableDefinition.newBuilder()
                .setSchema(schema)
                .setTimePartitioning(com.google.cloud.bigquery.TimePartitioning
                        .newBuilder(com.google.cloud.bigquery.TimePartitioning.Type.DAY)
                        .setField("occurred_at").build())
                .setClustering(com.google.cloud.bigquery.Clustering.newBuilder()
                        .setFields(List.of("user_id")).build())
                .build();
        bigquery.create(TableInfo.of(TableId.of(dataset, "events"), definition));

        StandardTableDefinition fetched = bigquery.getTable(TableId.of(dataset, "events")).getDefinition();
        assertThat(fetched.getTimePartitioning().getType())
                .isEqualTo(com.google.cloud.bigquery.TimePartitioning.Type.DAY);
        assertThat(fetched.getTimePartitioning().getField()).isEqualTo("occurred_at");
        // The partition expiration is inherited from the dataset default.
        assertThat(fetched.getTimePartitioning().getExpirationMs()).isEqualTo(86_400_000L);
        assertThat(fetched.getClustering().getFields()).containsExactly("user_id");

        assertThat(bigquery.delete(DatasetId.of(PROJECT_ID, dataset),
                BigQuery.DatasetDeleteOption.deleteContents())).isTrue();
    }

    @Test
    @Order(18)
    void ddlAndDmlThroughTheSdk() throws InterruptedException {
        String items = "`" + PROJECT_ID + "." + DATASET + ".items`";
        TableResult created = bigquery.query(QueryJobConfiguration.newBuilder(
                "CREATE TABLE " + items + " (id INT64 NOT NULL, name STRING, qty INT64)").build());
        assertThat(created.getTotalRows()).isZero();
        assertThat(bigquery.getTable(TableId.of(DATASET, "items")).getDefinition().getSchema().getFields()
                .get("id").getMode()).isEqualTo(Field.Mode.REQUIRED);

        Job insert = bigquery.create(JobInfo.of(QueryJobConfiguration.newBuilder(
                "INSERT INTO " + items + " (id, name, qty) VALUES (1, 'a', 5), (2, 'b', 0), (3, 'c', 9)")
                .build())).waitFor();
        JobStatistics.QueryStatistics insertStats = insert.getStatistics();
        assertThat(insertStats.getStatementType()).isEqualTo(JobStatistics.QueryStatistics.StatementType.INSERT);
        assertThat(insertStats.getNumDmlAffectedRows()).isEqualTo(3L);
        assertThat(insertStats.getDmlStats().getInsertedRowCount()).isEqualTo(3L);

        bigquery.query(QueryJobConfiguration.newBuilder(
                "UPDATE " + items + " SET qty = qty + 1 WHERE qty > 0").build());
        bigquery.query(QueryJobConfiguration.newBuilder("DELETE " + items + " WHERE id = 2").build());

        TableResult left = bigquery.query(QueryJobConfiguration.newBuilder(
                "SELECT SUM(qty) AS total, COUNT(*) AS n FROM " + items).build());
        FieldValueList row = left.iterateAll().iterator().next();
        assertThat(row.get("total").getLongValue()).isEqualTo(16L);
        assertThat(row.get("n").getLongValue()).isEqualTo(2L);
    }

    @Test
    @Order(19)
    void viewsAndDestinationTables() throws InterruptedException {
        String items = PROJECT_ID + "." + DATASET + ".items";
        Job view = bigquery.create(JobInfo.of(QueryJobConfiguration.newBuilder(
                "CREATE VIEW `" + PROJECT_ID + "." + DATASET + ".busy` AS SELECT name FROM `" + items
                        + "` WHERE qty > 5").build())).waitFor();
        JobStatistics.QueryStatistics viewStats = view.getStatistics();
        assertThat(viewStats.getDdlOperationPerformed()).isEqualTo("CREATE");
        assertThat(bigquery.getTable(TableId.of(DATASET, "busy")).getDefinition().getType())
                .isEqualTo(TableDefinition.Type.VIEW);

        TableResult busy = bigquery.query(QueryJobConfiguration.newBuilder(
                "SELECT name FROM `" + PROJECT_ID + "." + DATASET + ".busy`").build());
        assertThat(busy.getTotalRows()).isEqualTo(2);

        TableId snapshot = TableId.of(DATASET, "snapshot");
        bigquery.create(JobInfo.of(QueryJobConfiguration.newBuilder("SELECT id, name FROM `" + items + "`")
                .setDestinationTable(snapshot).build())).waitFor();
        Job append = bigquery.create(JobInfo.of(QueryJobConfiguration.newBuilder("SELECT 42 AS id, 'z' AS name")
                .setDestinationTable(snapshot)
                .setWriteDisposition(JobInfo.WriteDisposition.WRITE_APPEND).build())).waitFor();
        assertThat(append.getStatus().getError()).isNull();
        assertThat(bigquery.getTable(snapshot).getNumRows().longValue()).isEqualTo(3L);

        // WRITE_EMPTY (the default) onto a non-empty table fails the job with "duplicate".
        Job duplicate = bigquery.create(JobInfo.of(QueryJobConfiguration.newBuilder("SELECT 1 AS id")
                .setDestinationTable(snapshot).build()));
        assertThatThrownBy(duplicate::waitFor)
                .isInstanceOfSatisfying(BigQueryException.class,
                        e -> assertThat(e.getError().getReason()).isEqualTo("duplicate"));
    }

    @Test
    @Order(18)
    void loadCsvFromCloudStorage() throws InterruptedException {
        Storage storage = TestFixtures.storageClient();
        String bucket = TestFixtures.uniqueName("bq-load");
        storage.create(BucketInfo.of(bucket));
        storage.create(BlobInfo.newBuilder(bucket, "cities/part-0.csv").build(),
                "city,population\nLima,10000000\nQuito,2800000\n".getBytes(StandardCharsets.UTF_8));
        storage.create(BlobInfo.newBuilder(bucket, "cities/part-1.csv").build(),
                "city,population\nBogota,7900000\n".getBytes(StandardCharsets.UTF_8));

        TableId cities = TableId.of(DATASET, "cities");
        Job job = bigquery.create(JobInfo.of(LoadJobConfiguration
                .newBuilder(cities, "gs://" + bucket + "/cities/part-*.csv",
                        FormatOptions.csv())
                .setAutodetect(true)
                .build())).waitFor();
        assertThat(job.getStatus().getError()).isNull();
        JobStatistics.LoadStatistics stats = job.getStatistics();
        assertThat(stats.getOutputRows()).isEqualTo(3L);
        assertThat(stats.getInputFiles()).isEqualTo(2L);

        Schema schema = bigquery.getTable(cities).getDefinition().getSchema();
        assertThat(schema.getFields().get("population").getType().getStandardType())
                .isEqualTo(StandardSQLTypeName.INT64);
        TableResult biggest = bigquery.query(QueryJobConfiguration.newBuilder(
                "SELECT city FROM `" + PROJECT_ID + "." + DATASET + ".cities` ORDER BY population DESC LIMIT 1")
                .build());
        assertThat(biggest.iterateAll().iterator().next().get("city").getStringValue()).isEqualTo("Lima");
    }

    @Test
    @Order(19)
    void loadThroughAResumableUpload() throws Exception {
        TableId target = TableId.of(DATASET, "uploaded");
        WriteChannelConfiguration config = WriteChannelConfiguration
                .newBuilder(target)
                .setFormatOptions(FormatOptions.json())
                .setSchema(Schema.of(Field.of("name", StandardSQLTypeName.STRING),
                        Field.of("score", StandardSQLTypeName.FLOAT64)))
                .build();
        JobId jobId = JobId.of(TestFixtures.uniqueName("upload"));
        TableDataWriteChannel writer = bigquery.writer(jobId, config);
        try (OutputStream out = Channels.newOutputStream(writer)) {
            out.write("{\"name\": \"ana\", \"score\": 9.5}\n{\"name\": \"bo\", \"score\": 7}\n"
                    .getBytes(StandardCharsets.UTF_8));
        }
        Job job = writer.getJob().waitFor();
        assertThat(job.getStatus().getError()).isNull();
        assertThat(((JobStatistics.LoadStatistics) job.getStatistics()).getOutputRows()).isEqualTo(2L);
        assertThat(bigquery.getTable(target).getNumRows().longValue()).isEqualTo(2L);
    }

    @Test
    @Order(20)
    void informationSchemaDescribesTheDataset() throws InterruptedException {
        TableResult columns = bigquery.query(QueryJobConfiguration.newBuilder(
                "SELECT column_name, data_type FROM `" + PROJECT_ID + "." + DATASET
                        + ".INFORMATION_SCHEMA.COLUMNS` WHERE table_name = '" + TABLE + "' ORDER BY ordinal_position")
                .build());
        List<String> described = new ArrayList<>();
        columns.iterateAll().forEach(row -> described.add(
                row.get("column_name").getStringValue() + " " + row.get("data_type").getStringValue()));
        assertThat(described).containsExactly("name STRING", "age INT64", "score FLOAT64", "active BOOL",
                "tags ARRAY<STRING>");

        TableResult tables = bigquery.query(QueryJobConfiguration.newBuilder(
                "SELECT table_name, table_type FROM `" + PROJECT_ID + "`.`region-us`.INFORMATION_SCHEMA.TABLES"
                        + " WHERE table_schema = '" + DATASET + "' AND table_name = 'busy'").build());
        assertThat(tables.iterateAll().iterator().next().get("table_type").getStringValue()).isEqualTo("VIEW");
    }

    @Test
    @Order(21)
    void jobLocationMustMatchTheDatasetLocation() throws InterruptedException {
        String dataset = DATASET + "_eu";
        bigquery.create(DatasetInfo.newBuilder(dataset).setLocation("EU").build());
        bigquery.create(TableInfo.of(TableId.of(dataset, "items"),
                StandardTableDefinition.of(Schema.of(Field.of("name", StandardSQLTypeName.STRING)))));
        bigquery.insertAll(InsertAllRequest.newBuilder(TableId.of(dataset, "items"))
                .addRow(Map.of("name", "widget")).build());
        String sql = "SELECT name FROM `" + PROJECT_ID + "." + dataset + ".items`";

        BigQuery euClient = bigquery.getOptions().toBuilder().setLocation("EU").build().getService();
        Job job = euClient.create(JobInfo.of(QueryJobConfiguration.newBuilder(sql).build())).waitFor();
        assertThat(job.getStatus().getError()).isNull();
        assertThat(job.getJobId().getLocation()).isEqualTo("EU");
        assertThat(job.getQueryResults().getTotalRows()).isEqualTo(1);

        // With no location of its own, the client gets the job run where the dataset lives.
        BigQuery anyLocation = BigQueryOptions.newBuilder()
                .setHost(bigquery.getOptions().getHost())
                .setProjectId(PROJECT_ID)
                .setCredentials(NoCredentials.getInstance())
                .build()
                .getService();
        Job inferred = anyLocation.create(JobInfo.of(QueryJobConfiguration.newBuilder(sql).build())).waitFor();
        assertThat(inferred.getStatus().getError()).isNull();
        assertThat(inferred.getJobId().getLocation()).isEqualTo("EU");
        assertThat(inferred.getQueryResults().getTotalRows()).isEqualTo(1);

        String jobId = job.getJobId().getJob();
        assertThat(bigquery.getJob(JobId.newBuilder().setJob(jobId).setLocation("EU").build())).isNotNull();
        assertThat(bigquery.getJob(JobId.newBuilder().setJob(jobId).setLocation("US").build())).isNull();

        // The shared client runs jobs in US, so the EU dataset is not found there.
        assertThatThrownBy(() -> bigquery.query(QueryJobConfiguration.newBuilder(sql).build()))
                .isInstanceOfSatisfying(BigQueryException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(404);
                    assertThat(e.getMessage()).contains("was not found in location US");
                });

        assertThat(euClient.delete(JobId.newBuilder().setJob(jobId).setLocation("EU").build())).isTrue();
        assertThat(bigquery.delete(DatasetId.of(PROJECT_ID, dataset),
                BigQuery.DatasetDeleteOption.deleteContents())).isTrue();
    }

    @Test
    void scalarOffsetUsingKeysAndSuccessivePivotsExecuteThroughTheSdk() throws InterruptedException {
        TableResult joined = bigquery.query(QueryJobConfiguration.newBuilder(
                "SELECT t.id, pos FROM (SELECT 1 AS id) t"
                        + " JOIN UNNEST([1]) id WITH OFFSET AS pos USING(id)").build());
        FieldValueList row = joined.iterateAll().iterator().next();
        assertThat(row.get("id").getLongValue()).isEqualTo(1);
        assertThat(row.get("pos").getLongValue()).isZero();
        TableResult pivoted = bigquery.query(QueryJobConfiguration.newBuilder(
                "SELECT a.id, a.x, b.y FROM"
                        + " (SELECT 1 AS id, 2 AS v, 'x' AS k) PIVOT(SUM(v) FOR k IN ('x')) a"
                        + " CROSS JOIN (SELECT 3 AS v, 'y' AS k) PIVOT(SUM(v) FOR k IN ('y')) b").build());
        assertThat(pivoted.getSchema().getFields()).extracting(Field::getName).containsExactly("id", "x", "y");
        FieldValueList pivotRow = pivoted.iterateAll().iterator().next();
        assertThat(pivotRow.get("x").getLongValue()).isEqualTo(2);
        assertThat(pivotRow.get("y").getLongValue()).isEqualTo(3);
    }

    @Test
    void leftJoinUnnestUsingPreservesRowsThroughTheSdk() throws InterruptedException {
        TableResult result = bigquery.query(QueryJobConfiguration.newBuilder(
                "SELECT id, item, pos FROM (SELECT 1 AS id UNION ALL SELECT 2 AS id) t LEFT JOIN"
                        + " UNNEST([STRUCT(1 AS id)]) item WITH OFFSET AS pos USING(id) ORDER BY id").build());
        assertThat(result.getSchema().getFields()).extracting(Field::getName).containsExactly("id", "item", "pos");
        List<FieldValueList> rows = new ArrayList<>();
        result.iterateAll().forEach(rows::add);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("id").getLongValue()).isEqualTo(1);
        assertThat(rows.get(0).get("item").getRecordValue().get("id").getLongValue()).isEqualTo(1);
        assertThat(rows.get(0).get("pos").getLongValue()).isZero();
        assertThat(rows.get(1).get("id").getLongValue()).isEqualTo(2);
        assertThat(rows.get(1).get("item").isNull()).isTrue();
        assertThat(rows.get(1).get("pos").isNull()).isTrue();
    }

    @Test
    void distinctCountIfAndImplicitPivotAliasesExecuteThroughTheSdk() throws InterruptedException {
        TableResult counts = bigquery.query(QueryJobConfiguration.newBuilder(
                "SELECT COUNTIF(DISTINCT x > 0) AS positive, COUNTIF(DISTINCT x > 10) AS absent"
                        + " FROM UNNEST([1, 2, -1, NULL]) x").build());
        FieldValueList row = counts.iterateAll().iterator().next();
        assertThat(row.get("positive").getLongValue()).isEqualTo(1);
        assertThat(row.get("absent").getLongValue()).isZero();
        TableResult pivot = bigquery.query(QueryJobConfiguration.newBuilder(
                "SELECT * FROM (SELECT 1 sample, 'one' AS metric)"
                        + " PIVOT(ANY_VALUE(sample) FOR metric IN ('one'))").build());
        assertThat(pivot.getSchema().getFields()).extracting(Field::getName).containsExactly("one");
        assertThat(pivot.iterateAll().iterator().next().get("one").getLongValue()).isEqualTo(1);
    }

    @Test
    void structOffsetsExposeFieldsAndWholeRecordsThroughTheSdk() throws InterruptedException {
        TableResult result = bigquery.query(QueryJobConfiguration.newBuilder(
                "SELECT *, item AS whole FROM UNNEST([STRUCT(1 AS x)]) AS item WITH OFFSET AS pos").build());
        assertThat(result.getSchema().getFields()).extracting(Field::getName).containsExactly("x", "pos", "whole");
        assertThat(result.getSchema().getFields().get("whole").getSubFields())
                .extracting(Field::getName).containsExactly("x");
        FieldValueList row = result.iterateAll().iterator().next();
        assertThat(row.get("x").getLongValue()).isEqualTo(1);
        assertThat(row.get("pos").getLongValue()).isZero();
        assertThat(row.get("whole").getRecordValue().get("x").getLongValue()).isEqualTo(1);
    }

    @Test
    void correlatedOffsetsAndNestedReplacementStarsKeepTheirSchemaThroughTheSdk() throws InterruptedException {
        TableResult correlated = bigquery.query(QueryJobConfiguration.newBuilder(
                "SELECT (SELECT item.x) AS x, (SELECT item) AS whole FROM"
                        + " UNNEST([STRUCT(1 AS x)]) item WITH OFFSET AS pos").build());
        FieldValueList row = correlated.iterateAll().iterator().next();
        assertThat(row.get("x").getLongValue()).isEqualTo(1);
        assertThat(row.get("whole").getRecordValue().get("x").getLongValue()).isEqualTo(1);
        assertThat(correlated.getSchema().getFields().get("whole").getSubFields())
                .extracting(Field::getName).containsExactly("x");

        TableResult replaced = bigquery.query(QueryJobConfiguration.newBuilder(
                "SELECT * FROM (SELECT * REPLACE(x + 1 AS x), item AS original FROM"
                        + " UNNEST([STRUCT(1 AS x)]) item WITH OFFSET AS pos) q").build());
        assertThat(replaced.getSchema().getFields()).extracting(Field::getName)
                .containsExactly("x", "pos", "original");
        FieldValueList replacedRow = replaced.iterateAll().iterator().next();
        assertThat(replacedRow.get("x").getLongValue()).isEqualTo(2);
        assertThat(replacedRow.get("pos").getLongValue()).isZero();
        assertThat(replacedRow.get("original").getRecordValue().get("x").getLongValue()).isEqualTo(1);
    }

    @Test
    void parsedTimestampZonesOverrideFallbackThroughTheSdk() throws InterruptedException {
        TableResult result = bigquery.query(QueryJobConfiguration.newBuilder("""
                SELECT PARSE_TIMESTAMP('%Y-%m-%d %H:%M:%S%z',
                         '2024-01-01 00:00:00+0000', 'America/Los_Angeles') AS utc,
                       SAFE.PARSE_TIMESTAMP('%Y-%m-%d %H:%M:%S%z',
                         '2024-01-01 00:00:00+0200', 'America/Los_Angeles') AS shifted,
                       PARSE_TIMESTAMP('%Y-%m-%d %H:%M:%S',
                         '2024-01-01 00:00:00', 'America/Los_Angeles') AS fallback
                """).build());
        FieldValueList row = result.iterateAll().iterator().next();
        assertThat(row.get("utc").getTimestampInstant()).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"));
        assertThat(row.get("shifted").getTimestampInstant()).isEqualTo(Instant.parse("2023-12-31T22:00:00Z"));
        assertThat(row.get("fallback").getTimestampInstant()).isEqualTo(Instant.parse("2024-01-01T08:00:00Z"));
    }

    @Test
    void safeFromHexPreservesBytesAndNullsThroughTheSdk() throws InterruptedException {
        TableResult result = bigquery.query(QueryJobConfiguration.newBuilder("""
                SELECT SAFE.FROM_HEX(hex) AS decoded
                FROM UNNEST(['4142', 'aBc', '', 'xyz', NULL]) AS hex WITH OFFSET AS position
                ORDER BY position
                """).build());
        assertThat(result.getSchema().getFields().get("decoded").getType().getStandardType())
                .isEqualTo(StandardSQLTypeName.BYTES);
        List<FieldValue> values = new ArrayList<>();
        result.iterateAll().forEach(row -> values.add(row.get("decoded")));
        assertThat(values).hasSize(5);
        assertThat(values.get(0).getBytesValue()).containsExactly("AB".getBytes(StandardCharsets.UTF_8));
        assertThat(values.get(1).getBytesValue()).containsExactly((byte) 0x0a, (byte) 0xbc);
        assertThat(values.get(2).getBytesValue()).isEmpty();
        assertThat(values.get(3).isNull()).isTrue();
        assertThat(values.get(4).isNull()).isTrue();

        TableResult parameterized = bigquery.query(QueryJobConfiguration.newBuilder(
                "SELECT TO_HEX(SAFE.FROM_HEX(@hex)) AS decoded")
                .addNamedParameter("hex", QueryParameterValue.string("fF")).build());
        assertThat(parameterized.iterateAll().iterator().next().get("decoded").getStringValue()).isEqualTo("ff");
    }

    @Test
    void safeFromHexDoesNotSuppressAnArgumentEvaluationError() {
        assertThatThrownBy(() -> bigquery.query(QueryJobConfiguration.newBuilder(
                "SELECT SAFE.FROM_HEX(CAST(ERROR('argument failed') AS STRING)) AS decoded").build()))
                .isInstanceOf(BigQueryException.class)
                .hasMessageContaining("argument failed");
    }

    @Test
    void mixedStructStarsReturnSiblingFields() throws InterruptedException {
        TableResult result = bigquery.query(QueryJobConfiguration.newBuilder(
                "SELECT AS STRUCT t.*, 2 AS y FROM (SELECT 1 AS x) t").build());
        assertThat(result.getSchema().getFields()).hasSize(1);
        Field record = result.getSchema().getFields().get(0);
        assertThat(record.getSubFields()).extracting(Field::getName).containsExactly("x", "y");
        FieldValueList value = result.iterateAll().iterator().next().get(0).getRecordValue();
        assertThat(value.get("x").getLongValue()).isEqualTo(1);
        assertThat(value.get("y").getLongValue()).isEqualTo(2);
    }

    @Test
    void mixedStructStarsPreserveImplicitReservedAliasesInNestedQueries() throws InterruptedException {
        TableResult result = bigquery.query(QueryJobConfiguration.newBuilder(
                "SELECT AS STRUCT t.*, 2 AS y FROM (SELECT 1 sample) t").build());
        Field record = result.getSchema().getFields().get(0);
        assertThat(record.getSubFields()).extracting(Field::getName).containsExactly("sample", "y");
        FieldValueList value = result.iterateAll().iterator().next().get(0).getRecordValue();
        assertThat(value.get("sample").getLongValue()).isEqualTo(1);
        assertThat(value.get("y").getLongValue()).isEqualTo(2);
    }

    @Test
    void quotedDerivedTableAliasWorksWithoutAs() throws InterruptedException {
        TableResult result = bigquery.query(QueryJobConfiguration.newBuilder(
                "SELECT `select`.x FROM (SELECT 1 AS x) `select`").build());
        assertThat(result.iterateAll().iterator().next().get("x").getLongValue()).isEqualTo(1);
    }

    @Test
    void structUnnestAliasReturnsTheRecordWhenAFieldHasTheSameName() throws InterruptedException {
        TableResult result = bigquery.query(QueryJobConfiguration.newBuilder(
                "SELECT al, al.al AS field, al.x FROM UNNEST([STRUCT(1 AS al, 2 AS x)]) al").build());
        assertThat(result.getSchema().getFields().get("al").getType().getStandardType())
                .isEqualTo(StandardSQLTypeName.STRUCT);
        FieldValueList row = result.iterateAll().iterator().next();
        assertThat(row.get("al").getRecordValue().get("al").getLongValue()).isEqualTo(1);
        assertThat(row.get("al").getRecordValue().get("x").getLongValue()).isEqualTo(2);
        assertThat(row.get("field").getLongValue()).isEqualTo(1);
        assertThat(row.get("x").getLongValue()).isEqualTo(2);
    }

    @Test
    void arrayConcatAggLimitSkipsNullArraysAndCountsEmptyArrays() throws InterruptedException {
        for (boolean emptyArray : List.of(false, true)) {
            String first = emptyArray ? "ARRAY<INT64>[]" : "[1]";
            TableResult result = bigquery.query(QueryJobConfiguration.newBuilder(
                    "SELECT ARRAY_CONCAT_AGG(items ORDER BY id LIMIT 2) AS items FROM UNNEST(["
                            + "STRUCT(0 AS id, CAST(NULL AS ARRAY<INT64>) AS items),"
                            + " STRUCT(1 AS id, " + first + " AS items),"
                            + " STRUCT(2 AS id, [2] AS items), STRUCT(3 AS id, [3] AS items)])").build());
            List<Long> items = result.iterateAll().iterator().next().get("items").getRepeatedValue()
                    .stream().map(FieldValue::getLongValue).toList();
            assertThat(items).containsExactlyElementsOf(emptyArray ? List.of(2L) : List.of(1L, 2L));
        }
    }

    @Test
    @Order(99)
    void deleteDataset() {
        boolean deleted = bigquery.delete(DatasetId.of(PROJECT_ID, DATASET),
                BigQuery.DatasetDeleteOption.deleteContents());
        assertThat(deleted).isTrue();
        assertThat(bigquery.delete(DatasetId.of(PROJECT_ID, DATASET))).isFalse();
    }
}
