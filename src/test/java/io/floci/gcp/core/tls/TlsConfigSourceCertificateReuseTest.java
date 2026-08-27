package io.floci.gcp.core.tls;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlsConfigSourceCertificateReuseTest {

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
    }

    @Test
    void existingCertIsReusedWhenHostnamesUnchanged() throws Exception {
        new TlsConfigSource();

        Path certFile = tempDir.resolve("tls/floci-gcp-selfsigned.crt");
        assertTrue(Files.exists(certFile));

        // Back-date the modification time so a rewrite would be detectable
        Instant originalTime = Files.getLastModifiedTime(certFile).toInstant();
        Files.setLastModifiedTime(certFile, FileTime.from(originalTime.minusSeconds(10)));
        Instant modifiedTime = Files.getLastModifiedTime(certFile).toInstant();

        new TlsConfigSource();

        Instant afterTime = Files.getLastModifiedTime(certFile).toInstant();
        assertEquals(modifiedTime, afterTime, "Cert file should not be overwritten when hostnames are unchanged");
    }

    @Test
    void certIsRegeneratedWhenHostnameChanges() throws Exception {
        new TlsConfigSource();

        Path certFile = tempDir.resolve("tls/floci-gcp-selfsigned.crt");
        Instant firstGenTime = Files.getLastModifiedTime(certFile).toInstant();

        Thread.sleep(50);

        System.setProperty("floci-gcp.hostname", "floci-gcp");
        new TlsConfigSource();

        Instant secondGenTime = Files.getLastModifiedTime(certFile).toInstant();
        assertTrue(secondGenTime.isAfter(firstGenTime), "Cert file should be regenerated when hostname config changes");
    }

    @Test
    void certIsRegeneratedWhenMetadataIsMissing() throws Exception {
        new TlsConfigSource();

        Path certFile     = tempDir.resolve("tls/floci-gcp-selfsigned.crt");
        Path metadataFile = tempDir.resolve("tls/floci-gcp-selfsigned.metadata.json");
        assertTrue(Files.exists(metadataFile));

        // Delete metadata to simulate a migration from an older version
        Files.delete(metadataFile);
        Instant beforeTime = Files.getLastModifiedTime(certFile).toInstant();
        Thread.sleep(50);

        new TlsConfigSource();

        Instant afterTime = Files.getLastModifiedTime(certFile).toInstant();
        assertTrue(afterTime.isAfter(beforeTime), "Cert should be regenerated when metadata file is missing");
        assertTrue(Files.exists(metadataFile), "Metadata file should be recreated");
    }
}
