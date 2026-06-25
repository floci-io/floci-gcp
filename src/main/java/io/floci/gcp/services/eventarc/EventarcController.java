package io.floci.gcp.services.eventarc;

import io.floci.gcp.core.common.ProtoJson;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1/projects/{project}/locations/{location}")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EventarcController {

    private final EventarcService service;

    @Inject
    public EventarcController(EventarcService service) {
        this.service = service;
    }

    @POST
    @Path("/triggers")
    public Response createTrigger(@PathParam("project") String project,
                                   @PathParam("location") String location,
                                   @QueryParam("triggerId") String triggerId,
                                   @QueryParam("validateOnly") @DefaultValue("false") boolean validateOnly,
                                   String body) {
        return json(ProtoJson.print(service.createTrigger(project, location, triggerId, body, validateOnly)));
    }

    @GET
    @Path("/triggers")
    public Response listTriggers(@PathParam("project") String project,
                                  @PathParam("location") String location,
                                  @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                                  @QueryParam("pageToken") String pageToken) {
        return json(ProtoJson.print(service.listTriggers(project, location, pageSize, pageToken)));
    }

    @GET
    @Path("/triggers/{triggerId}")
    public Response getTrigger(@PathParam("project") String project,
                                @PathParam("location") String location,
                                @PathParam("triggerId") String triggerId) {
        return json(ProtoJson.print(service.getTrigger(triggerName(project, location, triggerId))));
    }

    @PATCH
    @Path("/triggers/{triggerId}")
    public Response updateTrigger(@PathParam("project") String project,
                                   @PathParam("location") String location,
                                   @PathParam("triggerId") String triggerId,
                                   @QueryParam("updateMask") String updateMask,
                                   @QueryParam("allowMissing") @DefaultValue("false") boolean allowMissing,
                                   @QueryParam("validateOnly") @DefaultValue("false") boolean validateOnly,
                                   String body) {
        return json(ProtoJson.print(service.updateTrigger(triggerName(project, location, triggerId), body, updateMask, allowMissing, validateOnly)));
    }

    @DELETE
    @Path("/triggers/{triggerId}")
    public Response deleteTrigger(@PathParam("project") String project,
                                   @PathParam("location") String location,
                                   @PathParam("triggerId") String triggerId,
                                   @QueryParam("allowMissing") @DefaultValue("false") boolean allowMissing,
                                   @QueryParam("validateOnly") @DefaultValue("false") boolean validateOnly) {
        return json(ProtoJson.print(service.deleteTrigger(triggerName(project, location, triggerId), allowMissing, validateOnly)));
    }

    @GET
    @Path("/channels")
    public Response listChannels(@PathParam("project") String project,
                                 @PathParam("location") String location,
                                 @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                                 @QueryParam("pageToken") String pageToken) {
        return json(ProtoJson.print(service.listChannels(project, location, pageSize, pageToken)));
    }

    @GET
    @Path("/channels/{channelId}")
    public Response getChannel(@PathParam("project") String project,
                               @PathParam("location") String location,
                               @PathParam("channelId") String channelId) {
        return json(ProtoJson.print(service.getChannel(channelName(project, location, channelId))));
    }

    @GET
    @Path("/providers")
    public Response listProviders(@PathParam("project") String project,
                                  @PathParam("location") String location,
                                  @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                                  @QueryParam("pageToken") String pageToken) {
        return json(ProtoJson.print(service.listProviders(project, location, pageSize, pageToken)));
    }

    @GET
    @Path("/providers/{providerId}")
    public Response getProvider(@PathParam("project") String project,
                                @PathParam("location") String location,
                                @PathParam("providerId") String providerId) {
        return json(ProtoJson.print(service.getProvider(providerName(project, location, providerId))));
    }

    private static Response json(String json) {
        return Response.ok(json, MediaType.APPLICATION_JSON).build();
    }

    private static String triggerName(String project, String location, String triggerId) {
        return "projects/" + project + "/locations/" + location + "/triggers/" + triggerId;
    }

    private static String channelName(String project, String location, String channelId) {
        return "projects/" + project + "/locations/" + location + "/channels/" + channelId;
    }

    private static String providerName(String project, String location, String providerId) {
        return "projects/" + project + "/locations/" + location + "/providers/" + providerId;
    }
}
