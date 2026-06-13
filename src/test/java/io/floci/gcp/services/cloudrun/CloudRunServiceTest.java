package io.floci.gcp.services.cloudrun;

import com.google.cloud.run.v2.Condition;
import com.google.cloud.run.v2.ListRevisionsResponse;
import com.google.cloud.run.v2.ListServicesResponse;
import com.google.cloud.run.v2.Revision;
import com.google.cloud.run.v2.Service;
import com.google.iam.v1.Binding;
import com.google.iam.v1.Policy;
import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import com.google.protobuf.Message;
import com.google.rpc.Code;
import com.google.rpc.Status;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.cloudrun.model.CloudRunRuntimeInstance;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.floci.gcp.services.operations.LongRunningOperationsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class CloudRunServiceTest {

    private CloudRunService service;
    private IamService iamService;

    @BeforeEach
    void setUp() {
        iamService = mock(IamService.class);
        service = new CloudRunService(new InMemoryStorage<>(), new InMemoryStorage<>(), operationsMock(), iamService);
    }

    @Test
    void createSynthesizesServiceAndRevision() {
        Operation operation = service.createService("p1", "us-central1", "svc",
                "{\"template\":{\"containers\":[{\"image\":\"gcr.io/p1/svc:latest\"}]}}", false);

        assertTrue(operation.getDone());

        Service created = service.getService("projects/p1/locations/us-central1/services/svc");
        assertEquals("projects/p1/locations/us-central1/services/svc", created.getName());
        assertTrue(created.getUri().startsWith("https://svc-"));
        assertEquals(created.getLatestCreatedRevision(), created.getLatestReadyRevision());
        assertEquals(Condition.State.CONDITION_SUCCEEDED, created.getTerminalCondition().getState());
        assertEquals(1, created.getTrafficStatusesCount());
        assertEquals(100, created.getTrafficStatuses(0).getPercent());

        Revision revision = service.getRevision(created.getLatestReadyRevision());
        assertEquals(created.getName(), revision.getService());
        assertEquals("gcr.io/p1/svc:latest", revision.getContainers(0).getImage());
    }

    @Test
    void duplicateCreateAndMissingGetUseGcpErrors() {
        service.createService("p1", "us-central1", "svc", "{}", false);

        GcpException duplicate = assertThrows(GcpException.class,
                () -> service.createService("p1", "us-central1", "svc", "{}", false));
        assertEquals("ALREADY_EXISTS", duplicate.getGcpStatus());

        GcpException missing = assertThrows(GcpException.class,
                () -> service.getService("projects/p1/locations/us-central1/services/missing"));
        assertEquals("NOT_FOUND", missing.getGcpStatus());
    }

    @Test
    void listServicesPaginatesAndFiltersByProjectAndLocation() {
        service.createService("p1", "us-central1", "a", "{}", false);
        service.createService("p1", "us-central1", "b", "{}", false);
        service.createService("p2", "us-central1", "a", "{}", false);
        service.createService("p1", "europe-west1", "c", "{}", false);

        ListServicesResponse firstPage = service.listServices("p1", "us-central1", 1, null);
        assertEquals(1, firstPage.getServicesCount());
        assertFalse(firstPage.getNextPageToken().isBlank());

        ListServicesResponse secondPage = service.listServices("p1", "us-central1", 10,
                firstPage.getNextPageToken());
        assertEquals(1, secondPage.getServicesCount());
        assertEquals("", secondPage.getNextPageToken());

        assertEquals(1, service.listServices("p2", "us-central1", 10, null).getServicesCount());
        assertEquals(1, service.listServices("p1", "europe-west1", 10, null).getServicesCount());
    }

    @Test
    void validateOnlyDoesNotPersistCreateOrDeleteMutations() {
        service.createService("p1", "us-central1", "validate", "{}", true);
        assertThrows(GcpException.class,
                () -> service.getService("projects/p1/locations/us-central1/services/validate"));

        service.createService("p1", "us-central1", "real", "{}", false);
        service.deleteService("projects/p1/locations/us-central1/services/real", true);

        assertEquals("projects/p1/locations/us-central1/services/real",
                service.getService("projects/p1/locations/us-central1/services/real").getName());
    }

    @Test
    void validateOnlyDoesNotInvokeRuntimeWhenExecutionEnabled() {
        CloudRunRuntimeService runtime = mock(CloudRunRuntimeService.class);
        CloudRunService gated = new CloudRunService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                operationsMock(), iamService, cloudRunConfig(true), runtime);

        Operation operation = gated.createService("p1", "us-central1", "validate",
                "{\"template\":{\"containers\":[{\"image\":\"gcr.io/p1/svc:latest\"}]}}", true);

        assertTrue(operation.getDone());
        verifyNoInteractions(runtime);
        assertThrows(GcpException.class,
                () -> gated.getService("projects/p1/locations/us-central1/services/validate"));
    }

    @Test
    void executionEnabledCreateReturnsPendingAndCompletesAfterRuntimeStart() throws InterruptedException {
        LongRunningOperationsService operations = operationsMock();
        Operation pending = Operation.newBuilder()
                .setName("projects/p1/locations/us-central1/operations/runtime-op")
                .setDone(false)
                .build();
        when(operations.pending(anyString(), any(Message.class))).thenReturn(pending);
        CloudRunRuntimeService runtime = mock(CloudRunRuntimeService.class);
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch allowStartToFinish = new CountDownLatch(1);
        when(runtime.start(anyString(), anyString(), any(Service.class), any(Revision.class)))
                .thenAnswer(invocation -> {
                    startEntered.countDown();
                    assertTrue(allowStartToFinish.await(2, TimeUnit.SECONDS));
                    return new CloudRunRuntimeInstance("p1", "us-central1",
                        "projects/p1/locations/us-central1/services/svc",
                        "projects/p1/locations/us-central1/services/svc/revisions/svc-00001",
                        "gcr.io/p1/svc:latest", "container-id", 8080, "localhost", 12345,
                        "http://localhost:4588/run/v2/projects/p1/locations/us-central1/services/svc",
                        "READY", 1, 1, null, 300_000);
                });
        CloudRunService gated = new CloudRunService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                operations, iamService, cloudRunConfig(true), runtime);

        Operation operation = gated.createService("p1", "us-central1", "svc",
                "{\"template\":{\"containers\":[{\"image\":\"gcr.io/p1/svc:latest\"}]}}", false);

        assertFalse(operation.getDone());
        assertTrue(startEntered.await(1, TimeUnit.SECONDS));
        Service starting = gated.getService("projects/p1/locations/us-central1/services/svc");
        assertTrue(starting.getReconciling());
        assertEquals("", starting.getLatestReadyRevision());
        assertEquals(Condition.State.CONDITION_PENDING, starting.getTerminalCondition().getState());

        allowStartToFinish.countDown();
        verify(runtime, timeout(1000)).start(eq("p1"), eq("us-central1"), any(Service.class), any(Revision.class));
        verify(operations, timeout(1000)).complete(eq(pending.getName()), any(Service.class), any(Service.class));
        Service ready = gated.getService("projects/p1/locations/us-central1/services/svc");
        assertFalse(ready.getReconciling());
        assertEquals(ready.getLatestCreatedRevision(), ready.getLatestReadyRevision());
        assertEquals("http://localhost:4588/run/v2/projects/p1/locations/us-central1/services/svc", ready.getUri());
    }

    @Test
    void executionEnabledRejectsUnsupportedTemplateSynchronously() {
        CloudRunRuntimeService runtime = mock(CloudRunRuntimeService.class);
        CloudRunService gated = new CloudRunService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                operationsMock(), iamService, cloudRunConfig(true), runtime);

        GcpException error = assertThrows(GcpException.class, () -> gated.createService("p1", "us-central1", "svc",
                "{\"template\":{\"containers\":[{\"image\":\"gcr.io/p1/one\"},{\"image\":\"gcr.io/p1/two\"}]}}", false));

        assertEquals("INVALID_ARGUMENT", error.getGcpStatus());
        verifyNoInteractions(runtime);
        assertThrows(GcpException.class,
                () -> gated.getService("projects/p1/locations/us-central1/services/svc"));
    }

    @Test
    void executionEnabledRuntimeFailureMarksServiceAndOperationFailed() {
        LongRunningOperationsService operations = operationsMock();
        Operation pending = Operation.newBuilder()
                .setName("projects/p1/locations/us-central1/operations/runtime-op")
                .setDone(false)
                .build();
        when(operations.pending(anyString(), any(Message.class))).thenReturn(pending);
        when(operations.fail(eq(pending.getName()), any(Status.class), any(Service.class)))
                .thenAnswer(invocation -> Operation.newBuilder()
                        .setName(pending.getName())
                        .setDone(true)
                        .setError(invocation.getArgument(1, Status.class))
                        .setMetadata(Any.pack(invocation.getArgument(2, Message.class)))
                        .build());
        CloudRunRuntimeService runtime = mock(CloudRunRuntimeService.class);
        doThrow(GcpException.unavailable("runtime failed"))
                .when(runtime).start(anyString(), anyString(), any(Service.class), any(Revision.class));
        CloudRunService gated = new CloudRunService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                operations, iamService, cloudRunConfig(true), runtime);

        Operation operation = gated.createService("p1", "us-central1", "svc",
                "{\"template\":{\"containers\":[{\"image\":\"gcr.io/p1/svc:latest\"}]}}", false);

        assertFalse(operation.getDone());
        verify(operations, timeout(1000)).fail(eq(pending.getName()), argThat(status ->
                status.getCode() == Code.INTERNAL_VALUE && status.getMessage().contains("runtime failed")),
                any(Service.class));
        Service failed = gated.getService("projects/p1/locations/us-central1/services/svc");
        assertFalse(failed.getReconciling());
        assertEquals("", failed.getLatestReadyRevision());
        assertEquals(Condition.State.CONDITION_FAILED, failed.getTerminalCondition().getState());
        assertTrue(failed.getTerminalCondition().getMessage().contains("runtime failed"));
        Revision failedRevision = gated.getRevision(failed.getLatestCreatedRevision());
        assertFalse(failedRevision.getReconciling());
        assertEquals(Condition.State.CONDITION_FAILED, failedRevision.getConditions(0).getState());
        assertTrue(failedRevision.getConditions(0).getMessage().contains("runtime failed"));
    }

    @Test
    void updateServiceAppliesFieldMaskAndCreatesReadyRevision() {
        service.createService("p1", "us-central1", "svc",
                "{\"template\":{\"containers\":[{\"image\":\"gcr.io/p1/svc:v1\"}]},\"labels\":{\"env\":\"old\"}}",
                false);

        Operation operation = service.updateService("projects/p1/locations/us-central1/services/svc",
                """
                {
                  "labels": {"env": "new"},
                  "template": {
                    "containers": [{
                      "image": "gcr.io/p1/svc:v2",
                      "env": [{"name": "MODE", "value": "compat"}],
                      "ports": [{"containerPort": 9090}]
                    }]
                  }
                }
                """,
                "labels,template", false);

        assertTrue(operation.getDone());
        Service updated = service.getService("projects/p1/locations/us-central1/services/svc");
        assertEquals("new", updated.getLabelsOrThrow("env"));
        assertEquals("projects/p1/locations/us-central1/services/svc/revisions/svc-00002",
                updated.getLatestCreatedRevision());
        assertEquals(updated.getLatestCreatedRevision(), updated.getLatestReadyRevision());
        assertFalse(updated.getReconciling());

        Revision revision = service.getRevision(updated.getLatestReadyRevision());
        assertEquals("gcr.io/p1/svc:v2", revision.getContainers(0).getImage());
        assertEquals("MODE", revision.getContainers(0).getEnv(0).getName());
        assertEquals(9090, revision.getContainers(0).getPorts(0).getContainerPort());
    }

    @Test
    void validateOnlyUpdateDoesNotPersistMutation() {
        service.createService("p1", "us-central1", "svc",
                "{\"template\":{\"containers\":[{\"image\":\"gcr.io/p1/svc:v1\"}]},\"labels\":{\"env\":\"old\"}}",
                false);

        Operation operation = service.updateService("projects/p1/locations/us-central1/services/svc",
                "{\"labels\":{\"env\":\"new\"}}", "labels", true);

        assertTrue(operation.getDone());
        Service stored = service.getService("projects/p1/locations/us-central1/services/svc");
        assertEquals("old", stored.getLabelsOrThrow("env"));
    }

    @Test
    void updateWithoutMaskDoesNotCreateRevisionWhenTemplateIsUnchanged() {
        service.createService("p1", "us-central1", "svc",
                "{\"template\":{\"containers\":[{\"image\":\"gcr.io/p1/svc:v1\"}]},\"labels\":{\"env\":\"old\"}}",
                false);
        Service initial = service.getService("projects/p1/locations/us-central1/services/svc");

        Operation operation = service.updateService("projects/p1/locations/us-central1/services/svc",
                """
                {
                  "labels": {"env": "new"},
                  "template": {
                    "containers": [{"image": "gcr.io/p1/svc:v1"}]
                  }
                }
                """,
                null, false);

        assertTrue(operation.getDone());
        Service updated = service.getService("projects/p1/locations/us-central1/services/svc");
        assertEquals("new", updated.getLabelsOrThrow("env"));
        assertEquals(initial.getLatestCreatedRevision(), updated.getLatestCreatedRevision());
        assertEquals(initial.getLatestReadyRevision(), updated.getLatestReadyRevision());
    }

    @Test
    void executionEnabledUpdateKeepsOldRevisionReadyUntilRuntimeStarts() throws InterruptedException {
        LongRunningOperationsService operations = operationsMock();
        Operation pending = Operation.newBuilder()
                .setName("projects/p1/locations/us-central1/operations/update-runtime-op")
                .setDone(false)
                .build();
        when(operations.pending(anyString(), any(Message.class))).thenReturn(pending);
        CloudRunRuntimeService runtime = mock(CloudRunRuntimeService.class);
        when(runtime.start(anyString(), anyString(), any(Service.class), any(Revision.class)))
                .thenReturn(new CloudRunRuntimeInstance("p1", "us-central1",
                        "projects/p1/locations/us-central1/services/svc",
                        "projects/p1/locations/us-central1/services/svc/revisions/svc-00001",
                        "gcr.io/p1/svc:v1", "container-v1", 8080, "localhost", 12345,
                        "http://localhost:4588/run/v2/projects/p1/locations/us-central1/services/svc",
                        "READY", 1, 1, null, 300_000))
                .thenReturn(new CloudRunRuntimeInstance("p1", "us-central1",
                        "projects/p1/locations/us-central1/services/svc",
                        "projects/p1/locations/us-central1/services/svc/revisions/svc-00002",
                        "gcr.io/p1/svc:v2", "container-v2", 8080, "localhost", 12346,
                        "http://localhost:4588/run/v2/projects/p1/locations/us-central1/services/svc",
                        "READY", 1, 1, null, 300_000));
        CloudRunService gated = new CloudRunService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                operations, iamService, cloudRunConfig(true), runtime);

        gated.createService("p1", "us-central1", "svc",
                "{\"template\":{\"containers\":[{\"image\":\"gcr.io/p1/svc:v1\"}]}}", false);
        verify(operations, timeout(1000)).complete(eq(pending.getName()), any(Service.class), any(Service.class));
        Service initial = gated.getService("projects/p1/locations/us-central1/services/svc");
        assertEquals(initial.getLatestCreatedRevision(), initial.getLatestReadyRevision());

        Operation update = gated.updateService("projects/p1/locations/us-central1/services/svc",
                "{\"template\":{\"containers\":[{\"image\":\"gcr.io/p1/svc:v2\"}]}}", "template", false);

        assertFalse(update.getDone());
        Service updating = gated.getService("projects/p1/locations/us-central1/services/svc");
        assertTrue(updating.getReconciling());
        assertEquals(initial.getLatestReadyRevision(), updating.getLatestReadyRevision());
        assertEquals("projects/p1/locations/us-central1/services/svc/revisions/svc-00002",
                updating.getLatestCreatedRevision());

        verify(runtime, timeout(1000)).stopOtherRevisions(eq("projects/p1/locations/us-central1/services/svc"),
                eq("projects/p1/locations/us-central1/services/svc/revisions/svc-00002"));
        Service ready = gated.getService("projects/p1/locations/us-central1/services/svc");
        assertFalse(ready.getReconciling());
        assertEquals(ready.getLatestCreatedRevision(), ready.getLatestReadyRevision());
    }

    @Test
    void deleteRemovesServiceAndReadOnlyRevision() {
        service.createService("p1", "us-central1", "svc", "{}", false);
        String name = "projects/p1/locations/us-central1/services/svc";
        String revision = service.getService(name).getLatestReadyRevision();

        Operation operation = service.deleteService(name, false);

        assertTrue(operation.getDone());
        assertThrows(GcpException.class, () -> service.getService(name));
        assertThrows(GcpException.class, () -> service.getRevision(revision));
    }

    @Test
    void listRevisionsPaginatesAndRequiresParentService() {
        service.createService("p1", "us-central1", "svc", "{}", false);

        ListRevisionsResponse revisions = service.listRevisions(
                "projects/p1/locations/us-central1/services/svc", 1, null);

        assertEquals(1, revisions.getRevisionsCount());
        assertEquals("projects/p1/locations/us-central1/services/svc", revisions.getRevisions(0).getService());
        assertThrows(GcpException.class, () -> service.listRevisions(
                "projects/p1/locations/us-central1/services/missing", 1, null));
    }

    @Test
    void iamPolicyConversionsDelegateToIamService() {
        String resource = "projects/p1/locations/us-central1/services/svc";
        StoredPolicy stored = new StoredPolicy();
        stored.setVersion(3);
        stored.setBindings(List.of());
        when(iamService.getPolicy(resource)).thenReturn(stored);

        assertEquals(3, service.getIamPolicy(resource).getVersion());

        Policy requested = Policy.newBuilder()
                .setVersion(1)
                .addBindings(Binding.newBuilder()
                        .setRole("roles/run.invoker")
                        .addMembers("allUsers")
                        .build())
                .build();
        when(iamService.setPolicy(eq(resource), any(StoredPolicy.class))).thenAnswer(invocation -> invocation.getArgument(1));

        Policy saved = service.setIamPolicy(resource, requested);

        assertEquals("roles/run.invoker", saved.getBindings(0).getRole());
        assertEquals(List.of("allUsers"), saved.getBindings(0).getMembersList());
    }

    private static LongRunningOperationsService operationsMock() {
        LongRunningOperationsService operations = mock(LongRunningOperationsService.class);
        when(operations.done(anyString(), any(Message.class), any(Message.class)))
                .thenAnswer(invocation -> Operation.newBuilder()
                        .setName(invocation.getArgument(0, String.class) + "/operations/test-op")
                        .setDone(true)
                        .setResponse(Any.pack(invocation.getArgument(1, Message.class)))
                        .setMetadata(Any.pack(invocation.getArgument(2, Message.class)))
                        .build());
        when(operations.doneTransient(anyString(), any(Message.class), any(Message.class)))
                .thenAnswer(invocation -> Operation.newBuilder()
                        .setName(invocation.getArgument(0, String.class) + "/operations/test-op")
                        .setDone(true)
                        .setResponse(Any.pack(invocation.getArgument(1, Message.class)))
                        .setMetadata(Any.pack(invocation.getArgument(2, Message.class)))
                        .build());
        return operations;
    }

    private static EmulatorConfig cloudRunConfig(boolean executionEnabled) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().cloudrun().execution().enabled()).thenReturn(executionEnabled);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4588");
        return config;
    }
}
