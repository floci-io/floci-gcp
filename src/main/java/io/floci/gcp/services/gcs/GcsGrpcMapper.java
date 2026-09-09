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
        if (stored.getIamConfiguration() != null) {
            Bucket.IamConfig.Builder iamConfig = Bucket.IamConfig.newBuilder();
            if (stored.getIamConfiguration().get("uniformBucketLevelAccess") instanceof Map<?, ?> access) {
                Bucket.IamConfig.UniformBucketLevelAccess.Builder uniformAccess =
                        Bucket.IamConfig.UniformBucketLevelAccess.newBuilder()
                                .setEnabled(Boolean.TRUE.equals(access.get("enabled")));
                if (access.get("lockedTime") instanceof String lockedTime) {
                    timestamp(lockedTime).ifPresent(uniformAccess::setLockTime);
                }
                iamConfig.setUniformBucketLevelAccess(uniformAccess);
            }
            if (stored.getIamConfiguration().get("publicAccessPrevention") instanceof String prevention) {
                iamConfig.setPublicAccessPrevention(prevention);
            }
            if (iamConfig.hasUniformBucketLevelAccess() || !iamConfig.getPublicAccessPrevention().isBlank()) {
                value.setIamConfig(iamConfig);
            }
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
        iamConfiguration(bucket).ifPresent(iamConfiguration -> body.put("iamConfiguration", iamConfiguration));
        body.put("defaultEventBasedHold", bucket.getDefaultEventBasedHold());
        return body;
    }

    static Map<String, java.lang.Object> bucketUpdateFields(GcsBucket current, Bucket bucket,
            java.util.List<String> paths) {
        if (paths.contains("*")) {
            Map<String, java.lang.Object> all = bucketCreateFields(bucket);
            all.putIfAbsent("iamConfiguration", Map.of());
            return all;
        }
        Map<String, java.lang.Object> patch = new LinkedHashMap<>();
        boolean mergeIamConfigurationMessage = false;
        boolean mergeUniformBucketLevelAccessMessage = false;
        boolean updateUniformBucketLevelAccessEnabled = false;
        boolean updateUniformBucketLevelAccessLockTime = false;
        boolean updatePublicAccessPrevention = false;
        for (String path : paths) {
            switch (path) {
                case "labels" -> patch.put("labels", new LinkedHashMap<>(bucket.getLabelsMap()));
                case "versioning", "versioning.enabled" ->
                        patch.put("versioning", Map.of("enabled", bucket.getVersioning().getEnabled()));
                case "iam_config" -> mergeIamConfigurationMessage = true;
                case "iam_config.uniform_bucket_level_access" ->
                        mergeUniformBucketLevelAccessMessage = true;
                case "iam_config.uniform_bucket_level_access.enabled" ->
                        updateUniformBucketLevelAccessEnabled = true;
                case "iam_config.uniform_bucket_level_access.lock_time" ->
                        updateUniformBucketLevelAccessLockTime = true;
                case "iam_config.public_access_prevention" -> updatePublicAccessPrevention = true;
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
        if (mergeIamConfigurationMessage || mergeUniformBucketLevelAccessMessage
                || updateUniformBucketLevelAccessEnabled || updateUniformBucketLevelAccessLockTime
                || updatePublicAccessPrevention) {
            patch.put("iamConfiguration", mergeIamConfiguration(
                    current.getIamConfiguration(), bucket,
                    mergeIamConfigurationMessage, mergeUniformBucketLevelAccessMessage,
                    updateUniformBucketLevelAccessEnabled, updateUniformBucketLevelAccessLockTime,
                    updatePublicAccessPrevention));
        }
        return patch;
    }

    private static Map<String, java.lang.Object> mergeIamConfiguration(
            Map<String, java.lang.Object> current, Bucket bucket,
            boolean mergeIamConfigurationMessage, boolean mergeUniformBucketLevelAccessMessage,
            boolean updateUniformBucketLevelAccessEnabled,
            boolean updateUniformBucketLevelAccessLockTime,
            boolean updatePublicAccessPrevention) {
        Map<String, java.lang.Object> merged = current == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(current);
        Bucket.IamConfig requested = bucket.getIamConfig();
        // Protobuf FieldMask update semantics merge a selected message into the
        // stored message, preserving omitted siblings. A leaf mask can clear one field.
        if (mergeIamConfigurationMessage) {
            if (bucket.hasIamConfig()) {
                if (requested.hasUniformBucketLevelAccess()) {
                    merged.put("uniformBucketLevelAccess", mergeUniformBucketLevelAccess(
                            merged.get("uniformBucketLevelAccess"),
                            requested.getUniformBucketLevelAccess()));
                }
                if (!requested.getPublicAccessPrevention().isBlank()) {
                    merged.put("publicAccessPrevention", requested.getPublicAccessPrevention());
                }
            }
            return merged;
        }

        if (mergeUniformBucketLevelAccessMessage) {
            if (requested.hasUniformBucketLevelAccess()) {
                merged.put("uniformBucketLevelAccess", mergeUniformBucketLevelAccess(
                        merged.get("uniformBucketLevelAccess"),
                        requested.getUniformBucketLevelAccess()));
            }
        } else if (updateUniformBucketLevelAccessEnabled || updateUniformBucketLevelAccessLockTime) {
            Map<String, java.lang.Object> uniformAccess = mutableMap(
                    merged.get("uniformBucketLevelAccess"));
            if (updateUniformBucketLevelAccessEnabled) {
                uniformAccess.put("enabled", requested.getUniformBucketLevelAccess().getEnabled());
            }
            if (updateUniformBucketLevelAccessLockTime) {
                if (requested.getUniformBucketLevelAccess().hasLockTime()) {
                    uniformAccess.put("lockedTime",
                            instant(requested.getUniformBucketLevelAccess().getLockTime()));
                } else {
                    uniformAccess.remove("lockedTime");
                }
            }
            merged.put("uniformBucketLevelAccess", uniformAccess);
        }

        if (updatePublicAccessPrevention) {
            if (requested.getPublicAccessPrevention().isBlank()) {
                merged.remove("publicAccessPrevention");
            } else {
                merged.put("publicAccessPrevention", requested.getPublicAccessPrevention());
            }
        }
        return merged;
    }

    private static Map<String, java.lang.Object> mergeUniformBucketLevelAccess(
            java.lang.Object current, Bucket.IamConfig.UniformBucketLevelAccess requested) {
        Map<String, java.lang.Object> merged = mutableMap(current);
        // Official clients can select the parent IAM field while sending only this
        // submessage. Its presence makes the plain proto3 false value intentional.
        merged.put("enabled", requested.getEnabled());
        return merged;
    }

    private static Map<String, java.lang.Object> mutableMap(java.lang.Object value) {
        Map<String, java.lang.Object> copy = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return copy;
    }

    private static java.util.Optional<Map<String, java.lang.Object>> iamConfiguration(Bucket bucket) {
        if (!bucket.hasIamConfig()) {
            return java.util.Optional.empty();
        }
        Map<String, java.lang.Object> iamConfiguration = new LinkedHashMap<>();
        if (bucket.getIamConfig().hasUniformBucketLevelAccess()) {
            Map<String, java.lang.Object> uniformAccess = new LinkedHashMap<>();
            uniformAccess.put("enabled", bucket.getIamConfig().getUniformBucketLevelAccess().getEnabled());
            iamConfiguration.put("uniformBucketLevelAccess", uniformAccess);
        }
        if (!bucket.getIamConfig().getPublicAccessPrevention().isBlank()) {
            iamConfiguration.put("publicAccessPrevention", bucket.getIamConfig().getPublicAccessPrevention());
        }
        return iamConfiguration.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(iamConfiguration);
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

    private static String instant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos()).toString();
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
