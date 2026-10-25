package io.floci.gcp.services.cloudrun;

import io.floci.gcp.services.cloudrun.model.CloudRunRuntimeInstance;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Streamed invocation responses as an HTTP caller sees them, with the runtime lookup mocked to a local upstream. */
@QuarkusTest
class CloudRunInvocationStreamingIntegrationTest {

    private static final String EVENTS = "/run/v2/projects/p1/locations/us-central1/services/svc/events";

    @InjectMock CloudRunService cloudRunService;

    private final CountDownLatch releaseUpstream = new CountDownLatch(1);
    private final CountDownLatch upstreamClosed = new CountDownLatch(1);
    /** Own threads, since the upstream blocks and the common pool can be a single thread on a small runner. */
    private final ExecutorService upstreamThreads = Executors.newCachedThreadPool();
    private ServerSocket upstream;

    @AfterEach
    void stopUpstream() throws IOException {
        releaseUpstream.countDown();
        if (upstream != null) {
            upstream.close();
        }
        upstreamThreads.shutdownNow();
    }

    @Test
    void deliversEachEventBeforeTheRuntimeFinishes() throws Exception {
        CountDownLatch sendHeartbeat = new CountDownLatch(1);
        startUpstream(300_000, out -> {
            chunk(out, ": connected\n\n");
            await(sendHeartbeat);
            chunk(out, "data: 1\n\n");
            await(releaseUpstream);
            out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        });

        HttpResponse<InputStream> response = get();
        assertEquals(200, response.statusCode());
        assertEquals("text/event-stream", response.headers().firstValue("Content-Type").orElseThrow());
        BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
        assertEquals(": connected", reader.readLine());
        sendHeartbeat.countDown();
        assertEquals("", reader.readLine());
        assertEquals("data: 1", reader.readLine());

        releaseUpstream.countDown();
        assertEquals("", reader.readLine());
        assertNull(reader.readLine());
    }

    @Test
    void abortsAResponseStillStreamingAtTheRequestTimeout() throws Exception {
        startUpstream(1_000, out -> {
            chunk(out, ": connected\n\n");
            await(releaseUpstream);
        });

        String received;
        try (Socket caller = new Socket(InetAddress.getLoopbackAddress(), RestAssured.port)) {
            caller.setSoTimeout(15_000);
            caller.getOutputStream().write(("GET " + EVENTS + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            caller.getOutputStream().flush();
            received = new String(caller.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        }

        assertTrue(received.startsWith("HTTP/1.1 200"), received);
        assertTrue(received.endsWith("d\r\n: connected\n\n\r\n"), received);
        assertFalse(received.contains("\r\n0\r\n\r\n"), "a clean end would carry the last chunk: " + received);
        assertTrue(upstreamClosed.await(5, TimeUnit.SECONDS));
    }

    @Test
    void closesTheIdleRuntimeConnectionWhenTheCallerDisconnects() throws Exception {
        startUpstream(300_000, out -> {
            chunk(out, ": connected\n\n");
            await(releaseUpstream);
        });

        try (Socket caller = new Socket(InetAddress.getLoopbackAddress(), RestAssured.port)) {
            caller.setSoTimeout(15_000);
            caller.getOutputStream().write(("GET " + EVENTS + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            caller.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(caller.getInputStream(),
                    StandardCharsets.ISO_8859_1));
            String line;
            while ((line = reader.readLine()) != null && !line.equals(": connected")) {
                // skip the status line, headers and chunk size
            }
            assertEquals(": connected", line);
        }

        assertTrue(upstreamClosed.await(5, TimeUnit.SECONDS));
    }

    private HttpResponse<InputStream> get() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + RestAssured.port + EVENTS))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    /** A runtime that answers one SSE request, then reports when the proxy closes its connection. */
    private void startUpstream(long requestTimeoutMillis, Body body) throws IOException {
        upstream = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
        when(cloudRunService.readyRuntime(anyString())).thenReturn(Optional.of(instance(upstream.getLocalPort(),
                requestTimeoutMillis)));
        CompletableFuture.runAsync(() -> {
            try (Socket socket = upstream.accept()) {
                InputStream in = socket.getInputStream();
                readRequestHead(in);
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                        + "Transfer-Encoding: chunked\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                CompletableFuture.runAsync(() -> {
                    try {
                        if (in.read() == -1) {
                            upstreamClosed.countDown();
                        }
                    } catch (IOException e) {
                        upstreamClosed.countDown();
                    }
                }, upstreamThreads);
                body.write(out);
                await(upstreamClosed);
            } catch (IOException e) {
                upstreamClosed.countDown();
            }
        }, upstreamThreads);
    }

    private static void chunk(OutputStream out, String data) throws IOException {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        out.write((Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static void readRequestHead(InputStream in) throws IOException {
        int matched = 0;
        byte[] end = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        int next;
        while (matched < end.length && (next = in.read()) != -1) {
            matched = next == end[matched] ? matched + 1 : (next == end[0] ? 1 : 0);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static CloudRunRuntimeInstance instance(int port, long requestTimeoutMillis) {
        return new CloudRunRuntimeInstance("p1", "us-central1",
                "projects/p1/locations/us-central1/services/svc",
                "projects/p1/locations/us-central1/services/svc/revisions/svc-00001",
                "gcr.io/p1/svc:latest", "container-id", 8080, null, "127.0.0.1", port,
                "http://localhost:4588/run/v2/projects/p1/locations/us-central1/services/svc",
                "READY", 1, 1, null, requestTimeoutMillis);
    }

    @FunctionalInterface
    private interface Body {
        void write(OutputStream out) throws IOException;
    }
}
