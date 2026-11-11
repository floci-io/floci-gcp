package io.floci.gcp.services.firestore;

import com.google.firestore.v1.Document;
import com.google.firestore.v1.ListenRequest;
import com.google.firestore.v1.ListenResponse;
import com.google.firestore.v1.StructuredQuery;
import com.google.firestore.v1.Target;
import com.google.firestore.v1.TargetChange;
import com.google.firestore.v1.Value;
import com.google.firestore.v1.Write;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.firestore.model.StoredDocument;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Firestore.Listen against google/firestore/v1/firestore.proto (TargetChange, DocumentChange,
 * DocumentDelete, DocumentRemove). The SDKs raise a listener snapshot only on a NO_CHANGE with no
 * target ids and a read_time once the target is CURRENT, so most tests assert that message.
 */
class FirestoreListenStreamTest {

    private static final String DB = "projects/p1/databases/(default)";
    private static final String PARENT = DB + "/documents";

    private FirestoreService service;
    private FirestoreController controller;

    @BeforeEach
    void setUp() {
        service = new FirestoreService(new InMemoryStorage<String, StoredDocument>());
        controller = new FirestoreController(service);
    }

    @Test
    void initialSnapshotEndsWithAConsistentSnapshotForTheWholeStream() {
        put("col/a", 1);
        put("col/b", 5);
        Recorder recorder = new Recorder();

        StreamObserver<ListenRequest> requests = controller.listen(recorder);
        requests.onNext(addTarget(lessThan3(1)));

        List<ListenResponse> messages = recorder.messages();
        assertEquals(4, messages.size(), messages::toString);
        assertTargetChange(messages.get(0), TargetChange.TargetChangeType.ADD, 1);
        assertFalse(messages.get(0).getTargetChange().hasReadTime());
        assertEquals(PARENT + "/col/a", messages.get(1).getDocumentChange().getDocument().getName());
        assertEquals(List.of(1), messages.get(1).getDocumentChange().getTargetIdsList());
        assertTargetChange(messages.get(2), TargetChange.TargetChangeType.CURRENT, 1);
        assertFalse(messages.get(2).getTargetChange().getResumeToken().isEmpty());
        assertTrue(messages.get(2).getTargetChange().hasReadTime());
        assertGlobalSnapshot(messages.get(3));
        assertNull(recorder.error);
    }

    @Test
    void laterWritesReachTheListenerAsChangesRemovalsAndDeletes() {
        put("col/a", 1);
        Recorder recorder = listen(lessThan3(1));

        int mark = recorder.size();
        put("col/b", 2);
        List<ListenResponse> added = recorder.messagesSince(mark);
        assertEquals(2, added.size(), added::toString);
        assertEquals(PARENT + "/col/b", added.get(0).getDocumentChange().getDocument().getName());
        assertEquals(2, added.get(0).getDocumentChange().getDocument().getFieldsMap().get("n").getIntegerValue());
        assertGlobalSnapshot(added.get(1));

        mark = recorder.size();
        put("col/a", 10);
        List<ListenResponse> stoppedMatching = recorder.messagesSince(mark);
        assertEquals(2, stoppedMatching.size(), stoppedMatching::toString);
        assertEquals(PARENT + "/col/a", stoppedMatching.get(0).getDocumentRemove().getDocument());
        assertEquals(List.of(1), stoppedMatching.get(0).getDocumentRemove().getRemovedTargetIdsList());
        assertGlobalSnapshot(stoppedMatching.get(1));

        mark = recorder.size();
        delete("col/b");
        List<ListenResponse> deleted = recorder.messagesSince(mark);
        assertEquals(2, deleted.size(), deleted::toString);
        assertEquals(PARENT + "/col/b", deleted.get(0).getDocumentDelete().getDocument());
        assertEquals(List.of(1), deleted.get(0).getDocumentDelete().getRemovedTargetIdsList());
        assertGlobalSnapshot(deleted.get(1));
    }

    @Test
    void anUpdateToAMatchingDocumentIsSentAgain() {
        put("col/a", 1);
        Recorder recorder = listen(lessThan3(1));

        int mark = recorder.size();
        put("col/a", 2);

        List<ListenResponse> messages = recorder.messagesSince(mark);
        assertEquals(2, messages.size(), messages::toString);
        assertEquals(2, messages.get(0).getDocumentChange().getDocument().getFieldsMap().get("n").getIntegerValue());
        assertGlobalSnapshot(messages.get(1));
    }

    @Test
    void writesThatCannotChangeTheResultsSendNothing() {
        put("col/a", 1);
        Recorder recorder = listen(lessThan3(1));

        int mark = recorder.size();
        put("other/x", 1);
        put("col/a/sub/y", 1);
        put("col/b", 7);

        assertEquals(List.of(), recorder.messagesSince(mark));
    }

    @Test
    void aCommitWithSeveralWritesIsOneSnapshot() {
        Recorder recorder = listen(lessThan3(1));

        int mark = recorder.size();
        service.commit(List.of(update("col/m1", 1), update("col/m2", 2)), null, Instant.now());

        List<ListenResponse> messages = recorder.messagesSince(mark);
        assertEquals(3, messages.size(), messages::toString);
        assertTrue(messages.get(0).hasDocumentChange());
        assertTrue(messages.get(1).hasDocumentChange());
        assertGlobalSnapshot(messages.get(2));
    }

    @Test
    void targetsAndWritesNamingTheProjectByNumberMeetUnderTheProjectId() {
        String byNumber = "projects/123456789012/databases/(default)/documents";
        service = new FirestoreService(new InMemoryStorage<String, StoredDocument>(),
                name -> name.replace("projects/123456789012/", "projects/p1/"));
        controller = new FirestoreController(service);
        Target query = lessThan3(1).toBuilder()
                .setQuery(lessThan3(1).getQuery().toBuilder().setParent(byNumber)).build();
        Recorder queries = listen(query);
        Recorder documents = listen(Target.newBuilder().setTargetId(2)
                .setDocuments(Target.DocumentsTarget.newBuilder().addDocuments(byNumber + "/col/a")).build());

        int queryMark = queries.size();
        int documentMark = documents.size();
        service.applyWrite(Write.newBuilder().setUpdate(Document.newBuilder().setName(byNumber + "/col/a")
                .putFields("n", Value.newBuilder().setIntegerValue(1).build())).build(), Instant.now());

        for (List<ListenResponse> changes : List.of(queries.messagesSince(queryMark),
                documents.messagesSince(documentMark))) {
            assertEquals(2, changes.size(), changes::toString);
            assertEquals(PARENT + "/col/a", changes.get(0).getDocumentChange().getDocument().getName());
        }
        assertTrue(service.getDocument(byNumber + "/col/a").isPresent());
    }

    @Test
    void aDocumentsTargetReportsAMissingDocumentAsDeletedAndThenItsCreation() {
        put("col/b", 6);
        Recorder recorder = listen(Target.newBuilder().setTargetId(2)
                .setDocuments(Target.DocumentsTarget.newBuilder()
                        .addDocuments(PARENT + "/col/b")
                        .addDocuments(PARENT + "/col/missing"))
                .build());

        List<ListenResponse> initial = recorder.messages();
        assertEquals(5, initial.size(), initial::toString);
        assertEquals(PARENT + "/col/b", initial.get(1).getDocumentChange().getDocument().getName());
        assertEquals(PARENT + "/col/missing", initial.get(2).getDocumentDelete().getDocument());
        assertEquals(List.of(2), initial.get(2).getDocumentDelete().getRemovedTargetIdsList());
        assertTargetChange(initial.get(3), TargetChange.TargetChangeType.CURRENT, 2);
        assertGlobalSnapshot(initial.get(4));

        int mark = recorder.size();
        put("col/other", 1);
        assertEquals(List.of(), recorder.messagesSince(mark));

        put("col/missing", 7);
        List<ListenResponse> created = recorder.messagesSince(mark);
        assertEquals(2, created.size(), created::toString);
        assertEquals(PARENT + "/col/missing", created.get(0).getDocumentChange().getDocument().getName());
        assertEquals(List.of(2), created.get(0).getDocumentChange().getTargetIdsList());
        assertGlobalSnapshot(created.get(1));
    }

    @Test
    void removingATargetStopsItsUpdatesAndAnUnknownTargetIsIgnored() {
        Recorder recorder = new Recorder();
        StreamObserver<ListenRequest> requests = controller.listen(recorder);
        requests.onNext(addTarget(lessThan3(1)));

        int mark = recorder.size();
        requests.onNext(ListenRequest.newBuilder().setDatabase(DB).setRemoveTarget(1).build());
        requests.onNext(ListenRequest.newBuilder().setDatabase(DB).setRemoveTarget(42).build());
        put("col/a", 1);

        List<ListenResponse> messages = recorder.messagesSince(mark);
        assertEquals(1, messages.size(), messages::toString);
        assertTargetChange(messages.get(0), TargetChange.TargetChangeType.REMOVE, 1);
        assertNull(recorder.error);
    }

    @Test
    void severalTargetsShareOneStreamAndTargetIdZeroIsAssigned() {
        Recorder recorder = new Recorder();
        StreamObserver<ListenRequest> requests = controller.listen(recorder);
        requests.onNext(addTarget(lessThan3(0)));
        requests.onNext(addTarget(lessThan3(0)));

        List<Integer> added = recorder.messages().stream()
                .filter(m -> m.hasTargetChange()
                        && m.getTargetChange().getTargetChangeType() == TargetChange.TargetChangeType.ADD)
                .map(m -> m.getTargetChange().getTargetIds(0))
                .toList();
        assertEquals(List.of(1, 2), added);

        int mark = recorder.size();
        put("col/a", 1);
        List<ListenResponse> messages = recorder.messagesSince(mark);
        assertEquals(3, messages.size(), messages::toString);
        assertEquals(List.of(1), messages.get(0).getDocumentChange().getTargetIdsList());
        assertEquals(List.of(2), messages.get(1).getDocumentChange().getTargetIdsList());
        assertGlobalSnapshot(messages.get(2));
    }

    @Test
    void aResumedTargetStartsOverWithAReset() {
        put("col/a", 1);
        Recorder recorder = listen(lessThan3(1).toBuilder()
                .setResumeToken(ByteString.copyFromUtf8("token from an earlier stream"))
                .build());

        List<ListenResponse> messages = recorder.messages();
        assertEquals(5, messages.size(), messages::toString);
        assertTargetChange(messages.get(0), TargetChange.TargetChangeType.ADD, 1);
        assertTargetChange(messages.get(1), TargetChange.TargetChangeType.RESET, 1);
        assertTrue(messages.get(2).hasDocumentChange());
        assertTargetChange(messages.get(3), TargetChange.TargetChangeType.CURRENT, 1);
        assertGlobalSnapshot(messages.get(4));
    }

    @Test
    void invalidTargetsEndTheStreamWithInvalidArgument() {
        assertRejected(lessThan3(-3));
        assertRejected(Target.newBuilder().setTargetId(4).build());

        Recorder recorder = new Recorder();
        StreamObserver<ListenRequest> requests = controller.listen(recorder);
        requests.onNext(addTarget(lessThan3(1)));
        requests.onNext(addTarget(lessThan3(1)));
        assertEquals(Status.Code.INVALID_ARGUMENT, Status.fromThrowable(recorder.error).getCode());

        int mark = recorder.size();
        requests.onNext(addTarget(lessThan3(7)));
        put("col/a", 1);
        assertEquals(List.of(), recorder.messagesSince(mark));
    }

    @Test
    void readTimesNeverGoBackwards() {
        Recorder recorder = listen(lessThan3(1));
        for (int i = 0; i < 20; i++) {
            put("col/d" + i, 1);
        }

        List<Instant> readTimes = recorder.messages().stream()
                .filter(m -> m.hasTargetChange() && m.getTargetChange().hasReadTime())
                .map(m -> toInstant(m.getTargetChange().getReadTime()))
                .toList();
        assertEquals(22, readTimes.size());
        for (int i = 1; i < readTimes.size(); i++) {
            assertFalse(readTimes.get(i).isBefore(readTimes.get(i - 1)), readTimes::toString);
        }
    }

    @Test
    void aClosedStreamStopsReceivingAndCompletesOnce() {
        Recorder recorder = new Recorder();
        StreamObserver<ListenRequest> requests = controller.listen(recorder);
        requests.onNext(addTarget(lessThan3(1)));

        requests.onCompleted();
        int mark = recorder.size();
        put("col/a", 1);
        requests.onCompleted();

        assertEquals(List.of(), recorder.messagesSince(mark));
        assertEquals(1, recorder.completions);
    }

    @Test
    void aListenerThatCannotBeReachedDoesNotFailTheWrite() {
        Recorder unreachable = new Recorder();
        StreamObserver<ListenRequest> requests = controller.listen(unreachable);
        requests.onNext(addTarget(lessThan3(1)));
        unreachable.failSends = true;
        Recorder healthy = listen(lessThan3(1));

        int mark = healthy.size();
        put("col/a", 1);
        put("col/b", 2);

        assertTrue(service.getDocument(PARENT + "/col/b").isPresent());
        assertEquals(1, unreachable.failedSends, "a dropped stream is not sent to again");
        assertEquals(4, healthy.messagesSince(mark).size());
    }

    /**
     * Invariant: a write that races a target's initial snapshot is reported exactly once, either in
     * the snapshot or as a later change. Here the snapshot is taken first: the write must wait until
     * the target is registered and then arrive as a change after the initial snapshot.
     */
    @Test
    void aWriteRacingTheInitialSnapshotWaitsAndArrivesAsAChange() throws Exception {
        CountDownLatch atCurrent = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Recorder recorder = new Recorder();
        recorder.blockWhen(m -> m.hasTargetChange()
                && m.getTargetChange().getTargetChangeType() == TargetChange.TargetChangeType.CURRENT, atCurrent, release);
        StreamObserver<ListenRequest> requests = controller.listen(recorder);
        Thread listener = new Thread(() -> requests.onNext(addTarget(lessThan3(1))));
        listener.start();
        assertTrue(atCurrent.await(10, TimeUnit.SECONDS));

        Thread writer = new Thread(() -> put("col/racer", 1));
        writer.start();
        awaitBlocked(writer);
        release.countDown();
        writer.join(10_000);
        listener.join(10_000);

        List<String> changes = recorder.messages().stream()
                .filter(ListenResponse::hasDocumentChange)
                .map(m -> m.getDocumentChange().getDocument().getName())
                .toList();
        assertEquals(List.of(PARENT + "/col/racer"), changes);
        List<ListenResponse> messages = recorder.messages();
        int change = indexOf(messages, ListenResponse::hasDocumentChange);
        assertTargetChange(messages.get(change - 2), TargetChange.TargetChangeType.CURRENT, 1);
        assertGlobalSnapshot(messages.get(change - 1));
        assertGlobalSnapshot(messages.get(change + 1));
    }

    /** The other order of the same race: the write lands first and is only in the initial snapshot. */
    @Test
    void aWriteThatLandsBeforeTheTargetIsOnlyInTheInitialSnapshot() {
        put("col/racer", 1);
        Recorder recorder = listen(lessThan3(1));

        List<ListenResponse> messages = recorder.messages();
        assertEquals(1, messages.stream().filter(ListenResponse::hasDocumentChange).count());
        assertGlobalSnapshot(messages.get(messages.size() - 1));
    }

    /**
     * Invariant: once a stream is closed, no write sends on it. A close that races a send waits for
     * the send to finish, and the completion is the last thing the client sees.
     */
    @Test
    void aCloseRacingASendWaitsAndNothingFollowsTheCompletion() throws Exception {
        CountDownLatch atChange = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Recorder recorder = new Recorder();
        StreamObserver<ListenRequest> requests = controller.listen(recorder);
        requests.onNext(addTarget(lessThan3(1)));
        recorder.blockWhen(ListenResponse::hasDocumentChange, atChange, release);

        Thread writer = new Thread(() -> put("col/a", 1));
        writer.start();
        assertTrue(atChange.await(10, TimeUnit.SECONDS));
        Thread closer = new Thread(requests::onCompleted);
        closer.start();
        awaitBlocked(closer);
        assertEquals(0, recorder.completions);
        release.countDown();
        writer.join(10_000);
        closer.join(10_000);

        int mark = recorder.size();
        put("col/b", 1);
        assertEquals(List.of(), recorder.messagesSince(mark));
        assertEquals(1, recorder.completions);
        assertGlobalSnapshot(recorder.messages().get(recorder.size() - 1));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Recorder listen(Target target) {
        Recorder recorder = new Recorder();
        controller.listen(recorder).onNext(addTarget(target));
        assertNull(recorder.error);
        return recorder;
    }

    private void assertRejected(Target target) {
        Recorder recorder = new Recorder();
        controller.listen(recorder).onNext(addTarget(target));
        assertEquals(Status.Code.INVALID_ARGUMENT, Status.fromThrowable(recorder.error).getCode());
        assertEquals(List.of(), recorder.messages());
    }

    private static ListenRequest addTarget(Target target) {
        return ListenRequest.newBuilder().setDatabase(DB).setAddTarget(target).build();
    }

    private static Target lessThan3(int targetId) {
        return Target.newBuilder().setTargetId(targetId)
                .setQuery(Target.QueryTarget.newBuilder().setParent(PARENT)
                        .setStructuredQuery(StructuredQuery.newBuilder()
                                .addFrom(StructuredQuery.CollectionSelector.newBuilder().setCollectionId("col"))
                                .setWhere(StructuredQuery.Filter.newBuilder()
                                        .setFieldFilter(StructuredQuery.FieldFilter.newBuilder()
                                                .setField(StructuredQuery.FieldReference.newBuilder().setFieldPath("n"))
                                                .setOp(StructuredQuery.FieldFilter.Operator.LESS_THAN)
                                                .setValue(Value.newBuilder().setIntegerValue(3))))))
                .build();
    }

    private void put(String path, long n) {
        service.applyWrite(update(path, n), Instant.now());
    }

    private void delete(String path) {
        service.applyWrite(Write.newBuilder().setDelete(PARENT + "/" + path).build(), Instant.now());
    }

    private static Write update(String path, long n) {
        return Write.newBuilder()
                .setUpdate(Document.newBuilder().setName(PARENT + "/" + path)
                        .putFields("n", Value.newBuilder().setIntegerValue(n).build()))
                .build();
    }

    private static void assertTargetChange(ListenResponse message, TargetChange.TargetChangeType type, int targetId) {
        assertTrue(message.hasTargetChange(), message::toString);
        assertEquals(type, message.getTargetChange().getTargetChangeType(), message::toString);
        assertEquals(List.of(targetId), message.getTargetChange().getTargetIdsList(), message::toString);
    }

    private static void assertGlobalSnapshot(ListenResponse message) {
        assertTrue(message.hasTargetChange(), message::toString);
        assertEquals(TargetChange.TargetChangeType.NO_CHANGE, message.getTargetChange().getTargetChangeType());
        assertEquals(List.of(), message.getTargetChange().getTargetIdsList());
        assertTrue(message.getTargetChange().hasReadTime(), message::toString);
        assertFalse(message.getTargetChange().getResumeToken().isEmpty(), message::toString);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    private static int indexOf(List<ListenResponse> messages, Predicate<ListenResponse> predicate) {
        for (int i = 0; i < messages.size(); i++) {
            if (predicate.test(messages.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Waits until the thread is parked on a monitor, here the service's write lock. */
    private static void awaitBlocked(Thread thread) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (thread.getState() != Thread.State.BLOCKED) {
            assertTrue(Instant.now().isBefore(deadline), "thread never blocked, state " + thread.getState());
            assertTrue(thread.isAlive(), "thread finished without blocking");
            Thread.onSpinWait();
        }
    }

    private static final class Recorder implements StreamObserver<ListenResponse> {
        private final List<ListenResponse> messages = new ArrayList<>();
        volatile Throwable error;
        volatile int completions;
        volatile boolean failSends;
        volatile int failedSends;
        private Predicate<ListenResponse> blockWhen;
        private CountDownLatch reached;
        private CountDownLatch release;

        void blockWhen(Predicate<ListenResponse> predicate, CountDownLatch reached, CountDownLatch release) {
            this.blockWhen = predicate;
            this.reached = reached;
            this.release = release;
        }

        @Override
        public void onNext(ListenResponse value) {
            if (failSends) {
                failedSends++;
                throw Status.CANCELLED.withDescription("call already cancelled").asRuntimeException();
            }
            synchronized (messages) {
                messages.add(value);
            }
            if (blockWhen != null && blockWhen.test(value)) {
                blockWhen = null;
                reached.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void onError(Throwable t) {
            error = t;
        }

        @Override
        public void onCompleted() {
            completions++;
        }

        List<ListenResponse> messages() {
            synchronized (messages) {
                return List.copyOf(messages);
            }
        }

        List<ListenResponse> messagesSince(int mark) {
            List<ListenResponse> all = messages();
            return all.subList(mark, all.size());
        }

        int size() {
            return messages().size();
        }
    }
}
