package io.floci.gcp.services.serviceusage;

import com.google.api.serviceusage.v1.BatchGetServicesResponse;
import com.google.api.serviceusage.v1.DisableServiceResponse;
import com.google.api.serviceusage.v1.EnableServiceResponse;
import com.google.api.serviceusage.v1.ListServicesResponse;
import com.google.api.serviceusage.v1.OperationMetadata;
import com.google.api.serviceusage.v1.Service;
import com.google.api.serviceusage.v1.State;
import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.operations.LongRunningOperationsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServiceUsageServiceTest {

    private ServiceUsageService service;

    @BeforeEach
    void setUp() {
        LongRunningOperationsService operations = mock(LongRunningOperationsService.class);
        when(operations.done(anyString(), any(Message.class), any(Message.class)))
                .thenAnswer(invocation -> Operation.newBuilder()
                        .setName("operations/test-op")
                        .setDone(true)
                        .setResponse(Any.pack(invocation.getArgument(1, Message.class)))
                        .setMetadata(Any.pack(invocation.getArgument(2, Message.class)))
                        .build());
        EmulatorConfig config = mock(EmulatorConfig.class);
        service = new ServiceUsageService(new InMemoryStorage<>(), operations, config);
    }

    @Test
    void enableReturnsDoneOperationWithEnabledService() throws InvalidProtocolBufferException {
        Operation operation = service.enable("p1", "run.googleapis.com");

        assertTrue(operation.getDone());
        Service enabled = operation.getResponse().unpack(EnableServiceResponse.class).getService();
        assertEquals("projects/p1/services/run.googleapis.com", enabled.getName());
        assertEquals("projects/p1", enabled.getParent());
        assertEquals("run.googleapis.com", enabled.getConfig().getName());
        assertEquals(State.ENABLED, enabled.getState());
        assertEquals(List.of("projects/p1/services/run.googleapis.com"),
                operation.getMetadata().unpack(OperationMetadata.class).getResourceNamesList());
    }

    @Test
    void getDefaultsToDisabledForUntrackedService() {
        Service untracked = service.get("p1", "compute.googleapis.com");

        assertEquals(State.DISABLED, untracked.getState());
        assertEquals("projects/p1/services/compute.googleapis.com", untracked.getName());
    }

    @Test
    void disableFlipsStateAndGetReflectsIt() throws InvalidProtocolBufferException {
        service.enable("p1", "run.googleapis.com");
        Operation operation = service.disable("p1", "run.googleapis.com");

        Service disabled = operation.getResponse().unpack(DisableServiceResponse.class).getService();
        assertEquals(State.DISABLED, disabled.getState());
        assertEquals(State.DISABLED, service.get("p1", "run.googleapis.com").getState());
    }

    @Test
    void disableNotEnabledServiceFailsPrecondition() {
        GcpException e = assertThrows(GcpException.class,
                () -> service.disable("p1", "run.googleapis.com"));
        assertEquals("FAILED_PRECONDITION", e.getGcpStatus());
    }

    @Test
    void listHonorsStateFilterAndIsProjectScoped() {
        service.enable("p1", "run.googleapis.com");
        service.enable("p1", "pubsub.googleapis.com");
        service.enable("p2", "run.googleapis.com");
        service.enable("p1", "compute.googleapis.com");
        service.disable("p1", "compute.googleapis.com");

        ListServicesResponse all = service.list("p1", 0, null, null);
        assertEquals(3, all.getServicesCount());

        ListServicesResponse enabled = service.list("p1", 0, null, "state:ENABLED");
        assertEquals(2, enabled.getServicesCount());
        assertTrue(enabled.getServicesList().stream().allMatch(s -> s.getState() == State.ENABLED));

        ListServicesResponse disabled = service.list("p1", 0, null, "state:DISABLED");
        assertEquals(1, disabled.getServicesCount());
        assertEquals("projects/p1/services/compute.googleapis.com", disabled.getServices(0).getName());
    }

    @Test
    void listRejectsUnknownFilterAndOversizedPage() {
        assertThrows(GcpException.class, () -> service.list("p1", 0, null, "state:BROKEN"));
        assertThrows(GcpException.class, () -> service.list("p1", 201, null, null));
    }

    @Test
    void listPaginates() {
        IntStream.range(0, 3).forEach(i -> service.enable("p1", "svc" + i + ".googleapis.com"));

        ListServicesResponse firstPage = service.list("p1", 2, null, null);
        assertEquals(2, firstPage.getServicesCount());

        ListServicesResponse secondPage = service.list("p1", 2, firstPage.getNextPageToken(), null);
        assertEquals(1, secondPage.getServicesCount());
        assertEquals("", secondPage.getNextPageToken());
    }

    @Test
    void batchEnableEnablesAllServices() throws InvalidProtocolBufferException {
        Operation operation = service.batchEnable("p1",
                "{\"serviceIds\": [\"run.googleapis.com\", \"pubsub.googleapis.com\"]}");

        assertTrue(operation.getDone());
        assertEquals(State.ENABLED, service.get("p1", "run.googleapis.com").getState());
        assertEquals(State.ENABLED, service.get("p1", "pubsub.googleapis.com").getState());
    }

    @Test
    void batchEnableRejectsEmptyAndOversizedRequests() {
        assertThrows(GcpException.class, () -> service.batchEnable("p1", "{}"));

        String tooMany = "{\"serviceIds\": [" + IntStream.range(0, 21)
                .mapToObj(i -> "\"svc" + i + ".googleapis.com\"")
                .reduce((a, b) -> a + "," + b).orElseThrow() + "]}";
        assertThrows(GcpException.class, () -> service.batchEnable("p1", tooMany));
    }

    @Test
    void batchGetReturnsStatesAndValidatesParent() {
        service.enable("p1", "run.googleapis.com");

        BatchGetServicesResponse response = service.batchGet("p1", List.of(
                "projects/p1/services/run.googleapis.com",
                "projects/p1/services/compute.googleapis.com"));
        assertEquals(State.ENABLED, response.getServices(0).getState());
        assertEquals(State.DISABLED, response.getServices(1).getState());

        assertThrows(GcpException.class, () -> service.batchGet("p1",
                List.of("projects/other/services/run.googleapis.com")));
        assertThrows(GcpException.class, () -> service.batchGet("p1", List.of()));
    }
}
