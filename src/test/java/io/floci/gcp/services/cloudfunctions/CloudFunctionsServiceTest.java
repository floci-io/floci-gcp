package io.floci.gcp.services.cloudfunctions;

import com.google.cloud.functions.v2.Environment;
import com.google.cloud.functions.v2.Function;
import com.google.cloud.functions.v2.GenerateUploadUrlResponse;
import com.google.cloud.functions.v2.ListFunctionsResponse;
import com.google.cloud.functions.v2.OnDeployUpdatePolicy;
import com.google.cloud.functions.v2.OperationMetadata;
import com.google.cloud.functions.v2.OperationType;
import com.google.iam.v1.Policy;
import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import com.google.protobuf.Message;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.gcs.GcsService;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.operations.LongRunningOperationsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CloudFunctionsServiceTest {

    private CloudFunctionsService service;
    private GcsService gcsService;
    private IamService iamService;
    private InMemoryStorage<String, String> functionStore;

    @BeforeEach
    void setUp() {
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
        gcsService = mock(GcsService.class);
        iamService = mock(IamService.class);
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(iamService).deleteResourceAndPolicy(anyString(), any(Runnable.class));
        functionStore = new InMemoryStorage<>();
        service = new CloudFunctionsService(functionStore, operations, gcsService, iamService);
    }

    @Test
    void createSynthesizesActiveGen2Function() {
        Operation operation = service.createFunction("p1", "us-central1", "fn",
                "{\"buildConfig\":{\"runtime\":\"java21\"}}", false);

        assertTrue(operation.getDone());
        Function function = service.getFunction("projects/p1/locations/us-central1/functions/fn");
        assertEquals("projects/p1/locations/us-central1/functions/fn", function.getName());
        assertEquals(Function.State.ACTIVE, function.getState());
        assertEquals(Environment.GEN_2, function.getEnvironment());
        assertEquals("https://us-central1-p1.cloudfunctions.net/fn", function.getUrl());
        assertEquals("projects/p1/locations/us-central1/services/fn", function.getServiceConfig().getService());
        assertTrue(function.getServiceConfig().getAllTrafficOnLatestRevision());
    }

    @Test
    void duplicateCreateAndMissingGetUseGcpErrors() {
        service.createFunction("p1", "us-central1", "fn", "{}", false);

        GcpException duplicate = assertThrows(GcpException.class,
                () -> service.createFunction("p1", "us-central1", "fn", "{}", false));
        assertEquals("ALREADY_EXISTS", duplicate.getGcpStatus());

        GcpException missing = assertThrows(GcpException.class,
                () -> service.getFunction("projects/p1/locations/us-central1/functions/missing"));
        assertEquals("NOT_FOUND", missing.getGcpStatus());
    }

    @Test
    void listFunctionsPaginatesAndFiltersByProjectAndLocation() {
        service.createFunction("p1", "us-central1", "a", "{}", false);
        service.createFunction("p1", "us-central1", "b", "{}", false);
        service.createFunction("p2", "us-central1", "a", "{}", false);
        service.createFunction("p1", "europe-west1", "c", "{}", false);

        ListFunctionsResponse firstPage = service.listFunctions("p1", "us-central1", 1, null);
        assertEquals(1, firstPage.getFunctionsCount());
        assertFalse(firstPage.getNextPageToken().isBlank());

        ListFunctionsResponse secondPage = service.listFunctions("p1", "us-central1", 10,
                firstPage.getNextPageToken());
        assertEquals(1, secondPage.getFunctionsCount());
        assertEquals("", secondPage.getNextPageToken());

        assertEquals(1, service.listFunctions("p2", "us-central1", 10, null).getFunctionsCount());
        assertEquals(1, service.listFunctions("p1", "europe-west1", 10, null).getFunctionsCount());
    }

    @Test
    void listFunctionsWithLocationWildcardSpansAllLocationsOfProject() {
        service.createFunction("p1", "us-central1", "a", "{}", false);
        service.createFunction("p1", "europe-west1", "b", "{}", false);
        service.createFunction("p2", "us-central1", "c", "{}", false);

        ListFunctionsResponse response = service.listFunctions("p1", "-", 10, null);

        assertEquals(List.of(
                        "projects/p1/locations/europe-west1/functions/b",
                        "projects/p1/locations/us-central1/functions/a"),
                response.getFunctionsList().stream().map(Function::getName).toList());
    }

    @Test
    void validateOnlyDoesNotPersistCreateOrDeleteMutations() {
        service.createFunction("p1", "us-central1", "validate", "{}", true);
        assertThrows(GcpException.class,
                () -> service.getFunction("projects/p1/locations/us-central1/functions/validate"));

        service.createFunction("p1", "us-central1", "real", "{}", false);
        service.deleteFunction("projects/p1/locations/us-central1/functions/real", true);

        assertEquals("projects/p1/locations/us-central1/functions/real",
                service.getFunction("projects/p1/locations/us-central1/functions/real").getName());
    }

    @Test
    void deleteFunctionRemovesStoredFunction() {
        service.createFunction("p1", "us-central1", "fn", "{}", false);
        String name = "projects/p1/locations/us-central1/functions/fn";

        Operation operation = service.deleteFunction(name, false);

        assertTrue(operation.getDone());
        assertThrows(GcpException.class, () -> service.getFunction(name));
    }

    @Test
    void updateWithMaskChangesOnlyMaskedFieldsAndKeepsOutputOnlyOnes() throws Exception {
        service.createFunction("p1", "us-central1", "fn",
                "{\"description\":\"before\",\"labels\":{\"team\":\"a\"},\"buildConfig\":{\"runtime\":\"java21\"}}", false);
        String name = "projects/p1/locations/us-central1/functions/fn";
        Function created = service.getFunction(name);

        Operation operation = service.updateFunction(name,
                "{\"description\":\"after\",\"labels\":{\"team\":\"b\"},\"state\":\"FAILED\",\"url\":\"https://elsewhere\"}",
                "description,state,url");

        Function updated = service.getFunction(name);
        assertEquals("after", updated.getDescription());
        assertEquals(Map.of("team", "a"), updated.getLabelsMap());
        assertEquals(Function.State.ACTIVE, updated.getState());
        assertEquals(created.getUrl(), updated.getUrl());
        assertEquals(created.getCreateTime(), updated.getCreateTime());
        assertEquals("java21", updated.getBuildConfig().getRuntime());
        assertEquals(created.getServiceConfig().getRevision(), updated.getServiceConfig().getRevision());
        assertEquals(OperationType.UPDATE_FUNCTION,
                operation.getMetadata().unpack(OperationMetadata.class).getOperationType());
        assertEquals(updated, operation.getResponse().unpack(Function.class));
    }

    @Test
    void updateWithNestedMaskKeepsSiblingFieldsAndRollsTheRevision() {
        service.createFunction("p1", "us-central1", "fn",
                "{\"buildConfig\":{\"runtime\":\"java21\",\"entryPoint\":\"Example\"}}", false);
        String name = "projects/p1/locations/us-central1/functions/fn";

        service.updateFunction(name, "{\"buildConfig\":{\"runtime\":\"java25\"}}", "buildConfig.runtime");

        Function updated = service.getFunction(name);
        assertEquals("java25", updated.getBuildConfig().getRuntime());
        assertEquals("Example", updated.getBuildConfig().getEntryPoint());
        assertEquals(name + "/revisions/fn-00002", updated.getServiceConfig().getRevision());
        assertEquals("projects/p1/locations/us-central1/services/fn", updated.getServiceConfig().getService());
    }

    @Test
    void aMaskNamingOnlyOutputOnlyPathsKeepsTheRevision() {
        service.createFunction("p1", "us-central1", "fn", "{\"buildConfig\":{\"runtime\":\"java21\"}}", false);
        String name = "projects/p1/locations/us-central1/functions/fn";
        Function created = service.getFunction(name);

        service.updateFunction(name,
                "{\"serviceConfig\":{\"uri\":\"https://elsewhere\"},"
                        + "\"buildConfig\":{\"sourceProvenance\":{\"resolvedStorageSource\":{\"bucket\":\"b\"}}}}",
                "serviceConfig.uri,buildConfig.sourceProvenance.resolvedStorageSource");

        Function updated = service.getFunction(name);
        assertEquals(created.getServiceConfig(), updated.getServiceConfig());
        assertEquals(created.getBuildConfig(), updated.getBuildConfig());
    }

    @Test
    void updateKeepsEveryOutputOnlyFieldOfTheBuildConfigAndEventTrigger() {
        service.createFunction("p1", "us-central1", "fn",
                "{\"eventTrigger\":{\"eventType\":\"google.cloud.pubsub.topic.v1.messagePublished\"}}", false);
        String name = "projects/p1/locations/us-central1/functions/fn";

        service.updateFunction(name,
                "{\"buildConfig\":{\"runtime\":\"java25\",\"build\":\"b\","
                        + "\"sourceProvenance\":{\"resolvedStorageSource\":{\"bucket\":\"b\"}},"
                        + "\"onDeployUpdatePolicy\":{\"runtimeVersion\":\"v\"}},"
                        + "\"eventTrigger\":{\"trigger\":\"projects/p1/locations/us-central1/triggers/t\","
                        + "\"eventType\":\"google.cloud.storage.object.v1.finalized\"}}",
                "buildConfig,eventTrigger");

        Function updated = service.getFunction(name);
        assertEquals("java25", updated.getBuildConfig().getRuntime());
        assertEquals("", updated.getBuildConfig().getBuild());
        assertFalse(updated.getBuildConfig().hasSourceProvenance());
        assertTrue(updated.getBuildConfig().hasOnDeployUpdatePolicy());
        assertEquals("", updated.getBuildConfig().getOnDeployUpdatePolicy().getRuntimeVersion());
        assertEquals("google.cloud.storage.object.v1.finalized", updated.getEventTrigger().getEventType());
        assertEquals("", updated.getEventTrigger().getTrigger());
    }

    @Test
    void createIgnoresOutputOnlyFieldsInTheBody() {
        service.createFunction("p1", "us-central1", "fn",
                "{\"state\":\"FAILED\",\"satisfiesPzs\":true,\"stateMessages\":[{\"message\":\"m\"}],"
                        + "\"buildConfig\":{\"runtime\":\"java21\",\"build\":\"b\","
                        + "\"sourceProvenance\":{\"resolvedStorageSource\":{\"bucket\":\"b\"}}},"
                        + "\"eventTrigger\":{\"trigger\":\"projects/p1/locations/us-central1/triggers/t\"}}", false);

        Function created = service.getFunction("projects/p1/locations/us-central1/functions/fn");
        assertEquals(Function.State.ACTIVE, created.getState());
        assertFalse(created.getSatisfiesPzs());
        assertEquals(0, created.getStateMessagesCount());
        assertEquals("java21", created.getBuildConfig().getRuntime());
        assertEquals("", created.getBuildConfig().getBuild());
        assertFalse(created.getBuildConfig().hasSourceProvenance());
        assertEquals("", created.getEventTrigger().getTrigger());
    }

    @Test
    void updateWithoutMaskReplacesEverySettableField() {
        service.createFunction("p1", "us-central1", "fn",
                "{\"description\":\"before\",\"labels\":{\"team\":\"a\"}}", false);
        String name = "projects/p1/locations/us-central1/functions/fn";

        service.updateFunction(name, "{\"buildConfig\":{\"runtime\":\"python312\"}}", null);

        Function updated = service.getFunction(name);
        assertEquals("", updated.getDescription());
        assertTrue(updated.getLabelsMap().isEmpty());
        assertEquals("python312", updated.getBuildConfig().getRuntime());
        assertEquals(Environment.GEN_2, updated.getEnvironment());
        assertEquals(name + "/revisions/fn-00002", updated.getServiceConfig().getRevision());
        assertEquals("https://us-central1-p1.cloudfunctions.net/fn", updated.getServiceConfig().getUri());
    }

    @Test
    void updateWithoutMaskKeepsStoredOutputOnlyValuesUnderAnOmittedMessage() {
        service.createFunction("p1", "us-central1", "fn",
                "{\"buildConfig\":{\"runtime\":\"java21\",\"entryPoint\":\"Example\"}}", false);
        String name = "projects/p1/locations/us-central1/functions/fn";
        Function stored = service.getFunction(name);
        functionStore.put(name, ProtoJson.print(stored.toBuilder()
                .setBuildConfig(stored.getBuildConfig().toBuilder().setBuild("projects/p1/builds/b1"))
                .build()));

        service.updateFunction(name, "{}", null);

        Function updated = service.getFunction(name);
        assertEquals("projects/p1/builds/b1", updated.getBuildConfig().getBuild());
        assertEquals("", updated.getBuildConfig().getRuntime());
        assertEquals("", updated.getBuildConfig().getEntryPoint());
        assertFalse(updated.hasEventTrigger());
    }

    @Test
    void updateKeepsTheRequestedRuntimeUpdatePolicyOverTheStoredOne() {
        service.createFunction("p1", "us-central1", "fn",
                "{\"buildConfig\":{\"runtime\":\"java21\"}}", false);
        String name = "projects/p1/locations/us-central1/functions/fn";
        Function stored = service.getFunction(name);
        functionStore.put(name, ProtoJson.print(stored.toBuilder()
                .setBuildConfig(stored.getBuildConfig().toBuilder()
                        .setOnDeployUpdatePolicy(OnDeployUpdatePolicy.newBuilder().setRuntimeVersion("v1")))
                .build()));

        service.updateFunction(name, "{\"buildConfig\":{\"automaticUpdatePolicy\":{}}}", null);

        assertTrue(service.getFunction(name).getBuildConfig().hasAutomaticUpdatePolicy());
    }

    @Test
    void aMaskedFieldMissingFromTheBodyIsCleared() {
        service.createFunction("p1", "us-central1", "fn", "{\"labels\":{\"team\":\"a\"}}", false);
        String name = "projects/p1/locations/us-central1/functions/fn";

        service.updateFunction(name, "{}", "labels");

        assertTrue(service.getFunction(name).getLabelsMap().isEmpty());
    }

    @Test
    void updateRejectsAnUnknownMaskPathAMissingFunctionAndAnEnvironmentChange() {
        service.createFunction("p1", "us-central1", "fn", "{}", false);
        String name = "projects/p1/locations/us-central1/functions/fn";

        GcpException badMask = assertThrows(GcpException.class,
                () -> service.updateFunction(name, "{}", "noSuchField"));
        assertEquals("INVALID_ARGUMENT", badMask.getGcpStatus());

        GcpException missing = assertThrows(GcpException.class,
                () -> service.updateFunction("projects/p1/locations/us-central1/functions/missing", "{}", "description"));
        assertEquals("NOT_FOUND", missing.getGcpStatus());

        GcpException environment = assertThrows(GcpException.class,
                () -> service.updateFunction(name, "{\"environment\":\"GEN_1\"}", "environment"));
        assertEquals("INVALID_ARGUMENT", environment.getGcpStatus());
        assertEquals(Environment.GEN_2, service.getFunction(name).getEnvironment());
    }

    @Test
    void iamCallsRequireTheFunctionAndDeleteDropsItsPolicy() {
        String missing = "projects/p1/locations/us-central1/functions/missing";
        assertEquals("NOT_FOUND", assertThrows(GcpException.class,
                () -> service.getIamPolicy(missing)).getGcpStatus());
        assertEquals("NOT_FOUND", assertThrows(GcpException.class,
                () -> service.setIamPolicy(missing, Policy.getDefaultInstance())).getGcpStatus());
        assertEquals(0, service.testIamPermissions(missing,
                List.of("cloudfunctions.functions.invoke")).getPermissionsCount());
        verifyNoInteractions(iamService);

        service.createFunction("p1", "us-central1", "fn", "{}", false);
        String name = "projects/p1/locations/us-central1/functions/fn";
        service.deleteFunction(name, false);

        verify(iamService).deleteResourceAndPolicy(eq(name), any(Runnable.class));
    }

    @Test
    void generateUploadUrlCreatesSourceBucketAndReturnsStorageSource() {
        GenerateUploadUrlResponse response = service.generateUploadUrl(
                "project-1", "us-central1", "http://localhost:4588");

        assertTrue(response.getUploadUrl().startsWith("http://localhost:4588/gcf-v2-sources-project-1-us-central1/"));
        assertEquals("gcf-v2-sources-project-1-us-central1", response.getStorageSource().getBucket());
        assertTrue(response.getStorageSource().getObject().startsWith("source-"));
        assertEquals(response.getUploadUrl(), response.getStorageSource().getSourceUploadUrl());
        verify(gcsService).createBucket(eq("gcf-v2-sources-project-1-us-central1"),
                eq("project-1"), eq("http://localhost:4588"), anyMap());
    }

    @SuppressWarnings("unchecked")
    @Test
    void generateUploadUrlIgnoresExistingSourceBucket() {
        when(gcsService.createBucket(anyString(), anyString(), anyString(), any(Map.class)))
                .thenThrow(GcpException.alreadyExists("Bucket already exists"));

        assertDoesNotThrow(() -> service.generateUploadUrl("p1", "us-central1", "http://localhost:4588"));
    }

    @Test
    void generateUploadUrlRequiresGcsServiceEnabled() {
        ServiceRegistry registry = mock(ServiceRegistry.class);
        when(registry.isEnabled("gcs")).thenReturn(false);
        CloudFunctionsService gated = new CloudFunctionsService(new InMemoryStorage<>(),
                mock(LongRunningOperationsService.class), gcsService, registry, iamService);

        GcpException ex = assertThrows(GcpException.class,
                () -> gated.generateUploadUrl("p1", "us-central1", "http://localhost:4588"));

        assertEquals("UNAVAILABLE", ex.getGcpStatus());
        verifyNoInteractions(gcsService);
    }
}
