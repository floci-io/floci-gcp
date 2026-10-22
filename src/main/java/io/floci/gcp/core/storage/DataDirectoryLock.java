package io.floci.gcp.core.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exclusive ownership of a persistent data directory. Every persistent backend rewrites its
 * whole file on flush, so two processes sharing a directory silently overwrite each other's
 * acknowledged writes. The lock is advisory and held by the OS, so a killed process leaves no
 * stale lock behind.
 */
final class DataDirectoryLock implements AutoCloseable {

    static final String LOCK_FILE = ".floci-gcp.lock";

    // Directories owned in this JVM. A second owner is refused here, before it opens a channel:
    // on POSIX systems closing any channel on the lock file drops every lock this JVM holds on it.
    private static final Set<Path> HELD = ConcurrentHashMap.newKeySet();

    private final Path key;
    private final FileChannel channel;
    private final FileLock lock;

    private DataDirectoryLock(Path key, FileChannel channel, FileLock lock) {
        this.key = key;
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * @throws IllegalStateException when another process, or another emulator instance in this
     *                               JVM, already owns the directory
     */
    static DataDirectoryLock acquire(Path dir) {
        Path root = dir.toAbsolutePath();
        Path key = null;
        FileChannel channel = null;
        try {
            Files.createDirectories(root);
            Path realRoot = root.toRealPath();
            if (!HELD.add(realRoot)) {
                throw inUse(root, "another floci-gcp instance in this JVM");
            }
            key = realRoot;
            channel = FileChannel.open(root.resolve(LOCK_FILE),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = tryLock(channel, root);
            if (lock == null) {
                throw inUse(root, "another floci-gcp process");
            }
            channel.truncate(0);
            channel.write(ByteBuffer.wrap(
                    (ProcessHandle.current().pid() + "\n").getBytes(StandardCharsets.US_ASCII)));
            return new DataDirectoryLock(key, channel, lock);
        } catch (OverlappingFileLockException e) {
            release(key, channel);
            throw inUse(root, "another floci-gcp instance in this JVM");
        } catch (IOException e) {
            release(key, channel);
            throw new IllegalStateException("Could not lock persistent storage path '" + root
                    + "': " + e.getMessage() + ". Point FLOCI_GCP_STORAGE_PERSISTENT_PATH at a"
                    + " writable directory.", e);
        } catch (IllegalStateException e) {
            release(key, channel);
            throw e;
        }
    }

    @Override
    public void close() {
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } catch (IOException ignored) {
            // Closing the channel below releases the lock as well.
        }
        release(key, channel);
    }

    private static void release(Path key, FileChannel channel) {
        closeQuietly(channel);
        if (key != null) {
            HELD.remove(key);
        }
    }

    private static FileLock tryLock(FileChannel channel, Path root) {
        try {
            return channel.tryLock();
        } catch (IOException e) {
            throw new IllegalStateException("Could not lock persistent storage path '" + root
                    + "': " + e.getMessage() + ". The filesystem may not support file locks; point"
                    + " FLOCI_GCP_STORAGE_PERSISTENT_PATH at a local directory or Docker volume.", e);
        }
    }

    private static IllegalStateException inUse(Path root, String holder) {
        return new IllegalStateException("Persistent storage path '" + root + "' is already in use by "
                + holder + ". Each running emulator needs its own data directory; set"
                + " FLOCI_GCP_STORAGE_PERSISTENT_PATH to a separate path for this instance.");
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Nothing left to release.
        }
    }
}
