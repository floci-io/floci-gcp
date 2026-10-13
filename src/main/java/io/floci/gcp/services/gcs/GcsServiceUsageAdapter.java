package io.floci.gcp.services.gcs;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import com.google.rpc.Status;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ProtoResourceFields;
import io.floci.gcp.core.common.ServiceUsageAdapter;
import io.floci.gcp.services.gcs.model.CompletedResumableUpload;
import io.floci.gcp.services.gcs.model.ResumableUpload;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Bucket and object requests name a bucket, not a project ({@code /storage/v1/b/{bucket}},
 * {@code /{bucket}/{object}}, {@code projects/_/buckets/{bucket}}), and act on the project that
 * owns it, the source and the destination of a copy or rewrite alike. Only the fields the API
 * declares as bucket names are read, so an object named like a bucket is not. The handlers look a bucket
 * up by its id whichever project the resource name carries, so that project is not trusted here
 * either. A resumed write acts on the bucket its {@code upload_id} was started for, on gRPC and
 * on the JSON upload route alike. A bucket or upload that does not exist leaves the generic resolution in place.
 */
final class GcsServiceUsageAdapter implements ServiceUsageAdapter {

    private static final String PROJECT_PREFIX = "projects/";
    private static final String BUCKET_MARKER = "/buckets/";
    private static final String UPLOAD_ID_FIELD = "upload_id";
    private static final String BUCKET_TYPE = "storage.googleapis.com/Bucket";
    /** {@code dstBucket} is the destination of {@code copyTo} and {@code rewriteTo}. */
    private static final List<String> BUCKET_PARAMETERS = List.of("bucket", "dstBucket");

    private final GcsService service;

    GcsServiceUsageAdapter(GcsService service) {
        this.service = service;
    }

    @Override
    public Optional<List<String>> grpcProjects(Message request) {
        Set<String> buckets = new LinkedHashSet<>();
        for (ProtoResourceFields.Ref ref : ProtoResourceFields.refs(request)) {
            String bucket = BUCKET_TYPE.equals(ref.type()) ? bucketOf(ref.value()) : null;
            if (bucket != null) {
                buckets.add(bucket);
            }
        }
        FieldDescriptor uploadId = request.getDescriptorForType().findFieldByName(UPLOAD_ID_FIELD);
        if (uploadId != null && uploadId.getJavaType() == FieldDescriptor.JavaType.STRING
                && !((String) request.getField(uploadId)).isEmpty()) {
            bucketOfUpload((String) request.getField(uploadId)).ifPresent(buckets::add);
        }
        Set<String> projects = new LinkedHashSet<>();
        for (String bucket : buckets) {
            projectOfBucket(bucket).ifPresent(projects::add);
        }
        return projects.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(projects));
    }

    @Override
    public Optional<List<String>> restProjects(RestRequest request) {
        Set<String> projects = new LinkedHashSet<>();
        for (String parameter : BUCKET_PARAMETERS) {
            String bucket = request.pathParameter(parameter);
            if (bucket != null) {
                projectOfBucket(bucket).ifPresent(projects::add);
            }
        }
        // A resumable chunk is applied to the bucket the upload was started for, whichever
        // bucket its path names.
        String uploadId = request.queryParameter(UPLOAD_ID_FIELD);
        if (uploadId != null && request.resourceClass() == GcsUploadController.class) {
            ResumableUpload upload = service.findResumableUpload(uploadId);
            CompletedResumableUpload completed = service.completedResumableUpload(uploadId);
            if (upload != null) {
                projectOfBucket(upload.bucket()).ifPresent(projects::add);
            } else if (completed != null) {
                // A retry of a finished upload replays the object it wrote.
                projectOfBucket(completed.bucket()).ifPresent(projects::add);
            }
        }
        return projects.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(projects));
    }

    /** A batch names no bucket itself; each request it carries is dispatched and gated on its own. */
    @Override
    public boolean restPassThrough(RestRequest request) {
        return request.resourceClass() == GcsBatchController.class;
    }

    // The XML API's body for a disabled API is not in the references; this follows the AccessDenied
    // error the XML routes already return for a 403.
    @Override
    public Optional<Response> restDenial(RestRequest request, Status status) {
        if (request.resourceClass() != GcsXmlDownloadController.class) {
            return Optional.empty();
        }
        return Optional.of(GcsXmlErrorResponse.of(403, "AccessDenied", status.getMessage()));
    }

    private Optional<String> projectOfBucket(String bucket) {
        try {
            return Optional.ofNullable(service.getBucket(bucket).getProjectId());
        } catch (GcpException expected) {
            // A bucket that does not exist has no owner: the generic resolution applies and the
            // handler answers 404.
            return Optional.empty();
        }
    }

    private Optional<String> bucketOfUpload(String uploadId) {
        try {
            return Optional.ofNullable(service.getStreamingUpload(uploadId).object().getBucket());
        } catch (GcpException expected) {
            // An upload that does not exist has no bucket: the generic resolution applies and the
            // handler answers 404.
            return Optional.empty();
        }
    }

    /** The bucket id of {@code projects/{project}/buckets/{bucket}[/...]}, or null. */
    static String bucketOf(String resourceName) {
        if (!resourceName.startsWith(PROJECT_PREFIX)) {
            return null;
        }
        int marker = resourceName.indexOf('/', PROJECT_PREFIX.length());
        if (marker < 0 || !resourceName.startsWith(BUCKET_MARKER, marker)) {
            return null;
        }
        String rest = resourceName.substring(marker + BUCKET_MARKER.length());
        int end = rest.indexOf('/');
        String bucket = end < 0 ? rest : rest.substring(0, end);
        return bucket.isEmpty() ? null : bucket;
    }
}
