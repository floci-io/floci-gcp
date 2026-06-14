package io.floci.gcp.services.cloudrun;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.cloud.run.v2.EnvVar;
import com.google.cloud.run.v2.Revision;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.docker.ContainerBuilder;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerSpec;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.cloudrun.model.CloudRunRuntimeInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@ApplicationScoped
public class CloudRunRuntimeService {

    private static final Logger LOG = Logger.getLogger(CloudRunRuntimeService.class);

    private final StorageBackend<String, CloudRunRuntimeInstance> runtimeStore;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final EmulatorConfig config;

    @Inject
    public CloudRunRuntimeService(StorageFactory storageFactory,
                                  ContainerBuilder containerBuilder,
                                  ContainerLifecycleManager lifecycleManager,
                                  EmulatorConfig config) {
        this(storageFactory.createGlobal("cloudrun-runtime-instances", "cloudrun-runtime-instances.json",
                        new TypeReference<Map<String, CloudRunRuntimeInstance>>() {}),
                containerBuilder, lifecycleManager, config);
    }

    CloudRunRuntimeService(StorageBackend<String, CloudRunRuntimeInstance> runtimeStore,
                           ContainerBuilder containerBuilder,
                           ContainerLifecycleManager lifecycleManager,
                           EmulatorConfig config) {
        this.runtimeStore = runtimeStore;
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.config = config;
    }

    public void initialize() {
        // Forces CDI proxy initialization on the request thread before background runtime work starts.
        containerBuilder.newContainer("scratch");
        lifecycleManager.getDockerClient().versionCmd();
    }

    public CloudRunRuntimeInstance start(String project, String location,
                                         com.google.cloud.run.v2.Service service,
                                         Revision revision) {
        validateSupported(revision);
        if (!"docker".equals(config.services().cloudrun().execution().runtime())) {
            throw GcpException.unimplemented("Cloud Run execution runtime is not supported: "
                    + config.services().cloudrun().execution().runtime());
        }

        com.google.cloud.run.v2.Container container = revision.getContainers(0);
        int containerPort = ingressPort(container);
        String containerName = containerName(service.getName(), revision);

        ContainerSpec spec = buildSpec(project, location, service, revision, container, containerPort, containerName);
        String containerId = null;
        try {
            ContainerLifecycleManager.ContainerInfo info = lifecycleManager.createAndStart(spec);
            containerId = info.containerId();
            ContainerLifecycleManager.EndpointInfo endpoint = info.getEndpoint(containerPort);
            if (endpoint == null) {
                throw GcpException.internal("Cloud Run runtime did not expose port " + containerPort);
            }

            CloudRunRuntimeInstance starting = instance(project, location, service, revision, container,
                    info.containerId(), containerPort, spec.networkMode(), endpoint, "STARTING", null);
            runtimeStore.put(revision.getName(), starting);
            waitForReady(endpoint, config.services().cloudrun().execution().startupTimeout());
            CloudRunRuntimeInstance ready = starting.withStatus("READY", null);
            runtimeStore.put(revision.getName(), ready);
            LOG.infof("Cloud Run runtime ready service=%s revision=%s endpoint=%s",
                    service.getName(), revision.getName(), endpoint);
            return ready;
        } catch (RuntimeException e) {
            if (containerId != null) {
                lifecycleManager.stopAndRemove(containerId, null);
            }
            throw e;
        }
    }

    public void stopService(String serviceName) {
        stopInstances(serviceInstances(serviceName));
    }

    List<CloudRunRuntimeInstance> serviceInstances(String serviceName) {
        String prefix = serviceName + "/revisions/";
        return List.copyOf(runtimeStore.keys()).stream()
                .filter(key -> key.startsWith(prefix))
                .map(runtimeStore::get)
                .flatMap(Optional::stream)
                .toList();
    }

    List<CloudRunRuntimeInstance> serviceInstancesExcept(String serviceName, String keepRevisionName) {
        return serviceInstances(serviceName).stream()
                .filter(instance -> !instance.revisionName().equals(keepRevisionName))
                .toList();
    }

    void stopInstances(List<CloudRunRuntimeInstance> instances) {
        for (CloudRunRuntimeInstance instance : List.copyOf(instances)) {
            stopInstance(instance);
        }
    }

    public void stopOtherRevisions(String serviceName, String keepRevisionName) {
        stopInstances(serviceInstancesExcept(serviceName, keepRevisionName));
    }

    public Optional<CloudRunRuntimeInstance> getReady(String revisionName) {
        Optional<CloudRunRuntimeInstance> stored = runtimeStore.get(revisionName);
        if (stored.isEmpty() || !stored.get().ready()) {
            return Optional.empty();
        }

        CloudRunRuntimeInstance instance = stored.get();
        if (instance.containerId() == null || instance.containerId().isBlank()
                || !lifecycleManager.isContainerRunning(instance.containerId())) {
            runtimeStore.delete(revisionName);
            return Optional.empty();
        }

        try {
            ContainerLifecycleManager.EndpointInfo endpoint = lifecycleManager.resolveEndpoint(
                    instance.containerId(), instance.ingressContainerPort(), instance.dockerNetwork());
            CloudRunRuntimeInstance refreshed = instance.withEndpoint(endpoint.host(), endpoint.port());
            if (!refreshed.equals(instance)) {
                runtimeStore.put(revisionName, refreshed);
            }
            return Optional.of(refreshed);
        } catch (RuntimeException e) {
            LOG.debugf(e, "Cloud Run runtime endpoint lookup failed revision=%s", revisionName);
            return Optional.empty();
        }
    }

    void markFailed(String revisionName, String message) {
        runtimeStore.get(revisionName)
                .map(instance -> instance.withStatus("FAILED", message))
                .ifPresent(instance -> runtimeStore.put(revisionName, instance));
    }

    ContainerSpec buildSpec(String project, String location,
                            com.google.cloud.run.v2.Service service,
                            Revision revision,
                            com.google.cloud.run.v2.Container container,
                            int containerPort,
                            String containerName) {
        Map<String, String> env = new LinkedHashMap<>();
        for (EnvVar envVar : container.getEnvList()) {
            env.put(envVar.getName(), envVar.getValue());
        }
        env.put("PORT", Integer.toString(containerPort));
        env.put("K_SERVICE", lastSegment(service.getName()));
        env.put("K_REVISION", lastSegment(revision.getName()));
        env.put("K_CONFIGURATION", lastSegment(service.getName()));

        ContainerBuilder.Builder builder = containerBuilder.newContainer(container.getImage())
                .withName(containerName)
                .withDynamicPort(containerPort)
                .withDockerNetwork(Optional.empty())
                .withHostDockerInternalOnLinux()
                .withLogRotation()
                .withLabels(Map.of(
                        "floci-gcp", "true",
                        "floci-gcp.service", "cloudrun",
                        "floci-gcp.project", project,
                        "floci-gcp.location", location,
                        "floci-gcp.cloudrun.service", service.getName(),
                        "floci-gcp.cloudrun.revision", revision.getName()));

        builder.withEnv(env.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .toList());
        if (!container.getCommandList().isEmpty()) {
            builder.withEntrypoint(container.getCommandList());
        }
        if (!container.getArgsList().isEmpty()) {
            builder.withCmd(container.getArgsList());
        }
        if (!container.getWorkingDir().isBlank()) {
            builder.withWorkingDir(container.getWorkingDir());
        }
        return builder.build();
    }

    private void stopInstance(CloudRunRuntimeInstance instance) {
        if (instance.containerId() != null && !instance.containerId().isBlank()) {
            lifecycleManager.stopAndRemove(instance.containerId(), null);
        }
        runtimeStore.get(instance.revisionName())
                .filter(current -> sameRuntime(current, instance))
                .ifPresent(current -> runtimeStore.delete(instance.revisionName()));
    }

    private static boolean sameRuntime(CloudRunRuntimeInstance current, CloudRunRuntimeInstance expected) {
        return Objects.equals(current.containerId(), expected.containerId())
                && current.createTimeMillis() == expected.createTimeMillis();
    }

    static void validateSupported(Revision revision) {
        if (revision.getContainersCount() != 1) {
            throw GcpException.invalidArgument("Cloud Run execution supports exactly one container");
        }
        if (revision.getVolumesCount() > 0) {
            throw GcpException.invalidArgument("Cloud Run execution does not support volumes");
        }
        com.google.cloud.run.v2.Container container = revision.getContainers(0);
        if (container.getImage().isBlank()) {
            throw GcpException.invalidArgument("Cloud Run execution requires a container image");
        }
        if (container.getPortsCount() > 1) {
            throw GcpException.invalidArgument("Cloud Run execution supports at most one container port");
        }
        for (EnvVar envVar : container.getEnvList()) {
            if (envVar.hasValueSource()) {
                throw GcpException.invalidArgument("Cloud Run execution does not support env valueSource");
            }
        }
    }

    private int ingressPort(com.google.cloud.run.v2.Container container) {
        if (container.getPortsCount() == 0 || container.getPorts(0).getContainerPort() == 0) {
            return config.services().cloudrun().execution().defaultPort();
        }
        return container.getPorts(0).getContainerPort();
    }

    private CloudRunRuntimeInstance instance(String project, String location,
                                             com.google.cloud.run.v2.Service service,
                                             Revision revision,
                                             com.google.cloud.run.v2.Container container,
                                             String containerId,
                                             int containerPort,
                                             String dockerNetwork,
                                             ContainerLifecycleManager.EndpointInfo endpoint,
                                             String status,
                                             String lastError) {
        long now = System.currentTimeMillis();
        long requestTimeoutMillis = requestTimeout(revision).toMillis();
        return new CloudRunRuntimeInstance(project, location, service.getName(), revision.getName(),
                container.getImage(), containerId, containerPort, dockerNetwork, endpoint.host(), endpoint.port(),
                service.getUri(), status, now, now, lastError, requestTimeoutMillis);
    }

    private Duration requestTimeout(Revision revision) {
        if (revision.hasTimeout() && (revision.getTimeout().getSeconds() > 0 || revision.getTimeout().getNanos() > 0)) {
            return Duration.ofSeconds(revision.getTimeout().getSeconds(), revision.getTimeout().getNanos());
        }
        return config.services().cloudrun().execution().requestTimeout();
    }

    private void waitForReady(ContainerLifecycleManager.EndpointInfo endpoint, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), 500);
                return;
            } catch (Exception e) {
                last = new RuntimeException(e);
                try {
                    Thread.sleep(250);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw GcpException.unavailable("Cloud Run runtime startup interrupted");
                }
            }
        }
        String detail = endpoint.toString();
        if (last != null && last.getCause() != null && last.getCause().getMessage() != null) {
            detail = last.getCause().getMessage();
        }
        throw GcpException.unavailable("Cloud Run runtime did not become ready before timeout: " + detail);
    }

    private String containerName(String serviceName, Revision revision) {
        String name = config.services().cloudrun().execution().containerNamePrefix()
                + "-" + sanitize(lastSegment(serviceName))
                + "-" + sanitize(lastSegment(revision.getName()));
        if (revision.getUid().isBlank()) {
            return name;
        }
        return name + "-" + sanitize(revision.getUid());
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_.-]", "-");
    }

    private static String lastSegment(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }
}
