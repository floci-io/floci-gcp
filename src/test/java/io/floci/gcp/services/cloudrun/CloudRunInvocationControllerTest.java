package io.floci.gcp.services.cloudrun;

import com.sun.net.httpserver.HttpServer;
import io.floci.gcp.services.cloudrun.model.CloudRunRuntimeInstance;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CloudRunInvocationControllerTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void proxiesMethodPathQueryHeadersBodyAndResponse() throws IOException {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> pathAndQuery = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> header = new AtomicReference<>();
        AtomicReference<String> forwardedHost = new AtomicReference<>();
        AtomicReference<String> forwardedUri = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            pathAndQuery.set(exchange.getRequestURI().toString());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            header.set(exchange.getRequestHeaders().getFirst("X-Test"));
            forwardedHost.set(exchange.getRequestHeaders().getFirst("X-Forwarded-Host"));
            forwardedUri.set(exchange.getRequestHeaders().getFirst("X-Forwarded-Uri"));
            byte[] response = "proxied".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-Upstream", "ok");
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        CloudRunService service = mock(CloudRunService.class);
        when(service.readyRuntime("projects/p1/locations/us-central1/services/svc"))
                .thenReturn(Optional.of(instance(server.getAddress().getPort())));
        CloudRunInvocationController controller = new CloudRunInvocationController(service);

        ResponseData response = invokePost(controller);

        assertEquals(201, response.status());
        assertEquals("proxied", response.body());
        assertEquals("ok", response.header());
        assertEquals("POST", method.get());
        assertEquals("/extra/path?x=1", pathAndQuery.get());
        assertEquals("payload", body.get());
        assertEquals("yes", header.get());
        assertEquals("localhost:4588", forwardedHost.get());
        assertEquals("/run/v2/projects/p1/locations/us-central1/services/svc/extra/path?x=1", forwardedUri.get());
    }

    @Test
    void proxiesHeadAndOptionsToRuntime() throws IOException {
        List<String> methods = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            methods.add(exchange.getRequestMethod());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        CloudRunService service = mock(CloudRunService.class);
        when(service.readyRuntime("projects/p1/locations/us-central1/services/svc"))
                .thenReturn(Optional.of(instance(server.getAddress().getPort())));
        CloudRunInvocationController controller = new CloudRunInvocationController(service);

        jakarta.ws.rs.core.Response head = controller.head("p1", "us-central1", "svc",
                headers(), uriInfo("/health"));
        jakarta.ws.rs.core.Response options = controller.options("p1", "us-central1", "svc",
                headers(), uriInfo("/health"));

        assertEquals(204, head.getStatus());
        assertEquals(204, options.getStatus());
        assertEquals(List.of("HEAD", "OPTIONS"), methods);
    }

    @Test
    void preservesEncodedPathAndQueryWhenProxying() throws IOException {
        AtomicReference<String> pathAndQuery = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            pathAndQuery.set(exchange.getRequestURI().toString());
            byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        CloudRunService service = mock(CloudRunService.class);
        when(service.readyRuntime("projects/p1/locations/us-central1/services/svc"))
                .thenReturn(Optional.of(instance(server.getAddress().getPort())));
        CloudRunInvocationController controller = new CloudRunInvocationController(service);

        controller.get("p1", "us-central1", "svc",
                headers(), uriInfo("/a%20b/%2Fliteral?x=a%2Bb"));

        assertEquals("/a%20b/%2Fliteral?x=a%2Bb", pathAndQuery.get());
    }

    @Test
    void routedUrlUsesOriginalAppPathAndForwardedHeaders() throws IOException {
        AtomicReference<String> pathAndQuery = new AtomicReference<>();
        AtomicReference<String> forwardedHost = new AtomicReference<>();
        AtomicReference<String> forwardedUri = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            pathAndQuery.set(exchange.getRequestURI().toString());
            forwardedHost.set(exchange.getRequestHeaders().getFirst("X-Forwarded-Host"));
            forwardedUri.set(exchange.getRequestHeaders().getFirst("X-Forwarded-Uri"));
            byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        CloudRunService service = mock(CloudRunService.class);
        when(service.readyRuntime("projects/p1/locations/us-central1/services/svc"))
                .thenReturn(Optional.of(instance(server.getAddress().getPort())));
        CloudRunInvocationController controller = new CloudRunInvocationController(service);
        controller.requestContext = routedRequestContext(
                "svc-f64551fcd6f0.us-central1.run.localhost.floci.io:4588",
                "/api/database?x=1");

        controller.get("p1", "us-central1", "svc",
                headers(), uriInfo("/api/database?x=1"));

        assertEquals("/api/database?x=1", pathAndQuery.get());
        assertEquals("svc-f64551fcd6f0.us-central1.run.localhost.floci.io:4588", forwardedHost.get());
        assertEquals("/api/database?x=1", forwardedUri.get());
    }

    private static ResponseData invokePost(CloudRunInvocationController controller) {
        jakarta.ws.rs.core.Response response = controller.post("p1", "us-central1", "svc",
                "payload".getBytes(StandardCharsets.UTF_8), headers(), uriInfo("/extra/path?x=1"));
        return new ResponseData(response.getStatus(),
                new String((byte[]) response.getEntity(), StandardCharsets.UTF_8),
                response.getHeaderString("X-Upstream"));
    }

    private static CloudRunRuntimeInstance instance(int port) {
        return new CloudRunRuntimeInstance("p1", "us-central1",
                "projects/p1/locations/us-central1/services/svc",
                "projects/p1/locations/us-central1/services/svc/revisions/svc-00001",
                "gcr.io/p1/svc:latest", "container-id", 8080, null, "127.0.0.1", port,
                "http://localhost:4588/run/v2/projects/p1/locations/us-central1/services/svc",
                "READY", 1, 1, null, 300_000);
    }

    private static HttpHeaders headers() {
        HttpHeaders headers = mock(HttpHeaders.class);
        MultivaluedHashMap<String, String> requestHeaders = new MultivaluedHashMap<>();
        requestHeaders.add("X-Test", "yes");
        requestHeaders.add("Content-Type", "text/plain");
        when(headers.getRequestHeaders()).thenReturn(requestHeaders);
        return headers;
    }

    private static UriInfo uriInfo(String pathAndQuery) {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getRequestUri()).thenReturn(URI.create(
                "http://localhost:4588/run/v2/projects/p1/locations/us-central1/services/svc" + pathAndQuery));
        return uriInfo;
    }

    private static ContainerRequestContext routedRequestContext(String host, String pathAndQuery) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getProperty(CloudRunUrlRoutingFilter.ORIGINAL_SCHEME)).thenReturn("http");
        when(ctx.getProperty(CloudRunUrlRoutingFilter.ORIGINAL_AUTHORITY)).thenReturn(host);
        when(ctx.getProperty(CloudRunUrlRoutingFilter.ORIGINAL_PATH_QUERY)).thenReturn(pathAndQuery);
        return ctx;
    }

    private record ResponseData(int status, String body, String header) {}
}
