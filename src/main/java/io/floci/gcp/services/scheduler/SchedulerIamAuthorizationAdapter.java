package io.floci.gcp.services.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.cloud.scheduler.v1.CreateJobRequest;
import com.google.cloud.scheduler.v1.DeleteJobRequest;
import com.google.cloud.scheduler.v1.GetJobRequest;
import com.google.cloud.scheduler.v1.Job;
import com.google.cloud.scheduler.v1.ListJobsRequest;
import com.google.cloud.scheduler.v1.PauseJobRequest;
import com.google.cloud.scheduler.v1.ResumeJobRequest;
import com.google.cloud.scheduler.v1.RunJobRequest;
import com.google.cloud.scheduler.v1.UpdateJobRequest;
import com.google.protobuf.Message;
import io.floci.gcp.core.common.ProtoJson;
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
public class SchedulerIamAuthorizationAdapter implements IamAuthorizationAdapter {
    private static final String SERVICE = "cloudscheduler.googleapis.com";
    private static final String LOCATION_PATTERN = "projects/[^/]+/locations/[^/:]+";
    private static final Set<String> VIEWER = Set.of("cloudscheduler.jobs.get", "cloudscheduler.jobs.list");
    private static final Set<String> ADMIN = Set.of(
            "cloudscheduler.jobs.create", "cloudscheduler.jobs.get", "cloudscheduler.jobs.list",
            "cloudscheduler.jobs.update", "cloudscheduler.jobs.delete", "cloudscheduler.jobs.pause",
            "cloudscheduler.jobs.enable", "cloudscheduler.jobs.run");

    @Override
    public String serviceName() {
        return "scheduler";
    }

    @Override
    public Set<Class<?>> restControllers() {
        return Set.of(SchedulerHttpController.class);
    }

    @Override
    public Set<String> grpcServices() {
        return Set.of("google.cloud.scheduler.v1.CloudScheduler");
    }

    @Override
    public IamOperation restOperation(String method, Map<String, String> path, JsonNode body) {
        String parent = "projects/" + path.get("project") + "/locations/" + path.get("location");
        String target = path.containsKey("jobId") ? parent + "/jobs/" + path.get("jobId") : parent;
        String operation = Character.toUpperCase(method.charAt(0)) + method.substring(1);
        if (method.equals("createJob") || method.equals("updateJob")) {
            Job job = ProtoJson.merge(body.toString(), Job.newBuilder()).build();
            if (method.equals("updateJob") && !job.getName().isEmpty()) {
                target = job.getName();
            }
            return jobOperation(operation, target, job);
        }
        return new IamOperation(operation, target);
    }

    @Override
    public IamOperation grpcOperation(String method, JsonNode request) {
        Message message = switch (method) {
            case "CreateJob" -> ProtoJson.merge(request.toString(), CreateJobRequest.newBuilder()).build();
            case "ListJobs" -> ProtoJson.merge(request.toString(), ListJobsRequest.newBuilder()).build();
            case "GetJob" -> ProtoJson.merge(request.toString(), GetJobRequest.newBuilder()).build();
            case "UpdateJob" -> ProtoJson.merge(request.toString(), UpdateJobRequest.newBuilder()).build();
            case "DeleteJob" -> ProtoJson.merge(request.toString(), DeleteJobRequest.newBuilder()).build();
            case "PauseJob" -> ProtoJson.merge(request.toString(), PauseJobRequest.newBuilder()).build();
            case "ResumeJob" -> ProtoJson.merge(request.toString(), ResumeJobRequest.newBuilder()).build();
            case "RunJob" -> ProtoJson.merge(request.toString(), RunJobRequest.newBuilder()).build();
            default -> throw unsupported("operation " + method);
        };
        return grpcOperation(method, message);
    }

    @Override
    public IamOperation grpcOperation(String method, Message request) {
        return switch (request) {
            case CreateJobRequest value -> jobOperation(method, value.getParent(), value.getJob());
            case ListJobsRequest value -> new IamOperation(method, value.getParent());
            case UpdateJobRequest value -> jobOperation(method, value.getJob().getName(), value.getJob());
            case GetJobRequest value -> new IamOperation(method, value.getName());
            case DeleteJobRequest value -> new IamOperation(method, value.getName());
            case PauseJobRequest value -> new IamOperation(method, value.getName());
            case ResumeJobRequest value -> new IamOperation(method, value.getName());
            case RunJobRequest value -> new IamOperation(method, value.getName());
            default -> throw unsupported("operation " + method);
        };
    }

    @Override
    public List<IamPermissionCheck> checks(IamOperation operation) {
        String permission = switch (operation.name()) {
            case "CreateJob" -> "cloudscheduler.jobs.create";
            case "ListJobs" -> "cloudscheduler.jobs.list";
            case "GetJob" -> "cloudscheduler.jobs.get";
            case "UpdateJob" -> "cloudscheduler.jobs.update";
            case "DeleteJob" -> "cloudscheduler.jobs.delete";
            case "PauseJob" -> "cloudscheduler.jobs.pause";
            case "ResumeJob" -> "cloudscheduler.jobs.enable";
            case "RunJob" -> "cloudscheduler.jobs.run";
            default -> throw unsupported("operation " + operation.name());
        };
        if (!operation.relatedResource().isEmpty()) {
            throw unsupported("authenticated HTTP targets (OAuth/OIDC and service-account act-as)");
        }
        IamResource target;
        if (operation.name().equals("CreateJob") || operation.name().equals("ListJobs")) {
            if (!operation.resource().matches(LOCATION_PATTERN)) {
                throw unsupported("location " + operation.resource());
            }
            String project = IamResource.projectName(operation.resource());
            target = IamResource.projectChild(SERVICE, "Location", operation.resource(), project);
        } else {
            target = requireResource(operation.resource());
        }
        return List.of(new IamPermissionCheck(permission, target));
    }

    @Override
    public Optional<IamResource> resource(String name) {
        if (name != null && name.matches(LOCATION_PATTERN + "/jobs/[^/:]+")) {
            return Optional.of(IamResource.projectChild(SERVICE, "Job", name, IamResource.projectName(name)));
        }
        return Optional.empty();
    }

    @Override
    public Map<String, Set<String>> roles() {
        return Map.of("roles/cloudscheduler.admin", ADMIN, "roles/cloudscheduler.viewer", VIEWER,
                "roles/cloudscheduler.jobRunner", Set.of("cloudscheduler.jobs.run"));
    }

    @Override
    public Map<String, Set<String>> basicRoles() {
        return Map.of("roles/owner", ADMIN, "roles/editor", ADMIN, "roles/viewer", VIEWER);
    }

    private static IamOperation jobOperation(String method, String target, Job job) {
        boolean authenticated = job.hasHttpTarget()
                && (job.getHttpTarget().hasOidcToken() || job.getHttpTarget().hasOauthToken());
        return new IamOperation(method, target, authenticated ? "authenticatedHttpTarget" : "");
    }

}
