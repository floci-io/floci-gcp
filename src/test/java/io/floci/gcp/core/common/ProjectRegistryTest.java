package io.floci.gcp.core.common;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.core.storage.HybridStorage;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.core.storage.PersistentStorage;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.WalStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectRegistryTest {

    private static final long BACKGROUND_INTERVAL_MS = 60_000;

    @TempDir
    Path tempDir;

    @Test
    void implicitProjectKeepsDeterministicNumberAndCreateTime() {
        ProjectRegistry registry = new ProjectRegistry(new InMemoryStorage<>(), "default-proj", false);

        ProjectRecord project = registry.require("implicit-proj");

        assertEquals(ProjectNumbers.of("implicit-proj"), project.getProjectNumber());
        assertEquals(ProjectRegistry.implicitCreateTime("implicit-proj"), project.getCreateTime());
        assertEquals("implicit-proj", project.getName());
        assertEquals(ProjectRecord.ACTIVE, project.getLifecycleState());
        assertFalse(project.isCreated());
    }

    @Test
    void resolvesRegisteredProjectsAndDefaultProjectByNumber() {
        ProjectRegistry registry = new ProjectRegistry(new InMemoryStorage<>(), "default-proj", false);
        registry.observe("seen-proj");

        assertEquals("seen-proj", registry.observe(ProjectNumbers.of("seen-proj")));
        assertEquals("default-proj", registry.observe(ProjectNumbers.of("default-proj")));
        assertEquals("default-proj", registry.require(ProjectNumbers.of("default-proj")).getProjectId());
    }

    @Test
    void unknownNumberIsNeverRegistered() {
        ProjectRegistry registry = new ProjectRegistry(new InMemoryStorage<>(), "default-proj", false);

        assertEquals("123456789", registry.observe("123456789"));
        assertTrue(registry.find("123456789").isEmpty());
        GcpException denied = assertThrows(GcpException.class, () -> registry.require("123456789"));
        assertEquals("PERMISSION_DENIED", denied.getGcpStatus());
    }

    @Test
    void wildcardsPassThroughUnregistered() {
        ProjectRegistry registry = new ProjectRegistry(new InMemoryStorage<>(), "default-proj", true);

        assertEquals("-", registry.observe("-"));
        assertEquals("_", registry.observe("_"));
    }

    @Test
    void strictModeRejectsUnknownProjectsButKnowsTheDefault() {
        ProjectRegistry registry = new ProjectRegistry(new InMemoryStorage<>(), "default-proj", true);

        GcpException denied = assertThrows(GcpException.class, () -> registry.observe("unknown-proj"));
        assertEquals("PERMISSION_DENIED", denied.getGcpStatus());
        assertEquals(ProjectRegistry.PERMISSION_DENIED_MESSAGE, denied.getMessage());
        assertEquals("default-proj", registry.observe("default-proj"));

        registry.createOrAdopt(newProject("made-proj"));
        assertEquals("made-proj", registry.require("made-proj").getProjectId());
    }

    @Test
    void createRejectsDuplicates() {
        ProjectRegistry registry = new ProjectRegistry(new InMemoryStorage<>(), "default-proj", false);
        registry.createOrAdopt(newProject("dup-proj"));

        GcpException conflict = assertThrows(GcpException.class, () -> registry.createOrAdopt(newProject("dup-proj")));
        assertEquals("ALREADY_EXISTS", conflict.getGcpStatus());
    }

    @Test
    void createAdoptsAnImplicitProjectKeepingItsNumberAndCreateTime() {
        ProjectRegistry registry = new ProjectRegistry(new InMemoryStorage<>(), "default-proj", false);
        registry.observe("seen-proj");
        ProjectRecord implicit = registry.require("seen-proj");
        String createTime = implicit.getCreateTime();

        ProjectRecord adopted = registry.createOrAdopt(newProject("seen-proj"));

        assertTrue(adopted.isCreated());
        assertEquals(ProjectNumbers.of("seen-proj"), adopted.getProjectNumber());
        assertEquals(createTime, adopted.getCreateTime());
        assertThrows(GcpException.class, () -> registry.createOrAdopt(newProject("seen-proj")));
    }

    @Test
    void anUpdateWaitsForAnotherInProgressSoFieldsNeverMix() throws Exception {
        ProjectRegistry registry = new ProjectRegistry(new InMemoryStorage<>(), "default-proj", false);
        registry.createOrAdopt(newProject("mix-proj"));
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> registry.update("mix-proj", p -> {
                p.setName("first");
                firstInside.countDown();
                try {
                    releaseFirst.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                p.setLabels(Map.of("writer", "first"));
            }));
            assertTrue(firstInside.await(5, TimeUnit.SECONDS));
            Future<?> second = pool.submit(() -> registry.update("mix-proj", p -> {
                p.setName("second");
                p.setLabels(Map.of("writer", "second"));
            }));
            assertThrows(TimeoutException.class, () -> second.get(300, TimeUnit.MILLISECONDS));

            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        ProjectRecord project = registry.require("mix-proj");
        assertEquals("second", project.getName());
        assertEquals(Map.of("writer", "second"), project.getLabels());
    }

    @Test
    void concurrentCreatesOfOneProjectLetExactlyOneWin() throws Exception {
        ProjectRegistry registry = new ProjectRegistry(new InMemoryStorage<>(), "default-proj", false);
        registry.observe("race-proj");
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        registry.createOrAdopt(newProject("race-proj"));
                        created.incrementAndGet();
                    } catch (GcpException e) {
                        conflicts.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, created.get());
        assertEquals(threads - 1, conflicts.get());
    }

    @Test
    void projectIdValidationFollowsResourceManagerRules() {
        assertTrue(ProjectRegistry.isValidProjectId("tokyo-rain-123"));
        assertTrue(ProjectRegistry.isValidProjectId("abcdef"));
        assertFalse(ProjectRegistry.isValidProjectId("abcde"));
        assertFalse(ProjectRegistry.isValidProjectId("a".repeat(31)));
        assertFalse(ProjectRegistry.isValidProjectId("1project"));
        assertFalse(ProjectRegistry.isValidProjectId("project-"));
        assertFalse(ProjectRegistry.isValidProjectId("Project-1"));
        assertFalse(ProjectRegistry.isValidProjectId("my_project"));
    }

    @ParameterizedTest
    @EnumSource(StorageMode.class)
    void registrySurvivesRestart(StorageMode mode) {
        StorageBackend<String, ProjectRecord> store = open(mode);
        store.load();
        ProjectRegistry registry = new ProjectRegistry(store, "default-proj", false);
        ProjectRecord created = newProject("persist-proj");
        created.setLabels(Map.of("env", "dev"));
        created.setParentType("folder");
        created.setParentId("123");
        registry.createOrAdopt(created);
        ProjectRecord linked = registry.require("persist-proj");
        linked.setBillingAccountName("billingAccounts/000000-AAAAAA-BBBBBB");
        linked.setLifecycleState(ProjectRecord.DELETE_REQUESTED);
        registry.save(linked);
        registry.observe("implicit-proj");
        store.checkpoint();
        close(store);

        StorageBackend<String, ProjectRecord> reopened = open(mode);
        reopened.load();
        ProjectRegistry restarted = new ProjectRegistry(reopened, "default-proj", true);

        ProjectRecord restored = restarted.require(ProjectNumbers.of("persist-proj"));
        assertEquals("persist-proj", restored.getProjectId());
        assertEquals(created.getCreateTime(), restored.getCreateTime());
        assertEquals(Map.of("env", "dev"), restored.getLabels());
        assertEquals("folder", restored.getParentType());
        assertEquals("123", restored.getParentId());
        assertEquals("billingAccounts/000000-AAAAAA-BBBBBB", restored.getBillingAccountName());
        assertEquals(ProjectRecord.DELETE_REQUESTED, restored.getLifecycleState());
        assertTrue(restored.isCreated());
        assertEquals("implicit-proj", restarted.observe("implicit-proj"));
        close(reopened);
    }

    private static ProjectRecord newProject(String id) {
        ProjectRecord project = new ProjectRecord();
        project.setProjectId(id);
        project.setName("Project " + id.length());
        return project;
    }

    private StorageBackend<String, ProjectRecord> open(StorageMode mode) {
        Path base = tempDir.resolve(mode.name().toLowerCase());
        TypeReference<Map<String, ProjectRecord>> type = new TypeReference<>() {};
        return switch (mode) {
            case PERSISTENT -> new PersistentStorage<>(base.resolve("projects.json"), type);
            case HYBRID -> new HybridStorage<>(base.resolve("projects.json"), type, BACKGROUND_INTERVAL_MS);
            case WAL -> new WalStorage<>(base.resolve("projects.json"), base.resolve("projects.wal"), type,
                    BACKGROUND_INTERVAL_MS);
        };
    }

    private static void close(StorageBackend<?, ?> storage) {
        if (storage instanceof HybridStorage<?, ?> hybrid) {
            hybrid.shutdown();
        } else if (storage instanceof WalStorage<?, ?> wal) {
            wal.shutdown();
        }
    }

    private enum StorageMode {
        PERSISTENT,
        HYBRID,
        WAL
    }
}
