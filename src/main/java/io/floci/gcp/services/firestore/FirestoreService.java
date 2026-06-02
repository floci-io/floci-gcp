package io.floci.gcp.services.firestore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.firestore.v1.Cursor;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
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

    FirestoreService(StorageBackend<String, StoredDocument> documentStore) {
        this.documentStore = documentStore;
        this.serviceRegistry = null;
        this.config = null;
        this.grpcServerManager = null;
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
        } else if (transform.hasIncrement()) {
            Value inc = transform.getIncrement();
            StoredValue current = fields.get(path);
            if (inc.getValueTypeCase() == Value.ValueTypeCase.INTEGER_VALUE) {
                long base = (current != null && "integer".equals(current.getType()) && current.getIntegerValue() != null)
                        ? current.getIntegerValue() : 0L;
                StoredValue result = new StoredValue();
                result.setType("integer");
                result.setIntegerValue(base + inc.getIntegerValue());
                fields.put(path, result);
            } else if (inc.getValueTypeCase() == Value.ValueTypeCase.DOUBLE_VALUE) {
                double base = (current != null && current.getDoubleValue() != null)
                        ? current.getDoubleValue() : 0.0;
                StoredValue result = new StoredValue();
                result.setType("double");
                result.setDoubleValue(base + inc.getDoubleValue());
                fields.put(path, result);
            }
        } else if (transform.hasAppendMissingElements()) {
            StoredValue arr = fields.get(path);
            List<StoredValue> existing = (arr != null && "array".equals(arr.getType()) && arr.getArrayValue() != null)
                    ? new ArrayList<>(arr.getArrayValue()) : new ArrayList<>();
            for (Value v : transform.getAppendMissingElements().getValuesList()) {
                StoredValue sv = StoredValue.fromProto(v);
                boolean found = existing.stream().anyMatch(e -> e.matchesEqual(v));
                if (!found) {
                    existing.add(sv);
                }
            }
            StoredValue result = new StoredValue();
            result.setType("array");
            result.setArrayValue(existing);
            fields.put(path, result);
        } else if (transform.hasRemoveAllFromArray()) {
            StoredValue arr = fields.get(path);
            if (arr != null && "array".equals(arr.getType()) && arr.getArrayValue() != null) {
                List<StoredValue> filtered = arr.getArrayValue().stream()
                        .filter(e -> transform.getRemoveAllFromArray().getValuesList().stream()
                                .noneMatch(e::matchesEqual))
                        .toList();
                StoredValue result = new StoredValue();
                result.setType("array");
                result.setArrayValue(new ArrayList<>(filtered));
                fields.put(path, result);
            }
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

        List<StoredDocument> results = documentStore.scan(k -> k.startsWith(prefix)
                && k.substring(prefix.length()).indexOf('/') < 0);

        if (query.hasWhere()) {
            results = results.stream()
                    .filter(doc -> matchesFilter(doc, query.getWhere()))
                    .toList();
        }

        // Firestore order of operations: where → order by → cursors → offset → limit
        results = sortByOrderBy(results, query);
        results = applyCursors(results, query);
        return applyLimitAndOffset(results, query);
    }

    /**
     * Sorts results by the query's {@code orderBy} clauses. Only sorts when an explicit
     * orderBy is present (preserving prior behavior for unordered queries), or when cursors
     * are used without an explicit orderBy — in which case Firestore implicitly orders by
     * document name.
     */
    private List<StoredDocument> sortByOrderBy(List<StoredDocument> docs, StructuredQuery query) {
        List<StructuredQuery.Order> orders = query.getOrderByList();
        if (orders.isEmpty()) {
            if (!query.hasStartAt() && !query.hasEndAt()) {
                return docs;
            }
            return docs.stream().sorted(Comparator.comparing(StoredDocument::getName)).toList();
        }
        Comparator<StoredDocument> comparator = null;
        for (StructuredQuery.Order order : orders) {
            String path = order.getField().getFieldPath();
            Comparator<StoredDocument> c = (a, b) -> compareDocsByField(a, b, path);
            if (order.getDirection() == StructuredQuery.Direction.DESCENDING) {
                c = c.reversed();
            }
            comparator = (comparator == null) ? c : comparator.thenComparing(c);
        }
        return docs.stream().sorted(comparator).toList();
    }

    private int compareDocsByField(StoredDocument a, StoredDocument b, String path) {
        if ("__name__".equals(path)) {
            return a.getName().compareTo(b.getName());
        }
        StoredValue va = a.getFields() != null ? a.getFields().get(path) : null;
        StoredValue vb = b.getFields() != null ? b.getFields().get(path) : null;
        if (va == null && vb == null) {
            return 0;
        }
        if (va == null) {
            return -1;
        }
        if (vb == null) {
            return 1;
        }
        return compareValues(va, vb.toProto());
    }

    /**
     * Applies {@code start_at} / {@code end_at} cursors against the already-sorted results.
     * Cursor {@code before} semantics: start_at before=true is inclusive (startAt) and
     * before=false is exclusive (startAfter); end_at before=false is inclusive (endAt) and
     * before=true is exclusive (endBefore).
     */
    private List<StoredDocument> applyCursors(List<StoredDocument> docs, StructuredQuery query) {
        if (!query.hasStartAt() && !query.hasEndAt()) {
            return docs;
        }
        List<StructuredQuery.Order> orders = query.getOrderByList();
        var stream = docs.stream();
        if (query.hasStartAt()) {
            Cursor start = query.getStartAt();
            boolean inclusive = start.getBefore();
            stream = stream.filter(doc -> {
                int c = compareDocToCursor(doc, start.getValuesList(), orders);
                return inclusive ? c >= 0 : c > 0;
            });
        }
        if (query.hasEndAt()) {
            Cursor end = query.getEndAt();
            boolean exclusive = end.getBefore();
            stream = stream.filter(doc -> {
                int c = compareDocToCursor(doc, end.getValuesList(), orders);
                return exclusive ? c < 0 : c <= 0;
            });
        }
        return stream.toList();
    }

    private int compareDocToCursor(StoredDocument doc, List<Value> cursorValues,
            List<StructuredQuery.Order> orders) {
        int n = Math.min(cursorValues.size(), orders.size());
        for (int i = 0; i < n; i++) {
            StructuredQuery.Order order = orders.get(i);
            int c = compareDocFieldToValue(doc, order.getField().getFieldPath(), cursorValues.get(i));
            if (order.getDirection() == StructuredQuery.Direction.DESCENDING) {
                c = -c;
            }
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private int compareDocFieldToValue(StoredDocument doc, String path, Value value) {
        if ("__name__".equals(path)) {
            String target = value.hasReferenceValue() ? value.getReferenceValue() : value.getStringValue();
            return doc.getName().compareTo(target);
        }
        StoredValue stored = doc.getFields() != null ? doc.getFields().get(path) : null;
        if (stored == null) {
            return -1;
        }
        return compareValues(stored, value);
    }

    private List<StoredDocument> applyLimitAndOffset(List<StoredDocument> docs, StructuredQuery query) {
        int offset = query.getOffset();
        int limit = query.hasLimit() ? query.getLimit().getValue() : Integer.MAX_VALUE;
        if (offset > 0 || limit < Integer.MAX_VALUE) {
            return docs.stream().skip(offset).limit(limit).toList();
        }
        return docs;
    }

    public List<String> listCollectionIds(String parent) {
        LOG.debugf("listCollectionIds parent=%s", parent);
        String prefix = parent + "/";
        TreeSet<String> ids = new TreeSet<>();
        documentStore.scan(k -> k.startsWith(prefix)).forEach(doc -> {
            String relative = doc.getName().substring(prefix.length());
            int slash = relative.indexOf('/');
            if (slash > 0) {
                ids.add(relative.substring(0, slash));
            }
        });
        return new ArrayList<>(ids);
    }

    public long countDocuments(String parent, StructuredQuery query) {
        return runQuery(parent, query).size();
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
            case LESS_THAN -> stored != null && compareValues(stored, filterValue) < 0;
            case LESS_THAN_OR_EQUAL -> stored != null && compareValues(stored, filterValue) <= 0;
            case GREATER_THAN -> stored != null && compareValues(stored, filterValue) > 0;
            case GREATER_THAN_OR_EQUAL -> stored != null && compareValues(stored, filterValue) >= 0;
            case ARRAY_CONTAINS -> stored != null && "array".equals(stored.getType())
                    && stored.getArrayValue() != null
                    && stored.getArrayValue().stream().anyMatch(sv -> sv.matchesEqual(filterValue));
            case IN -> filterValue.hasArrayValue()
                    && filterValue.getArrayValue().getValuesList().stream()
                        .anyMatch(v -> stored != null && stored.matchesEqual(v));
            case NOT_IN -> stored == null || (filterValue.hasArrayValue()
                    && filterValue.getArrayValue().getValuesList().stream()
                        .noneMatch(v -> stored.matchesEqual(v)));
            case ARRAY_CONTAINS_ANY -> stored != null && "array".equals(stored.getType())
                    && stored.getArrayValue() != null && filterValue.hasArrayValue()
                    && filterValue.getArrayValue().getValuesList().stream()
                        .anyMatch(fv -> stored.getArrayValue().stream().anyMatch(sv -> sv.matchesEqual(fv)));
            default -> true;
        };
    }

    private int compareValues(StoredValue stored, Value proto) {
        return switch (proto.getValueTypeCase()) {
            case INTEGER_VALUE -> {
                if ("integer".equals(stored.getType()) && stored.getIntegerValue() != null)
                    yield Long.compare(stored.getIntegerValue(), proto.getIntegerValue());
                if ("double".equals(stored.getType()) && stored.getDoubleValue() != null)
                    yield Double.compare(stored.getDoubleValue(), (double) proto.getIntegerValue());
                yield 0;
            }
            case DOUBLE_VALUE -> {
                if ("double".equals(stored.getType()) && stored.getDoubleValue() != null)
                    yield Double.compare(stored.getDoubleValue(), proto.getDoubleValue());
                if ("integer".equals(stored.getType()) && stored.getIntegerValue() != null)
                    yield Double.compare((double) stored.getIntegerValue(), proto.getDoubleValue());
                yield 0;
            }
            case STRING_VALUE -> {
                if ("string".equals(stored.getType()) && stored.getStringValue() != null)
                    yield stored.getStringValue().compareTo(proto.getStringValue());
                yield 0;
            }
            case TIMESTAMP_VALUE -> {
                if ("timestamp".equals(stored.getType()) && stored.getStringValue() != null) {
                    try {
                        Instant a = Instant.parse(stored.getStringValue());
                        Instant b = Instant.ofEpochSecond(
                                proto.getTimestampValue().getSeconds(),
                                proto.getTimestampValue().getNanos());
                        yield a.compareTo(b);
                    } catch (Exception ignored) {}
                }
                yield 0;
            }
            default -> 0;
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
