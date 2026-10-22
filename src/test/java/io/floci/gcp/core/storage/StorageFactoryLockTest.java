package io.floci.gcp.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.config.EmulatorConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StorageFactoryLockTest {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    @TempDir
    Path tempDir;

    @Test
    void secondFactoryOnTheSameDirectoryFailsWithTheDirectoryInTheMessage() {
        StorageFactory first = factory("persistent", tempDir);
        first.createGlobal("gcs", "gcs-buckets.json", STRING_MAP);
        try {
            StorageFactory second = factory("persistent", tempDir);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> second.createGlobal("gcs", "gcs-buckets.json", STRING_MAP));
            assertTrue(e.getMessage().contains(tempDir.toAbsolutePath().toString()), e.getMessage());
            assertTrue(e.getMessage().contains("already in use"), e.getMessage());
        } finally {
            first.shutdownAll();
        }
    }

    @Test
    void directoryIsReusableAfterShutdown() {
        StorageFactory first = factory("hybrid", tempDir);
        first.createGlobal("gcs", "gcs-buckets.json", STRING_MAP).put("bucket", "a");
        first.shutdownAll();

        StorageFactory restarted = factory("hybrid", tempDir);
        try {
            assertEquals("a", restarted.createGlobal("gcs", "gcs-buckets.json", STRING_MAP)
                    .get("bucket").orElseThrow());
        } finally {
            restarted.shutdownAll();
        }
    }

    @Test
    void preDestroyStopsTheWorkersBeforeReleasingTheDirectory() {
        StorageFactory first = factory("wal", tempDir, 200L);
        first.createGlobal("gcs", "gcs-buckets.json", STRING_MAP).put("first", "1");
        StorageFactory hybrid = factory("hybrid", tempDir.resolve("hybrid"));
        hybrid.createGlobal("gcs", "gcs-buckets.json", STRING_MAP);
        first.releaseDataDirectory();
        hybrid.releaseDataDirectory();
        assertTrue(first.workersStopped());
        assertTrue(hybrid.workersStopped());

        StorageFactory second = factory("wal", tempDir, 200L);
        second.createGlobal("gcs", "gcs-buckets.json", STRING_MAP).put("second", "2");
        second.shutdownAll();

        StorageFactory third = factory("wal", tempDir, 200L);
        try {
            StorageBackend<String, String> store = third.createGlobal("gcs", "gcs-buckets.json", STRING_MAP);
            assertEquals("1", store.get("first").orElseThrow());
            assertEquals("2", store.get("second").orElseThrow());
        } finally {
            third.shutdownAll();
        }
    }

    @Test
    void rejectedSameJvmStartupKeepsTheDirectoryLockedForOtherProcesses() throws Exception {
        StorageFactory first = factory("persistent", tempDir);
        first.createGlobal("gcs", "gcs-buckets.json", STRING_MAP);
        try {
            StorageFactory second = factory("persistent", tempDir);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> second.createGlobal("gcs", "gcs-buckets.json", STRING_MAP));
            assertTrue(e.getMessage().contains("in this JVM"), e.getMessage());

            assertEquals("REFUSED", runChild(TryLock.class));
        } finally {
            first.shutdownAll();
        }
        assertEquals("ACQUIRED", runChild(TryLock.class));
    }

    @Test
    void memoryModeNeverTakesTheLock() {
        StorageFactory first = factory("memory", tempDir);
        StorageFactory second = factory("memory", tempDir);
        first.createGlobal("gcs", "gcs-buckets.json", STRING_MAP);
        second.createGlobal("gcs", "gcs-buckets.json", STRING_MAP);

        assertFalse(Files.exists(tempDir.resolve(DataDirectoryLock.LOCK_FILE)));
    }

    @Test
    void separateDirectoriesRunSideBySide() {
        StorageFactory a = factory("persistent", tempDir.resolve("a"));
        StorageFactory b = factory("persistent", tempDir.resolve("b"));
        try {
            a.createGlobal("gcs", "gcs-buckets.json", STRING_MAP);
            b.createGlobal("gcs", "gcs-buckets.json", STRING_MAP);
        } finally {
            a.shutdownAll();
            b.shutdownAll();
        }
    }

    @Test
    void lockHeldByAnotherProcessBlocksStartup() throws Exception {
        String java = ProcessHandle.current().info().command().orElse("java");
        Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                HoldLock.class.getName(), tempDir.toString())
                .redirectErrorStream(true)
                .start();
        try {
            BufferedReader out = new BufferedReader(
                    new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
            CompletableFuture<String> ready = CompletableFuture.supplyAsync(() -> {
                try {
                    return out.readLine();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            assertEquals("LOCKED", ready.get(30, TimeUnit.SECONDS));

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> DataDirectoryLock.acquire(tempDir));
            assertTrue(e.getMessage().contains("another floci-gcp process"), e.getMessage());
        } finally {
            child.getOutputStream().close();
            if (!child.waitFor(10, TimeUnit.SECONDS)) {
                child.destroyForcibly();
            }
        }

        DataDirectoryLock.acquire(tempDir).close();
    }

    private String runChild(Class<?> main) throws Exception {
        String java = ProcessHandle.current().info().command().orElse("java");
        Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                main.getName(), tempDir.toString())
                .redirectErrorStream(true)
                .start();
        try {
            BufferedReader out = new BufferedReader(
                    new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
            CompletableFuture<String> line = CompletableFuture.supplyAsync(() -> {
                try {
                    return out.readLine();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            return line.get(30, TimeUnit.SECONDS);
        } finally {
            if (!child.waitFor(10, TimeUnit.SECONDS)) {
                child.destroyForcibly();
            }
        }
    }

    private static StorageFactory factory(String mode, Path dir) {
        return factory(mode, dir, 60_000L);
    }

    private static StorageFactory factory(String mode, Path dir, long compactionIntervalMs) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);
        EmulatorConfig.WalConfig wal = mock(EmulatorConfig.WalConfig.class);
        when(config.storage()).thenReturn(storage);
        when(storage.mode()).thenReturn(mode);
        when(storage.persistentPath()).thenReturn(dir.toString());
        when(storage.wal()).thenReturn(wal);
        when(wal.compactionIntervalMs()).thenReturn(compactionIntervalMs);
        return new StorageFactory(config);
    }

    /** Child-process entry point: reports whether it could take the lock, then exits. */
    public static final class TryLock {
        public static void main(String[] args) {
            try (DataDirectoryLock ignored = DataDirectoryLock.acquire(Path.of(args[0]))) {
                System.out.println("ACQUIRED");
            } catch (IllegalStateException e) {
                System.out.println("REFUSED");
            }
        }
    }

    /** Child-process entry point: holds the lock until its stdin closes. */
    public static final class HoldLock {
        public static void main(String[] args) throws IOException {
            try (DataDirectoryLock ignored = DataDirectoryLock.acquire(Path.of(args[0]))) {
                System.out.println("LOCKED");
                System.out.flush();
                while (System.in.read() != -1) {
                    // Wait for the parent to close stdin.
                }
            }
        }
    }
}
