package io.floci.gcp.core.common.docker;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the Docker network used by the floci-gcp container itself so that
 * spawned sidecar containers can join the same network.
 */
@ApplicationScoped
public class CurrentContainerNetworkResolver {

    private static final Logger LOG = Logger.getLogger(CurrentContainerNetworkResolver.class);
    private static final String HOSTNAME_FILE = "/etc/hostname";

    private final DockerClientProducer dockerClients;
    private final ContainerDetector containerDetector;

    private volatile Optional<CurrentContainerNetwork> cachedNetwork;

    @Inject
    public CurrentContainerNetworkResolver(DockerClientProducer dockerClients, ContainerDetector containerDetector) {
        this.dockerClients = dockerClients;
        this.containerDetector = containerDetector;
    }

    public Optional<String> resolveNetworkName() {
        return resolve().map(CurrentContainerNetwork::name);
    }

    public Optional<String> resolveContainerIp() {
        return resolve().map(CurrentContainerNetwork::ipAddress);
    }

    Optional<CurrentContainerNetwork> resolve() {
        Optional<CurrentContainerNetwork> cached = cachedNetwork;
        if (cached != null) {
            return cached;
        }
        cachedNetwork = detect();
        return cachedNetwork;
    }

    private Optional<CurrentContainerNetwork> detect() {
        if (!containerDetector.isRunningInContainer()) {
            return Optional.empty();
        }

        String containerId = currentContainerId();
        if (containerId.isBlank()) {
            LOG.debug("Could not determine current Docker container id");
            return Optional.empty();
        }

        try {
            InspectContainerResponse inspect = dockerClients.client().inspectContainerCmd(containerId).exec();
            Map<String, ContainerNetwork> networks = inspect.getNetworkSettings().getNetworks();
            if (networks == null || networks.isEmpty()) {
                return Optional.empty();
            }

            Optional<CurrentContainerNetwork> selected = selectNetwork(networks);
            selected.ifPresent(network -> LOG.infov(
                    "Detected current Docker network for spawned containers: {0} ({1})",
                    network.name(), network.ipAddress()));
            return selected;
        } catch (Exception e) {
            LOG.debugv("Could not inspect current Docker container {0}: {1}", containerId, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<CurrentContainerNetwork> selectNetwork(Map<String, ContainerNetwork> networks) {
        return networks.entrySet().stream()
                .filter(entry -> isUsable(entry.getValue()))
                .filter(entry -> isUserDefinedNetwork(entry.getKey()))
                .findFirst()
                .or(() -> networks.entrySet().stream()
                        .filter(entry -> isUsable(entry.getValue()))
                        .findFirst())
                .map(entry -> new CurrentContainerNetwork(entry.getKey(), entry.getValue().getIpAddress()));
    }

    private boolean isUsable(ContainerNetwork network) {
        return network != null && network.getIpAddress() != null && !network.getIpAddress().isBlank();
    }

    private boolean isUserDefinedNetwork(String networkName) {
        return !"bridge".equals(networkName) && !"host".equals(networkName) && !"none".equals(networkName);
    }

    String currentContainerId() {
        try {
            return Files.readString(Path.of(HOSTNAME_FILE)).trim();
        } catch (Exception e) {
            LOG.debugv("Could not read {0}: {1}", HOSTNAME_FILE, e.getMessage());
            return "";
        }
    }

    record CurrentContainerNetwork(String name, String ipAddress) {
    }
}
