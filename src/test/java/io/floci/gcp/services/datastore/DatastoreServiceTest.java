package io.floci.gcp.services.datastore;

import com.google.datastore.v1.ArrayValue;
import com.google.datastore.v1.Entity;
import com.google.datastore.v1.Filter;
import com.google.datastore.v1.Key;
import com.google.datastore.v1.KindExpression;
import com.google.datastore.v1.Mutation;
import com.google.datastore.v1.PartitionId;
import com.google.datastore.v1.PropertyFilter;
import com.google.datastore.v1.PropertyReference;
import com.google.datastore.v1.Query;
import com.google.datastore.v1.Value;
import com.google.protobuf.ByteString;
import com.google.protobuf.NullValue;
import com.google.protobuf.Timestamp;
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
    void geoPointValueReadsBackUnchanged() {
        Key key = namedKey("Place", "hq");
        Value.Builder at = Value.newBuilder();
        at.getGeoPointValueBuilder().setLatitude(37.422).setLongitude(-122.084);
        Entity entity = Entity.newBuilder()
                .setKey(key)
                .putProperties("at", at.build())
                .build();
        service.applyMutation(PROJECT, Mutation.newBuilder().setUpsert(entity).build(), Instant.now());

        Value stored = service.lookupEntity(PROJECT, key).orElseThrow().getProperties().get("at").toProto();

        assertEquals(Value.ValueTypeCase.GEO_POINT_VALUE, stored.getValueTypeCase());
        assertEquals(37.422, stored.getGeoPointValue().getLatitude());
        assertEquals(-122.084, stored.getGeoPointValue().getLongitude());
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

    @Test
    void equalFilterOnTimestampReturnsOnlyMatchingEntity() {
        Value first = Value.newBuilder()
                .setTimestampValue(Timestamp.newBuilder().setSeconds(1_700_000_000L).setNanos(100).build())
                .build();
        Value second = Value.newBuilder()
                .setTimestampValue(Timestamp.newBuilder().setSeconds(1_700_000_000L).setNanos(200).build())
                .build();
        upsertProperty("Event", "first", "seen", first);
        upsertProperty("Event", "second", "seen", second);

        List<StoredEntity> equal = runPropertyQuery("Event", "seen", PropertyFilter.Operator.EQUAL, first);
        assertEquals(List.of("first"), namesOf(equal));

        List<StoredEntity> notEqual = runPropertyQuery("Event", "seen", PropertyFilter.Operator.NOT_EQUAL, first);
        assertEquals(List.of("second"), namesOf(notEqual));
    }

    @Test
    void equalFilterOnBlobReturnsOnlyMatchingEntity() {
        Value first = Value.newBuilder().setBlobValue(ByteString.copyFrom(new byte[] {1, 2, 3})).build();
        Value second = Value.newBuilder().setBlobValue(ByteString.copyFrom(new byte[] {4, 5})).build();
        upsertProperty("BlobKind", "first", "payload", first);
        upsertProperty("BlobKind", "second", "payload", second);

        List<StoredEntity> results = runPropertyQuery(
                "BlobKind", "payload", PropertyFilter.Operator.EQUAL, first);
        assertEquals(List.of("first"), namesOf(results));
    }

    @Test
    void equalFilterOnKeyMatchesSamePathOnly() {
        Value first = Value.newBuilder().setKeyValue(namedKey("Ref", "alpha")).build();
        Value second = Value.newBuilder().setKeyValue(namedKey("Ref", "beta")).build();
        upsertProperty("Link", "first", "target", first);
        upsertProperty("Link", "second", "target", second);

        List<StoredEntity> results = runPropertyQuery("Link", "target", PropertyFilter.Operator.EQUAL, first);
        assertEquals(List.of("first"), namesOf(results));
    }

    @Test
    void equalFilterOnEntityMatchesSamePropertiesOnly() {
        Value first = Value.newBuilder()
                .setEntityValue(Entity.newBuilder()
                        .putProperties("n", Value.newBuilder().setStringValue("a").build())
                        .build())
                .build();
        Value extra = Value.newBuilder()
                .setEntityValue(Entity.newBuilder()
                        .putProperties("n", Value.newBuilder().setStringValue("a").build())
                        .putProperties("extra", Value.newBuilder().setIntegerValue(1).build())
                        .build())
                .build();
        upsertProperty("Wrap", "first", "body", first);
        upsertProperty("Wrap", "extra", "body", extra);

        List<StoredEntity> results = runPropertyQuery("Wrap", "body", PropertyFilter.Operator.EQUAL, first);
        assertEquals(List.of("first"), namesOf(results));
    }

    @Test
    void equalFilterOnArrayMatchesWhenAnyElementEquals() {
        upsertProperty("Post", "tagged", "tags", arrayValue("fun", "programming"));
        upsertProperty("Post", "other", "tags", arrayValue("work"));

        Value fun = Value.newBuilder().setStringValue("fun").build();
        assertEquals(List.of("tagged"), namesOf(
                runPropertyQuery("Post", "tags", PropertyFilter.Operator.EQUAL, fun)));

        Value programming = Value.newBuilder().setStringValue("programming").build();
        assertEquals(List.of("tagged"), namesOf(
                runPropertyQuery("Post", "tags", PropertyFilter.Operator.EQUAL, programming)));

        Value missing = Value.newBuilder().setStringValue("missing").build();
        List<StoredEntity> absent = runPropertyQuery("Post", "tags", PropertyFilter.Operator.EQUAL, missing);
        assertTrue(absent.isEmpty());
    }

    @Test
    void equalFilterOnScalarPropertyStillMatchesByEquality() {
        Value title = Value.newBuilder().setStringValue("fun").build();
        upsertProperty("Note", "match", "title", title);
        upsertProperty("Note", "other", "title", Value.newBuilder().setStringValue("work").build());

        List<StoredEntity> results = runPropertyQuery("Note", "title", PropertyFilter.Operator.EQUAL, title);
        assertEquals(List.of("match"), namesOf(results));
    }

    @Test
    void outOfRangeTimestampFilterReturnsNoEntities() {
        Value stored = Value.newBuilder()
                .setTimestampValue(Timestamp.newBuilder().setSeconds(1_700_000_000L).build())
                .build();
        upsertProperty("Event", "only", "seen", stored);
        Value filter = Value.newBuilder()
                .setTimestampValue(Timestamp.newBuilder().setSeconds(Long.MAX_VALUE).build())
                .build();

        List<StoredEntity> results = runPropertyQuery("Event", "seen", PropertyFilter.Operator.EQUAL, filter);
        assertTrue(results.isEmpty());
    }

    @Test
    void timestampFilterAgainstStringPropertyReturnsNoEntities() {
        upsertProperty("Event", "only", "seen", Value.newBuilder().setStringValue("1700000000").build());
        Value filter = Value.newBuilder()
                .setTimestampValue(Timestamp.newBuilder().setSeconds(1_700_000_000L).build())
                .build();

        List<StoredEntity> results = runPropertyQuery("Event", "seen", PropertyFilter.Operator.EQUAL, filter);
        assertTrue(results.isEmpty());
    }

    @Test
    void equalFilterStillMatchesBooleanIntegerDoubleStringAndNull() {
        Value yes = Value.newBuilder().setBooleanValue(true).build();
        upsertProperty("Bools", "yes", "v", yes);
        upsertProperty("Bools", "no", "v", Value.newBuilder().setBooleanValue(false).build());
        assertEquals(List.of("yes"), namesOf(runPropertyQuery("Bools", "v", PropertyFilter.Operator.EQUAL, yes)));

        Value one = Value.newBuilder().setIntegerValue(1L).build();
        upsertProperty("Ints", "one", "v", one);
        upsertProperty("Ints", "two", "v", Value.newBuilder().setIntegerValue(2L).build());
        assertEquals(List.of("one"), namesOf(runPropertyQuery("Ints", "v", PropertyFilter.Operator.EQUAL, one)));

        Value half = Value.newBuilder().setDoubleValue(1.5).build();
        upsertProperty("Doubles", "half", "v", half);
        upsertProperty("Doubles", "other", "v", Value.newBuilder().setDoubleValue(2.5).build());
        assertEquals(List.of("half"), namesOf(runPropertyQuery("Doubles", "v", PropertyFilter.Operator.EQUAL, half)));

        Value name = Value.newBuilder().setStringValue("Ada").build();
        upsertProperty("Strings", "ada", "v", name);
        upsertProperty("Strings", "bob", "v", Value.newBuilder().setStringValue("Bob").build());
        assertEquals(List.of("ada"), namesOf(runPropertyQuery("Strings", "v", PropertyFilter.Operator.EQUAL, name)));

        Value nil = Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
        upsertProperty("Nulls", "nil", "v", nil);
        upsertProperty("Nulls", "text", "v", Value.newBuilder().setStringValue("x").build());
        assertEquals(List.of("nil"), namesOf(runPropertyQuery("Nulls", "v", PropertyFilter.Operator.EQUAL, nil)));
    }

    private void upsertProperty(String kind, String name, String property, Value value) {
        Entity entity = Entity.newBuilder()
                .setKey(namedKey(kind, name))
                .putProperties(property, value)
                .build();
        service.applyMutation(PROJECT, Mutation.newBuilder().setUpsert(entity).build(), Instant.now());
    }

    private List<StoredEntity> runPropertyQuery(
            String kind, String property, PropertyFilter.Operator op, Value value) {
        Query query = Query.newBuilder()
                .addKind(KindExpression.newBuilder().setName(kind).build())
                .setFilter(Filter.newBuilder()
                        .setPropertyFilter(PropertyFilter.newBuilder()
                                .setProperty(PropertyReference.newBuilder().setName(property).build())
                                .setOp(op)
                                .setValue(value)
                                .build())
                        .build())
                .build();
        return service.runQuery(PROJECT, null, query);
    }

    private static Value arrayValue(String... elements) {
        ArrayValue.Builder array = ArrayValue.newBuilder();
        for (String element : elements) {
            array.addValues(Value.newBuilder().setStringValue(element).build());
        }
        return Value.newBuilder().setArrayValue(array.build()).build();
    }

    private static List<String> namesOf(List<StoredEntity> entities) {
        return entities.stream().map(StoredEntity::getKeyName).toList();
    }
}
