package io.floci.gcp.core.common;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.StatusRuntimeException;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies {@link ServiceUsageGate} to one gRPC service. The consumer project comes from the
 * {@code x-goog-request-params} routing header when it names one, otherwise from the first
 * request message (a {@code projects/...} resource name or a {@code project_id} field), otherwise
 * the default project.
 */
public final class ServiceUsageGrpcInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> REQUEST_PARAMS =
            Metadata.Key.of("x-goog-request-params", Metadata.ASCII_STRING_MARSHALLER);
    private static final Pattern RESOURCE_PROJECT = Pattern.compile("(?:^|[=&/])projects/([^/&]+)");
    private static final Pattern PARAM_PROJECT = Pattern.compile("(?:^|&)project(?:_id)?=([^&]+)");

    private final ServiceUsageGate gate;
    private final ServiceDescriptor descriptor;
    private final String defaultProjectId;

    public ServiceUsageGrpcInterceptor(ServiceUsageGate gate, ServiceDescriptor descriptor, String defaultProjectId) {
        this.gate = gate;
        this.descriptor = descriptor;
        this.defaultProjectId = defaultProjectId;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
            Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String project = projectFromHeader(headers.get(REQUEST_PARAMS));
        if (project != null) {
            if (reject(call, project)) {
                return new ServerCall.Listener<>() {};
            }
            return next.startCall(call, headers);
        }
        call.request(1);
        return new DeferredListener<>(call, headers, next);
    }

    private boolean reject(ServerCall<?, ?> call, String project) {
        if (!gate.isDenied(descriptor, project)) {
            return false;
        }
        StatusRuntimeException error = gate.grpcException(descriptor, project);
        call.close(error.getStatus(), error.getTrailers() != null ? error.getTrailers() : new Metadata());
        return true;
    }

    static String projectFromHeader(String params) {
        if (params == null || params.isBlank()) {
            return null;
        }
        String decoded = URLDecoder.decode(params, StandardCharsets.UTF_8);
        Matcher resource = RESOURCE_PROJECT.matcher(decoded);
        while (resource.find()) {
            if (isConcrete(resource.group(1))) {
                return resource.group(1);
            }
        }
        Matcher param = PARAM_PROJECT.matcher(decoded);
        if (param.find() && isConcrete(param.group(1))) {
            return param.group(1);
        }
        return null;
    }

    static String projectFromMessage(Object message) {
        if (!(message instanceof Message proto)) {
            return null;
        }
        for (FieldDescriptor field : proto.getDescriptorForType().getFields()) {
            if (field.isRepeated() || field.getJavaType() != FieldDescriptor.JavaType.STRING) {
                continue;
            }
            String value = (String) proto.getField(field);
            if (value.isEmpty()) {
                continue;
            }
            if (field.getName().equals("project_id") || field.getName().equals("project")) {
                String project = value.startsWith("projects/") ? value.substring("projects/".length()) : value;
                if (isConcrete(project)) {
                    return project;
                }
            }
            if (value.startsWith("projects/")) {
                int end = value.indexOf('/', "projects/".length());
                String project = end < 0 ? value.substring("projects/".length())
                        : value.substring("projects/".length(), end);
                if (isConcrete(project)) {
                    return project;
                }
            }
        }
        return null;
    }

    private static boolean isConcrete(String project) {
        return !project.isEmpty() && !project.equals("_") && !project.equals("-");
    }

    private final class DeferredListener<ReqT, RespT> extends ForwardingServerCallListener<ReqT> {

        private final ServerCall<ReqT, RespT> call;
        private final Metadata headers;
        private final ServerCallHandler<ReqT, RespT> next;
        private ServerCall.Listener<ReqT> delegate;
        private boolean rejected;
        private boolean ready;

        DeferredListener(ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            this.call = call;
            this.headers = headers;
            this.next = next;
        }

        @Override
        protected ServerCall.Listener<ReqT> delegate() {
            return delegate;
        }

        private boolean admit(String project) {
            if (delegate != null) {
                return true;
            }
            if (rejected) {
                return false;
            }
            if (reject(call, project != null ? project : defaultProjectId)) {
                rejected = true;
                return false;
            }
            delegate = next.startCall(call, headers);
            if (ready) {
                delegate.onReady();
            }
            return true;
        }

        @Override
        public void onMessage(ReqT message) {
            if (admit(delegate == null ? projectFromMessage(message) : null)) {
                delegate.onMessage(message);
            }
        }

        @Override
        public void onHalfClose() {
            if (admit(null)) {
                delegate.onHalfClose();
            }
        }

        @Override
        public void onCancel() {
            if (delegate != null) {
                delegate.onCancel();
            }
        }

        @Override
        public void onComplete() {
            if (delegate != null) {
                delegate.onComplete();
            }
        }

        @Override
        public void onReady() {
            if (delegate != null) {
                delegate.onReady();
            } else {
                ready = true;
            }
        }
    }
}
