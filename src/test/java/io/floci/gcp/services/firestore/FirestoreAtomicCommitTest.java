package io.floci.gcp.services.firestore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.firestore.v1.CommitRequest;
import com.google.firestore.v1.CommitResponse;
import com.google.firestore.v1.Document;
import com.google.firestore.v1.DocumentTransform;
import com.google.firestore.v1.Precondition;
import com.google.firestore.v1.Value;
import com.google.firestore.v1.Write;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.PersistentStorage;
import io.floci.gcp.core.storage.PersistentStorageFaults;
import io.floci.gcp.core.storage.StorageException;
import io.floci.gcp.services.firestore.model.StoredDocument;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FirestoreAtomicCommitTest {
    private static final String DB = "projects/synthetic/databases/(default)";
    private static final String SOURCE = DB + "/documents/source/document";
    private static final String DESTINATION = DB + "/documents/destination/document";
    private static final Instant ORIGINAL = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant UPDATED = ORIGINAL.plusSeconds(60);
    private static final TypeReference<Map<String, StoredDocument>> TYPE = new TypeReference<>() {};
    @TempDir Path dir;

    @Test
    void twoWriteFailureReturnsGrpcErrorAndReloadsOriginalSnapshot() throws Exception {
        Path path = dir.resolve("documents.json");
        var faults = new PersistentStorageFaults<>(path, TYPE);
        var service = new FirestoreService(faults.storage);
        service.applyWrite(upsert(SOURCE, "original"), ORIGINAL);
        byte[] before = Files.readAllBytes(path);
        byte[] transaction = transactionReadingMove(service);
        int writes = faults.writes();
        // In the old loop the destination was already durable when deletion failed.
        // Reject the candidate containing the complete move, regardless of call count.
        faults.failBeforeWrite(bytes -> {
            String candidate = new String(bytes, StandardCharsets.UTF_8);
            return candidate.contains(DESTINATION) && !candidate.contains(SOURCE);
        });
        var observer = new CommitObserver();
        new FirestoreController(service).commit(CommitRequest.newBuilder()
                .setDatabase(DB).setTransaction(ByteString.copyFrom(transaction))
                .addAllWrites(move()).build(), observer);

        assertNotNull(observer.error);
        assertEquals(Status.Code.INTERNAL, Status.fromThrowable(observer.error).getCode());
        assertFalse(observer.acknowledged);
        assertArrayEquals(before, Files.readAllBytes(path));
        assertEquals(writes + 1, faults.writes());
        assertThrows(StorageException.class, () -> service.getDocument(SOURCE));
        var reloaded = reload(path);
        assertEquals("original", reloaded.get(SOURCE).orElseThrow().getFields().get("value").getStringValue());
        assertTrue(reloaded.get(DESTINATION).isEmpty());

        faults.failBeforeWrite(bytes -> false);
        faults.storage.load();
        service.commit(move(), transaction, UPDATED);
        assertMoveComplete(reload(path));
    }

    @Test
    void successfulMovePersistsOnceAndReloadsBothChanges() {
        Path path = dir.resolve("documents.json");
        var faults = new PersistentStorageFaults<>(path, TYPE);
        var service = new FirestoreService(faults.storage);
        service.applyWrite(upsert(SOURCE, "original"), ORIGINAL);
        int writes = faults.writes();

        var result = service.commit(move(), transactionReadingMove(service), UPDATED);

        assertEquals(2, result.size());
        assertEquals(UPDATED.toString(), result.getFirst().updateTime());
        assertNull(result.getLast().updateTime());
        assertEquals(writes + 1, faults.writes());
        assertMoveComplete(faults.storage);
        assertMoveComplete(reload(path));
    }

    @Test
    void uncertainReplacementBlocksAccessThenReloadsWholeMove() {
        Path path = dir.resolve("documents.json");
        var faults = new PersistentStorageFaults<>(path, TYPE);
        var service = new FirestoreService(faults.storage);
        service.applyWrite(upsert(SOURCE, "original"), ORIGINAL);
        faults.failAfterWrite(true);
        int writes = faults.writes();

        assertThrows(StorageException.class, () -> service.commit(move(), transactionReadingMove(service), UPDATED));

        assertEquals(writes + 1, faults.writes());
        assertThrows(StorageException.class, () -> service.getDocument(SOURCE));
        assertThrows(StorageException.class, () -> service.getDocument(DESTINATION));
        assertThrows(StorageException.class, () -> service.applyWrite(upsert(SOURCE, "stale"), UPDATED));
        assertEquals(writes + 1, faults.writes());
        assertMoveComplete(reload(path));
        faults.failAfterWrite(false);
        faults.storage.load();
        assertMoveComplete(faults.storage);
    }

    @Test
    void writeAndTransformsUseStagedValuesAndPersistOnce() {
        Path path = dir.resolve("documents.json");
        var faults = new PersistentStorageFaults<>(path, TYPE);
        var service = new FirestoreService(faults.storage);
        var increment = DocumentTransform.FieldTransform.newBuilder().setFieldPath("count")
                .setIncrement(Value.newBuilder().setIntegerValue(2)).build();
        Write first = Write.newBuilder().setUpdate(Document.newBuilder().setName(SOURCE)
                        .putFields("count", Value.newBuilder().setIntegerValue(3).build()))
                .addUpdateTransforms(increment).build();
        Write second = Write.newBuilder().setTransform(DocumentTransform.newBuilder()
                .setDocument(SOURCE).addFieldTransforms(increment)).build();

        service.commit(List.of(first, second), null, UPDATED);

        assertEquals(1, faults.writes());
        assertEquals(7, reload(path).get(SOURCE).orElseThrow().getFields().get("count").getIntegerValue());
    }

    @Test
    void laterPreconditionFailureDoesNotReachDisk() {
        Path path = dir.resolve("documents.json");
        var faults = new PersistentStorageFaults<>(path, TYPE);
        var service = new FirestoreService(faults.storage);
        service.applyWrite(upsert(SOURCE, "changed"), UPDATED);
        int writes = faults.writes();

        GcpException error = assertThrows(GcpException.class, () -> service.commit(move(), null, UPDATED));

        assertEquals(Status.Code.FAILED_PRECONDITION, error.getGrpcCode());
        assertEquals(writes, faults.writes());
        assertTrue(reload(path).get(DESTINATION).isEmpty());
        assertEquals("changed", reload(path).get(SOURCE).orElseThrow().getFields().get("value").getStringValue());
    }

    @Test
    void failedPersistenceRetainsTransactionReadSetForRetry() {
        Path path = dir.resolve("documents.json");
        var faults = new PersistentStorageFaults<>(path, TYPE);
        var service = new FirestoreService(faults.storage);
        service.applyWrite(upsert(SOURCE, "original"), ORIGINAL);
        byte[] transaction = transactionReadingMove(service);
        faults.failBeforeWrite(bytes -> true);
        assertThrows(StorageException.class, () -> service.commit(move(), transaction, UPDATED));
        faults.failBeforeWrite(bytes -> false);
        faults.storage.load();
        service.applyWrite(upsert(SOURCE, "concurrent"), UPDATED);
        int writes = faults.writes();

        GcpException error = assertThrows(GcpException.class,
                () -> service.commit(move(), transaction, UPDATED.plusSeconds(1)));

        assertEquals(Status.Code.ABORTED, error.getGrpcCode());
        assertEquals(writes, faults.writes());
        assertTrue(reload(path).get(DESTINATION).isEmpty());
    }

    private static Write upsert(String name, String value) {
        return Write.newBuilder().setUpdate(Document.newBuilder().setName(name)
                .putFields("value", Value.newBuilder().setStringValue(value).build())).build();
    }

    private static List<Write> move() {
        return List.of(upsert(DESTINATION, "original").toBuilder()
                        .setCurrentDocument(Precondition.newBuilder().setExists(false)).build(),
                Write.newBuilder().setDelete(SOURCE).setCurrentDocument(Precondition.newBuilder()
                        .setUpdateTime(Timestamp.newBuilder().setSeconds(ORIGINAL.getEpochSecond()))).build());
    }

    private static byte[] transactionReadingMove(FirestoreService service) {
        byte[] transaction = service.beginTransaction();
        service.recordTransactionRead(transaction, SOURCE);
        service.recordTransactionRead(transaction, DESTINATION);
        return transaction;
    }

    private static PersistentStorage<String, StoredDocument> reload(Path path) {
        var storage = new PersistentStorage<>(path, TYPE);
        storage.load();
        return storage;
    }

    private static void assertMoveComplete(PersistentStorage<String, StoredDocument> storage) {
        assertTrue(storage.get(SOURCE).isEmpty());
        assertEquals("original", storage.get(DESTINATION).orElseThrow().getFields().get("value").getStringValue());
    }

    private static class CommitObserver implements StreamObserver<CommitResponse> {
        Throwable error;
        boolean acknowledged;
        public void onNext(CommitResponse response) { acknowledged = true; }
        public void onCompleted() { acknowledged = true; }
        public void onError(Throwable failure) { error = failure; }
    }
}
