package io.floci.gcp.services.gcs;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.services.credentials.GcsAuthorizationService;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Handles GCS XML API requests: GET/PUT /{bucket}/{object}.
 * Used by Go SDK (STORAGE_EMULATOR_HOST) and for signed URL access.
 */
@ApplicationScoped
@Path("/{bucket: [a-z0-9._-]+}")
public class GcsXmlDownloadController {

    private final GcsService service;
    private final EmulatorConfig config;
	private final GcsAuthorizationService authorizationService;

    @Inject
	public GcsXmlDownloadController(GcsService service, EmulatorConfig config,
			GcsAuthorizationService authorizationService) {
        this.service = service;
        this.config = config;
		this.authorizationService = authorizationService;
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
            @Context UriInfo uriInfo,
            @QueryParam("generation") String generation,
            @HeaderParam("x-goog-encryption-key-sha256") String customerEncryptionKeySha256,
			@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            @HeaderParam("Range") String rangeHeader) {
        GcsSignedUrl.checkNotExpired(uriInfo);
        authorizationService.requireObjectRead(authorization, bucket, objectPath);
        GcsCustomerEncryption customerEncryption = GcsCustomerEncryption.fromKeySha256(customerEncryptionKeySha256);
        var download = service.getObjectForDownload(bucket, objectPath, generation, customerEncryption);
        return GcsMediaResponses.mediaResponse(download.data(), download.meta(), rangeHeader);
    }

    @PUT
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{object: .+}")
    public Response upload(
            @PathParam("bucket") String bucket,
            @PathParam("object") String objectPath,
            @Context UriInfo uriInfo,
            @Context HttpHeaders headers,
            byte[] body) {
        GcsSignedUrl.checkNotExpired(uriInfo);
        authorizationService.requireObjectWrite(
                headers.getHeaderString(HttpHeaders.AUTHORIZATION), bucket, objectPath);
        String contentType = headers.getHeaderString(HttpHeaders.CONTENT_TYPE);
        String host = headers.getHeaderString("Host");
        String baseUrl = host != null ? "http://" + host : config.baseUrl();
        GcsObjectMeta meta = service.putObject(bucket, objectPath, contentType, body != null ? body : new byte[0],
                GcsCustomerEncryption.fromHeaders(headers), googMetaHeaders(headers), baseUrl);
        return Response.ok(meta).build();
    }

    private static Map<String, String> googMetaHeaders(HttpHeaders headers) {
        var prefix = GcsMediaResponses.META_HEADER_PREFIX;
        var metadata = new LinkedHashMap<String, String>();
        for (var headerName : headers.getRequestHeaders().keySet()) {
            var lower = headerName.toLowerCase(Locale.ROOT);
            if (lower.startsWith(prefix) && lower.length() > prefix.length()) {
                metadata.put(lower.substring(prefix.length()), headers.getHeaderString(headerName));
            }
        }
        return metadata;
    }
}
