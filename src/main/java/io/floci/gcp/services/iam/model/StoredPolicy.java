package io.floci.gcp.services.iam.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class StoredPolicy {

    private int version = 1;
    private List<Map<String, Object>> bindings = new ArrayList<>();
    // Empty means "no etag provided"; IamService assigns one on write. Reads of an
    // unset policy surface GCP's well-known empty-policy etag "ACAB" instead.
    private String etag = "";

    public StoredPolicy() {}

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public List<Map<String, Object>> getBindings() { return bindings; }
    public void setBindings(List<Map<String, Object>> bindings) { this.bindings = bindings; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }

    /** A deep copy, so a caller holding a read policy cannot change the stored one without a write. */
    @SuppressWarnings("unchecked")
    public StoredPolicy copy() {
        StoredPolicy copy = new StoredPolicy();
        copy.version = version;
        copy.etag = etag;
        copy.bindings = bindings == null ? null : (List<Map<String, Object>>) deepCopy(bindings);
        return copy;
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, entry) -> copy.put(String.valueOf(key), deepCopy(entry)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(entry -> copy.add(deepCopy(entry)));
            return copy;
        }
        return value;
    }
}
