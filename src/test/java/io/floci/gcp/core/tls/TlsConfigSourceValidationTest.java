package io.floci.gcp.core.tls;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Startup validation: floci-gcp must fail fast rather than start without the TLS material the
 * operator asked for. A silent fallback would serve a certificate nobody intended to use.
 */
class TlsConfigSourceValidationTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setup() {
        System.setProperty("floci-gcp.storage.persistent-path", tempDir.toString());
    }

    @AfterEach
    void cleanup() {
        System.clearProperty("floci-gcp.tls.enabled");
        System.clearProperty("floci-gcp.tls.self-signed");
        System.clearProperty("floci-gcp.tls.cert-path");
        System.clearProperty("floci-gcp.tls.key-path");
        System.clearProperty("floci-gcp.storage.persistent-path");
    }

    @Test
    void tlsDisabledEmitsNoProperties() {
        System.setProperty("floci-gcp.tls.enabled", "false");

        TlsConfigSource source = new TlsConfigSource();

        assertTrue(source.getPropertyNames().isEmpty(),
                "a disabled TlsConfigSource must not touch the Quarkus HTTP configuration");
    }

    @Test
    void selfSignedDisabledWithoutCertificateFailsFast() {
        System.setProperty("floci-gcp.tls.enabled", "true");
        System.setProperty("floci-gcp.tls.self-signed", "false");

        IllegalStateException e = assertThrows(IllegalStateException.class, TlsConfigSource::new);
        assertTrue(e.getMessage().contains("FLOCI_GCP_TLS_CERT_PATH"),
                "the error should name the variables that fix it: " + e.getMessage());
    }

    @Test
    void missingCertificateFileFailsFast() {
        System.setProperty("floci-gcp.tls.enabled", "true");
        System.setProperty("floci-gcp.tls.cert-path", tempDir.resolve("absent.crt").toString());
        System.setProperty("floci-gcp.tls.key-path", tempDir.resolve("absent.key").toString());

        IllegalStateException e = assertThrows(IllegalStateException.class, TlsConfigSource::new);
        assertTrue(e.getMessage().contains("TLS certificate"), e.getMessage());
    }

    @Test
    void missingKeyFileFailsFast() throws Exception {
        Path cert = tempDir.resolve("present.crt");
        Files.writeString(cert, "dummy-cert");
        System.setProperty("floci-gcp.tls.enabled", "true");
        System.setProperty("floci-gcp.tls.cert-path", cert.toString());
        System.setProperty("floci-gcp.tls.key-path", tempDir.resolve("absent.key").toString());

        IllegalStateException e = assertThrows(IllegalStateException.class, TlsConfigSource::new);
        assertTrue(e.getMessage().contains("TLS private key"), e.getMessage());
    }

    @Test
    void ordinalOutranksApplicationYaml() {
        System.setProperty("floci-gcp.tls.enabled", "false");

        // application.yml sits at 250; the TLS overrides must win over the declared http port.
        assertEquals(300, new TlsConfigSource().getOrdinal());
    }
}
