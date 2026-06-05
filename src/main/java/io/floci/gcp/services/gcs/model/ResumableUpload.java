package io.floci.gcp.services.gcs.model;

import java.util.Map;

public record ResumableUpload(String bucket, String objectName, String contentType,
        Map<String, String> customerEncryption, byte[] data) {}
