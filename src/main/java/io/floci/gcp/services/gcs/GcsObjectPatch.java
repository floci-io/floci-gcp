package io.floci.gcp.services.gcs;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

record GcsObjectPatch(
        Map<String, Object> fields,
        Map<String, String> metadataUpdates,
        Set<String> metadataRemovals) {

    GcsObjectPatch {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        metadataUpdates = Collections.unmodifiableMap(new LinkedHashMap<>(metadataUpdates));
        metadataRemovals = Collections.unmodifiableSet(new LinkedHashSet<>(metadataRemovals));
    }
}
