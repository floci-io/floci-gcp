package io.floci.gcp.services.compute;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.ContainerTeardown;
import io.floci.gcp.core.common.docker.ContainerBuilder;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerSpec;
import io.floci.gcp.core.common.docker.ContainerStorageHelper;
import io.floci.gcp.core.common.docker.DockerHostResolver;
import io.floci.gcp.core.common.docker.ImageCacheService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

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
    /** The unit konlet's lines carry on the serial console, as the container startup agent's systemd unit. */
    static final String KONLET = "konlet-startup";
    /** Set to {@link #WORKLOAD} on the container a {@code gce-container-declaration} runs, beside the instance's own. */
    static final String ROLE_LABEL = "io.floci.compute.role";
    static final String WORKLOAD = "container";
    static final String DECLARED_NAME_LABEL = "io.floci.compute.container-name";
    /** Docker calls one start can make under {@code startLock}: create, start, network connect, remove on failure. */
    private static final int DOCKER_CALLS_PER_START = 4;

    /** METADATA re-applies the guest's SSH keys after a metadata change, as the guest agent does on its own. */
    public enum Kind { LAUNCH, START, STOP, RESTART, REMOVE, METADATA }

    /**
     * One Docker step for one instance. {@code image}, {@code cpus} and {@code memoryMb} matter for LAUNCH and START.
     * {@code metadata} is the guest's effective metadata when the step was recorded: project keys overridden by
     * instance keys. It supplies the startup scripts of a boot and the shutdown scripts of a stop or delete.
     * {@code ports} are the container TCP ports a new container publishes on Docker-assigned host ports. SSH keys are
     * not carried here: each account update reads the instance's current keys when it runs.
     */
    public record Intent(Kind kind, String project, String zone, String name, String image, int cpus, int memoryMb,
                         Map<String, String> metadata, List<Integer> ports) {

        /** Publishes no ports, as a boot after an emulator restart does (its container keeps the ones it has). */
        public Intent(Kind kind, String project, String zone, String name, String image, int cpus, int memoryMb,
                      Map<String, String> metadata) {
            this(kind, project, zone, name, image, cpus, memoryMb, metadata, List.of());
        }
    }

    /** Progress of one operation's intents. PROVISIONING covers the image pull, STAGING the container create and start. */
    public enum Phase { PROVISIONING, STAGING, DONE }

    private record Tracked(Phase phase, long deadline) {}

    private final EmulatorConfig config;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ImageCacheService imageCache;
    private final ComputeGuestScripts scripts;
    private final DockerHostResolver dockerHost;
    private final Map<String, Tracked> operations = new ConcurrentHashMap<>();
    private final Map<String, Object> instanceLocks = new ConcurrentHashMap<>();
    private final Map<String, ComputeSerialPort> serialPorts = new ConcurrentHashMap<>();
    /** Each instance's current boot; stop, reset and delete end it, so a startup task queued for it is dropped. */
    private final Map<String, AtomicLong> boots = new ConcurrentHashMap<>();
    /** Container addresses of running instances, so the metadata server knows which instance is asking. */
    private final Map<String, InstanceRef> addresses = new ConcurrentHashMap<>();
    /** The newest account update asked for per instance; an older one still queued is skipped. */
    private final Map<String, AtomicLong> accountRequests = new ConcurrentHashMap<>();
    /** One account update at a time per instance, apart from the instance lock so a stop is never held up by one. */
    private final Map<String, Object> accountLocks = new ConcurrentHashMap<>();
    private volatile SshKeyLookup sshKeys = (project, zone, name) -> null;
    /** Log streams copying each running declared container's output to serial port 1. */
    private final Map<String, Closeable> workloadLogs = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final ExecutorService scriptExecutor;
    /** Set at teardown: a worker still running after it creates no container. */
    private volatile boolean closed;
    /** Held shared while a step brings a container up; teardown takes it exclusively before listing containers. */
    private final ReadWriteLock startLock = new ReentrantReadWriteLock();

    @Inject
    public ComputeInstanceRuntime(EmulatorConfig config, ContainerBuilder containerBuilder,
                                  ContainerLifecycleManager lifecycleManager, ImageCacheService imageCache,
                                  ComputeGuestScripts scripts, DockerHostResolver dockerHost) {
        this(config, containerBuilder, lifecycleManager, imageCache, scripts, dockerHost,
                Executors.newFixedThreadPool(4, daemonThreads("compute-instance-runtime-")),
                // Startup scripts may run for minutes; one instance's must not hold back another's.
                Executors.newCachedThreadPool(daemonThreads("compute-startup-scripts-")));
    }

    ComputeInstanceRuntime(EmulatorConfig config, ContainerBuilder containerBuilder,
                           ContainerLifecycleManager lifecycleManager, ImageCacheService imageCache,
                           ComputeGuestScripts scripts, DockerHostResolver dockerHost, ExecutorService executor,
                           ExecutorService scriptExecutor) {
        this.config = config;
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.imageCache = imageCache;
        this.scripts = scripts;
        this.dockerHost = dockerHost;
        this.executor = executor;
        this.scriptExecutor = scriptExecutor;
    }

    /** An instance by project, zone and name. */
    public record InstanceRef(String project, String zone, String name) {}

    /** The instance whose container has this address, if any. */
    public Optional<InstanceRef> instanceAt(String address) {
        return Optional.ofNullable(addresses.get(address));
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

    boolean closed() {
        return closed;
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
            if (closed) { return; }
            InstanceRef instance = new InstanceRef(intent.project(), intent.zone(), intent.name());
            try {
                step(operationKey, intent, name);
            } finally {
                if (closed) {
                    // Teardown began during this step and may have listed containers before it started this one.
                    ownedContainer(name).filter(container -> "running".equals(container.getState()))
                            .ifPresent(container -> stop(container.getId()));
                } else if (intent.kind() == Kind.STOP || intent.kind() == Kind.REMOVE) {
                    // Only now: the shutdown scripts ran inside the step and could still read their metadata.
                    addresses.values().removeIf(instance::equals);
                } else {
                    register(name, instance);
                }
            }
        }
    }

    private Object lock(String containerName) {
        return instanceLocks.computeIfAbsent(containerName, key -> new Object());
    }

    private void step(String operationKey, Intent intent, String name) {
        switch (intent.kind()) {
            case LAUNCH -> boot(intent, launch(operationKey, intent, name));
            case START -> {
                Optional<Container> existing = lifecycleManager.findByName(name);
                if (existing.isPresent() && !owned(existing.get(), name)) { return; }
                if (existing.isEmpty()) {
                    boot(intent, launch(operationKey, intent, name));
                } else {
                    stage(operationKey);
                    if (bringUp(() -> lifecycleManager.runDockerApi("start Compute instance " + name, () -> {
                        try {
                            lifecycleManager.getDockerClient().startContainerCmd(existing.get().getId()).exec();
                        } catch (NotModifiedException alreadyRunning) {
                            LOG.debugv("Compute instance container {0} was already running", name);
                        }
                        return null;
                    }))) {
                        boot(intent, existing.get().getId());
                    }
                }
            }
            case STOP -> ownedContainer(name).ifPresent(container -> {
                endBoot(intent);
                runShutdownScripts(intent, container);
                removeWorkload(intent.project(), intent.zone(), intent.name());
                lifecycleManager.runDockerApi("stop Compute instance " + name, () -> {
                    try {
                        lifecycleManager.getDockerClient().stopContainerCmd(container.getId())
                                .withTimeout(STOP_TIMEOUT_SECONDS).exec();
                    } catch (NotModifiedException alreadyStopped) {
                        LOG.debugv("Compute instance container {0} was already stopped", name);
                    }
                    return null;
                });
            });
            // A reset is a hard reset: no shutdown scripts, then a fresh boot.
            case RESTART -> ownedContainer(name).ifPresent(container -> {
                // The declared container shares the instance's network, which a restart replaces; the boot starts a new one.
                removeWorkload(intent.project(), intent.zone(), intent.name());
                if (bringUp(() -> lifecycleManager.runDockerApi("restart Compute instance " + name, () -> {
                    lifecycleManager.getDockerClient().restartContainerCmd(container.getId())
                            .withTimeout(0).exec();
                    return null;
                }))) {
                    boot(intent, container.getId());
                }
            });
            case METADATA -> ownedContainer(name).filter(container -> "running".equals(container.getState()))
                    .ifPresent(container -> applyAccounts(intent, container.getId()));
            case REMOVE -> {
                endBoot(intent);
                ownedContainer(name).ifPresent(container -> {
                    runShutdownScripts(intent, container);
                    lifecycleManager.forceRemove(container.getId(), null);
                });
                removeWorkload(intent.project(), intent.zone(), intent.name());
                boots.remove(instanceKey(intent.project(), intent.zone(), intent.name()));
                accountRequests.remove(instanceKey(intent.project(), intent.zone(), intent.name()));
                serialPorts.remove(instanceKey(intent.project(), intent.zone(), intent.name()));
            }
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

    /** Creates and starts the instance container; returns its id, or null when the instance runs without one. */
    private String launch(String operationKey, Intent intent, String name) {
        try {
            imageCache.ensureImageExists(intent.image());
            if (closed) { return null; }
            if (expired(operationKey)) {
                LOG.warnf("Compute instance %s/%s/%s: the image arrived after its operation finished; not creating a container",
                        intent.project(), intent.zone(), intent.name());
                return null;
            }
            stage(operationKey);
            Optional<Container> existing = lifecycleManager.findByName(name);
            if (existing.isPresent()) {
                if (!owned(existing.get(), name)) { return null; }
                removeWorkload(intent.project(), intent.zone(), intent.name());
                lifecycleManager.forceRemove(existing.get().getId(), null);
            }
            AtomicReference<String> started = new AtomicReference<>();
            bringUp(() -> {
                started.set(lifecycleManager.createAndStart(spec(intent, name)).containerId());
                LOG.infof("Started Compute instance container=%s image=%s", name, intent.image());
            });
            return started.get();
        } catch (RuntimeException e) {
            LOG.warnf("Compute instance %s/%s/%s runs without a container: %s",
                    intent.project(), intent.zone(), intent.name(), e.getMessage());
            return null;
        }
    }

    /**
     * Runs the startup scripts of a boot in the background, then the declared container if the metadata has a
     * {@code gce-container-declaration}: konlet starts after the startup scripts. The boot's operation waits for
     * neither, as on GCE.
     */
    private void boot(Intent intent, String containerId) {
        if (containerId == null) { return; }
        // Before the scripts are queued, so their first metadata read already finds the instance.
        register(containerId, new InstanceRef(intent.project(), intent.zone(), intent.name()));
        String key = instanceKey(intent.project(), intent.zone(), intent.name());
        long boot = boots.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        ComputeSerialPort port = serialPort(intent.project(), intent.zone(), intent.name());
        // Checked before every step: the instance may stop, reset or be deleted while this boot's scripts wait or run.
        BooleanSupplier current = () -> {
            AtomicLong latest = boots.get(key);
            return latest != null && latest.get() == boot;
        };
        String mark = bootMark(containerId);
        reportPorts(intent, containerId, port);
        // Apart from the scripts, as the guest agent runs beside the script runner: a slow sshd install holds back neither.
        applyAccounts(intent, containerId);
        scriptExecutor.execute(() -> {
            scripts.run(ComputeGuestScripts.Phase.STARTUP, intent.name(), containerId, intent.metadata(),
                    config.services().compute().startupScriptTimeout(), port, current, mark);
            startWorkload(intent, containerId, port, current);
        });
    }

    /** Supplies the instance's current {@code ssh-keys} lines to each account update; see {@link SshKeyLookup}. */
    public void sshKeys(SshKeyLookup lookup) {
        this.sshKeys = lookup;
    }

    /**
     * Brings the guest's accounts in line with the instance's current SSH keys, in the background. Updates for one
     * instance run one at a time and read the keys as they start, so a slow one can never put back keys a newer
     * metadata change removed; one that a newer request is already queued behind is skipped.
     */
    private void applyAccounts(Intent intent, String containerId) {
        String key = instanceKey(intent.project(), intent.zone(), intent.name());
        AtomicLong requests = accountRequests.computeIfAbsent(key, k -> new AtomicLong());
        // Time-based, so a request after an emulator restart still outranks those its guest saw before.
        long request = requests.updateAndGet(previous -> Math.max(previous + 1, System.currentTimeMillis()));
        ComputeSerialPort port = serialPort(intent.project(), intent.zone(), intent.name());
        try {
            scriptExecutor.execute(() -> {
                synchronized (accountLocks.computeIfAbsent(key, k -> new Object())) {
                    if (requests.get() != request || closed) { return; }
                    List<String> lines = sshKeys.apply(intent.project(), intent.zone(), intent.name());
                    Map<String, List<String>> users = lines == null ? null : ComputeSshKeys.users(lines, Instant.now());
                    scripts.accounts(intent.name(), containerId, users, request, port);
                }
            });
        } catch (RejectedExecutionException e) {
            LOG.debugv("SSH keys of Compute instance {0} not applied: the emulator is shutting down", intent.name());
        }
    }

    /** Logs and writes to serial port 1 the host port Docker gave each published container port. */
    private void reportPorts(Intent intent, String containerId, ComputeSerialPort port) {
        Map<Integer, Integer> published = publishedPorts(containerId);
        if (published.isEmpty()) { return; }
        String list = published.entrySet().stream()
                .map(entry -> entry.getKey() + "/tcp -> localhost:" + entry.getValue())
                .collect(Collectors.joining(", "));
        LOG.infof("Compute instance %s/%s/%s ports: %s", intent.project(), intent.zone(), intent.name(), list);
        port.write(ComputeGuestScripts.line(intent.name(), "floci-gcp", "Published ports: " + list));
    }

    /** Container TCP port to the host port Docker bound it to, for the instance's running container. */
    Map<Integer, Integer> publishedPorts(String containerId) {
        Map<Integer, Integer> published = new TreeMap<>();
        try {
            InspectContainerResponse inspect = lifecycleManager.runDockerApi("inspect Compute instance " + containerId,
                    () -> lifecycleManager.getDockerClient().inspectContainerCmd(containerId).exec());
            if (inspect.getNetworkSettings() == null || inspect.getNetworkSettings().getPorts() == null) { return published; }
            inspect.getNetworkSettings().getPorts().getBindings().forEach((exposed, bindings) -> {
                if (bindings == null || bindings.length == 0 || bindings[0].getHostPortSpec() == null) { return; }
                try {
                    published.put(exposed.getPort(), Integer.parseInt(bindings[0].getHostPortSpec()));
                } catch (NumberFormatException ignored) {
                    LOG.debugv("Unreadable host port {0} on container {1}", bindings[0].getHostPortSpec(), containerId);
                }
            });
        } catch (RuntimeException e) {
            LOG.debugv("Could not read the published ports of Compute instance container {0}: {1}", containerId,
                    e.getMessage());
        }
        return published;
    }

    /**
     * Starts the container a {@code gce-container-declaration} declares, as konlet does at each boot: the declaration
     * is read now, so a change made with setMetadata applies at the next start or reset. It runs in the instance
     * container's network, as konlet's runs in the VM's host network, and its output goes to serial port 1.
     */
    private void startWorkload(Intent intent, String vmContainerId, ComputeSerialPort port, BooleanSupplier current) {
        String value = intent.metadata() == null ? null : intent.metadata().get(ComputeContainerDeclaration.KEY);
        if (value == null) { return; }
        String instance = intent.name();
        try {
            ComputeContainerDeclaration declaration = ComputeContainerDeclaration.parse(value);
            String workload = workloadName(intent.project(), intent.zone(), intent.name());
            port.write(ComputeGuestScripts.line(instance, KONLET, "Configured container '" + declaration.name()
                    + "' will be started with name '" + workload + "'."));
            for (String skipped : declaration.skipped()) {
                port.write(ComputeGuestScripts.line(instance, KONLET, "Not mounting " + skipped + "."));
            }
            if (declaration.privileged() && !config.services().compute().allowPrivilegedContainers()) {
                port.write(ComputeGuestScripts.line(instance, KONLET, "Not running container '" + declaration.name()
                        + "' privileged: it would be privileged on the Docker host. Set"
                        + " FLOCI_GCP_SERVICES_COMPUTE_ALLOW_PRIVILEGED_CONTAINERS=true to allow it."));
            }
            ContainerSpec spec = workloadSpec(intent, declaration, workload, vmContainerId);
            port.write(ComputeGuestScripts.line(instance, KONLET, "Pulling image: '" + declaration.image() + "'"));
            // Outside the instance's lock, so a stop is not held up by a slow pull.
            imageCache.ensureImageExists(spec.image());
            synchronized (lock(containerName(intent.project(), intent.zone(), intent.name()))) {
                if (closed || !current.getAsBoolean()) { return; }
                removeWorkload(intent.project(), intent.zone(), intent.name());
                AtomicReference<String> started = new AtomicReference<>();
                if (!bringUp(() -> started.set(lifecycleManager.createAndStart(spec).containerId()))) { return; }
                port.write(ComputeGuestScripts.line(instance, KONLET, "Started a container with ID: " + started.get()));
                Closeable logs = followLogs(started.get(), port, instance, declaration.name(), null);
                if (logs != null) {
                    workloadLogs.put(instanceKey(intent.project(), intent.zone(), intent.name()), logs);
                }
            }
        } catch (RuntimeException e) {
            LOG.warnf("Compute instance %s could not start its declared container: %s", instance, e.getMessage());
            port.write(ComputeGuestScripts.line(instance, KONLET, "Error: Failed to start container: " + e.getMessage()));
        }
    }

    ContainerSpec workloadSpec(Intent intent, ComputeContainerDeclaration declaration, String name, String vmContainerId) {
        Map<String, String> labels = new LinkedHashMap<>(ContainerStorageHelper.resourceIdentityLabels(
                SERVICE, intent.name(), intent.project(), intent.zone()));
        labels.put(ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config));
        labels.put(ROLE_LABEL, WORKLOAD);
        if (declaration.name() != null) { labels.put(DECLARED_NAME_LABEL, declaration.name()); }
        ContainerBuilder.Builder builder = containerBuilder.newContainer(declaration.image())
                .withName(name)
                .withLabels(labels)
                .withNetworkMode("container:" + vmContainerId)
                .withLogRotation()
                .withRestartPolicy(declaration.restart())
                .withTty(declaration.tty())
                .withStdinOpen(declaration.stdin())
                .withPrivileged(declaration.privileged() && config.services().compute().allowPrivilegedContainers());
        if (declaration.command() != null) { builder.withEntrypoint(declaration.command()); }
        if (declaration.args() != null) { builder.withCmd(declaration.args()); }
        if (config.services().compute().metadataServer().enabled()) {
            String host = dockerHost.resolve() + ":" + config.port();
            builder.withEnv("GCE_METADATA_HOST", host).withEnv("GCE_METADATA_IP", host);
        }
        builder.withEnv(declaration.env());
        for (ComputeContainerDeclaration.Tmpfs tmpfs : declaration.tmpfs()) {
            builder.withMount(new Mount().withType(MountType.TMPFS).withTarget(tmpfs.path()).withReadOnly(tmpfs.readOnly()));
        }
        if (config.services().compute().instanceResourceLimits()) {
            long nanoCpus = intent.cpus() * 1_000_000_000L;
            long memoryBytes = intent.memoryMb() * 1024L * 1024L;
            if (nanoCpus <= 0 || memoryBytes <= 0) {
                // A boot after an emulator restart knows only the instance; its container keeps the machine's limits.
                HostConfig vm = vmHostConfig(vmContainerId);
                if (nanoCpus <= 0 && vm != null && vm.getNanoCPUs() != null) { nanoCpus = vm.getNanoCPUs(); }
                if (memoryBytes <= 0 && vm != null && vm.getMemory() != null) { memoryBytes = vm.getMemory(); }
            }
            if (nanoCpus > 0) { builder.withNanoCpus(nanoCpus); }
            if (memoryBytes > 0) { builder.withMemoryBytes(memoryBytes); }
        }
        return builder.build();
    }

    private HostConfig vmHostConfig(String vmContainerId) {
        try {
            return lifecycleManager.runDockerApi("inspect Compute instance " + vmContainerId,
                    () -> lifecycleManager.getDockerClient().inspectContainerCmd(vmContainerId).exec()).getHostConfig();
        } catch (RuntimeException e) {
            LOG.debugv("Could not read the limits of Compute instance container {0}: {1}", vmContainerId, e.getMessage());
            return null;
        }
    }

    /**
     * Copies a declared container's output to serial port 1, one line at a time, until the container goes away;
     * from {@code since} (epoch seconds) when set, so output already copied before an emulator restart is not repeated.
     */
    private Closeable followLogs(String containerId, ComputeSerialPort port, String instance, String declaredName,
                                 Integer since) {
        ComputeGuestScripts.LineWriter out = new ComputeGuestScripts.LineWriter(port, instance,
                declaredName == null ? WORKLOAD : declaredName, "");
        try {
            LogContainerCmd command = lifecycleManager.getDockerClient().logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(true);
            if (since != null) { command.withSince(since); }
            return command.exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            for (byte b : frame.getPayload()) { out.write(b); }
                        }

                        @Override
                        public void onComplete() {
                            out.close();
                            super.onComplete();
                        }
                    });
        } catch (RuntimeException e) {
            LOG.debugv("Could not follow the output of container {0}: {1}", containerId, e.getMessage());
            return null;
        }
    }

    /** Removes the instance's declared container, if it has one. */
    private void removeWorkload(String project, String zone, String instance) {
        Closeable logs = workloadLogs.remove(instanceKey(project, zone, instance));
        if (logs != null) {
            try {
                logs.close();
            } catch (IOException | RuntimeException e) {
                LOG.debugv("Could not close the output stream of Compute instance {0}: {1}", instance, e.getMessage());
            }
        }
        ownedContainer(workloadName(project, zone, instance))
                .ifPresent(container -> lifecycleManager.forceRemove(container.getId(), null));
    }

    /**
     * The start time of the container's PID 1, read while the step still holds the instance's lock, so it names this
     * boot; null when it cannot be read, and the scripts then run without the in-container boot check.
     */
    private String bootMark(String containerId) {
        try {
            ContainerLifecycleManager.ExecResult result = lifecycleManager.exec(containerId, List.of(),
                    List.of("cut", "-d", " ", "-f22", "/proc/1/stat"));
            String mark = result == null ? "" : result.stdout().strip();
            return result != null && result.exitCode() == 0 && !mark.isEmpty() ? mark : null;
        } catch (RuntimeException e) {
            LOG.debugv("Could not read the boot of Compute instance container {0}: {1}", containerId, e.getMessage());
            return null;
        }
    }

    /** Ends the instance's current boot, so startup scripts still queued for it never run. */
    private void endBoot(Intent intent) {
        AtomicLong current = boots.get(instanceKey(intent.project(), intent.zone(), intent.name()));
        if (current != null) { current.incrementAndGet(); }
    }

    /**
     * Runs the shutdown scripts of a running container and waits for them at most the shutdown script timeout, however
     * long their preparation takes; the container is stopped after that, which ends anything still running.
     */
    private void runShutdownScripts(Intent intent, Container container) {
        if (!"running".equals(container.getState())) { return; }
        Duration timeout = config.services().compute().shutdownScriptTimeout();
        ComputeSerialPort port = serialPort(intent.project(), intent.zone(), intent.name());
        Future<?> shutdown = scriptExecutor.submit(() -> scripts.run(ComputeGuestScripts.Phase.SHUTDOWN,
                intent.name(), container.getId(), intent.metadata(), timeout, port, () -> true, null));
        try {
            shutdown.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            shutdown.cancel(true);
            port.write(ComputeGuestScripts.line(intent.name(),
                    "Shutdown scripts still running after " + timeout + "; stopping the instance."));
        } catch (ExecutionException e) {
            LOG.warnf("Shutdown scripts of Compute instance %s failed: %s", intent.name(), e.getMessage());
        } catch (InterruptedException e) {
            shutdown.cancel(true);
            Thread.currentThread().interrupt();
        }
    }

    /** Serial port output of an instance, empty for an instance that never had a container in this process. */
    public ComputeSerialPort.Output serialOutput(String project, String zone, String name, int port, long start) {
        ComputeSerialPort serial = port == 1 ? serialPorts.get(instanceKey(project, zone, name)) : null;
        return serial == null ? new ComputeSerialPort().read(start) : serial.read(start);
    }

    private ComputeSerialPort serialPort(String project, String zone, String name) {
        return serialPorts.computeIfAbsent(instanceKey(project, zone, name), key -> new ComputeSerialPort());
    }

    private static String instanceKey(String project, String zone, String name) {
        return project + "/" + zone + "/" + name;
    }

    /**
     * Records the network addresses of the instance's running container, replacing any it had before. The new
     * addresses go in before the old ones go out, so a guest script reading its metadata meanwhile is never refused.
     */
    private void register(String containerName, InstanceRef instance) {
        try {
            InspectContainerResponse inspect = lifecycleManager.runDockerApi("inspect Compute instance " + containerName,
                    () -> lifecycleManager.getDockerClient().inspectContainerCmd(containerName).exec());
            Set<String> current = new HashSet<>();
            if (inspect.getState() != null && Boolean.TRUE.equals(inspect.getState().getRunning())
                    && inspect.getNetworkSettings() != null && inspect.getNetworkSettings().getNetworks() != null) {
                inspect.getNetworkSettings().getNetworks().values().stream()
                        .map(network -> network.getIpAddress())
                        .filter(ip -> ip != null && !ip.isBlank())
                        .forEach(current::add);
            }
            current.forEach(ip -> addresses.put(ip, instance));
            addresses.entrySet().removeIf(entry -> entry.getValue().equals(instance) && !current.contains(entry.getKey()));
        } catch (RuntimeException e) {
            LOG.debugv("Could not read the addresses of Compute instance container {0}: {1}", containerName, e.getMessage());
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
        if (config.services().compute().metadataServer().enabled()) {
            String host = dockerHost.resolve() + ":" + config.port();
            builder.withEnv("GCE_METADATA_HOST", host).withEnv("GCE_METADATA_IP", host);
        }
        if (config.services().compute().instanceResourceLimits()) {
            if (intent.cpus() > 0) { builder.withNanoCpus(intent.cpus() * 1_000_000_000L); }
            if (intent.memoryMb() > 0) { builder.withMemoryMb(intent.memoryMb()); }
        }
        for (int port : intent.ports()) { builder.withDynamicPort(port); }
        return builder.build();
    }

    private void stage(String operationKey) {
        operations.computeIfPresent(operationKey, (key, tracked) ->
                tracked.phase() == Phase.DONE ? tracked : new Tracked(Phase.STAGING, tracked.deadline()));
    }

    String containerName(String project, String zone, String instance) {
        return ContainerStorageHelper.dockerName(config, SERVICE + "-" + project + "-" + zone + "-" + instance);
    }

    /** The declared container's name; instance names have no underscore, so it cannot be another instance's. */
    String workloadName(String project, String zone, String instance) {
        return containerName(project, zone, instance) + "_klt";
    }

    /**
     * Brings this deployment's instance containers in line with the stored instances after a restart: a RUNNING
     * instance's container is started again, a TERMINATED instance's container is stopped if it still runs (the
     * emulator stopped after saving the stop but before Docker stopped it), and a container whose instance no longer
     * exists is removed. {@code status} answers an instance's status, or null when it
     * does not exist. Runs on the executor so startup does not wait for Docker.
     */
    public void reconcile(StatusLookup status) {
        reconcile(status, (project, zone, name) -> Map.of());
    }

    /**
     * As {@link #reconcile(StatusLookup)}; a RUNNING instance whose container is started again also runs its startup
     * scripts, with the metadata {@code metadata} answers, since to the guest that start is a boot.
     */
    public void reconcile(StatusLookup status, MetadataLookup metadata) {
        if (!enabled() || !config.services().compute().reconcileContainersOnStartup()) { return; }
        executor.execute(() -> {
            for (Container container : ownedContainers()) {
                Map<String, String> labels = container.getLabels();
                String project = labels.get(ContainerStorageHelper.PROJECT_LABEL);
                String zone = labels.get(ContainerStorageHelper.LOCATION_LABEL);
                String name = labels.get(ContainerStorageHelper.RESOURCE_ID_LABEL);
                // Under the instance's lock, so a step submitted meanwhile (a start, a stop) is not undone here.
                synchronized (lock(containerName(project, zone, name))) {
                    if (WORKLOAD.equals(labels.get(ROLE_LABEL))) {
                        reconcileWorkload(container, project, zone, name, status);
                    } else {
                        reconcile(container, project, zone, name, status, metadata);
                    }
                }
            }
        });
    }

    private void reconcile(Container container, String project, String zone, String name, StatusLookup status,
                           MetadataLookup metadata) {
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
        } else if (current.equals("RUNNING") && "running".equals(container.getState())) {
            register(container.getId(), new InstanceRef(project, zone, name));
        } else if (current.equals("RUNNING")) {
            LOG.infof("Restarting container of running Compute instance %s/%s/%s", project, zone, name);
            try {
                boolean started = bringUp(() -> lifecycleManager.runDockerApi("start Compute instance " + name, () -> {
                    try {
                        lifecycleManager.getDockerClient().startContainerCmd(container.getId()).exec();
                    } catch (NotModifiedException alreadyRunning) {
                        LOG.debugv("Compute instance container {0} was already running", name);
                    }
                    return null;
                }));
                if (started) {
                    boot(new Intent(Kind.START, project, zone, name, null, 0, 0, metadata.apply(project, zone, name)),
                            container.getId());
                    register(container.getId(), new InstanceRef(project, zone, name));
                }
            } catch (RuntimeException e) {
                LOG.warnf("Compute instance %s/%s/%s runs without a container: %s", project, zone, name,
                        e.getMessage());
            }
        }
    }

    /**
     * A declared container left from before a restart: removed unless its instance is RUNNING. A running one is kept
     * and its output followed again (an instance whose container restarts gets a new one at its boot instead).
     */
    private void reconcileWorkload(Container container, String project, String zone, String name, StatusLookup status) {
        String current;
        try {
            current = status.apply(project, zone, name);
        } catch (RuntimeException e) {
            current = null;
        }
        if (!"RUNNING".equals(current)) {
            LOG.infof("Removing declared container of Compute instance %s/%s/%s", project, zone, name);
            lifecycleManager.forceRemove(container.getId(), null);
        } else if ("running".equals(container.getState())) {
            String key = instanceKey(project, zone, name);
            if (workloadLogs.containsKey(key)) { return; }
            Closeable logs = followLogs(container.getId(), serialPort(project, zone, name), name,
                    container.getLabels().get(DECLARED_NAME_LABEL), (int) (System.currentTimeMillis() / 1000));
            if (logs != null) { workloadLogs.put(key, logs); }
        }
    }

    /**
     * Answers an instance's {@code ssh-keys} lines (instance then project, as the guest agent combines them), or null
     * to leave the guest's accounts alone: OS Login is on, or the instance does not exist.
     */
    @FunctionalInterface
    public interface SshKeyLookup {
        List<String> apply(String project, String zone, String name);
    }

    /** Answers an instance's status by project, zone and name; null when the instance does not exist. */
    @FunctionalInterface
    public interface StatusLookup {
        String apply(String project, String zone, String name);
    }

    /** Answers an instance's guest metadata (project keys overridden by instance keys) by project, zone and name. */
    @FunctionalInterface
    public interface MetadataLookup {
        Map<String, String> apply(String project, String zone, String name);
    }

    /**
     * Stops this deployment's running instance containers on shutdown; they restart with the emulator. Steps are not
     * interrupted: Docker may still complete an interrupted create or start. Once {@code closed} is set no step brings
     * a container up, and taking {@code startLock} waits for one already doing so. That wait covers every Docker call a
     * start can make, each bounded by the Docker API timeout, so with a responsive daemon the list below includes every
     * container this process started; past it, teardown logs that a container may be left running. A step still
     * pulling its image is not waited for; it sees {@code closed} after the pull and creates nothing.
     */
    @Override
    public void stopManagedContainers() {
        if (!enabled()) { return; }
        closed = true;
        executor.shutdown();
        boolean quiet = false;
        try {
            quiet = startLock.writeLock().tryLock(
                    DOCKER_CALLS_PER_START * config.docker().apiTimeout().toMillis()
                            + TimeUnit.SECONDS.toMillis(STOP_TIMEOUT_SECONDS),
                    TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!quiet) {
            LOG.warn("A Compute instance container was still starting at shutdown and may be left running");
        }
        try {
            for (Container container : ownedContainers()) {
                if ("running".equals(container.getState())) { stop(container.getId()); }
            }
        } finally {
            if (quiet) { startLock.writeLock().unlock(); }
        }
    }

    /**
     * Runs a step that brings a container up, unless teardown has begun; see {@link #stopManagedContainers}.
     * Returns whether it ran, so a boot only runs startup scripts in a container that came up.
     */
    private boolean bringUp(Runnable action) {
        startLock.readLock().lock();
        try {
            if (closed) { return false; }
            action.run();
            return true;
        } finally {
            startLock.readLock().unlock();
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
    void close() {
        executor.shutdownNow();
        scriptExecutor.shutdownNow();
    }

    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger count = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
