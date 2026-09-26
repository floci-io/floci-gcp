package io.floci.gcp.services.cloudrun;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.google.cloud.run.v2.Container;
import com.google.cloud.run.v2.Execution;
import com.google.cloud.run.v2.Task;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerSpec;
import io.floci.gcp.core.common.docker.ContainerStorageHelper;
import io.floci.gcp.core.common.docker.ImageCacheService;
import io.floci.gcp.services.cloudrun.CloudRunExecutionCoordinator.Events;
import io.floci.gcp.services.cloudrun.CloudRunExecutionCoordinator.TaskHandle;
import io.floci.gcp.services.cloudrun.CloudRunExecutionCoordinator.TaskOutcome;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

// com.github.dockerjava.api.model.Container is written fully qualified: it collides with the imported
// com.google.cloud.run.v2.Container.

/**
 * Runs Cloud Run job task attempts as Docker containers. Each attempt gets a fresh container on its own virtual
 * thread; the outcome (exit code, timeout, stop, start failure) is reported to the execution coordinator only after
 * the container is removed and its GCS volumes are written back.
 */
@ApplicationScoped
public class CloudRunJobsRuntime implements CloudRunExecutionCoordinator.TaskRunner {

    private static final Logger LOG = Logger.getLogger(CloudRunJobsRuntime.class);
    private static final long POLL_INTERVAL_MILLIS = 200;
    private static final long LOG_DRAIN_SECONDS = 2;
    private static final Duration SHUTDOWN_DRAIN = Duration.ofSeconds(10);
    private static final Pattern TASK_RESOURCE =
            Pattern.compile("projects/[^/]+/locations/[^/]+/jobs/[^/]+/executions/[^/]+/tasks/[^/]+");

    private final CloudRunRuntimeService runtimeService;
    private final ContainerLifecycleManager lifecycleManager;
    private final ImageCacheService imageCacheService;
    private final EmulatorConfig config;
    private final Map<String, Attempt> active = new ConcurrentHashMap<>();
    private volatile boolean shuttingDown;

    @Inject
    public CloudRunJobsRuntime(CloudRunRuntimeService runtimeService,
                               ContainerLifecycleManager lifecycleManager,
                               ImageCacheService imageCacheService,
                               EmulatorConfig config) {
        this.runtimeService = runtimeService;
        this.lifecycleManager = lifecycleManager;
        this.imageCacheService = imageCacheService;
        this.config = config;
    }

    @Override
    public void prepare(Execution execution, Events events) {
        Thread.ofVirtual()
                .name("cloudrun-job-prepare-" + CloudRunRuntimeService.lastSegment(execution.getName()))
                .start(() -> {
                    try {
                        for (Container container : execution.getTemplate().getContainersList()) {
                            imageCacheService.ensureImageExists(container.getImage());
                        }
                        events.prepared();
                    } catch (RuntimeException e) {
                        LOG.warnf(e, "Cloud Run job image preparation failed execution=%s", execution.getName());
                        events.prepareFailed(message(e));
                    }
                });
    }

    @Override
    public TaskHandle launch(Execution execution, Task task, int attempt, Events events) {
        Attempt handle = new Attempt();
        String taskId = CloudRunRuntimeService.lastSegment(task.getName());
        String key = task.getName() + "#" + attempt;
        active.put(key, handle);
        Thread.ofVirtual()
                .name("cloudrun-task-" + taskId + "-" + attempt)
                .start(() -> run(key, handle, execution, task, attempt, events));
        return handle;
    }

    /**
     * Removes every task container; used on emulator shutdown. An attempt still creating its container removes it
     * itself as soon as the create returns, and this waits a bounded time for those attempts to finish.
     */
    void stopAll() {
        shuttingDown = true;
        for (Attempt attempt : List.copyOf(active.values())) {
            String containerId = attempt.containerId;
            if (containerId != null) {
                lifecycleManager.forceRemove(containerId, null);
            }
        }
        Instant limit = Instant.now().plus(SHUTDOWN_DRAIN);
        while (!active.isEmpty() && Instant.now().isBefore(limit)) {
            if (!sleep(POLL_INTERVAL_MILLIS)) {
                break;
            }
        }
        if (!active.isEmpty()) {
            LOG.warnf("Cloud Run job task attempts still running at shutdown attempts=%s", active.keySet());
        }
    }

    /**
     * Removes the task containers of this emulator (same {@code floci_emulator} and {@code floci_namespace}
     * labels) left behind by a previous process that did not shut down cleanly. Job task containers are never
     * adopted: every execution that was running is failed by startup reconciliation, so any such container found
     * at startup is an orphan.
     */
    void removeOrphanedContainers() {
        Map<String, String> labels = new LinkedHashMap<>(ContainerStorageHelper.defaultLabels(config));
        labels.put("floci_service", "cloudrun");
        List<com.github.dockerjava.api.model.Container> containers;
        try {
            containers = lifecycleManager.runDockerApi("list orphaned Cloud Run job task containers",
                    () -> lifecycleManager.getDockerClient().listContainersCmd()
                            .withShowAll(true)
                            .withLabelFilter(labels)
                            .exec());
        } catch (RuntimeException e) {
            LOG.warnf("Could not list orphaned Cloud Run job task containers: %s", message(e));
            return;
        }
        for (com.github.dockerjava.api.model.Container container : containers) {
            String resource = container.getLabels() == null ? null : container.getLabels().get("floci_resource");
            if (resource != null && TASK_RESOURCE.matcher(resource).matches()) {
                LOG.infof("Removing orphaned Cloud Run job task container=%s task=%s", container.getId(), resource);
                lifecycleManager.forceRemove(container.getId(), null);
            }
        }
    }

    static Map<String, String> taskEnvironment(Execution execution, Task task, int attempt) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("CLOUD_RUN_JOB", execution.getJob());
        env.put("CLOUD_RUN_EXECUTION", CloudRunRuntimeService.lastSegment(execution.getName()));
        env.put("CLOUD_RUN_TASK_INDEX", Integer.toString(task.getIndex()));
        env.put("CLOUD_RUN_TASK_ATTEMPT", Integer.toString(attempt));
        env.put("CLOUD_RUN_TASK_COUNT", Integer.toString(execution.getTaskCount()));
        return env;
    }

    private void run(String key, Attempt handle, Execution execution, Task task, int attempt, Events events) {
        String taskId = CloudRunRuntimeService.lastSegment(task.getName());
        CloudRunRuntimeService.GcsVolumeMounts volumes = CloudRunRuntimeService.GcsVolumeMounts.EMPTY;
        String containerId = null;
        ResultCallback.Adapter<Frame> logs = null;
        TaskOutcome outcome;
        try {
            if (handle.stopRequested || shuttingDown) {
                outcome = new TaskOutcome.Stopped();
            } else {
                String[] parts = execution.getName().split("/");
                String project = parts[1];
                String location = parts[3];
                Container container = task.getContainers(0);
                volumes = runtimeService.prepareMergingGcsVolumeMounts(task.getName(), task.getVolumesList(),
                        container);
                String containerName = runtimeService.workloadContainerName(taskId, "attempt" + attempt,
                        UUID.randomUUID().toString().substring(0, 8));
                ContainerSpec spec = runtimeService.buildWorkloadSpec(project, location, task.getName(),
                        containerName, container, taskEnvironment(execution, task, attempt), null,
                        volumes.mounts());
                ContainerLifecycleManager.ContainerInfo info = lifecycleManager.createAndStart(spec);
                containerId = info.containerId();
                handle.containerId = containerId;
                if (shuttingDown) {
                    LOG.infof("Removing Cloud Run job task container created during shutdown task=%s container=%s",
                            task.getName(), containerId);
                    outcome = new TaskOutcome.Stopped();
                } else {
                    LOG.infof("Cloud Run job task started task=%s attempt=%d container=%s",
                            task.getName(), attempt, containerId);
                    events.taskStarted(task.getIndex(), attempt);
                    logs = attachLogs(containerId, taskId, attempt);
                    outcome = awaitExit(containerId, CloudRunJobTemplates.duration(task.getTimeout()), handle);
                }
            }
        } catch (RuntimeException e) {
            LOG.warnf(e, "Cloud Run job task attempt failed to run task=%s attempt=%d", task.getName(), attempt);
            outcome = new TaskOutcome.StartFailed(message(e));
        } finally {
            if (logs != null) {
                drainLogs(logs);
            }
            if (containerId != null) {
                lifecycleManager.forceRemove(containerId, logs);
            }
            runtimeService.releaseMergingGcsVolumeMounts(volumes);
            active.remove(key);
        }
        LOG.infof("Cloud Run job task finished task=%s attempt=%d outcome=%s", task.getName(), attempt, outcome);
        events.taskFinished(task.getIndex(), attempt, outcome);
    }

    private TaskOutcome awaitExit(String containerId, Duration timeout, Attempt handle) {
        Instant deadline = timeout.isZero() ? null : Instant.now().plus(timeout);
        while (true) {
            InspectContainerResponse.ContainerState state = inspectState(containerId);
            if (state == null) {
                return new TaskOutcome.Stopped();
            }
            if (!Boolean.TRUE.equals(state.getRunning())) {
                Long exitCode = state.getExitCodeLong();
                return new TaskOutcome.Exited(exitCode == null ? -1 : exitCode.intValue());
            }
            if (handle.stopRequested || shuttingDown) {
                terminate(containerId);
                return new TaskOutcome.Stopped();
            }
            if (deadline != null && !Instant.now().isBefore(deadline)) {
                terminate(containerId);
                return new TaskOutcome.TimedOut();
            }
            if (!sleep(POLL_INTERVAL_MILLIS)) {
                terminate(containerId);
                return new TaskOutcome.Stopped();
            }
        }
    }

    /** SIGTERM, then SIGKILL once {@code execution.cleanup-timeout} has elapsed. */
    private void terminate(String containerId) {
        signal(containerId, "SIGTERM");
        Instant limit = Instant.now().plus(config.services().cloudrun().execution().cleanupTimeout());
        while (Instant.now().isBefore(limit)) {
            InspectContainerResponse.ContainerState state = inspectState(containerId);
            if (state == null || !Boolean.TRUE.equals(state.getRunning())) {
                return;
            }
            if (!sleep(POLL_INTERVAL_MILLIS)) {
                break;
            }
        }
        signal(containerId, "SIGKILL");
    }

    private void signal(String containerId, String signal) {
        try {
            lifecycleManager.runDockerApi("send " + signal + " to container " + containerId, () -> {
                lifecycleManager.getDockerClient().killContainerCmd(containerId).withSignal(signal).exec();
                return null;
            });
        } catch (NotFoundException | ConflictException e) {
            LOG.debugf("Container already stopped container=%s signal=%s: %s", containerId, signal, e.getMessage());
        }
    }

    private InspectContainerResponse.ContainerState inspectState(String containerId) {
        try {
            return lifecycleManager.runDockerApi("inspect container " + containerId,
                    () -> lifecycleManager.getDockerClient().inspectContainerCmd(containerId).exec().getState());
        } catch (NotFoundException e) {
            return null;
        }
    }

    private ResultCallback.Adapter<Frame> attachLogs(String containerId, String taskId, int attempt) {
        try {
            return lifecycleManager.getDockerClient().logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(true)
                    .withTimestamps(false)
                    .exec(new ResultCallback.Adapter<>() {
                        @Override
                        public void onNext(Frame frame) {
                            String payload = new String(frame.getPayload(), StandardCharsets.UTF_8);
                            for (String line : payload.split("\n")) {
                                if (!line.isBlank()) {
                                    LOG.debugf("[%s attempt %d %s] %s", taskId, attempt, frame.getStreamType(),
                                            line.stripTrailing());
                                }
                            }
                        }
                    });
        } catch (RuntimeException e) {
            LOG.warnf("Could not attach log stream for Cloud Run task container=%s: %s", containerId, e.getMessage());
            return null;
        }
    }

    private static void drainLogs(ResultCallback.Adapter<Frame> logs) {
        try {
            logs.awaitCompletion(LOG_DRAIN_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private static final class Attempt implements TaskHandle {
        private volatile boolean stopRequested;
        private volatile String containerId;

        @Override
        public void stop() {
            stopRequested = true;
        }
    }
}
