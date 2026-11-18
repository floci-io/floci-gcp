package io.floci.gcp.services.cloudlogging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.logging.v2.LogExclusion;
import com.google.logging.v2.LogSink;
import com.google.pubsub.v1.PubsubMessage;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ProjectRegistry;
import io.floci.gcp.services.cloudlogging.model.StoredLogEntry;
import io.floci.gcp.services.iam.IamPrincipal;
import io.floci.gcp.services.iam.IamResource;
import io.floci.gcp.services.iam.authorization.IamAuthorizationService;
import io.floci.gcp.services.iam.authorization.IamPermissionCheck;
import io.floci.gcp.services.pubsub.PubSubIamAuthorizationAdapter;
import io.floci.gcp.services.pubsub.PubSubService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CloudLoggingPubSubExporterServiceTest {
    private static final String TOPIC = "projects/test-project/topics/logs";
    private static final String WRITER = "serviceAccount:service-1@gcp-sa-logging.iam.gserviceaccount.com";

    private PubSubService pubsub;
    private IamAuthorizationService authorization;
    private EmulatorConfig config;
    private CloudLoggingPubSubExporter exporter;
    private final AtomicBoolean policyLocksHeld = new AtomicBoolean();

    @BeforeEach
    void setUp() {
        pubsub = mock(PubSubService.class);
        authorization = mock(IamAuthorizationService.class);
        when(authorization.withPolicyLocks(any(IamResource.class), any())).thenAnswer(invocation -> {
            policyLocksHeld.set(true);
            try {
                return invocation.<Supplier<?>>getArgument(1).get();
            } finally {
                policyLocksHeld.set(false);
            }
        });
        PubSubIamAuthorizationAdapter adapter = mock(PubSubIamAuthorizationAdapter.class);
        when(adapter.requireResource(TOPIC)).thenReturn(IamResource.projectChild("pubsub.googleapis.com", "Topic", TOPIC, TOPIC));
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().pubsub().enabled()).thenReturn(true);
        exporter = new CloudLoggingPubSubExporter(pubsub, authorization, adapter, mock(ProjectRegistry.class), config);
    }

    @Test
    void eachRoutedEntryIsOneMessageWithTheEntryJsonAndItsTimestampAttribute() throws Exception {
        LogSink sink = sink().setFilter("severity>=WARNING")
                .addExclusions(LogExclusion.newBuilder().setName("noise").setFilter("textPayload=\"noise\""))
                .build();

        exporter.export("projects/test-project", sink, List.of(
                entry("first", "WARNING", "2026-11-17T10:00:00Z"),
                entry("filtered", "INFO", "2026-11-17T10:00:01Z"),
                entry("noise", "ERROR", "2026-11-17T10:00:02Z"),
                entry("second", "ERROR", "2026-11-17T10:00:03.250Z")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PubsubMessage>> published = ArgumentCaptor.forClass(List.class);
        verify(pubsub).publish(eq(TOPIC), published.capture());
        List<PubsubMessage> messages = published.getValue();
        assertEquals(2, messages.size());
        JsonNode first = new ObjectMapper().readTree(messages.get(0).getData().toStringUtf8());
        assertEquals("first", first.path("textPayload").asText());
        assertEquals("projects/test-project/logs/app", first.path("logName").asText());
        assertEquals("WARNING", first.path("severity").asText());
        assertEquals("2026-11-17T10:00:00Z", messages.get(0).getAttributesOrThrow("logging.googleapis.com/timestamp"));
        assertEquals("2026-11-17T10:00:03.250Z", messages.get(1).getAttributesOrThrow("logging.googleapis.com/timestamp"));
        assertEquals(1, messages.get(0).getAttributesCount());
        verify(authorization).authorizeServicePrincipal(eq(new IamPrincipal(WRITER)),
                argThat((IamPermissionCheck check) -> check.permission().equals("pubsub.topics.publish")
                        && check.resource().name().equals(TOPIC)));
    }

    @Test
    void theWriterCheckAndThePublicationRunUnderTheTopicPolicyLocks() {
        doAnswer(invocation -> {
            assertTrue(policyLocksHeld.get(), "writer checked outside the policy locks");
            return null;
        }).when(authorization).authorizeServicePrincipal(any(), any());
        when(pubsub.publish(eq(TOPIC), anyList())).thenAnswer(invocation -> {
            assertTrue(policyLocksHeld.get(), "published outside the policy locks");
            return List.of("1");
        });

        exporter.export("projects/test-project", sink().build(), List.of(entry("kept", "ERROR", "2026-11-17T10:00:00Z")));

        verify(authorization).withPolicyLocks(argThat((IamResource resource) -> resource.name().equals(TOPIC)), any());
        verify(pubsub).publish(eq(TOPIC), anyList());
    }

    @Test
    void entriesOfAnotherParentOrNoMatchPublishNothingAndSkipTheChecks() {
        when(config.services().pubsub().enabled()).thenReturn(false);
        StoredLogEntry foreign = entry("foreign", "ERROR", "2026-11-17T10:00:00Z");
        foreign.setLogName("projects/other-project/logs/app");

        exporter.export("projects/test-project", sink().setFilter("severity>=ERROR").build(),
                List.of(entry("quiet", "INFO", "2026-11-17T10:00:00Z"), foreign));

        verifyNoInteractions(pubsub, authorization);
    }

    @Test
    void disabledPubSubAndDeniedWritersFailWithoutPublishing() {
        LogSink sink = sink().build();
        List<StoredLogEntry> entries = List.of(entry("kept", "ERROR", "2026-11-17T10:00:00Z"));
        when(config.services().pubsub().enabled()).thenReturn(false);
        assertThrows(GcpException.class, () -> exporter.export("projects/test-project", sink, entries));

        when(config.services().pubsub().enabled()).thenReturn(true);
        doThrow(GcpException.permissionDenied("denied")).when(authorization).authorizeServicePrincipal(any(), any());
        assertThrows(GcpException.class, () -> exporter.export("projects/test-project", sink, entries));
        verifyNoInteractions(pubsub);
    }

    private static LogSink.Builder sink() {
        return LogSink.newBuilder().setName("to-topic").setDestination("pubsub.googleapis.com/" + TOPIC)
                .setWriterIdentity(WRITER);
    }

    private static StoredLogEntry entry(String text, String severity, String timestamp) {
        StoredLogEntry entry = new StoredLogEntry();
        entry.setLogName("projects/test-project/logs/app");
        entry.setTextPayload(text);
        entry.setSeverity(severity);
        entry.setTimestamp(timestamp);
        entry.setReceiveTimestamp(timestamp);
        return entry;
    }
}
