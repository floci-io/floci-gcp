package io.floci.gcp.services.iam.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
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
}
