package io.floci.gcp.services.gcs;

import io.floci.gcp.services.gcs.model.GcsObjectMeta;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@ApplicationScoped
@Path("/download/storage/v1/b/{bucket}/o")
public class GcsDownloadController {

    private final GcsService service;

    @Inject
    public GcsDownloadController(GcsService service) {
        this.service = service;
    }

    @GET
    @Path("/{object: .+}")
    public Response download(
            @PathParam("bucket") String bucket,
            @PathParam("object") String objectPath,
            @QueryParam("generation") String generation) {
        String objectName = URLDecoder.decode(objectPath, StandardCharsets.UTF_8);
        if (generation != null) {
            byte[] data = service.getObjectData(bucket, objectName, generation);
            GcsObjectMeta meta = service.getObjectMeta(bucket, objectName, generation);
            return Response.ok(data).type(meta.getContentType()).build();
        }
        byte[] data = service.getObjectData(bucket, objectName);
        GcsObjectMeta meta = service.getObjectMeta(bucket, objectName);
        return Response.ok(data).type(meta.getContentType()).build();
    }
}
