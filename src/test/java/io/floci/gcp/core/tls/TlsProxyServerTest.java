package io.floci.gcp.core.tls;

import io.floci.gcp.config.EmulatorConfig;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link TlsProxyServer}'s multi-port binding and first-byte protocol detection.
 *
 * <p>Uses ephemeral ports for the public port, the HTTPS port, and the stub HTTP/HTTPS
 * backends, so nothing privileged (443) is bound and the test never collides with a running
 * floci-gcp. The stub backends simply emit a marker on connect; the proxy pipes it back, letting
 * the test assert which backend a given first byte was routed to.
 */
class TlsProxyServerTest {

    /** TLS ClientHello content type — the first byte the proxy keys protocol detection on. */
    private static final byte TLS_HANDSHAKE = 0x16;

    /** First byte of the HTTP/2 connection preface ("PRI * HTTP/2.0…") used by h2c gRPC. */
    private static final byte H2C_PREFACE = 'P';

    private Vertx vertx;
    private TlsProxyServer proxy;
    private NetServer httpBackend;
    private NetServer httpsBackend;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        if (proxy != null) {
            proxy.stop();
        }
        closeBackend(httpBackend);
        closeBackend(httpsBackend);
        vertx.close();
    }

    private static EmulatorConfig configWith(boolean tlsEnabled, int publicPort, int httpsPort) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.TlsConfig tls = mock(EmulatorConfig.TlsConfig.class);
        when(config.port()).thenReturn(publicPort);
        when(config.tls()).thenReturn(tls);
        when(tls.enabled()).thenReturn(tlsEnabled);
        when(tls.httpsPort()).thenReturn(httpsPort);
        return config;
    }

    @Test
    void listenPorts_includesPublicAndHttpsPort() {
        proxy = new TlsProxyServer(vertx, configWith(false, 4588, 443), 4580, 4581);
        assertEquals(Set.of(4588, 443), proxy.listenPorts());
    }

    @Test
    void listenPorts_dropsHttpsPortWhenZero() {
        proxy = new TlsProxyServer(vertx, configWith(false, 4588, 0), 4580, 4581);
        assertEquals(Set.of(4588), proxy.listenPorts());
    }

    @Test
    void listenPorts_dedupesWhenHttpsPortEqualsPublic() {
        proxy = new TlsProxyServer(vertx, configWith(false, 4588, 4588), 4580, 4581);
        assertEquals(Set.of(4588), proxy.listenPorts());
    }

    @Test
    @Timeout(20)
    void bindsBothPorts_andRoutesByFirstByte() throws Exception {
        int httpBe = freePort();
        int httpsBe = freePort();
        int publicPort = freePort();
        int httpsPort = freePort();
        httpBackend = startMarkerBackend(httpBe, "PLAIN");
        httpsBackend = startMarkerBackend(httpsBe, "TLS");

        proxy = new TlsProxyServer(vertx, configWith(true, publicPort, httpsPort), httpBe, httpsBe);

        // Public port: TLS ClientHello → HTTPS backend; anything else → HTTP backend.
        assertEquals("TLS", roundTrip(publicPort, TLS_HANDSHAKE));
        assertEquals("PLAIN", roundTrip(publicPort, (byte) 'G'));
        // HTTPS port: same protocol detection as the public port.
        assertEquals("TLS", roundTrip(httpsPort, TLS_HANDSHAKE));
        assertEquals("PLAIN", roundTrip(httpsPort, (byte) 'G'));
    }

    /**
     * floci-gcp multiplexes gRPC and REST on the single public port. Plaintext gRPC opens with
     * the HTTP/2 preface, which must keep reaching the plaintext backend so existing SDK clients
     * are unaffected when TLS is switched on.
     */
    @Test
    @Timeout(20)
    void h2cPrefaceRoutesToPlaintextBackend() throws Exception {
        int httpBe = freePort();
        int httpsBe = freePort();
        int publicPort = freePort();
        httpBackend = startMarkerBackend(httpBe, "PLAIN");
        httpsBackend = startMarkerBackend(httpsBe, "TLS");

        proxy = new TlsProxyServer(vertx, configWith(true, publicPort, 0), httpBe, httpsBe);

        assertEquals("PLAIN", roundTrip(publicPort, H2C_PREFACE));
    }

    /**
     * When the public port cannot be bound, Quarkus is left listening only on loopback internal
     * ports and nothing is reachable. Previously that failure was merely logged, leaving a process
     * that looked healthy but served nothing; it must abort startup instead.
     */
    @Test
    @Timeout(20)
    void publicPortBindFailure_abortsStartup() throws Exception {
        int publicPort = freePort();
        // Occupy the public port so the proxy cannot bind it.
        try (ServerSocket squatter = new ServerSocket(publicPort)) {
            CompletableFuture<Integer> aborted = new CompletableFuture<>();
            proxy = new TlsProxyServer(vertx, configWith(true, publicPort, 0), 4580, 4581) {
                @Override
                void failStartup(int port, Throwable cause) {
                    aborted.complete(port);
                }
            };
            assertEquals(publicPort, aborted.get(10, TimeUnit.SECONDS),
                    "a failure to bind the public port must abort startup");
        }
    }

    /** The extra HTTPS port is best-effort: failing to bind 443 must NOT abort startup. */
    @Test
    @Timeout(20)
    void httpsPortBindFailure_doesNotAbortStartup() throws Exception {
        int httpBe = freePort();
        int httpsBe = freePort();
        int publicPort = freePort();
        int httpsPort = freePort();
        httpBackend = startMarkerBackend(httpBe, "PLAIN");

        try (ServerSocket squatter = new ServerSocket(httpsPort)) {
            CompletableFuture<Integer> aborted = new CompletableFuture<>();
            proxy = new TlsProxyServer(vertx, configWith(true, publicPort, httpsPort), httpBe, httpsBe) {
                @Override
                void failStartup(int port, Throwable cause) {
                    aborted.complete(port);
                }
            };
            // The public port still serves, and no abort is raised for the squatted extra port.
            assertEquals("PLAIN", roundTrip(publicPort, (byte) 'G'));
            assertFalse(aborted.isDone(), "failing to bind the extra HTTPS port must stay non-fatal");
        }
    }

    /**
     * The startup hooks and the ready flag must not run ahead of the public bind, or a hook that
     * calls the configured endpoint races it and sees connection refused.
     */
    @Test
    @Timeout(20)
    void awaitPublicPortReady_blocksUntilTheBindCompletes() throws Exception {
        int httpBe = freePort();
        int httpsBe = freePort();
        int publicPort = freePort();
        httpBackend = startMarkerBackend(httpBe, "PLAIN");

        proxy = new TlsProxyServer(vertx, configWith(true, publicPort, 0), httpBe, httpsBe);

        assertTrue(proxy.awaitPublicPortReady(10, TimeUnit.SECONDS),
                "await must succeed once the public listener is bound");
        // Once it reports ready the port must actually be accepting, with no further waiting.
        assertEquals("PLAIN", roundTrip(publicPort, (byte) 'G'));
    }

    /** A failed public bind must release the waiter rather than stalling startup for the timeout. */
    @Test
    @Timeout(20)
    void awaitPublicPortReady_returnsFalseWhenTheBindFails() throws Exception {
        int publicPort = freePort();
        try (ServerSocket squatter = new ServerSocket(publicPort)) {
            proxy = new TlsProxyServer(vertx, configWith(true, publicPort, 0), 4580, 4581) {
                @Override
                void failStartup(int port, Throwable cause) {
                    // swallow so the test JVM survives; the await behaviour is what is under test
                }
            };
            assertFalse(proxy.awaitPublicPortReady(10, TimeUnit.SECONDS),
                    "a failed bind must release the waiter, not stall until the timeout");
        }
    }

    @Test
    @Timeout(20)
    void tlsDisabled_bindsNothing() throws Exception {
        int publicPort = freePort();
        proxy = new TlsProxyServer(vertx, configWith(false, publicPort, 443), 4580, 4581);
        assertFalse(portAccepts(publicPort), "no proxy should listen when TLS is disabled");
    }

    // ---- helpers ----

    private NetServer startMarkerBackend(int port, String marker) throws Exception {
        NetServer server = vertx.createNetServer();
        server.connectHandler(sock -> sock.write(marker));
        CompletableFuture<Void> started = new CompletableFuture<>();
        server.listen(port, "127.0.0.1").onComplete(ar -> {
            if (ar.succeeded()) {
                started.complete(null);
            } else {
                started.completeExceptionally(ar.cause());
            }
        });
        started.get(5, TimeUnit.SECONDS);
        return server;
    }

    private String roundTrip(int proxyPort, byte firstByte) throws Exception {
        awaitListening(proxyPort);
        try (Socket sock = new Socket("127.0.0.1", proxyPort)) {
            sock.getOutputStream().write(new byte[]{firstByte});
            sock.getOutputStream().flush();
            byte[] buf = new byte[64];
            int n = sock.getInputStream().read(buf);
            assertTrue(n > 0, "expected a marker response from the backend");
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        }
    }

    private void awaitListening(int port) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket("127.0.0.1", port)) {
                return;
            } catch (IOException e) {
                Thread.sleep(50);
            }
        }
        fail("port " + port + " not listening within timeout");
    }

    private boolean portAccepts(int port) throws Exception {
        // Give any (unexpected) async bind a brief chance to come up, then probe once.
        Thread.sleep(500);
        try (Socket s = new Socket("127.0.0.1", port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void closeBackend(NetServer server) {
        if (server != null) {
            server.close();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
