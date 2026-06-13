package io.floci.gcp.services.cloudrun;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.cloudrun.model.CloudRunRuntimeInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

@Path("/run/v2/projects/{project}/locations/{location}/services/{serviceId}")
@ApplicationScoped
@Produces(MediaType.WILDCARD)
@Consumes(MediaType.WILDCARD)
public class CloudRunInvocationController {

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "host",
            "content-length");

    private final CloudRunService cloudRunService;
    private final HttpClient httpClient;

    @Context
    ContainerRequestContext requestContext;

    @Inject
    public CloudRunInvocationController(CloudRunService cloudRunService) {
        this.cloudRunService = cloudRunService;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @GET
    public Response getRoot(@PathParam("project") String project,
                            @PathParam("location") String location,
                            @PathParam("serviceId") String serviceId,
                            @Context HttpHeaders headers,
                            @Context UriInfo uriInfo) {
        return proxy("GET", project, location, serviceId, new byte[0], headers, uriInfo);
    }

    @GET
    @Path("/{path: .*}")
    public Response get(@PathParam("project") String project,
                        @PathParam("location") String location,
                        @PathParam("serviceId") String serviceId,
                        @Context HttpHeaders headers,
                        @Context UriInfo uriInfo) {
        return proxy("GET", project, location, serviceId, new byte[0], headers, uriInfo);
    }

    @HEAD
    public Response headRoot(@PathParam("project") String project,
                             @PathParam("location") String location,
                             @PathParam("serviceId") String serviceId,
                             @Context HttpHeaders headers,
                             @Context UriInfo uriInfo) {
        return proxy("HEAD", project, location, serviceId, new byte[0], headers, uriInfo);
    }

    @HEAD
    @Path("/{path: .*}")
    public Response head(@PathParam("project") String project,
                         @PathParam("location") String location,
                         @PathParam("serviceId") String serviceId,
                         @Context HttpHeaders headers,
                         @Context UriInfo uriInfo) {
        return proxy("HEAD", project, location, serviceId, new byte[0], headers, uriInfo);
    }

    @OPTIONS
    public Response optionsRoot(@PathParam("project") String project,
                                @PathParam("location") String location,
                                @PathParam("serviceId") String serviceId,
                                @Context HttpHeaders headers,
                                @Context UriInfo uriInfo) {
        return proxy("OPTIONS", project, location, serviceId, new byte[0], headers, uriInfo);
    }

    @OPTIONS
    @Path("/{path: .*}")
    public Response options(@PathParam("project") String project,
                            @PathParam("location") String location,
                            @PathParam("serviceId") String serviceId,
                            @Context HttpHeaders headers,
                            @Context UriInfo uriInfo) {
        return proxy("OPTIONS", project, location, serviceId, new byte[0], headers, uriInfo);
    }

    @POST
    public Response postRoot(@PathParam("project") String project, @PathParam("location") String location,
                             @PathParam("serviceId") String serviceId, byte[] body,
                             @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        return proxy("POST", project, location, serviceId, body, headers, uriInfo);
    }

    @POST
    @Path("/{path: .*}")
    public Response post(@PathParam("project") String project, @PathParam("location") String location,
                         @PathParam("serviceId") String serviceId, byte[] body,
                         @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        return proxy("POST", project, location, serviceId, body, headers, uriInfo);
    }

    @PUT
    public Response putRoot(@PathParam("project") String project, @PathParam("location") String location,
                            @PathParam("serviceId") String serviceId, byte[] body,
                            @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        return proxy("PUT", project, location, serviceId, body, headers, uriInfo);
    }

    @PUT
    @Path("/{path: .*}")
    public Response put(@PathParam("project") String project, @PathParam("location") String location,
                        @PathParam("serviceId") String serviceId, byte[] body,
                        @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        return proxy("PUT", project, location, serviceId, body, headers, uriInfo);
    }

    @PATCH
    public Response patchRoot(@PathParam("project") String project, @PathParam("location") String location,
                              @PathParam("serviceId") String serviceId, byte[] body,
                              @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        return proxy("PATCH", project, location, serviceId, body, headers, uriInfo);
    }

    @PATCH
    @Path("/{path: .*}")
    public Response patch(@PathParam("project") String project, @PathParam("location") String location,
                          @PathParam("serviceId") String serviceId, byte[] body,
                          @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        return proxy("PATCH", project, location, serviceId, body, headers, uriInfo);
    }

    @DELETE
    public Response deleteRoot(@PathParam("project") String project, @PathParam("location") String location,
                               @PathParam("serviceId") String serviceId, byte[] body,
                               @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        return proxy("DELETE", project, location, serviceId, body, headers, uriInfo);
    }

    @DELETE
    @Path("/{path: .*}")
    public Response delete(@PathParam("project") String project, @PathParam("location") String location,
                           @PathParam("serviceId") String serviceId, byte[] body,
                           @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        return proxy("DELETE", project, location, serviceId, body, headers, uriInfo);
    }

    private Response proxy(String method, String project, String location, String serviceId,
                           byte[] body, HttpHeaders headers, UriInfo uriInfo) {
        String serviceName = "projects/" + project + "/locations/" + location + "/services/" + serviceId;
        CloudRunRuntimeInstance instance = cloudRunService.readyRuntime(serviceName)
                .orElseThrow(() -> GcpException.unavailable("Cloud Run service has no ready runtime: " + serviceName));
        String target = instance.endpointUri(pathAndQueryFromRequest(project, location, serviceId, uriInfo));
        HttpRequest request = buildRequest(method, target, body, headers, uriInfo, instance.requestTimeoutMillis());
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return toResponse(response);
        } catch (HttpTimeoutException e) {
            throw GcpException.deadlineExceeded("Cloud Run runtime request timed out: " + serviceName);
        } catch (IOException e) {
            throw GcpException.badGateway("Cloud Run runtime connection failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw GcpException.unavailable("Cloud Run runtime request interrupted: " + serviceName);
        }
    }

    private HttpRequest buildRequest(String method, String target, byte[] body, HttpHeaders headers,
                                     UriInfo uriInfo, long timeoutMillis) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(target))
                .timeout(Duration.ofMillis(timeoutMillis));
        headers.getRequestHeaders().forEach((name, values) -> {
            if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                values.forEach(value -> builder.header(name, value));
            }
        });
        builder.header("X-Forwarded-Proto", contextProperty(CloudRunUrlRoutingFilter.ORIGINAL_SCHEME,
                uriInfo.getRequestUri().getScheme()));
        builder.header("X-Forwarded-Host", contextProperty(CloudRunUrlRoutingFilter.ORIGINAL_AUTHORITY,
                uriInfo.getRequestUri().getAuthority()));
        builder.header("X-Forwarded-Uri", contextProperty(CloudRunUrlRoutingFilter.ORIGINAL_PATH_QUERY,
                rawPathAndQuery(uriInfo)));
        builder.method(method, requestBody(body));
        return builder.build();
    }

    private Response toResponse(HttpResponse<byte[]> upstream) {
        Response.ResponseBuilder builder = Response.status(upstream.statusCode())
                .entity(upstream.body());
        upstream.headers().map().forEach((name, values) -> {
            if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                values.forEach(value -> builder.header(name, value));
            }
        });
        return builder.build();
    }

    private static HttpRequest.BodyPublisher requestBody(byte[] body) {
        if (body == null || body.length == 0) {
            return HttpRequest.BodyPublishers.noBody();
        }
        return HttpRequest.BodyPublishers.ofByteArray(body);
    }

    private static String pathAndQuery(String project, String location, String serviceId, UriInfo uriInfo) {
        String rawPath = rawPathAndQuery(uriInfo);
        int queryStart = rawPath.indexOf('?');
        if (queryStart >= 0) {
            rawPath = rawPath.substring(0, queryStart);
        }
        String prefix = "/run/v2/projects/" + project + "/locations/" + location + "/services/" + serviceId;
        String suffix = "/";
        if (rawPath.startsWith(prefix + "/")) {
            suffix = rawPath.substring(prefix.length());
        } else if (!rawPath.equals(prefix)) {
            throw GcpException.invalidArgument("Cloud Run invocation path does not match service path");
        }
        StringBuilder result = new StringBuilder(suffix);
        String query = uriInfo.getRequestUri().getRawQuery();
        if (query != null && !query.isBlank()) {
            result.append('?').append(query);
        }
        return result.toString();
    }

    private String pathAndQueryFromRequest(String project, String location, String serviceId, UriInfo uriInfo) {
        String routedPath = contextProperty(CloudRunUrlRoutingFilter.ORIGINAL_PATH_QUERY, null);
        return routedPath != null ? routedPath : pathAndQuery(project, location, serviceId, uriInfo);
    }

    private String contextProperty(String name, String fallback) {
        if (requestContext == null) {
            return fallback;
        }
        Object value = requestContext.getProperty(name);
        return value instanceof String text ? text : fallback;
    }

    private static String rawPathAndQuery(UriInfo uriInfo) {
        String rawPath = uriInfo.getRequestUri().getRawPath();
        String query = uriInfo.getRequestUri().getRawQuery();
        if (query == null || query.isBlank()) {
            return rawPath;
        }
        return rawPath + "?" + query;
    }
}
