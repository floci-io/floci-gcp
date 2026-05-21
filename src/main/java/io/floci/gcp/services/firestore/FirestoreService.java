package io.floci.gcp.services.firestore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.firestore.v1.Document;
import com.google.firestore.v1.DocumentMask;
import com.google.firestore.v1.StructuredQuery;
import com.google.firestore.v1.Value;
import com.google.firestore.v1.Write;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.lifecycle.GrpcServerManager;
import io.floci.gcp.services.firestore.model.StoredDocument;
import io.floci.gcp.services.firestore.model.StoredValue;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class FirestoreService {

    private static final Logger LOG = Logger.getLogger(FirestoreService.class);

    private final StorageBackend<String, StoredDocument> documentStore;
    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final GrpcServerManager grpcServerManager;

    @Inject
    public FirestoreService(ServiceRegistry serviceRegistry, EmulatorConfig config,
            StorageFactory storageFactory, GrpcServerManager grpcServerManager) {
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.grpcServerManager = grpcServerManager;
        this.documentStore = storageFactory.createGlobal("firestore-documents", "firestore-documents.json",
                new TypeReference<Map<String, StoredDocument>>() {});
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("firestore")
                .enabled(config.services().firestore().enabled())
                .storageKey("firestore")
                .protocol(ServiceProtocol.GRPC)
                .resourceClasses(FirestoreController.class)
                .build());
        grpcServerManager.bind(new FirestoreController(this));
    }

    // ── Writes ─────────────────────────────────────────────────────────────────

    public record WriteCommitResult(String updateTime) {}

    public WriteCommitResult applyWrite(Write write, Instant commitTime) {
        String now = commitTime.toString();

        if (write.hasUpdate()) {
            Document doc = write.getUpdate();
            String name = doc.getName();
            Map<String, StoredValue> incomingFields = convertFields(doc.getFieldsMap());

            boolean hasMask = write.hasUpdateMask() && write.getUpdateMask().getFieldPathsCount() > 0;

            if (hasMask) {
                Optional<StoredDocument> existing = documentStore.get(name);
                Map<String, StoredValue> merged = new LinkedHashMap<>(
                        existing.map(StoredDocument::getFields).orElse(new LinkedHashMap<>()));

                for (String path : write.getUpdateMask().getFieldPathsList()) {
                    StoredValue val = incomingFields.get(path);
                    if (val != null) {
                        merged.put(path, val);
                    } else {
                        merged.remove(path);
                    }
                }

                String createTime = existing.map(StoredDocument::getCreateTime).orElse(now);
                documentStore.put(name, new StoredDocument(name, createTime, now, merged));
            } else {
                String createTime = documentStore.get(name)
                        .map(StoredDocument::getCreateTime).orElse(now);
                documentStore.put(name, new StoredDocument(name, createTime, now, incomingFields));
            }

            // Apply field transforms (server timestamps etc.) after the update
            applyTransforms(name, write, now);

            return new WriteCommitResult(now);
        }

        if (!write.getDelete().isEmpty()) {
            documentStore.delete(write.getDelete());
            return new WriteCommitResult(null);
        }

        // standalone transform
        if (write.hasTransform()) {
            String docName = write.getTransform().getDocument();
            applyDocumentTransform(docName, write.getTransform().getFieldTransformsList(), now);
            return new WriteCommitResult(now);
        }

        return new WriteCommitResult(now);
    }

    private void applyTransforms(String name, Write write, String now) {
        if (write.getUpdateTransformsCount() == 0) return;
        Optional<StoredDocument> existing = documentStore.get(name);
        existing.ifPresent(doc -> {
            Map<String, StoredValue> fields = new LinkedHashMap<>(doc.getFields());
            for (var transform : write.getUpdateTransformsList()) {
                applyFieldTransform(fields, transform, now);
            }
            documentStore.put(name, new StoredDocument(name, doc.getCreateTime(), now, fields));
        });
    }

    private void applyDocumentTransform(String name, List<com.google.firestore.v1.DocumentTransform.FieldTransform> transforms, String now) {
        Optional<StoredDocument> existing = documentStore.get(name);
        if (existing.isEmpty()) return;
        StoredDocument doc = existing.get();
        Map<String, StoredValue> fields = new LinkedHashMap<>(doc.getFields());
        for (var transform : transforms) {
            applyFieldTransform(fields, transform, now);
        }
        documentStore.put(name, new StoredDocument(name, doc.getCreateTime(), now, fields));
    }

    private void applyFieldTransform(Map<String, StoredValue> fields,
            com.google.firestore.v1.DocumentTransform.FieldTransform transform, String now) {
        String path = transform.getFieldPath();
        if (transform.hasSetToServerValue()
                && transform.getSetToServerValue() == com.google.firestore.v1.DocumentTransform.FieldTransform.ServerValue.REQUEST_TIME) {
            StoredValue ts = new StoredValue();
            ts.setType("timestamp");
            ts.setStringValue(now);
            fields.put(path, ts);
        }
    }

    // ── Reads ──────────────────────────────────────────────────────────────────

    public Optional<StoredDocument> getDocument(String name) {
        LOG.debugf("getDocument name=%s", name);
        return documentStore.get(name);
    }

    public List<StoredDocument> runQuery(String parent, StructuredQuery query) {
        LOG.debugf("runQuery parent=%s", parent);
        String collectionId = query.getFromCount() > 0 ? query.getFrom(0).getCollectionId() : "";
        String prefix = parent + "/" + collectionId + "/";

        List<StoredDocument> candidates = documentStore.scan(k -> k.startsWith(prefix)
                && k.substring(prefix.length()).indexOf('/') < 0);

        if (!query.hasWhere()) {
            return applyLimitAndOffset(candidates, query);
        }

        List<StoredDocument> filtered = candidates.stream()
                .filter(doc -> matchesFilter(doc, query.getWhere()))
                .toList();

        return applyLimitAndOffset(filtered, query);
    }

    private List<StoredDocument> applyLimitAndOffset(List<StoredDocument> docs, StructuredQuery query) {
        int offset = query.getOffset();
        int limit = query.hasLimit() ? query.getLimit().getValue() : Integer.MAX_VALUE;
        if (offset > 0 || limit < Integer.MAX_VALUE) {
            return docs.stream().skip(offset).limit(limit).toList();
        }
        return docs;
    }

    // ── Transactions ───────────────────────────────────────────────────────────

    public byte[] beginTransaction() {
        String id = UUID.randomUUID().toString();
        return id.getBytes();
    }

    // ── Filter evaluation ──────────────────────────────────────────────────────

    private boolean matchesFilter(StoredDocument doc, StructuredQuery.Filter filter) {
        if (filter.hasFieldFilter()) {
            return matchesFieldFilter(doc, filter.getFieldFilter());
        }
        if (filter.hasCompositeFilter()) {
            StructuredQuery.CompositeFilter cf = filter.getCompositeFilter();
            if (cf.getOp() == StructuredQuery.CompositeFilter.Operator.AND) {
                return cf.getFiltersList().stream().allMatch(f -> matchesFilter(doc, f));
            }
            return cf.getFiltersList().stream().anyMatch(f -> matchesFilter(doc, f));
        }
        if (filter.hasUnaryFilter()) {
            return matchesUnaryFilter(doc, filter.getUnaryFilter());
        }
        return true;
    }

    private boolean matchesFieldFilter(StoredDocument doc, StructuredQuery.FieldFilter ff) {
        String path = ff.getField().getFieldPath();
        StoredValue stored = doc.getFields() != null ? doc.getFields().get(path) : null;
        Value filterValue = ff.getValue();

        return switch (ff.getOp()) {
            case EQUAL -> stored != null && stored.matchesEqual(filterValue);
            case NOT_EQUAL -> stored == null || !stored.matchesEqual(filterValue);
            default -> true; // LT, LTE, GT, GTE, IN, ARRAY_CONTAINS, etc. — stub as passing for Tier 1
        };
    }

    private boolean matchesUnaryFilter(StoredDocument doc, StructuredQuery.UnaryFilter uf) {
        String path = uf.getField().getFieldPath();
        StoredValue stored = doc.getFields() != null ? doc.getFields().get(path) : null;
        return switch (uf.getOp()) {
            case IS_NULL -> stored != null && "null".equals(stored.getType());
            case IS_NOT_NULL -> stored != null && !"null".equals(stored.getType());
            case IS_NAN -> stored != null && "double".equals(stored.getType())
                    && stored.getDoubleValue() != null && Double.isNaN(stored.getDoubleValue());
            case IS_NOT_NAN -> stored == null || !"double".equals(stored.getType())
                    || stored.getDoubleValue() == null || !Double.isNaN(stored.getDoubleValue());
            default -> true;
        };
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Map<String, StoredValue> convertFields(Map<String, Value> protoFields) {
        Map<String, StoredValue> result = new LinkedHashMap<>();
        protoFields.forEach((k, v) -> result.put(k, StoredValue.fromProto(v)));
        return result;
    }
}
