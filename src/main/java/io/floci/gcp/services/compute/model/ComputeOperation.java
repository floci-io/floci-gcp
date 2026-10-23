package io.floci.gcp.services.compute.model;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class ComputeOperation {
    public ObjectNode response;
    public long startedAt;
    public long readyAt;
    public boolean deleted;
    /** True while the instance containers this operation starts or stops gate its completion. */
    public boolean awaitRuntime;
    public Map<String, String> finalStates = new LinkedHashMap<>();
}
