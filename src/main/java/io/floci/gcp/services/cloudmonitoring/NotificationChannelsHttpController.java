package io.floci.gcp.services.cloudmonitoring;

import com.google.monitoring.v3.GetNotificationChannelVerificationCodeRequest;
import com.google.monitoring.v3.NotificationChannel;
import com.google.monitoring.v3.VerifyNotificationChannelRequest;
import com.google.protobuf.FieldMask;
import com.google.protobuf.util.FieldMaskUtil;
import io.floci.gcp.core.common.ProtoJson;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Monitoring v3 REST {@code projects.notificationChannels}. */
@Path("/v3/projects/{project}/notificationChannels")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class NotificationChannelsHttpController {

    private final NotificationChannelService channels;

    @Inject
    public NotificationChannelsHttpController(NotificationChannelService channels) {
        this.channels = channels;
    }

    @GET
    public Response listNotificationChannels(@PathParam("project") String project, @QueryParam("filter") String filter,
            @QueryParam("pageSize") @DefaultValue("0") int pageSize, @QueryParam("pageToken") String pageToken) {
        return json(ProtoJson.print(channels.list("projects/" + project, filter, pageSize, pageToken)));
    }

    @POST
    public Response createNotificationChannel(@PathParam("project") String project, String body) {
        NotificationChannel channel = ProtoJson.merge(body, NotificationChannel.newBuilder()).build();
        return json(ProtoJson.print(channels.create("projects/" + project, channel)));
    }

    @GET
    @Path("/{channel: [^/:]+}")
    public Response getNotificationChannel(@PathParam("project") String project, @PathParam("channel") String channel) {
        return json(ProtoJson.print(channels.get(channelName(project, channel))));
    }

    @PATCH
    @Path("/{channel: [^/:]+}")
    public Response updateNotificationChannel(@PathParam("project") String project,
            @PathParam("channel") String channel, @QueryParam("updateMask") String updateMask, String body) {
        NotificationChannel requested = ProtoJson.merge(body, NotificationChannel.newBuilder())
                .setName(channelName(project, channel)).build();
        return json(ProtoJson.print(channels.update(requested, mask(updateMask))));
    }

    @DELETE
    @Path("/{channel: [^/:]+}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteNotificationChannel(@PathParam("project") String project,
            @PathParam("channel") String channel, @QueryParam("force") @DefaultValue("false") boolean force) {
        channels.delete(channelName(project, channel), force);
        return json("{}");
    }

    @POST
    @Path("/{channel}:sendVerificationCode")
    public Response sendNotificationChannelVerificationCode(@PathParam("project") String project,
            @PathParam("channel") String channel, String body) {
        channels.sendVerificationCode(channelName(project, channel));
        return json("{}");
    }

    @POST
    @Path("/{channel}:getVerificationCode")
    public Response getNotificationChannelVerificationCode(@PathParam("project") String project,
            @PathParam("channel") String channel, String body) {
        var request = ProtoJson.merge(body == null || body.isBlank() ? "{}" : body,
                GetNotificationChannelVerificationCodeRequest.newBuilder()).build();
        return json(ProtoJson.print(channels.getVerificationCode(channelName(project, channel),
                request.hasExpireTime() ? request.getExpireTime() : null)));
    }

    @POST
    @Path("/{channel}:verify")
    public Response verifyNotificationChannel(@PathParam("project") String project,
            @PathParam("channel") String channel, String body) {
        var request = ProtoJson.merge(body == null || body.isBlank() ? "{}" : body,
                VerifyNotificationChannelRequest.newBuilder()).build();
        return json(ProtoJson.print(channels.verify(channelName(project, channel), request.getCode())));
    }

    private static String channelName(String project, String channel) {
        return "projects/" + project + "/notificationChannels/" + channel;
    }

    private static FieldMask mask(String updateMask) {
        return FieldMaskUtil.fromJsonString(updateMask == null ? "" : updateMask);
    }

    private static Response json(String body) {
        return Response.ok(body, MediaType.APPLICATION_JSON).build();
    }
}
