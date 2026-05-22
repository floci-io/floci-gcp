package io.floci.gcp.services.pubsub.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StoredSnapshot {

    private String name;
    private String topic;
    private String expireTime;
    private Map<String, String> labels;

    public StoredSnapshot() {}

    public StoredSnapshot(String name, String topic, String expireTime) {
        this.name = name;
        this.topic = topic;
        this.expireTime = expireTime;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public String getExpireTime() { return expireTime; }
    public void setExpireTime(String expireTime) { this.expireTime = expireTime; }

    public Map<String, String> getLabels() { return labels; }
    public void setLabels(Map<String, String> labels) { this.labels = labels; }
}
