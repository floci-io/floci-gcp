package io.floci.gcp.services.cloudrun;

import com.google.cloud.run.v2.Condition;
import com.google.cloud.run.v2.Instance;
import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import com.google.rpc.Status;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.cloudrun.model.CloudRunRuntimeInstance;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.operations.LongRunningOperationsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CloudRunInstancesServiceTest {

    private static final String NAME = "projects/p1/locations/us-central1/instances/inst";
    private static final String BODY = "{\"containers\":[{\"image\":\"busybox\"}]}";

    private LongRunningOperationsService operations;
    private IamService iamService;
    private CloudRunInstancesRuntime runtime;
    private CloudRunUrlService urlService;
    private InMemoryStorage<String, String> store;

    @BeforeEach
    void setUp() {
        operations = operationsMock();
        iamService = mock(IamService.class);
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(iamService).deleteResourceAndPolicy(anyString(), any(Runnable.class));
        runtime = mock(CloudRunInstancesRuntime.class);
        urlService = mock(CloudRunUrlService.class);
        when(urlService.invocationUri(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> "http://" + invocation.getArgument(2) + "-token.us-central1.run.test:4588");
        store = new InMemoryStorage<>();
    }

    @Test
    void generatedIdsAreFifteenLowercaseAlphanumericsStartingWithALetter() {
        Random random = new Random(42);
        for (int i = 0; i < 1000; i++) {
            String id = CloudRunInstanceStates.generateId(random);
            assertTrue(id.matches("[a-z][a-z0-9]{14}"), id);
        }
    }

    @Test
    void durationsFormatLikeGcpConditionMessages() {
        assertEquals("14.39s", CloudRunInstanceStates.seconds(Duration.ofMillis(14390)));
        assertEquals("1.1s", CloudRunInstanceStates.seconds(Duration.ofMillis(1100)));
        assertEquals("2.92s", CloudRunInstanceStates.seconds(Duration.ofMillis(2918)));
        assertEquals("0s", CloudRunInstanceStates.seconds(Duration.ZERO));
        assertEquals("3s", CloudRunInstanceStates.seconds(Duration.ofSeconds(3)));
    }

    @Test
    void phasesFollowTheRunningTerminalCondition() {
        Timestamp now = Timestamp.getDefaultInstance();
        assertPhase(CloudRunInstanceStates.starting(now), CloudRunInstanceStates.Phase.STARTING, true);
        assertPhase(CloudRunInstanceStates.running(now, Duration.ZERO), CloudRunInstanceStates.Phase.RUNNING, true);
        assertPhase(CloudRunInstanceStates.stopping(now), CloudRunInstanceStates.Phase.STOPPING, false);
        assertPhase(CloudRunInstanceStates.stopped(now), CloudRunInstanceStates.Phase.STOPPED, false);
        assertPhase(CloudRunInstanceStates.failed(now, "boom"), CloudRunInstanceStates.Phase.FAILED, false);
    }

    @Test
    void stopRequestedWhileStartingWinsOverTheEarlierStart() throws Exception {
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        when(runtime.start(anyString(), anyString(), any(Instance.class), anyString())).thenAnswer(invocation -> {
            startEntered.countDown();
            assertTrue(releaseStart.await(5, TimeUnit.SECONDS));
            return started();
        });
        CloudRunInstancesService service = dockerService();

        Operation create = service.createInstance("p1", "us-central1", "inst", BODY, false);
        assertFalse(create.getDone());
        assertTrue(startEntered.await(5, TimeUnit.SECONDS));

        Operation stop = service.stopInstance(NAME, false);
        assertFalse(stop.getDone());
        assertEquals(CloudRunInstanceStates.STOPPING_MESSAGE,
                service.getInstance(NAME).getTerminalCondition().getMessage());
        releaseStart.countDown();

        Instance startResult = awaitComplete(create);
        assertEquals(CloudRunInstanceStates.STOPPING_MESSAGE, startResult.getTerminalCondition().getMessage());
        Instance stopResult = awaitComplete(stop);
        assertEquals(CloudRunInstanceStates.STOPPED_MESSAGE, stopResult.getTerminalCondition().getMessage());

        Instance stored = service.getInstance(NAME);
        assertEquals(CloudRunInstanceStates.Phase.STOPPED, CloudRunInstanceStates.phase(stored));
        assertEquals(2, stored.getGeneration());
        assertEquals(2, stored.getObservedGeneration());
        assertFalse(stored.getReconciling());
        assertEquals("busybox@sha256:abc", stored.getContainerStatuses(0).getImageDigest());
        assertEquals(1, stored.getUrlsCount());
        InOrder order = inOrder(runtime);
        order.verify(runtime).start(anyString(), anyString(), any(Instance.class), anyString());
        order.verify(runtime).stop(any(Instance.class));
    }

    @Test
    void deleteWhileStartingPublishesNothingAndRemovesTheContainerAfterTheStart() throws Exception {
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        when(runtime.start(anyString(), anyString(), any(Instance.class), anyString())).thenAnswer(invocation -> {
            startEntered.countDown();
            assertTrue(releaseStart.await(5, TimeUnit.SECONDS));
            return started();
        });
        CloudRunInstancesService service = dockerService();

        Operation create = service.createInstance("p1", "us-central1", "inst", BODY, false);
        assertTrue(startEntered.await(5, TimeUnit.SECONDS));
        Operation delete = service.deleteInstance(NAME, false);
        assertThrows(GcpException.class, () -> service.getInstance(NAME));
        releaseStart.countDown();

        awaitComplete(create);
        Instance deleted = awaitComplete(delete);
        assertEquals(CloudRunInstanceStates.DELETED_MESSAGE, deleted.getTerminalCondition().getMessage());
        assertTrue(store.get(NAME).isEmpty());
        InOrder order = inOrder(runtime);
        order.verify(runtime).start(anyString(), anyString(), any(Instance.class), anyString());
        order.verify(runtime).stop(any(Instance.class));
    }

    @Test
    void startSupersededByDeleteAndRecreateDoesNotWriteIntoTheNewInstance() throws Exception {
        CountDownLatch firstStartEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstStart = new CountDownLatch(1);
        CountDownLatch secondStartEntered = new CountDownLatch(1);
        CountDownLatch releaseSecondStart = new CountDownLatch(1);
        when(runtime.start(anyString(), anyString(), any(Instance.class), anyString()))
                .thenAnswer(invocation -> {
                    firstStartEntered.countDown();
                    assertTrue(releaseFirstStart.await(5, TimeUnit.SECONDS));
                    return started();
                })
                .thenAnswer(invocation -> {
                    secondStartEntered.countDown();
                    assertTrue(releaseSecondStart.await(5, TimeUnit.SECONDS));
                    return started();
                });
        CloudRunInstancesService service = dockerService();

        Operation firstCreate = service.createInstance("p1", "us-central1", "inst", BODY, false);
        String firstUid = service.getInstance(NAME).getUid();
        assertTrue(firstStartEntered.await(5, TimeUnit.SECONDS));
        Operation delete = service.deleteInstance(NAME, false);
        Operation secondCreate = service.createInstance("p1", "us-central1", "inst", BODY, false);
        String secondUid = service.getInstance(NAME).getUid();
        assertNotEquals(firstUid, secondUid);
        releaseFirstStart.countDown();

        Instance firstResult = awaitComplete(firstCreate);
        assertEquals(firstUid, firstResult.getUid());
        awaitComplete(delete);
        assertTrue(secondStartEntered.await(5, TimeUnit.SECONDS));
        Instance recreated = service.getInstance(NAME);
        assertEquals(secondUid, recreated.getUid());
        assertEquals(CloudRunInstanceStates.Phase.STARTING, CloudRunInstanceStates.phase(recreated));
        assertEquals(0, recreated.getContainerStatusesCount());
        assertEquals(0, recreated.getConditionsCount());
        assertEquals(0, recreated.getUrlsCount());
        verify(operations, never()).complete(eq(secondCreate.getName()), any(Message.class), any(Message.class));

        releaseSecondStart.countDown();
        Instance secondResult = awaitComplete(secondCreate);
        assertEquals(secondUid, secondResult.getUid());
        assertEquals(CloudRunInstanceStates.Phase.RUNNING, CloudRunInstanceStates.phase(service.getInstance(NAME)));
    }

    @Test
    void failedRestartReplacesTheEarlierSuccessConditionsAndDigest() throws Exception {
        when(runtime.start(anyString(), anyString(), any(Instance.class), anyString()))
                .thenReturn(started())
                .thenThrow(GcpException.unavailable("port never opened"));
        CloudRunInstancesService service = dockerService();
        Instance running = awaitComplete(service.createInstance("p1", "us-central1", "inst", BODY, false));
        assertEquals("busybox@sha256:abc", running.getContainerStatuses(0).getImageDigest());

        Operation patch = service.updateInstance(NAME, "{\"containers\":[{\"name\":\"app\",\"image\":\"nginx\"}]}",
                "containers", false, false);
        verify(operations, timeout(5000)).fail(eq(patch.getName()), any(Status.class), any(Message.class));

        Instance failed = service.getInstance(NAME);
        assertEquals(CloudRunInstanceStates.Phase.FAILED, CloudRunInstanceStates.phase(failed));
        assertEquals(1, failed.getContainerStatusesCount());
        assertEquals("app", failed.getContainerStatuses(0).getName());
        assertEquals("", failed.getContainerStatuses(0).getImageDigest());
        assertEquals(2, failed.getConditionsCount());
        for (Condition condition : failed.getConditionsList()) {
            assertEquals(Condition.State.CONDITION_FAILED, condition.getState(), condition.getType());
            assertEquals("port never opened", condition.getMessage());
        }
        assertEquals(1, failed.getUrlsCount());
    }

    @Test
    void stopManagedContainersRemovesInstanceContainersOnlyWithExecution() {
        dockerService().stopManagedContainers();
        verify(runtime).stopAll();

        CloudRunInstancesRuntime mockRuntime = mock(CloudRunInstancesRuntime.class);
        new CloudRunInstancesService(store, operations, iamService, name -> false, mockRuntime, urlService, true)
                .stopManagedContainers();
        verify(mockRuntime, never()).stopAll();
    }

    @Test
    void labelPatchWhileStartingLeavesTheStartOwningTheTerminalCondition() throws Exception {
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        when(runtime.start(anyString(), anyString(), any(Instance.class), anyString())).thenAnswer(invocation -> {
            startEntered.countDown();
            assertTrue(releaseStart.await(5, TimeUnit.SECONDS));
            return started();
        });
        CloudRunInstancesService service = dockerService();

        Operation create = service.createInstance("p1", "us-central1", "inst", BODY, false);
        assertTrue(startEntered.await(5, TimeUnit.SECONDS));
        Operation patch = service.updateInstance(NAME, "{\"labels\":{\"env\":\"test\"}}", "labels", false, false);
        assertTrue(patch.getDone());
        releaseStart.countDown();

        Instance started = awaitComplete(create);
        assertEquals(Condition.State.CONDITION_SUCCEEDED, started.getTerminalCondition().getState());
        Instance stored = service.getInstance(NAME);
        assertEquals(CloudRunInstanceStates.Phase.RUNNING, CloudRunInstanceStates.phase(stored));
        assertEquals("test", stored.getLabelsOrThrow("env"));
        assertEquals(2, stored.getGeneration());
        assertEquals(2, stored.getObservedGeneration());
        verify(runtime, times(1)).start(anyString(), anyString(), any(Instance.class), anyString());
    }

    @Test
    void failedStartMarksTheInstanceFailedAndAllowsAnotherStart() throws Exception {
        when(runtime.start(anyString(), anyString(), any(Instance.class), anyString()))
                .thenThrow(GcpException.unavailable("port never opened"))
                .thenReturn(started());
        CloudRunInstancesService service = dockerService();

        Operation create = service.createInstance("p1", "us-central1", "inst", BODY, false);
        ArgumentCaptor<Status> error = ArgumentCaptor.forClass(Status.class);
        verify(operations, timeout(5000)).fail(eq(create.getName()), error.capture(), any(Message.class));
        assertEquals("port never opened", error.getValue().getMessage());
        Instance failed = service.getInstance(NAME);
        assertEquals(CloudRunInstanceStates.Phase.FAILED, CloudRunInstanceStates.phase(failed));
        assertThrows(GcpException.class, () -> service.stopInstance(NAME, false));

        Operation start = service.startInstance(NAME, false);
        Instance running = awaitComplete(start);
        assertEquals(CloudRunInstanceStates.Phase.RUNNING, CloudRunInstanceStates.phase(running));
        assertEquals(2, running.getObservedGeneration());
    }

    @Test
    void containerPatchRestartsOnlyARunningInstance() throws Exception {
        when(runtime.start(anyString(), anyString(), any(Instance.class), anyString())).thenReturn(started());
        CloudRunInstancesService service = dockerService();
        awaitComplete(service.createInstance("p1", "us-central1", "inst", BODY, false));
        awaitComplete(service.stopInstance(NAME, false));

        Operation patch = service.updateInstance(NAME, "{\"containers\":[{\"image\":\"nginx\"}]}", "containers",
                false, false);
        assertTrue(patch.getDone());
        assertEquals(CloudRunInstanceStates.Phase.STOPPED, CloudRunInstanceStates.phase(service.getInstance(NAME)));
        verify(runtime, times(1)).start(anyString(), anyString(), any(Instance.class), anyString());

        awaitComplete(service.startInstance(NAME, false));
        Operation restart = service.updateInstance(NAME, "{\"containers\":[{\"image\":\"httpd\"}]}", "containers",
                false, false);
        assertFalse(restart.getDone());
        Instance restarted = awaitComplete(restart);
        assertEquals("httpd", restarted.getContainers(0).getImage());
        assertEquals(CloudRunInstanceStates.Phase.RUNNING, CloudRunInstanceStates.phase(restarted));
        verify(runtime, times(3)).start(anyString(), anyString(), any(Instance.class), anyString());
    }

    @Test
    void nestedMaskPatchMergesAndRestartsOnlyWhenContainersChange() throws Exception {
        when(runtime.start(anyString(), anyString(), any(Instance.class), anyString())).thenReturn(started());
        CloudRunInstancesService service = dockerService();
        awaitComplete(service.createInstance("p1", "us-central1", "inst",
                "{\"containers\":[{\"image\":\"busybox\"}],"
                        + "\"vpcAccess\":{\"connector\":\"a\",\"egress\":\"ALL_TRAFFIC\"}}", false));

        Operation nested = service.updateInstance(NAME, "{\"vpcAccess\":{\"connector\":\"b\"}}",
                "vpcAccess.connector", false, false);
        assertTrue(nested.getDone());
        Instance merged = service.getInstance(NAME);
        assertEquals("b", merged.getVpcAccess().getConnector());
        assertEquals("ALL_TRAFFIC", merged.getVpcAccess().getEgress().name());
        verify(runtime, times(1)).start(anyString(), anyString(), any(Instance.class), anyString());

        Operation mixed = service.updateInstance(NAME,
                "{\"containers\":[{\"image\":\"nginx\"}],\"vpcAccess\":{\"connector\":\"c\"}}",
                "containers,vpcAccess.connector", false, false);
        assertFalse(mixed.getDone());
        Instance restarted = awaitComplete(mixed);
        assertEquals("nginx", restarted.getContainers(0).getImage());
        assertEquals("c", restarted.getVpcAccess().getConnector());
        assertEquals("ALL_TRAFFIC", restarted.getVpcAccess().getEgress().name());
        verify(runtime, times(2)).start(anyString(), anyString(), any(Instance.class), anyString());

        GcpException repeatedPath = assertThrows(GcpException.class, () -> service.updateInstance(NAME,
                "{}", "containers.image", false, false));
        assertEquals("Invalid update mask path: containers.image", repeatedPath.getMessage());
    }

    @Test
    void dockerModeRejectsUnsupportedTemplatesWithServicesMessages() {
        CloudRunInstancesService service = dockerService();

        GcpException sidecar = assertThrows(GcpException.class, () -> service.createInstance("p1", "us-central1",
                "inst", "{\"containers\":[{\"image\":\"a\"},{\"image\":\"b\"}]}", false));
        assertEquals("INVALID_ARGUMENT", sidecar.getGcpStatus());
        assertEquals("Cloud Run execution supports exactly one container", sidecar.getMessage());
        assertTrue(store.get(NAME).isEmpty());
    }

    private CloudRunInstancesService dockerService() {
        return new CloudRunInstancesService(store, operations, iamService, name -> false, runtime, urlService, false);
    }

    private Instance awaitComplete(Operation operation) throws Exception {
        if (operation.getDone()) {
            return operation.getResponse().unpack(Instance.class);
        }
        ArgumentCaptor<Message> response = ArgumentCaptor.forClass(Message.class);
        verify(operations, timeout(5000)).complete(eq(operation.getName()), response.capture(), any(Message.class));
        return (Instance) response.getValue();
    }

    private static CloudRunInstancesRuntime.Started started() {
        CloudRunRuntimeInstance record = new CloudRunRuntimeInstance("p1", "us-central1", NAME, NAME, "busybox",
                "container-id", 8080, null, "127.0.0.1", 32768, "http://inst", "READY", 0, 0, null, 1000);
        return new CloudRunInstancesRuntime.Started(record, "busybox@sha256:abc", Duration.ofMillis(1500));
    }

    private static void assertPhase(Condition terminal, CloudRunInstanceStates.Phase phase, boolean desiredRunning) {
        Instance instance = Instance.newBuilder().setTerminalCondition(terminal).build();
        assertEquals(phase, CloudRunInstanceStates.phase(instance));
        assertEquals(desiredRunning, CloudRunInstanceStates.desiredRunning(instance));
    }

    private static LongRunningOperationsService operationsMock() {
        LongRunningOperationsService operations = mock(LongRunningOperationsService.class);
        when(operations.done(anyString(), any(Message.class), any(Message.class)))
                .thenAnswer(invocation -> completed(invocation.getArgument(0, String.class),
                        invocation.getArgument(1, Message.class)));
        when(operations.doneTransient(anyString(), any(Message.class), any(Message.class)))
                .thenAnswer(invocation -> completed(invocation.getArgument(0, String.class),
                        invocation.getArgument(1, Message.class)));
        when(operations.pending(anyString(), any(Message.class)))
                .thenAnswer(invocation -> Operation.newBuilder()
                        .setName(invocation.getArgument(0, String.class) + "/operations/" + System.nanoTime())
                        .setDone(false)
                        .setMetadata(Any.pack(invocation.getArgument(1, Message.class)))
                        .build());
        return operations;
    }

    private static Operation completed(String parent, Message response) {
        return Operation.newBuilder()
                .setName(parent + "/operations/" + System.nanoTime())
                .setDone(true)
                .setResponse(Any.pack(response))
                .build();
    }
}
