package io.floci.gcp.services.kafka;

/**
 * A Confluent Schema Registry error: the HTTP status, the Confluent {@code error_code} (for
 * example {@code 40401} for an unknown subject) and its message.
 */
public class SchemaRegistryException extends RuntimeException {

    private final int httpStatus;
    private final int errorCode;

    public SchemaRegistryException(int httpStatus, int errorCode, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public int errorCode() {
        return errorCode;
    }

    static SchemaRegistryException subjectNotFound(String subject) {
        return new SchemaRegistryException(404, 40401, "Subject '" + subject + "' not found.");
    }

    static SchemaRegistryException versionNotFound(String version) {
        return new SchemaRegistryException(404, 40402, "Version " + version + " not found.");
    }

    static SchemaRegistryException schemaNotFound(String detail) {
        return new SchemaRegistryException(404, 40403, detail);
    }
}
