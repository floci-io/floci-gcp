package io.floci.gcp.services.cloudmonitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.Metric;
import com.google.api.MonitoredResource;
import com.google.monitoring.v3.AlertPolicy;
import com.google.monitoring.v3.ComparisonType;
import com.google.monitoring.v3.NotificationChannel;
import com.google.monitoring.v3.Point;
import com.google.monitoring.v3.TimeInterval;
import com.google.monitoring.v3.TimeSeries;
import com.google.monitoring.v3.TypedValue;
import com.google.protobuf.BoolValue;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import com.google.pubsub.v1.PubsubMessage;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.pubsub.PubSubService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AlertPolicyEvaluatorTest {
    private static final String PROJECT = "projects/test-project";
    private static final String CPU = "custom.googleapis.com/cpu";
    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");

    private final List<String> posts = new ArrayList<>();
    private final ObjectMapper json = new ObjectMapper();
    private CloudMonitoringService monitoring;
    private NotificationChannelService channels;
    private AlertPolicyService policies;
    private AlertService alerts;
    private PubSubService pubsub;
    private AlertPolicyEvaluator evaluator;
    private String hook;

    @BeforeEach
    void setUp() {
        Clock past = Clock.fixed(NOW.minusSeconds(3600), ZoneOffset.UTC);
        monitoring = new CloudMonitoringService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                Clock.fixed(NOW.plusSeconds(3600), ZoneOffset.UTC));
        channels = new NotificationChannelService(new InMemoryStorage<>(), null, past);
        policies = new AlertPolicyService(new InMemoryStorage<>(), channels, null, null, past);
        alerts = new AlertService(new InMemoryStorage<>(), null);
        pubsub = mock(PubSubService.class);
        AlertNotifier notifier = new AlertNotifier(pubsub, (url, authorization, body) -> posts.add(url + " " + authorization + " " + body));
        evaluator = new AlertPolicyEvaluator(policies, channels, monitoring, alerts, notifier, true, 30);
        hook = channels.create(PROJECT, NotificationChannel.newBuilder().setType("webhook_tokenauth")
                .putLabels("url", "http://hook.example/alert?auth_token=t").build()).getName();
    }

    @Test
    void aThresholdBreachOpensOneAlertAndRecoveryClosesItWithBothNotifications() throws Exception {
        AlertPolicy policy = policies.create(PROJECT, policy("CPU high", threshold(0.9, 0)).toBuilder()
                .addNotificationChannels(hook).setDocumentation(AlertPolicy.Documentation.newBuilder().setContent("Page")).build());
        write("vm-1", 0.95, NOW.minusSeconds(30));

        evaluator.tick(NOW);
        evaluator.tick(NOW.plusSeconds(5));

        List<Map<String, Object>> open = alertsList(null);
        assertEquals(1, open.size());
        assertEquals("OPEN", open.getFirst().get("state"));
        assertEquals(Map.of("name", policy.getName(), "displayName", "CPU high"), open.getFirst().get("policy"));
        assertEquals(Map.of("type", "gce_instance", "labels", Map.of("instance_id", "vm-1")), open.getFirst().get("resource"));
        assertEquals(1, posts.size());
        JsonNode opened = packet(0);
        assertEquals("1.2", opened.path("version").asText());
        assertEquals("open", opened.path("incident").path("state").asText());
        assertEquals("0.95", opened.path("incident").path("observed_value").asText());
        assertEquals("0.9", opened.path("incident").path("threshold_value").asText());
        assertEquals("CPU high", opened.path("incident").path("policy_name").asText());
        assertEquals("text/markdown", opened.path("incident").path("documentation").path("mime_type").asText());
        assertTrue(opened.path("incident").path("ended_at").isNull());
        assertEquals(NOW.getEpochSecond(), opened.path("incident").path("started_at").asLong());
        assertTrue(posts.getFirst().startsWith("http://hook.example/alert?auth_token=t null "));

        write("vm-1", 0.5, NOW.plusSeconds(30));
        evaluator.tick(NOW.plusSeconds(60));

        assertEquals("CLOSED", alertsList(null).getFirst().get("state"));
        assertEquals(2, posts.size());
        JsonNode closed = packet(1);
        assertEquals("closed", closed.path("incident").path("state").asText());
        assertEquals(NOW.plusSeconds(60).getEpochSecond(), closed.path("incident").path("ended_at").asLong());
    }

    @Test
    void theRetestWindowNeedsTheBreachToLastForTheDuration() {
        policies.create(PROJECT, policy("sustained", threshold(0.9, 120)));
        write("vm-1", 0.95, NOW.minusSeconds(60));
        write("vm-1", 0.97, NOW);
        evaluator.tick(NOW);
        assertTrue(alertsList(null).isEmpty());

        write("vm-1", 0.99, NOW.plusSeconds(60));
        evaluator.tick(NOW.plusSeconds(61));
        assertEquals(1, alertsList(null).size());
    }

    @Test
    void andNeedsEveryConditionWhileOrNeedsAny() {
        AlertPolicy.Condition cpu = threshold(0.9, 0);
        AlertPolicy.Condition low = threshold(0.1, 0).toBuilder().setDisplayName("low")
                .setConditionThreshold(threshold(0.1, 0).getConditionThreshold().toBuilder()
                        .setComparison(ComparisonType.COMPARISON_LT)).build();
        policies.create(PROJECT, policy("and", cpu).toBuilder().addConditions(low)
                .setCombiner(AlertPolicy.ConditionCombinerType.AND).build());
        policies.create(PROJECT, policy("or", cpu).toBuilder().addConditions(low)
                .setCombiner(AlertPolicy.ConditionCombinerType.OR).build());
        write("vm-1", 0.95, NOW.minusSeconds(30));

        evaluator.tick(NOW);

        List<Map<String, Object>> fired = alertsList(null);
        assertEquals(1, fired.size());
        assertEquals("or", ((Map<?, ?>) fired.getFirst().get("policy")).get("displayName"));
    }

    @Test
    void absenceFiresForASeriesThatStoppedReporting() {
        policies.create(PROJECT, policy("gone", AlertPolicy.Condition.newBuilder().setDisplayName("absent")
                .setConditionAbsent(AlertPolicy.Condition.MetricAbsence.newBuilder()
                        .setFilter("metric.type=\"" + CPU + "\"").setDuration(Duration.newBuilder().setSeconds(300)))
                .build()));
        write("vm-1", 0.5, NOW.minusSeconds(600));
        write("vm-2", 0.5, NOW.minusSeconds(60));

        evaluator.tick(NOW);

        List<Map<String, Object>> fired = alertsList(null);
        assertEquals(1, fired.size());
        assertEquals(Map.of("type", "gce_instance", "labels", Map.of("instance_id", "vm-1")), fired.getFirst().get("resource"));
    }

    @Test
    void pubSubChannelsGetThePacketAndUnverifiedOrDisabledChannelsGetNothing() throws Exception {
        String topic = channels.create(PROJECT, NotificationChannel.newBuilder().setType("pubsub")
                .putLabels("topic", "projects/test-project/topics/alerts").build()).getName();
        String sms = channels.create(PROJECT, NotificationChannel.newBuilder().setType("sms").build()).getName();
        String off = channels.create(PROJECT, NotificationChannel.newBuilder().setType("webhook_tokenauth")
                .putLabels("url", "http://off").setEnabled(BoolValue.of(false)).build()).getName();
        policies.create(PROJECT, policy("CPU", threshold(0.9, 0)).toBuilder()
                .addNotificationChannels(topic).addNotificationChannels(sms).addNotificationChannels(off).build());
        write("vm-1", 0.95, NOW.minusSeconds(30));

        evaluator.tick(NOW);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PubsubMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(pubsub).publish(eq("projects/test-project/topics/alerts"), messages.capture());
        assertEquals("open", json.readTree(messages.getValue().getFirst().getData().toStringUtf8())
                .path("incident").path("state").asText());
        assertTrue(posts.isEmpty());
    }

    @Test
    void missingDataKeepsAnAlertOpenUnlessTheConditionSaysInactive() {
        AlertPolicy.Condition inactive = threshold(0.9, 0).toBuilder().setConditionThreshold(threshold(0.9, 0)
                .getConditionThreshold().toBuilder().setDuration(Duration.newBuilder().setSeconds(60))
                .setEvaluationMissingData(AlertPolicy.Condition.EvaluationMissingData.EVALUATION_MISSING_DATA_INACTIVE)).build();
        policies.create(PROJECT, policy("keeps", threshold(0.9, 0)));
        policies.create(PROJECT, policy("closes", inactive));
        write("vm-1", 0.95, NOW.minusSeconds(60));
        write("vm-1", 0.96, NOW);
        evaluator.tick(NOW);
        assertEquals(2, alertsList("state=\"OPEN\"").size());

        evaluator.tick(NOW.plusSeconds(1800));

        List<Map<String, Object>> open = alertsList("state=\"OPEN\"");
        assertEquals(1, open.size());
        assertEquals("keeps", ((Map<?, ?>) open.getFirst().get("policy")).get("displayName"));
    }

    @Test
    void autoCloseEndsAnAlertThatOutlivesIt() {
        policies.create(PROJECT, policy("CPU", threshold(0.9, 0)).toBuilder().setAlertStrategy(
                AlertPolicy.AlertStrategy.newBuilder().setAutoClose(Duration.newBuilder().setSeconds(1800))).build());
        write("vm-1", 0.95, NOW.minusSeconds(30));
        evaluator.tick(NOW);

        evaluator.tick(NOW.plusSeconds(1801));

        assertEquals("CLOSED", alertsList(null).getFirst().get("state"));
    }

    @Test
    void disabledPoliciesAndUnsupportedConditionsProduceNothing() {
        policies.create(PROJECT, policy("off", threshold(0.9, 0)).toBuilder().setEnabled(BoolValue.of(false)).build());
        policies.create(PROJECT, policy("mql", AlertPolicy.Condition.newBuilder().setDisplayName("mql")
                .setConditionMonitoringQueryLanguage(AlertPolicy.Condition.MonitoringQueryLanguageCondition.newBuilder()
                        .setQuery("fetch gce_instance")).build()));
        write("vm-1", 0.95, NOW.minusSeconds(30));

        evaluator.tick(NOW);

        assertTrue(alertsList(null).isEmpty());
    }

    @Test
    void alertsListNewestFirstAndCanBeReadByName() {
        policies.create(PROJECT, policy("first", threshold(0.9, 0)));
        write("vm-1", 0.95, NOW.minusSeconds(30));
        evaluator.tick(NOW);
        policies.create(PROJECT, policy("second", threshold(0.9, 0)));
        evaluator.tick(NOW.plusSeconds(10));

        List<Map<String, Object>> listed = alertsList(null);
        assertEquals(List.of("second", "first"), listed.stream()
                .map(alert -> ((Map<?, ?>) alert.get("policy")).get("displayName")).toList());
        String name = (String) listed.getFirst().get("name");
        assertEquals(listed.getFirst(), alerts.getAlert(name));
        assertFalse(alerts.getAlert(name).containsKey(AlertService.KEY));
        assertEquals(2, alerts.listAlerts(PROJECT, null, "open_time", 0, null).get("totalSize"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> alertsList(String filter) {
        Object listed = alerts.listAlerts(PROJECT, filter, null, 0, null).get("alerts");
        return listed == null ? List.of() : (List<Map<String, Object>>) listed;
    }

    private JsonNode packet(int index) throws Exception {
        String post = posts.get(index);
        return json.readTree(post.substring(post.indexOf('{')));
    }

    private void write(String instance, double value, Instant at) {
        Timestamp end = Timestamp.newBuilder().setSeconds(at.getEpochSecond()).setNanos(at.getNano()).build();
        monitoring.createTimeSeries(PROJECT, List.of(TimeSeries.newBuilder()
                .setMetric(Metric.newBuilder().setType(CPU))
                .setResource(MonitoredResource.newBuilder().setType("gce_instance").putLabels("instance_id", instance))
                .addPoints(Point.newBuilder().setInterval(TimeInterval.newBuilder().setEndTime(end))
                        .setValue(TypedValue.newBuilder().setDoubleValue(value)))
                .build()));
    }

    private static AlertPolicy policy(String displayName, AlertPolicy.Condition condition) {
        return AlertPolicy.newBuilder().setDisplayName(displayName).addConditions(condition).build();
    }

    private static AlertPolicy.Condition threshold(double value, long durationSeconds) {
        return AlertPolicy.Condition.newBuilder().setDisplayName("cpu")
                .setConditionThreshold(AlertPolicy.Condition.MetricThreshold.newBuilder()
                        .setFilter("metric.type=\"" + CPU + "\"")
                        .setComparison(ComparisonType.COMPARISON_GT).setThresholdValue(value)
                        .setDuration(Duration.newBuilder().setSeconds(durationSeconds)))
                .build();
    }
}
