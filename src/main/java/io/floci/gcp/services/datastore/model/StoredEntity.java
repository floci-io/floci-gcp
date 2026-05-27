package io.floci.gcp.services.datastore.model;


import java.util.Map;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection

public class StoredEntity {

    private String projectId;
    private String namespaceId;
    private String kind;
    private String keyName;
    private Long keyId;
    private long version;
    private String createTime;
    private String updateTime;
    private Map<String, StoredProperty> properties;

    public StoredEntity() {}

    public StoredEntity(String projectId, String namespaceId, String kind,
            String keyName, Long keyId,
            long version, String createTime, String updateTime,
            Map<String, StoredProperty> properties) {
        this.projectId = projectId;
        this.namespaceId = namespaceId;
        this.kind = kind;
        this.keyName = keyName;
        this.keyId = keyId;
        this.version = version;
        this.createTime = createTime;
        this.updateTime = updateTime;
        this.properties = properties;
    }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getNamespaceId() { return namespaceId; }
    public void setNamespaceId(String namespaceId) { this.namespaceId = namespaceId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getKeyName() { return keyName; }
    public void setKeyName(String keyName) { this.keyName = keyName; }
    public Long getKeyId() { return keyId; }
    public void setKeyId(Long keyId) { this.keyId = keyId; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }
    public String getUpdateTime() { return updateTime; }
    public void setUpdateTime(String updateTime) { this.updateTime = updateTime; }
    public Map<String, StoredProperty> getProperties() { return properties; }
    public void setProperties(Map<String, StoredProperty> properties) { this.properties = properties; }
}
