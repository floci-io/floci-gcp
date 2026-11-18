package io.floci.gcp.services.cloudmonitoring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.monitoring.v3.AlertPolicy;
import com.google.monitoring.v3.NotificationChannel;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import io.floci.gcp.core.common.ProjectNumbers;
import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.services.pubsub.PubSubService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends an alert's open or close notification to the policy's channels as a schema 1.2 packet.
 * Webhooks receive a POST (token auth carries its {@code auth_token} in the URL, basic auth an
 * {@code Authorization} header), Pub/Sub channels a message on their topic; the other types have no
 * delivery path in the emulator and are written to the log.
 */
@ApplicationScoped
public class AlertNotifier {
    private static final Logger LOG = Logger.getLogger(AlertNotifier.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    interface Webhook {
        void post(String url, String authorization, String body) throws Exception;
    }

    private final PubSubService pubsub;
    private final Webhook webhook;
    private final ObjectMapper json = new ObjectMapper();

    @Inject
    public AlertNotifier(PubSubService pubsub) {
        this(pubsub, httpWebhook());
    }

    AlertNotifier(PubSubService pubsub, Webhook webhook) {
        this.pubsub = pubsub;
        this.webhook = webhook;
    }

    void notify(AlertPolicy policy, AlertPolicy.Condition condition, Map<String, Object> alert, String observedValue,
            List<NotificationChannel> channels) {
        String body = packet(policy, condition, alert, observedValue);
        for (NotificationChannel channel : channels) {
            if (channel.hasEnabled() && !channel.getEnabled().getValue()) {
                continue;
            }
            if (channel.getVerificationStatus() == NotificationChannel.VerificationStatus.UNVERIFIED) {
                LOG.infof("Alert %s not sent to unverified channel %s", alert.get("name"), channel.getName());
                continue;
            }
            try {
                deliver(channel, body);
            } catch (Exception e) {
                LOG.warnf(e, "Alert %s could not be delivered to channel %s", alert.get("name"), channel.getName());
            }
        }
    }

    private void deliver(NotificationChannel channel, String body) throws Exception {
        Map<String, String> labels = channel.getLabelsMap();
        switch (channel.getType()) {
            case "webhook_tokenauth" -> webhook.post(labels.get("url"), null, body);
            case "webhook_basicauth" -> webhook.post(labels.get("url"), "Basic " + Base64.getEncoder().encodeToString(
                    (labels.getOrDefault("username", "") + ":" + labels.getOrDefault("password", ""))
                            .getBytes(StandardCharsets.UTF_8)), body);
            case "pubsub" -> pubsub.publish(labels.get("topic"), List.of(PubsubMessage.newBuilder()
                    .setData(ByteString.copyFromUtf8(body)).build()));
            default -> LOG.infof("Alert notification for %s channel %s: %s", channel.getType(), channel.getName(), body);
        }
    }

    @SuppressWarnings("unchecked")
    String packet(AlertPolicy policy, AlertPolicy.Condition condition, Map<String, Object> alert, String observedValue) {
        String name = (String) alert.get("name");
        String project = name.substring("projects/".length(), name.indexOf("/alerts/"));
        boolean open = "OPEN".equals(alert.get("state"));
        Map<String, Object> resource = (Map<String, Object>) alert.getOrDefault("resource", Map.of("type", "global"));
        Map<String, Object> metric = (Map<String, Object>) alert.getOrDefault("metric", Map.of());

        Map<String, Object> incident = new LinkedHashMap<>();
        incident.put("incident_id", name.substring(name.lastIndexOf('/') + 1));
        incident.put("scoping_project_id", project);
        incident.put("scoping_project_number", Long.parseLong(ProjectNumbers.of(project)));
        incident.put("url", "");
        incident.put("severity", severity(policy));
        incident.put("started_at", Instant.parse((String) alert.get("openTime")).getEpochSecond());
        incident.put("ended_at", open ? null : Instant.parse((String) alert.get("closeTime")).getEpochSecond());
        incident.put("state", open ? "open" : "closed");
        incident.put("resource_type_display_name", resource.get("type"));
        incident.put("resource", resource);
        incident.put("metric", metric);
        incident.put("metadata", Map.of("system_labels", Map.of(), "user_labels", Map.of()));
        incident.put("policy_name", policy.getDisplayName());
        incident.put("policy_user_labels", policy.getUserLabelsMap());
        incident.put("condition_name", condition.getDisplayName());
        incident.put("condition", parse(ProtoJson.print(condition)));
        if (condition.hasConditionThreshold()) {
            incident.put("threshold_value", String.valueOf(condition.getConditionThreshold().getThresholdValue()));
        }
        if (observedValue != null) {
            incident.put("observed_value", observedValue);
        }
        if (policy.hasDocumentation()) {
            Map<String, Object> documentation = new LinkedHashMap<>();
            documentation.put("content", policy.getDocumentation().getContent());
            documentation.put("mime_type", policy.getDocumentation().getMimeType());
            if (!policy.getDocumentation().getSubject().isEmpty()) {
                documentation.put("subject", policy.getDocumentation().getSubject());
            }
            incident.put("documentation", documentation);
        }
        incident.put("summary", summary(condition, metric, resource, open, observedValue));

        Map<String, Object> packet = new LinkedHashMap<>();
        packet.put("incident", incident);
        packet.put("version", "1.2");
        try {
            return json.writeValueAsString(packet);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unwritable notification", e);
        }
    }

    private static String severity(AlertPolicy policy) {
        return switch (policy.getSeverity()) {
            case CRITICAL -> "Critical";
            case ERROR -> "Error";
            case WARNING -> "Warning";
            default -> "no severity";
        };
    }

    private static String summary(AlertPolicy.Condition condition, Map<String, Object> metric,
            Map<String, Object> resource, boolean open, String observedValue) {
        String subject = metric.getOrDefault("type", condition.getDisplayName()) + " for " + resource.get("type")
                + " " + resource.getOrDefault("labels", Map.of());
        if (condition.hasConditionAbsent()) {
            return subject + (open ? " is absent" : " is reporting again") + ".";
        }
        return subject + (open ? " is above or below the threshold" : " returned to normal")
                + (observedValue == null ? "" : " with a value of " + observedValue) + ".";
    }

    private Map<String, Object> parse(String value) {
        try {
            return json.readValue(value, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Webhook httpWebhook() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return (url, authorization, body) -> {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (authorization != null) {
                request.header("Authorization", authorization);
            }
            HttpResponse<Void> response = client.send(request.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("Webhook answered HTTP " + response.statusCode());
            }
        };
    }
}
