package io.floci.gcp.services.cloudrun;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.cloudrun.model.CloudRunRuntimeInstance;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CloudRunInvocationControllerTest {

    private static final byte[] CONNECTED = ": connected\n\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HEARTBEAT = ": heartbeat\n\n".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private final ExecutorService upstreamThreads = Executors.newCachedThreadPool();
    private final CountDownLatch releaseUpstream = new CountDownLatch(1);
    private final RoutingContext caller = mock(RoutingContext.class);
    private final HttpServerRequest callerRequest = mock(HttpServerRequest.class);
    private final HttpServerResponse callerResponse = mock(HttpServerResponse.class);
    private final HttpConnection callerConnection = mock(HttpConnection.class);
    private final List<Handler<AsyncResult<Void>>> callerEndHandlers = new ArrayList<>();

    @BeforeEach
    void callerConnection() {
        when(caller.request()).thenReturn(callerRequest);
        when(caller.response()).thenReturn(callerResponse);
        when(callerRequest.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(callerRequest.connection()).thenReturn(callerConnection);
        when(callerConnection.close()).thenReturn(Future.succeededFuture());
        when(caller.addEndHandler(any())).thenAnswer(invocation -> {
            callerEndHandlers.add(invocation.getArgument(0));
            return callerEndHandlers.size();
        });
    }

    @AfterEach
    void tearDown() {
        releaseUpstream.countDown();
        if (server != null) {
            server.stop(0);
        }
        upstreamThreads.shutdownNow();
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
        CloudRunInvocationController controller = new CloudRunInvocationController(service, () -> caller);

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
        CloudRunInvocationController controller = new CloudRunInvocationController(service, () -> caller);

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
        CloudRunInvocationController controller = new CloudRunInvocationController(service, () -> caller);

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
        CloudRunInvocationController controller = new CloudRunInvocationController(service, () -> caller);
        controller.requestContext = routedRequestContext(
                "svc-f64551fcd6f0.us-central1.run.localhost.floci.io:4588",
                "/api/database?x=1");

        controller.get("p1", "us-central1", "svc",
                headers(), uriInfo("/api/database?x=1"));

        assertEquals("/api/database?x=1", pathAndQuery.get());
        assertEquals("svc-f64551fcd6f0.us-central1.run.localhost.floci.io:4588", forwardedHost.get());
        assertEquals("/api/database?x=1", forwardedUri.get());
    }

    @Test
    void selectsRuntimeProtocolFromIngressConfiguration() {
        CloudRunInvocationController controller = new CloudRunInvocationController(mock(CloudRunService.class), () -> caller);

        assertEquals(HttpClient.Version.HTTP_1_1, controller.buildRequest("GET", "http://127.0.0.1:8080/",
                new byte[0], headers(), uriInfo("/"), 300_000, false).version().orElseThrow());
        assertEquals(HttpClient.Version.HTTP_2, controller.buildRequest("GET", "http://127.0.0.1:8080/",
                new byte[0], headers(), uriInfo("/"), 300_000, true).version().orElseThrow());
    }

    @Test
    void streamsEachEventBeforeUpstreamCompletes() throws Exception {
        CountDownLatch sendHeartbeat = new CountDownLatch(1);
        startUpstream(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            writeAndFlush(exchange, CONNECTED);
            await(sendHeartbeat);
            writeAndFlush(exchange, HEARTBEAT);
            await(releaseUpstream);
        });
        CloudRunInvocationController controller = controller(300_000);

        Response response = controller.get("p1", "us-central1", "svc", headers(), uriInfo("/events"));
        assertEquals(200, response.getStatus());
        assertEquals("text/event-stream", response.getHeaderString("Content-Type"));
        RecordingOutput downstream = new RecordingOutput(2);
        CompletableFuture<Void> body = writeAsync(response, downstream);

        assertTrue(downstream.awaitChunks(1));
        assertEquals(": connected\n\n", downstream.text());
        sendHeartbeat.countDown();
        assertTrue(downstream.awaitChunks(2));
        assertEquals(": connected\n\n: heartbeat\n\n", downstream.text());
        assertFalse(body.isDone());

        releaseUpstream.countDown();
        body.get(5, TimeUnit.SECONDS);
    }

    @Test
    void cutsOffResponseStillStreamingAtRequestTimeout() throws Exception {
        startUpstream(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            writeAndFlush(exchange, CONNECTED);
            await(releaseUpstream);
        });
        CloudRunInvocationController controller = controller(500);

        Response response = controller.get("p1", "us-central1", "svc", headers(), uriInfo("/events"));
        RecordingOutput downstream = new RecordingOutput(1);
        CompletableFuture<Void> body = writeAsync(response, downstream);

        ExecutionException failure = assertThrows(ExecutionException.class, () -> body.get(5, TimeUnit.SECONDS));
        IOException cutOff = assertInstanceOf(IOException.class, failure.getCause());
        assertEquals("Cloud Run runtime request timed out: projects/p1/locations/us-central1/services/svc",
                cutOff.getMessage());
        assertEquals(": connected\n\n", downstream.text());
        verify(callerConnection).close();
    }

    @Test
    void resetsAnHttp2StreamInsteadOfClosingTheConnection() throws Exception {
        when(callerRequest.version()).thenReturn(HttpVersion.HTTP_2);
        startUpstream(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            writeAndFlush(exchange, CONNECTED);
            await(releaseUpstream);
        });
        CloudRunInvocationController controller = controller(500);

        Response response = controller.get("p1", "us-central1", "svc", headers(), uriInfo("/events"));
        CompletableFuture<Void> body = writeAsync(response, new RecordingOutput(1));

        assertThrows(ExecutionException.class, () -> body.get(5, TimeUnit.SECONDS));
        verify(callerResponse).reset();
        verify(callerConnection, never()).close();
    }

    @Test
    void resetsCallerBlockedOnAWriteAtRequestTimeout() throws Exception {
        startUpstream(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            writeAndFlush(exchange, CONNECTED);
            await(releaseUpstream);
        });
        CountDownLatch reset = new CountDownLatch(1);
        when(callerConnection.close()).thenAnswer(invocation -> {
            reset.countDown();
            return Future.succeededFuture();
        });
        CloudRunInvocationController controller = controller(500);
        OutputStream stalledCaller = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                write(new byte[] {(byte) value}, 0, 1);
            }

            @Override
            public void write(byte[] values, int offset, int length) throws IOException {
                await(reset);
                throw new IOException("connection reset");
            }
        };

        Response response = controller.get("p1", "us-central1", "svc", headers(), uriInfo("/events"));
        CompletableFuture<Void> body = writeAsync(response, stalledCaller);

        ExecutionException failure = assertThrows(ExecutionException.class, () -> body.get(5, TimeUnit.SECONDS));
        assertTrue(failure.getCause().getMessage().contains("timed out"), failure.getCause().getMessage());
    }

    @Test
    void closesIdleUpstreamWhenCallerConnectionEnds() throws Exception {
        CountDownLatch upstreamClosed = new CountDownLatch(1);
        try (ServerSocket upstream = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            CompletableFuture.runAsync(() -> {
                try (Socket socket = upstream.accept()) {
                    InputStream in = socket.getInputStream();
                    readRequestHead(in);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                            + "Transfer-Encoding: chunked\r\n\r\nd\r\n: connected\n\n\r\n")
                            .getBytes(StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().flush();
                    if (in.read() == -1) {
                        upstreamClosed.countDown();
                    }
                } catch (IOException e) {
                    upstreamClosed.countDown();
                }
            }, upstreamThreads);
            CloudRunService service = mock(CloudRunService.class);
            when(service.readyRuntime("projects/p1/locations/us-central1/services/svc"))
                    .thenReturn(Optional.of(instance(upstream.getLocalPort(), 300_000)));
            CloudRunInvocationController controller = new CloudRunInvocationController(service, () -> caller);

            Response response = controller.get("p1", "us-central1", "svc", headers(), uriInfo("/events"));
            RecordingOutput downstream = new RecordingOutput(1);
            CompletableFuture<Void> body = writeAsync(response, downstream);
            assertTrue(downstream.awaitChunks(1));

            callerEndHandlers.forEach(handler -> handler.handle(null));

            assertTrue(upstreamClosed.await(5, TimeUnit.SECONDS));
            try {
                body.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                // A closed upstream may end the copy with an error; either way the copy must finish.
            }
            assertTrue(body.isDone());
        }
    }

    /** Reads an HTTP request up to the blank line that ends its headers. */
    private static void readRequestHead(InputStream in) throws IOException {
        int matched = 0;
        byte[] end = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        int next;
        while (matched < end.length && (next = in.read()) != -1) {
            matched = next == end[matched] ? matched + 1 : (next == end[0] ? 1 : 0);
        }
    }

    @Test
    void failsWithDeadlineExceededWhenHeadersMissTheRequestTimeout() throws IOException {
        startUpstream(exchange -> {
            await(releaseUpstream);
            exchange.sendResponseHeaders(200, -1);
        });
        CloudRunInvocationController controller = controller(300);

        GcpException error = assertThrows(GcpException.class,
                () -> controller.get("p1", "us-central1", "svc", headers(), uriInfo("/slow")));

        assertEquals(504, error.getHttpStatus());
        assertEquals("DEADLINE_EXCEEDED", error.getGcpStatus());
    }

    @Test
    void closesUpstreamWhenCallerDisconnects() throws Exception {
        CountDownLatch upstreamWriteFailed = new CountDownLatch(1);
        startUpstream(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            byte[] chunk = new byte[8192];
            try {
                while (true) {
                    exchange.getResponseBody().write(chunk);
                    exchange.getResponseBody().flush();
                }
            } catch (IOException e) {
                upstreamWriteFailed.countDown();
            }
        });
        CloudRunInvocationController controller = controller(300_000);

        Response response = controller.get("p1", "us-central1", "svc", headers(), uriInfo("/stream"));
        OutputStream disconnected = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("caller disconnected");
            }

            @Override
            public void write(byte[] values, int offset, int length) throws IOException {
                throw new IOException("caller disconnected");
            }
        };

        assertThrows(IOException.class, () -> ((StreamingOutput) response.getEntity()).write(disconnected));
        assertTrue(upstreamWriteFailed.await(5, TimeUnit.SECONDS));
    }

    @Test
    void streamsInstanceInvocations() throws Exception {
        startUpstream(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, 0);
            writeAndFlush(exchange, "{\"n\":1}\n".getBytes(StandardCharsets.UTF_8));
            await(releaseUpstream);
        });
        CloudRunInstancesService instances = mock(CloudRunInstancesService.class);
        when(instances.readyRuntime("projects/p1/locations/us-central1/instances/inst"))
                .thenReturn(Optional.of(instance(server.getAddress().getPort(), 300_000)));
        CloudRunInstanceInvocationController controller = new CloudRunInstanceInvocationController(instances,
                new CloudRunInvocationController(mock(CloudRunService.class), () -> caller));

        Response response = controller.get("p1", "us-central1", "inst", "rows", headers(), uriInfo("/rows"));
        RecordingOutput downstream = new RecordingOutput(1);
        CompletableFuture<Void> body = writeAsync(response, downstream);

        assertTrue(downstream.awaitChunks(1));
        assertEquals("{\"n\":1}\n", downstream.text());
        assertFalse(body.isDone());
        releaseUpstream.countDown();
        body.get(5, TimeUnit.SECONDS);
    }

    private void startUpstream(UpstreamHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(upstreamThreads);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private CloudRunInvocationController controller(long requestTimeoutMillis) {
        CloudRunService service = mock(CloudRunService.class);
        when(service.readyRuntime("projects/p1/locations/us-central1/services/svc"))
                .thenReturn(Optional.of(instance(server.getAddress().getPort(), requestTimeoutMillis)));
        return new CloudRunInvocationController(service, () -> caller);
    }

    private static void writeAndFlush(HttpExchange exchange, byte[] bytes) throws IOException {
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().flush();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private CompletableFuture<Void> writeAsync(Response response, OutputStream output) {
        StreamingOutput stream = (StreamingOutput) response.getEntity();
        return CompletableFuture.runAsync(() -> {
            try {
                stream.write(output);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, upstreamThreads);
    }

    private static ResponseData invokePost(CloudRunInvocationController controller) {
        Response response = controller.post("p1", "us-central1", "svc",
                "payload".getBytes(StandardCharsets.UTF_8), headers(), uriInfo("/extra/path?x=1"));
        return new ResponseData(response.getStatus(), readBody(response), response.getHeaderString("X-Upstream"));
    }

    private static String readBody(Response response) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            ((StreamingOutput) response.getEntity()).write(output);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static CloudRunRuntimeInstance instance(int port) {
        return instance(port, 300_000);
    }

    private static CloudRunRuntimeInstance instance(int port, long requestTimeoutMillis) {
        return new CloudRunRuntimeInstance("p1", "us-central1",
                "projects/p1/locations/us-central1/services/svc",
                "projects/p1/locations/us-central1/services/svc/revisions/svc-00001",
                "gcr.io/p1/svc:latest", "container-id", 8080, null, "127.0.0.1", port,
                "http://localhost:4588/run/v2/projects/p1/locations/us-central1/services/svc",
                "READY", 1, 1, null, requestTimeoutMillis);
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

    @FunctionalInterface
    private interface UpstreamHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    /** Records what the proxy writes downstream and counts the chunks it flushed. */
    private static final class RecordingOutput extends OutputStream {

        private final ByteArrayOutputStream received = new ByteArrayOutputStream();
        private final CountDownLatch[] chunks;
        private int flushed;

        RecordingOutput(int expectedChunks) {
            chunks = new CountDownLatch[expectedChunks];
            for (int i = 0; i < expectedChunks; i++) {
                chunks[i] = new CountDownLatch(1);
            }
        }

        @Override
        public synchronized void write(int value) {
            received.write(value);
        }

        @Override
        public synchronized void write(byte[] values, int offset, int length) {
            received.write(values, offset, length);
        }

        @Override
        public synchronized void flush() {
            if (flushed < chunks.length) {
                chunks[flushed++].countDown();
            }
        }

        boolean awaitChunks(int count) throws InterruptedException {
            return chunks[count - 1].await(5, TimeUnit.SECONDS);
        }

        synchronized String text() {
            return received.toString(StandardCharsets.UTF_8);
        }
    }
}
