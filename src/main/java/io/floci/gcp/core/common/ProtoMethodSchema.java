package io.floci.gcp.core.common;

import com.google.protobuf.Descriptors;
import io.grpc.protobuf.ProtoMethodDescriptorSupplier;

/**
 * The proto schema of a hand-built gRPC method, as generated stubs attach to theirs, so the
 * method's request type is known without reflection ({@link ProtoResourceFields#register}).
 */
public record ProtoMethodSchema(Descriptors.MethodDescriptor method) implements ProtoMethodDescriptorSupplier {

    public static ProtoMethodSchema of(Descriptors.FileDescriptor file, String service, String method) {
        return new ProtoMethodSchema(file.findServiceByName(service).findMethodByName(method));
    }

    @Override
    public Descriptors.FileDescriptor getFileDescriptor() {
        return method.getFile();
    }

    @Override
    public Descriptors.ServiceDescriptor getServiceDescriptor() {
        return method.getService();
    }

    @Override
    public Descriptors.MethodDescriptor getMethodDescriptor() {
        return method;
    }
}
