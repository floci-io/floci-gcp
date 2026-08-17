package io.floci.gcp.services.firestore;

import com.google.firestore.v1.Document;
import com.google.firestore.v1.RunQueryRequest;
import com.google.firestore.v1.RunQueryResponse;
import com.google.firestore.v1.StructuredQuery;
import com.google.firestore.v1.TransactionOptions;
import com.google.firestore.v1.Write;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.firestore.model.StoredDocument;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
}
