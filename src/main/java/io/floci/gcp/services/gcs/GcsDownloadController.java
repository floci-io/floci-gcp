package io.floci.gcp.services.gcs;

import io.floci.gcp.services.credentials.GcsAuthorizationService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
@Path("/download/storage/v1/b/{bucket}/o")
public class GcsDownloadController {

    private final GcsService service;
    private final GcsAuthorizationService authorizationService;

    @Inject
    public GcsDownloadController(GcsService service, GcsAuthorizationService authorizationService) {
        this.service = service;
        this.authorizationService = authorizationService;
    }

    @GET
    @Path("/{object: .+}")
    public Response download(
            @PathParam("bucket") String bucket,
            @PathParam("object") String objectPath,
            @QueryParam("generation") String generation,
            @HeaderParam("x-goog-encryption-key-sha256") String customerEncryptionKeySha256,
            @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            @HeaderParam("Range") String rangeHeader) {
        authorizationService.requireObjectRead(authorization, bucket, objectPath);
        GcsCustomerEncryption customerEncryption = GcsCustomerEncryption.fromKeySha256(customerEncryptionKeySha256);
        var download = service.getObjectForDownload(bucket, objectPath, generation, customerEncryption);
        return GcsMediaResponses.mediaResponse(download.data(), download.meta(), rangeHeader);
    }
}
