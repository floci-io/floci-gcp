package io.floci.gcp.services.cloudmonitoring;

import io.floci.gcp.core.common.ProtoJson;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Monitoring v3 REST {@code projects.notificationChannelDescriptors}. */
@Path("/v3/projects/{project}/notificationChannelDescriptors")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class NotificationChannelDescriptorsHttpController {

    private final NotificationChannelService channels;

    @Inject
    public NotificationChannelDescriptorsHttpController(NotificationChannelService channels) {
        this.channels = channels;
    }

    @GET
    public Response listNotificationChannelDescriptors(@PathParam("project") String project,
            @QueryParam("pageSize") @DefaultValue("0") int pageSize, @QueryParam("pageToken") String pageToken) {
        return json(ProtoJson.print(channels.listDescriptors("projects/" + project, pageSize, pageToken)));
    }

    @GET
    @Path("/{type}")
    public Response getNotificationChannelDescriptor(@PathParam("project") String project,
            @PathParam("type") String type) {
        return json(ProtoJson.print(channels.getDescriptor(
                "projects/" + project + "/notificationChannelDescriptors/" + type)));
    }

    private static Response json(String body) {
        return Response.ok(body, MediaType.APPLICATION_JSON).build();
    }
}
