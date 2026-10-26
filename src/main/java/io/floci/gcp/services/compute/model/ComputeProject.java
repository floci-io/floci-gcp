package io.floci.gcp.services.compute.model;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.LinkedHashMap;
import java.util.Map;

/** One checkpoint includes resources, operations and request deduplication records. */
@RegisterForReflection
public class ComputeProject {
    public long sequence = 1000;
    public Map<String, ObjectNode> resources = new LinkedHashMap<>();
    public Map<String, ComputeOperation> operations = new LinkedHashMap<>();
    public Map<String, java.util.List<ObjectNode>> endpoints = new LinkedHashMap<>();
    public Map<String, String> requests = new LinkedHashMap<>();
    public String creationTimestamp;
    public ObjectNode commonInstanceMetadata;
    /**
     * The name and family of the project image each disk was created from, by disk key: what the image put on the disk
     * (its container image, whether it ships cloud-init) stays with the disk after the image is deleted.
     */
    public Map<String, ObjectNode> diskImages = new LinkedHashMap<>();
}
