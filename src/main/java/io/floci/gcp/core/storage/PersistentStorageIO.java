package io.floci.gcp.core.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Disk operations shared by all mutations; the JSON representation is unchanged. */
class PersistentStorageIO {
    byte[] read(Path path) throws IOException {
        return Files.readAllBytes(path);
    }

    void write(Path path, byte[] data) throws IOException {
        Path absolute = path.toAbsolutePath();
        Path directory = absolute.getParent();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".floci-", ".tmp");
        try {
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(data);
                while (bytes.hasRemaining()) file.write(bytes);
                file.force(true);
            }
            Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            try (FileChannel parent = FileChannel.open(directory, StandardOpenOption.READ)) {
                parent.force(true);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
