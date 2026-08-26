package io.floci.gcp.services.gcs.model;

import java.util.Arrays;

public final class GcsStreamingUpload {

    private final GcsObjectMeta object;
    private final GcsObjectPreconditions preconditions;
    private final Long expectedSize;
    private final Integer expectedCrc32c;
    private final byte[] expectedMd5;
    private byte[] data = new byte[0];
    private GcsObjectMeta finalizedObject;

    public GcsStreamingUpload(GcsObjectMeta object, GcsObjectPreconditions preconditions,
            Long expectedSize, Integer expectedCrc32c, byte[] expectedMd5) {
        this.object = object;
        this.preconditions = preconditions;
        this.expectedSize = expectedSize;
        this.expectedCrc32c = expectedCrc32c;
        this.expectedMd5 = expectedMd5 != null ? expectedMd5.clone() : null;
    }

    public GcsObjectMeta object() {
        return object;
    }

    public GcsObjectPreconditions preconditions() {
        return preconditions;
    }

    public Long expectedSize() {
        return expectedSize;
    }

    public Integer expectedCrc32c() {
        return expectedCrc32c;
    }

    public byte[] expectedMd5() {
        return expectedMd5 != null ? expectedMd5.clone() : null;
    }

    public synchronized long size() {
        return data.length;
    }

    public synchronized byte[] data() {
        return data.clone();
    }

    public synchronized void append(long offset, byte[] chunk) {
        if (finalizedObject != null) {
            return;
        }
        if (offset < 0) {
            throw io.floci.gcp.core.common.GcpException.outOfRange(
                    "Write offset must not be negative: " + offset);
        }
        if (offset > data.length) {
            throw io.floci.gcp.core.common.GcpException.outOfRange(
                    "Write offset exceeds persisted size: " + offset);
        }
        int skip = (int) Math.min(data.length - offset, chunk.length);
        byte[] remaining = Arrays.copyOfRange(chunk, skip, chunk.length);
        if (remaining.length == 0) {
            return;
        }
        byte[] combined = Arrays.copyOf(data, data.length + remaining.length);
        System.arraycopy(remaining, 0, combined, data.length, remaining.length);
        data = combined;
    }

    public synchronized GcsObjectMeta finalizedObject() {
        return finalizedObject;
    }

    public synchronized void finalizeWith(GcsObjectMeta value) {
        finalizedObject = value;
        data = new byte[0];
    }
}
