package io.floci.gcp.services.gcs.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StoredNotification {

    private String kind = "storage#notification";
    private String id;
    private String selfLink;
    private String topic;
    private String payloadFormat = "JSON_API_V1";
    private List<String> eventTypes;
    private Map<String, String> customAttributes;
    private String objectNamePrefix;

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSelfLink() { return selfLink; }
    public void setSelfLink(String selfLink) { this.selfLink = selfLink; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public String getPayloadFormat() { return payloadFormat; }
    public void setPayloadFormat(String payloadFormat) { this.payloadFormat = payloadFormat; }

    public List<String> getEventTypes() { return eventTypes; }
    public void setEventTypes(List<String> eventTypes) { this.eventTypes = eventTypes; }

    public Map<String, String> getCustomAttributes() { return customAttributes; }
    public void setCustomAttributes(Map<String, String> customAttributes) { this.customAttributes = customAttributes; }

    public String getObjectNamePrefix() { return objectNamePrefix; }
    public void setObjectNamePrefix(String objectNamePrefix) { this.objectNamePrefix = objectNamePrefix; }
}
