package io.floci.gcp.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageCheckpointTest {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @EnumSource(StorageMode.class)
    void checkpointPersistsPriorMutations(StorageMode mode) {
        StorageBackend<String, String> storage = open(mode, tempDir.resolve("success"), STRING_MAP, 60_000);
        storage.load();
        storage.put("key", "value");

        storage.checkpoint();
        close(storage);

        StorageBackend<String, String> restarted = open(mode, tempDir.resolve("success"), STRING_MAP, 60_000);
        restarted.load();
        assertEquals("value", restarted.get("key").orElseThrow());
        close(restarted);
    }

    @ParameterizedTest
    @EnumSource(StorageMode.class)
    void checkpointReportsFilesystemFailure(StorageMode mode) throws Exception {
        Path invalidParent = tempDir.resolve("not-a-directory-" + mode);
        Files.writeString(invalidParent, "file");
        StorageBackend<String, String> storage = open(mode, invalidParent.resolve("store"), STRING_MAP, 60_000);
        storage.put("key", "value");

        assertThrows(StorageException.class, storage::checkpoint);
    }

    @Test
    void hybridCheckpointDoesNotClearDirtyMutationMadeDuringPersistence() throws Exception {
        Path base = tempDir.resolve("hybrid-race");
        CountDownLatch serializingOldValue = new CountDownLatch(1);
        CountDownLatch continueSerialization = new CountDownLatch(1);
        TypeReference<Map<String, BlockingValue>> type = new TypeReference<>() {};
        HybridStorage<String, BlockingValue> storage = new HybridStorage<>(base.resolve("store.json"), type, 250);
        storage.put("key", new BlockingValue("old", serializingOldValue, continueSerialization));

        try (var executor = Executors.newSingleThreadExecutor()) {
            var checkpoint = executor.submit(storage::checkpoint);
            assertTrue(serializingOldValue.await(5, TimeUnit.SECONDS));
            storage.put("key", new BlockingValue("new", null, null));
            continueSerialization.countDown();
            checkpoint.get(5, TimeUnit.SECONDS);
        }

        Thread.sleep(500);
        HybridStorage<String, BlockingValue> restarted = new HybridStorage<>(base.resolve("store.json"), type, 60_000);
        restarted.load();
        assertEquals("new", restarted.get("key").orElseThrow().getValue());
        restarted.shutdown();
        storage.shutdown();
    }

    private static <V> StorageBackend<String, V> open(StorageMode mode, Path base,
            TypeReference<Map<String, V>> type, long intervalMs) {
        return switch (mode) {
            case PERSISTENT -> new PersistentStorage<>(base.resolve("store.json"), type);
            case HYBRID -> new HybridStorage<>(base.resolve("store.json"), type, intervalMs);
            case WAL -> new WalStorage<>(base.resolve("store.json"), base.resolve("store.wal"), type, intervalMs);
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

    public static final class BlockingValue {
        private String value;
        private transient CountDownLatch entered;
        private transient CountDownLatch release;

        public BlockingValue() {}

        BlockingValue(String value, CountDownLatch entered, CountDownLatch release) {
            this.value = value;
            this.entered = entered;
            this.release = release;
        }

        public String getValue() {
            if (entered != null) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting to continue serialization");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}
