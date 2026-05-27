package io.floci.gcp.services.datastore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.datastore.v1.*;
import com.google.protobuf.Timestamp;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.lifecycle.GrpcServerManager;
import io.floci.gcp.services.datastore.model.StoredEntity;
import io.floci.gcp.services.datastore.model.StoredProperty;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class DatastoreService {

    private static final Logger LOG = Logger.getLogger(DatastoreService.class);

    private final StorageBackend<String, StoredEntity> entityStore;
    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final GrpcServerManager grpcServerManager;
    private final AtomicLong idGenerator = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong versionCounter = new AtomicLong(1);

    @Inject
    public DatastoreService(ServiceRegistry serviceRegistry, EmulatorConfig config,
            StorageFactory storageFactory, GrpcServerManager grpcServerManager) {
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.grpcServerManager = grpcServerManager;
        this.entityStore = storageFactory.createGlobal("datastore-entities",
                "datastore-entities.json", new TypeReference<Map<String, StoredEntity>>() {});
    }

    DatastoreService(StorageBackend<String, StoredEntity> entityStore) {
        this.entityStore = entityStore;
        this.serviceRegistry = null;
        this.config = null;
        this.grpcServerManager = null;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("datastore")
                .enabled(config.services().datastore().enabled())
                .storageKey("datastore")
                .protocol(ServiceProtocol.GRPC)
                .resourceClasses(DatastoreController.class)
                .build());
        grpcServerManager.bind(new DatastoreController(this));
    }

    // ── Key helpers ────────────────────────────────────────────────────────────

    String buildStorageKey(String projectId, Key key) {
        Key.PathElement last = key.getPath(key.getPathCount() - 1);
        String ns = key.getPartitionId().getNamespaceId();
        String idPart;
        if (last.getIdTypeCase() == Key.PathElement.IdTypeCase.NAME) {
            idPart = "n:" + last.getName();
        } else {
            idPart = "i:" + last.getId();
        }
        return projectId + "/" + ns + "/" + last.getKind() + "/" + idPart;
    }

    Key rebuildKey(StoredEntity entity) {
        Key.PathElement.Builder pathEl = Key.PathElement.newBuilder().setKind(entity.getKind());
        if (entity.getKeyName() != null) {
            pathEl.setName(entity.getKeyName());
        } else if (entity.getKeyId() != null) {
            pathEl.setId(entity.getKeyId());
        }
        return Key.newBuilder()
                .setPartitionId(PartitionId.newBuilder()
                        .setProjectId(entity.getProjectId())
                        .setNamespaceId(entity.getNamespaceId() != null ? entity.getNamespaceId() : ""))
                .addPath(pathEl)
                .build();
    }

    Entity toProto(StoredEntity stored) {
        Entity.Builder builder = Entity.newBuilder().setKey(rebuildKey(stored));
        if (stored.getProperties() != null) {
            stored.getProperties().forEach((k, v) -> builder.putProperties(k, v.toProto()));
        }
        return builder.build();
    }

    // ── Lookup ─────────────────────────────────────────────────────────────────

    public Optional<StoredEntity> lookupEntity(String projectId, Key key) {
        LOG.debugf("lookupEntity project=%s kind=%s", projectId,
                key.getPathCount() > 0 ? key.getPath(0).getKind() : "?");
        return entityStore.get(buildStorageKey(projectId, key));
    }

    // ── Commit ─────────────────────────────────────────────────────────────────

    public record MutationApplyResult(MutationResult mutationResult, Key allocatedKey) {}

    public MutationApplyResult applyMutation(String projectId, Mutation mutation, Instant commitTime) {
        String now = commitTime.toString();
        long version = versionCounter.incrementAndGet();

        if (mutation.hasInsert() || mutation.hasUpsert() || mutation.hasUpdate()) {
            Entity entity = mutation.hasInsert() ? mutation.getInsert()
                    : mutation.hasUpsert() ? mutation.getUpsert() : mutation.getUpdate();

            Key key = entity.getKey();
            boolean isIncomplete = key.getPathCount() == 0
                    || key.getPath(key.getPathCount() - 1).getIdTypeCase()
                            == Key.PathElement.IdTypeCase.IDTYPE_NOT_SET;

            Key allocatedKey = null;
            if (isIncomplete) {
                long newId = idGenerator.incrementAndGet();
                Key.PathElement lastEl = key.getPath(key.getPathCount() - 1);
                key = key.toBuilder()
                        .setPath(key.getPathCount() - 1,
                                lastEl.toBuilder().setId(newId).build())
                        .build();
                allocatedKey = key;
            }

            String storageKey = buildStorageKey(projectId, key);
            Optional<StoredEntity> existing = entityStore.get(storageKey);

            if (mutation.hasUpdate() && existing.isEmpty()) {
                throw GcpException.notFound("Entity not found for update: " + storageKey);
            }
            if (mutation.hasInsert() && existing.isPresent()) {
                throw GcpException.alreadyExists("Entity already exists: " + storageKey);
            }

            String createTime = existing.map(StoredEntity::getCreateTime).orElse(now);

            Key.PathElement last = key.getPath(key.getPathCount() - 1);
            StoredEntity stored = new StoredEntity(
                    projectId,
                    key.getPartitionId().getNamespaceId(),
                    last.getKind(),
                    last.getIdTypeCase() == Key.PathElement.IdTypeCase.NAME ? last.getName() : null,
                    last.getIdTypeCase() == Key.PathElement.IdTypeCase.ID ? last.getId() : null,
                    version, createTime, now,
                    convertProperties(entity.getPropertiesMap()));
            entityStore.put(storageKey, stored);

            MutationResult.Builder mr = MutationResult.newBuilder()
                    .setVersion(version)
                    .setUpdateTime(toTimestamp(now))
                    .setCreateTime(toTimestamp(createTime));
            if (allocatedKey != null) {
                mr.setKey(allocatedKey);
            }
            return new MutationApplyResult(mr.build(), allocatedKey);
        }

        if (mutation.hasDelete()) {
            Key key = mutation.getDelete();
            String storageKey = buildStorageKey(projectId, key);
            entityStore.delete(storageKey);
            return new MutationApplyResult(
                    MutationResult.newBuilder().setVersion(version).build(), null);
        }

        return new MutationApplyResult(
                MutationResult.newBuilder().setVersion(version).build(), null);
    }

    // ── RunQuery ───────────────────────────────────────────────────────────────

    public List<StoredEntity> runQuery(String projectId, PartitionId partitionId, Query query) {
        LOG.debugf("runQuery project=%s kind=%s",
                projectId, query.getKindCount() > 0 ? query.getKind(0).getName() : "");

        String ns = partitionId != null ? partitionId.getNamespaceId() : "";
        String kind = query.getKindCount() > 0 ? query.getKind(0).getName() : "";
        String prefix = projectId + "/" + ns + "/" + kind + "/";

        List<StoredEntity> candidates = entityStore.scan(k -> k.startsWith(prefix));

        if (query.hasFilter()) {
            candidates = candidates.stream()
                    .filter(e -> matchesFilter(e, query.getFilter()))
                    .toList();
        }

        int offset = query.getOffset();
        int limit = query.hasLimit() ? query.getLimit().getValue() : Integer.MAX_VALUE;
        if (offset > 0 || limit < Integer.MAX_VALUE) {
            candidates = candidates.stream().skip(offset).limit(limit).toList();
        }

        return candidates;
    }

    // ── AllocateIds ────────────────────────────────────────────────────────────

    public List<Key> allocateIds(String projectId, List<Key> keys) {
        List<Key> result = new ArrayList<>();
        for (Key key : keys) {
            long newId = idGenerator.incrementAndGet();
            Key.PathElement lastEl = key.getPath(key.getPathCount() - 1);
            Key allocated = key.toBuilder()
                    .setPath(key.getPathCount() - 1, lastEl.toBuilder().setId(newId).build())
                    .build();
            result.add(allocated);
        }
        return result;
    }

    // ── Transaction stubs ──────────────────────────────────────────────────────

    public byte[] beginTransaction() {
        return UUID.randomUUID().toString().getBytes();
    }

    // ── Filter evaluation ──────────────────────────────────────────────────────

    private boolean matchesFilter(StoredEntity entity, Filter filter) {
        if (filter.hasPropertyFilter()) {
            return matchesPropertyFilter(entity, filter.getPropertyFilter());
        }
        if (filter.hasCompositeFilter()) {
            CompositeFilter cf = filter.getCompositeFilter();
            if (cf.getOp() == CompositeFilter.Operator.AND) {
                return cf.getFiltersList().stream().allMatch(f -> matchesFilter(entity, f));
            }
            return cf.getFiltersList().stream().anyMatch(f -> matchesFilter(entity, f));
        }
        return true;
    }

    private boolean matchesPropertyFilter(StoredEntity entity, PropertyFilter pf) {
        String propName = pf.getProperty().getName();
        StoredProperty stored = entity.getProperties() != null
                ? entity.getProperties().get(propName) : null;
        Value filterValue = pf.getValue();

        return switch (pf.getOp()) {
            case EQUAL -> stored != null && stored.matchesEqual(filterValue);
            case NOT_EQUAL -> stored == null || !stored.matchesEqual(filterValue);
            default -> true;
        };
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Map<String, StoredProperty> convertProperties(Map<String, Value> protoProps) {
        Map<String, StoredProperty> result = new LinkedHashMap<>();
        protoProps.forEach((k, v) -> result.put(k, StoredProperty.fromProto(v)));
        return result;
    }

    static Timestamp toTimestamp(String isoTime) {
        if (isoTime == null) return Timestamp.getDefaultInstance();
        try {
            Instant instant = Instant.parse(isoTime);
            return Timestamp.newBuilder()
                    .setSeconds(instant.getEpochSecond())
                    .setNanos(instant.getNano())
                    .build();
        } catch (Exception e) {
            return Timestamp.getDefaultInstance();
        }
    }
}
