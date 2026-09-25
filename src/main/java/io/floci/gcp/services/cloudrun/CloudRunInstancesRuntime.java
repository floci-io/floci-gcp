package io.floci.gcp.services.cloudrun;

import com.github.dockerjava.api.command.InspectImageResponse;
import com.google.cloud.run.v2.Container;
import com.google.cloud.run.v2.Instance;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerSpec;
import io.floci.gcp.services.cloudrun.model.CloudRunRuntimeInstance;
import io.floci.gcp.services.cloudrun.model.CloudRunRuntimeVolumeMount;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Docker runtime for Cloud Run v2 Instances: one HTTP container per instance, created on create and start,
 * removed on stop and delete. The container is never restarted by the emulator on its own.
 */
@ApplicationScoped
public class CloudRunInstancesRuntime {

    private static final Logger LOG = Logger.getLogger(CloudRunInstancesRuntime.class);

    private final CloudRunRuntimeService runtimeService;
    private final ContainerLifecycleManager lifecycleManager;
    private final EmulatorConfig config;

    @Inject
    public CloudRunInstancesRuntime(CloudRunRuntimeService runtimeService,
                                    ContainerLifecycleManager lifecycleManager,
                                    EmulatorConfig config) {
        this.runtimeService = runtimeService;
        this.lifecycleManager = lifecycleManager;
        this.config = config;
    }

    record Started(CloudRunRuntimeInstance runtime, String imageDigest, Duration imageImport) {}

    /**
     * Starts the instance container and waits until its port accepts connections. Any container left over
     * from a previous start of the same instance is removed first.
     */
    Started start(String project, String location, Instance instance, String url) {
        runtimeService.initialize();
        CloudRunRuntimeService.validateSupported(instance.getContainersList(), instance.getVolumesList());
        stop(instance);

        Container container = instance.getContainers(0);
        int port = port(container);
        String name = instance.getName();
        List<CloudRunRuntimeVolumeMount> mounts =
                runtimeService.prepareGcsVolumeMounts(name, instance.getVolumesList(), container);
        ContainerSpec spec = runtimeService.buildWorkloadSpec(project, location, name, containerName(instance),
                container, Map.of("PORT", Integer.toString(port)), port, mounts);

        String containerId = null;
        CloudRunRuntimeInstance starting = null;
        try {
            long importStart = System.nanoTime();
            ContainerLifecycleManager.ContainerInfo info = lifecycleManager.createAndStart(spec);
            Duration imageImport = Duration.ofNanos(System.nanoTime() - importStart);
            containerId = info.containerId();
            ContainerLifecycleManager.EndpointInfo endpoint = info.getEndpoint(port);
            if (endpoint == null) {
                throw GcpException.internal("Cloud Run runtime did not expose port " + port);
            }
            long now = System.currentTimeMillis();
            starting = new CloudRunRuntimeInstance(project, location, name, name, container.getImage(),
                    containerId, port, CloudRunRuntimeService.ingressH2c(container), spec.networkMode(),
                    endpoint.host(), endpoint.port(), url, "STARTING", now, now, null,
                    config.services().cloudrun().execution().requestTimeout().toMillis(), mounts);
            runtimeService.saveRuntimeRecord(name, starting);
            runtimeService.waitForReady(endpoint, config.services().cloudrun().execution().startupTimeout());
            CloudRunRuntimeInstance ready = starting.withStatus("READY", null);
            runtimeService.saveRuntimeRecord(name, ready);
            LOG.infof("Cloud Run instance runtime ready instance=%s endpoint=%s", name, endpoint);
            return new Started(ready, imageDigest(spec.image()), imageImport);
        } catch (RuntimeException e) {
            if (starting != null) {
                runtimeService.stopInstances(List.of(starting));
            } else {
                if (containerId != null) {
                    lifecycleManager.forceRemove(containerId, null);
                }
                runtimeService.releaseGcsVolumeMounts(mounts);
            }
            throw e;
        }
    }

    /**
     * Stops and removes the instance container, giving it the configured cleanup timeout to exit after
     * SIGTERM. Writable GCS volumes are written back to their bucket.
     */
    void stop(Instance instance) {
        String name = instance.getName();
        Optional<CloudRunRuntimeInstance> record = runtimeService.runtimeRecord(name);
        if (record.isPresent()) {
            String containerId = record.get().containerId();
            if (containerId != null && !containerId.isBlank()) {
                gracefulStop(name, containerId);
            }
            runtimeService.stopInstances(List.of(record.get()));
        }
        lifecycleManager.removeIfExists(containerName(instance));
    }

    Optional<CloudRunRuntimeInstance> ready(String instanceName) {
        return runtimeService.getReady(instanceName);
    }

    private void gracefulStop(String instanceName, String containerId) {
        int graceSeconds = (int) Math.max(0, config.services().cloudrun().execution().cleanupTimeout().toSeconds());
        try {
            lifecycleManager.runDockerApi("stop container " + containerId, () -> {
                lifecycleManager.getDockerClient().stopContainerCmd(containerId).withTimeout(graceSeconds).exec();
                return null;
            });
        } catch (Exception e) {
            LOG.debugf(e, "Cloud Run instance container stop failed instance=%s container=%s; removing it",
                    instanceName, containerId);
        }
    }

    private String imageDigest(String image) {
        try {
            InspectImageResponse inspect = lifecycleManager.runDockerApi("inspect image " + image,
                    () -> lifecycleManager.getDockerClient().inspectImageCmd(image).exec());
            List<String> digests = inspect.getRepoDigests();
            return digests == null || digests.isEmpty() ? "" : digests.get(0);
        } catch (Exception e) {
            LOG.debugf(e, "Cloud Run instance image inspection failed image=%s", image);
            return "";
        }
    }

    private int port(Container container) {
        if (container.getPortsCount() == 0 || container.getPorts(0).getContainerPort() == 0) {
            return config.services().cloudrun().execution().defaultPort();
        }
        return container.getPorts(0).getContainerPort();
    }

    private String containerName(Instance instance) {
        String uid = instance.getUid().length() > 8 ? instance.getUid().substring(0, 8) : instance.getUid();
        return runtimeService.workloadContainerName("instance",
                CloudRunRuntimeService.lastSegment(instance.getName()), uid);
    }
}
