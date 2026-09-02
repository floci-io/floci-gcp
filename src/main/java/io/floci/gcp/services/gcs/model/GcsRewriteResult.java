package io.floci.gcp.services.gcs.model;

/** Outcome of one {@code objects.rewrite} call. {@code meta} is set only once {@code done}. */
public record GcsRewriteResult(boolean done, String rewriteToken, long totalBytesRewritten,
        long objectSize, GcsObjectMeta meta) {}
