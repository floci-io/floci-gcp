package io.floci.gcp.services.gcs.model;

import java.util.Map;

/**
 * An in-flight REST resumable upload session.
 *
 * <p>{@code systemMetadata} carries the fields a client may set when opening the session
 * (contentEncoding, customTime, ...) so they survive to the finalizing put rather than being
 * dropped once the first chunk arrives.
 *
 * <p>{@code lastTouchedMillis} is wall-clock {@code System.currentTimeMillis()} from the last
 * time the session was created or advanced by a chunk. It exists so abandoned sessions can be
 * evicted instead of holding their buffered bytes for the lifetime of the process; nothing on
 * the wire derives from it.
 */
public record ResumableUpload(String bucket, String objectName, String contentType,
        Map<String, String> customerEncryption, Map<String, String> metadata,
        GcsObjectMeta systemMetadata, GcsObjectPreconditions preconditions,
        byte[] data, Long totalSize, long lastTouchedMillis) {}
