package io.floci.gcp.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Predicate;

/** Test-only access to the package-local disk seam for service-level regressions. */
public final class PersistentStorageFaults<K, V> {
    private final FaultyDisk disk = new FaultyDisk();
    public final PersistentStorage<K, V> storage;

    public PersistentStorageFaults(Path path, TypeReference<Map<K, V>> type) {
        storage = new PersistentStorage<>(path, type, disk);
    }

    public void failBeforeWrite(Predicate<byte[]> predicate) {
        disk.failBefore = predicate;
    }

    public void failAfterWrite(boolean fail) {
        disk.failAfter = fail;
    }

    public int writes() {
        return disk.writes;
    }

    private static final class FaultyDisk extends PersistentStorageIO {
        Predicate<byte[]> failBefore = bytes -> false;
        boolean failAfter;
        int writes;

        @Override
        void write(Path path, byte[] bytes) throws IOException {
            writes++;
            if (failBefore.test(bytes)) {
                throw new IOException("injected failure before atomic replacement");
            }
            super.write(path, bytes);
            if (failAfter) {
                throw new IOException("injected failure after atomic replacement");
            }
        }
    }
}
