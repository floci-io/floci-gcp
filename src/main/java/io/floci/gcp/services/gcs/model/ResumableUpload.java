package io.floci.gcp.services.gcs.model;

import java.util.Map;

// systemMetadata carries the fields a client may set when opening the session
// (contentEncoding, customTime, ...) so they survive to the finalizing put
// rather than being dropped once the first chunk arrives.
public record ResumableUpload(String bucket, String objectName, String contentType,
        Map<String, String> customerEncryption, Map<String, String> metadata,
        GcsObjectMeta systemMetadata, GcsObjectPreconditions preconditions,
        byte[] data, Long totalSize) {}
