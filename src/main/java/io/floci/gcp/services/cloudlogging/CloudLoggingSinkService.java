package io.floci.gcp.services.cloudlogging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.logging.v2.ListSinksResponse;
import com.google.logging.v2.LogExclusion;
import com.google.logging.v2.LogSink;
import com.google.protobuf.FieldMask;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.FieldMaskUtil;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.GcpResourceNames;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.cloudlogging.model.StoredLogSink;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class CloudLoggingSinkService {

    private static final Set<String> MUTABLE_FIELDS = Set.of("destination", "filter", "description",
            "disabled", "include_children", "exclusions", "bigquery_options");
    private static final FieldMask DEFAULT_UPDATE_MASK = FieldMask.newBuilder()
            .addPaths("destination").addPaths("filter").addPaths("include_children").build();

    private final StorageBackend<String, StoredLogSink> sinks;
    // All sink checks and mutations share this lock; no mutable protobuf or store value escapes it.
    private final Object sinkLock = new Object();

    @Inject
    public CloudLoggingSinkService(StorageFactory storageFactory) {
        this(storageFactory.createGlobal("logging", "cloudlogging-sinks.json",
                new TypeReference<Map<String, StoredLogSink>>() {}));
    }

    CloudLoggingSinkService(StorageBackend<String, StoredLogSink> sinks) {
        this.sinks = sinks;
    }

    public LogSink createSink(String parent, LogSink requested, boolean uniqueWriterIdentity) {
        validateParent(parent);
        validateSinkId(requested.getName());
        LogSink configuration = normalizeConfiguration(requested);
        validateConfiguration(configuration);
        String name = parent + "/sinks/" + requested.getName();
        synchronized (sinkLock) {
            if (sinks.get(name).isPresent()) {
                throw GcpException.alreadyExists("Sink already exists: " + name);
            }
            boolean unique = uniqueWriterIdentity || !parent.startsWith("projects/");
            validateLegacyDestination(parent, configuration, unique);
            Timestamp now = timestamp();
            LogSink created = configuration.toBuilder()
                    .setWriterIdentity(writerIdentity(unique))
                    .setCreateTime(now).setUpdateTime(now).build();
            sinks.put(name, stored(created, unique));
            return created;
        }
    }

    public LogSink getSink(String name) {
        validateSinkName(name);
        synchronized (sinkLock) {
            return read(requireSink(name));
        }
    }

    public ListSinksResponse listSinks(String parent, int pageSize, String pageToken) {
        validateParent(parent);
        synchronized (sinkLock) {
            List<LogSink> all = sinks.scan(key -> key.startsWith(parent + "/sinks/")).stream()
                    .map(CloudLoggingSinkService::read).sorted(Comparator.comparing(LogSink::getName)).toList();
            int boundedPageSize = pageSize > 0 ? Math.min(pageSize, all.size()) : all.size();
            PageToken.Page<LogSink> page = PageToken.paginate(all, boundedPageSize, pageToken);
            ListSinksResponse.Builder response = ListSinksResponse.newBuilder().addAllSinks(page.items());
            if (page.nextPageToken() != null) {
                response.setNextPageToken(page.nextPageToken());
            }
            return response.build();
        }
    }

    public LogSink updateSink(String name, LogSink requested, FieldMask updateMask, boolean uniqueWriterIdentity) {
        String parent = validateSinkName(name);
        String sinkId = name.substring(name.lastIndexOf('/') + 1);
        if (!requested.getName().isEmpty() && !sinkId.equals(requested.getName())) {
            throw GcpException.invalidArgument("Sink name must match the sink identifier in the resource path");
        }
        FieldMask mask = updateMask.getPathsCount() == 0 ? DEFAULT_UPDATE_MASK : updateMask;
        for (String path : mask.getPathsList()) {
            String root = path.split("\\.", 2)[0];
            if (!MUTABLE_FIELDS.contains(root) || !FieldMaskUtil.isValid(LogSink.getDescriptor(), path)
                    || path.equals("bigquery_options.uses_timestamp_column_partitioning")) {
                throw GcpException.invalidArgument("Invalid sink update mask path: " + path);
            }
        }
        synchronized (sinkLock) {
            StoredLogSink stored = requireSink(name);
            boolean unique = uniqueWriterIdentity || !parent.startsWith("projects/");
            if (stored.uniqueWriterIdentity() && !unique) {
                throw GcpException.invalidArgument("A unique sink writer identity cannot be changed to a legacy identity");
            }
            LogSink current = read(stored);
            LogSink.Builder updated = current.toBuilder();
            FieldMaskUtil.merge(mask, requested, updated, new FieldMaskUtil.MergeOptions()
                    .setReplaceRepeatedFields(true).setReplaceMessageFields(true));
            if (unique && !stored.uniqueWriterIdentity()) {
                updated.setWriterIdentity(writerIdentity(true));
            }
            updated.setUpdateTime(timestamp());
            LogSink result = normalizeConfiguration(updated.build());
            validateConfiguration(result);
            validateLegacyDestination(parent, result, unique);
            sinks.put(name, stored(result, unique));
            return result;
        }
    }

    public void deleteSink(String name) {
        validateSinkName(name);
        synchronized (sinkLock) {
            requireSink(name);
            sinks.delete(name);
        }
    }

    private StoredLogSink requireSink(String name) {
        return sinks.get(name).orElseThrow(() -> GcpException.notFound("Sink not found: " + name));
    }

    private static StoredLogSink stored(LogSink sink, boolean unique) {
        return new StoredLogSink(ProtoJson.print(sink), unique);
    }

    private static LogSink read(StoredLogSink sink) {
        return ProtoJson.merge(sink.json(), LogSink.newBuilder()).build();
    }

    private static String writerIdentity(boolean unique) {
        return unique ? "serviceAccount:sink-" + UUID.randomUUID() + "@gcp-sa-logging.iam.gserviceaccount.com"
                : "serviceAccount:cloud-logs@system.gserviceaccount.com";
    }

    private static Timestamp timestamp() {
        Instant now = Instant.now();
        return Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()).build();
    }

    private static void validateParent(String parent) {
        if (parent == null || !parent.matches("(?:projects|organizations|folders|billingAccounts)/[^/\\s]+")
                || parent.endsWith("/.") || parent.endsWith("/..")) {
            throw GcpException.invalidArgument("Invalid sink parent: " + parent);
        }
    }

    private static void validateSinkId(String id) {
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")) {
            throw GcpException.invalidArgument("Invalid sink identifier: " + id);
        }
    }

    private static String validateSinkName(String name) {
        if (name == null) {
            throw GcpException.invalidArgument("Sink resource name is required");
        }
        String[] parts = name.split("/", -1);
        if (parts.length != 4 || !"sinks".equals(parts[2])) {
            throw GcpException.invalidArgument("Invalid sink resource name: " + name);
        }
        String parent = parts[0] + "/" + parts[1];
        validateParent(parent);
        validateSinkId(parts[3]);
        return parent;
    }

    private static void validateConfiguration(LogSink sink) {
        if (sink.getDestination().startsWith("logging.googleapis.com/")) {
            throw GcpException.unimplemented("Logging project and log-bucket sink destinations are not supported");
        }
        if (!sink.getDestination().matches("(?:storage\\.googleapis\\.com/[^/\\s]+"
                + "|pubsub\\.googleapis\\.com/projects/[^/\\s]+/topics/[^/\\s]+"
                + "|bigquery\\.googleapis\\.com/projects/[^/\\s]+/datasets/[^/\\s]+)")) {
            throw GcpException.invalidArgument("Invalid sink destination: " + sink.getDestination());
        }
        if (sink.getDescription().length() > 8000) {
            throw GcpException.invalidArgument("Sink description must not exceed 8000 characters");
        }
        Set<String> exclusionNames = new HashSet<>();
        for (LogExclusion exclusion : sink.getExclusionsList()) {
            validateSinkId(exclusion.getName());
            if (!exclusionNames.add(exclusion.getName()) || exclusion.getFilter().isBlank()) {
                throw GcpException.invalidArgument("Sink exclusions require unique names and nonempty filters");
            }
        }
    }

    private static void validateLegacyDestination(String parent, LogSink sink, boolean unique) {
        String destinationProject = GcpResourceNames.parseProject(sink.getDestination());
        if (!unique && destinationProject != null
                && !destinationProject.equals(GcpResourceNames.parseProject(parent))) {
            throw GcpException.invalidArgument("A sink with a legacy writer identity requires a destination in its own project");
        }
    }

    private static LogSink normalizeConfiguration(LogSink sink) {
        LogSink.Builder normalized = sink.toBuilder();
        if (sink.hasBigqueryOptions()) {
            normalized.setBigqueryOptions(sink.getBigqueryOptions().toBuilder()
                    .setUsesTimestampColumnPartitioning(sink.getBigqueryOptions().getUsePartitionedTables()));
        }
        for (int i = 0; i < sink.getExclusionsCount(); i++) {
            normalized.setExclusions(i, sink.getExclusions(i).toBuilder().clearCreateTime().clearUpdateTime());
        }
        return normalized.build();
    }
}
