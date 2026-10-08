package io.floci.gcp.services.firestore;

import com.google.firestore.v1.ArrayValue;
import com.google.firestore.v1.Cursor;
import com.google.firestore.v1.Document;
import com.google.firestore.v1.DocumentMask;
import com.google.firestore.v1.DocumentTransform;
import com.google.firestore.v1.MapValue;
import com.google.firestore.v1.Precondition;
import com.google.firestore.v1.StructuredQuery;
import com.google.firestore.v1.Value;
import com.google.firestore.v1.Write;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.google.type.LatLng;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.firestore.model.StoredDocument;
import io.floci.gcp.services.firestore.model.StoredValue;
import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    @Test
    void nestedInclusiveInequalitiesRejectIncompatibleTypes() {
        service.applyWrite(nestedValueDocument("number", "threshold",
                Value.newBuilder().setIntegerValue(10).build()), Instant.now());
        service.applyWrite(nestedValueDocument("string", "threshold",
                Value.newBuilder().setStringValue("10").build()), Instant.now());
        service.applyWrite(nestedValueDocument("boolean", "threshold",
                Value.newBuilder().setBooleanValue(true).build()), Instant.now());

        for (StructuredQuery.FieldFilter.Operator operator : List.of(
                StructuredQuery.FieldFilter.Operator.LESS_THAN_OR_EQUAL,
                StructuredQuery.FieldFilter.Operator.GREATER_THAN_OR_EQUAL)) {
            List<StoredDocument> results = runNestedFilter(
                    "billing.threshold", operator, Value.newBuilder().setIntegerValue(10).build());

            assertEquals(List.of(DB + "/documents/customers/number"),
                    results.stream().map(StoredDocument::getName).toList());
        }
    }

    @Test
    void nestedInclusiveInequalitiesPreserveNumericCrossComparison() {
        service.applyWrite(nestedValueDocument("integer", "threshold",
                Value.newBuilder().setIntegerValue(10).build()), Instant.now());
        service.applyWrite(nestedValueDocument("double", "threshold",
                Value.newBuilder().setDoubleValue(10.0).build()), Instant.now());
        service.applyWrite(nestedValueDocument("different", "threshold",
                Value.newBuilder().setDoubleValue(11.0).build()), Instant.now());

        List<StoredDocument> results = runNestedFilter(
                "billing.threshold",
                StructuredQuery.FieldFilter.Operator.GREATER_THAN_OR_EQUAL,
                Value.newBuilder().setDoubleValue(10.0).build());

        assertEquals(Set.of(
                        DB + "/documents/customers/integer",
                        DB + "/documents/customers/double",
                        DB + "/documents/customers/different"),
                Set.copyOf(results.stream().map(StoredDocument::getName).toList()));
    }

    @Test
    void nestedInclusiveInequalitiesPreserveSameTypeStringComparison() {
        service.applyWrite(nestedValueDocument("alpha", "tier",
                Value.newBuilder().setStringValue("alpha").build()), Instant.now());
        service.applyWrite(nestedValueDocument("beta", "tier",
                Value.newBuilder().setStringValue("beta").build()), Instant.now());
        service.applyWrite(nestedValueDocument("number", "tier",
                Value.newBuilder().setIntegerValue(0).build()), Instant.now());

        List<StoredDocument> results = runNestedFilter(
                "billing.tier",
                StructuredQuery.FieldFilter.Operator.LESS_THAN_OR_EQUAL,
                Value.newBuilder().setStringValue("beta").build());

        assertEquals(List.of(
                        DB + "/documents/customers/alpha",
                        DB + "/documents/customers/beta"),
                results.stream().map(StoredDocument::getName).toList());
    }

    @Test
    void equalFilterMatchesTimestampReadBackFromDocument() {
        Timestamp at = Timestamp.newBuilder().setSeconds(1756555200).build();
        service.applyWrite(topLevelValueDocument("matching", "at",
                Value.newBuilder().setTimestampValue(at).build()), Instant.now());
        service.applyWrite(topLevelValueDocument("different", "at",
                Value.newBuilder().setTimestampValue(
                        at.toBuilder().setSeconds(at.getSeconds() + 1)).build()), Instant.now());

        List<StoredDocument> results = runTopLevelFilter("at",
                StructuredQuery.FieldFilter.Operator.EQUAL, Value.newBuilder().setTimestampValue(at).build());

        assertEquals(List.of(DB + "/documents/customers/matching"),
                results.stream().map(StoredDocument::getName).toList());
    }

    @Test
    void notEqualFilterExcludesMatchingTimestampInsteadOfMatchingEverything() {
        Timestamp at = Timestamp.newBuilder().setSeconds(1756555200).build();
        service.applyWrite(topLevelValueDocument("matching", "at",
                Value.newBuilder().setTimestampValue(at).build()), Instant.now());
        service.applyWrite(topLevelValueDocument("different", "at",
                Value.newBuilder().setTimestampValue(
                        at.toBuilder().setSeconds(at.getSeconds() + 1)).build()), Instant.now());

        List<StoredDocument> results = runTopLevelFilter("at",
                StructuredQuery.FieldFilter.Operator.NOT_EQUAL, Value.newBuilder().setTimestampValue(at).build());

        assertEquals(List.of(DB + "/documents/customers/different"),
                results.stream().map(StoredDocument::getName).toList());
    }

    @Test
    void geoPointValueReadsBackUnchanged() {
        LatLng at = LatLng.newBuilder().setLatitude(37.422).setLongitude(-122.084).build();
        Document doc = Document.newBuilder()
                .setName(DOC_NAME)
                .putFields("at", Value.newBuilder().setGeoPointValue(at).build())
                .build();
        service.applyWrite(Write.newBuilder().setUpdate(doc).build(), Instant.now());

        Value stored = service.getDocument(DOC_NAME).orElseThrow().getFields().get("at").toProto();

        assertEquals(Value.ValueTypeCase.GEO_POINT_VALUE, stored.getValueTypeCase());
        assertEquals(37.422, stored.getGeoPointValue().getLatitude());
        assertEquals(-122.084, stored.getGeoPointValue().getLongitude());
    }

    @Test
    void orderByGeoPointSortsByLatitudeThenLongitude() {
        writeGeoPointFixture();

        assertEquals(List.of("south", "origin", "west", "east"),
                ids(runGeoPointOrder(StructuredQuery.Direction.ASCENDING, null)));
        assertEquals(List.of("east", "west", "origin", "south"),
                ids(runGeoPointOrder(StructuredQuery.Direction.DESCENDING, null)));
    }

    @Test
    void geoPointCursorStartsAfterTheCursorPoint() {
        writeGeoPointFixture();

        Cursor startAfterWest = Cursor.newBuilder().addValues(geoPoint(10, -5)).setBefore(false).build();

        assertEquals(List.of("east"),
                ids(runGeoPointOrder(StructuredQuery.Direction.ASCENDING, startAfterWest)));
    }

    @Test
    void rangeFiltersOnGeoPointCompareLatitudeThenLongitude() {
        writeGeoPointFixture();
        service.applyWrite(topLevelValueDocument("text", "at",
                Value.newBuilder().setStringValue("10,-5").build()), Instant.now());

        assertEquals(Set.of("east"), Set.copyOf(ids(runTopLevelFilter("at",
                StructuredQuery.FieldFilter.Operator.GREATER_THAN, geoPoint(10, -5)))));
        assertEquals(Set.of("west", "east"), Set.copyOf(ids(runTopLevelFilter("at",
                StructuredQuery.FieldFilter.Operator.GREATER_THAN_OR_EQUAL, geoPoint(10, -5)))));
        assertEquals(Set.of("south"), Set.copyOf(ids(runTopLevelFilter("at",
                StructuredQuery.FieldFilter.Operator.LESS_THAN, geoPoint(0, 0)))));
        assertEquals(Set.of("south", "origin"), Set.copyOf(ids(runTopLevelFilter("at",
                StructuredQuery.FieldFilter.Operator.LESS_THAN_OR_EQUAL, geoPoint(0, 0)))));
    }

    private void writeGeoPointFixture() {
        service.applyWrite(topLevelValueDocument("east", "at", geoPoint(10, 5)), Instant.now());
        service.applyWrite(topLevelValueDocument("origin", "at", geoPoint(0, 0)), Instant.now());
        service.applyWrite(topLevelValueDocument("south", "at", geoPoint(-20, 50)), Instant.now());
        service.applyWrite(topLevelValueDocument("west", "at", geoPoint(10, -5)), Instant.now());
    }

    private List<StoredDocument> runGeoPointOrder(StructuredQuery.Direction direction, Cursor startAt) {
        StructuredQuery.Builder query = StructuredQuery.newBuilder()
                .addFrom(StructuredQuery.CollectionSelector.newBuilder()
                        .setCollectionId("customers").build())
                .addOrderBy(StructuredQuery.Order.newBuilder()
                        .setField(StructuredQuery.FieldReference.newBuilder().setFieldPath("at"))
                        .setDirection(direction));
        if (startAt != null) {
            query.setStartAt(startAt);
        }
        return service.runQuery(DB + "/documents", query.build());
    }

    private static Value geoPoint(double latitude, double longitude) {
        return Value.newBuilder()
                .setGeoPointValue(LatLng.newBuilder().setLatitude(latitude).setLongitude(longitude))
                .build();
    }

    private static List<String> ids(List<StoredDocument> docs) {
        return docs.stream().map(d -> d.getName().substring(d.getName().lastIndexOf('/') + 1)).toList();
    }

    @Test
    void equalFilterMatchesBytesReadBackFromDocument() {
        ByteString payload = ByteString.copyFromUtf8("secret");
        service.applyWrite(topLevelValueDocument("matching", "blob",
                Value.newBuilder().setBytesValue(payload).build()), Instant.now());
        service.applyWrite(topLevelValueDocument("different", "blob",
                Value.newBuilder().setBytesValue(ByteString.copyFromUtf8("other")).build()), Instant.now());

        List<StoredDocument> results = runTopLevelFilter("blob",
                StructuredQuery.FieldFilter.Operator.EQUAL, Value.newBuilder().setBytesValue(payload).build());

        assertEquals(List.of(DB + "/documents/customers/matching"),
                results.stream().map(StoredDocument::getName).toList());
    }

    @Test
    void equalFilterOnOutOfRangeTimestampDoesNotThrow() {
        Timestamp at = Timestamp.newBuilder().setSeconds(1756555200).build();
        service.applyWrite(topLevelValueDocument("matching", "at",
                Value.newBuilder().setTimestampValue(at).build()), Instant.now());

        List<StoredDocument> results = runTopLevelFilter("at", StructuredQuery.FieldFilter.Operator.EQUAL,
                Value.newBuilder().setTimestampValue(
                        Timestamp.newBuilder().setSeconds(Long.MAX_VALUE)).build());

        assertEquals(List.of(), results);
    }

    @Test
    void inFilterMatchesTimestampReadBackFromDocument() {
        Timestamp at = Timestamp.newBuilder().setSeconds(1756555200).build();
        service.applyWrite(topLevelValueDocument("matching", "at",
                Value.newBuilder().setTimestampValue(at).build()), Instant.now());
        service.applyWrite(topLevelValueDocument("different", "at",
                Value.newBuilder().setTimestampValue(
                        at.toBuilder().setSeconds(at.getSeconds() + 1)).build()), Instant.now());

        List<StoredDocument> results = runTopLevelFilter("at", StructuredQuery.FieldFilter.Operator.IN,
                Value.newBuilder().setArrayValue(ArrayValue.newBuilder()
                        .addValues(Value.newBuilder().setTimestampValue(at))).build());

        assertEquals(List.of(DB + "/documents/customers/matching"),
                results.stream().map(StoredDocument::getName).toList());
    }

    @Test
    void notInFilterExcludesMatchingTimestampInsteadOfMatchingEverything() {
        Timestamp at = Timestamp.newBuilder().setSeconds(1756555200).build();
        service.applyWrite(topLevelValueDocument("matching", "at",
                Value.newBuilder().setTimestampValue(at).build()), Instant.now());
        service.applyWrite(topLevelValueDocument("different", "at",
                Value.newBuilder().setTimestampValue(
                        at.toBuilder().setSeconds(at.getSeconds() + 1)).build()), Instant.now());

        List<StoredDocument> results = runTopLevelFilter("at", StructuredQuery.FieldFilter.Operator.NOT_IN,
                Value.newBuilder().setArrayValue(ArrayValue.newBuilder()
                        .addValues(Value.newBuilder().setTimestampValue(at))).build());

        assertEquals(List.of(DB + "/documents/customers/different"),
                results.stream().map(StoredDocument::getName).toList());
    }

    @Test
    void arrayContainsFilterMatchesTimestampElement() {
        Timestamp at = Timestamp.newBuilder().setSeconds(1756555200).build();
        service.applyWrite(topLevelValueDocument("matching", "history", Value.newBuilder()
                .setArrayValue(ArrayValue.newBuilder().addValues(Value.newBuilder().setTimestampValue(at)))
                .build()), Instant.now());
        service.applyWrite(topLevelValueDocument("different", "history", Value.newBuilder()
                .setArrayValue(ArrayValue.newBuilder().addValues(Value.newBuilder().setTimestampValue(
                        at.toBuilder().setSeconds(at.getSeconds() + 1))))
                .build()), Instant.now());

        List<StoredDocument> results = runTopLevelFilter("history",
                StructuredQuery.FieldFilter.Operator.ARRAY_CONTAINS, Value.newBuilder().setTimestampValue(at).build());

        assertEquals(List.of(DB + "/documents/customers/matching"),
                results.stream().map(StoredDocument::getName).toList());
    }

    @Test
    void arrayContainsAnyFilterMatchesTimestampElement() {
        Timestamp at = Timestamp.newBuilder().setSeconds(1756555200).build();
        service.applyWrite(topLevelValueDocument("matching", "history", Value.newBuilder()
                .setArrayValue(ArrayValue.newBuilder().addValues(Value.newBuilder().setTimestampValue(at)))
                .build()), Instant.now());
        service.applyWrite(topLevelValueDocument("different", "history", Value.newBuilder()
                .setArrayValue(ArrayValue.newBuilder().addValues(Value.newBuilder().setTimestampValue(
                        at.toBuilder().setSeconds(at.getSeconds() + 1))))
                .build()), Instant.now());

        List<StoredDocument> results = runTopLevelFilter("history",
                StructuredQuery.FieldFilter.Operator.ARRAY_CONTAINS_ANY,
                Value.newBuilder().setArrayValue(ArrayValue.newBuilder()
                        .addValues(Value.newBuilder().setTimestampValue(at))).build());

        assertEquals(List.of(DB + "/documents/customers/matching"),
                results.stream().map(StoredDocument::getName).toList());
    }

    @Test
    void arrayUnionDoesNotDuplicateMatchingTimestampElement() {
        Timestamp at = Timestamp.newBuilder().setSeconds(1756555200).build();
        String name = DB + "/documents/customers/union-target";
        service.applyWrite(topLevelValueDocument("union-target", "history", Value.newBuilder()
                .setArrayValue(ArrayValue.newBuilder().addValues(Value.newBuilder().setTimestampValue(at)))
                .build()), Instant.now());

        service.applyWrite(Write.newBuilder()
                .setTransform(DocumentTransform.newBuilder()
                        .setDocument(name)
                        .addFieldTransforms(DocumentTransform.FieldTransform.newBuilder()
                                .setFieldPath("history")
                                .setAppendMissingElements(ArrayValue.newBuilder()
                                        .addValues(Value.newBuilder().setTimestampValue(at)))))
                .build(), Instant.now());

        StoredValue history = service.getDocument(name).orElseThrow().getFields().get("history");
        assertEquals(1, history.getArrayValue().size());
    }

    @Test
    void arrayRemoveRemovesMatchingBytesElement() {
        ByteString payload = ByteString.copyFromUtf8("secret");
        String name = DB + "/documents/customers/remove-target";
        service.applyWrite(topLevelValueDocument("remove-target", "blobs", Value.newBuilder()
                .setArrayValue(ArrayValue.newBuilder().addValues(Value.newBuilder().setBytesValue(payload)))
                .build()), Instant.now());

        service.applyWrite(Write.newBuilder()
                .setTransform(DocumentTransform.newBuilder()
                        .setDocument(name)
                        .addFieldTransforms(DocumentTransform.FieldTransform.newBuilder()
                                .setFieldPath("blobs")
                                .setRemoveAllFromArray(ArrayValue.newBuilder()
                                        .addValues(Value.newBuilder().setBytesValue(payload)))))
                .build(), Instant.now());

        StoredValue blobs = service.getDocument(name).orElseThrow().getFields().get("blobs");
        assertEquals(0, blobs.getArrayValue().size());
    }

    @Test
    void equalityFilterMatchesArrayValue() {
        service.applyWrite(topLevelValueDocument("matching", "tags", stringArray("a", "b")), Instant.now());
        service.applyWrite(topLevelValueDocument("reordered", "tags", stringArray("b", "a")), Instant.now());
        service.applyWrite(topLevelValueDocument("longer", "tags", stringArray("a", "b", "c")), Instant.now());

        List<StoredDocument> results = runTopLevelFilter("tags",
                StructuredQuery.FieldFilter.Operator.EQUAL, stringArray("a", "b"));

        assertEquals(List.of(DB + "/documents/customers/matching"),
                results.stream().map(StoredDocument::getName).toList());
    }

    @Test
    void notEqualFilterExcludesMatchingMapValue() {
        Value address = Value.newBuilder().setMapValue(MapValue.newBuilder()
                .putFields("city", Value.newBuilder().setStringValue("Lisbon").build())
                .putFields("zip", Value.newBuilder().setIntegerValue(1000).build())).build();
        Value other = Value.newBuilder().setMapValue(MapValue.newBuilder()
                .putFields("city", Value.newBuilder().setStringValue("Porto").build())
                .putFields("zip", Value.newBuilder().setIntegerValue(1000).build())).build();
        service.applyWrite(topLevelValueDocument("matching", "address", address), Instant.now());
        service.applyWrite(topLevelValueDocument("different", "address", other), Instant.now());

        List<StoredDocument> results = runTopLevelFilter("address",
                StructuredQuery.FieldFilter.Operator.NOT_EQUAL, address);

        assertEquals(List.of(DB + "/documents/customers/different"),
                results.stream().map(StoredDocument::getName).toList());
    }

    @Test
    void arrayUnionSkipsElementAlreadyPresent() {
        String name = DB + "/documents/customers/union-string";
        service.applyWrite(topLevelValueDocument("union-string", "tags", stringArray()), Instant.now());

        service.applyWrite(appendMissing(name, "tags", Value.newBuilder().setStringValue("x").build()), Instant.now());
        service.applyWrite(appendMissing(name, "tags", Value.newBuilder().setStringValue("x").build()), Instant.now());

        StoredValue tags = service.getDocument(name).orElseThrow().getFields().get("tags");
        assertEquals(1, tags.getArrayValue().size());
    }

    @Test
    void arrayUnionSkipsMapElementAlreadyPresent() {
        String name = DB + "/documents/customers/union-map";
        Value entry = Value.newBuilder().setMapValue(MapValue.newBuilder()
                .putFields("id", Value.newBuilder().setStringValue("x").build())).build();
        service.applyWrite(topLevelValueDocument("union-map", "entries", Value.newBuilder()
                .setArrayValue(ArrayValue.newBuilder().addValues(entry)).build()), Instant.now());

        service.applyWrite(appendMissing(name, "entries", entry), Instant.now());

        StoredValue entries = service.getDocument(name).orElseThrow().getFields().get("entries");
        assertEquals(1, entries.getArrayValue().size());
    }

    @Test
    void arrayUnionTreatsIntegerAndDoubleAsEqual() {
        String name = DB + "/documents/customers/union-number";
        service.applyWrite(topLevelValueDocument("union-number", "vals", Value.newBuilder()
                .setArrayValue(ArrayValue.newBuilder().addValues(Value.newBuilder().setIntegerValue(3))).build()),
                Instant.now());

        service.applyWrite(appendMissing(name, "vals", Value.newBuilder().setDoubleValue(3.0).build()), Instant.now());

        List<StoredValue> vals = service.getDocument(name).orElseThrow().getFields().get("vals").getArrayValue();
        assertEquals(1, vals.size());
        assertEquals("integer", vals.get(0).getType());
        assertEquals(3L, vals.get(0).getIntegerValue());
        assertTrue(vals.get(0).matchesEqual(StoredValue.fromProto(Value.newBuilder().setDoubleValue(3.0).build())));
    }

    @Test
    void numericEqualityIsExactAcrossIntegerAndDouble() {
        // 2^53 + 1 has no double representation; the nearest double is 2^53.
        assertTrue(matches(integer(9007199254740992L), dbl(9007199254740992.0)));
        assertTrue(matches(dbl(9007199254740992.0), integer(9007199254740992L)));
        assertFalse(matches(integer(9007199254740993L), dbl(9007199254740992.0)));
        assertFalse(matches(dbl(9007199254740992.0), integer(9007199254740993L)));
        assertFalse(matches(integer(Long.MAX_VALUE), dbl(9.223372036854775807E18)));
        assertFalse(matches(integer(3), dbl(3.5)));
    }

    @Test
    void numericEqualityHandlesSignedZeroNaNAndInfinity() {
        assertTrue(matches(dbl(-0.0), dbl(0.0)));
        assertTrue(matches(dbl(0.0), dbl(-0.0)));
        assertTrue(matches(integer(0), dbl(-0.0)));
        assertTrue(matches(dbl(-0.0), integer(0)));

        // NaN matches only NaN.
        assertTrue(matches(dbl(Double.NaN), dbl(Double.NaN)));
        assertFalse(matches(dbl(Double.NaN), dbl(0.0)));
        assertFalse(matches(dbl(0.0), dbl(Double.NaN)));
        assertFalse(matches(integer(0), dbl(Double.NaN)));
        assertFalse(matches(dbl(Double.NaN), integer(0)));

        assertTrue(matches(dbl(Double.POSITIVE_INFINITY), dbl(Double.POSITIVE_INFINITY)));
        assertFalse(matches(dbl(Double.POSITIVE_INFINITY), dbl(Double.NEGATIVE_INFINITY)));
        assertFalse(matches(integer(Long.MAX_VALUE), dbl(Double.POSITIVE_INFINITY)));
        assertFalse(matches(dbl(Double.POSITIVE_INFINITY), integer(Long.MAX_VALUE)));
    }

    @Test
    void numericEdgeCasesApplyInsideArraysAndMaps() {
        Value intArray = Value.newBuilder().setArrayValue(ArrayValue.newBuilder()
                .addValues(integer(3)).addValues(integer(0))).build();
        Value doubleArray = Value.newBuilder().setArrayValue(ArrayValue.newBuilder()
                .addValues(dbl(3.0)).addValues(dbl(-0.0))).build();
        Value nanArray = Value.newBuilder().setArrayValue(ArrayValue.newBuilder()
                .addValues(dbl(Double.NaN))).build();
        Value bigInt = Value.newBuilder().setMapValue(MapValue.newBuilder()
                .putFields("n", integer(9007199254740993L))).build();
        Value bigDouble = Value.newBuilder().setMapValue(MapValue.newBuilder()
                .putFields("n", dbl(9007199254740992.0))).build();

        assertTrue(matches(intArray, doubleArray));
        assertTrue(matches(doubleArray, intArray));
        assertTrue(matches(nanArray, nanArray));
        assertFalse(matches(bigInt, bigDouble));
        assertFalse(matches(bigDouble, bigInt));

        service.applyWrite(topLevelValueDocument("ints", "vals", intArray), Instant.now());
        service.applyWrite(topLevelValueDocument("big", "vals", bigInt), Instant.now());
        assertEquals(List.of(DB + "/documents/customers/ints"),
                runTopLevelFilter("vals", StructuredQuery.FieldFilter.Operator.EQUAL, doubleArray)
                        .stream().map(StoredDocument::getName).toList());
        assertEquals(List.of(),
                runTopLevelFilter("vals", StructuredQuery.FieldFilter.Operator.EQUAL, bigDouble)
                        .stream().map(StoredDocument::getName).toList());
    }

    private static boolean matches(Value stored, Value query) {
        return StoredValue.fromProto(stored).matchesEqual(query);
    }

    private static Value integer(long value) {
        return Value.newBuilder().setIntegerValue(value).build();
    }

    private static Value dbl(double value) {
        return Value.newBuilder().setDoubleValue(value).build();
    }

    @Test
    void arrayRemoveRemovesMatchingMapElement() {
        String name = DB + "/documents/customers/remove-map";
        Value first = Value.newBuilder().setMapValue(MapValue.newBuilder()
                .putFields("id", Value.newBuilder().setStringValue("a").build())).build();
        Value second = Value.newBuilder().setMapValue(MapValue.newBuilder()
                .putFields("id", Value.newBuilder().setStringValue("b").build())).build();
        service.applyWrite(topLevelValueDocument("remove-map", "entries", Value.newBuilder()
                .setArrayValue(ArrayValue.newBuilder().addValues(first).addValues(second)).build()), Instant.now());

        service.applyWrite(Write.newBuilder()
                .setTransform(DocumentTransform.newBuilder()
                        .setDocument(name)
                        .addFieldTransforms(DocumentTransform.FieldTransform.newBuilder()
                                .setFieldPath("entries")
                                .setRemoveAllFromArray(ArrayValue.newBuilder().addValues(first))))
                .build(), Instant.now());

        StoredValue entries = service.getDocument(name).orElseThrow().getFields().get("entries");
        assertEquals(1, entries.getArrayValue().size());
        assertEquals("b", entries.getArrayValue().get(0).getMapValue().get("id").getStringValue());
    }

    private Value stringArray(String... values) {
        ArrayValue.Builder array = ArrayValue.newBuilder();
        for (String value : values) {
            array.addValues(Value.newBuilder().setStringValue(value));
        }
        return Value.newBuilder().setArrayValue(array).build();
    }

    private Write appendMissing(String name, String fieldPath, Value element) {
        return Write.newBuilder()
                .setTransform(DocumentTransform.newBuilder()
                        .setDocument(name)
                        .addFieldTransforms(DocumentTransform.FieldTransform.newBuilder()
                                .setFieldPath(fieldPath)
                                .setAppendMissingElements(ArrayValue.newBuilder().addValues(element))))
                .build();
    }

    private List<StoredDocument> runTopLevelFilter(String fieldPath,
            StructuredQuery.FieldFilter.Operator operator, Value value) {
        StructuredQuery query = StructuredQuery.newBuilder()
                .addFrom(StructuredQuery.CollectionSelector.newBuilder()
                        .setCollectionId("customers").build())
                .setWhere(StructuredQuery.Filter.newBuilder()
                        .setFieldFilter(StructuredQuery.FieldFilter.newBuilder()
                                .setField(StructuredQuery.FieldReference.newBuilder()
                                        .setFieldPath(fieldPath))
                                .setOp(operator)
                                .setValue(value)))
                .build();
        return service.runQuery(DB + "/documents", query);
    }

    private Write topLevelValueDocument(String id, String field, Value value) {
        return Write.newBuilder()
                .setUpdate(Document.newBuilder()
                        .setName(DB + "/documents/customers/" + id)
                        .putFields(field, value)
                        .build())
                .build();
    }

    private List<StoredDocument> runNestedFilter(String fieldPath,
            StructuredQuery.FieldFilter.Operator operator, Value value) {
        StructuredQuery query = StructuredQuery.newBuilder()
                .addFrom(StructuredQuery.CollectionSelector.newBuilder()
                        .setCollectionId("customers").build())
                .setWhere(StructuredQuery.Filter.newBuilder()
                        .setFieldFilter(StructuredQuery.FieldFilter.newBuilder()
                                .setField(StructuredQuery.FieldReference.newBuilder()
                                        .setFieldPath(fieldPath))
                                .setOp(operator)
                                .setValue(value)))
                .build();
        return service.runQuery(DB + "/documents", query);
    }

    private Write nestedValueDocument(String id, String field, Value value) {
        Value billing = Value.newBuilder()
                .setMapValue(MapValue.newBuilder().putFields(field, value))
                .build();
        return Write.newBuilder()
                .setUpdate(Document.newBuilder()
                        .setName(DB + "/documents/customers/" + id)
                        .putFields("billing", billing)
                        .build())
                .build();
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
    void collectionGroupQueryMatchesSubcollectionsAtAnyDepth() {
        writeCollectionGroupFixture();

        List<String> names = service.runQuery(DB + "/documents", collectionGroupQuery("sub").build())
                .stream().map(StoredDocument::getName).sorted().toList();

        assertEquals(List.of(
                DB + "/documents/p/1/sub/s1",
                DB + "/documents/p/1/sub/s1/deeper/d1/sub/s3",
                DB + "/documents/p/2/sub/s2",
                DB + "/documents/sub/top"), names);
    }

    @Test
    void collectionGroupQueryIsScopedToParentDocument() {
        writeCollectionGroupFixture();

        List<String> names = service.runQuery(DB + "/documents/p/1", collectionGroupQuery("sub").build())
                .stream().map(StoredDocument::getName).sorted().toList();

        assertEquals(List.of(
                DB + "/documents/p/1/sub/s1",
                DB + "/documents/p/1/sub/s1/deeper/d1/sub/s3"), names);
    }

    @Test
    void collectionGroupQueryAppliesWhereFilter() {
        writeCollectionGroupFixture();

        StructuredQuery query = collectionGroupQuery("sub")
                .setWhere(StructuredQuery.Filter.newBuilder()
                        .setFieldFilter(StructuredQuery.FieldFilter.newBuilder()
                                .setField(StructuredQuery.FieldReference.newBuilder().setFieldPath("k"))
                                .setOp(StructuredQuery.FieldFilter.Operator.EQUAL)
                                .setValue(Value.newBuilder().setStringValue("match"))))
                .build();

        List<String> names = service.runQuery(DB + "/documents", query)
                .stream().map(StoredDocument::getName).sorted().toList();

        assertEquals(List.of(
                DB + "/documents/p/1/sub/s1/deeper/d1/sub/s3",
                DB + "/documents/p/2/sub/s2"), names);
    }

    @Test
    void collectionQueryWithoutAllDescendantsReturnsOnlyImmediateChildren() {
        writeCollectionGroupFixture();

        StructuredQuery query = StructuredQuery.newBuilder()
                .addFrom(StructuredQuery.CollectionSelector.newBuilder().setCollectionId("sub"))
                .build();

        List<String> names = service.runQuery(DB + "/documents/p/1", query)
                .stream().map(StoredDocument::getName).toList();

        assertEquals(List.of(DB + "/documents/p/1/sub/s1"), names);
    }

    private StructuredQuery.Builder collectionGroupQuery(String collectionId) {
        return StructuredQuery.newBuilder()
                .addFrom(StructuredQuery.CollectionSelector.newBuilder()
                        .setCollectionId(collectionId)
                        .setAllDescendants(true));
    }

    private void writeCollectionGroupFixture() {
        service.applyWrite(upsert(DB + "/documents/p/1", "k", "match"), Instant.now());
        service.applyWrite(upsert(DB + "/documents/p/1/sub/s1", "k", "other"), Instant.now());
        service.applyWrite(upsert(DB + "/documents/p/1/sub/s1/deeper/d1", "k", "match"), Instant.now());
        service.applyWrite(upsert(DB + "/documents/p/1/sub/s1/deeper/d1/sub/s3", "k", "match"), Instant.now());
        service.applyWrite(upsert(DB + "/documents/p/2/sub/s2", "k", "match"), Instant.now());
        service.applyWrite(upsert(DB + "/documents/p/2/subway/w1", "k", "match"), Instant.now());
        service.applyWrite(upsert(DB + "/documents/p/2/other/o1", "k", "match"), Instant.now());
        service.applyWrite(upsert(DB + "/documents/sub/top", "k", "other"), Instant.now());
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

    @Test
    void updateMaskWithDottedPathUpdatesOnlyThatNestedField() {
        service.applyWrite(Write.newBuilder().setUpdate(profileDocument()).build(), Instant.now());

        service.applyWrite(Write.newBuilder()
                .setUpdate(Document.newBuilder().setName(DOC_NAME)
                        .putFields("m", mapOf("x", intValue(9))))
                .setUpdateMask(DocumentMask.newBuilder().addFieldPaths("m.x"))
                .build(), Instant.now());

        StoredDocument stored = service.getDocument(DOC_NAME).orElseThrow();
        assertEquals(9L, stored.getFields().get("m").getMapValue().get("x").getIntegerValue());
        assertEquals(2L, stored.getFields().get("m").getMapValue().get("y").getIntegerValue());
        assertEquals("k", stored.getFields().get("keep").getStringValue());
    }

    @Test
    void updateMaskMergesNewNestedFieldAlongsideSiblings() {
        service.applyWrite(Write.newBuilder().setUpdate(profileDocument()).build(), Instant.now());

        service.applyWrite(Write.newBuilder()
                .setUpdate(Document.newBuilder().setName(DOC_NAME)
                        .putFields("m", mapOf("z", intValue(3))))
                .setUpdateMask(DocumentMask.newBuilder().addFieldPaths("m.z"))
                .build(), Instant.now());

        assertEquals(Set.of("x", "y", "z"), service.getDocument(DOC_NAME).orElseThrow()
                .getFields().get("m").getMapValue().keySet());
    }

    @Test
    void updateMaskDeletesNestedFieldAbsentFromInput() {
        service.applyWrite(Write.newBuilder().setUpdate(profileDocument()).build(), Instant.now());

        service.applyWrite(Write.newBuilder()
                .setUpdate(Document.newBuilder().setName(DOC_NAME))
                .setUpdateMask(DocumentMask.newBuilder().addFieldPaths("m.x"))
                .build(), Instant.now());

        assertEquals(Set.of("y"), service.getDocument(DOC_NAME).orElseThrow()
                .getFields().get("m").getMapValue().keySet());
    }

    @Test
    void updateMaskWithQuotedSegmentAddressesLiteralKey() {
        service.applyWrite(Write.newBuilder().setUpdate(profileDocument()).build(), Instant.now());

        service.applyWrite(Write.newBuilder()
                .setUpdate(Document.newBuilder().setName(DOC_NAME)
                        .putFields("m", mapOf("a.b", intValue(5))))
                .setUpdateMask(DocumentMask.newBuilder().addFieldPaths("m.`a.b`"))
                .build(), Instant.now());

        assertEquals(5L, service.getDocument(DOC_NAME).orElseThrow()
                .getFields().get("m").getMapValue().get("a.b").getIntegerValue());
    }

    @Test
    void emptyUpdateMaskWithTransformKeepsExistingFields() {
        service.applyWrite(Write.newBuilder().setUpdate(profileDocument()).build(), Instant.now());

        service.applyWrite(Write.newBuilder()
                .setUpdate(Document.newBuilder().setName(DOC_NAME))
                .setUpdateMask(DocumentMask.getDefaultInstance())
                .addUpdateTransforms(DocumentTransform.FieldTransform.newBuilder()
                        .setFieldPath("a").setIncrement(intValue(5)))
                .build(), Instant.now());

        StoredDocument stored = service.getDocument(DOC_NAME).orElseThrow();
        assertEquals(6L, stored.getFields().get("a").getIntegerValue());
        assertEquals("k", stored.getFields().get("keep").getStringValue());
        assertEquals(Set.of("x", "y"), stored.getFields().get("m").getMapValue().keySet());
    }

    @Test
    void transformAtDottedPathIncrementsNestedField() {
        service.applyWrite(Write.newBuilder().setUpdate(profileDocument()).build(), Instant.now());

        service.applyWrite(Write.newBuilder()
                .setUpdate(Document.newBuilder().setName(DOC_NAME))
                .setUpdateMask(DocumentMask.getDefaultInstance())
                .addUpdateTransforms(DocumentTransform.FieldTransform.newBuilder()
                        .setFieldPath("m.x").setIncrement(intValue(4)))
                .build(), Instant.now());

        StoredDocument stored = service.getDocument(DOC_NAME).orElseThrow();
        assertEquals(5L, stored.getFields().get("m").getMapValue().get("x").getIntegerValue());
        assertEquals(2L, stored.getFields().get("m").getMapValue().get("y").getIntegerValue());
    }

    @Test
    void splitFieldPathUnquotesBacktickSegments() {
        assertEquals(List.of("a", "b.c", "d`e"), FirestoreService.splitFieldPath("a.`b.c`.`d\\`e`"));
    }

    @Test
    void transformsAtDottedPathAppendAndRemoveNestedArrayElements() {
        service.applyWrite(Write.newBuilder().setUpdate(profileDocument()).build(), Instant.now());
        Value tags = Value.newBuilder().setArrayValue(ArrayValue.newBuilder()
                .addValues(intValue(1)).addValues(intValue(2))).build();

        service.applyWrite(Write.newBuilder()
                .setUpdate(Document.newBuilder().setName(DOC_NAME))
                .setUpdateMask(DocumentMask.getDefaultInstance())
                .addUpdateTransforms(DocumentTransform.FieldTransform.newBuilder()
                        .setFieldPath("m.tags").setAppendMissingElements(tags.getArrayValue()))
                .addUpdateTransforms(DocumentTransform.FieldTransform.newBuilder()
                        .setFieldPath("m.tags").setRemoveAllFromArray(ArrayValue.newBuilder()
                                .addValues(intValue(1))))
                .build(), Instant.now());

        StoredDocument stored = service.getDocument(DOC_NAME).orElseThrow();
        List<StoredValue> remaining = stored.getFields().get("m").getMapValue().get("tags").getArrayValue();
        assertEquals(List.of(2L), remaining.stream().map(StoredValue::getIntegerValue).toList());
        assertEquals(1L, stored.getFields().get("m").getMapValue().get("x").getIntegerValue());
    }

    @Test
    void queryOnQuotedFieldPathFindsValueWrittenThroughQuotedMask() {
        String name = DB + "/documents/customers/c1";
        service.applyWrite(Write.newBuilder()
                .setUpdate(Document.newBuilder().setName(name)
                        .putFields("m", mapOf("a.b", intValue(5))))
                .setUpdateMask(DocumentMask.newBuilder().addFieldPaths("m.`a.b`"))
                .build(), Instant.now());

        assertEquals(1, runNestedFilter("m.`a.b`", StructuredQuery.FieldFilter.Operator.EQUAL,
                intValue(5)).size());
    }

    @Test
    void malformedFieldPathsAreRejected() {
        for (String path : List.of("m..x", ".m", "m.", "m.`x", "m.`x\\", "m.``")) {
            GcpException ex = assertThrows(GcpException.class,
                    () -> FirestoreService.splitFieldPath(path), path);
            assertEquals(Status.Code.INVALID_ARGUMENT, ex.getGrpcCode(), path);
        }
    }

    @Test
    void fieldPathsDeeperThanTwentySegmentsAreRejected() {
        assertEquals(20, FirestoreService.splitFieldPath(String.join(".", "a".repeat(20).split(""))).size());

        String tooDeep = String.join(".", "a".repeat(10_000).split(""));
        GcpException ex = assertThrows(GcpException.class,
                () -> service.applyWrite(Write.newBuilder()
                        .setUpdate(Document.newBuilder().setName(DOC_NAME))
                        .setUpdateMask(DocumentMask.newBuilder().addFieldPaths(tooDeep))
                        .build(), Instant.now()));
        assertEquals(Status.Code.INVALID_ARGUMENT, ex.getGrpcCode());
    }

    @Test
    void commitWithMalformedFieldPathAppliesNoWrites() {
        String other = DB + "/documents/users/bob";
        Write malformed = Write.newBuilder()
                .setUpdate(Document.newBuilder().setName(DOC_NAME))
                .setUpdateMask(DocumentMask.newBuilder().addFieldPaths("m..x"))
                .build();

        assertThrows(GcpException.class, () -> service.commit(
                List.of(upsert(other, "b", "1"), malformed), new byte[0], Instant.now()));
        assertTrue(service.getDocument(other).isEmpty());
        assertTrue(service.getDocument(DOC_NAME).isEmpty());
    }

    private static Document profileDocument() {
        return Document.newBuilder()
                .setName(DOC_NAME)
                .putFields("a", intValue(1))
                .putFields("keep", Value.newBuilder().setStringValue("k").build())
                .putFields("m", mapOf("x", intValue(1), "y", intValue(2)))
                .build();
    }

    private static Value intValue(long value) {
        return Value.newBuilder().setIntegerValue(value).build();
    }

    private static Value mapOf(Object... keysAndValues) {
        MapValue.Builder map = MapValue.newBuilder();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.putFields((String) keysAndValues[i], (Value) keysAndValues[i + 1]);
        }
        return Value.newBuilder().setMapValue(map).build();
    }
}
