package io.floci.gcp.services.firestore;

import com.google.firestore.v1.Document;
import com.google.firestore.v1.MapValue;
import com.google.firestore.v1.Precondition;
import com.google.firestore.v1.StructuredQuery;
import com.google.firestore.v1.Value;
import com.google.firestore.v1.Write;
import com.google.protobuf.Timestamp;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.firestore.model.StoredDocument;
import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FirestoreServiceTest {

    private FirestoreService service;
    private InMemoryStorage<String, StoredDocument> storage;
    private static final String DB = "projects/p1/databases/(default)";
    private static final String DOC_NAME = DB + "/documents/users/alice";

    @BeforeEach
    void setUp() {
        storage = new InMemoryStorage<>();
        service = new FirestoreService(storage);
    }

    @Test
    void writeAndGetDocumentReturnsStoredFields() {
        Document doc = Document.newBuilder()
                .setName(DOC_NAME)
                .putFields("name", Value.newBuilder().setStringValue("Alice").build())
                .build();

        service.applyWrite(Write.newBuilder().setUpdate(doc).build(), Instant.now());

        Optional<StoredDocument> result = service.getDocument(DOC_NAME);
        assertTrue(result.isPresent());
        assertEquals(DOC_NAME, result.get().getName());
        assertEquals("string", result.get().getFields().get("name").getType());
    }

    @Test
    void getMissingDocumentReturnsEmpty() {
        Optional<StoredDocument> result = service.getDocument(DB + "/documents/users/missing");
        assertTrue(result.isEmpty());
    }

    @Test
    void deleteDocumentRemovesIt() {
        Document doc = Document.newBuilder().setName(DOC_NAME).build();
        service.applyWrite(Write.newBuilder().setUpdate(doc).build(), Instant.now());

        service.applyWrite(Write.newBuilder().setDelete(DOC_NAME).build(), Instant.now());

        assertTrue(service.getDocument(DOC_NAME).isEmpty());
    }

    @Test
    void secondWriteOverwritesFields() {
        Document v1 = Document.newBuilder()
                .setName(DOC_NAME)
                .putFields("a", Value.newBuilder().setStringValue("1").build())
                .build();
        service.applyWrite(Write.newBuilder().setUpdate(v1).build(), Instant.now());

        Document v2 = Document.newBuilder()
                .setName(DOC_NAME)
                .putFields("b", Value.newBuilder().setStringValue("2").build())
                .build();
        service.applyWrite(Write.newBuilder().setUpdate(v2).build(), Instant.now());

        StoredDocument stored = service.getDocument(DOC_NAME).orElseThrow();
        assertNotNull(stored.getFields().get("b"));
    }

    @Test
    void runQueryReturnsDocumentsInCollection() {
        for (String id : List.of("doc1", "doc2")) {
            Document doc = Document.newBuilder()
                    .setName(DB + "/documents/col/" + id)
                    .build();
            service.applyWrite(Write.newBuilder().setUpdate(doc).build(), Instant.now());
        }

        StructuredQuery query = StructuredQuery.newBuilder()
                .addFrom(StructuredQuery.CollectionSelector.newBuilder()
                        .setCollectionId("col").build())
                .build();

        List<StoredDocument> results = service.runQuery(DB + "/documents", query);
        assertEquals(2, results.size());
    }

    @Test
    void runQueryMatchesNestedMapFieldPath() {
        service.applyWrite(nestedBillingDocument("matching", "cus_matching"), Instant.now());
        service.applyWrite(nestedBillingDocument("different", "cus_different"), Instant.now());
        service.applyWrite(Write.newBuilder()
                .setUpdate(Document.newBuilder()
                        .setName(DB + "/documents/customers/missing")
                        .putFields("name", Value.newBuilder().setStringValue("No billing field").build())
                        .build())
                .build(), Instant.now());

        StructuredQuery query = StructuredQuery.newBuilder()
                .addFrom(StructuredQuery.CollectionSelector.newBuilder()
                        .setCollectionId("customers").build())
                .setWhere(StructuredQuery.Filter.newBuilder()
                        .setFieldFilter(StructuredQuery.FieldFilter.newBuilder()
                                .setField(StructuredQuery.FieldReference.newBuilder()
                                        .setFieldPath("billing.stripe_customer_id"))
                                .setOp(StructuredQuery.FieldFilter.Operator.EQUAL)
                                .setValue(Value.newBuilder().setStringValue("cus_matching"))))
                .build();

        List<StoredDocument> results = service.runQuery(DB + "/documents", query);

        assertEquals(List.of(DB + "/documents/customers/matching"),
                results.stream().map(StoredDocument::getName).toList());
    }

    private Write nestedBillingDocument(String id, String stripeCustomerId) {
        Value billing = Value.newBuilder()
                .setMapValue(MapValue.newBuilder()
                        .putFields("stripe_customer_id",
                                Value.newBuilder().setStringValue(stripeCustomerId).build()))
                .build();
        return Write.newBuilder()
                .setUpdate(Document.newBuilder()
                        .setName(DB + "/documents/customers/" + id)
                        .putFields("billing", billing)
                        .build())
                .build();
    }

    @Test
    void listCollectionIdsReturnsCollections() {
        Document doc = Document.newBuilder()
                .setName(DB + "/documents/myCollection/docA")
                .build();
        service.applyWrite(Write.newBuilder().setUpdate(doc).build(), Instant.now());

        List<String> ids = service.listCollectionIds(DB + "/documents");
        assertTrue(ids.contains("myCollection"));
    }

    @Test
    void beginTransactionReturnsByteArray() {
        byte[] txn = service.beginTransaction();
        assertNotNull(txn);
        assertTrue(txn.length > 0);
    }

    private Write upsert(String name, String field, String value) {
        return Write.newBuilder()
                .setUpdate(Document.newBuilder()
                        .setName(name)
                        .putFields(field, Value.newBuilder().setStringValue(value).build())
                        .build())
                .build();
    }

    @Test
    void createPreconditionFailsWhenDocumentExists() {
        service.applyWrite(upsert(DOC_NAME, "a", "1"), Instant.now());

        Write create = upsert(DOC_NAME, "a", "2").toBuilder()
                .setCurrentDocument(Precondition.newBuilder().setExists(false).build())
                .build();
        GcpException ex = assertThrows(GcpException.class,
                () -> service.applyWrite(create, Instant.now()));
        assertEquals(Status.Code.ALREADY_EXISTS, ex.getGrpcCode());
    }

    @Test
    void updatePreconditionFailsWhenDocumentMissing() {
        Write update = upsert(DOC_NAME, "a", "1").toBuilder()
                .setCurrentDocument(Precondition.newBuilder().setExists(true).build())
                .build();
        GcpException ex = assertThrows(GcpException.class,
                () -> service.applyWrite(update, Instant.now()));
        assertEquals(Status.Code.NOT_FOUND, ex.getGrpcCode());
    }

    @Test
    void staleUpdateTimePreconditionFails() {
        service.applyWrite(upsert(DOC_NAME, "a", "1"), Instant.parse("2026-01-01T00:00:00Z"));

        Write update = upsert(DOC_NAME, "a", "2").toBuilder()
                .setCurrentDocument(Precondition.newBuilder()
                        .setUpdateTime(Timestamp.newBuilder()
                                .setSeconds(Instant.parse("2025-01-01T00:00:00Z").getEpochSecond())
                                .build())
                        .build())
                .build();
        GcpException ex = assertThrows(GcpException.class,
                () -> service.applyWrite(update, Instant.now()));
        assertEquals(Status.Code.FAILED_PRECONDITION, ex.getGrpcCode());
        assertEquals("1", service.getDocument(DOC_NAME).orElseThrow()
                .getFields().get("a").getStringValue());
    }

    @Test
    void matchingUpdateTimePreconditionSucceeds() {
        Instant written = Instant.parse("2026-01-01T00:00:00.123456789Z");
        service.applyWrite(upsert(DOC_NAME, "a", "1"), written);

        Write update = upsert(DOC_NAME, "a", "2").toBuilder()
                .setCurrentDocument(Precondition.newBuilder()
                        .setUpdateTime(Timestamp.newBuilder()
                                .setSeconds(written.getEpochSecond())
                                .setNanos(written.getNano())
                                .build())
                        .build())
                .build();
        service.applyWrite(update, Instant.now());
        assertEquals("2", service.getDocument(DOC_NAME).orElseThrow()
                .getFields().get("a").getStringValue());
    }

    @Test
    void commitAppliesNothingWhenAnyPreconditionFails() {
        String other = DB + "/documents/users/bob";
        Write failing = upsert(DOC_NAME, "a", "1").toBuilder()
                .setCurrentDocument(Precondition.newBuilder().setExists(true).build())
                .build();

        assertThrows(GcpException.class, () -> service.commit(
                List.of(upsert(other, "b", "1"), failing), new byte[0], Instant.now()));
        assertTrue(service.getDocument(other).isEmpty());
    }

    @Test
    void conflictingTransactionAborts() {
        service.applyWrite(upsert(DOC_NAME, "counter", "0"), Instant.now());

        byte[] tx1 = service.beginTransaction();
        byte[] tx2 = service.beginTransaction();
        service.recordTransactionRead(tx1, DOC_NAME);
        service.recordTransactionRead(tx2, DOC_NAME);

        service.commit(List.of(upsert(DOC_NAME, "counter", "1")), tx1, Instant.now());

        GcpException ex = assertThrows(GcpException.class, () -> service.commit(
                List.of(upsert(DOC_NAME, "counter", "1")), tx2, Instant.now()));
        assertEquals(Status.Code.ABORTED, ex.getGrpcCode());
        assertEquals("1", service.getDocument(DOC_NAME).orElseThrow()
                .getFields().get("counter").getStringValue());
    }

    @Test
    void nonConflictingTransactionCommits() {
        service.applyWrite(upsert(DOC_NAME, "a", "1"), Instant.now());

        byte[] tx = service.beginTransaction();
        service.recordTransactionRead(tx, DOC_NAME);
        service.commit(List.of(upsert(DOC_NAME, "a", "2")), tx, Instant.now());

        assertEquals("2", service.getDocument(DOC_NAME).orElseThrow()
                .getFields().get("a").getStringValue());
    }

    @Test
    void transactionReadOfMissingDocumentDetectsCreation() {
        byte[] tx = service.beginTransaction();
        service.recordTransactionRead(tx, DOC_NAME);

        service.applyWrite(upsert(DOC_NAME, "a", "1"), Instant.now());

        GcpException ex = assertThrows(GcpException.class, () -> service.commit(
                List.of(upsert(DOC_NAME, "a", "2")), tx, Instant.now()));
        assertEquals(Status.Code.ABORTED, ex.getGrpcCode());
    }

    @Test
    void recordedSnapshotVersionDetectsWriteRacingTheRead() {
        service.applyWrite(upsert(DOC_NAME, "a", "1"), Instant.parse("2026-01-01T00:00:00Z"));
        byte[] tx = service.beginTransaction();
        String snapshotVersion = service.getDocument(DOC_NAME).orElseThrow().getUpdateTime();

        // write lands between the read and the read being recorded
        service.applyWrite(upsert(DOC_NAME, "a", "2"), Instant.parse("2026-01-02T00:00:00Z"));
        service.recordTransactionRead(tx, DOC_NAME, snapshotVersion);

        GcpException ex = assertThrows(GcpException.class, () -> service.commit(
                List.of(upsert(DOC_NAME, "a", "3")), tx, Instant.now()));
        assertEquals(Status.Code.ABORTED, ex.getGrpcCode());
    }

    @Test
    void retriedCommitOfAbortedTransactionStillAborts() {
        service.applyWrite(upsert(DOC_NAME, "a", "1"), Instant.now());
        byte[] tx = service.beginTransaction();
        service.recordTransactionRead(tx, DOC_NAME);
        service.applyWrite(upsert(DOC_NAME, "a", "2"), Instant.now().plusSeconds(1));

        List<Write> writes = List.of(upsert(DOC_NAME, "a", "3"));
        assertThrows(GcpException.class, () -> service.commit(writes, tx, Instant.now()));
        GcpException retry = assertThrows(GcpException.class,
                () -> service.commit(writes, tx, Instant.now()));
        assertEquals(Status.Code.ABORTED, retry.getGrpcCode());
    }

    @Test
    void unparseableStoredUpdateTimeFailsPreconditionInsteadOfCrashing() {
        storage.put(DOC_NAME, new StoredDocument(DOC_NAME, "not-a-timestamp", "not-a-timestamp", null));

        Write update = upsert(DOC_NAME, "a", "1").toBuilder()
                .setCurrentDocument(Precondition.newBuilder()
                        .setUpdateTime(Timestamp.newBuilder().setSeconds(1).build())
                        .build())
                .build();
        GcpException ex = assertThrows(GcpException.class,
                () -> service.applyWrite(update, Instant.now()));
        assertEquals(Status.Code.FAILED_PRECONDITION, ex.getGrpcCode());
    }

    @Test
    void rollbackDiscardsTransactionState() {
        byte[] tx = service.beginTransaction();
        service.recordTransactionRead(tx, DOC_NAME);
        service.rollback(tx);

        service.applyWrite(upsert(DOC_NAME, "a", "1"), Instant.now());
        // committing a rolled-back (unknown) transaction skips validation
        service.commit(List.of(upsert(DOC_NAME, "a", "2")), tx, Instant.now());
        assertEquals("2", service.getDocument(DOC_NAME).orElseThrow()
                .getFields().get("a").getStringValue());
    }
}
