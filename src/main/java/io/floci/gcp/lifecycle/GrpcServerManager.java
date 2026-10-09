package io.floci.gcp.lifecycle;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.common.ServiceUsageGate;
import io.floci.gcp.core.common.ServiceUsageGrpcInterceptor;
import io.floci.gcp.services.iam.authorization.IamGrpcAuthorizationInterceptor;
import io.grpc.BindableService;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.quarkus.runtime.Startup;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.grpc.server.GrpcServerOptions;
import io.vertx.grpcio.server.GrpcIoServer;
import io.vertx.grpcio.server.GrpcIoServiceBridge;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@Startup
@ApplicationScoped
public class GrpcServerManager {

    private static final Logger LOG = Logger.getLogger(GrpcServerManager.class);
    private static final long MAX_INBOUND_MESSAGE_SIZE = 4L * 1024 * 1024;

    private final Vertx vertx;
    private final Router router;
    private final Instance<BindableService> services;
    private final IamGrpcAuthorizationInterceptor authorization;
    private final ServiceRegistry serviceRegistry;
    private final ServiceUsageGate serviceUsageGate;
    private final String defaultProjectId;

    private GrpcIoServer grpcServer;

    @Inject
    GrpcServerManager(Vertx vertx, Router router, Instance<BindableService> services,
            IamGrpcAuthorizationInterceptor authorization, ServiceRegistry serviceRegistry,
            ServiceUsageGate serviceUsageGate, EmulatorConfig config) {
        this.vertx = vertx;
        this.router = router;
        this.services = services;
        this.authorization = authorization;
        this.serviceRegistry = serviceRegistry;
        this.serviceUsageGate = serviceUsageGate;
        this.defaultProjectId = config.defaultProjectId();
    }

    @PostConstruct
    void init() {
        grpcServer = GrpcIoServer.server(vertx,
                new GrpcServerOptions().setMaxMessageSize(MAX_INBOUND_MESSAGE_SIZE));
        services.stream().forEach(this::bind);
        router.route().order(Integer.MIN_VALUE).handler(ctx -> {
            String method = ctx.request().method().name();
            String uri = ctx.request().uri();
            String contentType = ctx.request().getHeader("Content-Type");
            String path = ctx.request().path();
            if ("/health".equals(path) || "/_floci-gcp/health".equals(path)) {
                LOG.debugf("Incoming request: %s %s, content-type=%s", method, uri, contentType);
            } else {
                LOG.infof("Incoming request: %s %s, content-type=%s", method, uri, contentType);
            }
            String ct = ctx.request().getHeader("Content-Type");
            if (ct != null && ct.startsWith("application/grpc")) {
                long start = System.currentTimeMillis();
                String remoteAddr = ctx.request().remoteAddress() != null ? ctx.request().remoteAddress().host() : "-";
                ctx.request().response().endHandler(v ->
                        LOG.infof("%s gRPC %s %dms", remoteAddr, path, System.currentTimeMillis() - start));
                grpcServer.handle(ctx.request());
            } else {
                ctx.next();
            }
        });
    }

    public void bind(BindableService service, ServerInterceptor... interceptors) {
        ServerServiceDefinition authorized = ServerInterceptors.intercept(
                ServerInterceptors.intercept(service, interceptors), authorization);
        // The interceptor applied last runs first, so a disabled API answers SERVICE_DISABLED before IAM runs.
        ServerServiceDefinition gated = serviceRegistry.byResourceClass(service.getClass())
                .filter(serviceUsageGate::gates)
                .map(descriptor -> ServerInterceptors.intercept(authorized,
                        new ServiceUsageGrpcInterceptor(serviceUsageGate, descriptor, defaultProjectId)))
                .orElse(authorized);
        GrpcIoServiceBridge.bridge(() -> gated).bind(grpcServer);
    }
}
