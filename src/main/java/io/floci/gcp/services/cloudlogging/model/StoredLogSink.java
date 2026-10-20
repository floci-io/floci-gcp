package io.floci.gcp.services.cloudlogging.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record StoredLogSink(String json, boolean uniqueWriterIdentity) {}
