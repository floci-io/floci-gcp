package io.floci.gcp.services.cloudfunctions;

import com.google.cloud.functions.v2.GenerateDownloadUrlRequest;
import com.google.cloud.functions.v2.GenerateUploadUrlRequest;
import com.google.iam.v1.SetIamPolicyRequest;
import com.google.iam.v1.TestIamPermissionsRequest;
import io.floci.gcp.config.EmulatorConfig;
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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v2/projects/{project}/locations/{location}")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CloudFunctionsController {

    private final CloudFunctionsService service;
    private final EmulatorConfig config;

    @Inject
    public CloudFunctionsController(CloudFunctionsService service, EmulatorConfig config) {
        this.service = service;
        this.config = config;
    }

    @POST
    @Path("/functions")
    public Response createFunction(@PathParam("project") String project,
                                   @PathParam("location") String location,
                                   @QueryParam("functionId") String functionId,
                                   @QueryParam("validateOnly") @DefaultValue("false") boolean validateOnly,
                                   String body) {
        return json(ProtoJson.print(service.createFunction(project, location, functionId, body, validateOnly)));
    }

    @GET
    @Path("/functions")
    public Response listFunctions(@PathParam("project") String project,
                                  @PathParam("location") String location,
                                  @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                                  @QueryParam("pageToken") String pageToken) {
        return json(ProtoJson.print(service.listFunctions(project, location, pageSize, pageToken)));
    }

    @GET
    @Path("/functions/{functionId}")
    public Response getFunction(@PathParam("project") String project,
                                @PathParam("location") String location,
                                @PathParam("functionId") String functionId) {
        return json(ProtoJson.print(service.getFunction(functionName(project, location, functionId))));
    }

    @PATCH
    @Path("/functions/{functionId}")
    public Response updateFunction(@PathParam("project") String project,
                                   @PathParam("location") String location,
                                   @PathParam("functionId") String functionId,
                                   @QueryParam("updateMask") String updateMask,
                                   String body) {
        return json(ProtoJson.print(service.updateFunction(functionName(project, location, functionId), body, updateMask)));
    }

    @GET
    @Path("/functions/{functionId}:getIamPolicy")
    public Response getIamPolicy(@PathParam("project") String project,
                                 @PathParam("location") String location,
                                 @PathParam("functionId") String functionId) {
        return json(ProtoJson.print(service.getIamPolicy(functionName(project, location, functionId))));
    }

    @POST
    @Path("/functions/{functionId}:setIamPolicy")
    public Response setIamPolicy(@PathParam("project") String project,
                                 @PathParam("location") String location,
                                 @PathParam("functionId") String functionId,
                                 String body) {
        SetIamPolicyRequest request = ProtoJson.merge(body, SetIamPolicyRequest.newBuilder()).build();
        return json(ProtoJson.print(service.setIamPolicy(functionName(project, location, functionId), request.getPolicy())));
    }

    @POST
    @Path("/functions/{functionId}:testIamPermissions")
    public Response testIamPermissions(@PathParam("project") String project,
                                       @PathParam("location") String location,
                                       @PathParam("functionId") String functionId,
                                       String body) {
        TestIamPermissionsRequest request = ProtoJson.merge(body, TestIamPermissionsRequest.newBuilder()).build();
        return json(ProtoJson.print(service.testIamPermissions(functionName(project, location, functionId),
                request.getPermissionsList())));
    }

    @DELETE
    @Path("/functions/{functionId}")
    public Response deleteFunction(@PathParam("project") String project,
                                   @PathParam("location") String location,
                                   @PathParam("functionId") String functionId,
                                   @QueryParam("validateOnly") @DefaultValue("false") boolean validateOnly) {
        return json(ProtoJson.print(service.deleteFunction(functionName(project, location, functionId), validateOnly)));
    }

    @POST
    @Path("/functions:generateUploadUrl")
    public Response generateUploadUrl(@PathParam("project") String project,
                                      @PathParam("location") String location,
                                      @Context HttpHeaders headers,
                                      String body) {
        ProtoJson.merge(body, GenerateUploadUrlRequest.newBuilder()).build();
        return json(ProtoJson.print(service.generateUploadUrl(project, location, requestBaseUrl(headers, config.effectiveBaseUrl()))));
    }

    @POST
    @Path("/functions/{functionId}:generateDownloadUrl")
    public Response generateDownloadUrl(@PathParam("project") String project,
                                        @PathParam("location") String location,
                                        @PathParam("functionId") String functionId,
                                        @Context HttpHeaders headers,
                                        String body) {
        ProtoJson.merge(body, GenerateDownloadUrlRequest.newBuilder()).build();
        return json(ProtoJson.print(service.generateDownloadUrl(functionName(project, location, functionId),
                requestBaseUrl(headers, config.effectiveBaseUrl()))));
    }

    private static Response json(String json) {
        return Response.ok(json, MediaType.APPLICATION_JSON).build();
    }

    private static String functionName(String project, String location, String functionId) {
        return "projects/" + project + "/locations/" + location + "/functions/" + functionId;
    }

    static String requestBaseUrl(HttpHeaders headers, String fallback) {
        String forwarded = firstHeader(headers, "Forwarded");
        String proto = firstPresent(firstHeader(headers, "X-Forwarded-Proto"), forwardedValue(forwarded, "proto"));
        String host = firstPresent(firstHeader(headers, "X-Forwarded-Host"), forwardedValue(forwarded, "host"));
        if (host != null) {
            return firstPresent(proto, scheme(fallback), "http") + "://" + host;
        }
        if (proto != null && fallback.contains("://")) {
            return proto + fallback.substring(fallback.indexOf("://"));
        }
        return fallback;
    }

    private static String firstHeader(HttpHeaders headers, String name) {
        String value = headers.getHeaderString(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        int comma = value.indexOf(',');
        return (comma < 0 ? value : value.substring(0, comma)).trim();
    }

    private static String forwardedValue(String forwarded, String key) {
        if (forwarded == null) {
            return null;
        }
        for (String part : forwarded.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].trim().equalsIgnoreCase(key)) {
                return unquote(kv[1].trim());
            }
        }
        return null;
    }

    private static String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String scheme(String url) {
        int delimiter = url.indexOf("://");
        return delimiter < 0 ? null : url.substring(0, delimiter);
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
