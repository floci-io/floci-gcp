package io.floci.gcp.services.gcs;

import com.google.protobuf.ByteString;
import com.google.protobuf.FieldMask;
import com.google.storage.v2.BidiWriteObjectRequest;
import com.google.storage.v2.BidiWriteObjectResponse;
import com.google.storage.v2.Bucket;
import com.google.storage.v2.ChecksummedData;
import com.google.storage.v2.ComposeObjectRequest;
import com.google.storage.v2.CreateBucketRequest;
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
