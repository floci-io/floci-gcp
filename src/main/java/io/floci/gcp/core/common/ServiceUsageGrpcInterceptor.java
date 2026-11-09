package io.floci.gcp.core.common;

import com.google.protobuf.Message;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Applies {@link ServiceUsageGate} to one gRPC service. The consumer project comes from the
 * {@code x-goog-request-params} routing header when it names one, otherwise from the first
 * request message (every project named by the fields the API declares as resource names, or a
 * {@code project_id} field), otherwise the default project. Every request message that names another project is checked as well, so
 * neither the header nor an earlier message of a stream admits a project they do not name. A
 * service whose handler picks the project differently answers through its {@link ServiceUsageAdapter}.
 */
public final class ServiceUsageGrpcInterceptor implements ServerInterceptor {

    private final ServiceUsageGate gate;
    private final ServiceDescriptor descriptor;
    private final String defaultProjectId;
    private final Set<String> domains;

    public ServiceUsageGrpcInterceptor(ServiceUsageGate gate, ServiceDescriptor descriptor, String defaultProjectId) {
        this.gate = gate;
        this.descriptor = descriptor;
        this.defaultProjectId = defaultProjectId;
        Set<String> gated = new HashSet<>(descriptor.usageAdapter().referenceDomains());
        gated.add(descriptor.apiName());
        this.domains = Set.copyOf(gated);
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
            Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        GatedCall<ReqT, RespT> gated = new GatedCall<>(call);
        GatedListener<ReqT, RespT> listener = new GatedListener<>(gated, headers, next);
        String project = RequestTarget.routedProject(headers.get(RequestTarget.ROUTING_HEADER));
        if (project == null) {
            call.request(1);
        } else if (gate.isDenied(descriptor, project)) {
            // The transport cannot close a call before this method returns its listener, so the
            // rejection is delivered with the first message.
            listener.deferred = project;
            call.request(1);
        } else {
            listener.start(project);
        }
        return listener;
    }

    private List<String> projects(Object message) {
        if (message instanceof Message proto) {
            Optional<List<String>> owned = descriptor.usageAdapter().grpcProjects(proto);
            if (owned.isPresent()) {
                return owned.get();
            }
        }
        return RequestTarget.projects(message, domains);
    }

    /** Lets the gate close a call the handler already holds: the handler's later writes are dropped. */
    private static final class GatedCall<ReqT, RespT>
            extends ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT> {

        private volatile boolean rejected;
        private boolean closed;

        GatedCall(ServerCall<ReqT, RespT> delegate) {
            super(delegate);
        }

        /** A call the handler has already closed is only marked rejected; it cannot be closed twice. */
        synchronized void reject(StatusRuntimeException error) {
            rejected = true;
            if (!closed) {
                closed = true;
                super.close(error.getStatus(), error.getTrailers() != null ? error.getTrailers() : new Metadata());
            }
        }

        @Override
        public void request(int numMessages) {
            if (!rejected) {
                super.request(numMessages);
            }
        }

        @Override
        public synchronized void sendHeaders(Metadata headers) {
            if (!rejected) {
                super.sendHeaders(headers);
            }
        }

        @Override
        public synchronized void sendMessage(RespT message) {
            if (!rejected) {
                super.sendMessage(message);
            }
        }

        @Override
        public synchronized void close(Status status, Metadata trailers) {
            if (!rejected) {
                closed = true;
                super.close(status, trailers);
            }
        }
    }

    private final class GatedListener<ReqT, RespT> extends ForwardingServerCallListener<ReqT> {

        private final GatedCall<ReqT, RespT> call;
        private final Metadata headers;
        private final ServerCallHandler<ReqT, RespT> next;
        private ServerCall.Listener<ReqT> delegate;
        private final Set<String> admitted = new HashSet<>();
        private String deferred;
        private boolean rejected;
        private boolean ready;

        GatedListener(GatedCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            this.call = call;
            this.headers = headers;
            this.next = next;
        }

        @Override
        protected ServerCall.Listener<ReqT> delegate() {
            return delegate;
        }

        /** Starts the handler for a project the caller has already found enabled. */
        void start(String project) {
            admitted.add(project);
            delegate = next.startCall(call, headers);
        }

        boolean admit(List<String> projects) {
            if (rejected) {
                return false;
            }
            if (deferred != null) {
                List<String> named = new ArrayList<>(List.of(deferred));
                named.addAll(projects);
                projects = named;
                deferred = null;
            } else if (projects.isEmpty() && delegate == null) {
                projects = List.of(defaultProjectId);
            }
            for (String project : projects) {
                if (admitted.contains(project)) {
                    continue;
                }
                if (gate.isDenied(descriptor, project)) {
                    rejected = true;
                    call.reject(gate.grpcException(descriptor, project));
                    if (delegate != null) {
                        delegate.onCancel();
                    }
                    return false;
                }
                admitted.add(project);
            }
            if (delegate == null) {
                delegate = next.startCall(call, headers);
                if (ready) {
                    delegate.onReady();
                }
            }
            return true;
        }

        @Override
        public void onMessage(ReqT message) {
            if (admit(projects(message))) {
                delegate.onMessage(message);
            }
        }

        @Override
        public void onHalfClose() {
            if (admit(List.of())) {
                delegate.onHalfClose();
            }
        }

        @Override
        public void onCancel() {
            if (delegate != null && !rejected) {
                delegate.onCancel();
            }
        }

        @Override
        public void onComplete() {
            if (delegate != null && !rejected) {
                delegate.onComplete();
            }
        }

        @Override
        public void onReady() {
            if (rejected) {
                return;
            }
            if (delegate != null) {
                delegate.onReady();
            } else {
                ready = true;
            }
        }
    }
}
