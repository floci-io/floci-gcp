package io.floci.gcp.services.cloudlogging;

import com.google.logging.v2.LogSink;
import com.google.protobuf.FieldMask;
import com.google.protobuf.util.FieldMaskUtil;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ProtoJson;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v2/{parentType:projects|organizations|folders|billingAccounts}/{parentId}/sinks")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CloudLoggingConfigHttpController {

    private final CloudLoggingSinkService service;

    @Inject
    public CloudLoggingConfigHttpController(CloudLoggingSinkService service) {
        this.service = service;
    }

    @POST
    public Response createSink(@PathParam("parentType") String parentType, @PathParam("parentId") String parentId,
            @QueryParam("uniqueWriterIdentity") @DefaultValue("false") boolean uniqueWriterIdentity,
            @QueryParam("customWriterIdentity") String customWriterIdentity, String body) {
        rejectCustomWriterIdentity(customWriterIdentity);
        LogSink sink = ProtoJson.merge(body, LogSink.newBuilder()).build();
        return json(ProtoJson.print(service.createSink(parentType + "/" + parentId, sink, uniqueWriterIdentity)));
    }

    @GET
    public Response listSinks(@PathParam("parentType") String parentType, @PathParam("parentId") String parentId,
            @QueryParam("pageSize") @DefaultValue("0") int pageSize, @QueryParam("pageToken") String pageToken) {
        return json(ProtoJson.print(service.listSinks(parentType + "/" + parentId, pageSize, pageToken)));
    }

    @GET
    @Path("/{sinkId}")
    public Response getSink(@PathParam("parentType") String parentType, @PathParam("parentId") String parentId,
            @PathParam("sinkId") String sinkId) {
        return json(ProtoJson.print(service.getSink(parentType + "/" + parentId + "/sinks/" + sinkId)));
    }

    @PUT
    @Path("/{sinkId}")
    public Response updateSink(@PathParam("parentType") String parentType, @PathParam("parentId") String parentId,
            @PathParam("sinkId") String sinkId, @QueryParam("updateMask") String updateMask,
            @QueryParam("uniqueWriterIdentity") @DefaultValue("false") boolean uniqueWriterIdentity,
            @QueryParam("customWriterIdentity") String customWriterIdentity, String body) {
        rejectCustomWriterIdentity(customWriterIdentity);
        FieldMask mask = FieldMaskUtil.fromJsonString(updateMask == null ? "" : updateMask);
        LogSink sink = ProtoJson.merge(body, LogSink.newBuilder()).build();
        return json(ProtoJson.print(service.updateSink(parentType + "/" + parentId + "/sinks/" + sinkId,
                sink, mask, uniqueWriterIdentity)));
    }

    @PATCH
    @Path("/{sinkId}")
    public Response patchSink(@PathParam("parentType") String parentType, @PathParam("parentId") String parentId,
            @PathParam("sinkId") String sinkId, @QueryParam("updateMask") String updateMask,
            @QueryParam("uniqueWriterIdentity") @DefaultValue("false") boolean uniqueWriterIdentity,
            @QueryParam("customWriterIdentity") String customWriterIdentity, String body) {
        return updateSink(parentType, parentId, sinkId, updateMask, uniqueWriterIdentity, customWriterIdentity, body);
    }

    @DELETE
    @Path("/{sinkId}")
    public Response deleteSink(@PathParam("parentType") String parentType, @PathParam("parentId") String parentId,
            @PathParam("sinkId") String sinkId) {
        service.deleteSink(parentType + "/" + parentId + "/sinks/" + sinkId);
        return json("{}");
    }

    private static void rejectCustomWriterIdentity(String customWriterIdentity) {
        if (customWriterIdentity != null && !customWriterIdentity.isEmpty()) {
            throw GcpException.unimplemented("Custom sink writer identities are not supported");
        }
    }

    private static Response json(String body) {
        return Response.ok(body, MediaType.APPLICATION_JSON).build();
    }
}
