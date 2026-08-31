package io.floci.gcp.services.firebaseauth;

import io.quarkus.runtime.Startup;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.Router;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@Startup
@ApplicationScoped
public class FirebaseAuthCorsRouteFilter {

    // FirebaseAuthController (sign-in/sign-up) and SecureTokenController (token refresh).
    private static final String[] PATH_PREFIXES = {
            "/identitytoolkit.googleapis.com/*",
            "/securetoken.googleapis.com/*",
    };

    // Matches expressjs/cors' defaults, which is what firebase-tools' Auth Emulator uses.
    private static final String DEFAULT_CORS_METHODS = "GET,HEAD,PUT,PATCH,POST,DELETE";


    private final Router router;

    @Inject
    public FirebaseAuthCorsRouteFilter(Router router) {
        this.router = router;
    }

    @PostConstruct
    void init() {
        for (String pathPrefix : PATH_PREFIXES) {
            router.route(pathPrefix).order(Integer.MIN_VALUE + 1).handler(this::handle);
        }
    }

    private void handle(RoutingContext ctx) {
        String origin = ctx.request().getHeader("Origin");
        if (origin == null) {
            ctx.next();
            return;
        }
        ctx.response().putHeader("Access-Control-Allow-Origin", origin);

        if (!"OPTIONS".equalsIgnoreCase(ctx.request().method().name())) {
            ctx.response().putHeader("Vary", "Origin");
            ctx.next();
            return;
        }
        ctx.response().putHeader("Access-Control-Allow-Methods", DEFAULT_CORS_METHODS);
        String requestedHeaders = ctx.request().getHeader("Access-Control-Request-Headers");
        if (requestedHeaders != null && !requestedHeaders.isEmpty()) {
            ctx.response().putHeader("Access-Control-Allow-Headers", requestedHeaders);
        }
        ctx.response().putHeader("Vary", "Origin, Access-Control-Request-Headers");
        ctx.response().setStatusCode(204).end();
    }
}
