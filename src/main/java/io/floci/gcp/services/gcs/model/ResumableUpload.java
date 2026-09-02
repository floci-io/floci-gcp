package io.floci.gcp.services.gcs.model;

import java.util.Map;

/**
 * An in-flight REST resumable upload session.
 *
 * <p>{@code lastTouchedMillis} is wall-clock {@code System.currentTimeMillis()} from the last
 * time the session was created or advanced by a chunk. It exists so abandoned sessions can be
 * evicted instead of holding their buffered bytes for the lifetime of the process; nothing on
 * the wire derives from it.
 */
public record ResumableUpload(String bucket, String objectName, String contentType,
        Map<String, String> customerEncryption, Map<String, String> metadata,
        GcsObjectPreconditions preconditions, byte[] data, Long totalSize,
        long lastTouchedMillis) {}
