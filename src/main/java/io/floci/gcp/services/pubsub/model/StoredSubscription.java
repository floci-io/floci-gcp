package io.floci.gcp.services.pubsub.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StoredSubscription {

    private String name;
    private String topic;
    private int ackDeadlineSeconds = 10;
    private String filter;
    private String pushEndpoint;
    private boolean retainAckedMessages;
    private String messageRetentionDuration;
    private String deadLetterTopic;
    private int maxDeliveryAttempts;
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

    public String getFilter() { return filter; }
    public void setFilter(String filter) { this.filter = filter; }

    public String getPushEndpoint() { return pushEndpoint; }
    public void setPushEndpoint(String pushEndpoint) { this.pushEndpoint = pushEndpoint; }

    public boolean isRetainAckedMessages() { return retainAckedMessages; }
    public void setRetainAckedMessages(boolean retainAckedMessages) { this.retainAckedMessages = retainAckedMessages; }

    public String getMessageRetentionDuration() { return messageRetentionDuration; }
    public void setMessageRetentionDuration(String d) { this.messageRetentionDuration = d; }

    public String getDeadLetterTopic() { return deadLetterTopic; }
    public void setDeadLetterTopic(String deadLetterTopic) { this.deadLetterTopic = deadLetterTopic; }

    public int getMaxDeliveryAttempts() { return maxDeliveryAttempts; }
    public void setMaxDeliveryAttempts(int maxDeliveryAttempts) { this.maxDeliveryAttempts = maxDeliveryAttempts; }

    public boolean isDetached() { return detached; }
    public void setDetached(boolean detached) { this.detached = detached; }
}
