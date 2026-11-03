package io.floci.gcp.services.gcs;

/** Rolls the names under a delimiter into their common prefix, as the GCS XML listings do. */
final class GcsXmlDelimiterRollup {

    private GcsXmlDelimiterRollup() {
    }

    /**
     * The common prefix {@code name} rolls up into, or null when there is no delimiter or the
     * name has none after the listing prefix and stays its own entry.
     */
    static String commonPrefix(String name, String prefix, String delimiter) {
        if (delimiter == null) {
            return null;
        }
        String rest = name.substring(prefix.length());
        int idx = rest.indexOf(delimiter);
        if (idx < 0) {
            return null;
        }
        return prefix + rest.substring(0, idx + delimiter.length());
    }
}
