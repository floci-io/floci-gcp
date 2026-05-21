package io.floci.gcp.services.firestore.model;

import java.util.Map;

public class StoredDocument {

    private String name;
    private String createTime;
    private String updateTime;
    private Map<String, StoredValue> fields;

    public StoredDocument() {}

    public StoredDocument(String name, String createTime, String updateTime, Map<String, StoredValue> fields) {
        this.name = name;
        this.createTime = createTime;
        this.updateTime = updateTime;
        this.fields = fields;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }

    public String getUpdateTime() { return updateTime; }
    public void setUpdateTime(String updateTime) { this.updateTime = updateTime; }

    public Map<String, StoredValue> getFields() { return fields; }
    public void setFields(Map<String, StoredValue> fields) { this.fields = fields; }
}
