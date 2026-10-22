package io.floci.gcp.services.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only images of the public image projects ({@code debian-cloud}, {@code ubuntu-os-cloud}, ...), one current
 * image per family, from {@code compute/public-images.json}. Every project can boot from them, which is the one
 * cross-project reference Compute accepts. Image names follow Google's naming, because Terraform matches a disk's
 * image to the family in its configuration by name.
 */
final class ComputePublicImages {
    private static final String RESOURCE = "compute/public-images.json";
    private static final String BASE = "https://www.googleapis.com/compute/v1/projects/";
    private static final String CREATED = "2026-10-01T00:00:00.000-07:00";
    private static final Map<String, List<ObjectNode>> PROJECTS = load();

    private ComputePublicImages() {}

    static boolean isPublicProject(String project) {
        return PROJECTS.containsKey(project);
    }

    /** Every image of a public project, in catalog order. */
    static List<ObjectNode> images(String project) {
        return PROJECTS.getOrDefault(project, List.of()).stream().map(ObjectNode::deepCopy).toList();
    }

    static Optional<ObjectNode> image(String project, String name) {
        return PROJECTS.getOrDefault(project, List.of()).stream()
                .filter(image -> image.path("name").asText().equals(name)).findFirst().map(ObjectNode::deepCopy);
    }

    static Optional<ObjectNode> fromFamily(String project, String family) {
        return PROJECTS.getOrDefault(project, List.of()).stream()
                .filter(image -> image.path("family").asText().equals(family)).findFirst().map(ObjectNode::deepCopy);
    }

    /** Whether {@code ref} names something inside a public image project. */
    static boolean isPublicReference(String ref) {
        int at = ref.indexOf("projects/");
        if (at < 0) { return false; }
        String rest = ref.substring(at + 9);
        int slash = rest.indexOf('/');
        return slash > 0 && isPublicProject(rest.substring(0, slash));
    }

    /**
     * Resolves {@code projects/<public>/global/images/<name>} or {@code .../images/family/<family>} (with or
     * without the {@code https://www.googleapis.com/compute/v1/} prefix) to a concrete image, or empty when the
     * reference is not into a public project.
     */
    static Optional<ObjectNode> resolve(String ref) {
        int at = ref.indexOf("projects/");
        if (at < 0) { return Optional.empty(); }
        String[] parts = ref.substring(at + 9).split("/");
        if (parts.length < 4 || !isPublicProject(parts[0]) || !parts[1].equals("global") || !parts[2].equals("images")) {
            return Optional.empty();
        }
        if (parts.length == 5 && parts[3].equals("family")) { return fromFamily(parts[0], parts[4]); }
        if (parts.length == 4) { return image(parts[0], parts[3]); }
        return Optional.empty();
    }

    private static Map<String, List<ObjectNode>> load() {
        ObjectMapper json = new ObjectMapper();
        try (InputStream in = ComputePublicImages.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) { throw new IllegalStateException(RESOURCE + " is missing"); }
            Map<String, List<ObjectNode>> projects = new LinkedHashMap<>();
            json.readTree(in).path("projects").properties().forEach(project -> {
                List<ObjectNode> images = new ArrayList<>();
                for (JsonNode entry : project.getValue()) {
                    images.add(image(json, project.getKey(), entry));
                }
                projects.put(project.getKey(), List.copyOf(images));
            });
            return Map.copyOf(projects);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + RESOURCE, e);
        }
    }

    private static ObjectNode image(ObjectMapper json, String project, JsonNode entry) {
        String name = entry.path("name").asText();
        String selfLink = BASE + project + "/global/images/" + name;
        ObjectNode image = json.createObjectNode().put("kind", "compute#image")
                .put("id", Integer.toUnsignedString(selfLink.hashCode()))
                .put("creationTimestamp", CREATED).put("name", name)
                .put("family", entry.path("family").asText()).put("selfLink", selfLink)
                .put("sourceType", "RAW").put("status", "READY")
                .put("diskSizeGb", entry.path("diskSizeGb").asText())
                .put("architecture", entry.path("architecture").asText())
                .put("labelFingerprint", "42WmSpB8rSM=");
        image.putArray("storageLocations").add("eu").add("asia").add("us");
        return image;
    }
}
