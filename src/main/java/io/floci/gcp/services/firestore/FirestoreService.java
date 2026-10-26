package io.floci.gcp.services.firestore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.firestore.v1.Cursor;
import com.google.firestore.v1.Document;
import com.google.firestore.v1.DocumentTransform;
import com.google.firestore.v1.DocumentMask;
import com.google.firestore.v1.Precondition;
import com.google.firestore.v1.StructuredAggregationQuery;
import com.google.firestore.v1.StructuredQuery;
import com.google.firestore.v1.Target;
import com.google.firestore.v1.Value;
import com.google.firestore.v1.Write;
import com.google.protobuf.NullValue;
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

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.stream.Stream;

@ApplicationScoped
public class FirestoreService {

    private static final Logger LOG = Logger.getLogger(FirestoreService.class);
    private static final String NAME_FIELD = "__name__";
    private static final Set<StructuredQuery.FieldFilter.Operator> INEQUALITY_OPERATORS = EnumSet.of(
            StructuredQuery.FieldFilter.Operator.LESS_THAN,
            StructuredQuery.FieldFilter.Operator.LESS_THAN_OR_EQUAL,
            StructuredQuery.FieldFilter.Operator.GREATER_THAN,
            StructuredQuery.FieldFilter.Operator.GREATER_THAN_OR_EQUAL,
            StructuredQuery.FieldFilter.Operator.NOT_EQUAL,
            StructuredQuery.FieldFilter.Operator.NOT_IN);
    /** Unary filters that, like an inequality, require their field first in the order (query.proto). */
    private static final Set<StructuredQuery.UnaryFilter.Operator> UNARY_INEQUALITY_OPERATORS = EnumSet.of(
            StructuredQuery.UnaryFilter.Operator.IS_NOT_NAN,
            StructuredQuery.UnaryFilter.Operator.IS_NOT_NULL);

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
                .api("firestore.googleapis.com", "Cloud Firestore API")
                .usageAdapter(new FirestoreServiceUsageAdapter())
                .build());
        grpcServerManager.bind(new FirestoreController(this));
    }

    // ── Writes ─────────────────────────────────────────────────────────────────

    public record WriteCommitResult(String updateTime) {}

    public WriteCommitResult applyWrite(Write write, Instant commitTime) {
        synchronized (writeLock) {
            validateFieldPaths(write);
            checkPrecondition(write);
            WriteCommitResult result = applyWriteUnchecked(write, commitTime);
            publishToListeners(List.of(write));
            return result;
        }
    }

    /**
     * Applies a commit atomically: transaction read-set validation and all write
     * preconditions are checked against the pre-commit state before any write lands.
     */
    public List<WriteCommitResult> commit(List<Write> writes, byte[] transactionId, Instant commitTime) {
        synchronized (writeLock) {
            validateTransaction(transactionId);
            for (Write write : writes) {
                validateFieldPaths(write);
                checkPrecondition(write);
            }
            List<WriteCommitResult> results = new ArrayList<>(writes.size());
            for (Write write : writes) {
                results.add(applyWriteUnchecked(write, commitTime));
            }
            discardTransaction(transactionId);
            publishToListeners(writes);
            return results;
        }
    }

    private static void validateFieldPaths(Write write) {
        write.getUpdateMask().getFieldPathsList().forEach(FirestoreService::splitFieldPath);
        write.getUpdateTransformsList().forEach(t -> splitFieldPath(t.getFieldPath()));
        write.getTransform().getFieldTransformsList().forEach(t -> splitFieldPath(t.getFieldPath()));
    }

    private void checkPrecondition(Write write) {
        if (!write.hasCurrentDocument()) {
            return;
        }
        String name = writeTargetName(write);
        if (name.isEmpty()) {
            return;
        }
        Precondition pre = write.getCurrentDocument();
        Optional<StoredDocument> existing = documentStore.get(name);
        switch (pre.getConditionTypeCase()) {
            case EXISTS -> {
                if (pre.getExists() && existing.isEmpty()) {
                    throw GcpException.notFound("No document to update: " + name);
                }
                if (!pre.getExists() && existing.isPresent()) {
                    throw GcpException.alreadyExists("Document already exists: " + name);
                }
            }
            case UPDATE_TIME -> {
                Instant required = Instant.ofEpochSecond(
                        pre.getUpdateTime().getSeconds(), pre.getUpdateTime().getNanos());
                Instant actual = existing.map(d -> parseUpdateTime(d.getUpdateTime())).orElse(null);
                if (!required.equals(actual)) {
                    throw GcpException.failedPrecondition("Document " + name
                            + " has changed: required update time " + required
                            + ", actual " + (actual == null ? "(missing)" : actual));
                }
            }
            default -> {}
        }
    }

    /** Null for an absent or unparseable stored update time; that then fails any update-time precondition. */
    private static Instant parseUpdateTime(String storedUpdateTime) {
        if (storedUpdateTime == null) {
            return null;
        }
        try {
            return Instant.parse(storedUpdateTime);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String writeTargetName(Write write) {
        if (write.hasUpdate()) {
            return write.getUpdate().getName();
        }
        if (!write.getDelete().isEmpty()) {
            return write.getDelete();
        }
        if (write.hasTransform()) {
            return write.getTransform().getDocument();
        }
        return "";
    }

    private WriteCommitResult applyWriteUnchecked(Write write, Instant commitTime) {
        String now = commitTime.toString();

        if (write.hasUpdate()) {
            Document doc = write.getUpdate();
            String name = doc.getName();
            Map<String, StoredValue> incomingFields = convertFields(doc.getFieldsMap());

            if (write.hasUpdateMask()) {
                Optional<StoredDocument> existing = documentStore.get(name);
                Map<String, StoredValue> merged = new LinkedHashMap<>(
                        existing.map(StoredDocument::getFields).orElse(new LinkedHashMap<>()));

                for (String path : write.getUpdateMask().getFieldPathsList()) {
                    List<String> segments = splitFieldPath(path);
                    StoredValue val = getAtPath(incomingFields, segments);
                    if (val != null) {
                        putAtPath(merged, segments, val);
                    } else {
                        removeAtPath(merged, segments);
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
            for (DocumentTransform.FieldTransform transform : write.getUpdateTransformsList()) {
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
        for (DocumentTransform.FieldTransform transform : transforms) {
            applyFieldTransform(fields, transform, now);
        }
        documentStore.put(name, new StoredDocument(name, doc.getCreateTime(), now, fields));
    }

    private void applyFieldTransform(Map<String, StoredValue> fields,
            com.google.firestore.v1.DocumentTransform.FieldTransform transform, String now) {
        List<String> path = splitFieldPath(transform.getFieldPath());
        if (transform.hasSetToServerValue()
                && transform.getSetToServerValue() == com.google.firestore.v1.DocumentTransform.FieldTransform.ServerValue.REQUEST_TIME) {
            StoredValue ts = new StoredValue();
            ts.setType("timestamp");
            ts.setStringValue(StoredValue.toInstant(now).toString());
            putAtPath(fields, path, ts);
        } else if (transform.hasIncrement()) {
            Value inc = transform.getIncrement();
            StoredValue current = getAtPath(fields, path);
            if (inc.getValueTypeCase() == Value.ValueTypeCase.INTEGER_VALUE) {
                long base = (current != null && "integer".equals(current.getType()) && current.getIntegerValue() != null)
                        ? current.getIntegerValue() : 0L;
                StoredValue result = new StoredValue();
                result.setType("integer");
                result.setIntegerValue(base + inc.getIntegerValue());
                putAtPath(fields, path, result);
            } else if (inc.getValueTypeCase() == Value.ValueTypeCase.DOUBLE_VALUE) {
                double base = (current != null && current.getDoubleValue() != null)
                        ? current.getDoubleValue() : 0.0;
                StoredValue result = new StoredValue();
                result.setType("double");
                result.setDoubleValue(base + inc.getDoubleValue());
                putAtPath(fields, path, result);
            }
        } else if (transform.hasAppendMissingElements()) {
            StoredValue arr = getAtPath(fields, path);
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
            putAtPath(fields, path, result);
        } else if (transform.hasRemoveAllFromArray()) {
            StoredValue arr = getAtPath(fields, path);
            if (arr != null && "array".equals(arr.getType()) && arr.getArrayValue() != null) {
                List<StoredValue> filtered = arr.getArrayValue().stream()
                        .filter(e -> transform.getRemoveAllFromArray().getValuesList().stream()
                                .noneMatch(e::matchesEqual))
                        .toList();
                StoredValue result = new StoredValue();
                result.setType("array");
                result.setArrayValue(new ArrayList<>(filtered));
                putAtPath(fields, path, result);
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
        StructuredQuery.CollectionSelector from = query.getFromCount() > 0
                ? query.getFrom(0) : StructuredQuery.CollectionSelector.getDefaultInstance();
        List<StoredDocument> results = documentStore.scan(inCollection(parent, from));

        if (query.hasWhere()) {
            results = results.stream()
                    .filter(doc -> matchesFilter(doc, query.getWhere()))
                    .toList();
        }

        // Firestore order of operations: where → order by → cursors → offset → limit
        List<StructuredQuery.Order> orders = normalizedOrderBy(query.getOrderByList(),
                query.hasWhere() ? query.getWhere() : null);
        results = withOrderedFields(results, query.getOrderByList());
        results = sortByOrderBy(results, orders);
        results = applyCursors(results, query, orders);
        return applyLimitAndOffset(results, query);
    }

    /**
     * Lists the documents ListDocuments returns before paging: the collection's direct children,
     * or those of every collection under {@code parent} when {@code collectionId} is empty,
     * ordered by the string {@code order_by} ({@code __name__ ASC} when absent).
     */
    public List<StoredDocument> listDocuments(String parent, String collectionId, String orderBy) {
        LOG.debugf("listDocuments parent=%s collection=%s", parent, collectionId);
        List<StructuredQuery.Order> explicit = parseOrderBy(orderBy);
        List<StoredDocument> docs = documentStore.scan(inCollection(parent,
                StructuredQuery.CollectionSelector.newBuilder().setCollectionId(collectionId).build()));
        return sortByOrderBy(withOrderedFields(docs, explicit), normalizedOrderBy(explicit, null));
    }

    static Predicate<String> inCollection(String parent,
            StructuredQuery.CollectionSelector from) {
        String collectionId = from.getCollectionId();
        if (!from.getAllDescendants()) {
            if (collectionId.isEmpty()) {
                String parentPrefix = parent + "/";
                return name -> {
                    if (!name.startsWith(parentPrefix)) {
                        return false;
                    }
                    int slash = name.indexOf('/', parentPrefix.length());
                    return slash > parentPrefix.length() && name.indexOf('/', slash + 1) < 0;
                };
            }
            String prefix = parent + "/" + collectionId + "/";
            return name -> name.startsWith(prefix) && name.indexOf('/', prefix.length()) < 0;
        }
        String prefix = parent + "/";
        if (collectionId.isEmpty()) {
            return name -> name.startsWith(prefix);
        }
        return name -> {
            if (!name.startsWith(prefix)) {
                return false;
            }
            int idEnd = name.lastIndexOf('/');
            int idStart = name.lastIndexOf('/', idEnd - 1) + 1;
            return idStart >= prefix.length()
                    && idEnd - idStart == collectionId.length()
                    && name.startsWith(collectionId, idStart);
        };
    }

    /**
     * Completes the order as Standard edition does (google/firestore/v1/query.proto
     * {@code StructuredQuery.order_by}): inequality-filtered fields not already ordered are
     * appended in field path order, then {@code __name__}, all in the last explicit direction.
     */
    private static List<StructuredQuery.Order> normalizedOrderBy(List<StructuredQuery.Order> explicit,
            StructuredQuery.Filter where) {
        List<StructuredQuery.Order> orders = new ArrayList<>(explicit);
        Set<String> ordered = new HashSet<>();
        explicit.forEach(order -> ordered.add(order.getField().getFieldPath()));
        StructuredQuery.Direction direction = explicit.isEmpty()
                ? StructuredQuery.Direction.ASCENDING
                : explicit.getLast().getDirection();

        TreeSet<String> inequalityFields = new TreeSet<>(FirestoreService::compareFieldPaths);
        if (where != null) {
            collectInequalityFields(where, inequalityFields);
        }
        for (String field : inequalityFields) {
            if (!ordered.contains(field) && !NAME_FIELD.equals(field)) {
                orders.add(order(field, direction));
            }
        }
        if (!ordered.contains(NAME_FIELD)) {
            orders.add(order(NAME_FIELD, direction));
        }
        return orders;
    }

    private static StructuredQuery.Order order(String fieldPath, StructuredQuery.Direction direction) {
        return StructuredQuery.Order.newBuilder()
                .setField(StructuredQuery.FieldReference.newBuilder().setFieldPath(fieldPath))
                .setDirection(direction)
                .build();
    }

    private static void collectInequalityFields(StructuredQuery.Filter filter, Set<String> fields) {
        if (filter.hasCompositeFilter()) {
            filter.getCompositeFilter().getFiltersList().forEach(f -> collectInequalityFields(f, fields));
        } else if (filter.hasFieldFilter()
                && INEQUALITY_OPERATORS.contains(filter.getFieldFilter().getOp())) {
            fields.add(filter.getFieldFilter().getField().getFieldPath());
        } else if (filter.hasUnaryFilter()
                && UNARY_INEQUALITY_OPERATORS.contains(filter.getUnaryFilter().getOp())) {
            fields.add(filter.getUnaryFilter().getField().getFieldPath());
        }
    }

    private static int compareFieldPaths(String left, String right) {
        List<String> a = splitFieldPath(left);
        List<String> b = splitFieldPath(right);
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = a.get(i).compareTo(b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    /** An explicit order on a field also filters out documents that lack that field. */
    private List<StoredDocument> withOrderedFields(List<StoredDocument> docs,
            List<StructuredQuery.Order> explicit) {
        List<String> paths = explicit.stream()
                .map(order -> order.getField().getFieldPath())
                .filter(path -> !NAME_FIELD.equals(path))
                .toList();
        if (paths.isEmpty()) {
            return docs;
        }
        return docs.stream()
                .filter(doc -> paths.stream().allMatch(path -> resolveFieldPath(doc, path) != null))
                .toList();
    }

    /** Parses ListDocuments {@code order_by}, such as {@code priority desc, __name__ desc}. */
    static List<StructuredQuery.Order> parseOrderBy(String orderBy) {
        List<StructuredQuery.Order> orders = new ArrayList<>();
        if (orderBy.isBlank()) {
            return orders;
        }
        for (String term : splitOutsideBackticks(orderBy, c -> c == ',')) {
            List<String> tokens = splitOutsideBackticks(term, Character::isWhitespace).stream()
                    .filter(token -> !token.isEmpty())
                    .toList();
            StructuredQuery.Direction direction;
            if (tokens.size() == 1) {
                direction = StructuredQuery.Direction.ASCENDING;
            } else if (tokens.size() == 2 && "asc".equalsIgnoreCase(tokens.get(1))) {
                direction = StructuredQuery.Direction.ASCENDING;
            } else if (tokens.size() == 2 && "desc".equalsIgnoreCase(tokens.get(1))) {
                direction = StructuredQuery.Direction.DESCENDING;
            } else {
                throw GcpException.invalidArgument("Invalid order_by: " + orderBy);
            }
            splitFieldPath(tokens.getFirst());
            orders.add(order(tokens.getFirst(), direction));
        }
        return orders;
    }

    private static List<String> splitOutsideBackticks(String text, IntPredicate separator) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted && c == '\\' && i + 1 < text.length()) {
                current.append(c).append(text.charAt(++i));
                continue;
            }
            if (c == '`') {
                quoted = !quoted;
            }
            if (!quoted && separator.test(c)) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        parts.add(current.toString());
        return parts;
    }

    private List<StoredDocument> sortByOrderBy(List<StoredDocument> docs, List<StructuredQuery.Order> orders) {
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
        if (NAME_FIELD.equals(path)) {
            return FirestoreValueOrder.compareResourceNames(a.getName(), b.getName());
        }
        StoredValue va = resolveFieldPath(a, path);
        StoredValue vb = resolveFieldPath(b, path);
        if (va == null && vb == null) {
            return 0;
        }
        if (va == null) {
            return -1;
        }
        if (vb == null) {
            return 1;
        }
        return FirestoreValueOrder.compare(va, vb);
    }

    /**
     * Applies {@code start_at} / {@code end_at} cursors against the already-sorted results.
     * Cursor {@code before} semantics: start_at before=true is inclusive (startAt) and
     * before=false is exclusive (startAfter); end_at before=false is inclusive (endAt) and
     * before=true is exclusive (endBefore).
     */
    private List<StoredDocument> applyCursors(List<StoredDocument> docs, StructuredQuery query,
            List<StructuredQuery.Order> orders) {
        if (!query.hasStartAt() && !query.hasEndAt()) {
            return docs;
        }
        Stream<StoredDocument> stream = docs.stream();
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
        if (NAME_FIELD.equals(path)) {
            String target = value.hasReferenceValue() ? value.getReferenceValue() : value.getStringValue();
            return FirestoreValueOrder.compareResourceNames(doc.getName(), target);
        }
        StoredValue stored = resolveFieldPath(doc, path);
        if (stored == null) {
            return -1;
        }
        StoredValue target = orderable(value);
        return target == null ? 0 : FirestoreValueOrder.compare(stored, target);
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

    /**
     * Evaluates each aggregation over the documents the nested query returns, following the rules
     * on {@code StructuredAggregationQuery.Aggregation} in google/firestore/v1/query.proto.
     */
    public Map<String, Value> runAggregationQuery(String parent, StructuredAggregationQuery query) {
        List<StoredDocument> docs = runQuery(parent,
                query.hasStructuredQuery() ? query.getStructuredQuery() : StructuredQuery.getDefaultInstance());
        Map<String, Value> results = new LinkedHashMap<>();
        int unnamed = 0;
        for (StructuredAggregationQuery.Aggregation aggregation : query.getAggregationsList()) {
            String alias = aggregation.getAlias().isEmpty() ? "field_" + ++unnamed : aggregation.getAlias();
            results.put(alias, switch (aggregation.getOperatorCase()) {
                case COUNT -> count(docs.size(), aggregation.getCount());
                case SUM -> sum(numericValues(docs, aggregation.getSum().getField().getFieldPath()));
                case AVG -> avg(numericValues(docs, aggregation.getAvg().getField().getFieldPath()));
                case OPERATOR_NOT_SET -> throw GcpException.invalidArgument("Aggregation operator is required");
            });
        }
        return results;
    }

    private static Value count(long count, StructuredAggregationQuery.Aggregation.Count spec) {
        if (spec.hasUpTo()) {
            long upTo = spec.getUpTo().getValue();
            if (upTo <= 0) {
                throw GcpException.invalidArgument("count up_to must be greater than zero");
            }
            count = Math.min(count, upTo);
        }
        return Value.newBuilder().setIntegerValue(count).build();
    }

    /** Only integer and double values are aggregated; every other type, including null, is skipped. */
    private List<StoredValue> numericValues(List<StoredDocument> docs, String path) {
        return docs.stream()
                .map(doc -> resolveFieldPath(doc, path))
                .filter(v -> v != null && ("integer".equals(v.getType()) && v.getIntegerValue() != null
                        || "double".equals(v.getType()) && v.getDoubleValue() != null))
                .toList();
    }

    /**
     * An integer when every value is an integer and the final total fits in 64 bits, otherwise a
     * double. The integers are added exactly, so an intermediate overflow that later cancels out
     * still yields an integer.
     */
    private static Value sum(List<StoredValue> values) {
        BigInteger integerSum = BigInteger.ZERO;
        double doubleSum = 0;
        boolean anyDouble = false;
        for (StoredValue v : values) {
            if ("integer".equals(v.getType())) {
                integerSum = integerSum.add(BigInteger.valueOf(v.getIntegerValue()));
            } else {
                anyDouble = true;
                doubleSum += v.getDoubleValue();
            }
        }
        if (!anyDouble && integerSum.bitLength() < Long.SIZE) {
            return Value.newBuilder().setIntegerValue(integerSum.longValue()).build();
        }
        return Value.newBuilder().setDoubleValue(integerSum.doubleValue() + doubleSum).build();
    }

    /** Always a double, and null when there is nothing to average. */
    private static Value avg(List<StoredValue> values) {
        if (values.isEmpty()) {
            return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
        }
        double total = 0;
        for (StoredValue v : values) {
            total += "integer".equals(v.getType()) ? v.getIntegerValue() : v.getDoubleValue();
        }
        return Value.newBuilder().setDoubleValue(total / values.size()).build();
    }

    // ── Transactions ───────────────────────────────────────────────────────────

    private static final Duration TRANSACTION_TTL = Duration.ofMinutes(15);

    private final Object writeLock = new Object();

    /** https://firebase.google.com/docs/firestore/quotas#limits: maximum depth of fields in a map or array. */
    private static final int MAX_FIELD_DEPTH = 20;
    private final Map<String, TransactionState> transactions = new ConcurrentHashMap<>();

    private static final class TransactionState {
        final Instant startedAt = Instant.now();
        // document name -> updateTime at first read; empty Optional = document was absent
        final Map<String, Optional<String>> readVersions = new HashMap<>();
    }

    public byte[] beginTransaction() {
        pruneExpiredTransactions();
        byte[] id = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
        transactions.put(toTransactionKey(id), new TransactionState());
        return id;
    }

    /** Records the version of a document read under a transaction (first read wins). */
    public void recordTransactionRead(byte[] transactionId, String docName) {
        synchronized (writeLock) {
            recordTransactionRead(transactionId, docName,
                    documentStore.get(docName).map(StoredDocument::getUpdateTime).orElse(null));
        }
    }

    /**
     * Records a read against the transaction's read set. Callers must pass the version of the
     * snapshot they returned to the client, not the store's current version: a write landing
     * between the read and this call would otherwise go undetected at commit.
     *
     * @param updateTime the returned snapshot's update time, or null if the document was absent
     */
    public void recordTransactionRead(byte[] transactionId, String docName, String updateTime) {
        if (transactionId == null || transactionId.length == 0) {
            return;
        }
        TransactionState state = transactions.get(toTransactionKey(transactionId));
        if (state == null) {
            return;
        }
        synchronized (writeLock) {
            state.readVersions.putIfAbsent(docName, Optional.ofNullable(updateTime));
        }
    }

    public void rollback(byte[] transactionId) {
        discardTransaction(transactionId);
    }

    /**
     * Optimistic-concurrency check: every document read under the transaction must still
     * be at the version observed at read time, otherwise the commit aborts. Callers must
     * hold {@code writeLock}. Unknown transaction ids pass through unchecked (e.g. state
     * lost across emulator restart). The read set is retained on failure so a retried commit
     * of the same transaction id is validated again.
     */
    private void validateTransaction(byte[] transactionId) {
        if (transactionId == null || transactionId.length == 0) {
            return;
        }
        TransactionState state = transactions.get(toTransactionKey(transactionId));
        if (state == null) {
            return;
        }
        for (Map.Entry<String, Optional<String>> read : state.readVersions.entrySet()) {
            Optional<String> current = documentStore.get(read.getKey())
                    .map(StoredDocument::getUpdateTime);
            if (!current.equals(read.getValue())) {
                throw GcpException.aborted(
                        "Transaction aborted: document " + read.getKey()
                                + " was modified after it was read. Retry the transaction.");
            }
        }
    }

    private void discardTransaction(byte[] transactionId) {
        if (transactionId == null || transactionId.length == 0) {
            return;
        }
        transactions.remove(toTransactionKey(transactionId));
    }

    private void pruneExpiredTransactions() {
        Instant cutoff = Instant.now().minus(TRANSACTION_TTL);
        transactions.values().removeIf(state -> state.startedAt.isBefore(cutoff));
    }

    /** Transaction ids are opaque bytes from the client and need not be valid UTF-8. */
    private static String toTransactionKey(byte[] transactionId) {
        return HexFormat.of().formatHex(transactionId);
    }

    // ── Listen ─────────────────────────────────────────────────────────────────
    //
    // Listen state is guarded by writeLock, the lock every write holds. A target's initial snapshot
    // and its registration happen in one critical section, and each write publishes its changes
    // before it releases the lock, so a listener sees every committed write exactly once, in commit
    // order, and never one that its initial snapshot already contained.

    /** Streams with at least one target; guarded by writeLock. */
    private final Set<FirestoreListenStream> listenStreams = new LinkedHashSet<>();
    /** Last read time handed to a listener; guarded by writeLock. */
    private Instant lastListenReadTime = Instant.EPOCH;

    public void addListenTarget(FirestoreListenStream stream, Target target) {
        synchronized (writeLock) {
            if (stream.isClosed()) {
                return;
            }
            stream.addTarget(target, nextListenReadTime());
            listenStreams.add(stream);
        }
    }

    public void removeListenTarget(FirestoreListenStream stream, int targetId) {
        synchronized (writeLock) {
            if (!stream.isClosed()) {
                stream.removeTarget(targetId);
            }
        }
    }

    /**
     * Stops publishing to the stream. Returns whether it was still open, so the caller ends the
     * call exactly once; once this returns, no write can send on the stream again.
     */
    public boolean closeListen(FirestoreListenStream stream) {
        synchronized (writeLock) {
            listenStreams.remove(stream);
            return stream.close();
        }
    }

    /** Callers must hold writeLock. */
    private void publishToListeners(List<Write> writes) {
        if (listenStreams.isEmpty()) {
            return;
        }
        List<String> names = writes.stream()
                .map(FirestoreService::writeTargetName)
                .filter(name -> !name.isEmpty())
                .toList();
        if (names.isEmpty()) {
            return;
        }
        Instant readTime = nextListenReadTime();
        for (FirestoreListenStream stream : new ArrayList<>(listenStreams)) {
            try {
                stream.publish(names, readTime);
            } catch (RuntimeException e) {
                // The write has already landed: a listener that cannot be reached, typically a
                // cancelled call, is dropped instead of failing the write.
                LOG.debugf("dropping listen stream after a failed send: %s", e.getMessage());
                listenStreams.remove(stream);
                stream.close();
            }
        }
    }

    /**
     * TargetChange.read_time is monotonically increasing per stream (firestore.proto). Commit times
     * are taken before writeLock is acquired, so they are not, and listeners use this clock instead.
     * Callers must hold writeLock.
     */
    private Instant nextListenReadTime() {
        Instant now = Instant.now();
        lastListenReadTime = now.isAfter(lastListenReadTime) ? now : lastListenReadTime.plusNanos(1);
        return lastListenReadTime;
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
        StoredValue stored = resolveFieldPath(doc, path);
        Value filterValue = ff.getValue();
        OptionalInt filterComparison = stored == null
                ? OptionalInt.empty()
                : compareFilterValues(stored, filterValue);

        return switch (ff.getOp()) {
            case EQUAL -> stored != null && stored.matchesEqual(filterValue);
            case NOT_EQUAL -> stored == null || !stored.matchesEqual(filterValue);
            case LESS_THAN -> filterComparison.isPresent() && filterComparison.getAsInt() < 0;
            case LESS_THAN_OR_EQUAL -> filterComparison.isPresent() && filterComparison.getAsInt() <= 0;
            case GREATER_THAN -> filterComparison.isPresent() && filterComparison.getAsInt() > 0;
            case GREATER_THAN_OR_EQUAL -> filterComparison.isPresent() && filterComparison.getAsInt() >= 0;
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

    /**
     * Compares values for inequality filters, which match only values of the same type as the
     * filter value (integers and doubles are both numbers). Null and NaN satisfy no inequality.
     */
    private OptionalInt compareFilterValues(StoredValue stored, Value proto) {
        StoredValue filterValue = orderable(proto);
        if (filterValue == null
                || !FirestoreValueOrder.sameType(stored, filterValue)
                || FirestoreValueOrder.isNull(stored)
                || FirestoreValueOrder.isNaN(stored)
                || FirestoreValueOrder.isNaN(filterValue)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(FirestoreValueOrder.compare(stored, filterValue));
    }

    /** Converts a query value for comparison, or null when it holds an unrepresentable timestamp. */
    private static StoredValue orderable(Value value) {
        try {
            return StoredValue.fromProto(value);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private boolean matchesUnaryFilter(StoredDocument doc, StructuredQuery.UnaryFilter uf) {
        String path = uf.getField().getFieldPath();
        StoredValue stored = resolveFieldPath(doc, path);
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

    private StoredValue resolveFieldPath(StoredDocument doc, String path) {
        if (doc.getFields() == null) {
            return null;
        }
        return getAtPath(doc.getFields(), splitFieldPath(path));
    }

    /**
     * Splits a field path into segments, unquoting backtick-quoted segments.
     * Rejects empty segments, unterminated quotes or escapes, and paths deeper
     * than Firestore's documented maximum field depth.
     */
    static List<String> splitFieldPath(String path) {
        List<String> segments = new ArrayList<>();
        StringBuilder segment = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (quoted && c == '\\') {
                if (++i == path.length()) {
                    throw invalidFieldPath(path);
                }
                segment.append(path.charAt(i));
            } else if (c == '`') {
                quoted = !quoted;
            } else if (c == '.' && !quoted) {
                addSegment(segments, segment, path);
            } else {
                segment.append(c);
            }
        }
        if (quoted) {
            throw invalidFieldPath(path);
        }
        addSegment(segments, segment, path);
        return segments;
    }

    private static void addSegment(List<String> segments, StringBuilder segment, String path) {
        if (segment.isEmpty() || segments.size() == MAX_FIELD_DEPTH) {
            throw invalidFieldPath(path);
        }
        segments.add(segment.toString());
        segment.setLength(0);
    }

    private static GcpException invalidFieldPath(String path) {
        return GcpException.invalidArgument("Invalid field path: " + path);
    }

    private static StoredValue getAtPath(Map<String, StoredValue> fields, List<String> path) {
        StoredValue value = fields.get(path.get(0));
        for (String segment : path.subList(1, path.size())) {
            if (value == null || !"map".equals(value.getType()) || value.getMapValue() == null) {
                return null;
            }
            value = value.getMapValue().get(segment);
        }
        return value;
    }

    /** Sets a nested field, copying each map on the way so stored documents are never mutated. */
    private static void putAtPath(Map<String, StoredValue> fields, List<String> path, StoredValue value) {
        if (path.size() == 1) {
            fields.put(path.get(0), value);
            return;
        }
        StoredValue parent = fields.get(path.get(0));
        Map<String, StoredValue> children = parent != null && "map".equals(parent.getType())
                && parent.getMapValue() != null
                ? new LinkedHashMap<>(parent.getMapValue()) : new LinkedHashMap<>();
        putAtPath(children, path.subList(1, path.size()), value);
        StoredValue map = new StoredValue();
        map.setType("map");
        map.setMapValue(children);
        fields.put(path.get(0), map);
    }

    private static void removeAtPath(Map<String, StoredValue> fields, List<String> path) {
        if (path.size() == 1) {
            fields.remove(path.get(0));
            return;
        }
        StoredValue parent = fields.get(path.get(0));
        if (parent == null || !"map".equals(parent.getType()) || parent.getMapValue() == null) {
            return;
        }
        Map<String, StoredValue> children = new LinkedHashMap<>(parent.getMapValue());
        removeAtPath(children, path.subList(1, path.size()));
        StoredValue map = new StoredValue();
        map.setType("map");
        map.setMapValue(children);
        fields.put(path.get(0), map);
    }

    private Map<String, StoredValue> convertFields(Map<String, Value> protoFields) {
        Map<String, StoredValue> result = new LinkedHashMap<>();
        protoFields.forEach((k, v) -> result.put(k, StoredValue.fromProto(v)));
        return result;
    }
}
