package io.floci.gcp.services.datastore;

import com.google.datastore.v1.Entity;
import com.google.datastore.v1.Key;
import com.google.datastore.v1.KindExpression;
import com.google.datastore.v1.Mutation;
import com.google.datastore.v1.PartitionId;
import com.google.datastore.v1.Query;
import com.google.datastore.v1.Value;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.datastore.model.StoredEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DatastoreServiceTest {

    private DatastoreService service;
    private static final String PROJECT = "p1";

    @BeforeEach
    void setUp() {
        service = new DatastoreService(new InMemoryStorage<>());
    }

    private Key namedKey(String kind, String name) {
        return Key.newBuilder()
                .setPartitionId(PartitionId.newBuilder().setProjectId(PROJECT).build())
                .addPath(Key.PathElement.newBuilder().setKind(kind).setName(name).build())
                .build();
    }

    @Test
    void upsertAndLookupEntityByName() {
        Key key = namedKey("Person", "alice");
        Entity entity = Entity.newBuilder()
                .setKey(key)
                .putProperties("name", Value.newBuilder().setStringValue("Alice").build())
                .build();

        service.applyMutation(PROJECT, Mutation.newBuilder().setUpsert(entity).build(), Instant.now());

        Optional<StoredEntity> result = service.lookupEntity(PROJECT, key);
        assertTrue(result.isPresent());
        assertEquals("Person", result.get().getKind());
        assertEquals("alice", result.get().getKeyName());
    }

    @Test
    void lookupMissingEntityReturnsEmpty() {
        Key key = namedKey("Person", "missing");
        Optional<StoredEntity> result = service.lookupEntity(PROJECT, key);
        assertTrue(result.isEmpty());
    }

    @Test
    void insertDuplicateThrowsAlreadyExists() {
        Key key = namedKey("Thing", "x");
        Entity entity = Entity.newBuilder().setKey(key).build();
        service.applyMutation(PROJECT, Mutation.newBuilder().setInsert(entity).build(), Instant.now());

        GcpException ex = assertThrows(GcpException.class,
                () -> service.applyMutation(PROJECT,
                        Mutation.newBuilder().setInsert(entity).build(), Instant.now()));
        assertEquals("ALREADY_EXISTS", ex.getGcpStatus());
    }

    @Test
    void updateMissingEntityThrowsNotFound() {
        Key key = namedKey("Thing", "missing");
        Entity entity = Entity.newBuilder().setKey(key).build();

        GcpException ex = assertThrows(GcpException.class,
                () -> service.applyMutation(PROJECT,
                        Mutation.newBuilder().setUpdate(entity).build(), Instant.now()));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    @Test
    void deleteEntityRemovedFromLookup() {
        Key key = namedKey("Person", "bob");
        Entity entity = Entity.newBuilder().setKey(key).build();
        service.applyMutation(PROJECT, Mutation.newBuilder().setUpsert(entity).build(), Instant.now());

        service.applyMutation(PROJECT, Mutation.newBuilder().setDelete(key).build(), Instant.now());

        assertTrue(service.lookupEntity(PROJECT, key).isEmpty());
    }

    @Test
    void runQueryReturnsEntitiesOfKind() {
        for (String name : List.of("a", "b")) {
            Key key = namedKey("Item", name);
            Entity entity = Entity.newBuilder().setKey(key).build();
            service.applyMutation(PROJECT, Mutation.newBuilder().setUpsert(entity).build(), Instant.now());
        }

        Query query = Query.newBuilder()
                .addKind(KindExpression.newBuilder().setName("Item").build())
                .build();

        List<StoredEntity> results = service.runQuery(PROJECT, null, query);
        assertEquals(2, results.size());
    }

    @Test
    void allocateIdsReturnsFilledKeys() {
        Key incomplete = Key.newBuilder()
                .setPartitionId(PartitionId.newBuilder().setProjectId(PROJECT).build())
                .addPath(Key.PathElement.newBuilder().setKind("Widget").build())
                .build();

        List<Key> allocated = service.allocateIds(PROJECT, List.of(incomplete));
        assertEquals(1, allocated.size());
        assertTrue(allocated.get(0).getPath(0).getId() > 0);
    }

    @Test
    void beginTransactionReturnsByteArray() {
        byte[] txn = service.beginTransaction();
        assertNotNull(txn);
        assertTrue(txn.length > 0);
    }
}
