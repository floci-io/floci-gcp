package io.floci.gcp.lifecycle;

import io.grpc.BindableService;
import io.quarkus.runtime.Startup;
import io.vertx.ext.web.Router;
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

    private final io.vertx.core.Vertx vertx;
    private final Router router;
    private final Instance<BindableService> services;

    private GrpcIoServer grpcServer;

    @Inject
    GrpcServerManager(io.vertx.core.Vertx vertx, Router router, Instance<BindableService> services) {
        this.vertx = vertx;
        this.router = router;
        this.services = services;
    }

    @PostConstruct
    void init() {
        grpcServer = GrpcIoServer.server(vertx);
        services.stream().forEach(svc -> GrpcIoServiceBridge.bridge(svc).bind(grpcServer));
        router.route().order(Integer.MIN_VALUE).handler(ctx -> {
            String method = ctx.request().method().name();
            String uri = ctx.request().uri();
            String contentType = ctx.request().getHeader("Content-Type");
            LOG.infof("Incoming request: %s %s, content-type=%s", method, uri, contentType);
            String ct = ctx.request().getHeader("Content-Type");
            if (ct != null && ct.startsWith("application/grpc")) {
                long start = System.currentTimeMillis();
                String path = ctx.request().path();
                String remoteAddr = ctx.request().remoteAddress() != null ? ctx.request().remoteAddress().host() : "-";
                ctx.request().response().endHandler(v ->
                        LOG.infof("%s gRPC %s %dms", remoteAddr, path, System.currentTimeMillis() - start));
                grpcServer.handle(ctx.request());
            } else {
                ctx.next();
            }
        });
    }

    public void bind(BindableService service) {
        GrpcIoServiceBridge.bridge(service).bind(grpcServer);
    }
}
