package io.floci.gcp.config;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlsCertificatePairInterceptorTest {

    private static final String QUARKUS_FILES = "quarkus.http.ssl.certificate.files";

    private static SmallRyeConfig config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(properties, "test", 100))
                .withInterceptors(new TlsCertificatePairInterceptor())
                .build();
    }

    @Test
    void certificateWithoutKey_failsWithBothVariablesNamed() {
        SmallRyeConfig cfg = config(Map.of("floci-gcp.tls.certificate", "/tls/cert.pem"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> cfg.getConfigValue(QUARKUS_FILES));
        assertTrue(e.getMessage().contains("FLOCI_GCP_TLS_CERTIFICATE_KEY"));
        assertTrue(e.getMessage().contains("floci-gcp.tls.certificate-key is not"));
    }

    @Test
    void keyWithoutCertificate_fails() {
        SmallRyeConfig cfg = config(Map.of("floci-gcp.tls.certificate-key", "/tls/key.pem"));
        assertThrows(IllegalStateException.class, () -> cfg.getConfigValue(QUARKUS_FILES));
    }

    @Test
    void bothSet_passesThrough() {
        SmallRyeConfig cfg = config(Map.of(
                "floci-gcp.tls.certificate", "/tls/cert.pem",
                "floci-gcp.tls.certificate-key", "/tls/key.pem"));
        assertEquals("/tls/cert.pem", cfg.getConfigValue("floci-gcp.tls.certificate").getValue());
    }

    @Test
    void neitherSet_passesThrough() {
        SmallRyeConfig cfg = config(Map.of());
        assertNull(cfg.getConfigValue(QUARKUS_FILES).getValue());
    }

    @Test
    void emptyValueCountsAsUnset() {
        // The yml defaults expand to empty strings when the pair is absent — that must not trip the guard.
        SmallRyeConfig cfg = config(Map.of(
                "floci-gcp.tls.certificate", "",
                "floci-gcp.tls.certificate-key", ""));
        assertNull(cfg.getConfigValue(QUARKUS_FILES).getValue());
    }
}
