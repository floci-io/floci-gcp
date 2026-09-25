package io.floci.gcp.services.cloudrun;

import com.google.cloud.run.v2.Container;
import com.google.cloud.run.v2.Revision;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerSpec;
import io.floci.gcp.core.common.docker.ContainerStorageHelper;
import io.floci.gcp.services.cloudrun.model.CloudRunRuntimeVolumeMount;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs worker pool replica containers.
 *
 * <p>Callers must serialize {@link #apply} per worker pool name. The replica map is keyed by pool name and
 * each entry is only read or written by the call currently holding that pool's turn, so no two calls ever
 * touch the same pool's replicas concurrently.
 *
 * <p>Every started container is also registered in {@code started}, under {@code lifecycle}, together with
 * the {@code closed} check. {@link #shutdown} sets {@code closed} and takes the registered containers under
 * the same monitor, so a replica whose start completes after shutdown removes its own container instead of
 * registering it, and no container outlives the runtime.
 */
@ApplicationScoped
public class CloudRunWorkerPoolRuntime {

    private static final Logger LOG = Logger.getLogger(CloudRunWorkerPoolRuntime.class);
    private static final Duration STOP_API_MARGIN = Duration.ofSeconds(2);

    private final CloudRunRuntimeService runtimeService;
    private final ContainerLifecycleManager lifecycleManager;
    private final EmulatorConfig config;
    private final Map<String, List<WorkerReplica>> replicas = new ConcurrentHashMap<>();
    private final Object lifecycle = new Object();
    private final Map<String, WorkerReplica> started = new HashMap<>();
    private boolean closed;

    @Inject
    public CloudRunWorkerPoolRuntime(CloudRunRuntimeService runtimeService,
                                     ContainerLifecycleManager lifecycleManager,
                                     EmulatorConfig config) {
        this.runtimeService = runtimeService;
        this.lifecycleManager = lifecycleManager;
        this.config = config;
    }

    /**
     * Converges the pool's containers onto {@code desired}: the missing replicas of the serving revision are
     * started first, then surplus replicas and replicas of any other revision are stopped.
     */
    void apply(DesiredWorkers desired) {
        runtimeService.initialize();
        String poolName = desired.poolName();
        List<WorkerReplica> current = new ArrayList<>(replicas.getOrDefault(poolName, List.of()));
        if (desired.revision() == null) {
            stopReplicas(current);
            replicas.remove(poolName);
            return;
        }

        String revisionName = desired.revision().getName();
        int count = effectiveCount(poolName, desired.requestedCount());
        List<WorkerReplica> serving = new ArrayList<>();
        List<WorkerReplica> retiring = new ArrayList<>();
        for (WorkerReplica replica : current) {
            if (replica.revisionName().equals(revisionName) && lifecycleManager.isContainerRunning(replica.containerId())) {
                serving.add(replica);
            } else {
                retiring.add(replica);
            }
        }
        serving.sort(Comparator.comparingInt(WorkerReplica::index));
        while (serving.size() > count) {
            retiring.add(serving.removeLast());
        }

        Set<Integer> usedIndexes = new HashSet<>();
        for (WorkerReplica replica : serving) {
            usedIndexes.add(replica.index());
        }
        try {
            for (int index = 0; serving.size() < count; index++) {
                if (usedIndexes.contains(index)) {
                    continue;
                }
                serving.add(startReplica(desired, index));
            }
        } finally {
            List<WorkerReplica> tracked = new ArrayList<>(serving);
            tracked.addAll(retiring);
            replicas.put(poolName, List.copyOf(tracked));
        }

        stopReplicas(retiring);
        replicas.put(poolName, List.copyOf(serving));
    }

    /**
     * Removes the worker pool containers of this emulator (same {@code floci_emulator} and
     * {@code floci_namespace} labels) that no replica of this process tracks, which are the ones a previous
     * process left behind. Returns the {@code floci_resource} label (revision name) of each removed container.
     */
    Set<String> removeLeftoverContainers() {
        Map<String, String> filter = new LinkedHashMap<>(ContainerStorageHelper.defaultLabels(config));
        filter.put("floci_service", "cloudrun");
        String namespace = filter.get("floci_namespace");
        Set<String> tracked;
        synchronized (lifecycle) {
            tracked = Set.copyOf(started.keySet());
        }
        List<LeftoverContainer> leftovers;
        try {
            leftovers = lifecycleManager.runDockerApi("list Cloud Run worker pool containers",
                    () -> lifecycleManager.getDockerClient().listContainersCmd()
                            .withShowAll(true)
                            .withLabelFilter(filter)
                            .exec()
                            .stream()
                            .map(container -> new LeftoverContainer(container.getId(),
                                    container.getLabels() == null ? Map.of() : container.getLabels()))
                            .toList());
        } catch (Exception e) {
            LOG.warnf(e, "Could not list leftover Cloud Run worker pool containers");
            return Set.of();
        }
        Set<String> resources = new TreeSet<>();
        for (LeftoverContainer container : leftovers) {
            String resource = container.labels().getOrDefault("floci_resource", "");
            if (!resource.contains("/workerPools/") || tracked.contains(container.id())
                    || !Objects.equals(namespace, container.labels().get("floci_namespace"))) {
                continue;
            }
            LOG.infof("Removing Cloud Run worker pool container left by a previous emulator process "
                    + "container=%s revision=%s", container.id(), resource);
            lifecycleManager.forceRemove(container.id(), null);
            resources.add(resource);
        }
        return resources;
    }

    @PreDestroy
    void shutdown() {
        List<WorkerReplica> toRemove;
        synchronized (lifecycle) {
            closed = true;
            toRemove = List.copyOf(started.values());
            started.clear();
        }
        for (WorkerReplica replica : toRemove) {
            try {
                lifecycleManager.forceRemove(replica.containerId(), null);
                runtimeService.releaseGcsVolumeMounts(replica.mounts());
            } catch (Exception e) {
                LOG.warnf(e, "Cloud Run worker pool container cleanup on shutdown failed container=%s",
                        replica.containerId());
            }
        }
        replicas.clear();
    }

    private int effectiveCount(String poolName, int requested) {
        int cap = Math.max(0, config.services().cloudrun().execution().maxWorkerInstances());
        int wanted = Math.max(0, requested);
        if (wanted > cap) {
            LOG.warnf("Cloud Run worker pool %s requests %d instances; running %d because "
                    + "floci-gcp.services.cloudrun.execution.max-worker-instances is %d", poolName, wanted, cap, cap);
            return cap;
        }
        return wanted;
    }

    private WorkerReplica startReplica(DesiredWorkers desired, int index) {
        synchronized (lifecycle) {
            if (closed) {
                throw new IllegalStateException("Cloud Run worker pool runtime is shutting down");
            }
        }
        Revision revision = desired.revision();
        Container container = revision.getContainers(0);
        String poolId = CloudRunRuntimeService.lastSegment(desired.poolName());
        String revisionId = CloudRunRuntimeService.lastSegment(revision.getName());
        String containerName = runtimeService.workloadContainerName("wp", desired.project(), desired.location(),
                poolId, revisionId, Integer.toString(index));
        lifecycleManager.removeIfExists(containerName);

        List<CloudRunRuntimeVolumeMount> mounts = runtimeService.prepareGcsVolumeMounts(
                revision.getName() + "-" + index, revision.getVolumesList(), container);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("CLOUD_RUN_WORKER_POOL", poolId);
        env.put("CLOUD_RUN_REVISION", revisionId);
        ContainerSpec spec = runtimeService.buildWorkloadSpec(desired.project(), desired.location(),
                revision.getName(), containerName, container, env, null, mounts);
        ContainerLifecycleManager.ContainerInfo info;
        try {
            info = lifecycleManager.createAndStart(spec);
        } catch (RuntimeException e) {
            runtimeService.releaseGcsVolumeMounts(mounts);
            throw e;
        }
        WorkerReplica replica = new WorkerReplica(revision.getName(), index, info.containerId(), mounts);
        synchronized (lifecycle) {
            if (!closed) {
                started.put(replica.containerId(), replica);
                LOG.infof("Cloud Run worker pool replica started pool=%s revision=%s index=%d container=%s",
                        desired.poolName(), revisionId, index, replica.containerId());
                return replica;
            }
        }
        LOG.infof("Cloud Run worker pool replica started during shutdown; removing it container=%s",
                replica.containerId());
        try {
            lifecycleManager.forceRemove(replica.containerId(), null);
        } finally {
            runtimeService.releaseGcsVolumeMounts(mounts);
        }
        throw new IllegalStateException("Cloud Run worker pool runtime is shutting down");
    }

    private void stopReplicas(List<WorkerReplica> toStop) {
        if (toStop.isEmpty()) {
            return;
        }
        try (ExecutorService stopper = Executors.newVirtualThreadPerTaskExecutor()) {
            for (WorkerReplica replica : toStop) {
                stopper.submit(() -> stopReplica(replica));
            }
        }
    }

    private void stopReplica(WorkerReplica replica) {
        int graceSeconds = stopGraceSeconds();
        try {
            lifecycleManager.runDockerApi("stop worker pool container " + replica.containerId(), () -> {
                lifecycleManager.getDockerClient()
                        .stopContainerCmd(replica.containerId())
                        .withTimeout(graceSeconds)
                        .exec();
                return null;
            });
        } catch (Exception e) {
            LOG.debugf(e, "Cloud Run worker pool container stop failed; removing it forcibly container=%s",
                    replica.containerId());
        }
        try {
            lifecycleManager.forceRemove(replica.containerId(), null);
        } finally {
            synchronized (lifecycle) {
                started.remove(replica.containerId());
            }
            runtimeService.releaseGcsVolumeMounts(replica.mounts());
        }
        LOG.infof("Cloud Run worker pool replica stopped revision=%s index=%d container=%s",
                replica.revisionName(), replica.index(), replica.containerId());
    }

    private int stopGraceSeconds() {
        Duration grace = config.services().cloudrun().execution().cleanupTimeout();
        Duration apiTimeout = config.docker().apiTimeout().minus(STOP_API_MARGIN);
        if (grace.compareTo(apiTimeout) > 0) {
            grace = apiTimeout;
        }
        return (int) Math.max(0, grace.toSeconds());
    }

    /**
     * What the pool should be running: {@code revision} null means nothing.
     */
    record DesiredWorkers(String project, String location, String poolName, long generation,
                          Revision revision, int requestedCount) {}

    private record WorkerReplica(String revisionName, int index, String containerId,
                                 List<CloudRunRuntimeVolumeMount> mounts) {}

    private record LeftoverContainer(String id, Map<String, String> labels) {}
}
