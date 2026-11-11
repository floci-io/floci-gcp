package io.floci.gcp.services.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.LocationCatalog;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.kafka.SchemaRegistryDataPlane.Scope;
import io.floci.gcp.services.kafka.model.StoredSchemaRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

class SchemaRegistryServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String P = "proj";
    private static final String L = "us-central1";
    private static final SchemaRegistryService.Target REG = new SchemaRegistryService.Target(P, L, "reg", null);

    private FakeDataPlane plane;
    private InMemoryStorage<String, StoredSchemaRegistry> store;
    private SchemaRegistryService service;

    @BeforeEach
    void setUp() {
        plane = new FakeDataPlane();
        store = new InMemoryStorage<>();
        service = new SchemaRegistryService(store, mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                LocationCatalog.lenient(), plane);
    }

    @Test
    void createStoresTheRegistryAndStartsItsDataPlane() {
        Map<String, Object> created = service.createRegistry(P, L, "reg_1");

        assertEquals("projects/proj/locations/us-central1/schemaRegistries/reg_1", created.get("name"));
        assertEquals(1, plane.started.get());
        assertEquals(List.of(created), service.listRegistries(P, L));
    }

    @Test
    void createRejectsIdsTheProtoDoesNotAllow() {
        for (String id : List.of("1abc", "has-hyphen", "a".repeat(64), "")) {
            GcpException e = assertThrows(GcpException.class, () -> service.createRegistry(P, L, id));
            assertEquals(400, e.getHttpStatus(), id);
        }
        assertEquals(0, plane.started.get());
    }

    @Test
    void createRollsBackWhenTheDataPlaneCannotStart() {
        plane.failStart = true;

        assertThrows(GcpException.class, () -> service.createRegistry(P, L, "reg"));

        assertTrue(service.listRegistries(P, L).isEmpty());
        assertEquals(1, plane.deleted.get());
    }

    @Test
    void createAnswersWithTheDefaultContextWithoutAskingTheBackend() {
        plane.failContexts = true;

        Map<String, Object> created = service.createRegistry(P, L, "reg");

        assertEquals(List.of("projects/proj/locations/us-central1/schemaRegistries/reg/contexts/."),
                created.get("contexts"));
        plane.failContexts = false;
        assertEquals(created, service.getRegistry(P, L, "reg"));
    }

    @Test
    void aSecondCreateOfTheSameIdWaitsForTheFirstAndFailsAlreadyExists() throws Exception {
        plane.blockStart = new Gate();
        FutureTask<Map<String, Object>> first = new FutureTask<>(() -> service.createRegistry(P, L, "reg"));
        new Thread(first).start();
        plane.blockStart.awaitEntered();

        FutureTask<Map<String, Object>> second = new FutureTask<>(() -> service.createRegistry(P, L, "reg"));
        Thread secondThread = new Thread(second);
        secondThread.start();
        awaitParked(secondThread);
        plane.blockStart.release();

        first.get(10, TimeUnit.SECONDS);
        Exception e = assertThrows(Exception.class, () -> second.get(10, TimeUnit.SECONDS));
        assertEquals(409, ((GcpException) e.getCause()).getHttpStatus());
        assertEquals(1, plane.started.get());
    }

    @Test
    void aDeleteWaitsForAnInFlightCallAndLaterCallsSeeNotFound() throws Exception {
        service.createRegistry(P, L, "reg");
        plane.blockList = new Gate();
        FutureTask<JsonNode> call = new FutureTask<>(() -> service.listSubjects(REG, null, false));
        new Thread(call).start();
        plane.blockList.awaitEntered();

        FutureTask<Void> delete = new FutureTask<>(() -> service.deleteRegistry(P, L, "reg"), null);
        Thread deleteThread = new Thread(delete);
        deleteThread.start();
        awaitParked(deleteThread);
        assertEquals(0, plane.deleted.get(), "the delete must not reach the data plane during the call");
        plane.blockList.release();

        call.get(10, TimeUnit.SECONDS);
        delete.get(10, TimeUnit.SECONDS);
        assertEquals(1, plane.deleted.get());
        plane.blockList = null;
        GcpException e = assertThrows(GcpException.class, () -> service.listSubjects(REG, null, false));
        assertEquals(404, e.getHttpStatus());
        assertEquals(1, plane.listCalls.get(), "a call after the delete must not reach the data plane");
    }

    @Test
    void aCallThatLosesTheRaceToADeleteNeverReachesTheDataPlane() throws Exception {
        service.createRegistry(P, L, "reg");
        plane.blockDelete = new Gate();
        FutureTask<Void> delete = new FutureTask<>(() -> service.deleteRegistry(P, L, "reg"), null);
        new Thread(delete).start();
        plane.blockDelete.awaitEntered();

        FutureTask<JsonNode> call = new FutureTask<>(() -> service.listSubjects(REG, null, false));
        Thread callThread = new Thread(call);
        callThread.start();
        awaitParked(callThread);
        plane.blockDelete.release();

        delete.get(10, TimeUnit.SECONDS);
        Exception e = assertThrows(Exception.class, () -> call.get(10, TimeUnit.SECONDS));
        assertEquals(404, ((GcpException) e.getCause()).getHttpStatus());
        assertEquals(0, plane.listCalls.get());
        assertEquals(1, plane.started.get(), "the deleted registry's data plane must not be started again");
    }

    @Test
    void confluentErrorsBecomeGoogleErrorsWithTheConfluentMessage() {
        service.createRegistry(P, L, "reg");
        plane.listError = new SchemaRegistryException(409, 409, "incompatible");
        GcpException conflict = assertThrows(GcpException.class,
                () -> service.listSubjects(REG, null, false));
        assertEquals(400, conflict.getHttpStatus());
        assertEquals("FAILED_PRECONDITION", conflict.getGcpStatus());
        assertEquals("incompatible", conflict.getMessage());

        plane.listError = new SchemaRegistryException(404, 40401, "Subject 's' not found.");
        assertEquals(404, assertThrows(GcpException.class,
                () -> service.listSubjects(REG, null, false)).getHttpStatus());

        plane.listError = new SchemaRegistryException(422, 42201, "Invalid schema");
        assertEquals("INVALID_ARGUMENT", assertThrows(GcpException.class,
                () -> service.listSubjects(REG, null, false)).getGcpStatus());
    }

    @Test
    void checkingAgainstAllVersionsUsesEveryVersionOnlyForTransitiveLevels() {
        service.createRegistry(P, L, "reg");
        ObjectNode body = MAPPER.createObjectNode().put("schema", "\"string\"");

        plane.compatibility = "BACKWARD";
        service.checkCompatibility(REG, "s", null, body, null);
        assertEquals(List.of("latest"), plane.checkedVersions);

        plane.checkedVersions.clear();
        plane.compatibility = "FULL_TRANSITIVE";
        plane.incompatibleVersion = "2";
        JsonNode result = service.checkCompatibility(REG, "s", null, body, null);
        assertEquals(List.of("1", "2", "3"), plane.checkedVersions);
        assertFalse(result.get("is_compatible").asBoolean());
    }

    @Test
    void theDefaultContextListsItsUnqualifiedSubjects() {
        service.createRegistry(P, L, "reg");
        plane.subjects = List.of("users", ":.team:orders");
        String name = "projects/proj/locations/us-central1/schemaRegistries/reg/contexts/.";

        Map<String, Object> context = service.getContext(new SchemaRegistryService.Target(P, L, "reg", "."));

        assertEquals(name, context.get("name"));
        assertEquals(List.of(name + "/subjects/users"), context.get("subjects"));
    }

    @Test
    void updateConfigRejectsNormalizeRatherThanDroppingIt() {
        service.createRegistry(P, L, "reg");
        ObjectNode body = MAPPER.createObjectNode().put("compatibility", "FULL").put("normalize", true);

        GcpException e = assertThrows(GcpException.class, () -> service.updateConfig(REG, null, body));
        assertEquals(400, e.getHttpStatus());
    }

    /** Waits, without sleeping, until {@code thread} is parked on a lock or monitor. */
    private static void awaitParked(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.BLOCKED) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("thread did not block, state " + thread.getState());
    }

    private static final class Gate {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        void pass() {
            entered.countDown();
            try {
                assertTrue(released.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        void awaitEntered() throws InterruptedException {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
        }

        void release() {
            released.countDown();
        }
    }

    private static final class FakeDataPlane implements SchemaRegistryDataPlane {
        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger deleted = new AtomicInteger();
        final AtomicInteger listCalls = new AtomicInteger();
        volatile boolean failStart;
        volatile boolean failContexts;
        volatile Gate blockStart;
        volatile Gate blockList;
        volatile Gate blockDelete;
        volatile SchemaRegistryException listError;
        volatile String compatibility = "BACKWARD";
        volatile String incompatibleVersion;
        final List<String> checkedVersions = new CopyOnWriteArrayList<>();
        volatile List<String> subjects = List.of();

        @Override
        public void ensureRunning(StoredSchemaRegistry registry) {
            started.incrementAndGet();
            if (blockStart != null) {
                blockStart.pass();
            }
            if (failStart) {
                throw GcpException.unavailable("no docker");
            }
        }

        @Override
        public void stop(StoredSchemaRegistry registry) {
        }

        @Override
        public void delete(StoredSchemaRegistry registry) {
            if (blockDelete != null) {
                blockDelete.pass();
            }
            deleted.incrementAndGet();
        }

        @Override
        public JsonNode listSubjects(Scope registry, String subjectPrefix, boolean deleted) {
            listCalls.incrementAndGet();
            if (blockList != null) {
                blockList.pass();
            }
            if (listError != null) {
                throw listError;
            }
            ArrayNode result = MAPPER.createArrayNode();
            subjects.forEach(result::add);
            return result;
        }

        @Override
        public JsonNode listVersions(Scope registry, String subject, boolean deleted) {
            return MAPPER.createArrayNode().add(1).add(2).add(3);
        }

        @Override
        public JsonNode checkCompatibility(Scope registry, String subject, String version,
                ObjectNode schema, boolean verbose) {
            checkedVersions.add(version);
            return MAPPER.createObjectNode().put("is_compatible", !version.equals(incompatibleVersion));
        }

        @Override
        public JsonNode getConfig(Scope registry, String subject, boolean defaultToGlobal) {
            return MAPPER.createObjectNode().put("compatibilityLevel", compatibility);
        }

        @Override
        public JsonNode listContexts(StoredSchemaRegistry registry) {
            if (failContexts) {
                throw GcpException.unavailable("registry went away");
            }
            return MAPPER.createArrayNode().add(".");
        }

        @Override
        public String getRawSchemaVersion(Scope r, String s, String v, boolean d) { throw new UnsupportedOperationException(); }
        @Override
        public JsonNode listReferencedSchemas(Scope r, String s, String v) { throw new UnsupportedOperationException(); }
        @Override
        public String getRawSchema(Scope r, int id, String s) { throw new UnsupportedOperationException(); }
        @Override
        public JsonNode listSchemaVersions(Scope r, int id, String s, boolean d) { throw new UnsupportedOperationException(); }
        @Override
        public JsonNode listSubjectsBySchemaId(Scope r, int id, String s, boolean d) { throw new UnsupportedOperationException(); }
        @Override
        public JsonNode listSchemaTypes(Scope r) { throw new UnsupportedOperationException(); }
        @Override
        public JsonNode deleteSubject(Scope r, String s, boolean p) { throw new UnsupportedOperationException(); }
        @Override
        public JsonNode lookupVersion(Scope r, String s, ObjectNode b, boolean n, boolean d) { throw new UnsupportedOperationException(); }
        @Override
        public JsonNode getVersion(Scope r, String s, String v, boolean d) { throw new UnsupportedOperationException(); }
        @Override
        public JsonNode createVersion(Scope r, String s, ObjectNode b, boolean n) { throw new UnsupportedOperationException(); }
        @Override
        public JsonNode deleteVersion(Scope r, String s, String v, boolean p) { throw new UnsupportedOperationException(); }
        @Override
        public JsonNode getSchema(Scope r, int id, String s) { throw new UnsupportedOperationException(); }
        @Override
        public JsonNode updateConfig(Scope r, String s, String c) { throw new UnsupportedOperationException(); }
        @Override
        public JsonNode deleteConfig(Scope r, String s) { throw new UnsupportedOperationException(); }
        @Override
        public JsonNode getMode(Scope r, String s) { throw new UnsupportedOperationException(); }
        @Override
        public JsonNode updateMode(Scope r, String s, String m) { throw new UnsupportedOperationException(); }
        @Override
        public JsonNode deleteMode(Scope r, String s) { throw new UnsupportedOperationException(); }
    }
}
