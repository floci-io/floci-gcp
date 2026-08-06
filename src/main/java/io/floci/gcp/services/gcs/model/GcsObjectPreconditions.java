package io.floci.gcp.services.gcs.model;

public record GcsObjectPreconditions(Long ifGenerationMatch, Long ifGenerationNotMatch,
        Long ifMetagenerationMatch, Long ifMetagenerationNotMatch) {

    public static final GcsObjectPreconditions NONE = new GcsObjectPreconditions(null, null, null, null);

    public boolean isEmpty() {
        return ifGenerationMatch == null && ifGenerationNotMatch == null
                && ifMetagenerationMatch == null && ifMetagenerationNotMatch == null;
    }
}
