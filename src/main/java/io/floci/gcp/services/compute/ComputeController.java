package io.floci.gcp.services.compute;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
@Path("/compute/v1/projects/{project}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class ComputeController {
    private final ComputeService service;
    @Inject
    public ComputeController(ComputeService service) { this.service = service; }

    @GET @Path("/{path:.+}")
    public Object get(@PathParam("project") String project, @PathParam("path") String path, @Context UriInfo uri) {
        return service.get(project, path, query(uri));
    }
    @POST @Path("/{path:.+}")
    public Object post(@PathParam("project") String project, @PathParam("path") String path, @Context UriInfo uri, byte[] body) {
        return service.mutate(project, path, "POST", body(body), query(uri));
    }
    @PATCH @Path("/{path:.+}")
    public Object patch(@PathParam("project") String project, @PathParam("path") String path, @Context UriInfo uri, byte[] body) {
        return service.mutate(project, path, "PATCH", body(body), query(uri));
    }
    @PUT @Path("/{path:.+}")
    public Object put(@PathParam("project") String project, @PathParam("path") String path, @Context UriInfo uri, byte[] body) {
        return service.mutate(project, path, "PUT", body(body), query(uri));
    }
    @DELETE @Path("/{path:.+}")
    public Object delete(@PathParam("project") String project, @PathParam("path") String path, @Context UriInfo uri) {
        return service.mutate(project, path, "DELETE", null, query(uri));
    }
    private static Map<String, String> query(UriInfo uri) {
        Map<String, String> result = new LinkedHashMap<>();
        uri.getQueryParameters().forEach((key, values) -> result.put(key, values.getFirst()));
        return result;
    }
    private static ObjectNode body(byte[] bytes) {
        if (bytes == null || bytes.length == 0) { return ComputeService.object(); }
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(bytes);
            if (node instanceof ObjectNode object) { return object; }
        } catch (java.io.IOException ignored) {}
        throw io.floci.gcp.core.common.GcpException.invalidArgument("Request body must be a JSON object");
    }
}
