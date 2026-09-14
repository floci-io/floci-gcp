package io.floci.gcp.services.bigquery.model;

/**
 * The {@code updateMode} query parameter on {@code datasets.update} and
 * {@code datasets.patch}, which selects whether a write touches the dataset's
 * metadata, its ACL, or both.
 *
 * <p>Without it a write always covers both, so a client updating only
 * {@code friendlyName} has to resend {@code access[]} or lose it. That is the
 * problem the parameter exists to solve, and it only bites once {@code access[]}
 * is modelled at all.
 *
 * @see <a href="https://cloud.google.com/bigquery/docs/reference/rest/v2/datasets/update">
 *      datasets.update</a>
 */
public enum UpdateMode {

    /** Unset. The API treats this as {@link #UPDATE_FULL}. */
    UPDATE_MODE_UNSPECIFIED,

    /** friendlyName, description, labels and the rest. Leaves the ACL alone. */
    UPDATE_METADATA,

    /** {@code access[]} only. Leaves metadata alone. */
    UPDATE_ACL,

    /** Both. The default. */
    UPDATE_FULL;

    /** Parse the query-parameter spelling, defaulting to {@link #UPDATE_FULL}. */
    public static UpdateMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return UPDATE_FULL;
        }
        try {
            UpdateMode parsed = valueOf(raw.trim().toUpperCase());
            return parsed == UPDATE_MODE_UNSPECIFIED ? UPDATE_FULL : parsed;
        } catch (IllegalArgumentException unknown) {
            // The real API rejects an unknown value rather than guessing.
            throw io.floci.gcp.core.common.GcpException
                    .invalidArgument("Invalid value for updateMode: " + raw)
                    .withReason("invalid");
        }
    }

    public boolean touchesMetadata() {
        return this == UPDATE_METADATA || this == UPDATE_FULL;
    }

    public boolean touchesAcl() {
        return this == UPDATE_ACL || this == UPDATE_FULL;
    }
}
