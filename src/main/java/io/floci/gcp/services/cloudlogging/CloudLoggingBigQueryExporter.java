package io.floci.gcp.services.cloudlogging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.logging.v2.LogSink;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ProjectRegistry;
import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.core.common.RequestScopes;
import io.floci.gcp.services.bigquery.BigQueryService;
import io.floci.gcp.services.bigquery.model.Table;
import io.floci.gcp.services.cloudlogging.model.StoredLogEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streams routed entries into one table per log (and per UTC day, unless the sink uses
 * partitioned tables) in the destination dataset. The first entries of a table determine its
 * schema; later entries may add fields, and an entry whose field changes type goes to the
 * {@code export_errors} table instead.
 */
@ApplicationScoped
public class CloudLoggingBigQueryExporter {
    private static final Logger LOG = Logger.getLogger(CloudLoggingBigQueryExporter.class);
    static final String DESTINATION_PREFIX = "bigquery.googleapis.com/";
    private static final Pattern DATASET = Pattern.compile("bigquery\\.googleapis\\.com/projects/([^/]+)/datasets/([^/]+)");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final BigQueryService bigquery;
    private final SinkRouting routing;
    private final EmulatorConfig config;
    private final ObjectMapper json;

    @Inject
    public CloudLoggingBigQueryExporter(BigQueryService bigquery, ProjectRegistry projects, EmulatorConfig config,
            ObjectMapper json) {
        this.bigquery = bigquery;
        this.routing = new SinkRouting(projects);
        this.config = config;
        this.json = json;
    }

    public void export(String parent, LogSink sink, List<StoredLogEntry> entries) {
        List<StoredLogEntry> routed = routing.select(parent, sink, entries);
        if (routed.isEmpty()) {
            return;
        }
        if (!config.services().bigquery().enabled()) {
            throw GcpException.failedPrecondition("BigQuery is disabled");
        }
        Matcher destination = DATASET.matcher(sink.getDestination());
        if (!destination.matches()) {
            throw GcpException.invalidArgument("Invalid BigQuery sink destination: " + sink.getDestination());
        }
        String project = destination.group(1);
        String dataset = destination.group(2);
        boolean partitioned = sink.getBigqueryOptions().getUsePartitionedTables();
        RequestScopes.runAs(project, () -> {
            bigquery.getDataset(project, dataset);
            Map<String, List<StoredLogEntry>> byTable = new LinkedHashMap<>();
            for (StoredLogEntry entry : routed) {
                byTable.computeIfAbsent(BigQueryLogRows.tableId(entry, partitioned), ignored -> new ArrayList<>()).add(entry);
            }
            List<Map<String, Object>> failures = new ArrayList<>();
            List<StoredLogEntry> failed = new ArrayList<>();
            byTable.forEach((table, tableEntries) -> write(project, dataset, table, partitioned, sink,
                    tableEntries, failures, failed));
            if (!failed.isEmpty()) {
                writeErrors(project, dataset, partitioned, failed, failures);
            }
        });
    }

    private void write(String project, String dataset, String tableId, boolean partitioned, LogSink sink,
            List<StoredLogEntry> entries, List<Map<String, Object>> failures, List<StoredLogEntry> failed) {
        List<Map<String, Object>> schema = existingSchema(project, dataset, tableId).orElse(null);
        List<BigQueryService.InsertRow> rows = new ArrayList<>();
        List<Map<String, Object>> grown = schema;
        for (StoredLogEntry entry : entries) {
            Map<String, Object> row = BigQueryLogRows.row(entry);
            StringBuilder conflict = new StringBuilder();
            Optional<List<Map<String, Object>>> merged = BigQueryLogRows.schema(row, conflict);
            if (merged.isPresent() && grown != null) {
                merged = BigQueryLogRows.merge(grown, merged.get(), conflict, "");
            }
            if (merged.isEmpty()) {
                failed.add(entry);
                failures.add(errorRow(sink, entry, conflict.toString()));
                continue;
            }
            grown = merged.get();
            rows.add(new BigQueryService.InsertRow(rows.size(), row));
        }
        if (rows.isEmpty()) {
            return;
        }
        if (schema == null) {
            createTable(project, dataset, tableId, grown, partitioned);
        } else if (!grown.equals(schema)) {
            bigquery.patchTable(project, dataset, tableId, table(Map.of("schema", Map.of("fields", grown))));
        }
        List<Map<String, Object>> errors = bigquery.insertAll(project, dataset, tableId, rows, true, false);
        if (!errors.isEmpty()) {
            LOG.warnf("Cloud Logging sink %s could not insert %d rows into %s.%s: %s", sink.getName(), errors.size(),
                    dataset, tableId, errors);
        }
    }

    private void writeErrors(String project, String dataset, boolean partitioned, List<StoredLogEntry> entries,
            List<Map<String, Object>> rows) {
        Map<String, List<BigQueryService.InsertRow>> byTable = new LinkedHashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            List<BigQueryService.InsertRow> table = byTable.computeIfAbsent(
                    BigQueryLogRows.errorsTableId(entries.get(i), partitioned), ignored -> new ArrayList<>());
            table.add(new BigQueryService.InsertRow(table.size(), rows.get(i)));
        }
        byTable.forEach((tableId, tableRows) -> {
            if (existingSchema(project, dataset, tableId).isEmpty()) {
                createTable(project, dataset, tableId, BigQueryLogRows.errorsSchema(), partitioned);
            }
            bigquery.insertAll(project, dataset, tableId, tableRows, true, false);
        });
    }

    private Map<String, Object> errorRow(LogSink sink, StoredLogEntry entry, String detail) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("logEntry", ProtoJson.print(CloudLoggingController.toProto(entry)));
        row.put("schemaErrorDetail", detail);
        row.put("sink", sink.getName());
        row.put("logName", entry.getLogName());
        row.put("timestamp", entry.getTimestamp());
        row.put("receiveTimestamp", entry.getReceiveTimestamp());
        row.put("severity", entry.getSeverity() == null ? "DEFAULT" : entry.getSeverity());
        row.put("insertId", entry.getInsertId());
        row.put("trace", entry.getTrace());
        row.put("resourceType", entry.getResourceType());
        return row;
    }

    private void createTable(String project, String dataset, String tableId, List<Map<String, Object>> schema,
            boolean partitioned) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tableReference", Map.of("projectId", project, "datasetId", dataset, "tableId", tableId));
        body.put("schema", Map.of("fields", schema));
        if (partitioned) {
            body.put("timePartitioning", Map.of("type", "DAY", "field", "timestamp"));
        }
        bigquery.createTable(project, dataset, table(body));
    }

    @SuppressWarnings("unchecked")
    private Optional<List<Map<String, Object>>> existingSchema(String project, String dataset, String tableId) {
        Table table;
        try {
            table = bigquery.getTable(project, dataset, tableId);
        } catch (GcpException e) {
            if (e.getHttpStatus() == 404) {
                return Optional.empty();
            }
            throw e;
        }
        Map<String, Object> schema = table.getSchema() == null ? Map.of() : json.convertValue(table.getSchema(), MAP);
        Object fields = schema.get("fields");
        return Optional.of(fields instanceof List<?> list ? normalize((List<Map<String, Object>>) list) : List.of());
    }

    /** Drops properties the model fills in that the inferred schema never sets, so equality means "unchanged". */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> normalize(List<Map<String, Object>> fields) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> field : fields) {
            Map<String, Object> copy = new LinkedHashMap<>();
            copy.put("name", field.get("name"));
            copy.put("type", field.get("type"));
            copy.put("mode", field.get("mode") == null ? "NULLABLE" : field.get("mode"));
            if (field.get("fields") instanceof List<?> nested && !nested.isEmpty()) {
                copy.put("fields", normalize((List<Map<String, Object>>) nested));
            }
            result.add(copy);
        }
        return result;
    }

    private Table table(Map<String, Object> body) {
        try {
            return json.readValue(json.writeValueAsString(body), Table.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable BigQuery table body", e);
        }
    }
}
