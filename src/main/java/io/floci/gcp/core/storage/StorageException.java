package io.floci.gcp.core.storage;

/** Raised when a requested durability boundary cannot be established. */
public class StorageException extends RuntimeException {
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
