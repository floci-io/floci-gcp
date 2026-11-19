package io.floci.gcp.services.cloudlogging;

import com.google.logging.v2.LogSink;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ProjectRegistry;
import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.services.cloudlogging.model.StoredLogEntry;
import io.floci.gcp.services.gcs.GcsService;
import io.floci.gcp.services.gcs.model.GcsObjectPreconditions;
import io.floci.gcp.services.iam.GcsIamAuthorizationService;
import io.floci.gcp.services.iam.IamPrincipal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Prompt local batches, authorized as the sink writer using the destination's stored IAM policies. */
@ApplicationScoped
public class CloudLoggingGcsExporter {
    private static final Logger LOG = Logger.getLogger(CloudLoggingGcsExporter.class);
    private static final DateTimeFormatter DIRECTORY = DateTimeFormatter.ofPattern("uuuu/MM/dd/")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneOffset.UTC);

    private final GcsService storage;
    private final GcsIamAuthorizationService authorization;
    private final ProjectRegistry projects;
    private final SinkRouting routing;
    private final EmulatorConfig config;

    @Inject
    public CloudLoggingGcsExporter(GcsService storage, GcsIamAuthorizationService authorization,
            ProjectRegistry projects, EmulatorConfig config) {
        this.storage = storage;
        this.authorization = authorization;
        this.projects = projects;
        this.routing = new SinkRouting(projects);
        this.config = config;
    }

    public void export(String parent, LogSink sink, List<StoredLogEntry> entries) {
        Map<String, List<StoredLogEntry>> batches = new LinkedHashMap<>();
        for (StoredLogEntry entry : routing.select(parent, sink, entries)) {
            batches.computeIfAbsent(objectPrefix(entry), ignored -> new ArrayList<>()).add(entry);
        }
        if (batches.isEmpty()) {
            return;
        }
        if (!config.services().gcs().enabled()) {
            throw GcpException.failedPrecondition("Cloud Storage is disabled");
        }
        String bucket = sink.getDestination().substring("storage.googleapis.com/".length());
        IamPrincipal writer = new IamPrincipal(sink.getWriterIdentity());
        for (Map.Entry<String, List<StoredLogEntry>> batch : batches.entrySet()) {
            // A numeric shard plus generation precondition prevents replacement of any existing export.
            String object = batch.getKey() + Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits());
            Instant hour = Instant.parse(batch.getValue().getFirst().getTimestamp()).truncatedTo(ChronoUnit.HOURS);
            Instant received = Instant.parse(batch.getValue().getFirst().getReceiveTimestamp());
            if (!hour.equals(received.truncatedTo(ChronoUnit.HOURS))) {
                object += ":" + Instant.now().getEpochSecond();
            }
            object += ".json";
            String json = batch.getValue().stream().map(CloudLoggingController::toProto).map(ProtoJson::print)
                    .collect(Collectors.joining(",", "[", "]"));
            String name = object;
            try {
                authorization.authorizeServiceObjectCreate(writer, bucket, name, overwrite -> {
                    if (writer.member().equals("serviceAccount:cloud-logs@system.gserviceaccount.com")
                            && !ownsBucket(parent, bucket)) {
                        throw GcpException.failedPrecondition("A legacy sink requires a destination in its own project");
                    }
                    return storage.putObject(bucket, name, "application/json", json.getBytes(StandardCharsets.UTF_8),
                            new GcsObjectPreconditions(0L, null, null, null), config.baseUrl(), overwrite::require);
                });
            } catch (RuntimeException exception) {
                LOG.warnf(exception, "Cloud Logging sink %s failed exporting to bucket %s", sink.getName(), bucket);
            }
        }
    }

    private boolean ownsBucket(String parent, String bucket) {
        String project = storage.getBucket(bucket).getProjectId();
        if (parent.equals("projects/" + project)) {
            return true;
        }
        return projects.find(project).map(record -> parent.equals("projects/" + record.getProjectNumber())
                || parent.equals("projects/" + record.getProjectId())).orElse(false);
    }

    private static String objectPrefix(StoredLogEntry entry) {
        String encodedLog = entry.getLogName().substring(entry.getLogName().indexOf("/logs/") + 6);
        String log = URLDecoder.decode(encodedLog.replace("+", "%2B"), StandardCharsets.UTF_8);
        Instant hour = Instant.parse(entry.getTimestamp()).truncatedTo(ChronoUnit.HOURS);
        Instant receiveHour = Instant.parse(entry.getReceiveTimestamp()).truncatedTo(ChronoUnit.HOURS);
        return log + "/" + DIRECTORY.format(hour) + TIME.format(hour) + "_"
                + TIME.format(hour.plusSeconds(3599)) + (hour.equals(receiveHour) ? "_S" : "_A");
    }
}
