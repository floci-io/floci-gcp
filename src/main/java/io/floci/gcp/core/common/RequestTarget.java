package io.floci.gcp.core.common;

import com.google.protobuf.Message;
import io.grpc.Metadata;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one generic answer to "which project does this request act on", for every policy that needs
 * it: the project context of a call, and Service Usage enablement. A resource name, the routing
 * header and the fields the API declares as resource names (see {@link ProtoResourceFields}) are
 * read here and nowhere else. The wildcards {@code _} and {@code -} name no project.
 */
public final class RequestTarget {

    public static final Metadata.Key<String> ROUTING_HEADER =
            Metadata.Key.of("x-goog-request-params", Metadata.ASCII_STRING_MARSHALLER);

    private static final Pattern RESOURCE_PROJECT = Pattern.compile("(?:^|[=&/])projects/([^/&]+)");
    private static final Pattern PARAM_PROJECT = Pattern.compile("(?:^|&)project(?:_id)?=([^&]+)");
    private static final Set<String> COMMON_DOMAINS =
            Set.of("cloudresourcemanager.googleapis.com", "locations.googleapis.com");
    private static final Set<String> TARGET_FIELDS = Set.of("name", "parent", "resource");

    private RequestTarget() {}

    /** The project a {@code projects/{project}/...} resource name belongs to, or null. */
    public static String projectOf(String resourceName) {
        if (resourceName == null || !resourceName.startsWith("projects/")) {
            return null;
        }
        int end = resourceName.indexOf('/', "projects/".length());
        String project = end < 0 ? resourceName.substring("projects/".length())
                : resourceName.substring("projects/".length(), end);
        return isConcrete(project) ? project : null;
    }

    /** The project a resource field names, where {@code project} and {@code project_id} may hold a bare ID. */
    static String projectOf(String field, String value) {
        if (field.equals("project_id") || field.equals("project")) {
            String project = value.startsWith("projects/") ? value.substring("projects/".length()) : value;
            if (isConcrete(project)) {
                return project;
            }
        }
        return projectOf(value);
    }

    /** The project an {@code x-goog-request-params} value names, or null. */
    static String routedProject(String params) {
        if (params == null || params.isBlank()) {
            return null;
        }
        String decoded = URLDecoder.decode(params, StandardCharsets.UTF_8);
        Matcher resource = RESOURCE_PROJECT.matcher(decoded);
        while (resource.find()) {
            if (isConcrete(resource.group(1))) {
                return resource.group(1);
            }
        }
        Matcher param = PARAM_PROJECT.matcher(decoded);
        if (param.find() && isConcrete(param.group(1))) {
            return param.group(1);
        }
        return null;
    }

    /** The project of the request's own target, otherwise of the first resource it names, or null. */
    static String primaryProject(Message request) {
        return primaryProject(ProtoResourceFields.refs(request));
    }

    /** {@link #primaryProject(Message)} over refs already read. */
    static String primaryProject(List<ProtoResourceFields.Ref> refs) {
        String first = null;
        for (ProtoResourceFields.Ref ref : refs) {
            String project = projectOf(ref.field(), ref.value());
            if (project == null) {
                continue;
            }
            if (ref.top()) {
                return project;
            }
            if (first == null) {
                first = project;
            }
        }
        return first;
    }

    /**
     * Every project named by the fields the API declares as resource names. A reference to another
     * API's resource, such as the KMS key of a Cloud Storage object, does not count unless it is
     * the request's own target or {@code domains} lists its API.
     */
    static List<String> projects(Object message, Set<String> domains) {
        if (!(message instanceof Message proto)) {
            return List.of();
        }
        return projects(ProtoResourceFields.refs(proto), domains);
    }

    /** {@link #projects(Object, Set)} over refs already read. */
    static List<String> projects(List<ProtoResourceFields.Ref> refs, Set<String> domains) {
        Set<String> projects = new LinkedHashSet<>();
        for (ProtoResourceFields.Ref ref : refs) {
            if (!actsOn(ref, domains)) {
                continue;
            }
            String project = projectOf(ref.field(), ref.value());
            if (project != null) {
                projects.add(project);
            }
        }
        return List.copyOf(projects);
    }

    private static boolean actsOn(ProtoResourceFields.Ref ref, Set<String> domains) {
        if (ref.type() == null || ref.type().equals("*") || (ref.top() && TARGET_FIELDS.contains(ref.field()))) {
            return true;
        }
        String domain = ref.type().substring(0, Math.max(0, ref.type().indexOf('/')));
        return COMMON_DOMAINS.contains(domain) || domains.contains(domain);
    }

    /**
     * The value of a resource field with a known project's number replaced by its ID: the project
     * segment of {@code projects/{number}/...}, or the whole value of a {@code project} or
     * {@code project_id} field that holds a bare number. Anything else is returned unchanged.
     */
    static String canonical(String field, String value, ProjectRegistry registry) {
        if ((field.equals("project_id") || field.equals("project")) && ProjectRegistry.isNumber(value)) {
            return registry.find(value).map(ProjectRecord::getProjectId).orElse(value);
        }
        return registry.canonicalResourceName(value);
    }

    /** Whether any of the refs names a project by a number the registry knows. */
    static boolean namesKnownNumber(List<ProtoResourceFields.Ref> refs, ProjectRegistry registry) {
        for (ProtoResourceFields.Ref ref : refs) {
            String project = projectOf(ref.field(), ref.value());
            if (ProjectRegistry.isNumber(project) && registry.find(project).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConcrete(String project) {
        return !project.isEmpty() && !project.equals("_") && !project.equals("-");
    }
}
