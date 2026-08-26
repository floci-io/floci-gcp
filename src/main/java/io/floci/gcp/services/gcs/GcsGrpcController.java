package io.floci.gcp.services.gcs;

import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.google.storage.v2.BidiWriteObjectRequest;
import com.google.storage.v2.BidiWriteObjectResponse;
import com.google.storage.v2.Bucket;
import com.google.storage.v2.ChecksummedData;
import com.google.storage.v2.ComposeObjectRequest;
import com.google.storage.v2.ContentRange;
import com.google.storage.v2.CreateBucketRequest;
import com.google.storage.v2.DeleteBucketRequest;
import com.google.storage.v2.DeleteObjectRequest;
import com.google.storage.v2.GetBucketRequest;
import com.google.storage.v2.GetObjectRequest;
import com.google.storage.v2.ListBucketsRequest;
import com.google.storage.v2.ListBucketsResponse;
import com.google.storage.v2.ListObjectsRequest;
import com.google.storage.v2.ListObjectsResponse;
import com.google.storage.v2.ObjectChecksums;
import com.google.storage.v2.QueryWriteStatusRequest;
import com.google.storage.v2.QueryWriteStatusResponse;
import com.google.storage.v2.ReadObjectRequest;
import com.google.storage.v2.ReadObjectResponse;
import com.google.storage.v2.StartResumableWriteRequest;
import com.google.storage.v2.StartResumableWriteResponse;
import com.google.storage.v2.StorageGrpc;
import com.google.storage.v2.UpdateBucketRequest;
import com.google.storage.v2.UpdateObjectRequest;
import com.google.storage.v2.WriteObjectRequest;
import com.google.storage.v2.WriteObjectResponse;
import com.google.storage.v2.WriteObjectSpec;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.GcpGrpcController;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.services.credentials.GcsAuthorizationService;
import io.floci.gcp.services.gcs.model.GcsBucket;
import io.floci.gcp.services.gcs.model.GcsComposeSource;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;
import io.floci.gcp.services.gcs.model.GcsObjectPreconditions;
import io.floci.gcp.services.gcs.model.GcsStreamingUpload;
import io.grpc.stub.StreamObserver;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.zip.CRC32C;

public class GcsGrpcController extends StorageGrpc.StorageImplBase {

    private static final int READ_CHUNK_SIZE = 2 * 1024 * 1024;

    private final GcsService service;
    private final String baseUrl;
    private final GcsAuthorizationService authorizationService;

    GcsGrpcController(GcsService service, EmulatorConfig config,
            GcsAuthorizationService authorizationService) {
        this.service = service;
        this.baseUrl = config.baseUrl();
        this.authorizationService = authorizationService;
    }

    GcsGrpcController(GcsService service, String baseUrl) {
        this.service = service;
        this.baseUrl = baseUrl;
        this.authorizationService = null;
    }

    @Override
    public void createBucket(CreateBucketRequest request, StreamObserver<Bucket> observer) {
        unary(observer, () -> {
            rejectDownscoped();
            String project = GcsGrpcMapper.projectId(request.getBucket().getProject());
            return GcsGrpcMapper.toProto(service.createBucket(request.getBucketId(), project, baseUrl,
                    GcsGrpcMapper.bucketCreateFields(request.getBucket())));
        });
    }

    @Override
    public void getBucket(GetBucketRequest request, StreamObserver<Bucket> observer) {
        unary(observer, () -> {
            rejectDownscoped();
            GcsBucket bucket = service.getBucket(GcsGrpcMapper.bucketId(request.getName()));
            checkMetageneration(bucket.getMetageneration(),
                    request.hasIfMetagenerationMatch() ? request.getIfMetagenerationMatch() : null,
                    request.hasIfMetagenerationNotMatch() ? request.getIfMetagenerationNotMatch() : null);
            return GcsGrpcMapper.toProto(bucket);
        });
    }

    @Override
    public void listBuckets(ListBucketsRequest request, StreamObserver<ListBucketsResponse> observer) {
        unary(observer, () -> {
            rejectDownscoped();
            String project = GcsGrpcMapper.projectId(request.getParent());
            List<GcsBucket> all = service.listBuckets(project).stream()
                    .filter(bucket -> request.getPrefix().isBlank()
                            || bucket.getName().startsWith(request.getPrefix()))
                    .sorted(Comparator.comparing(GcsBucket::getName))
                    .toList();
            PageToken.Page<GcsBucket> page = PageToken.paginate(
                    all, limitedPageSize(request.getPageSize()), request.getPageToken());
            ListBucketsResponse.Builder response = ListBucketsResponse.newBuilder();
            page.items().forEach(bucket -> response.addBuckets(GcsGrpcMapper.toProto(bucket)));
            if (page.nextPageToken() != null) {
                response.setNextPageToken(page.nextPageToken());
            }
            return response.build();
        });
    }

    @Override
    public void updateBucket(UpdateBucketRequest request, StreamObserver<Bucket> observer) {
        unary(observer, () -> {
            rejectDownscoped();
            String bucketId = GcsGrpcMapper.bucketId(request.getBucket().getName());
            GcsBucket current = service.getBucket(bucketId);
            checkMetageneration(current.getMetageneration(),
                    request.hasIfMetagenerationMatch() ? request.getIfMetagenerationMatch() : null,
                    request.hasIfMetagenerationNotMatch() ? request.getIfMetagenerationNotMatch() : null);
            if (request.getUpdateMask().getPathsCount() == 0) {
                throw GcpException.invalidArgument("update_mask is required");
            }
            return GcsGrpcMapper.toProto(service.updateBucket(bucketId,
                    GcsGrpcMapper.bucketUpdateFields(
                            request.getBucket(), request.getUpdateMask().getPathsList())));
        });
    }

    @Override
    public void deleteBucket(DeleteBucketRequest request, StreamObserver<Empty> observer) {
        unary(observer, () -> {
            rejectDownscoped();
            String bucketId = GcsGrpcMapper.bucketId(request.getName());
            GcsBucket current = service.getBucket(bucketId);
            checkMetageneration(current.getMetageneration(),
                    request.hasIfMetagenerationMatch() ? request.getIfMetagenerationMatch() : null,
                    request.hasIfMetagenerationNotMatch() ? request.getIfMetagenerationNotMatch() : null);
            if (!service.listObjectVersions(bucketId, null).isEmpty()) {
                throw GcpException.failedPrecondition("Bucket is not empty: " + bucketId);
            }
            service.deleteBucket(bucketId);
            return Empty.getDefaultInstance();
        });
    }

    @Override
    public void getObject(GetObjectRequest request,
            StreamObserver<com.google.storage.v2.Object> observer) {
        unary(observer, () -> {
            String bucket = GcsGrpcMapper.bucketId(request.getBucket());
            requireRead(bucket, request.getObject());
            GcsObjectMeta meta = request.getGeneration() == 0
                    ? service.getObjectMeta(bucket, request.getObject())
                    : service.getObjectMeta(bucket, request.getObject(), Long.toString(request.getGeneration()));
            checkObjectPreconditions(meta,
                    request.hasIfGenerationMatch() ? request.getIfGenerationMatch() : null,
                    request.hasIfGenerationNotMatch() ? request.getIfGenerationNotMatch() : null,
                    request.hasIfMetagenerationMatch() ? request.getIfMetagenerationMatch() : null,
                    request.hasIfMetagenerationNotMatch() ? request.getIfMetagenerationNotMatch() : null);
            return GcsGrpcMapper.toProto(meta);
        });
    }

    @Override
    public void updateObject(UpdateObjectRequest request,
            StreamObserver<com.google.storage.v2.Object> observer) {
        unary(observer, () -> {
            com.google.storage.v2.Object input = request.getObject();
            String bucket = GcsGrpcMapper.bucketId(input.getBucket());
            requireWrite(bucket, input.getName());
            if (request.getUpdateMask().getPathsCount() == 0) {
                throw GcpException.invalidArgument("update_mask is required");
            }
            GcsObjectPreconditions conditions = preconditions(
                    request.hasIfGenerationMatch(), request.getIfGenerationMatch(),
                    request.hasIfGenerationNotMatch(), request.getIfGenerationNotMatch(),
                    request.hasIfMetagenerationMatch(), request.getIfMetagenerationMatch(),
                    request.hasIfMetagenerationNotMatch(), request.getIfMetagenerationNotMatch());
            return GcsGrpcMapper.toProto(service.patchObject(bucket, input.getName(),
                    GcsGrpcMapper.objectUpdateFields(input, request.getUpdateMask().getPathsList()), conditions));
        });
    }

    @Override
    public void deleteObject(DeleteObjectRequest request, StreamObserver<Empty> observer) {
        unary(observer, () -> {
            String bucket = GcsGrpcMapper.bucketId(request.getBucket());
            requireDelete(bucket, request.getObject());
            GcsObjectPreconditions conditions = preconditions(
                    request.hasIfGenerationMatch(), request.getIfGenerationMatch(),
                    request.hasIfGenerationNotMatch(), request.getIfGenerationNotMatch(),
                    request.hasIfMetagenerationMatch(), request.getIfMetagenerationMatch(),
                    request.hasIfMetagenerationNotMatch(), request.getIfMetagenerationNotMatch());
            if (request.getGeneration() != 0) {
                service.deleteObjectVersion(bucket, request.getObject(),
                        Long.toString(request.getGeneration()), conditions);
            } else if (!service.deleteObject(bucket, request.getObject(), conditions)) {
                throw GcpException.notFound("Object not found: " + request.getObject());
            }
            return Empty.getDefaultInstance();
        });
    }

    @Override
    public void composeObject(ComposeObjectRequest request,
            StreamObserver<com.google.storage.v2.Object> observer) {
        unary(observer, () -> {
            com.google.storage.v2.Object destination = request.getDestination();
            String bucket = GcsGrpcMapper.bucketId(destination.getBucket());
            List<GcsComposeSource> sources = new ArrayList<>();
            for (ComposeObjectRequest.SourceObject source : request.getSourceObjectsList()) {
                requireRead(bucket, source.getName());
                sources.add(new GcsComposeSource(source.getName(),
                        source.getGeneration() == 0 ? null : Long.toString(source.getGeneration()),
                        source.hasObjectPreconditions()
                                && source.getObjectPreconditions().hasIfGenerationMatch()
                                ? source.getObjectPreconditions().getIfGenerationMatch() : null));
            }
            requireWrite(bucket, destination.getName());
            GcsObjectPreconditions conditions = new GcsObjectPreconditions(
                    request.hasIfGenerationMatch() ? request.getIfGenerationMatch() : null,
                    null,
                    request.hasIfMetagenerationMatch() ? request.getIfMetagenerationMatch() : null,
                    null);
            GcsObjectMeta stored = service.composeObjectSources(bucket, destination.getName(), sources,
                    destination.getContentType().isBlank() ? null : destination.getContentType(),
                    GcsGrpcMapper.fromProto(destination), conditions, baseUrl);
            return GcsGrpcMapper.toProto(stored);
        });
    }

    @Override
    public void listObjects(ListObjectsRequest request, StreamObserver<ListObjectsResponse> observer) {
        unary(observer, () -> {
            String bucket = GcsGrpcMapper.bucketId(request.getParent());
            requireList(bucket, request.getPrefix());
            if (request.getSoftDeleted() || !request.getMatchGlob().isBlank() || !request.getFilter().isBlank()) {
                throw GcpException.unimplemented("Requested object listing mode is not supported");
            }
            List<GcsObjectMeta> candidates = request.getVersions()
                    ? service.listObjectVersions(bucket, request.getPrefix())
                    : service.listObjects(bucket);
            TreeMap<String, ListedResult> results = new TreeMap<>();
            String prefix = request.getPrefix();
            for (GcsObjectMeta meta : candidates) {
                String name = meta.getName();
                if (!prefix.isBlank() && !name.startsWith(prefix)) {
                    continue;
                }
                if (!request.getLexicographicStart().isBlank()
                        && name.compareTo(request.getLexicographicStart()) < 0) {
                    continue;
                }
                if (!request.getLexicographicEnd().isBlank()
                        && name.compareTo(request.getLexicographicEnd()) >= 0) {
                    continue;
                }
                if (!request.getDelimiter().isBlank()) {
                    String remainder = name.substring(prefix.length());
                    int delimiter = remainder.indexOf(request.getDelimiter());
                    if (delimiter >= 0) {
                        String commonPrefix = prefix + remainder.substring(
                                0, delimiter + request.getDelimiter().length());
                        results.putIfAbsent(commonPrefix + "\0P", ListedResult.prefix(commonPrefix));
                        if (!(request.getIncludeTrailingDelimiter() && name.equals(commonPrefix))) {
                            continue;
                        }
                    }
                }
                String key = name + "\0O\0" + Objects.toString(meta.getGeneration(), "");
                results.put(key, ListedResult.object(meta));
            }
            PageToken.Page<ListedResult> page = PageToken.paginate(new ArrayList<>(results.values()),
                    limitedPageSize(request.getPageSize()), request.getPageToken());
            ListObjectsResponse.Builder response = ListObjectsResponse.newBuilder();
            for (ListedResult result : page.items()) {
                if (result.prefix != null) {
                    response.addPrefixes(result.prefix);
                } else {
                    response.addObjects(GcsGrpcMapper.toProto(result.object));
                }
            }
            if (page.nextPageToken() != null) {
                response.setNextPageToken(page.nextPageToken());
            }
            return response.build();
        });
    }

    @Override
    public void readObject(ReadObjectRequest request, StreamObserver<ReadObjectResponse> observer) {
        try {
            String bucket = GcsGrpcMapper.bucketId(request.getBucket());
            requireRead(bucket, request.getObject());
            var download = service.getObjectForDownload(bucket, request.getObject(),
                    request.getGeneration() == 0 ? null : Long.toString(request.getGeneration()),
                    GcsCustomerEncryption.none());
            checkObjectPreconditions(download.meta(),
                    request.hasIfGenerationMatch() ? request.getIfGenerationMatch() : null,
                    request.hasIfGenerationNotMatch() ? request.getIfGenerationNotMatch() : null,
                    request.hasIfMetagenerationMatch() ? request.getIfMetagenerationMatch() : null,
                    request.hasIfMetagenerationNotMatch() ? request.getIfMetagenerationNotMatch() : null);
            if (request.getReadLimit() < 0) {
                throw GcpException.invalidArgument("read_limit must not be negative");
            }
            byte[] data = download.data();
            long requestedOffset = request.getReadOffset();
            int start = requestedOffset < 0
                    ? (int) Math.max(0, data.length + requestedOffset)
                    : (int) Math.min(data.length, requestedOffset);
            long available = data.length - (long) start;
            int length = (int) Math.min(available,
                    request.getReadLimit() == 0 ? available : request.getReadLimit());
            int end = start + length;
            boolean first = true;
            for (int position = start; position < end || first; position += READ_CHUNK_SIZE) {
                int chunkEnd = Math.min(end, position + READ_CHUNK_SIZE);
                byte[] chunk = Arrays.copyOfRange(data, position, chunkEnd);
                ReadObjectResponse.Builder response = ReadObjectResponse.newBuilder()
                        .setChecksummedData(checksummedData(chunk));
                if (first) {
                    response.setMetadata(GcsGrpcMapper.toProto(download.meta()))
                            .setObjectChecksums(GcsGrpcMapper.toChecksums(download.meta()));
                    if (request.getReadOffset() != 0 || request.getReadLimit() != 0) {
                        response.setContentRange(ContentRange.newBuilder()
                                .setStart(start).setEnd(end).setCompleteLength(data.length));
                    }
                }
                observer.onNext(response.build());
                first = false;
                if (position == end) {
                    break;
                }
            }
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    @Override
    public void startResumableWrite(StartResumableWriteRequest request,
            StreamObserver<StartResumableWriteResponse> observer) {
        unary(observer, () -> {
            WriteObjectSpec spec = request.getWriteObjectSpec();
            GcsObjectMeta object = GcsGrpcMapper.fromProto(spec.getResource());
            requireWrite(object.getBucket(), object.getName());
            String uploadId = service.startStreamingUpload(object, preconditions(spec),
                    spec.hasObjectSize() ? spec.getObjectSize() : null,
                    request.hasObjectChecksums() && request.getObjectChecksums().hasCrc32C()
                            ? request.getObjectChecksums().getCrc32C() : null,
                    request.hasObjectChecksums() && !request.getObjectChecksums().getMd5Hash().isEmpty()
                            ? request.getObjectChecksums().getMd5Hash().toByteArray() : null);
            return StartResumableWriteResponse.newBuilder().setUploadId(uploadId).build();
        });
    }

    @Override
    public void queryWriteStatus(QueryWriteStatusRequest request,
            StreamObserver<QueryWriteStatusResponse> observer) {
        unary(observer, () -> {
            GcsStreamingUpload upload = service.getStreamingUpload(request.getUploadId());
            requireWrite(upload.object().getBucket(), upload.object().getName());
            QueryWriteStatusResponse.Builder response = QueryWriteStatusResponse.newBuilder();
            if (upload.finalizedObject() != null) {
                response.setResource(GcsGrpcMapper.toProto(upload.finalizedObject()));
            } else {
                response.setPersistedSize(upload.size())
                        .setPersistedDataChecksums(checksums(upload.data()));
            }
            return response.build();
        });
    }

    @Override
    public StreamObserver<WriteObjectRequest> writeObject(StreamObserver<WriteObjectResponse> observer) {
        String authorization = authorization();
        return new StreamObserver<>() {
            private String uploadId;
            private boolean resumable;
            private boolean finished;

            @Override
            public void onNext(WriteObjectRequest request) {
                if (finished) {
                    fail(GcpException.invalidArgument("Write already finalized"));
                    return;
                }
                try {
                    initialize(request.getUploadId(), request.hasWriteObjectSpec()
                            ? request.getWriteObjectSpec() : null, request.getObjectChecksums(), authorization);
                    GcsStreamingUpload upload = service.getStreamingUpload(uploadId);
                    synchronized (upload) {
                        append(upload, request.getWriteOffset(), request.hasChecksummedData()
                                ? request.getChecksummedData() : ChecksummedData.getDefaultInstance());
                        if (request.getFinishWrite()) {
                            GcsObjectMeta stored = finalizeUpload(uploadId, upload,
                                    request.hasObjectChecksums() ? request.getObjectChecksums() : null);
                            finished = true;
                            releaseNonResumableUpload(uploadId, resumable);
                            observer.onNext(WriteObjectResponse.newBuilder()
                                    .setResource(GcsGrpcMapper.toProto(stored)).build());
                            observer.onCompleted();
                        }
                    }
                } catch (Exception e) {
                    fail(e);
                }
            }

            private void initialize(String requestedUploadId, WriteObjectSpec spec,
                    ObjectChecksums checksums, String auth) {
                if (uploadId != null) {
                    if (!requestedUploadId.isBlank() || spec != null) {
                        throw GcpException.invalidArgument("First-message fields may only be sent once");
                    }
                    return;
                }
                if (!requestedUploadId.isBlank()) {
                    uploadId = requestedUploadId;
                    resumable = true;
                } else if (spec != null) {
                    GcsObjectMeta object = GcsGrpcMapper.fromProto(spec.getResource());
                    requireWrite(auth, object.getBucket(), object.getName());
                    uploadId = service.startStreamingUpload(object, preconditions(spec),
                            spec.hasObjectSize() ? spec.getObjectSize() : null,
                            checksums.hasCrc32C() ? checksums.getCrc32C() : null,
                            checksums.getMd5Hash().isEmpty() ? null : checksums.getMd5Hash().toByteArray());
                } else {
                    throw GcpException.invalidArgument("The first write request must include an upload ID or spec");
                }
                GcsStreamingUpload upload = service.getStreamingUpload(uploadId);
                requireWrite(auth, upload.object().getBucket(), upload.object().getName());
            }

            @Override
            public void onError(Throwable t) {
                finished = true;
                releaseNonResumableUpload(uploadId, resumable);
            }

            @Override
            public void onCompleted() {
                if (finished) {
                    return;
                }
                if (uploadId == null || !resumable) {
                    fail(GcpException.invalidArgument("Non-resumable write closed without finish_write"));
                    return;
                }
                GcsStreamingUpload upload = service.getStreamingUpload(uploadId);
                synchronized (upload) {
                    observer.onNext(WriteObjectResponse.newBuilder()
                            .setPersistedSize(upload.size())
                            .setPersistedDataChecksums(checksums(upload.data())).build());
                }
                observer.onCompleted();
                finished = true;
            }

            private void fail(Throwable t) {
                if (!finished) {
                    finished = true;
                    releaseNonResumableUpload(uploadId, resumable);
                    GcpGrpcController.grpcError(observer, t);
                }
            }
        };
    }

    @Override
    public StreamObserver<BidiWriteObjectRequest> bidiWriteObject(
            StreamObserver<BidiWriteObjectResponse> observer) {
        String authorization = authorization();
        return new StreamObserver<>() {
            private String uploadId;
            private boolean resumable;
            private boolean finished;

            @Override
            public void onNext(BidiWriteObjectRequest request) {
                if (finished) {
                    fail(GcpException.invalidArgument("Write already finalized"));
                    return;
                }
                try {
                    if (request.hasAppendObjectSpec()) {
                        throw GcpException.unimplemented("Appendable objects are not supported");
                    }
                    initialize(request, authorization);
                    GcsStreamingUpload upload = service.getStreamingUpload(uploadId);
                    synchronized (upload) {
                        append(upload, request.getWriteOffset(), request.hasChecksummedData()
                                ? request.getChecksummedData() : ChecksummedData.getDefaultInstance());
                        if (request.getFinishWrite()) {
                            GcsObjectMeta stored = finalizeUpload(uploadId, upload,
                                    request.hasObjectChecksums() ? request.getObjectChecksums() : null);
                            finished = true;
                            releaseNonResumableUpload(uploadId, resumable);
                            observer.onNext(BidiWriteObjectResponse.newBuilder()
                                    .setResource(GcsGrpcMapper.toProto(stored)).build());
                            observer.onCompleted();
                        } else if (request.getStateLookup() || request.getFlush()) {
                            observer.onNext(status(upload));
                        }
                    }
                } catch (Exception e) {
                    fail(e);
                }
            }

            private void initialize(BidiWriteObjectRequest request, String auth) {
                if (uploadId != null) {
                    if (!request.getUploadId().isBlank() || request.hasWriteObjectSpec()) {
                        throw GcpException.invalidArgument("First-message fields may only be sent once");
                    }
                    return;
                }
                if (!request.getUploadId().isBlank()) {
                    uploadId = request.getUploadId();
                    resumable = true;
                } else if (request.hasWriteObjectSpec()) {
                    WriteObjectSpec spec = request.getWriteObjectSpec();
                    if (spec.hasAppendable() && spec.getAppendable()) {
                        throw GcpException.unimplemented("Appendable objects are not supported");
                    }
                    GcsObjectMeta object = GcsGrpcMapper.fromProto(spec.getResource());
                    requireWrite(auth, object.getBucket(), object.getName());
                    ObjectChecksums supplied = request.getObjectChecksums();
                    uploadId = service.startStreamingUpload(object, preconditions(spec),
                            spec.hasObjectSize() ? spec.getObjectSize() : null,
                            supplied.hasCrc32C() ? supplied.getCrc32C() : null,
                            supplied.getMd5Hash().isEmpty() ? null : supplied.getMd5Hash().toByteArray());
                } else {
                    throw GcpException.invalidArgument("The first write request must include an upload ID or spec");
                }
                GcsStreamingUpload upload = service.getStreamingUpload(uploadId);
                requireWrite(auth, upload.object().getBucket(), upload.object().getName());
            }

            @Override
            public void onError(Throwable t) {
                finished = true;
                releaseNonResumableUpload(uploadId, resumable);
            }

            @Override
            public void onCompleted() {
                if (finished) {
                    return;
                }
                if (uploadId == null || !resumable) {
                    fail(GcpException.invalidArgument("Non-resumable write closed without finish_write"));
                    return;
                }
                GcsStreamingUpload upload = service.getStreamingUpload(uploadId);
                synchronized (upload) {
                    observer.onNext(status(upload));
                }
                observer.onCompleted();
                finished = true;
            }

            private BidiWriteObjectResponse status(GcsStreamingUpload upload) {
                return BidiWriteObjectResponse.newBuilder()
                        .setPersistedSize(upload.size())
                        .setPersistedDataChecksums(checksums(upload.data())).build();
            }

            private void fail(Throwable t) {
                if (!finished) {
                    finished = true;
                    releaseNonResumableUpload(uploadId, resumable);
                    GcpGrpcController.grpcError(observer, t);
                }
            }
        };
    }

    private GcsObjectMeta finalizeUpload(String uploadId, GcsStreamingUpload upload,
            ObjectChecksums suppliedChecksums) {
        try {
            validateFullChecksums(upload, suppliedChecksums);
            return service.finalizeStreamingUpload(uploadId, baseUrl);
        } catch (Exception e) {
            service.abortStreamingUpload(uploadId);
            throw e;
        }
    }

    private void releaseNonResumableUpload(String uploadId, boolean resumable) {
        if (uploadId != null && !resumable) {
            service.abortStreamingUpload(uploadId);
        }
    }

    private static void append(GcsStreamingUpload upload, long offset, ChecksummedData data) {
        byte[] content = data.getContent().toByteArray();
        if (data.hasCrc32C() && data.getCrc32C() != crc32c(content)) {
            throw GcpException.dataLoss("Chunk CRC32C checksum mismatch");
        }
        upload.append(offset, content);
    }

    private static void validateFullChecksums(GcsStreamingUpload upload, ObjectChecksums supplied) {
        byte[] data = upload.data();
        Integer expectedCrc = supplied != null && supplied.hasCrc32C()
                ? Integer.valueOf(supplied.getCrc32C()) : upload.expectedCrc32c();
        byte[] expectedMd5 = supplied != null && !supplied.getMd5Hash().isEmpty()
                ? supplied.getMd5Hash().toByteArray() : upload.expectedMd5();
        if (expectedCrc != null && expectedCrc != crc32c(data)) {
            throw GcpException.dataLoss("Object CRC32C checksum mismatch");
        }
        if (expectedMd5 != null && !MessageDigest.isEqual(expectedMd5, md5(data))) {
            throw GcpException.dataLoss("Object MD5 checksum mismatch");
        }
    }

    private static ChecksummedData checksummedData(byte[] data) {
        return ChecksummedData.newBuilder().setContent(ByteString.copyFrom(data))
                .setCrc32C(crc32c(data)).build();
    }

    private static ObjectChecksums checksums(byte[] data) {
        return ObjectChecksums.newBuilder().setCrc32C(crc32c(data))
                .setMd5Hash(ByteString.copyFrom(md5(data))).build();
    }

    private static int crc32c(byte[] data) {
        CRC32C crc = new CRC32C();
        crc.update(data, 0, data.length);
        return (int) crc.getValue();
    }

    private static byte[] md5(byte[] data) {
        try {
            return MessageDigest.getInstance("MD5").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static GcsObjectPreconditions preconditions(WriteObjectSpec spec) {
        return preconditions(spec.hasIfGenerationMatch(), spec.getIfGenerationMatch(),
                spec.hasIfGenerationNotMatch(), spec.getIfGenerationNotMatch(),
                spec.hasIfMetagenerationMatch(), spec.getIfMetagenerationMatch(),
                spec.hasIfMetagenerationNotMatch(), spec.getIfMetagenerationNotMatch());
    }

    private static GcsObjectPreconditions preconditions(boolean hasGenerationMatch, long generationMatch,
            boolean hasGenerationNotMatch, long generationNotMatch,
            boolean hasMetagenerationMatch, long metagenerationMatch,
            boolean hasMetagenerationNotMatch, long metagenerationNotMatch) {
        return new GcsObjectPreconditions(hasGenerationMatch ? generationMatch : null,
                hasGenerationNotMatch ? generationNotMatch : null,
                hasMetagenerationMatch ? metagenerationMatch : null,
                hasMetagenerationNotMatch ? metagenerationNotMatch : null);
    }

    private static void checkObjectPreconditions(GcsObjectMeta meta, Long generationMatch,
            Long generationNotMatch, Long metagenerationMatch, Long metagenerationNotMatch) {
        long generation = Long.parseLong(meta.getGeneration());
        long metageneration = Long.parseLong(meta.getMetageneration());
        if (generationMatch != null && generation != generationMatch
                || generationNotMatch != null && generation == generationNotMatch
                || metagenerationMatch != null && metageneration != metagenerationMatch
                || metagenerationNotMatch != null && metageneration == metagenerationNotMatch) {
            throw GcpException.conditionNotMet("Object precondition failed");
        }
    }

    private static void checkMetageneration(String currentValue, Long match, Long notMatch) {
        long current = Long.parseLong(currentValue);
        if (match != null && current != match || notMatch != null && current == notMatch) {
            throw GcpException.conditionNotMet("Bucket metageneration precondition failed");
        }
    }

    private static int limitedPageSize(int requested) {
        return requested <= 0 ? 1000 : Math.min(requested, 1000);
    }

    private String authorization() {
        return GcsGrpcAuthorizationInterceptor.AUTHORIZATION.get();
    }

    private void rejectDownscoped() {
        if (authorizationService != null) {
            authorizationService.rejectDownscopedToken(authorization());
        }
    }

    private void requireRead(String bucket, String object) {
        requireRead(authorization(), bucket, object);
    }

    private void requireRead(String authorization, String bucket, String object) {
        if (authorizationService != null) {
            authorizationService.requireObjectRead(authorization, bucket, object);
        }
    }

    private void requireWrite(String bucket, String object) {
        requireWrite(authorization(), bucket, object);
    }

    private void requireWrite(String authorization, String bucket, String object) {
        if (authorizationService != null) {
            authorizationService.requireObjectWrite(authorization, bucket, object);
        }
    }

    private void requireDelete(String bucket, String object) {
        if (authorizationService != null) {
            authorizationService.requireObjectDelete(authorization(), bucket, object);
        }
    }

    private void requireList(String bucket, String prefix) {
        if (authorizationService != null) {
            authorizationService.requireObjectList(authorization(), bucket, prefix);
        }
    }

    private static <T> void unary(StreamObserver<T> observer, ThrowingSupplier<T> supplier) {
        try {
            observer.onNext(supplier.get());
            observer.onCompleted();
        } catch (Exception e) {
            GcpGrpcController.grpcError(observer, e);
        }
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static final class ListedResult {
        private final String prefix;
        private final GcsObjectMeta object;

        private ListedResult(String prefix, GcsObjectMeta object) {
            this.prefix = prefix;
            this.object = object;
        }

        static ListedResult prefix(String prefix) {
            return new ListedResult(prefix, null);
        }

        static ListedResult object(GcsObjectMeta object) {
            return new ListedResult(null, object);
        }
    }
}
