package io.floci.gcp.services.cloudkms.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class StoredKeyRing {

    private String name;
    private String createTime;

    public StoredKeyRing() {}

    public StoredKeyRing(String name, String createTime) {
        this.name = name;
        this.createTime = createTime;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }
}