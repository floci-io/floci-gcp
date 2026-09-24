package io.floci.gcp.services.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.kafka.model.ConnectorState;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaConnectContainerDataPlaneTest {

    private static final String CC = "projects/p/locations/us-central1/connectClusters/cc";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Properties load(String text) throws IOException {
        Properties props = new Properties();
        props.load(new StringReader(text));
        return props;
    }

    @Test
    void workerPropertiesAttachToTheSidecarListenerAndKeepStateInPerClusterTopics() throws IOException {
        Properties props = load(KafkaConnectContainerDataPlane.workerProperties(CC, null));

        assertEquals("floci-kafka-broker:29092", props.getProperty("bootstrap.servers"));
        assertEquals("floci-connect-cc", props.getProperty("group.id"));
        assertEquals("_floci-connect-cc-configs", props.getProperty("config.storage.topic"));
        assertEquals("_floci-connect-cc-offsets", props.getProperty("offset.storage.topic"));
        assertEquals("_floci-connect-cc-status", props.getProperty("status.storage.topic"));
        assertEquals("1", props.getProperty("offset.storage.replication.factor"));
        assertEquals("http://0.0.0.0:8083", props.getProperty("listeners"));
    }

    @Test
    void workerConfigOverridesDefaultsButNotThePropertiesTheEmulatorOwns() throws IOException {
        Properties props = load(KafkaConnectContainerDataPlane.workerProperties(CC, Map.of(
                "exactly.once.source.support", "enabled",
                "value.converter", "org.apache.kafka.connect.storage.StringConverter",
                "bootstrap.servers", "elsewhere:9092",
                "group.id", "hijacked")));

        assertEquals("enabled", props.getProperty("exactly.once.source.support"));
        assertEquals("org.apache.kafka.connect.storage.StringConverter", props.getProperty("value.converter"));
        assertEquals("floci-kafka-broker:29092", props.getProperty("bootstrap.servers"));
        assertEquals("floci-connect-cc", props.getProperty("group.id"));
    }

    @Test
    void workerConfigValuesSurviveThePropertiesFileVerbatim() throws IOException {
        String awkward = "a=b:c \\ d\nnext.line=injected";
        Properties props = load(KafkaConnectContainerDataPlane.workerProperties(CC, Map.of("client.id", awkward)));

        assertEquals(awkward, props.getProperty("client.id"));
        assertNull(props.getProperty("next.line"));
    }

    @Test
    void internalTopicsAreTheOnesTheWorkerIsConfiguredWith() {
        assertEquals(List.of("_floci-connect-cc-configs", "_floci-connect-cc-offsets", "_floci-connect-cc-status"),
                KafkaConnectContainerDataPlane.internalTopics(CC));
    }

    @Test
    void connectErrorsMapToTheGcpStatusTheCallerCanActOn() {
        assertEquals("INVALID_ARGUMENT", KafkaConnectContainerDataPlane.toGcpException(400,
                "Connector config {name=x} contains no connector type").getGcpStatus());
        // Kafka 3.x reports an unknown connector class as a 500; 4.x as a 400.
        assertEquals("INVALID_ARGUMENT", KafkaConnectContainerDataPlane.toGcpException(500,
                "Failed to find any class that implements Connector and which name matches x.Nope").getGcpStatus());
        assertEquals("NOT_FOUND", KafkaConnectContainerDataPlane.toGcpException(404, "Connector x not found").getGcpStatus());
        assertEquals("ALREADY_EXISTS", KafkaConnectContainerDataPlane.toGcpException(409, "Connector x already exists").getGcpStatus());
        assertEquals("INTERNAL", KafkaConnectContainerDataPlane.toGcpException(500, "Request timed out").getGcpStatus());
    }

    @Test
    void connectStatusStatesAreTheProtoMembers() throws IOException {
        for (String state : List.of("UNASSIGNED", "RUNNING", "PAUSED", "FAILED", "RESTARTING", "STOPPED")) {
            assertEquals(ConnectorState.valueOf(state), KafkaConnectContainerDataPlane.connectorState(
                    MAPPER.readTree("{\"connector\":{\"state\":\"" + state + "\"}}")));
        }
        assertEquals(ConnectorState.STATE_UNSPECIFIED,
                KafkaConnectContainerDataPlane.connectorState(MAPPER.readTree("{\"connector\":{\"state\":\"NEW\"}}")));
        assertEquals(ConnectorState.UNASSIGNED, KafkaConnectContainerDataPlane.connectorState(MAPPER.readTree("{}")));
    }

    @Test
    void aFailedTaskMakesTheConnectorFailedEvenWhileItsInstanceRuns() throws IOException {
        assertEquals(ConnectorState.FAILED, KafkaConnectContainerDataPlane.connectorState(MAPPER.readTree(
                "{\"connector\":{\"state\":\"RUNNING\"},\"tasks\":[{\"id\":0,\"state\":\"RUNNING\"},"
                        + "{\"id\":1,\"state\":\"FAILED\"}]}")));
        assertEquals(ConnectorState.PAUSED, KafkaConnectContainerDataPlane.connectorState(MAPPER.readTree(
                "{\"connector\":{\"state\":\"PAUSED\"},\"tasks\":[{\"id\":0,\"state\":\"PAUSED\"}]}")));
    }

    @Test
    void aRebalanceThatOutlastsTheRetriesIsUnavailableNotANameConflict() {
        assertEquals("UNAVAILABLE", KafkaConnectContainerDataPlane.toGcpException(409,
                "Cannot complete request momentarily due to stale configuration (typically caused by a concurrent config change)"
                        + " or rebalance in progress").getGcpStatus());
    }

    // ── Writes whose response never arrives ───────────────────────────────────

    /**
     * A stand-in worker that can apply a write and then drop the connection without answering,
     * which is what a client sees when a request times out after the worker acted on it.
     */
    private static final class FlakyWorker implements AutoCloseable {
        final HttpServer server;
        final Map<String, String> configs = new ConcurrentHashMap<>();
        volatile boolean applyThenDrop;
        volatile boolean dropWithoutApplying;

        FlakyWorker() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/connectors", this::handle);
            server.start();
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean write = !method.equals("GET");
            if (write && dropWithoutApplying) {
                exchange.close();
                return;
            }
            int status = 200;
            String response = "{}";
            if (method.equals("POST") && path.equals("/connectors")) {
                JsonNode request = MAPPER.readTree(body);
                configs.put(request.get("name").asText(), MAPPER.writeValueAsString(request.get("config")));
                status = 201;
            } else if (method.equals("PUT") && path.endsWith("/config")) {
                configs.put(path.split("/")[2], body);
            } else if (method.equals("DELETE")) {
                configs.remove(path.split("/")[2]);
                status = 204;
            } else if (method.equals("GET") && path.endsWith("/config")) {
                String config = configs.get(path.split("/")[2]);
                if (config == null) {
                    status = 404;
                    response = "{\"error_code\":404,\"message\":\"Connector not found\"}";
                } else {
                    response = config;
                }
            } else if (method.equals("GET")) {
                if (!configs.containsKey(path.split("/")[2])) {
                    status = 404;
                    response = "{\"error_code\":404,\"message\":\"Connector not found\"}";
                }
            }
            if (write && applyThenDrop) {
                exchange.close();
                return;
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
            if (status != 204) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    @Test
    void anUnansweredCreateThatTheWorkerAppliedSucceeds() throws IOException {
        try (FlakyWorker worker = new FlakyWorker()) {
            KafkaConnectContainerDataPlane dataPlane = new KafkaConnectContainerDataPlane(MAPPER);
            dataPlane.register(CC, worker.base(), "broker");
            worker.applyThenDrop = true;

            dataPlane.createConnector(CC, "sink", Map.of("connector.class", "x.Sink", "topics", "orders"));

            assertTrue(worker.configs.containsKey("sink"));
        }
    }

    @Test
    void anUnansweredCreateThatTheWorkerDidNotApplyIsUnavailable() throws IOException {
        try (FlakyWorker worker = new FlakyWorker()) {
            KafkaConnectContainerDataPlane dataPlane = new KafkaConnectContainerDataPlane(MAPPER);
            dataPlane.register(CC, worker.base(), "broker");
            worker.dropWithoutApplying = true;

            assertEquals("UNAVAILABLE", assertThrows(GcpException.class,
                    () -> dataPlane.createConnector(CC, "sink", Map.of("connector.class", "x.Sink"))).getGcpStatus());
        }
    }

    @Test
    void anUnansweredUpdateOrDeleteThatTheWorkerAppliedSucceeds() throws IOException {
        try (FlakyWorker worker = new FlakyWorker()) {
            KafkaConnectContainerDataPlane dataPlane = new KafkaConnectContainerDataPlane(MAPPER);
            dataPlane.register(CC, worker.base(), "broker");
            dataPlane.createConnector(CC, "sink", Map.of("connector.class", "x.Sink", "topics", "orders"));
            worker.applyThenDrop = true;

            dataPlane.updateConnector(CC, "sink", Map.of("connector.class", "x.Sink", "topics", "returns"));
            assertTrue(worker.configs.get("sink").contains("returns"));

            dataPlane.deleteConnector(CC, "sink");
            assertFalse(worker.configs.containsKey("sink"));
        }
    }

    @Test
    void anUnansweredUpdateWhoseConfigDidNotLandIsUnavailable() throws IOException {
        try (FlakyWorker worker = new FlakyWorker()) {
            KafkaConnectContainerDataPlane dataPlane = new KafkaConnectContainerDataPlane(MAPPER);
            dataPlane.register(CC, worker.base(), "broker");
            dataPlane.createConnector(CC, "sink", Map.of("connector.class", "x.Sink", "topics", "orders"));
            worker.dropWithoutApplying = true;

            assertEquals("UNAVAILABLE", assertThrows(GcpException.class, () -> dataPlane.updateConnector(
                    CC, "sink", Map.of("connector.class", "x.Sink", "topics", "returns"))).getGcpStatus());
            assertTrue(worker.configs.get("sink").contains("orders"));
        }
    }
}
