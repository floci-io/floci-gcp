package io.floci.gcp.services.cloudrun;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.cloudrun.model.CloudRunRuntimeInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * Internal target of {@link CloudRunUrlRoutingFilter} for Cloud Run Instance URLs. Requests reach the
 * instance's running container through the same proxy as Services.
 */
@Path("/run/v2/projects/{project}/locations/{location}/instances/{instanceId}")
@ApplicationScoped
@Produces(MediaType.WILDCARD)
@Consumes(MediaType.WILDCARD)
public class CloudRunInstanceInvocationController {

    private final CloudRunInstancesService instancesService;
    private final CloudRunInvocationController invocationController;

    @Context
    ContainerRequestContext requestContext;

    @Inject
    public CloudRunInstanceInvocationController(CloudRunInstancesService instancesService,
                                                CloudRunInvocationController invocationController) {
        this.instancesService = instancesService;
        this.invocationController = invocationController;
    }

    @GET
    @Path("/{path: .*}")
    public Response get(@PathParam("project") String project, @PathParam("location") String location,
                        @PathParam("instanceId") String instanceId, @PathParam("path") String path,
                        @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        return proxy("GET", project, location, instanceId, path, new byte[0], headers, uriInfo);
    }

    @HEAD
    @Path("/{path: .*}")
    public Response head(@PathParam("project") String project, @PathParam("location") String location,
                         @PathParam("instanceId") String instanceId, @PathParam("path") String path,
                         @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        return proxy("HEAD", project, location, instanceId, path, new byte[0], headers, uriInfo);
    }

    @OPTIONS
    @Path("/{path: .*}")
    public Response options(@PathParam("project") String project, @PathParam("location") String location,
                            @PathParam("instanceId") String instanceId, @PathParam("path") String path,
                            @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        return proxy("OPTIONS", project, location, instanceId, path, new byte[0], headers, uriInfo);
    }

    @POST
    @Path("/{path: .*}")
    public Response post(@PathParam("project") String project, @PathParam("location") String location,
                         @PathParam("instanceId") String instanceId, @PathParam("path") String path, byte[] body,
                         @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        return proxy("POST", project, location, instanceId, path, body, headers, uriInfo);
    }

    @PUT
    @Path("/{path: .*}")
    public Response put(@PathParam("project") String project, @PathParam("location") String location,
                        @PathParam("instanceId") String instanceId, @PathParam("path") String path, byte[] body,
                        @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        return proxy("PUT", project, location, instanceId, path, body, headers, uriInfo);
    }

    @PATCH
    @Path("/{path: .*}")
    public Response patch(@PathParam("project") String project, @PathParam("location") String location,
                          @PathParam("instanceId") String instanceId, @PathParam("path") String path, byte[] body,
                          @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        return proxy("PATCH", project, location, instanceId, path, body, headers, uriInfo);
    }

    @DELETE
    @Path("/{path: .*}")
    public Response delete(@PathParam("project") String project, @PathParam("location") String location,
                           @PathParam("instanceId") String instanceId, @PathParam("path") String path, byte[] body,
                           @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        return proxy("DELETE", project, location, instanceId, path, body, headers, uriInfo);
    }

    private Response proxy(String method, String project, String location, String instanceId, String path,
                           byte[] body, HttpHeaders headers, UriInfo uriInfo) {
        String instanceName = "projects/" + project + "/locations/" + location + "/instances/" + instanceId;
        CloudRunRuntimeInstance runtime = instancesService.readyRuntime(instanceName)
                .orElseThrow(() -> GcpException.unavailable(
                        "Cloud Run instance has no ready runtime: " + instanceName));
        return invocationController.forward(method, instanceName, runtime, pathAndQuery(path, uriInfo), body,
                headers, uriInfo);
    }

    private String pathAndQuery(String path, UriInfo uriInfo) {
        Object routed = requestContext == null
                ? null
                : requestContext.getProperty(CloudRunUrlRoutingFilter.ORIGINAL_PATH_QUERY);
        if (routed instanceof String original) {
            return original;
        }
        String query = uriInfo.getRequestUri().getRawQuery();
        return "/" + (path == null ? "" : path) + (query == null || query.isBlank() ? "" : "?" + query);
    }
}
