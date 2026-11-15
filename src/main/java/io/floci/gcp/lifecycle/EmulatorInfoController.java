package io.floci.gcp.lifecycle;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.Resettable;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.core.tls.TlsConfigSource;
import io.floci.gcp.lifecycle.inithook.InitializationHook;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The Floci control plane (floci-loft decision 0022) under the legacy {@code /_floci-gcp} prefix.
 * {@link FlociControlPlaneController} serves the same endpoints under the canonical {@code /_floci}.
 * An unknown path under either prefix answers a JSON 404 instead of falling into the GCS routes,
 * which would read the prefix as a bucket name.
 */
@Path("/_floci-gcp")
@Produces(MediaType.APPLICATION_JSON)
public class EmulatorInfoController {

    static final String CLOUD = "gcp";
    static final int MANIFEST_VERSION = 1;
    static final int CONSOLE_CONTRACT = 2;

    /** Each served endpoint and the methods it answers, sent as {@code Allow} on a 405. */
    private static final Map<String, String> ENDPOINT_METHODS = Map.of(
            "health", "GET, HEAD",
            "info", "GET, HEAD",
            "init", "GET, HEAD",
            "tls-cert", "GET, HEAD",
            "state/reset", "POST",
            "state/nuke", "POST");

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
        return Response.ok(HealthController.body(serviceRegistry)).build();
    }

    /**
     * The Floci Console Contract v2 manifest. {@code port} and {@code defaultProject} are the keys
     * this endpoint returned before the manifest; they stay.
     */
    @GET
    @Path("/info")
    public Response info() {
        return Response.ok(manifest()).build();
    }

    Map<String, Object> manifest() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("manifest_version", MANIFEST_VERSION);
        body.put("console_contract", CONSOLE_CONTRACT);
        body.put("cloud", CLOUD);
        body.put("emulator", "floci-gcp");
        body.put("version", HealthController.resolveVersion());
        body.put("port", config.port());
        body.put("defaultProject", config.defaultProjectId());
        body.put("endpoint", config.effectiveBaseUrl());
        body.put("health_url", "/_floci/health");
        body.put("tls", Map.of("enabled", config.tls().enabled()));
        body.put("services", manifestServices(serviceRegistry.getServices()));
        body.put("defaults", Map.of("project_id", config.defaultProjectId()));
        body.put("ui", Map.of("enabled", false));
        return body;
    }

    /** The health map's {@code running} and {@code available}, as the manifest's {@code enabled} and {@code disabled}. */
    static Map<String, String> manifestServices(Map<String, String> healthServices) {
        Map<String, String> services = new TreeMap<>();
        healthServices.forEach((name, status) -> services.put(name, "running".equals(status) ? "enabled" : "disabled"));
        return services;
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

    @GET
    @Path("/{any: .*}")
    public Response unknownGet(@Context UriInfo uriInfo) {
        return notFound(uriInfo);
    }

    @POST
    @Path("/{any: .*}")
    public Response unknownPost(@Context UriInfo uriInfo) {
        return notFound(uriInfo);
    }

    @PUT
    @Path("/{any: .*}")
    public Response unknownPut(@Context UriInfo uriInfo) {
        return notFound(uriInfo);
    }

    @PATCH
    @Path("/{any: .*}")
    public Response unknownPatch(@Context UriInfo uriInfo) {
        return notFound(uriInfo);
    }

    @DELETE
    @Path("/{any: .*}")
    public Response unknownDelete(@Context UriInfo uriInfo) {
        return notFound(uriInfo);
    }

    /**
     * A JSON 404 for a path no endpoint serves. A served endpoint called with the wrong method keeps
     * answering 405, as it did before the catch-all existed, with the {@code Allow} header naming the
     * methods it does answer.
     */
    private static Response notFound(UriInfo uriInfo) {
        String path = uriInfo.getPath();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        int endpointStart = path.indexOf('/', 1);
        String allowed = endpointStart > 0 ? ENDPOINT_METHODS.get(path.substring(endpointStart + 1)) : null;
        if (allowed != null) {
            return Response.status(Response.Status.METHOD_NOT_ALLOWED).header("Allow", allowed).build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "NotFound");
        body.put("path", path);
        return Response.status(Response.Status.NOT_FOUND).entity(body).build();
    }

    private void performReset() {
        for (Resettable r : resettables) {
            r.clear();
        }
        storageFactory.clearAll();
    }

    /** Deletes everything under the persistent data root, keeping the root directory itself. */
}
