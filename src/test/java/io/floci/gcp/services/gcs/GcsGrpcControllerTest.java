package io.floci.gcp.services.gcs;

import com.google.protobuf.ByteString;
import com.google.protobuf.FieldMask;
import com.google.protobuf.Timestamp;
import com.google.storage.v2.BidiWriteObjectRequest;
import com.google.storage.v2.BidiWriteObjectResponse;
import com.google.storage.v2.Bucket;
import com.google.storage.v2.ChecksummedData;
import com.google.storage.v2.ComposeObjectRequest;
import com.google.storage.v2.CreateBucketRequest;
import com.google.storage.v2.GetBucketRequest;
import com.google.storage.v2.GetObjectRequest;
import com.google.storage.v2.ListBucketsRequest;
import com.google.storage.v2.ListBucketsResponse;
import com.google.storage.v2.ListObjectsRequest;
import com.google.storage.v2.ListObjectsResponse;
import com.google.storage.v2.QueryWriteStatusRequest;
import com.google.storage.v2.QueryWriteStatusResponse;
import com.google.storage.v2.ReadObjectRequest;
import com.google.storage.v2.ReadObjectResponse;
import com.google.storage.v2.StartResumableWriteRequest;
import com.google.storage.v2.StartResumableWriteResponse;
import com.google.storage.v2.UpdateBucketRequest;
import com.google.storage.v2.UpdateObjectRequest;
import com.google.storage.v2.WriteObjectRequest;
import com.google.storage.v2.WriteObjectResponse;
import com.google.storage.v2.WriteObjectSpec;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GcsGrpcControllerTest {

    private static final String BASE_URL = "http://localhost:4588";

    private GcsService service;
    private GcsGrpcController controller;

    @BeforeEach
    void setUp() {
        service = new GcsService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), "test-project");
        controller = new GcsGrpcController(service, BASE_URL);
    }

    @Test
    void bucketCrudUsesCanonicalResourceNames() {
        RecordingObserver<Bucket> created = new RecordingObserver<>();
        controller.createBucket(CreateBucketRequest.newBuilder()
                .setParent("projects/_")
                .setBucketId("grpc-bucket")
                .setBucket(Bucket.newBuilder().setProject("projects/test-project")
                        .setLocation("EU").putLabels("protocol", "grpc"))
                .build(), created);

        assertNull(created.error);
        assertEquals("projects/_/buckets/grpc-bucket", created.single().getName());
        assertEquals("EU", created.single().getLocation());
        assertEquals(Map.of("protocol", "grpc"), created.single().getLabelsMap());
        assertEquals("grpc-bucket", service.getBucket("grpc-bucket").getName());

        RecordingObserver<Bucket> updated = new RecordingObserver<>();
        controller.updateBucket(UpdateBucketRequest.newBuilder()
                .setBucket(created.single().toBuilder().clearLabels().putLabels("updated", "true"))
                .setUpdateMask(FieldMask.newBuilder().addPaths("labels.updated"))
                .build(), updated);
        assertNull(updated.error);
        assertEquals("true", updated.single().getLabelsOrThrow("updated"));
    }

    @Test
    void everyBucketResponseCarriesBucketId() {
        RecordingObserver<Bucket> created = new RecordingObserver<>();
        controller.createBucket(CreateBucketRequest.newBuilder()
                .setParent("projects/_")
                .setBucketId("bucket-id-bucket")
                .setBucket(Bucket.newBuilder().setProject("projects/test-project"))
                .build(), created);
        assertNull(created.error);
        assertEquals("bucket-id-bucket", created.single().getBucketId());

        RecordingObserver<Bucket> fetched = new RecordingObserver<>();
        controller.getBucket(GetBucketRequest.newBuilder()
                .setName("projects/_/buckets/bucket-id-bucket")
                .build(), fetched);
        assertNull(fetched.error);
        assertEquals("bucket-id-bucket", fetched.single().getBucketId());

        RecordingObserver<ListBucketsResponse> listed = new RecordingObserver<>();
        controller.listBuckets(ListBucketsRequest.newBuilder()
                .setParent("projects/test-project")
                .build(), listed);
        assertNull(listed.error);
        assertEquals(List.of("bucket-id-bucket"),
                listed.single().getBucketsList().stream().map(Bucket::getBucketId).toList());

        RecordingObserver<Bucket> updated = new RecordingObserver<>();
        controller.updateBucket(UpdateBucketRequest.newBuilder()
                .setBucket(created.single().toBuilder().putLabels("updated", "true"))
                .setUpdateMask(FieldMask.newBuilder().addPaths("labels.updated"))
                .build(), updated);
        assertNull(updated.error);
        assertEquals("bucket-id-bucket", updated.single().getBucketId());
    }

    @Test
    void classicWriteAndRangeReadShareServiceState() {
        createBucket("classic-bucket");
        byte[] payload = "hello grpc storage".getBytes(StandardCharsets.UTF_8);
        com.google.storage.v2.Object object = object("classic-bucket", "path/object.txt");

        RecordingObserver<WriteObjectResponse> writeResponse = new RecordingObserver<>();
        StreamObserver<WriteObjectRequest> requestObserver = controller.writeObject(writeResponse);
        requestObserver.onNext(WriteObjectRequest.newBuilder()
                .setWriteObjectSpec(WriteObjectSpec.newBuilder()
                        .setResource(object).setObjectSize(payload.length))
                .setWriteOffset(0)
                .setChecksummedData(data(payload))
                .setFinishWrite(true)
                .build());
        requestObserver.onCompleted();

        assertNull(writeResponse.error);
        assertEquals(payload.length, writeResponse.single().getResource().getSize());
        assertArrayEquals(payload, service.getObjectData("classic-bucket", "path/object.txt"));

        RecordingObserver<ReadObjectResponse> readResponse = new RecordingObserver<>();
        controller.readObject(ReadObjectRequest.newBuilder()
                .setBucket("projects/_/buckets/classic-bucket")
                .setObject("path/object.txt")
                .setReadOffset(6)
                .setReadLimit(4)
                .build(), readResponse);

        assertNull(readResponse.error);
        assertEquals(6, readResponse.values.getFirst().getContentRange().getStart());
        assertArrayEquals("grpc".getBytes(StandardCharsets.UTF_8), concatenate(readResponse.values));
    }

    @Test
    void resumableWriteAcceptsRetryOverlapAndRetainsFinalStatus() {
        createBucket("resumable-bucket");
        byte[] first = "hello ".getBytes(StandardCharsets.UTF_8);
        byte[] overlap = "lo world".getBytes(StandardCharsets.UTF_8);

        RecordingObserver<StartResumableWriteResponse> started = new RecordingObserver<>();
        controller.startResumableWrite(StartResumableWriteRequest.newBuilder()
                .setWriteObjectSpec(WriteObjectSpec.newBuilder()
                        .setResource(object("resumable-bucket", "object"))
                        .setObjectSize(11))
                .build(), started);
        String uploadId = started.single().getUploadId();

        RecordingObserver<WriteObjectResponse> firstResponse = new RecordingObserver<>();
        StreamObserver<WriteObjectRequest> firstStream = controller.writeObject(firstResponse);
        firstStream.onNext(WriteObjectRequest.newBuilder().setUploadId(uploadId)
                .setWriteOffset(0).setChecksummedData(data(first)).build());
        firstStream.onCompleted();
        assertEquals(6, firstResponse.single().getPersistedSize());

        RecordingObserver<WriteObjectResponse> finalResponse = new RecordingObserver<>();
        StreamObserver<WriteObjectRequest> finalStream = controller.writeObject(finalResponse);
        finalStream.onNext(WriteObjectRequest.newBuilder().setUploadId(uploadId)
                .setWriteOffset(3).setChecksummedData(data(overlap)).setFinishWrite(true).build());

        assertNull(finalResponse.error);
        assertEquals(11, finalResponse.single().getResource().getSize());
        assertArrayEquals("hello world".getBytes(StandardCharsets.UTF_8),
                service.getObjectData("resumable-bucket", "object"));

        RecordingObserver<QueryWriteStatusResponse> status = new RecordingObserver<>();
        controller.queryWriteStatus(QueryWriteStatusRequest.newBuilder().setUploadId(uploadId).build(), status);
        assertEquals("object", status.single().getResource().getName());
    }

    @Test
    void bidiWriteSupportsStateLookupAndFinalization() {
        createBucket("bidi-bucket");
        byte[] payload = "bidi payload".getBytes(StandardCharsets.UTF_8);
        RecordingObserver<BidiWriteObjectResponse> responses = new RecordingObserver<>();
        StreamObserver<BidiWriteObjectRequest> stream = controller.bidiWriteObject(responses);

        stream.onNext(BidiWriteObjectRequest.newBuilder()
                .setWriteObjectSpec(WriteObjectSpec.newBuilder()
                        .setResource(object("bidi-bucket", "bidi-object")))
                .setWriteOffset(0)
                .setChecksummedData(data("bidi ".getBytes(StandardCharsets.UTF_8)))
                .setStateLookup(true)
                .build());
        stream.onNext(BidiWriteObjectRequest.newBuilder()
                .setWriteOffset(5)
                .setChecksummedData(data("payload".getBytes(StandardCharsets.UTF_8)))
                .setFinishWrite(true)
                .build());

        assertNull(responses.error);
        assertEquals(5, responses.values.getFirst().getPersistedSize());
        assertEquals("bidi-object", responses.values.getLast().getResource().getName());
        assertArrayEquals(payload, service.getObjectData("bidi-bucket", "bidi-object"));
    }

    @Test
    void listObjectsPaginatesObjectsAndPrefixesTogether() {
        createBucket("list-bucket");
        service.putObject("list-bucket", "a.txt", "text/plain", new byte[] {1}, BASE_URL);
        service.putObject("list-bucket", "dir/a.txt", "text/plain", new byte[] {2}, BASE_URL);
        service.putObject("list-bucket", "z.txt", "text/plain", new byte[] {3}, BASE_URL);

        RecordingObserver<ListObjectsResponse> first = new RecordingObserver<>();
        controller.listObjects(ListObjectsRequest.newBuilder()
                .setParent("projects/_/buckets/list-bucket")
                .setDelimiter("/").setPageSize(2).build(), first);

        assertEquals(List.of("a.txt"), first.single().getObjectsList().stream()
                .map(com.google.storage.v2.Object::getName).toList());
        assertEquals(List.of("dir/"), first.single().getPrefixesList());
        assertTrue(!first.single().getNextPageToken().isBlank());

        RecordingObserver<ListObjectsResponse> second = new RecordingObserver<>();
        controller.listObjects(ListObjectsRequest.newBuilder()
                .setParent("projects/_/buckets/list-bucket")
                .setDelimiter("/").setPageSize(2)
                .setPageToken(first.single().getNextPageToken()).build(), second);
        assertEquals("z.txt", second.single().getObjects(0).getName());
    }

    @Test
    void invalidChunkChecksumReturnsDataLoss() {
        createBucket("checksum-bucket");
        RecordingObserver<WriteObjectResponse> response = new RecordingObserver<>();
        StreamObserver<WriteObjectRequest> stream = controller.writeObject(response);
        stream.onNext(WriteObjectRequest.newBuilder()
                .setWriteObjectSpec(WriteObjectSpec.newBuilder()
                        .setResource(object("checksum-bucket", "bad")))
                .setChecksummedData(ChecksummedData.newBuilder()
                        .setContent(ByteString.copyFromUtf8("bad")).setCrc32C(1))
                .setFinishWrite(true).build());

        assertEquals(Status.Code.DATA_LOSS, Status.fromThrowable(response.error).getCode());
        assertEquals(0, service.streamingUploadCount());
    }

    @Test
    void abortedNonResumableWriteReleasesStreamingUpload() {
        createBucket("aborted-write-bucket");
        RecordingObserver<WriteObjectResponse> response = new RecordingObserver<>();
        StreamObserver<WriteObjectRequest> stream = controller.writeObject(response);

        stream.onNext(WriteObjectRequest.newBuilder()
                .setWriteObjectSpec(WriteObjectSpec.newBuilder()
                        .setResource(object("aborted-write-bucket", "object")))
                .setChecksummedData(data("partial".getBytes(StandardCharsets.UTF_8)))
                .build());
        assertEquals(1, service.streamingUploadCount());

        stream.onError(Status.CANCELLED.asRuntimeException());

        assertEquals(0, service.streamingUploadCount());
    }

    @Test
    void failedResumableFinalizationReleasesStreamingUpload() {
        createBucket("failed-resume-bucket");
        RecordingObserver<StartResumableWriteResponse> started = new RecordingObserver<>();
        controller.startResumableWrite(StartResumableWriteRequest.newBuilder()
                .setWriteObjectSpec(WriteObjectSpec.newBuilder()
                        .setResource(object("failed-resume-bucket", "object"))
                        .setObjectSize(1))
                .build(), started);
        String uploadId = started.single().getUploadId();

        RecordingObserver<WriteObjectResponse> response = new RecordingObserver<>();
        controller.writeObject(response).onNext(WriteObjectRequest.newBuilder()
                .setUploadId(uploadId)
                .setChecksummedData(data("too large".getBytes(StandardCharsets.UTF_8)))
                .setFinishWrite(true)
                .build());

        assertEquals(Status.Code.OUT_OF_RANGE, Status.fromThrowable(response.error).getCode());
        assertEquals(0, service.streamingUploadCount());
    }

    @Test
    void composeHonorsExplicitSourceGeneration() {
        service.createBucket("versioned-bucket", "test-project", BASE_URL,
                Map.of("versioning", Map.of("enabled", true)));
        long firstGeneration = Long.parseLong(service.putObject("versioned-bucket", "source", "text/plain",
                "first".getBytes(StandardCharsets.UTF_8), BASE_URL).getGeneration());
        service.putObject("versioned-bucket", "source", "text/plain",
                "second".getBytes(StandardCharsets.UTF_8), BASE_URL);

        RecordingObserver<com.google.storage.v2.Object> response = new RecordingObserver<>();
        controller.composeObject(ComposeObjectRequest.newBuilder()
                .setDestination(object("versioned-bucket", "composed"))
                .addSourceObjects(ComposeObjectRequest.SourceObject.newBuilder()
                        .setName("source").setGeneration(firstGeneration))
                .build(), response);

        assertNull(response.error);
        assertArrayEquals("first".getBytes(StandardCharsets.UTF_8),
                service.getObjectData("versioned-bucket", "composed"));
    }

    @Test
    void objectUpdateAcceptsSdkMetadataKeyMask() {
        createBucket("metadata-bucket");
        service.putObject("metadata-bucket", "object", "text/plain",
                new byte[] {1}, BASE_URL);
        RecordingObserver<com.google.storage.v2.Object> response = new RecordingObserver<>();

        controller.updateObject(UpdateObjectRequest.newBuilder()
                .setObject(object("metadata-bucket", "object").toBuilder()
                        .putMetadata("updated", "true"))
                .setUpdateMask(FieldMask.newBuilder().addPaths("metadata.updated"))
                .build(), response);

        assertNull(response.error);
        assertEquals("true", response.single().getMetadataOrThrow("updated"));
    }

    /**
     * cacheControl and customTime are part of the system metadata a REST write already
     * accepts. A gRPC-only client reads object metadata through GetObject and ListObjects,
     * so both have to carry them rather than reporting an empty field.
     */
    @Test
    void writeCarriesCacheControlAndCustomTimeThroughGetAndList() {
        createBucket("grpc-system-metadata-bucket");
        byte[] payload = "cached".getBytes(StandardCharsets.UTF_8);
        Timestamp customTime = timestamp("2026-01-15T10:30:00Z");

        RecordingObserver<WriteObjectResponse> written = new RecordingObserver<>();
        StreamObserver<WriteObjectRequest> stream = controller.writeObject(written);
        stream.onNext(WriteObjectRequest.newBuilder()
                .setWriteObjectSpec(WriteObjectSpec.newBuilder()
                        .setResource(object("grpc-system-metadata-bucket", "cached.txt").toBuilder()
                                .setCacheControl("public, max-age=3600")
                                .setCustomTime(customTime))
                        .setObjectSize(payload.length))
                .setWriteOffset(0)
                .setChecksummedData(data(payload))
                .setFinishWrite(true)
                .build());
        stream.onCompleted();

        assertNull(written.error);
        assertEquals("public, max-age=3600", written.single().getResource().getCacheControl());
        assertEquals(customTime, written.single().getResource().getCustomTime());

        RecordingObserver<com.google.storage.v2.Object> fetched = new RecordingObserver<>();
        controller.getObject(GetObjectRequest.newBuilder()
                .setBucket("projects/_/buckets/grpc-system-metadata-bucket")
                .setObject("cached.txt")
                .build(), fetched);
        assertNull(fetched.error);
        assertEquals("public, max-age=3600", fetched.single().getCacheControl());
        assertEquals(customTime, fetched.single().getCustomTime());

        RecordingObserver<ListObjectsResponse> listed = new RecordingObserver<>();
        controller.listObjects(ListObjectsRequest.newBuilder()
                .setParent("projects/_/buckets/grpc-system-metadata-bucket").build(), listed);
        assertNull(listed.error);
        assertEquals("public, max-age=3600", listed.single().getObjects(0).getCacheControl());
    }

    @Test
    void objectUpdateSetsCacheControlAndCustomTime() {
        createBucket("grpc-patch-metadata-bucket");
        service.putObject("grpc-patch-metadata-bucket", "object", "text/plain",
                new byte[] {1}, BASE_URL);
        Timestamp customTime = timestamp("2026-06-20T08:00:00Z");

        RecordingObserver<com.google.storage.v2.Object> response = new RecordingObserver<>();
        controller.updateObject(UpdateObjectRequest.newBuilder()
                .setObject(object("grpc-patch-metadata-bucket", "object").toBuilder()
                        .setCacheControl("no-store")
                        .setCustomTime(customTime))
                .setUpdateMask(FieldMask.newBuilder().addPaths("cache_control").addPaths("custom_time"))
                .build(), response);

        assertNull(response.error);
        assertEquals("no-store", response.single().getCacheControl());
        assertEquals(customTime, response.single().getCustomTime());
        assertEquals("no-store",
                service.getObjectMeta("grpc-patch-metadata-bucket", "object").getCacheControl());
    }

    /**
     * GCS never removes a custom time. Naming custom_time in the mask without a value, or
     * sending a "*" mask with the field unset, leaves the existing value in place.
     */
    @Test
    void objectUpdateWithAnUnsetCustomTimeKeepsTheValue() {
        createBucket("grpc-keep-custom-time-bucket");
        service.putObject("grpc-keep-custom-time-bucket", "object", "text/plain",
                new byte[] {1}, BASE_URL);
        Timestamp customTime = timestamp("2026-06-20T08:00:00Z");
        updateObject("grpc-keep-custom-time-bucket",
                object("grpc-keep-custom-time-bucket", "object").toBuilder().setCustomTime(customTime),
                "custom_time");

        RecordingObserver<com.google.storage.v2.Object> masked = updateObject("grpc-keep-custom-time-bucket",
                object("grpc-keep-custom-time-bucket", "object").toBuilder(), "custom_time");
        assertNull(masked.error);
        assertEquals(customTime, masked.single().getCustomTime());

        RecordingObserver<com.google.storage.v2.Object> star = updateObject("grpc-keep-custom-time-bucket",
                object("grpc-keep-custom-time-bucket", "object").toBuilder().setCacheControl("max-age=1"), "*");
        assertNull(star.error);
        assertEquals("max-age=1", star.single().getCacheControl());
        assertEquals(customTime, star.single().getCustomTime());
    }

    @Test
    void objectUpdateRejectsADecreasedCustomTime() {
        createBucket("grpc-decrease-custom-time-bucket");
        service.putObject("grpc-decrease-custom-time-bucket", "object", "text/plain",
                new byte[] {1}, BASE_URL);
        updateObject("grpc-decrease-custom-time-bucket",
                object("grpc-decrease-custom-time-bucket", "object").toBuilder()
                        .setCustomTime(timestamp("2027-01-01T00:00:00Z")),
                "custom_time");

        RecordingObserver<com.google.storage.v2.Object> response = updateObject("grpc-decrease-custom-time-bucket",
                object("grpc-decrease-custom-time-bucket", "object").toBuilder()
                        .setCustomTime(timestamp("2025-01-01T00:00:00Z")),
                "custom_time");

        Status status = Status.fromThrowable(response.error);
        assertEquals(Status.Code.INVALID_ARGUMENT, status.getCode());
        assertEquals("""
                Custom time cannot be decreased. Previously: 2027-01-01T00:00:00+00:00. \
                Attempting to set: 2025-01-01T00:00:00+00:00.""", status.getDescription());
    }

    /**
     * GCS keeps custom_time as int64 nanoseconds. A seconds value it cannot multiply is rejected
     * as "too large" (INVALID_ARGUMENT on write, INTERNAL on update), a Timestamp that breaks the
     * protobuf rules is an internal error, and values inside the range saturate at the limits.
     */
    @Test
    void malformedCustomTimeIsRejectedOrSaturatedLikeGcs() {
        createBucket("grpc-custom-time-range-bucket");
        String tooLarge = "Invalid timestamp - too large to convert to nanoseconds.";
        String internal = "We encountered an internal error. Please try again.";

        Status tooLargeWrite = Status.fromThrowable(writeWithCustomTime("too-large", 9_223_372_037L, 0).error);
        assertEquals(Status.Code.INVALID_ARGUMENT, tooLargeWrite.getCode());
        assertEquals(tooLarge, tooLargeWrite.getDescription());

        Status badNanos = Status.fromThrowable(writeWithCustomTime("bad-nanos", 0, 1_000_000_000).error);
        assertEquals(Status.Code.INTERNAL, badNanos.getCode());
        assertEquals(internal, badNanos.getDescription());

        Status beforeYearOne = Status.fromThrowable(writeWithCustomTime("year-zero", -62_135_596_801L, 0).error);
        assertEquals(Status.Code.INTERNAL, beforeYearOne.getCode());
        assertEquals(internal, beforeYearOne.getDescription());

        RecordingObserver<WriteObjectResponse> saturatedHigh = writeWithCustomTime("high", 9_223_372_036L, 999_999_999);
        assertNull(saturatedHigh.error);
        assertEquals(timestamp("2262-04-11T23:47:16.854775807Z"), saturatedHigh.single().getResource().getCustomTime());

        RecordingObserver<WriteObjectResponse> saturatedLow = writeWithCustomTime("low", -62_135_596_800L, 0);
        assertNull(saturatedLow.error);
        assertEquals(timestamp("1677-09-21T00:12:43.145224192Z"), saturatedLow.single().getResource().getCustomTime());

        RecordingObserver<com.google.storage.v2.Object> tooLargeUpdate = updateObject("grpc-custom-time-range-bucket",
                object("grpc-custom-time-range-bucket", "low").toBuilder()
                        .setCustomTime(Timestamp.newBuilder().setSeconds(9_223_372_037L)),
                "custom_time");
        Status updateStatus = Status.fromThrowable(tooLargeUpdate.error);
        assertEquals(Status.Code.INTERNAL, updateStatus.getCode());
        assertEquals(tooLarge, updateStatus.getDescription());
        assertEquals("1677-09-21T00:12:43.145224192Z",
                service.getObjectMeta("grpc-custom-time-range-bucket", "low").getCustomTime());
    }

    private RecordingObserver<WriteObjectResponse> writeWithCustomTime(String name, long seconds, int nanos) {
        byte[] payload = {1};
        RecordingObserver<WriteObjectResponse> written = new RecordingObserver<>();
        StreamObserver<WriteObjectRequest> stream = controller.writeObject(written);
        stream.onNext(WriteObjectRequest.newBuilder()
                .setWriteObjectSpec(WriteObjectSpec.newBuilder()
                        .setResource(object("grpc-custom-time-range-bucket", name).toBuilder()
                                .setCustomTime(Timestamp.newBuilder().setSeconds(seconds).setNanos(nanos)))
                        .setObjectSize(payload.length))
                .setWriteOffset(0)
                .setChecksummedData(data(payload))
                .setFinishWrite(true)
                .build());
        stream.onCompleted();
        return written;
    }

    /** An empty cache_control under the mask unsets the field, so REST omits it afterwards. */
    @Test
    void objectUpdateWithAnEmptyCacheControlUnsetsIt() {
        createBucket("grpc-clear-cache-control-bucket");
        service.putObject("grpc-clear-cache-control-bucket", "object", "text/plain",
                new byte[] {1}, BASE_URL);
        updateObject("grpc-clear-cache-control-bucket",
                object("grpc-clear-cache-control-bucket", "object").toBuilder().setCacheControl("no-store"),
                "cache_control");

        RecordingObserver<com.google.storage.v2.Object> response = updateObject("grpc-clear-cache-control-bucket",
                object("grpc-clear-cache-control-bucket", "object").toBuilder(), "cache_control");

        assertNull(response.error);
        assertEquals("", response.single().getCacheControl());
        assertNull(service.getObjectMeta("grpc-clear-cache-control-bucket", "object").getCacheControl());
    }

    private RecordingObserver<com.google.storage.v2.Object> updateObject(String bucket,
            com.google.storage.v2.Object.Builder object, String... mask) {
        RecordingObserver<com.google.storage.v2.Object> response = new RecordingObserver<>();
        controller.updateObject(UpdateObjectRequest.newBuilder()
                .setObject(object)
                .setUpdateMask(FieldMask.newBuilder().addAllPaths(List.of(mask)))
                .build(), response);
        return response;
    }

    private void createBucket(String name) {
        service.createBucket(name, "test-project", BASE_URL, Map.of());
    }

    private static com.google.storage.v2.Object object(String bucket, String name) {
        return com.google.storage.v2.Object.newBuilder()
                .setBucket("projects/_/buckets/" + bucket)
                .setName(name)
                .setContentType("text/plain")
                .build();
    }

    private static Timestamp timestamp(String iso) {
        Instant instant = Instant.parse(iso);
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    private static ChecksummedData data(byte[] bytes) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bytes.length);
        return ChecksummedData.newBuilder().setContent(ByteString.copyFrom(bytes))
                .setCrc32C((int) crc.getValue()).build();
    }

    private static byte[] concatenate(List<ReadObjectResponse> responses) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        responses.forEach(response -> output.writeBytes(response.getChecksummedData().getContent().toByteArray()));
        return output.toByteArray();
    }

    private static final class RecordingObserver<T> implements StreamObserver<T> {
        private final List<T> values = new ArrayList<>();
        private Throwable error;

        @Override
        public void onNext(T value) {
            values.add(value);
        }

        @Override
        public void onError(Throwable t) {
            error = t;
        }

        @Override
        public void onCompleted() {}

        T single() {
            assertEquals(1, values.size());
            return values.getFirst();
        }
    }
}
