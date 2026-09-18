package io.floci.gcp.services.bigquery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.core.common.GcpException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for the floci-duck {@code /query} endpoint. Every call runs in a fresh in-memory
 * DuckDB connection: {@code setupSql} (a batch) runs first, then the single {@code sql}
 * statement whose rows are returned as ordered column-to-value maps. Requests ask for
 * lossless {@code typed_values}; floci-duck releases that report result {@code columns}
 * honor it, older ones ignore it and return no columns.
 */
@ApplicationScoped
public class DuckClient {

    /** A SQL error reported by DuckDB, as opposed to the sidecar being unreachable. */
    public static final class DuckSqlException extends RuntimeException {
        public DuckSqlException(String message) {
            super(message);
        }
    }

    /** A result column as floci-duck reports it: name plus DuckDB SQL type. */
    public record DuckColumn(String name, String type) {}

    /**
     * {@code columns} is null when the floci-duck image predates column reporting;
     * {@code followup} is the result of the follow-up statement, when one was sent and the
     * image supports it.
     */
    public record DuckResult(List<DuckColumn> columns, List<Map<String, Object>> rows, DuckResult followup) {}

    private final BigQueryDuckManager duckManager;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    @Inject
    public DuckClient(BigQueryDuckManager duckManager, ObjectMapper mapper) {
        this.duckManager = duckManager;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public DuckResult query(String sql, String setupSql, String flociEndpoint) {
        return query(sql, setupSql, flociEndpoint, null);
    }

    /** Runs {@code sql}, then {@code followupSql} (when not null) in the same DuckDB session. */
    public DuckResult query(String sql, String setupSql, String flociEndpoint, String followupSql) {
        String baseUrl = duckManager.ensureReady();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sql", sql);
        body.put("setup_sql", setupSql);
        // Required by floci-duck for its httpfs S3 settings; BigQuery stages data over plain HTTP.
        body.put("s3_endpoint", flociEndpoint);
        body.put("typed_values", true);
        if (followupSql != null) {
            body.put("followup_sql", followupSql);
        }

        String response;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/query"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw GcpException.internal("Interrupted while calling the BigQuery SQL engine");
        } catch (Exception e) {
            throw GcpException.internal("The BigQuery SQL engine (floci-duck) is unreachable: " + e.getMessage());
        }
        return parse(response);
    }

    @SuppressWarnings("unchecked")
    private DuckResult parse(String response) {
        JsonNode root;
        try {
            root = mapper.readTree(response);
        } catch (Exception e) {
            throw GcpException.internal("Unreadable response from the BigQuery SQL engine: " + e.getMessage());
        }
        if (!"success".equals(root.path("status").asText())) {
            throw new DuckSqlException(root.path("message").asText("Query failed"));
        }
        return result(root);
    }

    @SuppressWarnings("unchecked")
    private DuckResult result(JsonNode root) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode row : root.path("rows")) {
            rows.add(mapper.convertValue(row, LinkedHashMap.class));
        }
        List<DuckColumn> columns = null;
        if (root.path("columns").isArray()) {
            columns = new ArrayList<>();
            for (JsonNode column : root.path("columns")) {
                columns.add(new DuckColumn(column.path("name").asText(), column.path("type").asText()));
            }
        }
        DuckResult followup = root.path("followup").isObject() ? result(root.path("followup")) : null;
        return new DuckResult(columns, rows, followup);
    }
}
