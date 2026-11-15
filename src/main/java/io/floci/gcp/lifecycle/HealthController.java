package io.floci.gcp.lifecycle;

import io.floci.gcp.core.common.ServiceRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

@Path("/health")
@Produces(MediaType.APPLICATION_JSON)
public class HealthController {

    private final ServiceRegistry serviceRegistry;

    @Inject
    public HealthController(ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
    }

    @GET
    public Response health() {
        return Response.ok(body(serviceRegistry)).build();
    }

    /**
     * The control-plane health body shared by {@code /health}, {@code /_floci/health} and
     * {@code /_floci-gcp/health}. floci-gcp has no failure signal of its own, so it is always UP.
     */
    static Map<String, Object> body(ServiceRegistry serviceRegistry) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("cloud", EmulatorInfoController.CLOUD);
        body.put("services", serviceRegistry.getServices());
        body.put("version", resolveVersion());
        return body;
    }

    static String resolveVersion() {
        String env = System.getenv("FLOCI_GCP_VERSION");
        return (env != null && !env.isBlank()) ? env : "dev";
    }
}
