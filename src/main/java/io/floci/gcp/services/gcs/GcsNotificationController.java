package io.floci.gcp.services.gcs;

import io.floci.gcp.services.iam.GcsIamAuthorizationService;
import io.floci.gcp.services.gcs.model.StoredNotification;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

@ApplicationScoped
@Path("/storage/v1/b/{bucket}/notificationConfigs")
@Produces(MediaType.APPLICATION_JSON)
public class GcsNotificationController {

    private final GcsService service;
    private final GcsIamAuthorizationService iamAuthorizationService;

    @Inject
    public GcsNotificationController(GcsService service, GcsIamAuthorizationService iamAuthorizationService) {
        this.service = service;
        this.iamAuthorizationService = iamAuthorizationService;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
	public Response createNotification(@PathParam("bucket") String bucket,
			@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization, Map<String, Object> body) {
		iamAuthorizationService.requireBucketPermission(authorization, bucket, "storage.buckets.update");
        return Response.ok(service.createNotification(bucket, body)).build();
    }

    @GET
	public Response listNotifications(@PathParam("bucket") String bucket,
			@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization) {
		iamAuthorizationService.requireBucketPermission(authorization, bucket, "storage.buckets.get");
        List<StoredNotification> items = service.listNotifications(bucket);
        return Response.ok(Map.of("kind", "storage#notifications", "items", items)).build();
    }

    @GET
    @Path("/{notification}")
    public Response getNotification(@PathParam("bucket") String bucket,
			@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            @PathParam("notification") String notificationId) {
		iamAuthorizationService.requireBucketPermission(authorization, bucket, "storage.buckets.get");
        return Response.ok(service.getNotification(bucket, notificationId)).build();
    }

    @DELETE
    @Path("/{notification}")
    public Response deleteNotification(@PathParam("bucket") String bucket,
			@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            @PathParam("notification") String notificationId) {
		iamAuthorizationService.requireBucketPermission(authorization, bucket, "storage.buckets.update");
        service.deleteNotification(bucket, notificationId);
        return Response.noContent().build();
    }
}
