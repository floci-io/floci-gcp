package io.floci.gcp.services.iam.model;

import com.fasterxml.jackson.annotation.JsonInclude;
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
    // proto3 JSON objects of google.iam.v1.AuditConfig; null when the policy has none.
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<Map<String, Object>> auditConfigs;

    public StoredPolicy() {}

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public List<Map<String, Object>> getBindings() { return bindings; }
    public void setBindings(List<Map<String, Object>> bindings) { this.bindings = bindings; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }

    public List<Map<String, Object>> getAuditConfigs() { return auditConfigs; }
    public void setAuditConfigs(List<Map<String, Object>> auditConfigs) { this.auditConfigs = auditConfigs; }

    /** A deep copy, so a caller holding a read policy cannot change the stored one without a write. */
    @SuppressWarnings("unchecked")
    public StoredPolicy copy() {
        StoredPolicy copy = new StoredPolicy();
        copy.version = version;
        copy.etag = etag;
        copy.bindings = bindings == null ? null : (List<Map<String, Object>>) deepCopy(bindings);
        copy.auditConfigs = auditConfigs == null ? null : (List<Map<String, Object>>) deepCopy(auditConfigs);
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
