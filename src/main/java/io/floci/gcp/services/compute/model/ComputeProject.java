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
    public Map<String, String> requests = new LinkedHashMap<>();
}
