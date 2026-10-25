package io.floci.gcp.services.compute;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.Ports;
import com.google.common.util.concurrent.MoreExecutors;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.docker.ContainerBuilder;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerSpec;
import io.floci.gcp.core.common.docker.DockerHostResolver;
import io.floci.gcp.core.common.docker.ImageCacheService;
import io.floci.gcp.services.compute.ComputeInstanceRuntime.Intent;
import io.floci.gcp.services.compute.ComputeInstanceRuntime.Kind;
import io.floci.gcp.services.compute.ComputeInstanceRuntime.Phase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComputeInstanceRuntimeTest {

    private static final String CONTAINER = "floci-gcp-compute-p-us-central1-a-vm";
    private static final Map<String, String> METADATA = Map.of("startup-script", "echo hi", "shutdown-script", "echo bye");
    private static final String WORKLOAD = CONTAINER + "_klt";
    private static final String DECLARATION = """
            spec:
              containers:
              - name: web
                image: nginx:1.27
                command: [/bin/sh]
                args: [-c, 'echo hi']
                env:
                - name: MODE
                  value: test
                tty: true
                stdin: true
                securityContext:
                  privileged: true
                volumeMounts:
                - name: scratch
                  mountPath: /scratch
              volumes:
              - name: scratch
                emptyDir:
                  medium: Memory
              restartPolicy: OnFailure
            """;

    private EmulatorConfig config;
    private ContainerBuilder containerBuilder;
    private ContainerBuilder.Builder builder;
    private ContainerLifecycleManager lifecycleManager;
    private ImageCacheService imageCache;
    private DockerClient docker;
    private ComputeGuestScripts scripts;
    private DockerHostResolver dockerHost;
    private ComputeInstanceRuntime runtime;

    @BeforeEach
    void setUp() throws Exception {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().compute().mock()).thenReturn(false);
        when(config.services().compute().launchTimeout()).thenReturn(Duration.ofMinutes(5));
        when(config.services().compute().instanceResourceLimits()).thenReturn(true);
        when(config.services().compute().reconcileContainersOnStartup()).thenReturn(true);
        when(config.services().compute().dockerNetwork()).thenReturn(Optional.empty());
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        when(config.port()).thenReturn(4588);
        when(config.docker().apiTimeout()).thenReturn(Duration.ofSeconds(30));
        containerBuilder = mock(ContainerBuilder.class);
        builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(mock(ContainerSpec.class));
        lifecycleManager = mock(ContainerLifecycleManager.class);
        docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(docker);
        doAnswer(invocation -> invocation.<Callable<?>>getArgument(1).call())
                .when(lifecycleManager).runDockerApi(anyString(), any());
        imageCache = mock(ImageCacheService.class);
        scripts = mock(ComputeGuestScripts.class);
        when(config.services().compute().startupScriptTimeout()).thenReturn(Duration.ofMinutes(30));
        when(config.services().compute().shutdownScriptTimeout()).thenReturn(Duration.ofSeconds(90));
        ContainerLifecycleManager.ContainerInfo started = mock(ContainerLifecycleManager.ContainerInfo.class);
        when(started.containerId()).thenReturn("new-id");
        when(lifecycleManager.createAndStart(any())).thenReturn(started);
        dockerHost = mock(DockerHostResolver.class);
        when(dockerHost.resolve()).thenReturn("host.docker.internal");
        runtime = new ComputeInstanceRuntime(config, containerBuilder, lifecycleManager, imageCache, scripts, dockerHost,
                MoreExecutors.newDirectExecutorService(), MoreExecutors.newDirectExecutorService());
    }

    @Test
    void launchPullsTheImageAndStartsAnIdleLabelledContainerBoundByTheMachineType() {
        runtime.submit("op", List.of(intent(Kind.LAUNCH)));

        verify(imageCache).ensureImageExists("debian:12");
        verify(lifecycleManager, never()).forceRemove(any(), any());
        verify(lifecycleManager).createAndStart(any());
        verify(builder).withName(CONTAINER);
        verify(builder).withEntrypoint(ComputeInstanceRuntime.IDLE_ENTRYPOINT);
        verify(builder).withNanoCpus(2_000_000_000L);
        verify(builder).withMemoryMb(8192);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> labels = ArgumentCaptor.forClass(Map.class);
        verify(builder).withLabels(labels.capture());
        assertEquals(Map.of("io.floci", "gcp", "io.floci.service", "compute", "io.floci.resource-id", "vm",
                "io.floci.project", "p", "io.floci.location", "us-central1-a", "io.floci.owner", "4588"),
                labels.getValue());
        assertEquals(Phase.DONE, runtime.phase("op"));
    }

    @Test
    void resourceLimitsCanBeTurnedOff() {
        when(config.services().compute().instanceResourceLimits()).thenReturn(false);

        runtime.submit("op", List.of(intent(Kind.LAUNCH)));

        verify(builder, never()).withNanoCpus(any(Long.class));
        verify(builder, never()).withMemoryMb(any(Integer.class));
    }

    @Test
    void aFailedLaunchStillFinishesTheOperationWithoutAContainer() {
        doThrow(new RuntimeException("pull denied")).when(imageCache).ensureImageExists("debian:12");

        runtime.submit("op", List.of(intent(Kind.LAUNCH)));

        verify(lifecycleManager, never()).createAndStart(any());
        assertTrue(runtime.finished("op"));
    }

    @Test
    void phaseIsStagingOnceTheImageIsPresentAndTheContainerIsBeingCreated() {
        Phase[] seen = new Phase[1];
        when(lifecycleManager.createAndStart(any())).thenAnswer(invocation -> {
            seen[0] = runtime.phase("op");
            return null;
        });

        runtime.submit("op", List.of(intent(Kind.LAUNCH)));

        assertEquals(Phase.STAGING, seen[0]);
    }

    @Test
    void startRunsTheExistingContainerOrLaunchesAMissingOne() {
        Container existing = container("c-1", "exited", Map.of());
        when(lifecycleManager.findByName(CONTAINER)).thenReturn(Optional.of(existing));
        runtime.submit("op-1", List.of(intent(Kind.START)));
        verify(docker.startContainerCmd("c-1")).exec();
        verify(lifecycleManager, never()).createAndStart(any());

        when(lifecycleManager.findByName(CONTAINER)).thenReturn(Optional.empty());
        runtime.submit("op-2", List.of(intent(Kind.START)));
        verify(lifecycleManager).createAndStart(any());
    }

    @Test
    void stopRestartAndRemoveDriveTheNamedContainer() {
        Container running = container("c-1", "running", Map.of());
        when(lifecycleManager.findByName(CONTAINER)).thenReturn(Optional.of(running));

        runtime.submit("op", List.of(intent(Kind.STOP), intent(Kind.RESTART), intent(Kind.REMOVE)));

        verify(docker.stopContainerCmd("c-1").withTimeout(10)).exec();
        verify(docker.restartContainerCmd("c-1").withTimeout(0)).exec();
        verify(lifecycleManager).forceRemove(eq("c-1"), isNull());
    }

    @Test
    void launchReplacesALeftoverContainerThisDeploymentOwns() {
        Container old = container("old", "exited", identity("vm", "4588"));
        when(lifecycleManager.findByName(CONTAINER)).thenReturn(Optional.of(old));

        runtime.submit("op", List.of(intent(Kind.LAUNCH)));

        verify(lifecycleManager).forceRemove(eq("old"), isNull());
        verify(lifecycleManager).createAndStart(any());
    }

    @Test
    void aContainerAnotherDeploymentOwnsUnderTheSameNameIsNeverTouched() {
        Container theirs = container("theirs", "running", identity("vm", "4599"));
        when(lifecycleManager.findByName(CONTAINER)).thenReturn(Optional.of(theirs));

        runtime.submit("op", List.of(intent(Kind.LAUNCH), intent(Kind.START), intent(Kind.STOP),
                intent(Kind.RESTART), intent(Kind.REMOVE)));

        verify(lifecycleManager, never()).forceRemove(any(), any());
        verify(lifecycleManager, never()).createAndStart(any());
        verify(docker, never()).startContainerCmd("theirs");
        verify(docker, never()).stopContainerCmd("theirs");
        verify(docker, never()).restartContainerCmd("theirs");
        assertTrue(runtime.finished("op"));
    }

    @Test
    void aLaunchWhoseOperationTimedOutDuringThePullCreatesNoContainer() {
        when(config.services().compute().launchTimeout()).thenReturn(Duration.ofMillis(-1));

        runtime.submit("op", List.of(intent(Kind.LAUNCH)));

        verify(imageCache).ensureImageExists("debian:12");
        verify(lifecycleManager, never()).createAndStart(any());
    }

    @Test
    void stepsSubmittedAfterTeardownAreSkippedAndTheirOperationFinishes() {
        when(lifecycleManager.listContainersByLabels(anyString(), any())).thenReturn(List.of());
        runtime.stopManagedContainers();

        runtime.submit("late", List.of(intent(Kind.LAUNCH)));

        verify(imageCache, never()).ensureImageExists(anyString());
        assertTrue(runtime.finished("late"));
    }

    @Test
    void untrackedOperationsAndOperationsPastTheLaunchTimeoutCountAsFinished() {
        assertTrue(runtime.finished("never-submitted"));

        when(config.services().compute().launchTimeout()).thenReturn(Duration.ofMillis(-1));
        ComputeInstanceRuntime slow = new ComputeInstanceRuntime(config, mock(ContainerBuilder.class), lifecycleManager,
                imageCache, scripts, dockerHost, mock(ExecutorService.class), mock(ExecutorService.class));
        slow.submit("stuck", List.of(intent(Kind.LAUNCH)));
        assertTrue(slow.finished("stuck"));
    }

    @Test
    void forgetDropsTheTrackedOperation() {
        ComputeInstanceRuntime pending = new ComputeInstanceRuntime(config, mock(ContainerBuilder.class),
                lifecycleManager, imageCache, scripts, dockerHost, mock(ExecutorService.class),
                mock(ExecutorService.class));
        pending.submit("op", List.of(intent(Kind.LAUNCH)));
        assertEquals(Phase.PROVISIONING, pending.phase("op"));

        pending.forget("op");

        assertEquals(Phase.DONE, pending.phase("op"));
    }

    @Test
    void reconcileRestartsRunningInstancesStopsTerminatedOnesRemovesDeletedOnesAndLeavesOtherOwners() {
        List<Container> containers = List.of(
                container("running-stopped", "exited", identity("vm-running", "4588")),
                container("terminated", "exited", identity("vm-stopped", "4588")),
                container("terminated-running", "running", identity("vm-stopped", "4588")),
                container("deleted", "running", identity("vm-gone", "4588")),
                container("foreign", "running", identity("vm-gone", "4599")));
        when(lifecycleManager.listContainersByLabels(anyString(), any())).thenReturn(containers);
        Map<String, String> statuses = Map.of("vm-running", "RUNNING", "vm-stopped", "TERMINATED");

        runtime.reconcile((project, zone, name) -> statuses.get(name));

        verify(docker.startContainerCmd("running-stopped")).exec();
        verify(docker, never()).startContainerCmd("terminated");
        verify(docker, never()).stopContainerCmd("terminated");
        verify(docker.stopContainerCmd("terminated-running").withTimeout(10)).exec();
        verify(lifecycleManager).forceRemove(eq("deleted"), isNull());
        verify(lifecycleManager, never()).forceRemove(eq("foreign"), any());
        verify(lifecycleManager, never()).forceRemove(eq("terminated"), any());
    }

    @Test
    void reconcileWaitsForAStepOnTheSameInstanceAndReadsTheStatusAfterIt() throws Exception {
        CountDownLatch pulling = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            pulling.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(imageCache).ensureImageExists("debian:12");
        Container stale = container("vm-container", "running", identity("vm", "4588"));
        when(lifecycleManager.listContainersByLabels(anyString(), any())).thenReturn(List.of(stale));
        AtomicBoolean readWhileStepRan = new AtomicBoolean();
        CountDownLatch reconciled = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            ComputeInstanceRuntime concurrent = new ComputeInstanceRuntime(config, mock(ContainerBuilder.class),
                    lifecycleManager, imageCache, scripts, dockerHost, pool, MoreExecutors.newDirectExecutorService());
            concurrent.submit("start", List.of(intent(Kind.LAUNCH)));
            assertTrue(pulling.await(5, TimeUnit.SECONDS));

            concurrent.reconcile((project, zone, name) -> {
                readWhileStepRan.set(release.getCount() > 0);
                reconciled.countDown();
                return "RUNNING";
            });
            Thread.sleep(200);
            release.countDown();

            assertTrue(reconciled.await(5, TimeUnit.SECONDS));
            assertFalse(readWhileStepRan.get(), "reconcile must not read the status while a step on the instance runs");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aContainerStartedWhileTeardownRunsIsStoppedByItsOwnStep() throws Exception {
        CountDownLatch creating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // Like ContainerLifecycleManager.dockerApi: an interrupted call fails and leaves the interrupt flag set, so
        // every later Docker call on that thread fails too, although the daemon may have created the container.
        when(lifecycleManager.createAndStart(any())).thenAnswer(invocation -> {
            creating.countDown();
            try {
                release.await();
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Docker API interrupted", e);
            }
        });
        doAnswer(invocation -> {
            if (Thread.currentThread().isInterrupted()) { throw new IllegalStateException("Docker API interrupted"); }
            return invocation.<Callable<?>>getArgument(1).call();
        }).when(lifecycleManager).runDockerApi(anyString(), any());
        Container created = container("new", "running", identity("vm", "4588"));
        when(lifecycleManager.findByName(CONTAINER)).thenAnswer(invocation -> {
            if (Thread.currentThread().isInterrupted()) { throw new IllegalStateException("Docker API interrupted"); }
            return creating.getCount() == 0 ? Optional.of(created) : Optional.empty();
        });
        when(lifecycleManager.listContainersByLabels(anyString(), any())).thenAnswer(invocation ->
                creating.getCount() == 0 ? List.of(created) : List.of());
        ExecutorService pool = Executors.newFixedThreadPool(1);
        ComputeInstanceRuntime concurrent = new ComputeInstanceRuntime(config, containerBuilder,
                lifecycleManager, imageCache, scripts, dockerHost, pool, MoreExecutors.newDirectExecutorService());
        concurrent.submit("op", List.of(intent(Kind.LAUNCH)));
        assertTrue(creating.await(5, TimeUnit.SECONDS));

        Thread teardown = new Thread(concurrent::stopManagedContainers);
        teardown.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!concurrent.closed() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(concurrent.closed());
        release.countDown();
        teardown.join(15_000);

        verify(docker.stopContainerCmd("new").withTimeout(10), timeout(5_000).atLeastOnce()).exec();
    }

    @Test
    void teardownWaitsForAContainerStillStartingBeforeListingContainers() throws Exception {
        CountDownLatch creating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> events = new CopyOnWriteArrayList<>();
        when(lifecycleManager.createAndStart(any())).thenAnswer(invocation -> {
            creating.countDown();
            release.await(10, TimeUnit.SECONDS);
            events.add("created");
            return null;
        });
        when(lifecycleManager.listContainersByLabels(anyString(), any())).thenAnswer(invocation -> {
            events.add("listed");
            return List.of();
        });
        ExecutorService pool = Executors.newFixedThreadPool(1);
        // Reports a step outliving any fixed wait, as a create slower than the old 10 second wait did.
        ExecutorService impatient = new AbstractExecutorService() {
            @Override public void execute(Runnable command) { pool.execute(command); }
            @Override public void shutdown() { pool.shutdown(); }
            @Override public List<Runnable> shutdownNow() { return pool.shutdownNow(); }
            @Override public boolean isShutdown() { return pool.isShutdown(); }
            @Override public boolean isTerminated() { return pool.isTerminated(); }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return false; }
        };
        ComputeInstanceRuntime concurrent = new ComputeInstanceRuntime(config, containerBuilder,
                lifecycleManager, imageCache, scripts, dockerHost, impatient, MoreExecutors.newDirectExecutorService());
        concurrent.submit("op", List.of(intent(Kind.LAUNCH)));
        assertTrue(creating.await(5, TimeUnit.SECONDS));

        Thread teardown = new Thread(concurrent::stopManagedContainers);
        teardown.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!events.contains("listed") && teardown.getState() != Thread.State.TIMED_WAITING
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        release.countDown();
        teardown.join(15_000);

        assertEquals(List.of("created", "listed"), events);
    }

    @Test
    void reconcileAndTeardownDoNothingInMockMode() {
        when(config.services().compute().mock()).thenReturn(true);

        runtime.reconcile((project, zone, name) -> null);
        runtime.stopManagedContainers();

        verify(lifecycleManager, never()).listContainersByLabels(anyString(), any());
    }

    @Test
    void teardownStopsOnlyRunningContainersThisDeploymentOwns() {
        List<Container> containers = List.of(
                container("mine", "running", identity("a", "4588")),
                container("mine-stopped", "exited", identity("b", "4588")),
                container("foreign", "running", identity("c", "4599")));
        when(lifecycleManager.listContainersByLabels(anyString(), any())).thenReturn(containers);

        runtime.stopManagedContainers();

        verify(docker.stopContainerCmd("mine").withTimeout(10)).exec();
        verify(docker, never()).stopContainerCmd("mine-stopped");
        verify(docker, never()).stopContainerCmd("foreign");
    }

    @Test
    void containersAreToldWhereTheMetadataServerIs() {
        when(config.services().compute().metadataServer().enabled()).thenReturn(true);

        runtime.submit("op", List.of(intent(Kind.LAUNCH)));

        verify(builder).withEnv("GCE_METADATA_HOST", "host.docker.internal:4588");
        verify(builder).withEnv("GCE_METADATA_IP", "host.docker.internal:4588");
    }

    @Test
    void theMetadataServerIsNotAdvertisedWhenItIsOff() {
        when(config.services().compute().metadataServer().enabled()).thenReturn(false);

        runtime.submit("op", List.of(intent(Kind.LAUNCH)));

        verify(builder, never()).withEnv(eq("GCE_METADATA_HOST"), anyString());
    }

    @Test
    void runningContainerAddressesIdentifyTheirInstanceUntilItStops() {
        ContainerNetwork network = mock(ContainerNetwork.class);
        when(network.getIpAddress()).thenReturn("172.18.0.5");
        InspectContainerResponse inspect = docker.inspectContainerCmd(CONTAINER).exec();
        when(inspect.getState().getRunning()).thenReturn(true);
        when(inspect.getNetworkSettings().getNetworks()).thenReturn(Map.of("bridge", network));

        runtime.submit("launch", List.of(intent(Kind.LAUNCH)));
        assertEquals(Optional.of(new ComputeInstanceRuntime.InstanceRef("p", "us-central1-a", "vm")),
                runtime.instanceAt("172.18.0.5"));

        runtime.submit("stop", List.of(intent(Kind.STOP)));
        assertEquals(Optional.empty(), runtime.instanceAt("172.18.0.5"));
    }

    @Test
    void guestScriptsCanReadTheirMetadataFromBootUntilTheirShutdownScriptsFinish() {
        ContainerNetwork network = mock(ContainerNetwork.class);
        when(network.getIpAddress()).thenReturn("172.18.0.5");
        for (String container : List.of(CONTAINER, "new-id")) {
            InspectContainerResponse inspect = docker.inspectContainerCmd(container).exec();
            when(inspect.getState().getRunning()).thenReturn(true);
            when(inspect.getNetworkSettings().getNetworks()).thenReturn(Map.of("bridge", network));
        }
        List<Optional<ComputeInstanceRuntime.InstanceRef>> seen = new ArrayList<>();
        doAnswer(invocation -> seen.add(runtime.instanceAt("172.18.0.5")))
                .when(scripts).run(any(), any(), any(), any(), any(), any(), any(), any());

        runtime.submit("launch", List.of(intent(Kind.LAUNCH)));
        Container running = container("new-id", "running", Map.of());
        when(lifecycleManager.findByName(CONTAINER)).thenReturn(Optional.of(running));
        runtime.submit("stop", List.of(intent(Kind.STOP)));

        Optional<ComputeInstanceRuntime.InstanceRef> vm =
                Optional.of(new ComputeInstanceRuntime.InstanceRef("p", "us-central1-a", "vm"));
        assertEquals(List.of(vm, vm), seen, "startup and shutdown scripts both found their instance");
        assertEquals(Optional.empty(), runtime.instanceAt("172.18.0.5"));
    }

    @Test
    void reregisteringARunningInstanceNeverHidesItsAddress() {
        ContainerNetwork network = mock(ContainerNetwork.class);
        when(network.getIpAddress()).thenReturn("172.18.0.5");
        List<Optional<ComputeInstanceRuntime.InstanceRef>> seen = new ArrayList<>();
        for (String container : List.of(CONTAINER, "new-id")) {
            InspectContainerResponse inspect = docker.inspectContainerCmd(container).exec();
            when(inspect.getState().getRunning()).thenReturn(true);
            when(inspect.getNetworkSettings().getNetworks()).thenAnswer(invocation -> {
                seen.add(runtime.instanceAt("172.18.0.5"));
                return Map.of("bridge", network);
            });
        }

        runtime.submit("launch", List.of(intent(Kind.LAUNCH)));

        int first = seen.indexOf(Optional.of(new ComputeInstanceRuntime.InstanceRef("p", "us-central1-a", "vm")));
        assertTrue(first >= 0, "the boot registered the instance: " + seen);
        assertFalse(seen.subList(first, seen.size()).contains(Optional.empty()),
                "a later registration hid the address: " + seen);
    }

    private static Intent intent(Kind kind) {
        return new Intent(kind, "p", "us-central1-a", "vm", "debian:12", 2, 8192, METADATA);
    }

    @Test
    void everyBootRunsTheStartupScriptsInTheNewContainer() {
        runtime.submit("launch", List.of(intent(Kind.LAUNCH)));
        verify(scripts).run(eq(ComputeGuestScripts.Phase.STARTUP), eq("vm"), eq("new-id"), eq(METADATA),
                eq(Duration.ofMinutes(30)), any(), any(), any());

        Container running = container("c-1", "running", Map.of());
        when(lifecycleManager.findByName(CONTAINER)).thenReturn(Optional.of(running));
        runtime.submit("reset", List.of(intent(Kind.RESTART)));
        runtime.submit("start", List.of(intent(Kind.START)));
        verify(scripts, times(2)).run(eq(ComputeGuestScripts.Phase.STARTUP), eq("vm"), eq("c-1"),
                eq(METADATA), any(), any(), any(), any());
    }

    @Test
    void aBootWithoutAContainerRunsNoScripts() {
        doThrow(new RuntimeException("pull denied")).when(imageCache).ensureImageExists("debian:12");

        runtime.submit("op", List.of(intent(Kind.LAUNCH)));

        verify(scripts, never()).run(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aStartupTaskQueuedForAnEarlierBootSeesItsBootEnded() {
        List<Runnable> queued = new ArrayList<>();
        ExecutorService deferred = mock(ExecutorService.class);
        doAnswer(invocation -> queued.add(invocation.getArgument(0))).when(deferred).execute(any());
        ComputeInstanceRuntime runtime = new ComputeInstanceRuntime(config, containerBuilder, lifecycleManager,
                imageCache, scripts, dockerHost, MoreExecutors.newDirectExecutorService(), deferred);
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));
        runtime.submit("insert", List.of(intent(Kind.LAUNCH)));
        Container running = container("c-1", "running", Map.of());
        when(lifecycleManager.findByName(CONTAINER)).thenReturn(Optional.of(running));
        runtime.submit("stop", List.of(intent(Kind.STOP)));

        queued.forEach(Runnable::run);

        ArgumentCaptor<BooleanSupplier> current = ArgumentCaptor.forClass(BooleanSupplier.class);
        verify(scripts).run(eq(ComputeGuestScripts.Phase.STARTUP), any(), any(), any(), any(), any(), current.capture(), any());
        assertFalse(current.getValue().getAsBoolean(), "the stop ended the boot those scripts belong to");
    }

    @Test
    void reconcileRunsTheStartupScriptsOfAContainerItStartsAgain() {
        Container stopped = container("c-1", "exited", identity("vm", "4588"));
        when(lifecycleManager.listContainersByLabels(anyString(), any())).thenReturn(List.of(stopped));

        runtime.reconcile((project, zone, name) -> "RUNNING",
                (project, zone, name) -> Map.of("startup-script", "echo again"));

        verify(docker.startContainerCmd("c-1")).exec();
        verify(scripts).run(eq(ComputeGuestScripts.Phase.STARTUP), eq("vm"), eq("c-1"),
                eq(Map.of("startup-script", "echo again")), any(), any(), any(), any());
    }

    @Test
    void aStopWaitsForShutdownScriptsAtMostTheShutdownTimeout() throws Exception {
        when(config.services().compute().shutdownScriptTimeout()).thenReturn(Duration.ofMillis(200));
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(scripts).run(eq(ComputeGuestScripts.Phase.SHUTDOWN), any(), any(), any(), any(), any(), any(), any());
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            ComputeInstanceRuntime stopping = new ComputeInstanceRuntime(config, containerBuilder, lifecycleManager,
                    imageCache, scripts, dockerHost, MoreExecutors.newDirectExecutorService(), pool);
            Container running = container("c-1", "running", Map.of());
            when(lifecycleManager.findByName(CONTAINER)).thenReturn(Optional.of(running));

            long started = System.nanoTime();
            stopping.submit("stop", List.of(intent(Kind.STOP)));

            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5), "the stop did not wait for the script");
            verify(lifecycleManager).runDockerApi(eq("stop Compute instance " + CONTAINER), any());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void stopAndDeleteRunTheShutdownScriptsOfARunningContainerFirst() {
        Container running = container("c-1", "running", Map.of());
        when(lifecycleManager.findByName(CONTAINER)).thenReturn(Optional.of(running));
        InOrder order = inOrder(scripts, lifecycleManager);

        runtime.submit("stop", List.of(intent(Kind.STOP)));
        order.verify(scripts).run(eq(ComputeGuestScripts.Phase.SHUTDOWN), eq("vm"), eq("c-1"), eq(METADATA),
                eq(Duration.ofSeconds(90)), any(), any(), any());
        order.verify(lifecycleManager).runDockerApi(eq("stop Compute instance " + CONTAINER), any());

        runtime.submit("delete", List.of(intent(Kind.REMOVE)));
        order.verify(scripts).run(eq(ComputeGuestScripts.Phase.SHUTDOWN), eq("vm"), eq("c-1"), any(), any(), any(), any(), any());
        order.verify(lifecycleManager).forceRemove(eq("c-1"), isNull());
    }

    @Test
    void aStoppedContainerRunsNoShutdownScripts() {
        Container exited = container("c-1", "exited", Map.of());
        when(lifecycleManager.findByName(CONTAINER)).thenReturn(Optional.of(exited));

        runtime.submit("delete", List.of(intent(Kind.REMOVE)));

        verify(scripts, never()).run(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void serialOutputComesFromPortOneAndDeleteDropsIt() {
        doAnswer(invocation -> {
            invocation.<ComputeSerialPort>getArgument(5).write("hello\n".getBytes());
            return null;
        }).when(scripts).run(any(), any(), any(), any(), any(), any(), any(), any());

        runtime.submit("launch", List.of(intent(Kind.LAUNCH)));

        assertEquals("hello\n", runtime.serialOutput("p", "us-central1-a", "vm", 1, 0).contents());
        assertEquals("", runtime.serialOutput("p", "us-central1-a", "vm", 2, 0).contents());
        runtime.submit("delete", List.of(intent(Kind.REMOVE)));
        assertEquals("", runtime.serialOutput("p", "us-central1-a", "vm", 1, 0).contents());
    }

    @Test
    void aContainerDeclarationRunsItsContainerInTheInstanceNetworkAfterTheStartupScripts() {
        when(config.services().compute().metadataServer().enabled()).thenReturn(true);
        InOrder order = inOrder(scripts, lifecycleManager);

        runtime.submit("launch", List.of(declared(Kind.LAUNCH)));

        order.verify(lifecycleManager).createAndStart(any());
        order.verify(scripts).run(eq(ComputeGuestScripts.Phase.STARTUP), any(), any(), any(), any(), any(), any(), any());
        order.verify(lifecycleManager).createAndStart(any());
        verify(containerBuilder).newContainer("nginx:1.27");
        verify(builder).withName(WORKLOAD);
        verify(builder).withNetworkMode("container:new-id");
        verify(builder).withEntrypoint(List.of("/bin/sh"));
        verify(builder).withCmd(List.of("-c", "echo hi"));
        verify(builder).withEnv(List.of("MODE=test"));
        verify(builder).withRestartPolicy("on-failure");
        verify(builder).withTty(true);
        verify(builder).withStdinOpen(true);
        verify(builder).withPrivileged(false);
        verify(builder).withMount(argThat(mount -> mount.getType() == MountType.TMPFS
                && "/scratch".equals(mount.getTarget()) && !Boolean.TRUE.equals(mount.getReadOnly())));
        verify(builder, times(2)).withNanoCpus(2_000_000_000L);
        verify(builder).withMemoryBytes(8192L * 1024 * 1024);
        verify(builder, times(2)).withEnv("GCE_METADATA_HOST", "host.docker.internal:4588");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> labels = ArgumentCaptor.forClass(Map.class);
        verify(builder, times(2)).withLabels(labels.capture());
        assertEquals("container", labels.getAllValues().get(1).get("io.floci.compute.role"));
        assertEquals("web", labels.getAllValues().get(1).get("io.floci.compute.container-name"));
        assertEquals("vm", labels.getAllValues().get(1).get("io.floci.resource-id"));
        String serial = runtime.serialOutput("p", "us-central1-a", "vm", 1, 0).contents();
        assertTrue(serial.contains(" vm konlet-startup[1]: Configured container 'web' will be started with name '"
                + WORKLOAD + "'."), serial);
        assertTrue(serial.contains("konlet-startup[1]: Started a container with ID: new-id"), serial);
        assertTrue(serial.contains("konlet-startup[1]: Not running container 'web' privileged: it would be privileged"
                + " on the Docker host."), serial);
    }

    @Test
    void aPrivilegedDeclarationIsHonouredOnlyWhenTheHostAllowsIt() {
        when(config.services().compute().allowPrivilegedContainers()).thenReturn(true);

        runtime.submit("launch", List.of(declared(Kind.LAUNCH)));

        verify(builder).withPrivileged(true);
        assertFalse(runtime.serialOutput("p", "us-central1-a", "vm", 1, 0).contents().contains("Not running container"));
    }

    @Test
    void aWorkloadStartedAfterAnEmulatorRestartKeepsTheMachineLimits() {
        Container stopped = container("vm-c", "exited", identity("vm", "4588"));
        when(lifecycleManager.listContainersByLabels(anyString(), any())).thenReturn(List.of(stopped));
        when(docker.inspectContainerCmd("vm-c").exec().getHostConfig().getNanoCPUs()).thenReturn(2_000_000_000L);
        when(docker.inspectContainerCmd("vm-c").exec().getHostConfig().getMemory()).thenReturn(8192L * 1024 * 1024);

        runtime.reconcile((project, zone, name) -> "RUNNING", (project, zone, name) -> declared(Kind.START).metadata());

        verify(builder).withNetworkMode("container:vm-c");
        verify(builder).withNanoCpus(2_000_000_000L);
        verify(builder).withMemoryBytes(8192L * 1024 * 1024);
    }

    @Test
    void reconcileFollowsTheOutputOfADeclaredContainerThatSurvived() {
        Map<String, String> labels = new HashMap<>(identity("vm", "4588"));
        labels.put("io.floci.compute.role", "container");
        labels.put("io.floci.compute.container-name", "web");
        Container survivor = container("w-1", "running", labels);
        when(lifecycleManager.listContainersByLabels(anyString(), any())).thenReturn(List.of(survivor));

        runtime.reconcile((project, zone, name) -> "RUNNING");

        verify(docker.logContainerCmd("w-1").withStdOut(true).withStdErr(true).withFollowStream(true))
                .withSince(any(Integer.class));
        verify(lifecycleManager, never()).forceRemove(eq("w-1"), any());
    }

    @Test
    void anInstanceWithoutADeclarationRunsOnlyItsOwnContainer() {
        runtime.submit("launch", List.of(intent(Kind.LAUNCH)));

        verify(lifecycleManager, times(1)).createAndStart(any());
        verify(builder, never()).withNetworkMode(anyString());
    }

    @Test
    void aDeclarationKonletRefusesIsReportedOnTheSerialPortAndStartsNothing() {
        Map<String, String> metadata = new HashMap<>(METADATA);
        metadata.put("gce-container-declaration", "spec:\n  containers: []\n");

        runtime.submit("launch", List.of(new Intent(Kind.LAUNCH, "p", "us-central1-a", "vm", "debian:12", 2, 8192,
                metadata)));

        verify(lifecycleManager, times(1)).createAndStart(any());
        assertTrue(runtime.serialOutput("p", "us-central1-a", "vm", 1, 0).contents().contains(
                "konlet-startup[1]: Error: Failed to start container: Container declaration should include exactly 1"
                        + " container, 0 found"));
    }

    @Test
    void stopRemovesTheDeclaredContainerAfterTheShutdownScriptsAndBeforeTheInstanceStops() {
        Container vm = container("c-1", "running", Map.of());
        Container workload = container("w-1", "running", Map.of());
        when(lifecycleManager.findByName(CONTAINER)).thenReturn(Optional.of(vm));
        when(lifecycleManager.findByName(WORKLOAD)).thenReturn(Optional.of(workload));
        InOrder order = inOrder(scripts, lifecycleManager);

        runtime.submit("stop", List.of(declared(Kind.STOP)));

        order.verify(scripts).run(eq(ComputeGuestScripts.Phase.SHUTDOWN), any(), any(), any(), any(), any(), any(), any());
        order.verify(lifecycleManager).forceRemove(eq("w-1"), isNull());
        order.verify(lifecycleManager).runDockerApi(eq("stop Compute instance " + CONTAINER), any());
    }

    @Test
    void resetReplacesTheDeclaredContainerAndDeleteRemovesIt() {
        Container vm = container("c-1", "running", Map.of());
        Container workload = container("w-1", "running", Map.of());
        when(lifecycleManager.findByName(CONTAINER)).thenReturn(Optional.of(vm));
        when(lifecycleManager.findByName(WORKLOAD)).thenReturn(Optional.of(workload));
        InOrder order = inOrder(lifecycleManager);

        runtime.submit("reset", List.of(declared(Kind.RESTART)));

        order.verify(lifecycleManager).forceRemove(eq("w-1"), isNull());
        order.verify(lifecycleManager).runDockerApi(eq("restart Compute instance " + CONTAINER), any());
        order.verify(lifecycleManager).createAndStart(any());
        verify(builder).withNetworkMode("container:c-1");

        runtime.submit("delete", List.of(declared(Kind.REMOVE)));
        order.verify(lifecycleManager).forceRemove(eq("c-1"), isNull());
        order.verify(lifecycleManager).forceRemove(eq("w-1"), isNull());
    }

    @Test
    void aDeclaredContainerQueuedForAnEarlierBootIsNeverStarted() {
        List<Runnable> queued = new ArrayList<>();
        ExecutorService deferred = mock(ExecutorService.class);
        doAnswer(invocation -> queued.add(invocation.getArgument(0))).when(deferred).execute(any());
        ComputeInstanceRuntime runtime = new ComputeInstanceRuntime(config, containerBuilder, lifecycleManager,
                imageCache, scripts, dockerHost, MoreExecutors.newDirectExecutorService(), deferred);
        runtime.submit("insert", List.of(declared(Kind.LAUNCH)));
        Container vm = container("new-id", "running", Map.of());
        when(lifecycleManager.findByName(CONTAINER)).thenReturn(Optional.of(vm));
        runtime.submit("stop", List.of(declared(Kind.STOP)));

        queued.forEach(Runnable::run);

        verify(lifecycleManager, times(1)).createAndStart(any());
    }

    @Test
    void reconcileRemovesDeclaredContainersOfInstancesThatAreNotRunning() {
        Map<String, String> running = new HashMap<>(identity("vm-running", "4588"));
        running.put("io.floci.compute.role", "container");
        Map<String, String> stopped = new HashMap<>(identity("vm-stopped", "4588"));
        stopped.put("io.floci.compute.role", "container");
        Map<String, String> deleted = new HashMap<>(identity("vm-gone", "4588"));
        deleted.put("io.floci.compute.role", "container");
        List<Container> containers = List.of(container("w-running", "exited", running),
                container("w-stopped", "running", stopped), container("w-gone", "running", deleted));
        when(lifecycleManager.listContainersByLabels(anyString(), any())).thenReturn(containers);
        Map<String, String> statuses = Map.of("vm-running", "RUNNING", "vm-stopped", "TERMINATED");

        runtime.reconcile((project, zone, name) -> statuses.get(name));

        verify(lifecycleManager).forceRemove(eq("w-stopped"), isNull());
        verify(lifecycleManager).forceRemove(eq("w-gone"), isNull());
        verify(lifecycleManager, never()).forceRemove(eq("w-running"), any());
        verify(docker, never()).startContainerCmd("w-running");
    }

    private static Intent declared(Kind kind) {
        Map<String, String> metadata = new HashMap<>(METADATA);
        metadata.put("gce-container-declaration", DECLARATION);
        return new Intent(kind, "p", "us-central1-a", "vm", "debian:12", 2, 8192, metadata);
    }

    @Test
    void aBootPublishesTheIntentPortsReportsThemAndAppliesTheSshKeys() {
        runtime.sshKeys((project, zone, name) -> List.of("alice:ssh-ed25519 AAAAC3Nz alice"));
        Intent launch = new Intent(Kind.LAUNCH, "p", "us-central1-a", "vm", "debian:12", 2, 8192, METADATA,
                List.of(22, 80));
        Ports bindings = new Ports();
        bindings.bind(ExposedPort.tcp(22), Ports.Binding.bindPort(32768));
        bindings.bind(ExposedPort.tcp(80), Ports.Binding.bindPort(32769));
        when(docker.inspectContainerCmd("new-id").exec().getNetworkSettings().getPorts()).thenReturn(bindings);

        runtime.submit("launch", List.of(launch));

        verify(builder).withDynamicPort(22);
        verify(builder).withDynamicPort(80);
        verify(scripts).accounts(eq("vm"), eq("new-id"), eq(Map.of("alice", List.of("ssh-ed25519 AAAAC3Nz alice"))),
                anyLong(), any());
        assertTrue(runtime.serialOutput("p", "us-central1-a", "vm", 1, 0).contents()
                .contains("floci-gcp[1]: Published ports: 22/tcp -> localhost:32768, 80/tcp -> localhost:32769"));
    }

    @Test
    void aMetadataChangeReappliesTheCurrentKeysInTheRunningContainer() {
        Container running = container("c-1", "running", Map.of());
        when(lifecycleManager.findByName(CONTAINER)).thenReturn(Optional.of(running));
        runtime.sshKeys((project, zone, name) -> List.of());

        runtime.submit("metadata", List.of(new Intent(Kind.METADATA, "p", "us-central1-a", "vm", null, 0, 0, METADATA)));

        verify(scripts).accounts(eq("vm"), eq("c-1"), eq(Map.of()), anyLong(), any());
        verify(lifecycleManager, never()).createAndStart(any());
    }

    @Test
    void anAccountUpdateQueuedBehindANewerOneIsSkippedAndTheNewerReadsTheLatestKeys() {
        List<Runnable> queued = new ArrayList<>();
        ExecutorService deferred = mock(ExecutorService.class);
        doAnswer(invocation -> queued.add(invocation.getArgument(0))).when(deferred).execute(any());
        ComputeInstanceRuntime runtime = new ComputeInstanceRuntime(config, containerBuilder, lifecycleManager,
                imageCache, scripts, dockerHost, MoreExecutors.newDirectExecutorService(), deferred);
        List<String> keys = new ArrayList<>(List.of("alice:ssh-ed25519 AAAAC3Nz alice"));
        runtime.sshKeys((project, zone, name) -> List.copyOf(keys));
        Container running = container("c-1", "running", Map.of());
        when(lifecycleManager.findByName(CONTAINER)).thenReturn(Optional.of(running));
        Intent change = new Intent(Kind.METADATA, "p", "us-central1-a", "vm", null, 0, 0, METADATA);

        runtime.submit("first", List.of(change));
        keys.clear();
        runtime.submit("second", List.of(change));
        queued.forEach(Runnable::run);

        verify(scripts, times(1)).accounts(any(), any(), any(), anyLong(), any());
        verify(scripts).accounts(eq("vm"), eq("c-1"), eq(Map.of()), anyLong(), any());
    }

    @Test
    void aBootAfterAnEmulatorRestartAppliesTheCurrentKeys() {
        Container stopped = container("vm-c", "exited", identity("vm", "4588"));
        when(lifecycleManager.listContainersByLabels(anyString(), any())).thenReturn(List.of(stopped));
        runtime.sshKeys((project, zone, name) -> List.of("bob:ssh-ed25519 AAAAC3Nz bob"));

        runtime.reconcile((project, zone, name) -> "RUNNING");

        verify(scripts).accounts(eq("vm"), eq("vm-c"), eq(Map.of("bob", List.of("ssh-ed25519 AAAAC3Nz bob"))), anyLong(), any());
    }

    @Test
    void osLoginLeavesAccountsAlone() {
        runtime.sshKeys((project, zone, name) -> null);

        runtime.submit("launch", List.of(intent(Kind.LAUNCH)));

        verify(scripts).accounts(eq("vm"), eq("new-id"), isNull(), anyLong(), any());
    }

    private static Map<String, String> identity(String name, String owner) {
        Map<String, String> labels = new HashMap<>(Map.of("floci", "true", "floci_emulator", "floci-gcp",
                "io.floci.service", "compute", "io.floci.project", "p", "io.floci.location", "us-central1-a",
                "io.floci.resource-id", name));
        labels.put("io.floci.owner", owner);
        return labels;
    }

    private static Container container(String id, String state, Map<String, String> labels) {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn(id);
        when(container.getState()).thenReturn(state);
        when(container.getLabels()).thenReturn(labels);
        return container;
    }
}
