package io.floci.gcp.services.cloudlogging.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record StoredLogMetric(String json) {}
