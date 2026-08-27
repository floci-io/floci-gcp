package io.floci.gcp.core.common;

import io.vertx.core.http.HttpServerOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Google Cloud Java SDK gzips request bodies by default, so every listener Quarkus
 * builds must have decompression enabled. Quarkus customizes the plaintext, TLS and domain
 * socket servers from separate options instances — missing the TLS one made gzipped bodies
 * arrive undecompressed over HTTPS and fail as malformed JSON.
 */
class GzipRequestFilterTest {

    private final GzipRequestFilter filter = new GzipRequestFilter();

    @Test
    void enablesDecompressionOnThePlaintextServer() {
        HttpServerOptions options = new HttpServerOptions();
        filter.customizeHttpServer(options);
        assertTrue(options.isDecompressionSupported());
    }

    @Test
    void enablesDecompressionOnTheTlsServer() {
        HttpServerOptions options = new HttpServerOptions();
        filter.customizeHttpsServer(options);
        assertTrue(options.isDecompressionSupported(),
                "gzipped request bodies must be decompressed over HTTPS too");
    }

    @Test
    void enablesDecompressionOnTheDomainSocketServer() {
        HttpServerOptions options = new HttpServerOptions();
        filter.customizeDomainSocketServer(options);
        assertTrue(options.isDecompressionSupported());
    }
}
