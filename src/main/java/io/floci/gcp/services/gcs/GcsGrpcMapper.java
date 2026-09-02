package io.floci.gcp.services.gcs;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.google.storage.v2.Bucket;
import com.google.storage.v2.ObjectChecksums;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.gcs.model.GcsBucket;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

final class GcsGrpcMapper {

    private static final String BUCKET_MARKER = "/buckets/";

    private GcsGrpcMapper() {}

    static String bucketId(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            throw GcpException.invalidArgument("Bucket resource name is required");
        }
        int marker = resourceName.indexOf(BUCKET_MARKER);
        if (!resourceName.startsWith("projects/") || marker < 0
                || marker + BUCKET_MARKER.length() == resourceName.length()) {
            throw GcpException.invalidArgument("Invalid bucket resource name: " + resourceName);
        }
        String bucket = resourceName.substring(marker + BUCKET_MARKER.length());
        if (bucket.contains("/")) {
            throw GcpException.invalidArgument("Invalid bucket resource name: " + resourceName);
        }
        return bucket;
    }

    static String projectId(String projectName) {
        if (projectName == null || !projectName.startsWith("projects/")
                || projectName.length() == "projects/".length()) {
            throw GcpException.invalidArgument("Invalid project resource name: " + projectName);
        }
        return projectName.substring("projects/".length());
    }

    static String bucketName(String bucket) {
        return "projects/_/buckets/" + bucket;
    }

    static Bucket toProto(GcsBucket stored) {
        Bucket.Builder value = Bucket.newBuilder()
                .setName(bucketName(stored.getName()))
                .setBucketId(stored.getName())
                .setProject("projects/" + stored.getProjectId())
                .setMetageneration(parseLong(stored.getMetageneration()))
                .setLocation(orEmpty(stored.getLocation()))
                .setStorageClass(orEmpty(stored.getStorageClass()))
                .setDefaultEventBasedHold(Boolean.TRUE.equals(stored.getDefaultEventBasedHold()));
        timestamp(stored.getTimeCreated()).ifPresent(value::setCreateTime);
        timestamp(stored.getUpdated()).ifPresent(value::setUpdateTime);
        if (stored.getLabels() != null) {
            value.putAllLabels(stored.getLabels());
        }
        if (stored.getVersioning() != null) {
            value.setVersioning(Bucket.Versioning.newBuilder()
                    .setEnabled(Boolean.TRUE.equals(stored.getVersioning().get("enabled"))));
        }
        return value.build();
    }

    static Map<String, java.lang.Object> bucketCreateFields(Bucket bucket) {
        Map<String, java.lang.Object> body = new LinkedHashMap<>();
        if (!bucket.getLocation().isBlank()) {
            body.put("location", bucket.getLocation());
        }
        if (!bucket.getStorageClass().isBlank()) {
            body.put("storageClass", bucket.getStorageClass());
        }
        if (bucket.getLabelsCount() > 0) {
            body.put("labels", new LinkedHashMap<>(bucket.getLabelsMap()));
        }
        if (bucket.hasVersioning()) {
            body.put("versioning", Map.of("enabled", bucket.getVersioning().getEnabled()));
        }
        body.put("defaultEventBasedHold", bucket.getDefaultEventBasedHold());
        return body;
    }

    static Map<String, java.lang.Object> bucketUpdateFields(Bucket bucket,
            java.util.List<String> paths) {
        Map<String, java.lang.Object> all = bucketCreateFields(bucket);
        if (paths.contains("*")) {
            return all;
        }
        Map<String, java.lang.Object> patch = new LinkedHashMap<>();
        for (String path : paths) {
            switch (path) {
                case "labels" -> patch.put("labels", new LinkedHashMap<>(bucket.getLabelsMap()));
                case "versioning", "versioning.enabled" ->
                        patch.put("versioning", Map.of("enabled", bucket.getVersioning().getEnabled()));
                case "storage_class" -> patch.put("storageClass", bucket.getStorageClass());
                case "default_event_based_hold" ->
                        patch.put("defaultEventBasedHold", bucket.getDefaultEventBasedHold());
                default -> {
                    if (path.startsWith("labels.")) {
                        patch.put("labels", new LinkedHashMap<>(bucket.getLabelsMap()));
                    } else {
                        throw GcpException.invalidArgument("Unsupported bucket update field: " + path);
                    }
                }
            }
        }
        return patch;
    }

    static com.google.storage.v2.Object toProto(GcsObjectMeta stored) {
        com.google.storage.v2.Object.Builder value = com.google.storage.v2.Object.newBuilder()
                .setName(stored.getName())
                .setBucket(bucketName(stored.getBucket()))
                .setGeneration(parseLong(stored.getGeneration()))
                .setMetageneration(parseLong(stored.getMetageneration()))
                .setSize(parseLong(stored.getSize()))
                .setContentType(orEmpty(stored.getContentType()))
                .setStorageClass(orEmpty(stored.getStorageClass()))
                .setEtag(orEmpty(stored.getEtag()))
                .setContentDisposition(orEmpty(stored.getContentDisposition()))
                .setContentEncoding(orEmpty(stored.getContentEncoding()))
                .setContentLanguage(orEmpty(stored.getContentLanguage()))
                .setTemporaryHold(Boolean.TRUE.equals(stored.getTemporaryHold()))
                .setEventBasedHold(Boolean.TRUE.equals(stored.getEventBasedHold()));
        timestamp(stored.getTimeCreated()).ifPresent(value::setCreateTime);
        timestamp(stored.getUpdated()).ifPresent(value::setUpdateTime);
        timestamp(stored.getTimeDeleted()).ifPresent(value::setDeleteTime);
        timestamp(stored.getRetentionExpirationTime()).ifPresent(value::setRetentionExpireTime);
        if (stored.getMetadata() != null) {
            value.putAllMetadata(stored.getMetadata());
        }
        if (stored.getComponentCount() != null) {
            value.setComponentCount(stored.getComponentCount());
        }
        value.setChecksums(toChecksums(stored));
        return value.build();
    }

    static GcsObjectMeta fromProto(com.google.storage.v2.Object value) {
        GcsObjectMeta meta = new GcsObjectMeta();
        meta.setName(value.getName());
        meta.setBucket(bucketId(value.getBucket()));
        meta.setContentType(value.getContentType().isBlank() ? "application/octet-stream" : value.getContentType());
        // Left unset when the client omits it, so the object inherits the bucket default the
        // same way a REST write does; putObject falls back to STANDARD when the bucket has none.
        if (!value.getStorageClass().isBlank()) {
            meta.setStorageClass(value.getStorageClass());
        }
        meta.setContentDisposition(blankToNull(value.getContentDisposition()));
        meta.setContentEncoding(blankToNull(value.getContentEncoding()));
        meta.setContentLanguage(blankToNull(value.getContentLanguage()));
        meta.setTemporaryHold(value.getTemporaryHold());
        meta.setEventBasedHold(value.hasEventBasedHold() ? value.getEventBasedHold() : null);
        if (value.getMetadataCount() > 0) {
            meta.setMetadata(new LinkedHashMap<>(value.getMetadataMap()));
        }
        return meta;
    }

    static Map<String, java.lang.Object> objectUpdateFields(com.google.storage.v2.Object value,
            java.util.List<String> paths) {
        Map<String, java.lang.Object> patch = new LinkedHashMap<>();
        java.util.Set<String> selected = paths.contains("*")
                ? java.util.Set.of("content_type", "content_disposition", "content_encoding",
                        "content_language", "metadata", "temporary_hold", "event_based_hold")
                : new java.util.LinkedHashSet<>(paths);
        for (String path : selected) {
            switch (path) {
                case "content_type" -> patch.put("contentType", value.getContentType());
                case "content_disposition" -> patch.put("contentDisposition", value.getContentDisposition());
                case "content_encoding" -> patch.put("contentEncoding", value.getContentEncoding());
                case "content_language" -> patch.put("contentLanguage", value.getContentLanguage());
                case "metadata" -> patch.put("metadata", new LinkedHashMap<>(value.getMetadataMap()));
                case "temporary_hold" -> patch.put("temporaryHold", value.getTemporaryHold());
                case "event_based_hold" -> patch.put("eventBasedHold", value.getEventBasedHold());
                default -> {
                    if (path.startsWith("metadata.")) {
                        patch.put("metadata", new LinkedHashMap<>(value.getMetadataMap()));
                    } else {
                        throw GcpException.invalidArgument("Unsupported object update field: " + path);
                    }
                }
            }
        }
        return patch;
    }

    static ObjectChecksums toChecksums(GcsObjectMeta stored) {
        ObjectChecksums.Builder checksums = ObjectChecksums.newBuilder();
        if (stored.getCrc32c() != null) {
            checksums.setCrc32C(decodeCrc32c(stored.getCrc32c()));
        }
        if (stored.getMd5Hash() != null) {
            checksums.setMd5Hash(ByteString.copyFrom(Base64.getDecoder().decode(stored.getMd5Hash())));
        }
        return checksums.build();
    }

    static int decodeCrc32c(String encoded) {
        return ByteBuffer.wrap(Base64.getDecoder().decode(encoded)).getInt();
    }

    static java.util.Optional<Timestamp> timestamp(String value) {
        if (value == null || value.isBlank()) {
            return java.util.Optional.empty();
        }
        Instant instant = Instant.parse(value);
        return java.util.Optional.of(Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build());
    }

    private static long parseLong(String value) {
        return value == null || value.isBlank() ? 0 : Long.parseLong(value);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
