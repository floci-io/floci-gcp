package io.floci.gcp.services.operations;

import com.google.cloud.functions.v2.OperationMetadata;
import com.google.cloud.run.v2.Service;
import com.google.longrunning.ListOperationsResponse;
import com.google.longrunning.Operation;
import com.google.protobuf.Duration;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.Code;
import com.google.rpc.Status;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LongRunningOperationsServiceTest {

    private LongRunningOperationsService service;

    @BeforeEach
    void setUp() {
        service = new LongRunningOperationsService(new InMemoryStorage<>());
    }

    @Test
    void donePersistsPackedResponseAndMetadata() throws InvalidProtocolBufferException {
        Service response = Service.newBuilder()
                .setName("projects/p1/locations/us-central1/services/svc")
                .build();
        OperationMetadata metadata = OperationMetadata.newBuilder()
                .setTarget(response.getName())
                .setVerb("create")
                .build();

        Operation created = service.done("projects/p1/locations/us-central1", response, metadata);
        Operation fetched = service.get(created.getName());

        assertTrue(fetched.getDone());
        assertEquals(response.getName(), fetched.getResponse().unpack(Service.class).getName());
        assertEquals("create", fetched.getMetadata().unpack(OperationMetadata.class).getVerb());
    }

    @Test
    void listOperationsPaginatesWithinParent() {
        service.done("projects/p1/locations/us-central1", service("a"), service("a"));
        service.done("projects/p1/locations/us-central1", service("b"), service("b"));
        service.done("projects/p2/locations/us-central1", service("c"), service("c"));

        ListOperationsResponse firstPage = service.list("projects/p1/locations/us-central1", 1, null);
        assertEquals(1, firstPage.getOperationsCount());
        assertFalse(firstPage.getNextPageToken().isBlank());

        ListOperationsResponse secondPage = service.list(
                "projects/p1/locations/us-central1", 1, firstPage.getNextPageToken());
        assertEquals(1, secondPage.getOperationsCount());
        assertEquals("", secondPage.getNextPageToken());
    }

    @Test
    void deleteOperationRemovesStoredRecord() {
        Operation operation = service.done("projects/p1/locations/us-central1", service("a"), service("a"));

        service.delete(operation.getName());

        GcpException ex = assertThrows(GcpException.class, () -> service.get(operation.getName()));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    @Test
    void doneTransientDoesNotPersistOperation() {
        Operation operation = service.doneTransient("projects/p1/locations/us-central1", service("a"), service("a"));

        assertTrue(operation.getDone());
        GcpException ex = assertThrows(GcpException.class, () -> service.get(operation.getName()));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
        assertEquals(0, service.list("projects/p1/locations/us-central1", 10, null).getOperationsCount());
    }

    @Test
    void pendingCompleteFailAndWaitUpdateStoredOperation() throws InvalidProtocolBufferException {
        Operation pending = service.pending("projects/p1/locations/us-central1", service("a"));

        assertFalse(pending.getDone());
        assertFalse(service.wait(pending.getName(), Duration.newBuilder().setNanos(1).build()).getDone());

        Operation completed = service.complete(pending.getName(), service("a"), service("a"));
        assertTrue(completed.getDone());
        assertEquals(service("a").getName(), service.get(pending.getName()).getResponse().unpack(Service.class).getName());

        Operation failed = service.pending("projects/p1/locations/us-central1", service("b"));
        service.fail(failed.getName(), Status.newBuilder()
                .setCode(Code.INTERNAL_VALUE)
                .setMessage("boom")
                .build(), service("b"));

        Operation fetched = service.get(failed.getName());
        assertTrue(fetched.getDone());
        assertEquals("boom", fetched.getError().getMessage());
        assertEquals(Code.INTERNAL_VALUE, fetched.getError().getCode());
    }

    private static Service service(String id) {
        return Service.newBuilder()
                .setName("projects/p1/locations/us-central1/services/" + id)
                .build();
    }
}
