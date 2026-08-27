package io.floci.gcp.lifecycle;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.Resettable;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.core.tls.TlsConfigSource;
import io.floci.gcp.lifecycle.inithook.InitializationHook;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

@Path("/_floci-gcp")
@Produces(MediaType.APPLICATION_JSON)
public class EmulatorInfoController {

    private static final Logger LOG = Logger.getLogger(EmulatorInfoController.class);

    private final ServiceRegistry serviceRegistry;
    private final InitLifecycleState initLifecycleState;
    private final EmulatorConfig config;
    private final StorageFactory storageFactory;
    private final Instance<Resettable> resettables;

    @Inject
    public EmulatorInfoController(ServiceRegistry serviceRegistry,
                                  InitLifecycleState initLifecycleState,
                                  EmulatorConfig config,
                                  StorageFactory storageFactory,
                                  Instance<Resettable> resettables) {
        this.serviceRegistry = serviceRegistry;
        this.initLifecycleState = initLifecycleState;
        this.config = config;
        this.storageFactory = storageFactory;
        this.resettables = resettables;
    }

    @GET
    @Path("/health")
    public Response health() {
        return Response.ok(Map.of(
                "services", serviceRegistry.getServices(),
                "version", HealthController.resolveVersion())).build();
    }

    @GET
    @Path("/info")
    public Response info() {
        return Response.ok(Map.of(
                "version", HealthController.resolveVersion(),
                "port", config.port(),
                "defaultProject", config.defaultProjectId())).build();
    }

    @GET
    @Path("/tls-cert")
    public Response tlsCert() {
        String pem = TlsConfigSource.currentCertPem;
        if (pem == null || pem.isBlank()) {
            boolean tlsEnabled = config.tls().enabled();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tlsEnabled", tlsEnabled);
            if (!tlsEnabled) {
                body.put("error", "TLS is not enabled");
                body.put("message",
                        "floci-gcp is serving plain HTTP only. Set FLOCI_GCP_TLS_ENABLED=true and "
                        + "restart to serve HTTPS on the same port.");
            } else {
                body.put("error", "TLS certificate not available yet");
                body.put("message",
                        "TLS is enabled but no certificate is available. If floci-gcp has only just "
                        + "started, the certificate is still being generated — retry shortly. "
                        + "Otherwise check the startup logs for certificate generation/read errors.");
            }
            return Response.status(Response.Status.NOT_FOUND).entity(body).build();
        }
        return Response.ok(pem).type("application/x-pem-file").build();
    }

    @GET
    @Path("/init")
    public Response init() {
        Map<String, Object> completed = new LinkedHashMap<>();
        completed.put("boot", initLifecycleState.isBootCompleted());
        completed.put("start", initLifecycleState.isStartCompleted());
        completed.put("ready", initLifecycleState.isReadyCompleted());
        completed.put("shutdown", initLifecycleState.isShutdownStarted());

        Map<String, Object> scripts = new LinkedHashMap<>();
        for (InitializationHook hook : InitializationHook.values()) {
            scripts.put(hook.getResponseKey(), initLifecycleState.getScripts(hook).stream()
                    .map(r -> Map.of("script", r.script(), "state", r.state(), "return_code", r.returnCode()))
                    .toList());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("completed", completed);
        body.put("scripts", scripts);
        return Response.ok(body).build();
    }

    @POST
    @Path("/state/reset")
    public Response reset() {
        performReset();
        return Response.ok(Map.of("status", "OK")).build();
    }

    @POST
    @Path("/state/nuke")
    public Response nuke() {
        return reset();
    }

    private void performReset() {
        for (Resettable r : resettables) {
            r.clear();
        }
        storageFactory.clearAll();
    }

    /** Deletes everything under the persistent data root, keeping the root directory itself. */
}
