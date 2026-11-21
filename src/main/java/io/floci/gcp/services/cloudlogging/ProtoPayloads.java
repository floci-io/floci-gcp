package io.floci.gcp.services.cloudlogging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.audit.AuditLog;
import com.google.logging.v2.LogEntryOperation;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ProtoJson;

import java.util.Map;

/**
 * Converts {@code LogEntry.proto_payload} and {@code LogEntry.operation} between their protobuf form and the
 * proto3 JSON objects stored on {@link io.floci.gcp.services.cloudlogging.model.StoredLogEntry}, so gRPC and
 * REST ingestion store the same value and every reader (list, filters, exports) sees one shape.
 *
 * <p>The LogEntry reference lists the supported payload types, "user-defined types are not supported":
 * {@code google.cloud.audit.AuditLog} and {@code google.appengine.logging.v1.RequestLog}. Only AuditLog is
 * stored; RequestLog has no Java class in the emulator's dependencies.
 */
final class ProtoPayloads {

    static final String AUDIT_LOG_TYPE = "type.googleapis.com/" + AuditLog.getDescriptor().getFullName();

    private static final ObjectMapper JSON = new ObjectMapper();

    private ProtoPayloads() {}

    /** The stored form of a gRPC payload, or null when its type is not stored. */
    static Map<String, Object> fromAny(Any payload) {
        if (!AUDIT_LOG_TYPE.equals(payload.getTypeUrl())) {
            return null;
        }
        try {
            AuditLog.parseFrom(payload.getValue());
        } catch (InvalidProtocolBufferException e) {
            throw GcpException.invalidArgument("Invalid protoPayload: " + e.getMessage());
        }
        return ProtoJson.printAsMap(payload);
    }

    /** The stored form of a REST payload, or null when its {@code @type} is not stored. */
    static Map<String, Object> fromJson(Object payload) {
        if (!(payload instanceof Map<?, ?> map) || !AUDIT_LOG_TYPE.equals(map.get("@type"))) {
            return null;
        }
        return ProtoJson.printAsMap(ProtoJson.merge(write(map), Any.newBuilder()).build());
    }

    static Any toAny(Object stored) {
        return ProtoJson.merge(write(stored), Any.newBuilder()).build();
    }

    static Map<String, Object> operationFromProto(LogEntryOperation operation) {
        return ProtoJson.printAsMap(operation);
    }

    /** The stored form of a REST {@code operation}, or null when the entry has none. */
    static Map<String, Object> operationFromJson(Object operation) {
        if (operation == null) {
            return null;
        }
        if (!(operation instanceof Map<?, ?>)) {
            throw GcpException.invalidArgument("Invalid protobuf JSON: operation must be an object");
        }
        return ProtoJson.printAsMap(parse(operation, LogEntryOperation.newBuilder()));
    }

    static LogEntryOperation operationToProto(Map<String, Object> stored) {
        return parse(stored, LogEntryOperation.newBuilder());
    }

    @SuppressWarnings("unchecked")
    private static <T extends Message> T parse(Object json, Message.Builder builder) {
        return (T) ProtoJson.merge(write(json), builder).build();
    }

    private static String write(Object json) {
        try {
            return JSON.writeValueAsString(json);
        } catch (JsonProcessingException e) {
            throw GcpException.invalidArgument("Invalid protobuf JSON: " + e.getOriginalMessage());
        }
    }
}
