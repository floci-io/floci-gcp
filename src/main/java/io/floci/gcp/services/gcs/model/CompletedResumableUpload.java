package io.floci.gcp.services.gcs.model;

public record CompletedResumableUpload(String bucket, String objectName, GcsObjectMeta meta) {}
