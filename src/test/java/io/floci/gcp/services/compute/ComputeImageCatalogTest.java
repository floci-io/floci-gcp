package io.floci.gcp.services.compute;

import io.floci.gcp.config.EmulatorConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ComputeImageCatalogTest {

    @Test
    void bundledCatalogMatchesFamilyFirstThenNamePrefix() {
        ComputeImageCatalog catalog = new ComputeImageCatalog(config(Optional.empty()));

        assertEquals("ubuntu:24.04", catalog.dockerImage("my-golden-image", "ubuntu-2404-lts-amd64"));
        assertEquals("debian:12", catalog.dockerImage("debian-12-bookworm-v20261001", null));
        assertEquals("rockylinux:9", catalog.dockerImage("rocky-linux-9-v20261001", "custom-family"));
    }

    @Test
    void unmappedImagesRunTheDefaultImage() {
        ComputeImageCatalog catalog = new ComputeImageCatalog(config(Optional.empty()));

        assertEquals("alpine:3.21", catalog.dockerImage("windows-2022", "windows-2022"));
        assertEquals("alpine:3.21", catalog.dockerImage(null, null));
    }

    @Test
    void configuredCatalogReplacesTheBundledOne(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("catalog.json");
        Files.writeString(file, """
                {"images": [{"family": "golden", "prefixes": ["gold-"], "dockerImage": "registry.local/golden:1"}]}
                """);
        ComputeImageCatalog catalog = new ComputeImageCatalog(config(Optional.of(file.toString())));

        assertEquals("registry.local/golden:1", catalog.dockerImage("gold-v2", null));
        assertEquals("alpine:3.21", catalog.dockerImage("debian-12-bookworm", "debian-12"));
    }

    @Test
    void unreadableCatalogFallsBackToTheDefaultImage(@TempDir Path dir) {
        ComputeImageCatalog catalog = new ComputeImageCatalog(config(Optional.of(dir.resolve("missing.json").toString())));

        assertEquals("alpine:3.21", catalog.dockerImage("debian-12-bookworm", "debian-12"));
    }

    private static EmulatorConfig config(Optional<String> catalogPath) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().compute().imageCatalogPath()).thenReturn(catalogPath);
        when(config.services().compute().defaultImage()).thenReturn("alpine:3.21");
        return config;
    }
}
