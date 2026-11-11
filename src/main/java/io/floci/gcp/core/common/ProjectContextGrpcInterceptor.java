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

import java.util.List;
import java.util.Set;

/**
 * The gRPC counterpart of {@link ProjectContextFilter}: every handler callback runs with
 * {@link RequestContext} holding the project of the call. The project is the one the request
 * message names as its target, otherwise the {@code x-goog-request-params} routing header,
 * otherwise the default project. One request context serves the whole call, so a streaming
 * handler that works on several projects at once passes the project explicitly
 * ({@link RequestScopes}, {@code forProject}) instead of reading it from the context.
 * <p>
 * As in {@link ProjectContextFilter}, a named project goes through {@link ProjectRegistry#observe}:
 * a registered project's number resolves to its ID, and an unknown project is registered or, in
 * strict mode, rejected. A project named only by a reference to another API's resource is not
 * registered or rejected.
 * <p>
 * Resource names that use a known project's number already arrive rewritten to the project's ID
 * by {@link ProjectNumberGrpcInterceptor}, which runs ahead of IAM.
 */
public final class ProjectContextGrpcInterceptor implements ServerInterceptor {

    private final String defaultProjectId;
    private final ProjectRegistry projects;
    private final Set<String> domains;

    public ProjectContextGrpcInterceptor(String defaultProjectId, ProjectRegistry projects, Set<String> domains) {
        this.defaultProjectId = defaultProjectId;
        this.projects = projects;
        this.domains = domains;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
            Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String routed = RequestTarget.routedProject(headers.get(RequestTarget.ROUTING_HEADER));
        // The message outranks the header, so the header is only mapped here. It is observed once
        // the first message shows that it decides the project.
        String initial = routed != null
                ? projects.find(routed).map(ProjectRecord::getProjectId).orElse(routed)
                : defaultProjectId;
        ServiceUsageGrpcInterceptor.GatedCall<ReqT, RespT> gated = new ServiceUsageGrpcInterceptor.GatedCall<>(call);
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
            delegate = RequestScopes.callAs(initial, () -> next.startCall(gated, headers));
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

            private String unobserved = routed;
            private boolean rejected;

            @Override
            public void onMessage(ReqT message) {
                if (rejected) {
                    return;
                }
                if (!named && message instanceof Message proto) {
                    List<ProtoResourceFields.Ref> refs = ProtoResourceFields.refs(proto);
                    String target = RequestTarget.primaryProject(refs);
                    if (target != null) {
                        named = true;
                        if (RequestTarget.projects(refs, domains).contains(target)) {
                            unobserved = target;
                        } else if (routed == null) {
                            // A reference to another API's resource, such as a KMS key, can
                            // only supply the context: it is not a project this call acts on.
                            project = projects.find(target).map(ProjectRecord::getProjectId).orElse(target);
                        }
                    }
                }
                if (!admitted()) {
                    return;
                }
                scoped(() -> super.onMessage(message));
            }

            @Override
            public void onHalfClose() {
                if (admitted()) {
                    scoped(super::onHalfClose);
                }
            }

            @Override
            public void onReady() {
                if (!rejected) {
                    scoped(super::onReady);
                }
            }

            @Override
            public void onCancel() {
                last(super::onCancel);
            }

            @Override
            public void onComplete() {
                last(super::onComplete);
            }

            /** Rejects the call when the project it names does not exist; the handler then only sees a cancel. */
            private boolean admitted() {
                if (rejected) {
                    return false;
                }
                if (unobserved != null) {
                    String naming = unobserved;
                    unobserved = null;
                    try {
                        project = projects.observe(naming);
                    } catch (GcpException e) {
                        rejected = true;
                        gated.reject(GcpGrpcController.grpcException(e));
                        scoped(super::onCancel);
                        return false;
                    }
                }
                return true;
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
                    if (!rejected) {
                        scoped(callback);
                    }
                } finally {
                    if (callScoped && context.isActive()) {
                        context.terminate();
                    }
                }
            }
        };
    }
}
