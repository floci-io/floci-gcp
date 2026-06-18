package io.floci.gcp.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class ContainerLifecycleManager {

    private static final Logger LOG = Logger.getLogger(ContainerLifecycleManager.class);
    private static final AtomicInteger DOCKER_API_THREAD = new AtomicInteger();

    private final DockerClientProducer dockerClients;
    private final ContainerDetector containerDetector;
    private final PortAllocator portAllocator;
    private final ImageCacheService imageCacheService;
    private final ExecutorService dockerApiExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "floci-docker-api-" + DOCKER_API_THREAD.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public ContainerLifecycleManager(DockerClientProducer dockerClients,
                                     ContainerDetector containerDetector,
                                     PortAllocator portAllocator,
                                     ImageCacheService imageCacheService) {
        this.dockerClients = dockerClients;
        this.containerDetector = containerDetector;
        this.portAllocator = portAllocator;
        this.imageCacheService = imageCacheService;
    }

    @PreDestroy
    void shutdown() {
        dockerApiExecutor.shutdownNow();
    }

    public ContainerInfo createAndStart(ContainerSpec spec) {
        String containerId = create(spec);
        return startCreated(containerId, spec);
    }

    public String create(ContainerSpec spec) {
        LOG.debugv("Creating container: image={0}, name={1}", spec.image(), spec.name());

        imageCacheService.ensureImageExists(spec.image());

        HostConfig hostConfig = buildHostConfig(spec);

        CreateContainerCmd createCmd = dockerClient().createContainerCmd(spec.image())
                .withHostConfig(hostConfig);

        if (spec.name() != null) {
            createCmd.withName(spec.name());
        }
        if (spec.env() != null && !spec.env().isEmpty()) {
            createCmd.withEnv(spec.env());
        }
        if (spec.cmd() != null && !spec.cmd().isEmpty()) {
            createCmd.withCmd(spec.cmd());
        }
        if (spec.entrypoint() != null && !spec.entrypoint().isEmpty()) {
            createCmd.withEntrypoint(spec.entrypoint());
        }
        if (spec.workingDir() != null && !spec.workingDir().isBlank()) {
            createCmd.withWorkingDir(spec.workingDir());
        }
        if (spec.exposedPorts() != null && !spec.exposedPorts().isEmpty()) {
            ExposedPort[] exposed = spec.exposedPorts().stream()
                    .map(ExposedPort::tcp)
                    .toArray(ExposedPort[]::new);
            createCmd.withExposedPorts(exposed);
        }
        if (spec.labels() != null && !spec.labels().isEmpty()) {
            createCmd.withLabels(spec.labels());
        }

        CreateContainerResponse response = dockerApi("create container " + spec.name(), createCmd::exec);
        String containerId = response.getId();
        LOG.infov("Created container {0} (name={1})", containerId, spec.name());
        return containerId;
    }

    public ContainerInfo startCreated(String containerId, ContainerSpec spec) {
        dockerApi("start container " + containerId, () -> {
            dockerClient().startContainerCmd(containerId).exec();
            return null;
        });
        LOG.infov("Started container {0}", containerId);

        if (spec.networkMode() != null && !spec.networkMode().isBlank() && spec.hasPortBindings()) {
            try {
                dockerApi("connect container " + containerId + " to network " + spec.networkMode(), () -> {
                    dockerClient().connectToNetworkCmd()
                            .withContainerId(containerId)
                            .withNetworkId(spec.networkMode())
                            .exec();
                    return null;
                });
                LOG.debugv("Connected container {0} to network {1}", containerId, spec.networkMode());
            } catch (Exception e) {
                LOG.warnv("Could not connect container {0} to network {1}: {2}",
                        containerId, spec.networkMode(), e.getMessage());
            }
        }

        Map<Integer, EndpointInfo> endpoints = resolveEndpoints(containerId, spec);
        return new ContainerInfo(containerId, endpoints);
    }

    public void stopAndRemove(String containerId, Closeable logStream) {
        LOG.infov("Stopping container {0}", containerId);

        closeLogStream(logStream);

        try {
            dockerApi("stop container " + containerId, () -> {
                dockerClient().stopContainerCmd(containerId).withTimeout(5).exec();
                return null;
            });
        } catch (NotFoundException e) {
            LOG.debugv("Container {0} not found (already removed)", containerId);
            return;
        } catch (Exception e) {
            LOG.warnv("Error stopping container {0}: {1}", containerId, e.getMessage());
        }

        try {
            dockerApi("remove container " + containerId, () -> {
                dockerClient().removeContainerCmd(containerId).withForce(true).exec();
                return null;
            });
            LOG.debugv("Removed container {0}", containerId);
        } catch (NotFoundException e) {
            // Already gone
        } catch (Exception e) {
            LOG.warnv("Error removing container {0}: {1}", containerId, e.getMessage());
        }
    }

    public void forceRemove(String containerId, Closeable logStream) {
        LOG.infov("Force-removing container {0}", containerId);

        closeLogStream(logStream);

        try {
            dockerApi("force-remove container " + containerId, () -> {
                dockerClient().removeContainerCmd(containerId).withForce(true).exec();
                return null;
            });
            LOG.debugv("Force-removed container {0}", containerId);
        } catch (NotFoundException e) {
            LOG.debugv("Container {0} not found (already removed)", containerId);
        } catch (Exception e) {
            LOG.warnv("Error force-removing container {0}: {1}", containerId, e.getMessage());
        }
    }

    private void closeLogStream(Closeable logStream) {
        if (logStream == null) {
            return;
        }
        try {
            logStream.close();
        } catch (Exception e) {
            LOG.debugv("Error closing log stream: {0}", e.getMessage());
        }
    }

    public void ensureVolume(String volumeName) {
        if (!volumeExists(volumeName)) {
            LOG.infov("Creating Docker volume {0}", volumeName);
            dockerApi("create Docker volume " + volumeName, () -> {
                dockerClient().createVolumeCmd()
                        .withName(volumeName)
                        .withLabels(Map.of("floci-gcp", "true"))
                        .exec();
                return null;
            });
            LOG.infov("Created Docker volume {0}", volumeName);
        }
    }

    public void removeVolume(String volumeName) {
        try {
            LOG.infov("Removing Docker volume {0}", volumeName);
            dockerApi("remove Docker volume " + volumeName, () -> {
                dockerClient().removeVolumeCmd(volumeName).exec();
                return null;
            });
            LOG.infov("Removed Docker volume {0}", volumeName);
        } catch (NotFoundException e) {
            // Already gone
        } catch (Exception e) {
            LOG.warnv("Error removing volume {0}: {1}", volumeName, e.getMessage());
        }
    }

    public Optional<Container> findByName(String name) {
        try {
            List<Container> containers = dockerApi("list containers while searching for " + name,
                    () -> dockerClient().listContainersCmd()
                            .withShowAll(true)
                            .exec());

            for (Container c : containers) {
                String[] names = c.getNames();
                if (names == null) {
                    continue;
                }
                for (String n : names) {
                    if (n.equals("/" + name) || n.equals(name)) {
                        return Optional.of(c);
                    }
                }
            }
        } catch (Exception e) {
            LOG.debugv("Error searching for container {0}: {1}", name, e.getMessage());
        }
        return Optional.empty();
    }

    public ContainerInfo adopt(String containerId, List<Integer> ports) {
        LOG.infov("Adopting existing container {0}", containerId);

        InspectContainerResponse inspect = dockerApi("inspect container " + containerId,
                () -> dockerClient().inspectContainerCmd(containerId).exec());
        boolean running = Boolean.TRUE.equals(inspect.getState().getRunning());

        if (!running) {
            dockerApi("start adopted container " + containerId, () -> {
                dockerClient().startContainerCmd(containerId).exec();
                return null;
            });
            LOG.infov("Started adopted container {0}", containerId);
            inspect = dockerApi("inspect adopted container " + containerId,
                    () -> dockerClient().inspectContainerCmd(containerId).exec());
        }

        Map<Integer, EndpointInfo> endpoints = new HashMap<>();
        for (int port : ports) {
            endpoints.put(port, resolveEndpoint(inspect, port));
        }

        return new ContainerInfo(containerId, endpoints);
    }

    public void removeIfExists(String name) {
        try {
            dockerApi("remove stale container " + name, () -> {
                dockerClient().removeContainerCmd(name).withForce(true).exec();
                return null;
            });
            LOG.infov("Removed stale container {0}", name);
        } catch (NotFoundException e) {
            // Not found - normal case
        } catch (Exception e) {
            LOG.debugv("Could not remove container {0}: {1}", name, e.getMessage());
        }
    }

    public boolean isContainerRunning(String containerId) {
        try {
            InspectContainerResponse inspect = dockerApi("inspect container " + containerId,
                    () -> dockerClient().inspectContainerCmd(containerId).exec());
            return Boolean.TRUE.equals(inspect.getState().getRunning());
        } catch (NotFoundException e) {
            return false;
        } catch (Exception e) {
            LOG.warnv("Liveness check failed for container {0}: {1}", containerId, e.getMessage());
            return false;
        }
    }

    public EndpointInfo resolveEndpoint(String containerId, int containerPort) {
        InspectContainerResponse inspect = dockerApi("inspect container " + containerId,
                () -> dockerClient().inspectContainerCmd(containerId).exec());
        return resolveEndpoint(inspect, containerPort);
    }

    public EndpointInfo resolveEndpoint(String containerId, int containerPort, String preferredNetwork) {
        InspectContainerResponse inspect = dockerApi("inspect container " + containerId,
                () -> dockerClient().inspectContainerCmd(containerId).exec());
        return resolveEndpoint(inspect, containerPort, preferredNetwork);
    }

    public DockerClient getDockerClient() {
        return dockerClient();
    }

    public <T> T runDockerApi(String description, Callable<T> action) {
        return dockerApi(description, action);
    }

    public boolean volumeExists(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (name.startsWith("/") || name.startsWith(".")) {
            return false;
        }
        if (name.length() >= 3 && Character.isLetter(name.charAt(0))
                && name.charAt(1) == ':' && (name.charAt(2) == '\\' || name.charAt(2) == '/')) {
            return false;
        }
        try {
            LOG.infov("Inspecting Docker volume {0}", name);
            dockerApi("inspect Docker volume " + name, () -> {
                dockerClient().inspectVolumeCmd(name).exec();
                return null;
            });
            LOG.infov("Docker volume {0} exists", name);
            return true;
        } catch (NotFoundException e) {
            LOG.infov("Docker volume {0} does not exist", name);
            return false;
        } catch (DockerException e) {
            LOG.warnv("Failed to inspect volume ''{0}'': {1}", name, e.getMessage());
            return false;
        }
    }

    private HostConfig buildHostConfig(ContainerSpec spec) {
        HostConfig hostConfig = HostConfig.newHostConfig();

        if (spec.privileged()) {
            hostConfig.withPrivileged(true);
        }
        if (spec.hasMemoryLimit()) {
            hostConfig.withMemory(spec.memoryBytes());
        }
        if (spec.hasPortBindings()) {
            Ports ports = new Ports();
            for (Map.Entry<Integer, Integer> entry : spec.portBindings().entrySet()) {
                int containerPort = entry.getKey();
                int hostPort = entry.getValue();
                if (hostPort == 0) {
                    hostPort = portAllocator.allocateAny();
                }
                ports.bind(ExposedPort.tcp(containerPort), Ports.Binding.bindPort(hostPort));
            }
            hostConfig.withPortBindings(ports);
        }
        if (spec.networkMode() != null && !spec.networkMode().isBlank() && !spec.hasPortBindings()) {
            hostConfig.withNetworkMode(spec.networkMode());
        }
        if (spec.mounts() != null && !spec.mounts().isEmpty()) {
            hostConfig.withMounts(spec.mounts());
        }
        if (spec.binds() != null && !spec.binds().isEmpty()) {
            hostConfig.withBinds(spec.binds().toArray(new Bind[0]));
        }
        if (spec.extraHosts() != null && !spec.extraHosts().isEmpty()) {
            hostConfig.withExtraHosts(spec.extraHosts().toArray(new String[0]));
        }
        if (spec.hasLogConfig()) {
            hostConfig.withLogConfig(spec.logConfig());
        }
        if (spec.dnsServers() != null && !spec.dnsServers().isEmpty()) {
            hostConfig.withDns(spec.dnsServers().toArray(new String[0]));
        }

        return hostConfig;
    }

    private Map<Integer, EndpointInfo> resolveEndpoints(String containerId, ContainerSpec spec) {
        if (spec.exposedPorts() == null || spec.exposedPorts().isEmpty()) {
            return Map.of();
        }

        InspectContainerResponse inspect = dockerApi("inspect container " + containerId,
                () -> dockerClient().inspectContainerCmd(containerId).exec());
        Map<Integer, EndpointInfo> endpoints = new HashMap<>();

        for (int containerPort : spec.exposedPorts()) {
            endpoints.put(containerPort, resolveEndpoint(inspect, containerPort, spec.networkMode()));
        }

        return endpoints;
    }

    private EndpointInfo resolveEndpoint(InspectContainerResponse inspect, int containerPort) {
        return resolveEndpoint(inspect, containerPort, null);
    }

    private EndpointInfo resolveEndpoint(InspectContainerResponse inspect, int containerPort, String preferredNetwork) {
        if (!containerDetector.isRunningInContainer()) {
            var bindings = inspect.getNetworkSettings().getPorts().getBindings();
            var binding = bindings.get(ExposedPort.tcp(containerPort));

            if (binding != null && binding.length > 0) {
                int hostPort = Integer.parseInt(binding[0].getHostPortSpec());
                return new EndpointInfo("localhost", hostPort);
            }
            return new EndpointInfo("localhost", containerPort);
        } else {
            String containerIp = resolveContainerIp(inspect, preferredNetwork);
            return new EndpointInfo(containerIp, containerPort);
        }
    }

    private String resolveContainerIp(InspectContainerResponse inspect, String preferredNetwork) {
        var networks = inspect.getNetworkSettings().getNetworks();
        if (networks != null) {
            if (preferredNetwork != null && networks.containsKey(preferredNetwork)) {
                String ip = networks.get(preferredNetwork).getIpAddress();
                if (ip != null && !ip.isBlank()) {
                    return ip;
                }
            }
            for (Map.Entry<String, ContainerNetwork> entry : networks.entrySet()) {
                String ip = entry.getValue().getIpAddress();
                if (ip != null && !ip.isBlank()) {
                    return ip;
                }
            }
        }
        return inspect.getNetworkSettings().getIpAddress();
    }

    private DockerClient dockerClient() {
        return dockerClients.client();
    }

    private <T> T dockerApi(String description, Callable<T> action) {
        Duration timeout = dockerClients.apiTimeout();
        Future<T> future = dockerApiExecutor.submit(action);
        try {
            return future.get(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            dockerClients.reset("Docker API timed out during " + description + " after " + timeout);
            throw new DockerApiTimeoutException(description, timeout, e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            dockerClients.reset("Docker API interrupted during " + description);
            throw new DockerApiTimeoutException(description, timeout, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException("Docker API call failed during " + description, cause);
        }
    }

    public static class DockerApiTimeoutException extends RuntimeException {
        DockerApiTimeoutException(String description, Duration timeout, Throwable cause) {
            super("Docker API timed out during " + description + " after " + timeout, cause);
        }
    }

    public record ContainerInfo(
            String containerId,
            Map<Integer, EndpointInfo> endpoints
    ) {
        public EndpointInfo getEndpoint(int containerPort) {
            return endpoints.get(containerPort);
        }
    }

    public record EndpointInfo(String host, int port) {
        @Override
        public String toString() {
            return host + ":" + port;
        }
    }
}
