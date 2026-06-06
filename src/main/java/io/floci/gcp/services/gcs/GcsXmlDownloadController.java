package io.floci.gcp.services.gcs;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Handles GCS XML API requests: GET/PUT /{bucket}/{object}.
 * Used by Go SDK (STORAGE_EMULATOR_HOST) and for signed URL access.
 */
@ApplicationScoped
@Path("/{bucket: [a-z0-9._-]+}")
public class GcsXmlDownloadController {

    private final GcsService service;
    private final EmulatorConfig config;

    @Inject
    public GcsXmlDownloadController(GcsService service, EmulatorConfig config) {
        this.service = service;
        this.config = config;
    }

    @OPTIONS
    @Path("/{object: .*}")
    public Response options() {
        return Response.ok().build();
    }

    @OPTIONS
    public Response optionsBucket() {
        return Response.ok().build();
    }

    @GET
    @Path("/{object: .+}")
    public Response download(
            @PathParam("bucket") String bucket,
            @PathParam("object") String objectPath,
            @QueryParam("generation") String generation,
            @HeaderParam("x-goog-encryption-key-sha256") String customerEncryptionKeySha256) {
        String objectName = URLDecoder.decode(objectPath, StandardCharsets.UTF_8);
        GcsCustomerEncryption customerEncryption = GcsCustomerEncryption.fromKeySha256(customerEncryptionKeySha256);
        if (generation != null) {
            byte[] data = service.getObjectData(bucket, objectName, generation, customerEncryption);
            GcsObjectMeta meta = service.getObjectMeta(bucket, objectName, generation);
            return Response.ok(data).type(meta.getContentType()).build();
        }
        byte[] data = service.getObjectData(bucket, objectName, customerEncryption);
        GcsObjectMeta meta = service.getObjectMeta(bucket, objectName);
        return Response.ok(data).type(meta.getContentType()).build();
    }

    @PUT
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{object: .+}")
    public Response upload(
            @PathParam("bucket") String bucket,
            @PathParam("object") String objectPath,
            @Context HttpHeaders headers,
            byte[] body) {
        String objectName = URLDecoder.decode(objectPath, StandardCharsets.UTF_8);
        String contentType = headers.getHeaderString(HttpHeaders.CONTENT_TYPE);
        String host = headers.getHeaderString("Host");
        String baseUrl = host != null ? "http://" + host : config.baseUrl();
        GcsObjectMeta meta = service.putObject(bucket, objectName, contentType, body != null ? body : new byte[0],
                GcsCustomerEncryption.fromHeaders(headers), baseUrl);
        return Response.ok(meta).build();
    }
}
