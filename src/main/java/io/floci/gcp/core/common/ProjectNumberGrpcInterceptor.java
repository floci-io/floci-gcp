package io.floci.gcp.core.common;

import com.google.protobuf.Message;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

import java.util.List;

/**
 * Rewrites every resource name that uses a known project's number to the project's ID, in the
 * first message of a call that names a project, so the gates, IAM and the handler all see one
 * name whichever form the caller used. It runs ahead of them: IAM matching a policy or a
 * {@code resource.name} condition against the number form while the handler acts on the ID form
 * would authorize a different resource than the one served. Later messages of a stream (upload chunks, appended rows) are passed on as sent,
 * as {@link ProjectContextGrpcInterceptor} reads the project from that same message.
 */
public final class ProjectNumberGrpcInterceptor implements ServerInterceptor {

    private final ProjectRegistry projects;

    public ProjectNumberGrpcInterceptor(ProjectRegistry projects) {
        this.projects = projects;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
            Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        return new SimpleForwardingServerCallListener<>(next.startCall(call, headers)) {

            private boolean named;

            @Override
            @SuppressWarnings("unchecked")
            public void onMessage(ReqT message) {
                if (named || !(message instanceof Message proto)) {
                    super.onMessage(message);
                    return;
                }
                List<ProtoResourceFields.Ref> refs = ProtoResourceFields.refs(proto);
                named = RequestTarget.primaryProject(refs) != null;
                if (!RequestTarget.namesKnownNumber(refs, projects)) {
                    super.onMessage(message);
                    return;
                }
                super.onMessage((ReqT) ProtoResourceFields.rename(proto,
                        (field, value) -> RequestTarget.canonical(field, value, projects)));
            }
        };
    }
}
