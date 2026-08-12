package io.floci.gcp.services.gcs.model;

public record GcsObjectDownload(GcsObjectMeta meta, byte[] data) {}
