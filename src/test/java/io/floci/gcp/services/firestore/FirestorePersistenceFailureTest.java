package io.floci.gcp.services.firestore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.firestore.v1.CommitRequest;
import com.google.firestore.v1.CommitResponse;
import com.google.firestore.v1.Document;
import com.google.firestore.v1.Write;
import io.floci.gcp.core.storage.PersistentStorage;
import io.floci.gcp.core.storage.StorageException;
import io.floci.gcp.services.firestore.model.StoredDocument;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FirestorePersistenceFailureTest {
    @TempDir Path dir;

    @Test
    void failedDiskCommitReturnsGrpcErrorWithoutAcknowledgmentOrVisibleDocument() throws Exception {
        Path blocker = dir.resolve("blocked");
        Files.writeString(blocker, "not a directory");
        var storage = new PersistentStorage<>(blocker.resolve("documents.json"),
                new TypeReference<Map<String, StoredDocument>>() {});
        var controller = new FirestoreController(new FirestoreService(storage));
        String name = "projects/synthetic/databases/(default)/documents/runs/new";
        var observer = new StreamObserver<CommitResponse>() {
            Throwable error;
            boolean acknowledged;
            public void onNext(CommitResponse response) { acknowledged = true; }
            public void onCompleted() { acknowledged = true; }
            public void onError(Throwable failure) { error = failure; }
        };
        controller.commit(CommitRequest.newBuilder()
                .setDatabase("projects/synthetic/databases/(default)")
                .addWrites(Write.newBuilder().setUpdate(Document.newBuilder().setName(name))).build(), observer);
        assertNotNull(observer.error);
        assertEquals(Status.Code.INTERNAL, Status.fromThrowable(observer.error).getCode());
        assertFalse(observer.acknowledged);
        assertThrows(StorageException.class, () -> storage.get(name));
    }
}
