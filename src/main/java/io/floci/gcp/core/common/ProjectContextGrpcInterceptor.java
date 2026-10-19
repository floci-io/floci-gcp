package io.floci.gcp.core.common;

import com.google.protobuf.Message;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.smallrye.common.vertx.VertxContext;

/**
 * The gRPC counterpart of {@link ProjectContextFilter}: every handler callback runs with
 * {@link RequestContext} holding the project of the call. The project is the one the request
 * message names as its target, otherwise the {@code x-goog-request-params} routing header,
 * otherwise the default project. One request context serves the whole call, so a streaming
 * handler that works on several projects at once passes the project explicitly
 * ({@link RequestScopes}, {@code forProject}) instead of reading it from the context.
 */
public final class ProjectContextGrpcInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> REQUEST_PARAMS =
            Metadata.Key.of("x-goog-request-params", Metadata.ASCII_STRING_MARSHALLER);

    private final String defaultProjectId;

    public ProjectContextGrpcInterceptor(String defaultProjectId) {
        this.defaultProjectId = defaultProjectId;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
            Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String routed = ServiceUsageGrpcInterceptor.projectFromHeader(headers.get(REQUEST_PARAMS));
        String initial = routed != null ? routed : defaultProjectId;
        // On the server every call has its own Vert.x context, which carries the request context
        // to the threads the handler moves work to. There the context lives as long as the call,
        // like a REST request's. Elsewhere it is bound to the thread and scoped to each callback.
        ManagedContext context = Arc.container().requestContext();
        boolean callScoped = !context.isActive() && VertxContext.isOnDuplicatedContext();
        if (callScoped) {
            context.activate();
        }
        ServerCall.Listener<ReqT> delegate;
        try {
            delegate = RequestScopes.callAs(initial, () -> next.startCall(call, headers));
        } catch (RuntimeException e) {
            if (callScoped) {
                context.terminate();
            }
            throw e;
        }
        return new SimpleForwardingServerCallListener<>(delegate) {

            private String project = initial;

            // The first message that names a project sets it for the rest of the call, so later
            // messages of a stream (upload chunks, appended rows) are not read again.
            private boolean named;

            @Override
            public void onMessage(ReqT message) {
                if (!named && message instanceof Message proto) {
                    String target = targetProject(proto);
                    if (target != null) {
                        project = target;
                        named = true;
                    }
                }
                scoped(() -> super.onMessage(message));
            }

            @Override
            public void onHalfClose() {
                scoped(super::onHalfClose);
            }

            @Override
            public void onReady() {
                scoped(super::onReady);
            }

            @Override
            public void onCancel() {
                last(super::onCancel);
            }

            @Override
            public void onComplete() {
                last(super::onComplete);
            }

            private void scoped(Runnable callback) {
                if (callScoped) {
                    Arc.container().instance(RequestContext.class).get().setProjectId(project);
                    callback.run();
                } else {
                    RequestScopes.runAs(project, callback);
                }
            }

            private void last(Runnable callback) {
                try {
                    scoped(callback);
                } finally {
                    if (callScoped && context.isActive()) {
                        context.terminate();
                    }
                }
            }
        };
    }

    /** The project of the request's own target, otherwise of the first resource it names. */
    static String targetProject(Message request) {
        String first = null;
        for (ProtoResourceFields.Ref ref : ProtoResourceFields.refs(request)) {
            String project = ServiceUsageGrpcInterceptor.projectOf(ref.field(), ref.value());
            if (project == null) {
                continue;
            }
            if (ref.top()) {
                return project;
            }
            if (first == null) {
                first = project;
            }
        }
        return first;
    }
}
