package io.floci.gcp.services.resourcemanager;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.GcpResourceNames;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.common.ProjectRecord;
import io.floci.gcp.core.common.ProjectRegistry;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.authorization.IamAuthorizationService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cloud Resource Manager v1 projects surface, backed by the shared {@link ProjectRegistry}.
 * Projects used without being created are registered on first sight (unless strict mode is
 * on) and report a stable project number and createTime.
 */
@ApplicationScoped
public class ResourceManagerService {

    private static final Logger LOG = Logger.getLogger(ResourceManagerService.class);

    static final String PROJECT_TYPE = "type.googleapis.com/google.cloudresourcemanager.v1.Project";
    static final String CREATION_STATUS_TYPE =
            "type.googleapis.com/google.cloudresourcemanager.v1.ProjectCreationStatus";
    static final String OPERATION_PREFIX = "operations/cp.";

    private static final Pattern DISPLAY_NAME = Pattern.compile("[A-Za-z0-9\\-'\" !]{4,30}");
    private static final Pattern LABEL_KEY = Pattern.compile("[\\p{Ll}\\p{Lo}][\\p{Ll}\\p{Lo}\\p{N}_-]{0,62}");
    private static final Pattern LABEL_VALUE = Pattern.compile("[\\p{Ll}\\p{Lo}\\p{N}_-]{0,63}");
    private static final Set<String> PARENT_TYPES = Set.of("organization", "folder");
    private static final int MAX_LABELS = 256;

    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final IamService iamService;
    private final IamAuthorizationService iamAuthorization;
    private final ProjectRegistry projects;

    @Inject
    public ResourceManagerService(ServiceRegistry serviceRegistry, EmulatorConfig config, IamService iamService,
                                  IamAuthorizationService iamAuthorization, ProjectRegistry projects) {
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.iamService = iamService;
        this.iamAuthorization = iamAuthorization;
        this.projects = projects;
    }

    ResourceManagerService(ProjectRegistry projects) {
        this.serviceRegistry = null;
        this.config = null;
        this.iamService = null;
        this.iamAuthorization = null;
        this.projects = projects;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("resourcemanager")
                .enabled(config.services().resourcemanager().enabled())
                .protocol(ServiceProtocol.REST)
                .resourceClasses(ResourceManagerController.class, ResourceManagerProjectsController.class,
                        ResourceManagerIamController.class, ResourceManagerOperationsController.class)
                .api("cloudresourcemanager.googleapis.com", "Cloud Resource Manager API")
                .build());
        iamService.registerPolicyResourceResolver("projects/*", this::requireProjectForPolicy);
    }

    public Map<String, Object> getProject(String idOrNumber) {
        requireProjectId(idOrNumber);
        return toJson(projects.require(idOrNumber));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> createProject(Map<String, Object> body) {
        if (body == null) {
            throw GcpException.invalidArgument("Request contains an invalid argument.");
        }
        String projectId = stringField(body, "projectId");
        if (!ProjectRegistry.isValidProjectId(projectId)) {
            throw GcpException.invalidArgument("Project ID '" + (projectId == null ? "" : projectId)
                    + "' is invalid. It must be 6 to 30 lowercase letters, digits, or hyphens. "
                    + "It must start with a letter. Trailing hyphens are prohibited.");
        }
        ProjectRecord existing = projects.find(projectId).orElse(null);
        if (existing != null && existing.isCreated()) {
            throw GcpException.alreadyExists("Requested entity already exists");
        }
        String name = validName(stringField(body, "name"));
        Map<String, String> labels = validLabels(body.get("labels"));
        ProjectRecord project = existing != null ? existing : new ProjectRecord();
        project.setProjectId(projectId);
        project.setName(name);
        project.setLabels(labels);
        applyParent(project, body.get("parent"));
        ProjectRecord created = existing != null ? adopt(project) : projects.create(project);
        LOG.infof("create project id=%s number=%s", created.getProjectId(), created.getProjectNumber());
        return operation(created);
    }

    public Map<String, Object> updateProject(String idOrNumber, Map<String, Object> body) {
        requireProjectId(idOrNumber);
        ProjectRecord project = projects.require(idOrNumber);
        if (!project.isActive()) {
            throw GcpException.failedPrecondition("Project " + project.getProjectId() + " is not active.");
        }
        Map<String, Object> update = body != null ? body : Map.of();
        project.setName(validName(stringField(update, "name")));
        project.setLabels(validLabels(update.get("labels")));
        if (update.get("parent") != null) {
            applyParent(project, update.get("parent"));
        }
        projects.save(project);
        return toJson(project);
    }

    public void deleteProject(String idOrNumber) {
        requireProjectId(idOrNumber);
        ProjectRecord project = projects.require(idOrNumber);
        if (project.isActive()) {
            project.setLifecycleState(ProjectRecord.DELETE_REQUESTED);
            projects.save(project);
            LOG.infof("delete requested project id=%s", project.getProjectId());
        }
    }

    public void undeleteProject(String idOrNumber) {
        requireProjectId(idOrNumber);
        ProjectRecord project = projects.require(idOrNumber);
        if (!ProjectRecord.DELETE_REQUESTED.equals(project.getLifecycleState())) {
            throw GcpException.failedPrecondition("Project " + project.getProjectId()
                    + " is not in state DELETE_REQUESTED.");
        }
        project.setLifecycleState(ProjectRecord.ACTIVE);
        projects.save(project);
    }

    public Map<String, Object> getAncestry(String idOrNumber) {
        requireProjectId(idOrNumber);
        ProjectRecord project = projects.require(idOrNumber);
        List<Map<String, Object>> ancestors = new ArrayList<>();
        ancestors.add(Map.of("resourceId", resourceId("project", project.getProjectId())));
        if (project.hasParent()) {
            ancestors.add(Map.of("resourceId", resourceId(project.getParentType(), project.getParentId())));
        }
        return Map.of("ancestor", ancestors);
    }

    public Map<String, Object> listProjects(String filter, int pageSize, String pageToken) {
        if (pageSize < 0) {
            throw GcpException.invalidArgument("Page size must not be negative.");
        }
        ProjectFilter parsed = ProjectFilter.parse(filter);
        List<Map<String, Object>> matching = projects.list().stream()
                .filter(parsed::matches)
                .filter(this::visible)
                .map(ResourceManagerService::toJson)
                .toList();
        PageToken.Page<Map<String, Object>> page = PageToken.paginate(matching, pageSize, pageToken);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("projects", page.items());
        if (page.nextPageToken() != null) {
            response.put("nextPageToken", page.nextPageToken());
        }
        return response;
    }

    private boolean visible(ProjectRecord project) {
        return iamAuthorization == null || !iamAuthorization.testPermissions("projects/" + project.getProjectId(),
                List.of("resourcemanager.projects.get")).isEmpty();
    }

    public Map<String, Object> getOperation(String operationId) {
        String name = "operations/" + operationId;
        if (!name.startsWith(OPERATION_PREFIX)) {
            throw GcpException.notFound("Operation not found: " + name);
        }
        String number = name.substring(OPERATION_PREFIX.length());
        return projects.find(number)
                .filter(p -> ProjectRegistry.isNumber(number) && p.isCreated())
                .map(ResourceManagerService::operation)
                .orElseThrow(() -> GcpException.notFound("Operation not found: " + name));
    }

    private ProjectRecord adopt(ProjectRecord project) {
        project.setCreated(true);
        project.setLifecycleState(ProjectRecord.ACTIVE);
        projects.save(project);
        return project;
    }

    private void requireProjectForPolicy(String resource) {
        projects.require(GcpResourceNames.projectIdOf(resource));
    }

    static Map<String, Object> toJson(ProjectRecord project) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("projectNumber", project.getProjectNumber());
        json.put("projectId", project.getProjectId());
        json.put("lifecycleState", project.getLifecycleState());
        if (project.getName() != null) {
            json.put("name", project.getName());
        }
        json.put("createTime", project.getCreateTime());
        if (!project.getLabels().isEmpty()) {
            json.put("labels", project.getLabels());
        }
        if (project.hasParent()) {
            json.put("parent", resourceId(project.getParentType(), project.getParentId()));
        }
        return json;
    }

    private static Map<String, Object> operation(ProjectRecord project) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("@type", CREATION_STATUS_TYPE);
        metadata.put("createTime", project.getCreateTime());
        metadata.put("gettable", true);
        metadata.put("ready", true);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("@type", PROJECT_TYPE);
        response.putAll(toJson(project));
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("name", OPERATION_PREFIX + project.getProjectNumber());
        operation.put("metadata", metadata);
        operation.put("done", true);
        operation.put("response", response);
        return operation;
    }

    private static Map<String, Object> resourceId(String type, String id) {
        Map<String, Object> resourceId = new LinkedHashMap<>();
        resourceId.put("type", type);
        resourceId.put("id", id);
        return resourceId;
    }

    private static void requireProjectId(String idOrNumber) {
        if (idOrNumber == null || idOrNumber.isBlank()) {
            throw GcpException.invalidArgument("Project ID is required.");
        }
    }

    private static String stringField(Map<String, Object> body, String field) {
        Object value = body.get(field);
        return value instanceof String s && !s.isEmpty() ? s : null;
    }

    private static String validName(String name) {
        if (name != null && !DISPLAY_NAME.matcher(name).matches()) {
            throw GcpException.invalidArgument("Project name '" + name + "' is invalid. It must be 4 to 30 "
                    + "letters, digits, hyphens, single-quotes, double-quotes, spaces, or exclamation points.");
        }
        return name;
    }

    private static Map<String, String> validLabels(Object raw) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (raw == null) {
            return labels;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw GcpException.invalidArgument("Project labels must be a map of strings.");
        }
        if (map.size() > MAX_LABELS) {
            throw GcpException.invalidArgument("A project can have at most " + MAX_LABELS + " labels.");
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
            if (!LABEL_KEY.matcher(key).matches() || !LABEL_VALUE.matcher(value).matches()) {
                throw GcpException.invalidArgument("Label '" + key + "' with value '" + value + "' is invalid.");
            }
            labels.put(key, value);
        }
        return labels;
    }

    private static void applyParent(ProjectRecord project, Object raw) {
        if (raw == null) {
            return;
        }
        if (!(raw instanceof Map<?, ?> parent)) {
            throw GcpException.invalidArgument("Project parent must be a ResourceId.");
        }
        Object type = parent.get("type");
        Object id = parent.get("id");
        if (!(type instanceof String t) || !PARENT_TYPES.contains(t)
                || !(id instanceof String i) || i.isBlank()) {
            throw GcpException.invalidArgument(
                    "Project parent must have type \"organization\" or \"folder\" and a non-empty id.");
        }
        project.setParentType(t);
        project.setParentId(i);
    }
}
