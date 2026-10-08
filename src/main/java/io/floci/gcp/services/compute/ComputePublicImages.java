package io.floci.gcp.services.compute;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.core.common.GcpException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Synthetic catalog of Google-published public images, served read-only from the well-known
 * image projects (debian-cloud, ubuntu-os-cloud, ...). Like {@link ComputeCatalog} it is not
 * stored per project: any project can reference these images from instances and disks.
 */
final class ComputePublicImages {
    private static final String CREATED = "2025-01-15T00:00:00.000-08:00";
    private static final Pattern REFERENCE = Pattern.compile(
            "(?:https?://[^/]+/compute/[a-z0-9]+/)?projects/([^/]+)/global/images/(?:family/([^/]+)|([^/]+))");

    private record Entry(String project, String family, String name, int sizeGb, String architecture, String description) {}

    private static final List<Entry> ENTRIES = List.of(
            entry("debian-cloud", "debian-11", "debian-11-bullseye-v20250115", 10, "X86_64", "Debian, Debian GNU/Linux, 11 (bullseye), amd64 built on 20250115"),
            entry("debian-cloud", "debian-12", "debian-12-bookworm-v20250115", 10, "X86_64", "Debian, Debian GNU/Linux, 12 (bookworm), amd64 built on 20250115"),
            entry("debian-cloud", "debian-12-arm64", "debian-12-bookworm-arm64-v20250115", 10, "ARM64", "Debian, Debian GNU/Linux, 12 (bookworm), arm64 built on 20250115"),
            entry("debian-cloud", "debian-13", "debian-13-trixie-v20250115", 10, "X86_64", "Debian, Debian GNU/Linux, 13 (trixie), amd64 built on 20250115"),
            entry("ubuntu-os-cloud", "ubuntu-2004-lts", "ubuntu-2004-focal-v20250115", 10, "X86_64", "Canonical, Ubuntu, 20.04 LTS, amd64 focal image built on 2025-01-15"),
            entry("ubuntu-os-cloud", "ubuntu-2204-lts", "ubuntu-2204-jammy-v20250115", 10, "X86_64", "Canonical, Ubuntu, 22.04 LTS, amd64 jammy image built on 2025-01-15"),
            entry("ubuntu-os-cloud", "ubuntu-2204-lts-arm64", "ubuntu-2204-jammy-arm64-v20250115", 10, "ARM64", "Canonical, Ubuntu, 22.04 LTS, arm64 jammy image built on 2025-01-15"),
            entry("ubuntu-os-cloud", "ubuntu-2404-lts-amd64", "ubuntu-2404-noble-amd64-v20250115", 10, "X86_64", "Canonical, Ubuntu, 24.04 LTS, amd64 noble image built on 2025-01-15"),
            entry("ubuntu-os-cloud", "ubuntu-2404-lts-arm64", "ubuntu-2404-noble-arm64-v20250115", 10, "ARM64", "Canonical, Ubuntu, 24.04 LTS, arm64 noble image built on 2025-01-15"),
            entry("ubuntu-os-cloud", "ubuntu-minimal-2404-lts-amd64", "ubuntu-minimal-2404-noble-amd64-v20250115", 10, "X86_64", "Canonical, Ubuntu Minimal, 24.04 LTS, amd64 noble image built on 2025-01-15"),
            entry("cos-cloud", "cos-stable", "cos-stable-117-18613-164-23", 10, "X86_64", "Container-Optimized OS from Google, 117-18613.164.23 stable"),
            entry("cos-cloud", "cos-beta", "cos-beta-121-18700-12-3", 10, "X86_64", "Container-Optimized OS from Google, 121-18700.12.3 beta"),
            entry("cos-cloud", "cos-dev", "cos-dev-123-18800-0-1", 10, "X86_64", "Container-Optimized OS from Google, 123-18800.0.1 dev"),
            entry("cos-cloud", "cos-117-lts", "cos-117-18613-164-23", 10, "X86_64", "Container-Optimized OS from Google, 117-18613.164.23 LTS"),
            entry("rocky-linux-cloud", "rocky-linux-8", "rocky-linux-8-v20250115", 20, "X86_64", "Rocky Linux, Rocky Linux, 8, x86_64 built on 20250115"),
            entry("rocky-linux-cloud", "rocky-linux-9", "rocky-linux-9-v20250115", 20, "X86_64", "Rocky Linux, Rocky Linux, 9, x86_64 built on 20250115"),
            entry("centos-cloud", "centos-stream-9", "centos-stream-9-v20250115", 20, "X86_64", "CentOS, CentOS, Stream 9, x86_64 built on 20250115"),
            entry("rhel-cloud", "rhel-8", "rhel-8-v20250115", 20, "X86_64", "Red Hat, Red Hat Enterprise Linux, 8, x86_64 built on 20250115"),
            entry("rhel-cloud", "rhel-9", "rhel-9-v20250115", 20, "X86_64", "Red Hat, Red Hat Enterprise Linux, 9, x86_64 built on 20250115"));

    private ComputePublicImages() {}

    private static Entry entry(String project, String family, String name, int sizeGb, String architecture, String description) {
        return new Entry(project, family, name, sizeGb, architecture, description);
    }

    static boolean isProject(String project) {
        return ENTRIES.stream().anyMatch(e -> e.project().equals(project));
    }

    static List<ObjectNode> list(String project) {
        List<ObjectNode> result = new ArrayList<>();
        for (Entry e : ENTRIES) {
            if (e.project().equals(project)) {
                result.add(image(e));
            }
        }
        return result;
    }

    static Optional<ObjectNode> byName(String project, String name) {
        return ENTRIES.stream().filter(e -> e.project().equals(project) && e.name().equals(name)).findFirst().map(ComputePublicImages::image);
    }

    static Optional<ObjectNode> byFamily(String project, String family) {
        return ENTRIES.stream().filter(e -> e.project().equals(project) && e.family().equals(family))
                .max(Comparator.comparing(Entry::name)).map(ComputePublicImages::image);
    }

    /**
     * Resolves a reference to a public image owned by a project other than {@code currentProject}:
     * a full or partial URL, {@code projects/P/global/images/N} or {@code projects/P/global/images/family/F}.
     */
    static Optional<ObjectNode> resolve(String currentProject, String ref) {
        Matcher match = REFERENCE.matcher(ref);
        if (!match.matches() || match.group(1).equals(currentProject) || !isProject(match.group(1))) {
            return Optional.empty();
        }
        Optional<ObjectNode> image = match.group(2) != null ? byFamily(match.group(1), match.group(2)) : byName(match.group(1), match.group(3));
        if (image.isEmpty()) {
            throw GcpException.notFound("The resource '" + ref + "' was not found");
        }
        return image;
    }

    private static ObjectNode image(Entry e) {
        String selfLink = "https://www.googleapis.com/compute/v1/projects/" + e.project() + "/global/images/" + e.name();
        ObjectNode r = ComputeService.object().put("kind", "compute#image").put("name", e.name())
                .put("id", Integer.toUnsignedString(selfLink.hashCode())).put("selfLink", selfLink)
                .put("creationTimestamp", CREATED).put("status", "READY").put("family", e.family())
                .put("description", e.description()).put("archiveSizeBytes", "2147483648")
                .put("diskSizeGb", Integer.toString(e.sizeGb())).put("architecture", e.architecture())
                .put("sourceType", "RAW").put("labelFingerprint", "42WmSpB8rSM=");
        r.putArray("storageLocations").add("us");
        r.putArray("licenses").add("https://www.googleapis.com/compute/v1/projects/" + e.project() + "/global/licenses/" + e.family());
        r.putArray("guestOsFeatures").addObject().put("type", "VIRTIO_SCSI_MULTIQUEUE");
        return r;
    }
}
