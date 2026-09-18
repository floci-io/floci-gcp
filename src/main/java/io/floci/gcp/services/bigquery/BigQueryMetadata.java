package io.floci.gcp.services.bigquery;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.bigquery.model.Dataset;
import io.floci.gcp.services.bigquery.model.Table;
import io.floci.gcp.services.bigquery.model.TableFieldSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Round-trip rules for the writable Table and Dataset fields the emulator stores verbatim
 * (the client-writable properties of the BigQuery v2 discovery schemas, minus the ones modeled
 * explicitly), plus the server-side behavior those fields drive: dataset default expirations
 * applied to new tables, table expiration, and validation of the documented value ranges.
 */
final class BigQueryMetadata {

    /** Writable {@code Table} properties without a typed field. */
    static final Set<String> TABLE_FIELDS = Set.of(
            "expirationTime", "partitionDefinition", "requirePartitionFilter", "timePartitioning",
            "maxStaleness", "view", "materializedView", "defaultRoundingMode", "tableReplicationInfo",
            "tableConstraints", "clustering", "biglakeConfiguration", "externalCatalogTableOptions",
            "managedTableType", "encryptionConfiguration", "defaultCollation", "rangePartitioning",
            "resourceTags", "externalDataConfiguration");

    /** Writable {@code Dataset} properties without a typed field ({@code access} is not stored yet). */
    static final Set<String> DATASET_FIELDS = Set.of(
            "defaultCollation", "defaultRoundingMode", "defaultEncryptionConfiguration", "resourceTags",
            "isCaseInsensitive", "maxTimeTravelHours", "storageBillingModel", "externalCatalogDatasetOptions",
            "defaultPartitionExpirationMs", "externalDatasetReference", "defaultTableExpirationMs",
            "linkedDatasetSource");

    /**
     * Accepted on insert but never on update. The reference is explicit: "This field cannot be
     * updated once it is set. Any attempt to update this field using Update and Patch API
     * Operations will be ignored."
     */
    static final Set<String> IMMUTABLE_DATASET_FIELDS = Set.of("linkedDatasetSource");

    private static final Set<String> INT64_FIELDS = Set.of(
            "expirationTime", "defaultTableExpirationMs", "defaultPartitionExpirationMs", "maxTimeTravelHours");

    private static final Set<String> PARTITION_TYPES = Set.of("DAY", "HOUR", "MONTH", "YEAR");

    static final long MIN_TABLE_EXPIRATION_MS = 3_600_000L;
    static final String DEFAULT_MAX_TIME_TRAVEL_HOURS = "168";

    private BigQueryMetadata() {}

    // ── Create / patch / update ──────────────────────────────────────────────

    /** Keeps only the allowed, non-null keys; int64 values are normalized to strings. */
    static Map<String, Object> writable(Map<String, Object> source, Set<String> allowed) {
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (allowed.contains(key) && value != null) {
                out.put(key, normalize(key, value));
            }
        });
        return out;
    }

    /** PATCH: present keys replace stored ones; an explicit {@code null} clears the field. */
    static void patch(Map<String, Object> target, Map<String, Object> patch, Set<String> allowed) {
        patch.forEach((key, value) -> {
            if (!allowed.contains(key) || IMMUTABLE_DATASET_FIELDS.contains(key)) {
                return;
            }
            if (value == null) {
                target.remove(key);
            } else {
                target.put(key, normalize(key, value));
            }
        });
    }

    /** UPDATE (PUT): the stored writable fields become exactly the ones in the request. */
    static void replace(Map<String, Object> target, Map<String, Object> update, Set<String> allowed) {
        Map<String, Object> immutable = new LinkedHashMap<>();
        IMMUTABLE_DATASET_FIELDS.forEach(key -> {
            if (target.containsKey(key)) {
                immutable.put(key, target.get(key));
            }
        });
        target.keySet().removeIf(allowed::contains);
        target.putAll(writable(update, allowed));
        target.keySet().removeAll(IMMUTABLE_DATASET_FIELDS);
        target.putAll(immutable);
    }

    /** A {@code null} nested field means "absent" on the wire; clients such as the Java SDK send them. */
    @SuppressWarnings("unchecked")
    private static Object stripNulls(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) map).forEach((k, v) -> {
                if (v != null) {
                    out.put(k, stripNulls(v));
                }
            });
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(BigQueryMetadata::stripNulls).toList();
        }
        return value;
    }

    private static Object normalize(String key, Object value) {
        return normalizeInt64(key, stripNulls(value));
    }

    @SuppressWarnings("unchecked")
    private static Object normalizeInt64(String key, Object value) {
        if (INT64_FIELDS.contains(key) && value instanceof Number n) {
            return String.valueOf(n.longValue());
        }
        if (key.equals("timePartitioning") && value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) map);
            if (copy.get("expirationMs") instanceof Number n) {
                copy.put("expirationMs", String.valueOf(n.longValue()));
            }
            return copy;
        }
        if (key.equals("rangePartitioning") && value instanceof Map<?, ?> map
                && ((Map<String, Object>) map).get("range") instanceof Map<?, ?> range) {
            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) map);
            Map<String, Object> rangeCopy = new LinkedHashMap<>((Map<String, Object>) range);
            rangeCopy.replaceAll((k, v) -> v instanceof Number n ? String.valueOf(n.longValue()) : v);
            copy.put("range", rangeCopy);
            return copy;
        }
        return value;
    }

    // ── Validation ───────────────────────────────────────────────────────────

    /**
     * Dataset value ranges from the discovery document: {@code defaultTableExpirationMs} is at
     * least one hour ({@code 0} clears it on PATCH), {@code maxTimeTravelHours} is 48 to 168.
     */
    static void validateDataset(Map<String, Object> extra) {
        Long tableExpiration = longValue(extra, "defaultTableExpirationMs");
        if (tableExpiration != null && tableExpiration != 0 && tableExpiration < MIN_TABLE_EXPIRATION_MS) {
            throw invalid("defaultTableExpirationMs must be at least " + MIN_TABLE_EXPIRATION_MS
                    + " milliseconds (one hour)");
        }
        Long partitionExpiration = longValue(extra, "defaultPartitionExpirationMs");
        if (partitionExpiration != null && partitionExpiration < 0) {
            throw invalid("defaultPartitionExpirationMs must not be negative");
        }
        Long timeTravel = longValue(extra, "maxTimeTravelHours");
        if (timeTravel != null && (timeTravel < 48 || timeTravel > 168)) {
            throw invalid("maxTimeTravelHours must be between 48 and 168");
        }
    }

    /** {@code timePartitioning.type} is required and one of DAY/HOUR/MONTH/YEAR; its field must exist. */
    @SuppressWarnings("unchecked")
    static void validateTable(Map<String, Object> extra, List<TableFieldSchema> fields) {
        if (extra.get("timePartitioning") instanceof Map<?, ?> tp) {
            Map<String, Object> partitioning = (Map<String, Object>) tp;
            Object type = partitioning.get("type");
            if (!(type instanceof String s) || !PARTITION_TYPES.contains(s)) {
                throw invalid("timePartitioning.type must be one of DAY, HOUR, MONTH or YEAR");
            }
            Long expirationMs = longValue(partitioning, "expirationMs");
            if (expirationMs != null && expirationMs <= 0) {
                throw invalid("timePartitioning.expirationMs must be positive");
            }
            if (partitioning.get("field") instanceof String field && fields != null && !fields.isEmpty()
                    && fields.stream().noneMatch(f -> f.getName().equalsIgnoreCase(field))) {
                throw invalid("The field specified for time partitioning (" + field + ") is not in the table schema");
            }
        }
        if (extra.containsKey("timePartitioning") && extra.containsKey("rangePartitioning")) {
            throw invalid("Cannot specify both time partitioning and range partitioning");
        }
    }

    // ── Server-side behavior ─────────────────────────────────────────────────

    /**
     * New tables inherit the dataset defaults: a partitioned table without
     * {@code timePartitioning.expirationMs} takes {@code defaultPartitionExpirationMs} (and then no
     * table expiration); otherwise a table without {@code expirationTime} gets creation time plus
     * {@code defaultTableExpirationMs}.
     */
    @SuppressWarnings("unchecked")
    static void applyDatasetDefaults(Table table, Dataset dataset, long creationMillis) {
        Map<String, Object> defaults = dataset.getExtra();
        Long partitionDefault = longValue(defaults, "defaultPartitionExpirationMs");
        if (table.getExtra().get("timePartitioning") instanceof Map<?, ?> tp && partitionDefault != null
                && partitionDefault > 0) {
            Map<String, Object> partitioning = (Map<String, Object>) tp;
            if (!partitioning.containsKey("expirationMs")) {
                partitioning.put("expirationMs", String.valueOf(partitionDefault));
            }
            return;
        }
        Long tableDefault = longValue(defaults, "defaultTableExpirationMs");
        if (!table.getExtra().containsKey("expirationTime") && tableDefault != null && tableDefault > 0) {
            table.getExtra().put("expirationTime", String.valueOf(creationMillis + tableDefault));
        }
    }

    /** A table past its {@code expirationTime} is gone. */
    static boolean expired(Table table, long nowMillis) {
        Long expiration = longValue(table.getExtra(), "expirationTime");
        return expiration != null && expiration <= nowMillis;
    }

    /**
     * Server-populated dataset fields: {@code type} follows the stored source references, the
     * location defaults to {@code US}, and {@code maxTimeTravelHours} is 168 "if this is not set".
     */
    static void fillDatasetOutputs(Dataset dataset) {
        if (dataset.getExtra().containsKey("externalDatasetReference")) {
            dataset.setType("EXTERNAL");
        } else if (dataset.getExtra().containsKey("linkedDatasetSource")) {
            dataset.setType("LINKED");
        } else {
            dataset.setType("DEFAULT");
        }
        if (dataset.getLocation() == null || dataset.getLocation().isBlank()) {
            dataset.setLocation("US");
        }
        dataset.getExtra().putIfAbsent("maxTimeTravelHours", DEFAULT_MAX_TIME_TRAVEL_HOURS);
    }

    private static Long longValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                throw invalid(key + " must be an integer, got " + s);
            }
        }
        return null;
    }

    private static GcpException invalid(String message) {
        return GcpException.invalidArgument(message).withReason("invalid");
    }
}
