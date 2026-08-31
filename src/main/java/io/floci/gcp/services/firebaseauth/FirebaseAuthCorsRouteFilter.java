package io.floci.gcp.services.firebaseauth;

import io.quarkus.runtime.Startup;
import io.vertx.ext.web.Router;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@Startup
@ApplicationScoped
public class FirebaseAuthCorsRouteFilter {

    private static final String PATH_PREFIX = "/identitytoolkit.googleapis.com/*";

    private final Router router;

    @Inject
    public FirebaseAuthCorsRouteFilter(Router router) {
        this.router = router;
    }

    @PostConstruct
    void init() {
        router.route(PATH_PREFIX).order(Integer.MIN_VALUE + 1).handler(ctx -> {
            String origin = ctx.request().getHeader("Origin");
            if (origin == null) {
                ctx.next();
                return;
            }
            ctx.response().putHeader("Access-Control-Allow-Origin", origin);
            ctx.response().putHeader("Vary", "Origin");

            if (!"OPTIONS".equalsIgnoreCase(ctx.request().method().name())) {
                ctx.next();
                return;
            }
            String requestedMethod = ctx.request().getHeader("Access-Control-Request-Method");
            ctx.response().putHeader("Access-Control-Allow-Methods",
                    requestedMethod != null ? requestedMethod : "GET, POST");
            String requestedHeaders = ctx.request().getHeader("Access-Control-Request-Headers");
            if (requestedHeaders != null) {
                ctx.response().putHeader("Access-Control-Allow-Headers", requestedHeaders);
            }
            ctx.response().putHeader("Access-Control-Max-Age", "3600");
            ctx.response().setStatusCode(200).end();
        });
    }
}
