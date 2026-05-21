package io.floci.gcp;

import com.google.cloud.NoCredentials;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.WriteResult;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirestoreIntegrationTest {

    private static final String COLLECTION = "test-users";
    private static final String DOC_ID = "user-" + UUID.randomUUID().toString().substring(0, 8);

    private static Firestore firestore;

    @BeforeAll
    static void setUp() {
        // Quarkus @QuarkusTest binds on port 8081 (quarkus.http.test-port default).
        // gRPC shares this port (use-separate-server=false).
        // GrpcFirestoreRpc uses plaintext when host contains "localhost" or credentials == NoCredentials.
        // setHost routes the channel to the local emulator port.
        firestore = FirestoreOptions.newBuilder()
                .setProjectId("test-project")
                .setHost("localhost:8081")
                .setCredentials(NoCredentials.getInstance())
                .build()
                .getService();
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

        WriteResult result = firestore.collection(COLLECTION).document(DOC_ID).set(data).get();
        assertNotNull(result.getUpdateTime());
    }

    @Test
    @Order(2)
    void getDocumentAndVerifyFields() throws ExecutionException, InterruptedException {
        DocumentSnapshot snapshot = firestore.collection(COLLECTION).document(DOC_ID).get().get();

        assertTrue(snapshot.exists());
        assertEquals("Alice", snapshot.getString("name"));
        assertEquals(30L, snapshot.getLong("age"));
        assertEquals("alice@example.com", snapshot.getString("email"));
        assertEquals(Boolean.TRUE, snapshot.getBoolean("active"));
    }

    @Test
    @Order(3)
    void updateDocumentField() throws ExecutionException, InterruptedException {
        DocumentReference docRef = firestore.collection(COLLECTION).document(DOC_ID);
        WriteResult result = docRef.update("age", 31L, "city", "New York").get();
        assertNotNull(result.getUpdateTime());

        DocumentSnapshot snapshot = docRef.get().get();
        assertEquals(31L, snapshot.getLong("age"));
        assertEquals("New York", snapshot.getString("city"));
        assertEquals("Alice", snapshot.getString("name"));
    }

    @Test
    @Order(4)
    void queryDocumentsWithFilter() throws ExecutionException, InterruptedException {
        String secondDocId = "user-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> secondData = new HashMap<>();
        secondData.put("name", "Bob");
        secondData.put("age", 25L);
        secondData.put("active", false);
        firestore.collection(COLLECTION).document(secondDocId).set(secondData).get();

        QuerySnapshot querySnapshot = firestore.collection(COLLECTION)
                .whereEqualTo("active", true)
                .get()
                .get();

        List<String> names = querySnapshot.getDocuments().stream()
                .map(d -> d.getString("name"))
                .toList();
        assertTrue(names.contains("Alice"));
        assertFalse(names.contains("Bob"));

        firestore.collection(COLLECTION).document(secondDocId).delete().get();
    }

    @Test
    @Order(5)
    void deleteDocument() throws ExecutionException, InterruptedException {
        WriteResult result = firestore.collection(COLLECTION).document(DOC_ID).delete().get();
        assertNotNull(result.getUpdateTime());

        DocumentSnapshot snapshot = firestore.collection(COLLECTION).document(DOC_ID).get().get();
        assertFalse(snapshot.exists());
    }
}
