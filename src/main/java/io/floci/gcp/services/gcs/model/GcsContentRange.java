package io.floci.gcp.services.gcs.model;

public record GcsContentRange(long start, long end, Long totalSize, boolean statusQuery) {}
