package io.floci.gcp.services.gcs.model;

public record ResumableChunkOutcome(GcsObjectMeta completed, long receivedLength) {

    public static ResumableChunkOutcome completed(GcsObjectMeta meta) {
        return new ResumableChunkOutcome(meta, 0);
    }

    public static ResumableChunkOutcome incomplete(long receivedLength) {
        return new ResumableChunkOutcome(null, receivedLength);
    }
}
