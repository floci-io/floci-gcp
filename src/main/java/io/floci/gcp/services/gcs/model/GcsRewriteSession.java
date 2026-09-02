package io.floci.gcp.services.gcs.model;

/**
 * State for a multi-call {@code objects.rewrite}.
 *
 * <p>GCS answers a rewrite that cannot finish within {@code maxBytesRewrittenPerCall} with
 * {@code done: false} and a {@code rewriteToken}; the client calls back with that token until the
 * response comes back done. The destination object does not become visible until then.
 *
 * <p>The emulator holds no partial bytes, the copy is performed once, on the call that completes
 * the rewrite, so this only tracks how far the protocol has advanced. That is enough for a client
 * to take the same code path it would against real GCS, which is what the emulator exists to
 * exercise.
 */
public record GcsRewriteSession(String srcBucket, String srcObject, String srcGeneration,
        String dstBucket, String dstObject, long objectSize, long bytesRewritten,
        GcsObjectPreconditions preconditions) {

    public GcsRewriteSession advancedBy(long bytes) {
        return new GcsRewriteSession(srcBucket, srcObject, srcGeneration, dstBucket, dstObject,
                objectSize, Math.min(objectSize, bytesRewritten + bytes), preconditions);
    }

    public boolean complete() {
        return bytesRewritten >= objectSize;
    }

    public boolean matches(String srcBucket, String srcObject, String dstBucket, String dstObject) {
        return this.srcBucket.equals(srcBucket) && this.srcObject.equals(srcObject)
                && this.dstBucket.equals(dstBucket) && this.dstObject.equals(dstObject);
    }

    /**
     * Whether the source is still the generation the rewrite started against. The token records
     * it so that a source overwritten between calls cannot be silently copied in place of the one
     * the progress was measured on, which would report totals for one generation and copy another.
     */
    public boolean sourceGenerationUnchanged(String currentGeneration) {
        return srcGeneration == null || srcGeneration.equals(currentGeneration);
    }
}
