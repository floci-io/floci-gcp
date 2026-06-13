package io.floci.gcp.services.cloudsql;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Map;

@Path("/v1/projects/{project}")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CloudSqlController {

    private static final Logger LOG = Logger.getLogger(CloudSqlController.class);

    private final CloudSqlService service;

    CloudSqlController() {
        this.service = null;
    }

    @Inject
    public CloudSqlController(CloudSqlService service) {
        this.service = service;
    }

    @POST
    @Path("/instances")
    public Response createInstance(@PathParam("project") String project, Map<String, Object> body) {
        LOG.debugf("Cloud SQL createInstance project=%s", project);
        return Response.ok(service.createInstance(project, body)).build();
    }

    @GET
    @Path("/instances")
    public Response listInstances(@QueryParam("maxResults") @DefaultValue("0") int maxResults,
                                  @QueryParam("pageToken") String pageToken) {
        return Response.ok(service.listInstances(maxResults, pageToken)).build();
    }

    @GET
    @Path("/instances/{instance}")
    public Response getInstance(@PathParam("project") String project,
                                @PathParam("instance") String instance) {
        return Response.ok(service.getInstance(project, instance)).build();
    }

    @PATCH
    @Path("/instances/{instance}")
    public Response patchInstance(@PathParam("project") String project,
                                  @PathParam("instance") String instance,
                                  Map<String, Object> body) {
        return Response.ok(service.patchInstance(project, instance, body)).build();
    }

    @PUT
    @Path("/instances/{instance}")
    public Response updateInstance(@PathParam("project") String project,
                                   @PathParam("instance") String instance,
                                   Map<String, Object> body) {
        return Response.ok(service.updateInstance(project, instance, body)).build();
    }

    @DELETE
    @Path("/instances/{instance}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteInstance(@PathParam("project") String project,
                                   @PathParam("instance") String instance) {
        return Response.ok(service.deleteInstance(project, instance)).build();
    }

    @GET
    @Path("/tiers")
    public Response listTiers(@PathParam("project") String project) {
        return Response.ok(service.listTiers(project)).build();
    }

    @GET
    @Path("/instances/{instance}/connectSettings")
    public Response getConnectSettings(@PathParam("project") String project,
                                       @PathParam("instance") String instance) {
        return Response.ok(service.getConnectSettings(project, instance)).build();
    }

    @GET
    @Path("/operations/{operation}")
    public Response getOperation(@PathParam("operation") String operation) {
        return Response.ok(service.getOperation(operation)).build();
    }

    @GET
    @Path("/operations")
    public Response listOperations(@QueryParam("maxResults") @DefaultValue("0") int maxResults,
                                   @QueryParam("pageToken") String pageToken) {
        return Response.ok(service.listOperations(maxResults, pageToken)).build();
    }

    @POST
    @Path("/instances/{instance}/databases")
    public Response createDatabase(@PathParam("project") String project,
                                   @PathParam("instance") String instance,
                                   Map<String, Object> body) {
        return Response.ok(service.createDatabase(project, instance, body)).build();
    }

    @GET
    @Path("/instances/{instance}/databases")
    public Response listDatabases(@PathParam("project") String project,
                                  @PathParam("instance") String instance) {
        return Response.ok(service.listDatabases(project, instance)).build();
    }

    @GET
    @Path("/instances/{instance}/databases/{database}")
    public Response getDatabase(@PathParam("project") String project,
                                @PathParam("instance") String instance,
                                @PathParam("database") String database) {
        return Response.ok(service.getDatabase(project, instance, database)).build();
    }

    @PUT
    @Path("/instances/{instance}/databases/{database}")
    public Response updateDatabase(@PathParam("project") String project,
                                   @PathParam("instance") String instance,
                                   @PathParam("database") String database,
                                   Map<String, Object> body) {
        return Response.ok(service.updateDatabase(project, instance, database, body)).build();
    }

    @PATCH
    @Path("/instances/{instance}/databases/{database}")
    public Response patchDatabase(@PathParam("project") String project,
                                  @PathParam("instance") String instance,
                                  @PathParam("database") String database,
                                  Map<String, Object> body) {
        return Response.ok(service.patchDatabase(project, instance, database, body)).build();
    }

    @DELETE
    @Path("/instances/{instance}/databases/{database}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteDatabase(@PathParam("project") String project,
                                   @PathParam("instance") String instance,
                                   @PathParam("database") String database) {
        return Response.ok(service.deleteDatabase(project, instance, database)).build();
    }

    @POST
    @Path("/instances/{instance}/users")
    public Response createUser(@PathParam("project") String project,
                               @PathParam("instance") String instance,
                               Map<String, Object> body) {
        return Response.ok(service.createUser(project, instance, body)).build();
    }

    @GET
    @Path("/instances/{instance}/users")
    public Response listUsers(@PathParam("project") String project,
                              @PathParam("instance") String instance) {
        return Response.ok(service.listUsers(project, instance)).build();
    }

    @GET
    @Path("/instances/{instance}/users/{name}")
    public Response getUser(@PathParam("project") String project,
                            @PathParam("instance") String instance,
                            @PathParam("name") String name,
                            @QueryParam("host") String host) {
        return Response.ok(service.getUser(project, instance, name, host)).build();
    }

    @PUT
    @Path("/instances/{instance}/users")
    public Response updateUser(@PathParam("project") String project,
                               @PathParam("instance") String instance,
                               @QueryParam("name") String name,
                               @QueryParam("host") String host,
                               Map<String, Object> body) {
        return Response.ok(service.updateUser(project, instance, name, host, body)).build();
    }

    @DELETE
    @Path("/instances/{instance}/users")
    @Consumes(MediaType.WILDCARD)
    public Response deleteUser(@PathParam("project") String project,
                               @PathParam("instance") String instance,
                               @QueryParam("name") String name,
                               @QueryParam("host") String host) {
        return Response.ok(service.deleteUser(project, instance, name, host)).build();
    }
}
