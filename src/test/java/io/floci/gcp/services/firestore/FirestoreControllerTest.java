package io.floci.gcp.services.firestore;

import com.google.firestore.v1.Document;
import com.google.firestore.v1.DocumentMask;
import com.google.firestore.v1.ListDocumentsRequest;
import com.google.firestore.v1.ListDocumentsResponse;
import com.google.firestore.v1.RunAggregationQueryRequest;
import com.google.firestore.v1.RunAggregationQueryResponse;
import com.google.firestore.v1.RunQueryRequest;
import com.google.firestore.v1.RunQueryResponse;
import com.google.firestore.v1.StructuredAggregationQuery;
import com.google.firestore.v1.StructuredQuery;
import com.google.firestore.v1.TransactionOptions;
import com.google.firestore.v1.UpdateDocumentRequest;
import com.google.firestore.v1.Value;
import com.google.firestore.v1.Write;
import com.google.protobuf.Int64Value;
import com.google.protobuf.NullValue;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.firestore.model.StoredDocument;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class FirestoreControllerTest {

    private FirestoreService service;
    private FirestoreController controller;
    private static final String DB = "projects/p1/databases/(default)";
    private static final String PARENT = DB + "/documents";

    @BeforeEach
    void setUp() {
        service = new FirestoreService(new InMemoryStorage<String, StoredDocument>());
        controller = new FirestoreController(service);
    }

    private static final class CapturingObserver<T> implements StreamObserver<T> {
        final List<T> messages = new ArrayList<>();
        Throwable error;
        boolean completed;

        public void onNext(T value) { messages.add(value); }
        public void onError(Throwable t) { error = t; }
        public void onCompleted() { completed = true; }
    }

    private void seedDocument(String name) {
        Document doc = Document.newBuilder().setName(name).build();
        service.applyWrite(Write.newBuilder().setUpdate(doc).build(), Instant.now());
    }

    private static RunQueryRequest.Builder queryRequest(String collectionId) {
        return RunQueryRequest.newBuilder()
                .setParent(PARENT)
                .setStructuredQuery(StructuredQuery.newBuilder()
                        .addFrom(StructuredQuery.CollectionSelector.newBuilder()
                                .setCollectionId(collectionId)
                                .build()));
    }

    @Test
    void newTransactionQueryReturnsTransactionInDedicatedFirstResponse() {
        seedDocument(PARENT + "/col/doc1");
        seedDocument(PARENT + "/col/doc2");
        CapturingObserver<RunQueryResponse> observer = new CapturingObserver<>();

        controller.runQuery(queryRequest("col")
                .setNewTransaction(TransactionOptions.getDefaultInstance())
                .build(), observer);

        assertNull(observer.error);
        assertTrue(observer.completed);
        assertEquals(4, observer.messages.size());

        RunQueryResponse first = observer.messages.get(0);
        assertFalse(first.getTransaction().isEmpty());
        assertFalse(first.hasDocument());
        assertFalse(first.hasReadTime());
        assertFalse(first.getDone());

        for (RunQueryResponse later : observer.messages.subList(1, observer.messages.size())) {
            assertTrue(later.getTransaction().isEmpty());
        }
        assertTrue(observer.messages.get(1).hasDocument());
        assertTrue(observer.messages.get(3).getDone());
    }

    @Test
    void newTransactionQueryWithNoResultsStillReturnsTransactionFirst() {
        CapturingObserver<RunQueryResponse> observer = new CapturingObserver<>();

        controller.runQuery(queryRequest("empty")
                .setNewTransaction(TransactionOptions.getDefaultInstance())
                .build(), observer);

        assertNull(observer.error);
        assertEquals(2, observer.messages.size());

        RunQueryResponse first = observer.messages.get(0);
        assertFalse(first.getTransaction().isEmpty());
        assertFalse(first.hasDocument());
        assertFalse(first.hasReadTime());
        assertFalse(first.getDone());

        assertTrue(observer.messages.get(1).getDone());
        assertTrue(observer.messages.get(1).getTransaction().isEmpty());
    }

    @Test
    void listDocumentsPagesInNameOrderWithPageTokens() {
        for (String id : List.of("e", "c", "a", "d", "b")) {
            seedDocument(PARENT + "/col/" + id);
        }

        List<String> seen = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        String token = "";
        do {
            CapturingObserver<ListDocumentsResponse> observer = new CapturingObserver<>();
            controller.listDocuments(ListDocumentsRequest.newBuilder()
                    .setParent(PARENT)
                    .setCollectionId("col")
                    .setPageSize(2)
                    .setPageToken(token)
                    .build(), observer);
            assertNull(observer.error);
            ListDocumentsResponse page = observer.messages.get(0);
            pageSizes.add(page.getDocumentsCount());
            page.getDocumentsList().forEach(d -> seen.add(d.getName().substring(d.getName().lastIndexOf('/') + 1)));
            token = page.getNextPageToken();
        } while (!token.isEmpty());

        assertEquals(List.of("a", "b", "c", "d", "e"), seen);
        assertEquals(List.of(2, 2, 1), pageSizes);
    }

    @Test
    void pageTokenWithoutPageSizeReturnsTheRestOfTheListing() {
        for (String id : List.of("d", "c", "a", "b")) {
            seedDocument(PARENT + "/col/" + id);
        }
        CapturingObserver<ListDocumentsResponse> first = new CapturingObserver<>();
        controller.listDocuments(ListDocumentsRequest.newBuilder()
                .setParent(PARENT)
                .setCollectionId("col")
                .setPageSize(2)
                .build(), first);
        CapturingObserver<ListDocumentsResponse> rest = new CapturingObserver<>();

        controller.listDocuments(ListDocumentsRequest.newBuilder()
                .setParent(PARENT)
                .setCollectionId("col")
                .setPageToken(first.messages.get(0).getNextPageToken())
                .build(), rest);

        assertNull(rest.error);
        ListDocumentsResponse page = rest.messages.get(0);
        assertEquals(List.of(PARENT + "/col/c", PARENT + "/col/d"),
                page.getDocumentsList().stream().map(Document::getName).toList());
        assertTrue(page.getNextPageToken().isEmpty());
    }

    @Test
    void listDocumentsWithoutPageSizeReturnsEverythingInOnePage() {
        for (String id : List.of("b", "a", "c")) {
            seedDocument(PARENT + "/col/" + id);
        }
        CapturingObserver<ListDocumentsResponse> observer = new CapturingObserver<>();

        controller.listDocuments(ListDocumentsRequest.newBuilder()
                .setParent(PARENT)
                .setCollectionId("col")
                .setOrderBy("__name__ desc")
                .build(), observer);

        assertNull(observer.error);
        ListDocumentsResponse page = observer.messages.get(0);
        assertEquals(List.of(PARENT + "/col/c", PARENT + "/col/b", PARENT + "/col/a"),
                page.getDocumentsList().stream().map(Document::getName).toList());
        assertTrue(page.getNextPageToken().isEmpty());
    }

    @Test
    void queryWithoutNewTransactionNeverSetsTransaction() {
        seedDocument(PARENT + "/col/doc1");
        CapturingObserver<RunQueryResponse> observer = new CapturingObserver<>();

        controller.runQuery(queryRequest("col").build(), observer);

        assertNull(observer.error);
        assertEquals(2, observer.messages.size());
        for (RunQueryResponse resp : observer.messages) {
            assertTrue(resp.getTransaction().isEmpty());
        }
    }

    @Test
    void updateDocumentWithoutMaskOverwritesDocument() {
        String name = PARENT + "/col/doc1";
        controller.updateDocument(UpdateDocumentRequest.newBuilder()
                .setDocument(documentWith(name, "a", "1"))
                .build(), new CapturingObserver<>());
        CapturingObserver<Document> observer = new CapturingObserver<>();

        controller.updateDocument(UpdateDocumentRequest.newBuilder()
                .setDocument(documentWith(name, "b", "2"))
                .build(), observer);

        assertNull(observer.error);
        assertEquals(Set.of("b"), observer.messages.get(0).getFieldsMap().keySet());
        assertEquals(Set.of("b"), service.getDocument(name).orElseThrow().getFields().keySet());
    }

    @Test
    void updateDocumentWithMaskKeepsFieldsOutsideMask() {
        String name = PARENT + "/col/doc1";
        controller.updateDocument(UpdateDocumentRequest.newBuilder()
                .setDocument(documentWith(name, "a", "1"))
                .build(), new CapturingObserver<>());
        CapturingObserver<Document> observer = new CapturingObserver<>();

        controller.updateDocument(UpdateDocumentRequest.newBuilder()
                .setDocument(documentWith(name, "b", "2"))
                .setUpdateMask(DocumentMask.newBuilder().addFieldPaths("b"))
                .build(), observer);

        assertNull(observer.error);
        assertEquals(Set.of("a", "b"), service.getDocument(name).orElseThrow().getFields().keySet());
    }

    @Test
    void collectionGroupQueryStreamsMatchingDocumentsAtEveryDepth() {
        seedDocument(PARENT + "/users/alice/orders/o1");
        seedDocument(PARENT + "/shops/s1/branches/b1/orders/o2");
        seedDocument(PARENT + "/orders/o3");
        seedDocument(PARENT + "/users/alice/returns/r1");
        seedDocument(PARENT + "/users/alice/ordersArchive/o4");
        CapturingObserver<RunQueryResponse> observer = new CapturingObserver<>();

        controller.runQuery(RunQueryRequest.newBuilder()
                .setParent(PARENT)
                .setStructuredQuery(StructuredQuery.newBuilder()
                        .addFrom(StructuredQuery.CollectionSelector.newBuilder()
                                .setCollectionId("orders")
                                .setAllDescendants(true)))
                .build(), observer);

        assertNull(observer.error);
        assertTrue(observer.completed);
        assertEquals(Set.of(
                        PARENT + "/users/alice/orders/o1",
                        PARENT + "/shops/s1/branches/b1/orders/o2",
                        PARENT + "/orders/o3"),
                observer.messages.stream()
                        .filter(RunQueryResponse::hasDocument)
                        .map(resp -> resp.getDocument().getName())
                        .collect(Collectors.toSet()));
    }

    @Test
    void sumAndAvgAggregateTheFieldInsteadOfCountingDocuments() {
        seedPrice("a", Value.newBuilder().setIntegerValue(10).build());
        seedPrice("b", Value.newBuilder().setIntegerValue(20).build());
        seedPrice("c", Value.newBuilder().setStringValue("n/a").build());
        seedPrice("d", Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build());

        Map<String, Value> result = aggregate("items",
                count("n"), sum("total", "price"), avg("mean", "price"));

        assertEquals(Value.newBuilder().setIntegerValue(4).build(), result.get("n"));
        assertEquals(Value.newBuilder().setIntegerValue(30).build(), result.get("total"));
        assertEquals(Value.newBuilder().setDoubleValue(15.0).build(), result.get("mean"));
    }

    @Test
    void sumIsADoubleWhenAnyValueIsADoubleOrTheIntegersOverflow() {
        seedPrice("a", Value.newBuilder().setIntegerValue(10).build());
        seedPrice("b", Value.newBuilder().setDoubleValue(2.5).build());
        assertEquals(Value.newBuilder().setDoubleValue(12.5).build(),
                aggregate("items", sum("total", "price")).get("total"));

        seedDocument(PARENT + "/big/a", "price", Value.newBuilder().setIntegerValue(Long.MAX_VALUE).build());
        seedDocument(PARENT + "/big/b", "price", Value.newBuilder().setIntegerValue(1).build());
        assertEquals(Value.newBuilder().setDoubleValue((double) Long.MAX_VALUE + 1).build(),
                aggregate("big", sum("total", "price")).get("total"));
    }

    @Test
    void sumStaysAnIntegerWhenAnIntermediateOverflowCancelsOut() {
        seedDocument(PARENT + "/swing/a", "n", Value.newBuilder().setIntegerValue(Long.MAX_VALUE).build());
        seedDocument(PARENT + "/swing/b", "n", Value.newBuilder().setIntegerValue(1).build());
        seedDocument(PARENT + "/swing/c", "n", Value.newBuilder().setIntegerValue(-Long.MAX_VALUE).build());

        // Descending visits MAX, 1, -MAX, so the running total overflows before it comes back in range.
        StructuredQuery query = StructuredQuery.newBuilder()
                .addFrom(StructuredQuery.CollectionSelector.newBuilder().setCollectionId("swing"))
                .addOrderBy(StructuredQuery.Order.newBuilder()
                        .setField(StructuredQuery.FieldReference.newBuilder().setFieldPath("n"))
                        .setDirection(StructuredQuery.Direction.DESCENDING))
                .build();

        assertEquals(Value.newBuilder().setIntegerValue(1).build(), aggregate(query, sum("total", "n")).get("total"));
    }

    @Test
    void sumOfNothingIsZeroAndAvgOfNothingIsNull() {
        seedPrice("a", Value.newBuilder().setStringValue("n/a").build());

        Map<String, Value> result = aggregate("items", sum("total", "price"), avg("mean", "price"));

        assertEquals(Value.newBuilder().setIntegerValue(0).build(), result.get("total"));
        assertEquals(Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build(), result.get("mean"));
    }

    @Test
    void nanInTheAggregatedValuesMakesSumAndAvgNan() {
        seedPrice("a", Value.newBuilder().setIntegerValue(10).build());
        seedPrice("b", Value.newBuilder().setDoubleValue(Double.NaN).build());

        Map<String, Value> result = aggregate("items", sum("total", "price"), avg("mean", "price"));

        assertTrue(Double.isNaN(result.get("total").getDoubleValue()));
        assertTrue(Double.isNaN(result.get("mean").getDoubleValue()));
    }

    @Test
    void countUpToCapsTheCount() {
        seedPrice("a", Value.newBuilder().setIntegerValue(1).build());
        seedPrice("b", Value.newBuilder().setIntegerValue(2).build());
        seedPrice("c", Value.newBuilder().setIntegerValue(3).build());

        StructuredAggregationQuery.Aggregation upTo2 = StructuredAggregationQuery.Aggregation.newBuilder()
                .setAlias("n")
                .setCount(StructuredAggregationQuery.Aggregation.Count.newBuilder().setUpTo(Int64Value.of(2)))
                .build();

        assertEquals(Value.newBuilder().setIntegerValue(2).build(), aggregate("items", upTo2).get("n"));
    }

    @Test
    void countUpToMustBeGreaterThanZero() {
        seedPrice("a", Value.newBuilder().setIntegerValue(1).build());

        for (long upTo : new long[] {0, -1}) {
            assertInvalidArgument(StructuredAggregationQuery.Aggregation.newBuilder()
                    .setAlias("n")
                    .setCount(StructuredAggregationQuery.Aggregation.Count.newBuilder().setUpTo(Int64Value.of(upTo)))
                    .build());
        }
    }

    @Test
    void anAggregationWithoutAnOperatorIsRejected() {
        seedPrice("a", Value.newBuilder().setIntegerValue(1).build());

        assertInvalidArgument(StructuredAggregationQuery.Aggregation.newBuilder().setAlias("n").build());
    }

    @Test
    void unnamedAggregationsGetTheirOwnDefaultAliases() {
        seedPrice("a", Value.newBuilder().setIntegerValue(10).build());
        seedPrice("b", Value.newBuilder().setIntegerValue(20).build());

        Map<String, Value> result = aggregate("items",
                count(""), sum("total", "price"), sum("", "price"), avg("", "price"));

        assertEquals(Map.of(
                        "field_1", Value.newBuilder().setIntegerValue(2).build(),
                        "total", Value.newBuilder().setIntegerValue(30).build(),
                        "field_2", Value.newBuilder().setIntegerValue(30).build(),
                        "field_3", Value.newBuilder().setDoubleValue(15.0).build()),
                result);
    }

    private void seedPrice(String id, Value price) {
        seedDocument(PARENT + "/items/" + id, "price", price);
    }

    private void seedDocument(String name, String field, Value value) {
        Document doc = Document.newBuilder().setName(name).putFields(field, value).build();
        service.applyWrite(Write.newBuilder().setUpdate(doc).build(), Instant.now());
    }

    private Map<String, Value> aggregate(String collectionId, StructuredAggregationQuery.Aggregation... aggregations) {
        return aggregate(StructuredQuery.newBuilder()
                .addFrom(StructuredQuery.CollectionSelector.newBuilder().setCollectionId(collectionId))
                .build(), aggregations);
    }

    private Map<String, Value> aggregate(StructuredQuery query, StructuredAggregationQuery.Aggregation... aggregations) {
        CapturingObserver<RunAggregationQueryResponse> observer = new CapturingObserver<>();
        controller.runAggregationQuery(RunAggregationQueryRequest.newBuilder()
                .setParent(PARENT)
                .setStructuredAggregationQuery(StructuredAggregationQuery.newBuilder()
                        .setStructuredQuery(query)
                        .addAllAggregations(List.of(aggregations)))
                .build(), observer);
        assertNull(observer.error);
        assertTrue(observer.completed);
        return observer.messages.get(0).getResult().getAggregateFieldsMap();
    }

    private void assertInvalidArgument(StructuredAggregationQuery.Aggregation aggregation) {
        CapturingObserver<RunAggregationQueryResponse> observer = new CapturingObserver<>();
        controller.runAggregationQuery(RunAggregationQueryRequest.newBuilder()
                .setParent(PARENT)
                .setStructuredAggregationQuery(StructuredAggregationQuery.newBuilder()
                        .setStructuredQuery(StructuredQuery.newBuilder()
                                .addFrom(StructuredQuery.CollectionSelector.newBuilder().setCollectionId("items")))
                        .addAggregations(aggregation))
                .build(), observer);
        assertEquals(Status.Code.INVALID_ARGUMENT, Status.fromThrowable(observer.error).getCode());
        assertTrue(observer.messages.isEmpty());
        assertFalse(observer.completed);
    }

    private static StructuredAggregationQuery.Aggregation count(String alias) {
        return StructuredAggregationQuery.Aggregation.newBuilder()
                .setAlias(alias)
                .setCount(StructuredAggregationQuery.Aggregation.Count.getDefaultInstance())
                .build();
    }

    private static StructuredAggregationQuery.Aggregation sum(String alias, String field) {
        return StructuredAggregationQuery.Aggregation.newBuilder()
                .setAlias(alias)
                .setSum(StructuredAggregationQuery.Aggregation.Sum.newBuilder()
                        .setField(StructuredQuery.FieldReference.newBuilder().setFieldPath(field)))
                .build();
    }

    private static StructuredAggregationQuery.Aggregation avg(String alias, String field) {
        return StructuredAggregationQuery.Aggregation.newBuilder()
                .setAlias(alias)
                .setAvg(StructuredAggregationQuery.Aggregation.Avg.newBuilder()
                        .setField(StructuredQuery.FieldReference.newBuilder().setFieldPath(field)))
                .build();
    }

    private static Document documentWith(String name, String field, String value) {
        return Document.newBuilder()
                .setName(name)
                .putFields(field, Value.newBuilder().setStringValue(value).build())
                .build();
    }
}
