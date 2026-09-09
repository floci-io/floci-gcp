package io.floci.gcp.services.gcs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Map;
import java.util.TreeMap;

@RegisterForReflection
public class GcsMultipartUpload {
    public String id;
    public String bucket;
    public String object;
    public String contentType;
    public String initiated;
    public Map<String, String> metadata;
    public Map<Integer, Part> parts = new TreeMap<>();
    @RegisterForReflection
    public record Part(int number, String etag, String modified, byte[] data) {}
}
