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
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.lifecycle.GrpcServerManager;
import io.floci.gcp.services.bigquery.model.Dataset;
import io.floci.gcp.services.bigquery.model.Table;
import io.floci.gcp.services.bigquery.model.TableFieldSchema;
import io.grpc.stub.StreamObserver;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
    /**
     * Sessions hold a full snapshot of what they read, so the number kept is bounded: creating one
     * past a project's bound drops that project's oldest, and past the overall bound drops the
     * oldest of the project holding the most. Real BigQuery keeps every session until it expires.
     */
    static final int MAX_SESSIONS_PER_PROJECT = 256;
    static final int MAX_SESSIONS = 1024;
    /** Keywords that would turn a row restriction into more than a predicate over one table. */
    private static final Set<String> RESTRICTION_KEYWORDS = Set.of("SELECT", "UNION", "INTERSECT", "EXCEPT", "WITH");

    /** One ReadRows block: the serialized rows and how many rows they hold. */
    private record Block(ByteString data, long rowCount) {}

    private record Session(ReadSession resource, List<Block> arrowBlocks, List<TableFieldSchema> avroFields,
                           List<Map<String, Object>> avroRows, long totalRows, Instant createTime,
                           Instant expireTime) {}

    private final BigQueryService service;
    private final GrpcServerManager grpcServerManager;
    private final EmulatorConfig config;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private final Vertx vertx;

    @Inject
    public BigQueryStorageRead(BigQueryService service, GrpcServerManager grpcServerManager, EmulatorConfig config,
                               Vertx vertx) {
        this.service = service;
        this.grpcServerManager = grpcServerManager;
        this.config = config;
        this.vertx = vertx;
    }

    void onStart(@Observes StartupEvent event) {
        if (config.services().bigquery().enabled()) {
            grpcServerManager.bind(new BigQueryReadController(this, vertx));
        }
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
        Instant createTime = Instant.now();
        Instant expireTime = createTime.plus(SESSION_LIFETIME);
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
        sessions.put(sessionName, new Session(session, arrowBlocks, fields, avroRows, totalRows, createTime,
                expireTime));
        evictOldest(projectId);
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

    private void evictOldest(String projectId) {
        evict(sessions, Session::createTime, projectId, MAX_SESSIONS_PER_PROJECT, MAX_SESSIONS);
    }

    /**
     * Drops {@code projectId}'s oldest sessions while it holds more than {@code perProject}, then,
     * while all projects together hold more than {@code total}, the oldest session of the project
     * holding the most. Ties fall on the creating project, then on the project whose oldest session
     * is newest, so projects that spread their sessions thin lose them before long-lived ones.
     */
    static <V> void evict(Map<String, V> sessions, Function<V, Instant> createTime, String projectId,
                          int perProject, int total) {
        Comparator<Map.Entry<String, V>> byAge = Comparator.comparing(e -> createTime.apply(e.getValue()));
        while (true) {
            Map<String, List<Map.Entry<String, V>>> byProject = sessions.entrySet().stream()
                    .collect(Collectors.groupingBy(e -> sessionProject(e.getKey())));
            List<Map.Entry<String, V>> victims = byProject.getOrDefault(projectId, List.of());
            if (victims.size() <= perProject) {
                if (sessions.size() <= total) {
                    return;
                }
                victims = byProject.entrySet().stream()
                        .max(Comparator.<Map.Entry<String, List<Map.Entry<String, V>>>>comparingInt(
                                        p -> p.getValue().size())
                                .thenComparing(p -> p.getKey().equals(projectId))
                                .thenComparing(p -> p.getValue().stream().min(byAge).orElseThrow(), byAge))
                        .orElseThrow()
                        .getValue();
            }
            Map.Entry<String, V> oldest = victims.stream().min(byAge).orElseThrow();
            sessions.remove(oldest.getKey(), oldest.getValue());
        }
    }

    private static String sessionProject(String sessionName) {
        return sessionName.substring("projects/".length(), sessionName.indexOf('/', "projects/".length()));
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
        if (rowRestriction == null || rowRestriction.isBlank()) {
            return sql;
        }
        checkRestrictionIsPredicate(rowRestriction);
        sql += " WHERE " + rowRestriction;
        SqlDialectTranslator.Translation translation = SqlDialectTranslator.translate(sql, projectId, null,
                SqlDialectTranslator.QueryParameters.none());
        if (!translation.tables().equals(Set.of(new SqlDialectTranslator.TableRef(datasetId, tableId)))
                || !translation.informationSchema().isEmpty()) {
            throw invalidRestriction(rowRestriction, "it may only reference columns of the table being read");
        }
        return sql;
    }

    /**
     * {@code row_restriction} is "a SQL text filtering statement, similar to a WHERE clause", so it
     * must stay one predicate: balanced parentheses, no statement separators or comments, and no
     * keyword that would add a query of its own. Quoted text is skipped.
     */
    static void checkRestrictionIsPredicate(String restriction) {
        int depth = 0;
        int i = 0;
        while (i < restriction.length()) {
            char c = restriction.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                String quote = c != '`' && restriction.startsWith(String.valueOf(c).repeat(3), i)
                        ? String.valueOf(c).repeat(3) : String.valueOf(c);
                int end = i + quote.length();
                while (end < restriction.length() && !restriction.startsWith(quote, end)) {
                    end += restriction.charAt(end) == '\\' ? 2 : 1;
                }
                if (end >= restriction.length()) {
                    throw invalidRestriction(restriction, "it has an unterminated quote");
                }
                i = end + quote.length();
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int end = i;
                while (end < restriction.length()
                        && (Character.isLetterOrDigit(restriction.charAt(end)) || restriction.charAt(end) == '_')) {
                    end++;
                }
                String word = restriction.substring(i, end).toUpperCase(Locale.ROOT);
                if (RESTRICTION_KEYWORDS.contains(word)) {
                    throw invalidRestriction(restriction, word + " is not allowed in a row restriction");
                }
                i = end;
                continue;
            }
            if (c == ';' || c == '#' || restriction.startsWith("--", i) || restriction.startsWith("/*", i)) {
                throw invalidRestriction(restriction, "statement separators and comments are not allowed");
            }
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth < 0) {
                throw invalidRestriction(restriction, "its parentheses are unbalanced");
            }
            i++;
        }
        if (depth != 0) {
            throw invalidRestriction(restriction, "its parentheses are unbalanced");
        }
    }

    private static GcpException invalidRestriction(String restriction, String reason) {
        return GcpException.invalidArgument("Invalid row_restriction \"" + restriction + "\": " + reason);
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
