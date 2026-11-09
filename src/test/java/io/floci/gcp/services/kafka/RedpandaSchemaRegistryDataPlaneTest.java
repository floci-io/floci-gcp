package io.floci.gcp.services.kafka;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.docker.ContainerBuilder;
import io.floci.gcp.core.common.docker.ContainerDetector;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.gcp.services.kafka.model.StoredSchemaRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Redpanda data plane's container tracking, against a local HTTP stub standing in for the
 * container's schema registry listener: a request that fails against a running container keeps
 * it, only a container that has stopped is replaced, and a container that never becomes ready is
 * removed.
 */
class RedpandaSchemaRegistryDataPlaneTest {

    @TempDir
    Path dataDir;

    private HttpServer server;
    private ContainerLifecycleManager lifecycle;
    private RedpandaSchemaRegistryDataPlane plane;
    private final StoredSchemaRegistry registry = registry();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/subjects", exchange -> respond(exchange, "[]"));
        // Closes the connection without a response, as a hung or crashed listener would.
        server.createContext("/config", HttpExchange::close);
        server.start();

        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.storage().hostPersistentPath()).thenReturn(dataDir.toString());
        lifecycle = mock(ContainerLifecycleManager.class);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerInfo("cid",
                Map.of(8081, new EndpointInfo("127.0.0.1", server.getAddress().getPort()))));
        ContainerDetector detector = mock(ContainerDetector.class);
        plane = new RedpandaSchemaRegistryDataPlane(mock(ContainerBuilder.class, RETURNS_DEEP_STUBS), lifecycle,
                detector, config);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void aFailedRequestAgainstARunningContainerKeepsIt() {
        plane.ensureRunning(registry);
        when(lifecycle.isContainerRunning("cid")).thenReturn(true);

        assertThrows(GcpException.class, () -> plane.getConfig(registry, null, false));
        plane.listSubjects(registry, null, false);

        verify(lifecycle, times(1)).createAndStart(any());
        verify(lifecycle, times(1)).removeIfExists(anyString());
    }

    @Test
    void aFailedRequestAgainstAStoppedContainerStartsANewOneOnTheNextCall() {
        plane.ensureRunning(registry);
        when(lifecycle.isContainerRunning("cid")).thenReturn(false);

        GcpException e = assertThrows(GcpException.class, () -> plane.getConfig(registry, null, false));
        assertEquals(503, e.getHttpStatus());
        plane.listSubjects(registry, null, false);

        verify(lifecycle, times(2)).createAndStart(any());
    }

    @Test
    void aContainerThatNeverBecomesReadyIsRemoved() {
        // An interrupt makes the readiness wait give up at once, as its deadline would.
        Thread.currentThread().interrupt();
        try {
            assertThrows(GcpException.class, () -> plane.ensureRunning(registry));
        } finally {
            Thread.interrupted();
        }
        verify(lifecycle).stopAndRemove("cid", null);
        plane.listSubjects(registry, null, false);
        verify(lifecycle, times(2)).createAndStart(any());
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static StoredSchemaRegistry registry() {
        StoredSchemaRegistry registry = new StoredSchemaRegistry("projects/p/locations/us-central1/schemaRegistries/r");
        registry.setVolumeId("abc123");
        registry.setVolumeName("floci-gcp-kafka-schema-registry-abc123");
        return registry;
    }
}
