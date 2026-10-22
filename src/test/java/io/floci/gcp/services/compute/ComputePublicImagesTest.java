package io.floci.gcp.services.compute;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComputePublicImagesTest {

    @Test
    void everyImageNameMatchesItsFamilyTheWayTerraformCompares() {
        for (String project : List.of("debian-cloud", "ubuntu-os-cloud", "rocky-linux-cloud", "centos-cloud", "cos-cloud")) {
            List<ObjectNode> images = ComputePublicImages.images(project);
            assertFalse(images.isEmpty(), project);
            for (ObjectNode image : images) {
                String name = image.path("name").asText(), family = image.path("family").asText();
                assertTrue(terraformFamilyEquals(name, family), project + ": " + name + " vs family " + family);
            }
        }
    }

    @Test
    void referencesResolveOnlyIntoPublicProjects() {
        assertEquals("debian-12-bookworm-v20261001",
                ComputePublicImages.resolve("projects/debian-cloud/global/images/family/debian-12").orElseThrow().path("name").asText());
        assertEquals("cos-stable-121-18867-0-50", ComputePublicImages.resolve(
                "https://www.googleapis.com/compute/v1/projects/cos-cloud/global/images/cos-stable-121-18867-0-50")
                .orElseThrow().path("name").asText());
        assertTrue(ComputePublicImages.resolve("projects/debian-cloud/global/images/family/nope").isEmpty());
        assertTrue(ComputePublicImages.resolve("projects/my-project/global/images/family/debian-12").isEmpty());
        assertTrue(ComputePublicImages.resolve("global/images/debian-12").isEmpty());
        assertTrue(ComputePublicImages.isPublicReference("projects/ubuntu-os-cloud/global/images/x"));
        assertFalse(ComputePublicImages.isPublicReference("projects/my-project/global/images/x"));
    }

    /**
     * terraform-provider-google {@code diskImageFamilyEquals} (google/services/compute/resource_compute_disk.go), for
     * the families in the catalog: the containment rules plus the Canonical Ubuntu LTS rule. A false result is a
     * permanent diff on every {@code terraform plan} for an instance booted from that family.
     */
    private static boolean terraformFamilyEquals(String imageName, String familyName) {
        if (!imageName.contains("-arm64") && imageName.contains(trimSuffix(familyName, "-amd64"))) {
            return imageName.contains("-amd64") == familyName.endsWith("-amd64");
        }
        if (imageName.contains(trimSuffix(familyName, "-arm64"))) {
            return imageName.contains("-arm64") == familyName.endsWith("-arm64");
        }
        Matcher ubuntu = Pattern.compile("^ubuntu-(minimal-)?([0-9]+)(?:.*(arm64|amd64))?.*$").matcher(imageName);
        if (ubuntu.matches()) {
            String expected = "ubuntu-" + (ubuntu.group(1) == null ? "" : ubuntu.group(1)) + ubuntu.group(2) + "-lts"
                    + (ubuntu.group(3) == null ? "" : "-" + ubuntu.group(3));
            if (expected.equals(familyName)) { return true; }
        }
        return Pattern.compile("^cos-([0-9]+)-").matcher(imageName).find() && familyName.equals("cos-" + imageName.split("-")[1] + "-lts");
    }

    private static String trimSuffix(String value, String suffix) {
        return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
    }
}
