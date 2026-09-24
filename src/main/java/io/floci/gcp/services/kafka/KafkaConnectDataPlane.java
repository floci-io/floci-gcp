package io.floci.gcp.services.kafka;

import io.floci.gcp.services.kafka.model.ConnectorState;
import io.floci.gcp.services.kafka.model.StoredCluster;

import java.util.Map;
import java.util.Optional;

/**
 * The Kafka Connect runtime behind a Connect cluster: one worker per Connect cluster, attached to
 * the Kafka cluster it references, with connectors driven through the worker's REST API.
 * Implementations throw {@code GcpException} for anything the caller should see.
 */
interface KafkaConnectDataPlane {

    /** Starts the worker and returns once it serves its REST API. */
    void startWorker(String connectCluster, StoredCluster kafkaCluster, Map<String, String> workerConfig);

    /**
     * Stops the worker. With {@code eraseState}, also deletes the internal topics it keeps in the
     * Kafka cluster (connector configs, offsets, status), which deleting a Connect cluster erases.
     */
    void stopWorker(String connectCluster, StoredCluster kafkaCluster, boolean eraseState);

    void createConnector(String connectCluster, String connectorId, Map<String, String> configs);

    void updateConnector(String connectCluster, String connectorId, Map<String, String> configs);

    void deleteConnector(String connectCluster, String connectorId);

    /** Pause, resume or stop, named by the state the method moves the connector to. */
    void transitionConnector(String connectCluster, String connectorId, ConnectorState target);

    /** Restarts the connector and its tasks. */
    void restartConnector(String connectCluster, String connectorId);

    /** The runtime's state for one connector, or empty when the worker cannot be asked. */
    Optional<ConnectorState> connectorState(String connectCluster, String connectorId);

    /** The runtime's state for every connector it knows, or empty when the worker cannot be asked. */
    Optional<Map<String, ConnectorState>> connectorStates(String connectCluster);

    /** Whether a worker is running for the Connect cluster in this process. */
    boolean isRunning(String connectCluster);

    /**
     * Stops every worker attached to this Kafka cluster's broker, which is about to be removed: a
     * worker cannot run without it, and one left behind would keep a container running against a
     * broker that no longer exists.
     */
    void stopWorkersOn(StoredCluster kafkaCluster);

    static KafkaConnectDataPlane noop() {
        return new KafkaConnectDataPlane() {
            @Override
            public void startWorker(String connectCluster, StoredCluster kafkaCluster,
                                    Map<String, String> workerConfig) {
            }

            @Override
            public void stopWorker(String connectCluster, StoredCluster kafkaCluster, boolean eraseState) {
            }

            @Override
            public void createConnector(String connectCluster, String connectorId, Map<String, String> configs) {
            }

            @Override
            public void updateConnector(String connectCluster, String connectorId, Map<String, String> configs) {
            }

            @Override
            public void deleteConnector(String connectCluster, String connectorId) {
            }

            @Override
            public void transitionConnector(String connectCluster, String connectorId, ConnectorState target) {
            }

            @Override
            public void restartConnector(String connectCluster, String connectorId) {
            }

            @Override
            public Optional<ConnectorState> connectorState(String connectCluster, String connectorId) {
                return Optional.empty();
            }

            @Override
            public Optional<Map<String, ConnectorState>> connectorStates(String connectCluster) {
                return Optional.empty();
            }

            @Override
            public boolean isRunning(String connectCluster) {
                return false;
            }

            @Override
            public void stopWorkersOn(StoredCluster kafkaCluster) {
            }
        };
    }
}
