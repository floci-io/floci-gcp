package io.floci.gcp.services.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Maps a Compute image to the Docker image an instance container runs. An entry matches an image whose
 * {@code family} equals its family, or whose name starts with one of its prefixes; an unmatched image runs
 * {@code floci-gcp.services.compute.default-image}. {@code image-catalog-path} replaces the bundled catalog.
 */
@ApplicationScoped
public class ComputeImageCatalog {
    private static final Logger LOG = Logger.getLogger(ComputeImageCatalog.class);
    private static final String BUNDLED = "compute/image-catalog.json";

    record Entry(String family, List<String> prefixes, String dockerImage) {}

    private final EmulatorConfig config;
    private volatile List<Entry> entries;

    @Inject
    public ComputeImageCatalog(EmulatorConfig config) {
        this.config = config;
    }

    /** The Docker image for an image resource with this name and family; either may be null. */
    public String dockerImage(String imageName, String family) {
        for (Entry entry : entries()) {
            if (family != null && family.equals(entry.family())) { return entry.dockerImage(); }
        }
        if (imageName != null) {
            for (Entry entry : entries()) {
                for (String prefix : entry.prefixes()) {
                    if (imageName.startsWith(prefix)) { return entry.dockerImage(); }
                }
            }
        }
        String fallback = config.services().compute().defaultImage();
        LOG.warnf("No Docker image mapped for Compute image name=%s family=%s; using %s", imageName, family, fallback);
        return fallback;
    }

    private List<Entry> entries() {
        List<Entry> loaded = entries;
        if (loaded == null) {
            loaded = load();
            entries = loaded;
        }
        return loaded;
    }

    private List<Entry> load() {
        try {
            JsonNode root;
            Optional<String> custom = config.services().compute().imageCatalogPath().filter(p -> !p.isBlank());
            if (custom.isPresent()) {
                root = new ObjectMapper().readTree(Files.readAllBytes(Path.of(custom.get())));
            } else {
                try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(BUNDLED)) {
                    if (in == null) { throw new IOException(BUNDLED + " is missing"); }
                    root = new ObjectMapper().readTree(in);
                }
            }
            return parse(root);
        } catch (IOException | RuntimeException e) {
            LOG.warnf("Could not load the Compute image catalog, every image runs the default image: %s", e.getMessage());
            return List.of();
        }
    }

    static List<Entry> parse(JsonNode root) {
        List<Entry> result = new ArrayList<>();
        for (JsonNode image : root.path("images")) {
            List<String> prefixes = new ArrayList<>();
            image.path("prefixes").forEach(p -> prefixes.add(p.asText()));
            String family = image.path("family").asText(null);
            String dockerImage = image.path("dockerImage").asText(null);
            if (dockerImage == null || dockerImage.isBlank()) {
                throw new IllegalArgumentException("Image catalog entry without dockerImage: " + image);
            }
            result.add(new Entry(family, List.copyOf(prefixes), dockerImage));
        }
        return List.copyOf(result);
    }
}
