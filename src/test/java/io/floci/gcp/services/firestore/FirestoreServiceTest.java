package io.floci.gcp.services.firestore;

import com.google.firestore.v1.Document;
import com.google.firestore.v1.StructuredQuery;
import com.google.firestore.v1.Value;
import com.google.firestore.v1.Write;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.firestore.model.StoredDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FirestoreServiceTest {

    private FirestoreService service;
    private static final String DB = "projects/p1/databases/(default)";
    private static final String DOC_NAME = DB + "/documents/users/alice";

    @BeforeEach
    void setUp() {
        service = new FirestoreService(new InMemoryStorage<>());
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
}
