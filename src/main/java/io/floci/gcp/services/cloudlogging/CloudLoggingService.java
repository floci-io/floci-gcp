package io.floci.gcp.services.cloudlogging;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.lifecycle.GrpcServerManager;
import io.floci.gcp.services.cloudlogging.model.StoredLogEntry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@ApplicationScoped
public class CloudLoggingService {

    private static final Logger LOG = Logger.getLogger(CloudLoggingService.class);

    private final StorageBackend<String, StoredLogEntry> entryStore;
    private final AtomicLong sequence = new AtomicLong(0);

    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final GrpcServerManager grpcServerManager;

    @Inject
    public CloudLoggingService(ServiceRegistry serviceRegistry, EmulatorConfig config,
            StorageFactory storageFactory, GrpcServerManager grpcServerManager) {
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.grpcServerManager = grpcServerManager;
        this.entryStore = storageFactory.createGlobal("cloudlogging-entries", "cloudlogging-entries.json",
                new TypeReference<Map<String, StoredLogEntry>>() {});
    }

    CloudLoggingService(StorageBackend<String, StoredLogEntry> entryStore) {
        this.entryStore = entryStore;
        this.serviceRegistry = null;
        this.config = null;
        this.grpcServerManager = null;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("logging")
                .enabled(config.services().logging().enabled())
                .storageKey("logging")
                .protocol(ServiceProtocol.GRPC)
                .resourceClasses(CloudLoggingController.class)
                .build());
        grpcServerManager.bind(new CloudLoggingController(this));
    }

    // ── Write ────────────────────────────────────────────────────────────────

    public void writeLogEntries(String defaultLogName, String defaultResourceType,
            Map<String, String> defaultResourceLabels, Map<String, String> defaultLabels,
            List<StoredLogEntry> entries, boolean dryRun) {
        if (entries.isEmpty()) {
            throw GcpException.invalidArgument("WriteLogEntries requires at least one entry");
        }
        for (StoredLogEntry entry : entries) {
            String logName = blankToNull(entry.getLogName()) != null ? entry.getLogName() : defaultLogName;
            if (blankToNull(logName) == null) {
                throw GcpException.invalidArgument(
                        "Log entry is missing a log_name and no default log_name was provided");
            }
            entry.setLogName(logName);

            if (entry.getResourceType() == null) {
                entry.setResourceType(defaultResourceType != null ? defaultResourceType : "global");
                if (entry.getResourceLabels() == null) {
                    entry.setResourceLabels(defaultResourceLabels);
                }
            }

            entry.setLabels(mergeLabels(defaultLabels, entry.getLabels()));

            if (blankToNull(entry.getInsertId()) == null) {
                entry.setInsertId(UUID.randomUUID().toString());
            }
            String now = Instant.now().toString();
            entry.setReceiveTimestamp(now);
            if (blankToNull(entry.getTimestamp()) == null) {
                entry.setTimestamp(now);
            }
            entry.setSequence(sequence.incrementAndGet());
        }

        if (dryRun) {
            LOG.debugf("writeLogEntries dryRun=true validated %d entries", entries.size());
            return;
        }
        for (StoredLogEntry entry : entries) {
            entryStore.put(key(entry), entry);
        }
        LOG.debugf("writeLogEntries stored %d entries", entries.size());
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    public PageToken.Page<StoredLogEntry> listLogEntries(List<String> resourceNames, String filter,
            String orderBy, int pageSize, String pageToken) {
        if (resourceNames == null || resourceNames.isEmpty()) {
            throw GcpException.invalidArgument("ListLogEntries requires at least one resource name");
        }
        Predicate<StoredLogEntry> filterPredicate = LogEntryFilter.parse(filter);
        List<String> prefixes = resourceNames.stream().map(r -> r + "/logs/").collect(Collectors.toList());

        Comparator<StoredLogEntry> comparator = Comparator
                .comparing(CloudLoggingService::entryInstant)
                .thenComparingLong(StoredLogEntry::getSequence);
        if (orderBy != null && orderBy.toLowerCase().contains("desc")) {
            comparator = comparator.reversed();
        }

        List<StoredLogEntry> matched = entryStore.scan(k -> true).stream()
                .filter(e -> prefixes.stream().anyMatch(p -> e.getLogName().startsWith(p)))
                .filter(filterPredicate)
                .sorted(comparator)
                .collect(Collectors.toList());

        return PageToken.paginate(matched, pageSize, pageToken);
    }

    public List<String> listLogs(String parent) {
        String prefix = parent + "/logs/";
        return entryStore.scan(k -> true).stream()
                .map(StoredLogEntry::getLogName)
                .filter(name -> name.startsWith(prefix))
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    public void deleteLog(String logName) {
        List<StoredLogEntry> entries = entryStore.scan(k -> true).stream()
                .filter(e -> logName.equals(e.getLogName()))
                .collect(Collectors.toList());
        if (entries.isEmpty()) {
            throw GcpException.notFound("Log not found: " + logName);
        }
        entries.forEach(e -> entryStore.delete(key(e)));
        LOG.debugf("deleteLog removed %d entries for %s", entries.size(), logName);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String key(StoredLogEntry entry) {
        return entry.getLogName() + "#" + entry.getSequence();
    }

    private static Instant entryInstant(StoredLogEntry entry) {
        try {
            return Instant.parse(entry.getTimestamp());
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }

    private static Map<String, String> mergeLabels(Map<String, String> defaults, Map<String, String> entryLabels) {
        if ((defaults == null || defaults.isEmpty()) && (entryLabels == null || entryLabels.isEmpty())) {
            return entryLabels;
        }
        Map<String, String> merged = new LinkedHashMap<>();
        if (defaults != null) {
            merged.putAll(defaults);
        }
        if (entryLabels != null) {
            merged.putAll(entryLabels); // entry labels win over request defaults
        }
        return merged;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
