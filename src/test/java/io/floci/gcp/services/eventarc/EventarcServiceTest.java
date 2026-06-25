package io.floci.gcp.services.eventarc;

import com.google.cloud.eventarc.v1.Trigger;
import com.google.cloud.eventarc.v1.ListTriggersResponse;
import com.google.cloud.eventarc.v1.Destination;
import com.google.cloud.eventarc.v1.HttpEndpoint;
import com.google.cloud.eventarc.v1.EventFilter;
import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import com.google.protobuf.Message;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.cloudrun.CloudRunUrlService;
import io.floci.gcp.services.operations.LongRunningOperationsService;
import io.floci.gcp.services.pubsub.model.StoredMessage;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class EventarcServiceTest {

    private EventarcService service;
    private LongRunningOperationsService operations;
    private EmulatorConfig config;
    private CloudRunUrlService cloudRunUrlService;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        operations = mock(LongRunningOperationsService.class);
        when(operations.done(anyString(), any(Message.class), any(Message.class)))
                .thenAnswer(invocation -> Operation.newBuilder()
                        .setName(invocation.getArgument(0, String.class) + "/operations/test-op")
                        .setDone(true)
                        .setResponse(Any.pack(invocation.getArgument(1, Message.class)))
                        .setMetadata(Any.pack(invocation.getArgument(2, Message.class)))
                        .build());
        when(operations.doneTransient(anyString(), any(Message.class), any(Message.class)))
                .thenAnswer(invocation -> Operation.newBuilder()
                        .setName(invocation.getArgument(0, String.class) + "/operations/test-op")
                        .setDone(true)
                        .setResponse(Any.pack(invocation.getArgument(1, Message.class)))
                        .setMetadata(Any.pack(invocation.getArgument(2, Message.class)))
                        .build());

        config = mock(EmulatorConfig.class);
        when(config.defaultProjectId()).thenReturn("p1");

        cloudRunUrlService = mock(CloudRunUrlService.class);
        httpClient = mock(HttpClient.class);

        service = new EventarcService(new InMemoryStorage<>(), operations, config, cloudRunUrlService, httpClient);
    }

    @Test
    void createAndGetTriggerSuccess() {
        String body = "{\n" +
                "  \"eventFilters\": [\n" +
                "    { \"attribute\": \"type\", \"value\": \"google.cloud.storage.object.v1.finalized\" },\n" +
                "    { \"attribute\": \"bucket\", \"value\": \"my-bucket\" }\n" +
                "  ],\n" +
                "  \"destination\": {\n" +
                "    \"httpEndpoint\": { \"uri\": \"http://example.com/endpoint\" }\n" +
                "  }\n" +
                "}";

        Operation op = service.createTrigger("p1", "us-central1", "t1", body, false);
        assertTrue(op.getDone());

        Trigger trigger = service.getTrigger("projects/p1/locations/us-central1/triggers/t1");
        assertEquals("projects/p1/locations/us-central1/triggers/t1", trigger.getName());
        assertEquals("http://example.com/endpoint", trigger.getDestination().getHttpEndpoint().getUri());
        assertEquals(2, trigger.getEventFiltersCount());
    }

    @Test
    void duplicateCreateThrowsException() {
        String body = "{}";
        service.createTrigger("p1", "us-central1", "t1", body, false);

        assertThrows(GcpException.class, () ->
                service.createTrigger("p1", "us-central1", "t1", body, false));
    }

    @Test
    void listTriggersFiltersByProjectAndLocation() {
        String body = "{}";
        service.createTrigger("p1", "us-central1", "t1", body, false);
        service.createTrigger("p1", "us-central1", "t2", body, false);
        service.createTrigger("p2", "us-central1", "t3", body, false);
        service.createTrigger("p1", "europe-west1", "t4", body, false);

        ListTriggersResponse response = service.listTriggers("p1", "us-central1", 10, null);
        assertEquals(2, response.getTriggersCount());
        assertEquals("projects/p1/locations/us-central1/triggers/t1", response.getTriggers(0).getName());
        assertEquals("projects/p1/locations/us-central1/triggers/t2", response.getTriggers(1).getName());
    }

    @Test
    void updateTriggerUpdatesFields() {
        String createBody = "{\n" +
                "  \"destination\": {\n" +
                "    \"httpEndpoint\": { \"uri\": \"http://example.com/old\" }\n" +
                "  }\n" +
                "}";
        service.createTrigger("p1", "us-central1", "t1", createBody, false);

        String updateBody = "{\n" +
                "  \"destination\": {\n" +
                "    \"httpEndpoint\": { \"uri\": \"http://example.com/new\" }\n" +
                "  }\n" +
                "}";
        service.updateTrigger("projects/p1/locations/us-central1/triggers/t1", updateBody, "destination", false, false);

        Trigger trigger = service.getTrigger("projects/p1/locations/us-central1/triggers/t1");
        assertEquals("http://example.com/new", trigger.getDestination().getHttpEndpoint().getUri());
    }

    @Test
    void deleteTriggerRemovesIt() {
        service.createTrigger("p1", "us-central1", "t1", "{}", false);
        String name = "projects/p1/locations/us-central1/triggers/t1";

        service.deleteTrigger(name, false, false);
        assertThrows(GcpException.class, () -> service.getTrigger(name));
    }

    @Test
    void onPubSubPublishDeliversMatchingEvent() {
        String body = "{\n" +
                "  \"eventFilters\": [\n" +
                "    { \"attribute\": \"type\", \"value\": \"google.cloud.pubsub.topic.v1.messagePublished\" },\n" +
                "    { \"attribute\": \"topic\", \"value\": \"my-topic\" }\n" +
                "  ],\n" +
                "  \"destination\": {\n" +
                "    \"httpEndpoint\": { \"uri\": \"http://example.com/pubsub-receiver\" }\n" +
                "  }\n" +
                "}";
        service.createTrigger("p1", "us-central1", "t1", body, false);

        StoredMessage msg = new StoredMessage();
        msg.setMessageId("msg-123");
        msg.setData("hello world".getBytes());
        msg.setPublishTime("2026-06-25T12:00:00Z");

        CompletableFuture<HttpResponse<Object>> future = CompletableFuture.completedFuture(mock(HttpResponse.class));
        when(httpClient.sendAsync(any(HttpRequest.class), any())).thenReturn(future);

        service.onPubSubPublish("projects/p1/topics/my-topic", msg);

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).sendAsync(requestCaptor.capture(), any());

        HttpRequest request = requestCaptor.getValue();
        assertEquals("POST", request.method());
        assertEquals("http://example.com/pubsub-receiver", request.uri().toString());
        assertEquals("msg-123", request.headers().firstValue("ce-id").orElse(""));
        assertEquals("google.cloud.pubsub.topic.v1.messagePublished", request.headers().firstValue("ce-type").orElse(""));
        assertEquals("//pubsub.googleapis.com/projects/p1/topics/my-topic", request.headers().firstValue("ce-source").orElse(""));
    }

    @Test
    void onGcsEventDeliversMatchingEvent() {
        String body = "{\n" +
                "  \"eventFilters\": [\n" +
                "    { \"attribute\": \"type\", \"value\": \"google.cloud.storage.object.v1.finalized\" },\n" +
                "    { \"attribute\": \"bucket\", \"value\": \"my-bucket\" }\n" +
                "  ],\n" +
                "  \"destination\": {\n" +
                "    \"httpEndpoint\": { \"uri\": \"http://example.com/gcs-receiver\" }\n" +
                "  }\n" +
                "}";
        service.createTrigger("p1", "us-central1", "t1", body, false);

        GcsObjectMeta meta = new GcsObjectMeta();
        meta.setBucket("my-bucket");
        meta.setName("hello.txt");
        meta.setUpdated("2026-06-25T12:00:00Z");

        CompletableFuture<HttpResponse<Object>> future = CompletableFuture.completedFuture(mock(HttpResponse.class));
        when(httpClient.sendAsync(any(HttpRequest.class), any())).thenReturn(future);

        service.onGcsEvent("my-bucket", "hello.txt", meta, "google.cloud.storage.object.v1.finalized");

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).sendAsync(requestCaptor.capture(), any());

        HttpRequest request = requestCaptor.getValue();
        assertEquals("POST", request.method());
        assertEquals("http://example.com/gcs-receiver", request.uri().toString());
        assertEquals("google.cloud.storage.object.v1.finalized", request.headers().firstValue("ce-type").orElse(""));
        assertEquals("//storage.googleapis.com/projects/_/buckets/my-bucket", request.headers().firstValue("ce-source").orElse(""));
    }
}
