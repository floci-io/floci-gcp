package io.floci.gcp.core.storage;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class PersistentStorageTest {
    private static final TypeReference<Map<String, List<String>>> TYPE = new TypeReference<>() {};
    @TempDir Path dir;

    static class FailingDisk extends PersistentStorageIO {
        boolean failWrite;
        boolean failRead;
        @Override void write(Path path, byte[] bytes) throws IOException {
            if (failWrite) throw new IOException("injected disk write failure");
            super.write(path, bytes);
        }
        @Override byte[] read(Path path) throws IOException {
            if (failRead) throw new IOException("injected disk read failure");
            return super.read(path);
        }
    }

    @Test
    void failedMutationsDoNotPublishOrOverwriteDurableState() throws Exception {
        for (String operation : List.of("put", "delete", "clear", "checkpoint")) {
            Path file = dir.resolve(operation + ".json");
            FailingDisk disk = new FailingDisk();
            var storage = new PersistentStorage<>(file, TYPE, disk);
            storage.put("key", List.of("durable"));
            byte[] before = Files.readAllBytes(file);
            disk.failWrite = true;
            assertThrows(StorageException.class, () -> {
                switch (operation) {
                    case "put" -> storage.put("key", List.of("uncommitted"));
                    case "delete" -> storage.delete("key");
                    case "clear" -> storage.clear();
                    case "checkpoint" -> storage.checkpoint();
                }
            }, operation);
            assertThrows(StorageException.class, () -> storage.get("key"), operation);
            assertArrayEquals(before, Files.readAllBytes(file), operation);
            disk.failWrite = false;
            storage.load();
            storage.put("next", List.of("retry"));
            var reloaded = new PersistentStorage<>(file, TYPE);
            reloaded.load();
            assertEquals(List.of("durable"), reloaded.get("key").orElseThrow());
            assertEquals(List.of("retry"), reloaded.get("next").orElseThrow());
        }
    }

    @Test
    void realFilesystemFailureDoesNotAcknowledgeNewValue() throws Exception {
        Path blocker = dir.resolve("not-a-directory");
        Files.writeString(blocker, "block");
        var storage = new PersistentStorage<>(blocker.resolve("data.json"), TYPE);
        assertThrows(StorageException.class, () -> storage.put("key", List.of("value")));
        assertThrows(StorageException.class, () -> storage.get("key"));
        assertEquals("block", Files.readString(blocker));
    }

    @Test
    void corruptAndUnreadableLoadPreserveOnlyCopyAndBlockUseUntilReload() throws Exception {
        Path file = dir.resolve("data.json");
        FailingDisk disk = new FailingDisk();
        var storage = new PersistentStorage<>(file, TYPE, disk);
        storage.put("key", List.of("durable"));
        byte[] valid = Files.readAllBytes(file);
        disk.failRead = true;
        assertThrows(StorageException.class, storage::load);
        assertArrayEquals(valid, Files.readAllBytes(file));
        assertThrows(StorageException.class, storage::checkpoint);
        assertThrows(StorageException.class, storage::clear);
        disk.failRead = false;
        storage.load();
        assertEquals(List.of("durable"), storage.get("key").orElseThrow());
        for (String invalid : List.of("{broken", "null", "{\"key\":null}")) {
            Files.writeString(file, invalid);
            assertThrows(StorageException.class, storage::load);
            assertEquals(invalid, Files.readString(file));
            assertFalse(Files.exists(file.resolveSibling("data.json.corrupt")));
            assertThrows(StorageException.class, () -> storage.put("key", List.of("replacement")));
            assertThrows(StorageException.class, () -> storage.get("key"));
        }
        Files.write(file, valid);
        storage.load();
        assertEquals(List.of("durable"), storage.get("key").orElseThrow());
    }

    @Test
    void mutableValuesRemainLiveAndCheckpointPersistsThem() {
        FailingDisk disk = new FailingDisk();
        var storage = new PersistentStorage<>(dir.resolve("data.json"), TYPE, disk);
        var input = new ArrayList<>(List.of("durable"));
        storage.put("key", input);
        input.add("input mutation");
        var read = storage.get("key").orElseThrow();
        read.add("read mutation");
        storage.scan(k -> true).getFirst().add("scan mutation");
        storage.checkpoint();

        var reloaded = new PersistentStorage<>(dir.resolve("data.json"), TYPE);
        reloaded.load();
        assertEquals(List.of("durable", "input mutation", "read mutation", "scan mutation"),
                reloaded.get("key").orElseThrow());
    }

    @Test
    void checkpointPreservesRuntimeOnlyFieldsInMemory() {
        TypeReference<Map<String, RuntimeValue>> type = new TypeReference<>() {};
        var storage = new PersistentStorage<>(dir.resolve("runtime.json"), type);
        RuntimeValue value = new RuntimeValue("durable", "broker-container");

        storage.put("key", value);
        storage.checkpoint();

        assertEquals("broker-container", storage.get("key").orElseThrow().getRuntimeId());
        var reloaded = new PersistentStorage<>(dir.resolve("runtime.json"), type);
        reloaded.load();
        assertEquals("durable", reloaded.get("key").orElseThrow().getValue());
        assertNull(reloaded.get("key").orElseThrow().getRuntimeId());
    }

    @Test
    void failedCheckpointOfLiveMutationBlocksUseAndPreservesDurableState() {
        FailingDisk disk = new FailingDisk();
        var storage = new PersistentStorage<>(dir.resolve("checkpoint.json"), TYPE, disk);
        storage.put("key", new ArrayList<>(List.of("durable")));
        byte[] before = assertDoesNotThrow(() -> Files.readAllBytes(dir.resolve("checkpoint.json")));
        storage.get("key").orElseThrow().add("uncommitted");
        disk.failWrite = true;
        assertThrows(StorageException.class, storage::checkpoint);
        assertThrows(StorageException.class, () -> storage.get("key"));
        assertArrayEquals(before, assertDoesNotThrow(() -> Files.readAllBytes(dir.resolve("checkpoint.json"))));
        disk.failWrite = false;
        storage.load();
        assertEquals(List.of("durable"), storage.get("key").orElseThrow());
    }

    @Test
    void uncertainWriteRequiresReloadBeforeAnyFurtherUse() {
        Path file = dir.resolve("uncertain.json");
        var disk = new PersistentStorageIO() {
            boolean fail;
            @Override void write(Path path, byte[] bytes) throws IOException {
                super.write(path, bytes);
                if (fail) throw new IOException("injected failure after atomic replacement");
            }
        };
        var storage = new PersistentStorage<>(file, TYPE, disk);
        storage.put("key", List.of("old"));
        disk.fail = true;
        assertThrows(StorageException.class, () -> storage.put("key", List.of("landed")));
        assertThrows(StorageException.class, () -> storage.get("key"));
        assertThrows(StorageException.class, () -> storage.scan(k -> true));
        assertThrows(StorageException.class, storage::keys);
        assertThrows(StorageException.class, () -> storage.put("other", List.of("stale")));
        assertThrows(StorageException.class, () -> storage.delete("key"));
        assertThrows(StorageException.class, storage::clear);
        assertThrows(StorageException.class, storage::checkpoint);
        disk.fail = false;
        storage.load();
        assertEquals(List.of("landed"), storage.get("key").orElseThrow());
        storage.put("other", List.of("retry"));
        assertEquals(List.of("landed"), storage.get("key").orElseThrow());
    }

    @Test
    void concurrentWritesSurviveReloadAndDeleteClearPersist() throws Exception {
        Path file = dir.resolve("data.json");
        var storage = new PersistentStorage<>(file, TYPE);
        storage.load(); // an absent file is a new store, not corruption
        try (var workers = Executors.newFixedThreadPool(4)) {
            List<Future<?>> writes = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                String key = "key-" + i;
                writes.add(workers.submit(() -> storage.put(key, List.of(key))));
            }
            for (var write : writes) write.get();
        }
        var reloaded = new PersistentStorage<>(file, TYPE);
        reloaded.load();
        assertEquals(20, reloaded.keys().size());
        storage.delete("key-0");
        reloaded.load();
        assertTrue(reloaded.get("key-0").isEmpty());
        storage.clear();
        reloaded.load();
        assertTrue(reloaded.keys().isEmpty());
    }

    static final class RuntimeValue {
        private String value;
        @JsonIgnore
        private String runtimeId;

        RuntimeValue() {}

        RuntimeValue(String value, String runtimeId) {
            this.value = value;
            this.runtimeId = runtimeId;
        }

        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public String getRuntimeId() { return runtimeId; }
        public void setRuntimeId(String runtimeId) { this.runtimeId = runtimeId; }
    }
}
