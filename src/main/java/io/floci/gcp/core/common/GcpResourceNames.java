package io.floci.gcp.core.common;

/**
 * Utilities for parsing and building GCP resource name strings
 * (e.g. {@code projects/{project}/topics/{topic}}).
 */
public final class GcpResourceNames {

    private GcpResourceNames() {}

    public record ProjectLocation(String project, String location) {}

    /** Parses a location parent of the exact form {@code projects/{project}/locations/{location}}. */
    public static ProjectLocation parseLocationParent(String parent) {
        String[] parts = parent == null ? new String[0] : parent.split("/", -1);
        if (parts.length != 4 || !"projects".equals(parts[0]) || !"locations".equals(parts[2])
                || parts[1].isEmpty() || parts[3].isEmpty()) {
            throw GcpException.invalidArgument("Invalid parent: '" + (parent == null ? "" : parent)
                    + "'. Expected format projects/{project}/locations/{location}");
        }
        return new ProjectLocation(parts[1], parts[3]);
    }

    /** Extracts the project ID from a resource name segment {@code projects/{project}/...}. */
    public static String parseProject(String resourceName) {
        if (resourceName == null) {
            return null;
        }
        int start = resourceName.indexOf("projects/");
        if (start < 0) {
            return null;
        }
        start += "projects/".length();
        int end = resourceName.indexOf('/', start);
        return end < 0 ? resourceName.substring(start) : resourceName.substring(start, end);
    }

    /** Extracts the location from a resource name segment {@code .../locations/{location}/...}. */
    public static String parseLocation(String resourceName) {
        if (resourceName == null) {
            return null;
        }
        int start = resourceName.indexOf("locations/");
        if (start < 0) {
            return null;
        }
        start += "locations/".length();
        int end = resourceName.indexOf('/', start);
        return end < 0 ? resourceName.substring(start) : resourceName.substring(start, end);
    }

    /** Extracts the last path segment (the resource ID) from a full resource name. */
    public static String lastSegment(String resourceName) {
        if (resourceName == null || resourceName.isEmpty()) {
            return resourceName;
        }
        int slash = resourceName.lastIndexOf('/');
        return slash < 0 ? resourceName : resourceName.substring(slash + 1);
    }

    public static String topic(String project, String topic) {
        return "projects/" + project + "/topics/" + topic;
    }

    public static String subscription(String project, String subscription) {
        return "projects/" + project + "/subscriptions/" + subscription;
    }

    public static String secret(String project, String secret) {
        return "projects/" + project + "/secrets/" + secret;
    }

    public static String secretVersion(String project, String secret, String version) {
        return "projects/" + project + "/secrets/" + secret + "/versions/" + version;
    }

    public static String firestoreDocument(String project, String database, String path) {
        return "projects/" + project + "/databases/" + database + "/documents/" + path;
    }
}
