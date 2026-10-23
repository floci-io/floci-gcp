package io.floci.gcp.services.compute;

import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Container;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.ContainerTeardown;
import io.floci.gcp.core.common.docker.ContainerBuilder;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerSpec;
import io.floci.gcp.core.common.docker.ContainerStorageHelper;
import io.floci.gcp.core.common.docker.ImageCacheService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs Compute instances as Docker containers when {@code floci-gcp.services.compute.mock} is false.
 *
 * <p>{@link ComputeService} records what each mutation needs as {@link Intent}s and hands them over after the
 * mutation is committed, so a request that fails validation never starts a container. The intents of one
 * operation run in order on a bounded executor, and the operation reaches DONE only once they finish (see
 * {@link #finished}). A container that cannot be created leaves the instance running without one: the API
 * state never depends on Docker. Instance containers carry no state of their own on the resource; they are found
 * by a name derived from project, zone and instance name, and a container another deployment owns under that name
 * (see {@link ContainerStorageHelper#ownedBy}) is never stopped, restarted or removed. Steps for one instance run one
 * at a time, in submission order.
 */
@ApplicationScoped
public class ComputeInstanceRuntime implements ContainerTeardown {
    private static final Logger LOG = Logger.getLogger(ComputeInstanceRuntime.class);
    static final String SERVICE = "compute";
    /** Keeps PID 1 alive and lets {@code docker stop} end it at once instead of after the kill timeout. */
    static final List<String> IDLE_ENTRYPOINT = List.of("/bin/sh", "-c",
            "trap 'exit 0' TERM INT; while :; do sleep 3600 & wait $!; done");
    private static final int STOP_TIMEOUT_SECONDS = 10;

    public enum Kind { LAUNCH, START, STOP, RESTART, REMOVE }

    /** One Docker step for one instance. {@code image}, {@code cpus} and {@code memoryMb} matter for LAUNCH and START. */
    public record Intent(Kind kind, String project, String zone, String name, String image, int cpus, int memoryMb) {}

    /** Progress of one operation's intents. PROVISIONING covers the image pull, STAGING the container create and start. */
    public enum Phase { PROVISIONING, STAGING, DONE }

    private record Tracked(Phase phase, long deadline) {}

    private final EmulatorConfig config;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ImageCacheService imageCache;
    private final Map<String, Tracked> operations = new ConcurrentHashMap<>();
    private final Map<String, Object> instanceLocks = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    /** Set at teardown: a worker still running after it creates no container. */
    private volatile boolean closed;

    @Inject
    public ComputeInstanceRuntime(EmulatorConfig config, ContainerBuilder containerBuilder,
                                  ContainerLifecycleManager lifecycleManager, ImageCacheService imageCache) {
        this(config, containerBuilder, lifecycleManager, imageCache, Executors.newFixedThreadPool(4, daemonThreads()));
    }

    ComputeInstanceRuntime(EmulatorConfig config, ContainerBuilder containerBuilder,
                           ContainerLifecycleManager lifecycleManager, ImageCacheService imageCache,
                           ExecutorService executor) {
        this.config = config;
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.imageCache = imageCache;
        this.executor = executor;
    }

    public boolean enabled() {
        return !config.services().compute().mock();
    }

    /** Runs {@code intents} in order, tracked under {@code operationKey} until {@link #forget} is called. */
    public void submit(String operationKey, List<Intent> intents) {
        long deadline = System.currentTimeMillis() + config.services().compute().launchTimeout().toMillis();
        operations.put(operationKey, new Tracked(Phase.PROVISIONING, deadline));
        try {
            executor.execute(() -> {
                try {
                    for (Intent intent : intents) {
                        run(operationKey, intent);
                    }
                } catch (RuntimeException e) {
                    LOG.warnf("Compute instance container step failed for operation %s: %s", operationKey, e.getMessage());
                } finally {
                    operations.computeIfPresent(operationKey, (key, tracked) -> new Tracked(Phase.DONE, tracked.deadline()));
                }
            });
        } catch (RejectedExecutionException e) {
            LOG.warnf("Compute instance container steps for operation %s skipped: the emulator is shutting down", operationKey);
            operations.computeIfPresent(operationKey, (key, tracked) -> new Tracked(Phase.DONE, tracked.deadline()));
        }
    }

    /**
     * The phase of an operation's intents. An operation this process does not track, because it was never
     * submitted or the emulator restarted while it ran, counts as DONE; so does one past the launch timeout.
     */
    public Phase phase(String operationKey) {
        Tracked tracked = operations.get(operationKey);
        if (tracked == null) { return Phase.DONE; }
        if (tracked.phase() != Phase.DONE && System.currentTimeMillis() > tracked.deadline()) {
            LOG.warnf("Compute operation %s waited longer than %s for its container; continuing without it",
                    operationKey, config.services().compute().launchTimeout());
            operations.put(operationKey, new Tracked(Phase.DONE, tracked.deadline()));
            return Phase.DONE;
        }
        return tracked.phase();
    }

    public boolean finished(String operationKey) {
        return phase(operationKey) == Phase.DONE;
    }

    public void forget(String operationKey) {
        operations.remove(operationKey);
    }

    private void run(String operationKey, Intent intent) {
        String name = containerName(intent.project(), intent.zone(), intent.name());
        synchronized (lock(name)) {
            step(operationKey, intent, name);
        }
    }

    private Object lock(String containerName) {
        return instanceLocks.computeIfAbsent(containerName, key -> new Object());
    }

    private void step(String operationKey, Intent intent, String name) {
        switch (intent.kind()) {
            case LAUNCH -> launch(operationKey, intent, name);
            case START -> {
                Optional<Container> existing = lifecycleManager.findByName(name);
                if (existing.isPresent() && !owned(existing.get(), name)) { return; }
                if (existing.isEmpty()) {
                    launch(operationKey, intent, name);
                } else {
                    stage(operationKey);
                    lifecycleManager.runDockerApi("start Compute instance " + name, () -> {
                        try {
                            lifecycleManager.getDockerClient().startContainerCmd(existing.get().getId()).exec();
                        } catch (NotModifiedException alreadyRunning) {
                            LOG.debugv("Compute instance container {0} was already running", name);
                        }
                        return null;
                    });
                }
            }
            case STOP -> ownedContainer(name).ifPresent(container ->
                    lifecycleManager.runDockerApi("stop Compute instance " + name, () -> {
                        try {
                            lifecycleManager.getDockerClient().stopContainerCmd(container.getId())
                                    .withTimeout(STOP_TIMEOUT_SECONDS).exec();
                        } catch (NotModifiedException alreadyStopped) {
                            LOG.debugv("Compute instance container {0} was already stopped", name);
                        }
                        return null;
                    }));
            case RESTART -> ownedContainer(name).ifPresent(container ->
                    lifecycleManager.runDockerApi("restart Compute instance " + name, () -> {
                        lifecycleManager.getDockerClient().restartContainerCmd(container.getId())
                                .withTimeout(0).exec();
                        return null;
                    }));
            case REMOVE -> ownedContainer(name).ifPresent(container -> lifecycleManager.forceRemove(container.getId(), null));
        }
    }

    /** The container named {@code name} when this deployment owns it; another deployment's is left alone. */
    private Optional<Container> ownedContainer(String name) {
        return lifecycleManager.findByName(name).filter(container -> owned(container, name));
    }

    private boolean owned(Container container, String name) {
        if (ContainerStorageHelper.ownedBy(config, container.getLabels())) { return true; }
        LOG.warnf("Container %s belongs to another floci-gcp deployment on this Docker daemon; leaving it alone", name);
        return false;
    }

    /** Whether the operation stopped waiting for its container (timed out, or already finished and forgotten). */
    private boolean expired(String operationKey) {
        Tracked tracked = operations.get(operationKey);
        return tracked == null || tracked.phase() == Phase.DONE || System.currentTimeMillis() > tracked.deadline();
    }

    private void launch(String operationKey, Intent intent, String name) {
        try {
            imageCache.ensureImageExists(intent.image());
            if (closed) { return; }
            if (expired(operationKey)) {
                LOG.warnf("Compute instance %s/%s/%s: the image arrived after its operation finished; not creating a container",
                        intent.project(), intent.zone(), intent.name());
                return;
            }
            stage(operationKey);
            Optional<Container> existing = lifecycleManager.findByName(name);
            if (existing.isPresent()) {
                if (!owned(existing.get(), name)) { return; }
                lifecycleManager.forceRemove(existing.get().getId(), null);
            }
            lifecycleManager.createAndStart(spec(intent, name));
            LOG.infof("Started Compute instance container=%s image=%s", name, intent.image());
        } catch (RuntimeException e) {
            LOG.warnf("Compute instance %s/%s/%s runs without a container: %s",
                    intent.project(), intent.zone(), intent.name(), e.getMessage());
        }
    }

    ContainerSpec spec(Intent intent, String name) {
        Map<String, String> labels = new LinkedHashMap<>(ContainerStorageHelper.resourceIdentityLabels(
                SERVICE, intent.name(), intent.project(), intent.zone()));
        labels.put(ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config));
        ContainerBuilder.Builder builder = containerBuilder.newContainer(intent.image())
                .withName(name)
                .withEntrypoint(IDLE_ENTRYPOINT)
                .withLabels(labels)
                .withDockerNetwork(config.services().compute().dockerNetwork())
                .withHostDockerInternalOnLinux()
                .withLogRotation();
        if (config.services().compute().instanceResourceLimits()) {
            if (intent.cpus() > 0) { builder.withNanoCpus(intent.cpus() * 1_000_000_000L); }
            if (intent.memoryMb() > 0) { builder.withMemoryMb(intent.memoryMb()); }
        }
        return builder.build();
    }

    private void stage(String operationKey) {
        operations.computeIfPresent(operationKey, (key, tracked) ->
                tracked.phase() == Phase.DONE ? tracked : new Tracked(Phase.STAGING, tracked.deadline()));
    }

    String containerName(String project, String zone, String instance) {
        return ContainerStorageHelper.dockerName(config, SERVICE + "-" + project + "-" + zone + "-" + instance);
    }

    /**
     * Brings this deployment's instance containers in line with the stored instances after a restart: a RUNNING
     * instance's container is started again, a TERMINATED instance's container is stopped if it still runs (the
     * emulator stopped after saving the stop but before Docker stopped it), and a container whose instance no longer
     * exists is removed. {@code status} answers an instance's status, or null when it
     * does not exist. Runs on the executor so startup does not wait for Docker.
     */
    public void reconcile(StatusLookup status) {
        if (!enabled() || !config.services().compute().reconcileContainersOnStartup()) { return; }
        executor.execute(() -> {
            for (Container container : ownedContainers()) {
                Map<String, String> labels = container.getLabels();
                String project = labels.get(ContainerStorageHelper.PROJECT_LABEL);
                String zone = labels.get(ContainerStorageHelper.LOCATION_LABEL);
                String name = labels.get(ContainerStorageHelper.RESOURCE_ID_LABEL);
                // Under the instance's lock, so a step submitted meanwhile (a start, a stop) is not undone here.
                synchronized (lock(containerName(project, zone, name))) {
                    reconcile(container, project, zone, name, status);
                }
            }
        });
    }

    private void reconcile(Container container, String project, String zone, String name, StatusLookup status) {
        String current;
        try {
            current = status.apply(project, zone, name);
        } catch (RuntimeException e) {
            current = null;
        }
        if (current == null) {
            LOG.infof("Removing container of deleted Compute instance container=%s", container.getId());
            lifecycleManager.forceRemove(container.getId(), null);
        } else if (current.equals("TERMINATED") && "running".equals(container.getState())) {
            LOG.infof("Stopping container of stopped Compute instance %s/%s/%s", project, zone, name);
            stop(container.getId());
        } else if (current.equals("RUNNING") && !"running".equals(container.getState())) {
            LOG.infof("Restarting container of running Compute instance %s/%s/%s", project, zone, name);
            try {
                lifecycleManager.runDockerApi("start Compute instance " + name, () -> {
                    try {
                        lifecycleManager.getDockerClient().startContainerCmd(container.getId()).exec();
                    } catch (NotModifiedException alreadyRunning) {
                        LOG.debugv("Compute instance container {0} was already running", name);
                    }
                    return null;
                });
            } catch (RuntimeException e) {
                LOG.warnf("Compute instance %s/%s/%s runs without a container: %s", project, zone, name,
                        e.getMessage());
            }
        }
    }

    /** Answers an instance's status by project, zone and name; null when the instance does not exist. */
    @FunctionalInterface
    public interface StatusLookup {
        String apply(String project, String zone, String name);
    }

    /**
     * Stops this deployment's running instance containers on shutdown; they restart with the emulator. The workers
     * stop first, so no launch creates a container after the list below is taken.
     */
    @Override
    public void stopManagedContainers() {
        if (!enabled()) { return; }
        closed = true;
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOG.warn("Compute instance container steps still running at shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (Container container : ownedContainers()) {
            if ("running".equals(container.getState())) { stop(container.getId()); }
        }
    }

    private void stop(String containerId) {
        try {
            lifecycleManager.runDockerApi("stop Compute instance " + containerId, () -> {
                try {
                    lifecycleManager.getDockerClient().stopContainerCmd(containerId).withTimeout(STOP_TIMEOUT_SECONDS).exec();
                } catch (NotModifiedException alreadyStopped) {
                    LOG.debugv("Compute instance container {0} was already stopped", containerId);
                }
                return null;
            });
        } catch (RuntimeException e) {
            LOG.warnf("Could not stop Compute instance container %s: %s", containerId, e.getMessage());
        }
    }

    private List<Container> ownedContainers() {
        Map<String, String> filter = new LinkedHashMap<>(ContainerStorageHelper.defaultLabels(config));
        filter.put(ContainerStorageHelper.SERVICE_LABEL, SERVICE);
        String namespace = filter.get("floci_namespace");
        try {
            return lifecycleManager.listContainersByLabels("list Compute instance containers", filter).stream()
                    .filter(c -> c.getLabels() != null)
                    .filter(c -> Objects.equals(namespace, c.getLabels().get("floci_namespace")))
                    .filter(c -> ContainerStorageHelper.ownedBy(config, c.getLabels()))
                    .toList();
        } catch (RuntimeException e) {
            LOG.warnf("Could not list Compute instance containers: %s", e.getMessage());
            return List.of();
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static ThreadFactory daemonThreads() {
        AtomicInteger count = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "compute-instance-runtime-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
