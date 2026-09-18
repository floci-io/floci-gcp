package io.floci.gcp.services.bigquery;

import com.google.cloud.bigquery.storage.v1.ArrowRecordBatch;
import com.google.cloud.bigquery.storage.v1.ArrowSchema;
import com.google.cloud.bigquery.storage.v1.AvroRows;
import com.google.cloud.bigquery.storage.v1.AvroSchema;
import com.google.cloud.bigquery.storage.v1.CreateReadSessionRequest;
import com.google.cloud.bigquery.storage.v1.DataFormat;
import com.google.cloud.bigquery.storage.v1.ReadRowsRequest;
import com.google.cloud.bigquery.storage.v1.ReadRowsResponse;
import com.google.cloud.bigquery.storage.v1.ReadSession;
import com.google.cloud.bigquery.storage.v1.ReadStream;
import com.google.cloud.bigquery.storage.v1.StreamStats;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.lifecycle.GrpcServerManager;
import io.floci.gcp.services.bigquery.model.Dataset;
import io.floci.gcp.services.bigquery.model.Table;
import io.floci.gcp.services.bigquery.model.TableFieldSchema;
import io.grpc.stub.StreamObserver;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BigQuery Storage Read API ({@code google.cloud.bigquery.storage.v1.BigQueryRead}). A read session
 * snapshots the selected columns and rows of a table when it is created (running
 * {@code row_restriction} through the SQL engine) and serves them from one stream, as Arrow
 * record batches or Avro row blocks.
 */
@ApplicationScoped
public class BigQueryStorageRead {

    private static final Pattern TABLE = Pattern.compile("projects/([^/]+)/datasets/([^/]+)/tables/([^/]+)");
    private static final Pattern PARENT = Pattern.compile("projects/([^/]+)");
    private static final Pattern STREAM = Pattern.compile("(projects/[^/]+/locations/[^/]+/sessions/[^/]+)/streams/[^/]+");
    private static final Duration SESSION_LIFETIME = Duration.ofHours(6);
    private static final int AVRO_ROWS_PER_BLOCK = 1000;

    /** One ReadRows block: the serialized rows and how many rows they hold. */
    private record Block(ByteString data, long rowCount) {}

    private record Session(ReadSession resource, List<Block> arrowBlocks, List<TableFieldSchema> avroFields,
                           List<Map<String, Object>> avroRows, long totalRows, Instant expireTime) {}

    private final BigQueryService service;
    private final GrpcServerManager grpcServerManager;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Inject
    public BigQueryStorageRead(BigQueryService service, GrpcServerManager grpcServerManager) {
        this.service = service;
        this.grpcServerManager = grpcServerManager;
    }

    void onStart(@Observes StartupEvent event) {
        grpcServerManager.bind(new BigQueryReadController(this));
    }

    /** The project whose storage a request reads: the table's project, else the parent's. */
    static String projectOf(CreateReadSessionRequest request) {
        Matcher table = TABLE.matcher(request.getReadSession().getTable());
        if (table.matches()) {
            return table.group(1);
        }
        Matcher parent = PARENT.matcher(request.getParent());
        return parent.matches() ? parent.group(1) : null;
    }

    public ReadSession createReadSession(CreateReadSessionRequest request) {
        if (!PARENT.matcher(request.getParent()).matches()) {
            throw GcpException.invalidArgument("parent must be of the form projects/{project_id}");
        }
        ReadSession requested = request.getReadSession();
        Matcher tableName = TABLE.matcher(requested.getTable());
        if (!tableName.matches()) {
            throw GcpException.invalidArgument(
                    "read_session.table must be of the form projects/{project}/datasets/{dataset}/tables/{table}");
        }
        DataFormat format = requested.getDataFormat();
        if (format != DataFormat.ARROW && format != DataFormat.AVRO) {
            throw GcpException.invalidArgument("DATA_FORMAT_UNSPECIFIED not supported; use AVRO or ARROW");
        }
        String projectId = tableName.group(1);
        String datasetId = tableName.group(2);
        String tableId = tableName.group(3);
        Table table = service.getTable(projectId, datasetId, tableId);
        if (table.viewQuery() != null) {
            throw GcpException.invalidArgument("Reading views with the Storage Read API is not supported: "
                    + requested.getTable());
        }

        List<TableFieldSchema> fields = selectedFields(table, requested.getReadOptions().getSelectedFieldsList());
        String sql = selectSql(projectId, datasetId, tableId, fields,
                requested.getReadOptions().getSelectedFieldsCount() > 0,
                requested.getReadOptions().getRowRestriction());

        String location = location(projectId, datasetId);
        String sessionName = "projects/" + projectId + "/locations/" + location + "/sessions/"
                + UUID.randomUUID().toString().replace("-", "");
        Instant expireTime = Instant.now().plus(SESSION_LIFETIME);
        ReadSession.Builder resource = requested.toBuilder()
                .setName(sessionName)
                .setExpireTime(Timestamp.newBuilder().setSeconds(expireTime.getEpochSecond()))
                .clearStreams()
                .clearAvroSchema()
                .clearArrowSchema()
                .addStreams(ReadStream.newBuilder().setName(sessionName + "/streams/0"));

        List<Block> arrowBlocks = List.of();
        List<Map<String, Object>> avroRows = List.of();
        long totalRows;
        long totalBytes = 0;
        if (format == DataFormat.ARROW) {
            DuckClient.ArrowIpc arrow = service.readArrow(projectId, sql);
            arrowBlocks = new ArrayList<>();
            for (DuckClient.ArrowBatch batch : arrow.batches()) {
                arrowBlocks.add(new Block(ByteString.copyFrom(batch.data()), batch.rowCount()));
                totalBytes += batch.data().length;
            }
            totalRows = arrowBlocks.stream().mapToLong(Block::rowCount).sum();
            resource.setArrowSchema(ArrowSchema.newBuilder().setSerializedSchema(ByteString.copyFrom(arrow.schema())));
        } else {
            avroRows = service.readRows(projectId, sql);
            totalRows = avroRows.size();
            totalBytes = BigQueryAvro.encode(fields, avroRows).length;
            resource.setAvroSchema(AvroSchema.newBuilder().setSchema(BigQueryAvro.schema(fields)));
        }
        resource.setEstimatedRowCount(totalRows).setEstimatedTotalBytesScanned(totalBytes);
        ReadSession session = resource.build();
        evictExpired();
        sessions.put(sessionName, new Session(session, arrowBlocks, fields, avroRows, totalRows, expireTime));
        return session;
    }

    public void readRows(ReadRowsRequest request, StreamObserver<ReadRowsResponse> observer) {
        Session session = sessionForStream(request.getReadStream());
        long offset = request.getOffset();
        if (offset < 0 || offset > session.totalRows()) {
            throw GcpException.outOfRange("offset " + offset + " is beyond the stream's " + session.totalRows()
                    + " rows");
        }
        List<Block> blocks = blocksFrom(session, offset);
        long position = offset;
        boolean first = true;
        for (Block block : blocks) {
            ReadRowsResponse.Builder response = ReadRowsResponse.newBuilder().setRowCount(block.rowCount());
            if (session.resource().getDataFormat() == DataFormat.ARROW) {
                response.setArrowRecordBatch(ArrowRecordBatch.newBuilder()
                        .setSerializedRecordBatch(block.data()).setRowCount(block.rowCount()));
                if (first) {
                    response.setArrowSchema(session.resource().getArrowSchema());
                }
            } else {
                response.setAvroRows(AvroRows.newBuilder()
                        .setSerializedBinaryRows(block.data()).setRowCount(block.rowCount()));
                if (first) {
                    response.setAvroSchema(session.resource().getAvroSchema());
                }
            }
            double total = Math.max(1, session.totalRows());
            response.setStats(StreamStats.newBuilder().setProgress(StreamStats.Progress.newBuilder()
                    .setAtResponseStart(position / total)
                    .setAtResponseEnd((position + block.rowCount()) / total)));
            position += block.rowCount();
            first = false;
            observer.onNext(response.build());
        }
        observer.onCompleted();
    }

    /** The session has a single stream, which "can no longer be split". */
    public void checkStreamExists(String streamName) {
        sessionForStream(streamName);
    }

    private List<Block> blocksFrom(Session session, long offset) {
        if (session.resource().getDataFormat() == DataFormat.AVRO) {
            List<Block> blocks = new ArrayList<>();
            List<Map<String, Object>> rows = session.avroRows();
            for (int start = (int) offset; start < rows.size(); start += AVRO_ROWS_PER_BLOCK) {
                List<Map<String, Object>> chunk = rows.subList(start, Math.min(rows.size(), start + AVRO_ROWS_PER_BLOCK));
                blocks.add(new Block(ByteString.copyFrom(BigQueryAvro.encode(session.avroFields(), chunk)),
                        chunk.size()));
            }
            return blocks;
        }
        long skipped = 0;
        int index = 0;
        List<Block> arrow = session.arrowBlocks();
        while (index < arrow.size() && skipped < offset) {
            skipped += arrow.get(index++).rowCount();
        }
        if (skipped != offset) {
            throw GcpException.invalidArgument("offset " + offset + " does not fall on a record batch boundary;"
                    + " the floci BigQuery emulator resumes ARROW streams only between batches");
        }
        return arrow.subList(index, arrow.size());
    }

    private Session sessionForStream(String streamName) {
        Matcher stream = STREAM.matcher(streamName);
        if (!stream.matches()) {
            throw GcpException.invalidArgument("Invalid read stream name " + streamName);
        }
        Session session = sessions.get(stream.group(1));
        if (session == null || !streamName.endsWith("/streams/0")) {
            throw GcpException.notFound("Not found: read stream " + streamName);
        }
        if (Instant.now().isAfter(session.expireTime())) {
            sessions.remove(stream.group(1));
            throw GcpException.notFound("Read session " + stream.group(1) + " has expired");
        }
        return session;
    }

    private void evictExpired() {
        Instant now = Instant.now();
        sessions.values().removeIf(s -> now.isAfter(s.expireTime()));
    }

    /** Selected top-level fields in table order; nested selections are not emulated. */
    private static List<TableFieldSchema> selectedFields(Table table, List<String> selected) {
        List<TableFieldSchema> all = table.getSchema() != null && table.getSchema().getFields() != null
                ? table.getSchema().getFields() : List.of();
        if (selected.isEmpty()) {
            return all;
        }
        for (String name : selected) {
            if (name.contains(".")) {
                throw GcpException.invalidArgument("Selecting nested field " + name
                        + " is not supported by the floci BigQuery emulator; select the top-level field");
            }
            if (all.stream().noneMatch(f -> f.getName().equalsIgnoreCase(name))) {
                throw GcpException.invalidArgument("Field " + name + " not found in table schema");
            }
        }
        return all.stream().filter(f -> selected.stream().anyMatch(s -> s.equalsIgnoreCase(f.getName()))).toList();
    }

    private static String selectSql(String projectId, String datasetId, String tableId, List<TableFieldSchema> fields,
                                    boolean projected, String rowRestriction) {
        String columns = projected
                ? String.join(", ", fields.stream().map(f -> "`" + f.getName() + "`").toList())
                : "*";
        String sql = "SELECT " + columns + " FROM `" + projectId + "." + datasetId + "." + tableId + "`";
        if (rowRestriction != null && !rowRestriction.isBlank()) {
            sql += " WHERE " + rowRestriction;
        }
        return sql;
    }

    private String location(String projectId, String datasetId) {
        if (BigQueryService.ANON_DATASET.equals(datasetId)) {
            return "us"; // query result tables have no dataset record
        }
        Dataset dataset = service.getDataset(projectId, datasetId);
        String location = dataset.getLocation() != null && !dataset.getLocation().isBlank() ? dataset.getLocation() : "US";
        return location.toLowerCase(Locale.ROOT);
    }
}
