package io.floci.gcp.core.tls;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlsConfigSourceCertificateGenerationTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setup() {
        System.setProperty("floci-gcp.tls.enabled", "true");
        System.setProperty("floci-gcp.tls.self-signed", "true");
        System.setProperty("floci-gcp.storage.persistent-path", tempDir.toString());
    }

    @AfterEach
    void cleanup() {
        System.clearProperty("floci-gcp.tls.enabled");
        System.clearProperty("floci-gcp.tls.self-signed");
        System.clearProperty("floci-gcp.storage.persistent-path");
        System.clearProperty("floci-gcp.hostname");
        System.clearProperty("floci-gcp.base-url");
    }

    @Test
    void certificateIncludesFlociGcpHostname() throws Exception {
        System.setProperty("floci-gcp.hostname", "floci-gcp");

        new TlsConfigSource();

        Path certFile = tempDir.resolve("tls/floci-gcp-selfsigned.crt");
        assertTrue(Files.exists(certFile), "Certificate file should exist");

        List<String> sans = extractSans(certFile);
        assertTrue(sans.contains("floci-gcp"), "SANs should include 'floci-gcp' from floci-gcp.hostname");
        assertTrue(sans.contains("localhost"), "SANs should include default 'localhost'");
    }

    @Test
    void certificateIncludesBaseUrlHostname() throws Exception {
        System.setProperty("floci-gcp.base-url", "https://myhost:4588");

        new TlsConfigSource();

        Path certFile = tempDir.resolve("tls/floci-gcp-selfsigned.crt");
        List<String> sans = extractSans(certFile);
        assertTrue(sans.contains("myhost"), "SANs should include 'myhost' from floci-gcp.base-url");
        assertTrue(sans.contains("localhost"), "SANs should include default 'localhost'");
    }

    @Test
    void certificateIncludesIpFromBaseUrl() throws Exception {
        System.setProperty("floci-gcp.base-url", "https://192.168.1.100:4588");

        new TlsConfigSource();

        Path certFile = tempDir.resolve("tls/floci-gcp-selfsigned.crt");
        List<String> sans = extractSans(certFile);
        assertTrue(sans.contains("192.168.1.100"), "SANs should include the IP from floci-gcp.base-url");
    }

    @Test
    void certificateWithDefaultConfigHasDefaultSans() throws Exception {
        new TlsConfigSource();

        Path certFile = tempDir.resolve("tls/floci-gcp-selfsigned.crt");
        assertTrue(Files.exists(certFile));

        List<String> sans = extractSans(certFile);
        assertTrue(sans.contains("localhost"));
        assertTrue(sans.contains("127.0.0.1"));
        assertTrue(sans.contains("0.0.0.0"));
        assertTrue(sans.contains("*.googleapis.com"),
                "SANs should include '*.googleapis.com' so SDK clients addressing the real service hostnames verify");
        assertTrue(sans.contains("*.localhost.floci.io"),
                "SANs should include '*.localhost.floci.io' for GCS virtual-hosted-style bucket URLs");
        assertTrue(sans.contains("host.docker.internal"),
                "SANs should include 'host.docker.internal' so spawned containers can reach floci-gcp on the host");
        assertEquals(8, sans.size(),
                "Default cert should have exactly 8 SANs (localhost, 127.0.0.1, 0.0.0.0, *.localhost, "
                        + "localhost.floci.io, *.localhost.floci.io, *.googleapis.com, host.docker.internal)");
    }

    @Test
    void duplicateHostnamesAreDeduplicatedInCert() throws Exception {
        System.setProperty("floci-gcp.hostname", "myhost");
        System.setProperty("floci-gcp.base-url", "http://myhost:4588");

        new TlsConfigSource();

        Path certFile = tempDir.resolve("tls/floci-gcp-selfsigned.crt");
        List<String> sans = extractSans(certFile);
        long count = sans.stream().filter("myhost"::equals).count();
        assertEquals(1, count, "Duplicate hostnames must appear only once in the cert SANs");
    }

    @Test
    void metadataFileContainsCustomHostnames() throws Exception {
        System.setProperty("floci-gcp.hostname", "floci-gcp");
        System.setProperty("floci-gcp.base-url", "https://myhost:4588");

        new TlsConfigSource();

        Path metadataFile = tempDir.resolve("tls/floci-gcp-selfsigned.metadata.json");
        assertTrue(Files.exists(metadataFile), "Metadata file should be written after cert generation");

        String json = Files.readString(metadataFile);
        assertTrue(json.contains("floci-gcp"), "Metadata should contain 'floci-gcp'");
        assertTrue(json.contains("myhost"), "Metadata should contain 'myhost'");
        assertTrue(json.contains("localhost"), "Metadata should contain default 'localhost'");
    }

    private List<String> extractSans(Path certFile) throws Exception {
        String pem = Files.readString(certFile);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) cf.generateCertificate(
                new ByteArrayInputStream(pem.getBytes()));
        Collection<List<?>> sans = cert.getSubjectAlternativeNames();
        if (sans == null) {
            return List.of();
        }
        return sans.stream()
                .filter(san -> san.size() >= 2)
                .map(san -> san.get(1).toString())
                .toList();
    }
}
