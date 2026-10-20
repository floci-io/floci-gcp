package io.floci.gcp.services.cloudlogging;

import com.google.logging.v2.LogSink;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.ProjectRegistry;
import io.floci.gcp.services.cloudlogging.model.StoredLogEntry;
import io.floci.gcp.services.gcs.GcsService;
import io.floci.gcp.services.gcs.model.GcsObjectPreconditions;
import io.floci.gcp.services.iam.GcsIamAuthorizationService;
import io.floci.gcp.services.iam.GcsIamAuthorizationService.OverwriteAuthorization;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CloudLoggingGcsExporterServiceTest {
    @Test
    void publicationAlwaysRequiresAnAbsentDestinationObject() {
        GcsService storage = mock(GcsService.class);
        GcsIamAuthorizationService authorization = mock(GcsIamAuthorizationService.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().gcs().enabled()).thenReturn(true);
        when(config.baseUrl()).thenReturn("http://localhost:4588");
        doAnswer(invocation -> {
            Function<OverwriteAuthorization, Object> publish = invocation.getArgument(3);
            return publish.apply(new OverwriteAuthorization(false));
        }).when(authorization).authorizeServiceObjectCreate(any(), eq("exports"), anyString(), any());
        CloudLoggingGcsExporter exporter = new CloudLoggingGcsExporter(storage, authorization,
                mock(ProjectRegistry.class), config);
        StoredLogEntry entry = new StoredLogEntry();
        entry.setLogName("projects/test-project/logs/events");
        entry.setTextPayload("exported");
        entry.setTimestamp("2026-10-20T00:00:00Z");
        entry.setReceiveTimestamp("2026-10-20T00:00:00Z");
        LogSink sink = LogSink.newBuilder().setName("events").setDestination("storage.googleapis.com/exports")
                .setWriterIdentity("serviceAccount:logging@example.test").build();

        exporter.export("projects/test-project", sink, List.of(entry));

        ArgumentCaptor<GcsObjectPreconditions> preconditions = ArgumentCaptor.forClass(GcsObjectPreconditions.class);
        verify(storage).putObject(eq("exports"), anyString(), eq("application/json"), any(byte[].class),
                preconditions.capture(), eq("http://localhost:4588"), any(Runnable.class));
        assertEquals(new GcsObjectPreconditions(0L, null, null, null), preconditions.getValue());
    }
}
