package io.floci.gcp.test;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Precondition;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.WriteResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirestoreTest {

    private static final String COLLECTION = "test-users";
    private static final String DOC_ID = TestFixtures.uniqueName("user");

    private static Firestore firestore;

    @BeforeAll
    static void setUp() {
        firestore = TestFixtures.firestoreClient();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (firestore != null) {
            firestore.close();
        }
    }

    @Test
    @Order(1)
    void setDocument() throws ExecutionException, InterruptedException {
        Map<String, Object> data = new HashMap<>();
        data.put("name", "Alice");
        data.put("age", 30L);
        data.put("email", "alice@example.com");
        data.put("active", true);

        DocumentReference docRef = firestore.collection(COLLECTION).document(DOC_ID);
        WriteResult result = docRef.set(data).get();

        assertThat(result.getUpdateTime()).isNotNull();
    }

    @Test
    @Order(2)
    void getDocumentAndVerifyFields() throws ExecutionException, InterruptedException {
        DocumentSnapshot snapshot = firestore.collection(COLLECTION).document(DOC_ID).get().get();

        assertThat(snapshot.exists()).isTrue();
        assertThat(snapshot.getString("name")).isEqualTo("Alice");
        assertThat(snapshot.getLong("age")).isEqualTo(30L);
        assertThat(snapshot.getString("email")).isEqualTo("alice@example.com");
        assertThat(snapshot.getBoolean("active")).isTrue();
    }

    @Test
    @Order(3)
    void updateDocumentField() throws ExecutionException, InterruptedException {
        DocumentReference docRef = firestore.collection(COLLECTION).document(DOC_ID);
        WriteResult result = docRef.update("age", 31L, "city", "New York").get();

        assertThat(result.getUpdateTime()).isNotNull();

        DocumentSnapshot snapshot = docRef.get().get();
        assertThat(snapshot.getLong("age")).isEqualTo(31L);
        assertThat(snapshot.getString("city")).isEqualTo("New York");
        assertThat(snapshot.getString("name")).isEqualTo("Alice");
    }

    @Test
    @Order(4)
    void queryDocumentsWithFilter() throws ExecutionException, InterruptedException {
        // Add a second document to make the query meaningful
        String secondDocId = TestFixtures.uniqueName("user");
        Map<String, Object> secondData = new HashMap<>();
        secondData.put("name", "Bob");
        secondData.put("age", 25L);
        secondData.put("active", false);

        firestore.collection(COLLECTION).document(secondDocId).set(secondData).get();

        // Query active users
        QuerySnapshot querySnapshot = firestore.collection(COLLECTION)
                .whereEqualTo("active", true)
                .get()
                .get();

        List<QueryDocumentSnapshot> documents = querySnapshot.getDocuments();
        assertThat(documents).isNotEmpty();

        List<String> names = documents.stream()
                .map(doc -> doc.getString("name"))
                .toList();
        assertThat(names).contains("Alice");
        assertThat(names).doesNotContain("Bob");

        // Clean up second document
        firestore.collection(COLLECTION).document(secondDocId).delete().get();
    }

    @Test
    @Order(5)
    void queryWithOrderingAndCursors() throws ExecutionException, InterruptedException {
        String collection = TestFixtures.uniqueName("cursor-coll");
        long[] ages = {10L, 20L, 30L, 40L};
        for (long age : ages) {
            Map<String, Object> data = new HashMap<>();
            data.put("name", "u" + age);
            data.put("age", age);
            firestore.collection(collection).document("doc-" + age).set(data).get();
        }
        try {
            // orderBy ascending
            List<Long> asc = firestore.collection(collection).orderBy("age").get().get()
                    .getDocuments().stream().map(d -> d.getLong("age")).toList();
            assertThat(asc).containsExactly(10L, 20L, 30L, 40L);

            // orderBy descending
            List<Long> desc = firestore.collection(collection)
                    .orderBy("age", Query.Direction.DESCENDING).get().get()
                    .getDocuments().stream().map(d -> d.getLong("age")).toList();
            assertThat(desc).containsExactly(40L, 30L, 20L, 10L);

            // startAfter (exclusive)
            List<Long> after = firestore.collection(collection).orderBy("age")
                    .startAfter(20L).get().get()
                    .getDocuments().stream().map(d -> d.getLong("age")).toList();
            assertThat(after).containsExactly(30L, 40L);

            // startAt (inclusive) + endAt (inclusive) — a window
            List<Long> window = firestore.collection(collection).orderBy("age")
                    .startAt(20L).endAt(30L).get().get()
                    .getDocuments().stream().map(d -> d.getLong("age")).toList();
            assertThat(window).containsExactly(20L, 30L);

            // endBefore (exclusive)
            List<Long> before = firestore.collection(collection).orderBy("age")
                    .endBefore(30L).get().get()
                    .getDocuments().stream().map(d -> d.getLong("age")).toList();
            assertThat(before).containsExactly(10L, 20L);
        } finally {
            for (long age : ages) {
                firestore.collection(collection).document("doc-" + age).delete().get();
            }
        }
    }

    @Test
    @Order(6)
    void deleteDocument() throws ExecutionException, InterruptedException {
        DocumentReference docRef = firestore.collection(COLLECTION).document(DOC_ID);
        WriteResult result = docRef.delete().get();

        assertThat(result.getUpdateTime()).isNotNull();

        DocumentSnapshot snapshot = docRef.get().get();
        assertThat(snapshot.exists()).isFalse();
    }

    @Test
    @Order(7)
    void createFailsWhenDocumentAlreadyExists() throws ExecutionException, InterruptedException {
        DocumentReference docRef = firestore.collection(COLLECTION)
                .document(TestFixtures.uniqueName("precond"));
        docRef.create(Map.of("name", "Alice")).get();
        try {
            assertThatThrownBy(() -> docRef.create(Map.of("name", "Bob")).get())
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .hasMessageContaining("already exists");

            DocumentSnapshot snapshot = docRef.get().get();
            assertThat(snapshot.getString("name")).isEqualTo("Alice");
        } finally {
            docRef.delete().get();
        }
    }

    @Test
    @Order(8)
    void updateFailsWhenDocumentMissing() {
        DocumentReference docRef = firestore.collection(COLLECTION)
                .document(TestFixtures.uniqueName("missing"));

        assertThatThrownBy(() -> docRef.update("name", "Alice").get())
                .isInstanceOf(ExecutionException.class)
                .cause()
                .hasMessageContaining("No document to update");
    }

    @Test
    @Order(9)
    void staleUpdateTimePreconditionFails() throws ExecutionException, InterruptedException {
        DocumentReference docRef = firestore.collection(COLLECTION)
                .document(TestFixtures.uniqueName("precond"));
        WriteResult first = docRef.set(Map.of("version", 1L)).get();
        WriteResult second = docRef.set(Map.of("version", 2L)).get();
        try {
            assertThatThrownBy(() -> docRef.update(
                    Map.of("version", 3L), Precondition.updatedAt(first.getUpdateTime())).get())
                    .isInstanceOf(ExecutionException.class);

            // matching precondition succeeds
            docRef.update(Map.of("version", 3L), Precondition.updatedAt(second.getUpdateTime())).get();
            assertThat(docRef.get().get().getLong("version")).isEqualTo(3L);
        } finally {
            docRef.delete().get();
        }
    }

    @Test
    @Order(10)
    void transactionRetriesOnConcurrentModification() throws ExecutionException, InterruptedException {
        DocumentReference docRef = firestore.collection(COLLECTION)
                .document(TestFixtures.uniqueName("counter"));
        docRef.set(Map.of("count", 0L)).get();
        AtomicInteger attempts = new AtomicInteger();
        try {
            firestore.runTransaction(transaction -> {
                long current = transaction.get(docRef).get().getLong("count");
                if (attempts.incrementAndGet() == 1) {
                    // out-of-band write invalidates the transaction's read set
                    docRef.update("count", 100L).get();
                }
                transaction.update(docRef, "count", current + 1);
                return null;
            }).get();

            assertThat(attempts.get()).isEqualTo(2);
            assertThat(docRef.get().get().getLong("count")).isEqualTo(101L);
        } finally {
            docRef.delete().get();
        }
    }

    @Test
    @Order(11)
    void queryDocumentsByNestedMapField() throws ExecutionException, InterruptedException {
        String collection = TestFixtures.uniqueName("nested-query");
        DocumentReference matching = firestore.collection(collection).document("matching");
        DocumentReference different = firestore.collection(collection).document("different");
        DocumentReference missing = firestore.collection(collection).document("missing");

        matching.set(Map.of("billing", Map.of("stripe_customer_id", "cus_matching"))).get();
        different.set(Map.of("billing", Map.of("stripe_customer_id", "cus_different"))).get();
        missing.set(Map.of("name", "No billing field")).get();

        try {
            List<String> documentIds = firestore.collection(collection)
                    .whereEqualTo("billing.stripe_customer_id", "cus_matching")
                    .get()
                    .get()
                    .getDocuments()
                    .stream()
                    .map(DocumentSnapshot::getId)
                    .toList();

            assertThat(documentIds).containsExactly("matching");
        } finally {
            matching.delete().get();
            different.delete().get();
            missing.delete().get();
        }
    }
}
