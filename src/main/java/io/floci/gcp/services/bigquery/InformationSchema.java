package io.floci.gcp.services.bigquery;

import io.floci.gcp.services.bigquery.model.Dataset;
import io.floci.gcp.services.bigquery.model.Table;
import io.floci.gcp.services.bigquery.model.TableFieldSchema;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The {@code INFORMATION_SCHEMA} views floci serves, generated from dataset and table metadata:
 * SCHEMATA, TABLES, COLUMNS, COLUMN_FIELD_PATHS, TABLE_OPTIONS and VIEWS, with the columns and
 * value formats of the BigQuery documentation.
 */
final class InformationSchema {

    /** A referenced view: dataset-qualified ({@code dataset} set) or region-qualified. */
    record Ref(String view, String dataset, String region) {

        /** The DuckDB table the view is staged as. */
        String stagedName() {
            String scope = dataset != null ? "ds_" + dataset : "region_" + (region != null ? region : "us");
            return view.toLowerCase(Locale.ROOT) + "__" + scope.replaceAll("[^A-Za-z0-9_]", "_");
        }
    }

    static final String SCHEMA = "_floci_information_schema";

    private static final Map<String, List<TableFieldSchema>> VIEWS = new LinkedHashMap<>();

    static {
        VIEWS.put("SCHEMATA", fields("catalog_name STRING", "schema_name STRING", "schema_owner STRING",
                "creation_time TIMESTAMP", "last_modified_time TIMESTAMP", "location STRING", "ddl STRING",
                "default_collation_name STRING", "sync_status JSON"));
        VIEWS.put("TABLES", fields("table_catalog STRING", "table_schema STRING", "table_name STRING",
                "table_type STRING", "managed_table_type STRING", "is_insertable_into STRING",
                "is_fine_grained_mutations_enabled STRING", "is_typed STRING", "is_change_history_enabled STRING",
                "creation_time TIMESTAMP", "base_table_catalog STRING", "base_table_schema STRING",
                "base_table_name STRING", "snapshot_time_ms TIMESTAMP", "replica_source_catalog STRING",
                "replica_source_schema STRING", "replica_source_name STRING", "replication_status STRING",
                "replication_error STRING", "ddl STRING", "default_collation_name STRING", "sync_status JSON",
                "upsert_stream_apply_watermark TIMESTAMP"));
        VIEWS.put("COLUMNS", fields("table_catalog STRING", "table_schema STRING", "table_name STRING",
                "column_name STRING", "ordinal_position INTEGER", "is_nullable STRING", "data_type STRING",
                "is_generated STRING", "generation_expression STRING", "is_stored STRING", "is_hidden STRING",
                "is_updatable STRING", "is_system_defined STRING", "is_partitioning_column STRING",
                "clustering_ordinal_position INTEGER", "collation_name STRING", "column_default STRING",
                "rounding_mode STRING", "policy_tags STRING REPEATED", "is_identity STRING",
                "identity_generation STRING", "identity_start INTEGER", "identity_increment INTEGER",
                "identity_maximum INTEGER", "identity_minimum INTEGER", "identity_cycle STRING"));
        VIEWS.put("COLUMN_FIELD_PATHS", fields("table_catalog STRING", "table_schema STRING", "table_name STRING",
                "column_name STRING", "field_path STRING", "data_type STRING", "description STRING",
                "collation_name STRING", "rounding_mode STRING", "policy_tags STRING REPEATED"));
        VIEWS.put("TABLE_OPTIONS", fields("table_catalog STRING", "table_schema STRING", "table_name STRING",
                "option_name STRING", "option_type STRING", "option_value STRING"));
        VIEWS.put("VIEWS", fields("table_catalog STRING", "table_schema STRING", "table_name STRING",
                "view_definition STRING", "check_option STRING", "use_standard_sql STRING"));
    }

    private InformationSchema() {}

    static boolean isView(String name) {
        return VIEWS.containsKey(name);
    }

    /** SCHEMATA is project/region scoped; every other view needs a dataset or region qualifier. */
    static boolean regionOnly(String view) {
        return view.equals("SCHEMATA");
    }

    static List<TableFieldSchema> columns(String view) {
        return VIEWS.get(view);
    }

    private static List<TableFieldSchema> fields(String... definitions) {
        List<TableFieldSchema> fields = new ArrayList<>();
        for (String definition : definitions) {
            String[] parts = definition.split(" ");
            TableFieldSchema field = new TableFieldSchema();
            field.setName(parts[0]);
            field.setType(parts[1]);
            field.setMode(parts.length > 2 ? parts[2] : "NULLABLE");
            fields.add(field);
        }
        return List.copyOf(fields);
    }

    // ── Rows ─────────────────────────────────────────────────────────────────

    /** Rows of one view over the given datasets, in the stored row representation. */
    static List<Map<String, Object>> rows(String view, String projectId, List<Dataset> datasets,
                                          Map<String, List<Table>> tablesByDataset) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Dataset dataset : datasets) {
            String datasetId = dataset.getDatasetReference().getDatasetId();
            if (view.equals("SCHEMATA")) {
                rows.add(schemataRow(projectId, dataset));
                continue;
            }
            for (Table table : tablesByDataset.getOrDefault(datasetId, List.of())) {
                switch (view) {
                    case "TABLES" -> rows.add(tablesRow(projectId, datasetId, table));
                    case "COLUMNS" -> rows.addAll(columnRows(projectId, datasetId, table));
                    case "COLUMN_FIELD_PATHS" -> rows.addAll(fieldPathRows(projectId, datasetId, table));
                    case "TABLE_OPTIONS" -> rows.addAll(optionRows(projectId, datasetId, table));
                    case "VIEWS" -> {
                        if (table.viewQuery() != null) {
                            rows.add(viewsRow(projectId, datasetId, table));
                        }
                    }
                    default -> throw new IllegalArgumentException(view);
                }
            }
        }
        return rows;
    }

    private static Map<String, Object> schemataRow(String projectId, Dataset dataset) {
        Map<String, Object> row = new LinkedHashMap<>();
        String datasetId = dataset.getDatasetReference().getDatasetId();
        row.put("catalog_name", projectId);
        row.put("schema_name", datasetId);
        row.put("schema_owner", null);
        row.put("creation_time", seconds(dataset.getCreationTime()));
        row.put("last_modified_time", seconds(dataset.getLastModifiedTime()));
        row.put("location", location(dataset));
        StringBuilder ddl = new StringBuilder("CREATE SCHEMA `").append(projectId).append('.').append(datasetId)
                .append("`\nOPTIONS(\n");
        if (dataset.getDescription() != null) {
            ddl.append("  description=").append(quote(dataset.getDescription())).append(",\n");
        }
        ddl.append("  location=").append(quote(location(dataset).toLowerCase(Locale.ROOT))).append("\n);");
        row.put("ddl", ddl.toString());
        row.put("default_collation_name", null);
        row.put("sync_status", null);
        return row;
    }

    private static Map<String, Object> tablesRow(String projectId, String datasetId, Table table) {
        String tableId = table.getTableReference().getTableId();
        boolean view = table.viewQuery() != null;
        Map<String, Object> row = base(projectId, datasetId, tableId);
        row.put("table_type", switch (table.getType() != null ? table.getType() : "TABLE") {
            case "VIEW" -> "VIEW";
            case "MATERIALIZED_VIEW" -> "MATERIALIZED VIEW";
            case "EXTERNAL" -> "EXTERNAL";
            case "SNAPSHOT" -> "SNAPSHOT";
            default -> "BASE TABLE";
        });
        row.put("managed_table_type", view ? null : "NATIVE");
        row.put("is_insertable_into", view ? "NO" : "YES");
        row.put("is_fine_grained_mutations_enabled", "NO");
        row.put("is_typed", "NO");
        row.put("is_change_history_enabled", "NO");
        row.put("creation_time", seconds(table.getCreationTime()));
        for (String nullColumn : List.of("base_table_catalog", "base_table_schema", "base_table_name",
                "snapshot_time_ms", "replica_source_catalog", "replica_source_schema", "replica_source_name",
                "replication_status", "replication_error")) {
            row.put(nullColumn, null);
        }
        row.put("ddl", tableDdl(projectId, datasetId, table));
        row.put("default_collation_name", null);
        row.put("sync_status", null);
        row.put("upsert_stream_apply_watermark", null);
        return row;
    }

    private static List<Map<String, Object>> columnRows(String projectId, String datasetId, Table table) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<TableFieldSchema> fields = fieldsOf(table);
        for (int i = 0; i < fields.size(); i++) {
            TableFieldSchema field = fields.get(i);
            Map<String, Object> row = base(projectId, datasetId, table.getTableReference().getTableId());
            row.put("column_name", field.getName());
            row.put("ordinal_position", (long) (i + 1));
            row.put("is_nullable", "REQUIRED".equals(field.getMode()) ? "NO" : "YES");
            row.put("data_type", dataType(field));
            row.put("is_generated", "NEVER");
            row.put("generation_expression", null);
            row.put("is_stored", null);
            row.put("is_hidden", "NO");
            row.put("is_updatable", null);
            row.put("is_system_defined", "NO");
            row.put("is_partitioning_column", "NO");
            row.put("clustering_ordinal_position", null);
            row.put("collation_name", null);
            row.put("column_default", null);
            row.put("rounding_mode", null);
            row.put("policy_tags", List.of());
            row.put("is_identity", "NO");
            for (String nullColumn : List.of("identity_generation", "identity_start", "identity_increment",
                    "identity_maximum", "identity_minimum", "identity_cycle")) {
                row.put(nullColumn, null);
            }
            rows.add(row);
        }
        return rows;
    }

    private static List<Map<String, Object>> fieldPathRows(String projectId, String datasetId, Table table) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TableFieldSchema field : fieldsOf(table)) {
            addFieldPaths(rows, projectId, datasetId, table.getTableReference().getTableId(), field.getName(), "",
                    field);
        }
        return rows;
    }

    /** One row per field path: the column itself, then every nested STRUCT field, depth first. */
    private static void addFieldPaths(List<Map<String, Object>> rows, String projectId, String datasetId,
                                      String tableId, String column, String prefix, TableFieldSchema field) {
        Map<String, Object> row = base(projectId, datasetId, tableId);
        String path = prefix.isEmpty() ? field.getName() : prefix + "." + field.getName();
        row.put("column_name", column);
        row.put("field_path", path);
        // A nested path carries its own field's type: "difference.old_mode" is INT64 even when
        // "difference" is ARRAY<STRUCT<...>>.
        row.put("data_type", dataType(field));
        row.put("description", field.getDescription());
        row.put("collation_name", null);
        row.put("rounding_mode", null);
        row.put("policy_tags", List.of());
        rows.add(row);
        if ("RECORD".equals(field.getType()) && field.getFields() != null) {
            for (TableFieldSchema child : field.getFields()) {
                addFieldPaths(rows, projectId, datasetId, tableId, column, path, child);
            }
        }
    }

    /**
     * TABLE_OPTIONS lists only options that are set; values use GoogleSQL literal syntax
     * ({@code "test data"}, {@code TIMESTAMP "2020-01-16T21:12:28.000Z"}).
     */
    private static List<Map<String, Object>> optionRows(String projectId, String datasetId, Table table) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String tableId = table.getTableReference().getTableId();
        if (table.getDescription() != null) {
            rows.add(option(projectId, datasetId, tableId, "description", "STRING", quote(table.getDescription())));
        }
        if (table.getFriendlyName() != null) {
            rows.add(option(projectId, datasetId, tableId, "friendly_name", "STRING", quote(table.getFriendlyName())));
        }
        if (table.getLabels() != null && !table.getLabels().isEmpty()) {
            String labels = table.getLabels().entrySet().stream()
                    .map(e -> "STRUCT(" + quote(e.getKey()) + ", " + quote(e.getValue()) + ")")
                    .collect(Collectors.joining(", ", "[", "]"));
            rows.add(option(projectId, datasetId, tableId, "labels", "ARRAY<STRUCT<STRING, STRING>>", labels));
        }
        return rows;
    }

    private static Map<String, Object> option(String projectId, String datasetId, String tableId, String name,
                                              String type, String value) {
        Map<String, Object> row = base(projectId, datasetId, tableId);
        row.put("option_name", name);
        row.put("option_type", type);
        row.put("option_value", value);
        return row;
    }

    private static Map<String, Object> viewsRow(String projectId, String datasetId, Table table) {
        Map<String, Object> row = base(projectId, datasetId, table.getTableReference().getTableId());
        row.put("view_definition", table.viewQuery());
        row.put("check_option", null);
        Map<String, Object> definition = table.viewDefinition();
        Object legacy = definition != null ? definition.get("useLegacySql") : null;
        row.put("use_standard_sql", Boolean.TRUE.equals(legacy) ? "NO" : "YES");
        return row;
    }

    private static Map<String, Object> base(String projectId, String datasetId, String tableId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("table_catalog", projectId);
        row.put("table_schema", datasetId);
        row.put("table_name", tableId);
        return row;
    }

    // ── DDL and GoogleSQL type names ─────────────────────────────────────────

    /** {@code CREATE TABLE `p.d.t`\n(\n  id INT64\n);} or {@code CREATE VIEW `p.d.v`\nAS SELECT ...;}. */
    static String tableDdl(String projectId, String datasetId, Table table) {
        String name = "`" + projectId + "." + datasetId + "." + table.getTableReference().getTableId() + "`";
        if (table.viewQuery() != null) {
            String kind = "MATERIALIZED_VIEW".equals(table.getType()) ? "MATERIALIZED VIEW" : "VIEW";
            return "CREATE " + kind + " " + name + "\nAS " + table.viewQuery() + ";";
        }
        List<TableFieldSchema> fields = fieldsOf(table);
        StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(name);
        if (!fields.isEmpty()) {
            ddl.append("\n(\n");
            for (int i = 0; i < fields.size(); i++) {
                TableFieldSchema field = fields.get(i);
                ddl.append("  ").append(field.getName()).append(' ').append(dataType(field));
                if ("REQUIRED".equals(field.getMode())) {
                    ddl.append(" NOT NULL");
                }
                if (field.getDescription() != null) {
                    ddl.append(" OPTIONS(description=").append(quote(field.getDescription())).append(')');
                }
                ddl.append(i < fields.size() - 1 ? ",\n" : "\n");
            }
            ddl.append(')');
        }
        return ddl.append(';').toString();
    }

    /** The GoogleSQL type of a column: {@code INT64}, {@code ARRAY<STRING>}, {@code STRUCT<a INT64>}. */
    static String dataType(TableFieldSchema field) {
        String element = elementType(field);
        return "REPEATED".equals(field.getMode()) ? "ARRAY<" + element + ">" : element;
    }

    private static String elementType(TableFieldSchema field) {
        String type = field.getType() != null ? field.getType() : "STRING";
        return switch (type) {
            case "INTEGER", "INT64" -> "INT64";
            case "FLOAT", "FLOAT64" -> "FLOAT64";
            case "BOOLEAN", "BOOL" -> "BOOL";
            case "RECORD", "STRUCT" -> {
                List<TableFieldSchema> children = field.getFields() != null ? field.getFields() : List.of();
                yield children.stream().map(c -> c.getName() + " " + dataType(c))
                        .collect(Collectors.joining(", ", "STRUCT<", ">"));
            }
            default -> type;
        };
    }

    private static List<TableFieldSchema> fieldsOf(Table table) {
        return table.getSchema() != null && table.getSchema().getFields() != null
                ? table.getSchema().getFields() : List.of();
    }

    private static String location(Dataset dataset) {
        return dataset.getLocation() != null && !dataset.getLocation().isBlank() ? dataset.getLocation() : "US";
    }

    /** A dataset matches {@code region-us} when its location is {@code US}, case-insensitively. */
    static boolean inRegion(Dataset dataset, String region) {
        return region == null || location(dataset).equalsIgnoreCase(region);
    }

    private static String seconds(String millis) {
        if (millis == null) {
            return null;
        }
        return DuckTypes.microsToSeconds(Instant.ofEpochMilli(Long.parseLong(millis)).toEpochMilli() * 1000);
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
