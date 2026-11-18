package io.floci.gcp.services.cloudlogging;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.logging.v2.CreateLogMetricRequest;
import com.google.logging.v2.CreateSinkRequest;
import com.google.logging.v2.DeleteLogMetricRequest;
import com.google.logging.v2.DeleteSinkRequest;
import com.google.logging.v2.GetLogMetricRequest;
import com.google.logging.v2.GetSinkRequest;
import com.google.logging.v2.ListLogMetricsRequest;
import com.google.logging.v2.ListSinksRequest;
import com.google.logging.v2.UpdateLogMetricRequest;
import com.google.logging.v2.UpdateSinkRequest;
import com.google.protobuf.Message;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.iam.IamResource;
import io.floci.gcp.services.iam.authorization.IamAuthorizationAdapter;
import io.floci.gcp.services.iam.authorization.IamOperation;
import io.floci.gcp.services.iam.authorization.IamPermissionCheck;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class CloudLoggingIamAuthorizationAdapter implements IamAuthorizationAdapter {
    private static final String PARENT = "(?:projects/[^/]+|organizations/[^/:]+)";
    private static final String SINK = PARENT + "/sinks/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}";
    private static final String METRIC = "projects/[^/]+/metrics/[^\\s]+";
    private static final Set<String> VIEWER = Set.of("logging.sinks.get", "logging.sinks.list",
            "logging.logMetrics.get", "logging.logMetrics.list");
    private static final Set<String> ADMIN = Set.of("logging.sinks.create", "logging.sinks.get",
            "logging.sinks.list", "logging.sinks.update", "logging.sinks.delete",
            "logging.logMetrics.create", "logging.logMetrics.get", "logging.logMetrics.list",
            "logging.logMetrics.update", "logging.logMetrics.delete");

    @Override
    public String serviceName() {
        return "logging";
    }

    @Override
    public Set<Class<?>> restControllers() {
        return Set.of(CloudLoggingConfigHttpController.class, CloudLoggingMetricsHttpController.class);
    }

    @Override
    public Set<String> grpcServices() {
        return Set.of("google.logging.v2.ConfigServiceV2", "google.logging.v2.MetricsServiceV2");
    }

    @Override
    public IamOperation restOperation(String method, Map<String, String> path, JsonNode body) {
        if (method.endsWith("LogMetric") || method.endsWith("LogMetrics")) {
            String project = "projects/" + path.get("project");
            String metric = path.containsKey("metricId") ? project + "/metrics/" + path.get("metricId") : project;
            return switch (method) {
                case "createLogMetric" -> new IamOperation("CreateLogMetric",
                        project + "/metrics/" + body.path("name").asText());
                case "listLogMetrics" -> new IamOperation("ListLogMetrics", project);
                case "getLogMetric" -> new IamOperation("GetLogMetric", metric);
                case "updateLogMetric" -> new IamOperation("UpdateLogMetric", metric);
                case "deleteLogMetric" -> new IamOperation("DeleteLogMetric", metric);
                default -> throw unsupported("REST method " + method);
            };
        }
        String parent = path.get("parentType") + "/" + path.get("parentId");
        String name = path.containsKey("sinkId") ? parent + "/sinks/" + path.get("sinkId") : parent;
        return switch (method) {
            case "createSink" -> new IamOperation("CreateSink", parent + "/sinks/" + body.path("name").asText());
            case "listSinks" -> new IamOperation("ListSinks", parent);
            case "getSink" -> new IamOperation("GetSink", name);
            case "updateSink", "patchSink" -> new IamOperation("UpdateSink", name);
            case "deleteSink" -> new IamOperation("DeleteSink", name);
            default -> throw unsupported("REST method " + method);
        };
    }

    @Override
    public IamOperation grpcOperation(String method, JsonNode request) {
        throw unsupported("gRPC JSON operation " + method);
    }

    @Override
    public IamOperation grpcOperation(String method, Message request) {
        return switch (request) {
            case CreateSinkRequest value -> new IamOperation(method,
                    value.getParent() + "/sinks/" + value.getSink().getName());
            case ListSinksRequest value -> new IamOperation(method, value.getParent());
            case GetSinkRequest value -> new IamOperation(method, value.getSinkName());
            case UpdateSinkRequest value -> new IamOperation(method, value.getSinkName());
            case DeleteSinkRequest value -> new IamOperation(method, value.getSinkName());
            case CreateLogMetricRequest value -> new IamOperation(method,
                    value.getParent() + "/metrics/" + value.getMetric().getName());
            case ListLogMetricsRequest value -> new IamOperation(method, value.getParent());
            case GetLogMetricRequest value -> new IamOperation(method, value.getMetricName());
            case UpdateLogMetricRequest value -> new IamOperation(method, value.getMetricName());
            case DeleteLogMetricRequest value -> new IamOperation(method, value.getMetricName());
            default -> throw unsupported("gRPC operation " + method);
        };
    }

    @Override
    public List<IamPermissionCheck> checks(IamOperation operation) {
        String permission = switch (operation.name()) {
            case "CreateSink" -> "logging.sinks.create";
            case "ListSinks" -> "logging.sinks.list";
            case "GetSink" -> "logging.sinks.get";
            case "UpdateSink" -> "logging.sinks.update";
            case "DeleteSink" -> "logging.sinks.delete";
            case "CreateLogMetric" -> "logging.logMetrics.create";
            case "ListLogMetrics" -> "logging.logMetrics.list";
            case "GetLogMetric" -> "logging.logMetrics.get";
            case "UpdateLogMetric" -> "logging.logMetrics.update";
            case "DeleteLogMetric" -> "logging.logMetrics.delete";
            default -> throw unsupported("operation " + operation.name());
        };
        IamResource target = switch (operation.name()) {
            case "ListSinks" -> listResource(operation.resource());
            case "ListLogMetrics" -> metricListResource(operation.resource());
            default -> requireResource(operation.resource());
        };
        return List.of(new IamPermissionCheck(permission, target));
    }

    @Override
    public Optional<IamResource> resource(String name) {
        if (name != null && name.matches(METRIC)) {
            String project = name.substring(0, name.indexOf("/metrics/"));
            return Optional.of(new IamResource("logging.googleapis.com", "logging.googleapis.com/LogMetric",
                    name, project, project, true));
        }
        if (name != null && name.matches(SINK)) {
            String parent = name.substring(0, name.indexOf("/sinks/"));
            return Optional.of(new IamResource("logging.googleapis.com", "logging.googleapis.com/LogSink",
                    name, parent, parent.startsWith("projects/") ? parent : null, true));
        }
        return Optional.empty();
    }

    @Override
    public Map<String, Set<String>> roles() {
        return Map.of("roles/logging.admin", ADMIN, "roles/logging.configWriter", ADMIN,
                "roles/logging.privateLogViewer", VIEWER, "roles/logging.viewer", VIEWER);
    }

    @Override
    public boolean supportsIamPolicyMethod(String method, String resource) {
        return false;
    }

    @Override
    public GcpException permissionDenied(IamPermissionCheck check) {
        String resource = check.resource().name();
        String message = "Permission '" + check.permission() + "' denied on resource '//"
                + check.resource().service() + "/" + resource + "' (or it may not exist).";
        return GcpException.permissionDenied(message).withErrorInfo("IAM_PERMISSION_DENIED",
                "iam.googleapis.com", Map.of("permission", check.permission(), "resource", resource));
    }

    @Override
    public Map<String, Set<String>> basicRoles() {
        return Map.of("roles/owner", ADMIN, "roles/editor", VIEWER, "roles/viewer", VIEWER);
    }

    private IamResource parentResource(String name) {
        if (!name.matches(PARENT)) {
            throw unsupported("sink parent " + name);
        }
        if (name.startsWith("projects/")) {
            return IamResource.project(name);
        }
        return new IamResource("cloudresourcemanager.googleapis.com",
                "cloudresourcemanager.googleapis.com/Organization", name, name);
    }

    private IamResource metricListResource(String name) {
        if (!name.matches("projects/[^/]+")) {
            throw unsupported("log-based metric parent " + name);
        }
        return new IamResource("logging.googleapis.com", "logging.googleapis.com/LogMetric", name, name, name, true);
    }

    private IamResource listResource(String name) {
        parentResource(name);
        return new IamResource("logging.googleapis.com", "logging.googleapis.com/LogSink",
                name, name, name.startsWith("projects/") ? name : null, true);
    }
}
