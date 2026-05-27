package io.floci.gcp.services.secretmanager.model;


import java.util.Map;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection

public class StoredSecret {

    private String name;
    private String createTime;
    private String replicationType;
    private Map<String, String> labels;

    public StoredSecret() {}

    public StoredSecret(String name, String createTime, String replicationType) {
        this.name = name;
        this.createTime = createTime;
        this.replicationType = replicationType;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }

    public String getReplicationType() { return replicationType; }
    public void setReplicationType(String replicationType) { this.replicationType = replicationType; }

    public Map<String, String> getLabels() { return labels; }
    public void setLabels(Map<String, String> labels) { this.labels = labels; }
}
