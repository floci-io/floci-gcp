package io.floci.gcp.services.gcs.model;

public record GcsComposeSource(String name, String generation, Long ifGenerationMatch) {}
