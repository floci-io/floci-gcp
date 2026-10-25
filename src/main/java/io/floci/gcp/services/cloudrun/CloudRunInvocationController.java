package io.floci.gcp.services.cloudrun;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.cloudrun.model.CloudRunRuntimeInstance;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.PreDestroy;
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
import jakarta.ws.rs.core.StreamingOutput;
import jakarta.ws.rs.core.UriInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

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
    private final Supplier<RoutingContext> currentRequest;
    private final HttpClient httpClient;
    /** Cuts off response bodies still streaming when their request timeout runs out. */
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "cloudrun-invocation-deadline");
        thread.setDaemon(true);
        return thread;
    });

    @Context
    ContainerRequestContext requestContext;

    @Inject
    public CloudRunInvocationController(CloudRunService cloudRunService, CurrentVertxRequest currentRequest) {
        this(cloudRunService, currentRequest::getCurrent);
    }

    CloudRunInvocationController(CloudRunService cloudRunService, Supplier<RoutingContext> currentRequest) {
        this.cloudRunService = cloudRunService;
        this.currentRequest = currentRequest;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.deadlines.setRemoveOnCancelPolicy(true);
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
        return forward(method, serviceName, instance, pathAndQueryFromRequest(project, location, serviceId, uriInfo),
                body, headers, uriInfo);
    }

    /**
     * Proxies one request to a ready Cloud Run runtime container and streams the response back as the runtime
     * writes it. Shared by the Services and Instances invocation routes; {@code resourceName} only appears in
     * error messages. The request timeout bounds the whole request: headers not received in time fail with 504,
     * and a body still streaming at the deadline is cut off.
     */
    Response forward(String method, String resourceName, CloudRunRuntimeInstance instance, String pathAndQuery,
                     byte[] body, HttpHeaders headers, UriInfo uriInfo) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(instance.requestTimeoutMillis());
        String target = instance.endpointUri(pathAndQuery);
        HttpRequest request = buildRequest(method, target, body, headers, uriInfo, instance.requestTimeoutMillis(),
                instance.ingressH2c());
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return toResponse(response, currentRequest.get(), deadlineNanos, resourceName);
        } catch (HttpTimeoutException e) {
            throw GcpException.deadlineExceeded("Cloud Run runtime request timed out: " + resourceName);
        } catch (IOException e) {
            throw GcpException.badGateway("Cloud Run runtime connection failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw GcpException.unavailable("Cloud Run runtime request interrupted: " + resourceName);
        }
    }

    HttpRequest buildRequest(String method, String target, byte[] body, HttpHeaders headers,
                             UriInfo uriInfo, long timeoutMillis, boolean h2c) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(target))
                .version(h2c ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1)
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

    private Response toResponse(HttpResponse<InputStream> upstream, RoutingContext caller, long deadlineNanos,
                                String resourceName) {
        StreamingOutput body = output -> copyUntilDeadline(upstream.body(), output, caller, deadlineNanos,
                resourceName);
        Response.ResponseBuilder builder = Response.status(upstream.statusCode())
                .entity(body);
        upstream.headers().map().forEach((name, values) -> {
            if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                values.forEach(value -> builder.header(name, value));
            }
        });
        return builder.build();
    }

    /**
     * Copies the runtime's response as it arrives, flushing each chunk so streamed responses (server-sent events,
     * NDJSON) reach the caller as they are written. Like Cloud Run, which closes the connection of a request that
     * outlives its timeout, a body still open at the deadline is cut off (see {@link #abort}). A caller that
     * disconnects closes the upstream stream straight away, even while the runtime is idle between events.
     */
    private void copyUntilDeadline(InputStream input, OutputStream output, RoutingContext caller, long deadlineNanos,
                                   String resourceName) throws IOException {
        AtomicBoolean expired = new AtomicBoolean();
        caller.addEndHandler(ended -> closeQuietly(input));
        ScheduledFuture<?> cutoff = deadlines.schedule(() -> {
            expired.set(true);
            abort(caller, input);
        }, Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        try (input) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                output.flush();
            }
        } catch (IOException e) {
            if (expired.get()) {
                throw new IOException("Cloud Run runtime request timed out: " + resourceName, e);
            }
            throw e;
        } finally {
            cutoff.cancel(false);
        }
        // Closing the stream from the deadline task can end the read with -1 instead of an exception.
        if (expired.get()) {
            throw new IOException("Cloud Run runtime request timed out: " + resourceName);
        }
    }

    /**
     * Aborts the caller's response so it reads as incomplete rather than ended: an HTTP/2 stream is reset, an
     * HTTP/1.x connection is closed. This also releases a write blocked on a caller that stopped reading. The
     * upstream stream is closed only after the HTTP/1.x connection is, because the failing copy makes the server end
     * the response, and that end must not reach the caller first.
     */
    private static void abort(RoutingContext caller, InputStream input) {
        HttpServerRequest request = caller.request();
        if (request.version() == HttpVersion.HTTP_2) {
            caller.response().reset();
            closeQuietly(input);
        } else {
            request.connection().close().onComplete(closed -> closeQuietly(input));
        }
    }

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // The copy loop reports the timeout.
        }
    }

    @PreDestroy
    void shutdown() {
        deadlines.shutdownNow();
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
