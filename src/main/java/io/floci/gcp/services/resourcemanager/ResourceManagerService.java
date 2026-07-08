package io.floci.gcp.services.resourcemanager;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal Cloud Resource Manager v1 surface. The emulator accepts any project ID
 * (multi-tenancy is keyed by project ID, projects are never created), so every
 * project resolves to an ACTIVE project with a stable synthetic project number.
 * Required by IaC tooling: the Terraform/Pulumi Google providers verify a project
 * via {@code cloudresourcemanager.v1.Projects.GetProject} before touching
 * project-scoped resources such as {@code google_project_service}.
 */
@ApplicationScoped
public class ResourceManagerService {

    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final String createTime = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();

    @Inject
    public ResourceManagerService(ServiceRegistry serviceRegistry, EmulatorConfig config) {
        this.serviceRegistry = serviceRegistry;
        this.config = config;
    }

    ResourceManagerService(EmulatorConfig config) {
        this.serviceRegistry = null;
        this.config = config;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("resourcemanager")
                .enabled(config.services().resourcemanager().enabled())
                .protocol(ServiceProtocol.REST)
                .resourceClasses(ResourceManagerController.class)
                .build());
    }

    public Map<String, Object> getProject(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw GcpException.invalidArgument("Project ID is required.");
        }
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("projectNumber", projectNumber(projectId));
        project.put("projectId", projectId);
        project.put("lifecycleState", "ACTIVE");
        project.put("name", projectId);
        project.put("createTime", createTime);
        return project;
    }

    static String projectNumber(String projectId) {
        return String.valueOf(100_000_000_000L + Math.floorMod((long) projectId.hashCode(), 900_000_000_000L));
    }
}
