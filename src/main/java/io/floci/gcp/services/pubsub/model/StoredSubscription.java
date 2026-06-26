package io.floci.gcp.services.pubsub.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StoredSubscription {

    private String name;
    private String topic;
    private int ackDeadlineSeconds = 10;
    private Map<String, String> labels;
    private String filter;
    private String pushEndpoint;
    private boolean retainAckedMessages;
    private String messageRetentionDuration;
    private Map<String, Object> bigQueryConfig;
    private Map<String, Object> expirationPolicy;
    private Map<String, Object> retryPolicy;
    private String deadLetterTopic;
    private int maxDeliveryAttempts;
    private boolean enableMessageOrdering;
    private boolean enableExactlyOnceDelivery;
    private boolean detached;

    public StoredSubscription() {}

    public StoredSubscription(String name, String topic, int ackDeadlineSeconds) {
        this.name = name;
        this.topic = topic;
        this.ackDeadlineSeconds = ackDeadlineSeconds;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public int getAckDeadlineSeconds() { return ackDeadlineSeconds; }
    public void setAckDeadlineSeconds(int ackDeadlineSeconds) { this.ackDeadlineSeconds = ackDeadlineSeconds; }

    public Map<String, String> getLabels() { return labels; }
    public void setLabels(Map<String, String> labels) { this.labels = labels; }

    public String getFilter() { return filter; }
    public void setFilter(String filter) { this.filter = filter; }

    public String getPushEndpoint() { return pushEndpoint; }
    public void setPushEndpoint(String pushEndpoint) { this.pushEndpoint = pushEndpoint; }

    public boolean isRetainAckedMessages() { return retainAckedMessages; }
    public void setRetainAckedMessages(boolean retainAckedMessages) { this.retainAckedMessages = retainAckedMessages; }

    public String getMessageRetentionDuration() { return messageRetentionDuration; }
    public void setMessageRetentionDuration(String d) { this.messageRetentionDuration = d; }

    public Map<String, Object> getBigQueryConfig() { return bigQueryConfig; }
    public void setBigQueryConfig(Map<String, Object> bigQueryConfig) { this.bigQueryConfig = bigQueryConfig; }

    public Map<String, Object> getExpirationPolicy() { return expirationPolicy; }
    public void setExpirationPolicy(Map<String, Object> expirationPolicy) { this.expirationPolicy = expirationPolicy; }

    public Map<String, Object> getRetryPolicy() { return retryPolicy; }
    public void setRetryPolicy(Map<String, Object> retryPolicy) { this.retryPolicy = retryPolicy; }

    public String getDeadLetterTopic() { return deadLetterTopic; }
    public void setDeadLetterTopic(String deadLetterTopic) { this.deadLetterTopic = deadLetterTopic; }

    public int getMaxDeliveryAttempts() { return maxDeliveryAttempts; }
    public void setMaxDeliveryAttempts(int maxDeliveryAttempts) { this.maxDeliveryAttempts = maxDeliveryAttempts; }

    public boolean isEnableMessageOrdering() { return enableMessageOrdering; }
    public void setEnableMessageOrdering(boolean enableMessageOrdering) {
        this.enableMessageOrdering = enableMessageOrdering;
    }

    public boolean isEnableExactlyOnceDelivery() { return enableExactlyOnceDelivery; }
    public void setEnableExactlyOnceDelivery(boolean enableExactlyOnceDelivery) {
        this.enableExactlyOnceDelivery = enableExactlyOnceDelivery;
    }

    public boolean isDetached() { return detached; }
    public void setDetached(boolean detached) { this.detached = detached; }
}
