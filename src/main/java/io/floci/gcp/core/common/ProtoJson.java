package io.floci.gcp.core.common;

import com.google.longrunning.ListOperationsResponse;
import com.google.longrunning.Operation;
import com.google.protobuf.Empty;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;

/**
 * Shared proto3 JSON parsing/printing for REST APIs backed by Google protobuf
 * contracts. The type registry keeps packed Any responses readable to GAPIC
 * HTTP/JSON clients.
 */
public final class ProtoJson {

    private static final JsonFormat.TypeRegistry TYPE_REGISTRY = JsonFormat.TypeRegistry.newBuilder()
            .add(Operation.getDescriptor())
            .add(ListOperationsResponse.getDescriptor())
            .add(Empty.getDescriptor())
            .add(com.google.cloud.run.v2.Service.getDescriptor())
            .add(com.google.cloud.run.v2.Revision.getDescriptor())
            .add(com.google.cloud.functions.v2.Function.getDescriptor())
            .add(com.google.cloud.functions.v2.OperationMetadata.getDescriptor())
            .add(com.google.cloud.eventarc.v1.Trigger.getDescriptor())
            .add(com.google.cloud.eventarc.v1.OperationMetadata.getDescriptor())
            .build();

    private static final JsonFormat.Printer PRINTER = JsonFormat.printer()
            .usingTypeRegistry(TYPE_REGISTRY)
            .omittingInsignificantWhitespace();

    private static final JsonFormat.Parser PARSER = JsonFormat.parser()
            .usingTypeRegistry(TYPE_REGISTRY)
            .ignoringUnknownFields();

    private ProtoJson() {}

    public static String print(MessageOrBuilder message) {
        try {
            return PRINTER.print(message);
        } catch (InvalidProtocolBufferException e) {
            throw GcpException.internal("Failed to serialize protobuf JSON: " + e.getMessage());
        }
    }

    public static <B extends Message.Builder> B merge(String json, B builder) {
        if (json == null || json.isBlank()) {
            return builder;
        }
        try {
            PARSER.merge(json, builder);
            return builder;
        } catch (InvalidProtocolBufferException e) {
            throw GcpException.invalidArgument("Invalid protobuf JSON: " + e.getMessage());
        }
    }
}
