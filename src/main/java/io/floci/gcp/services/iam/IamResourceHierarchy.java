package io.floci.gcp.services.iam;

import io.floci.gcp.core.common.ProjectRecord;
import io.floci.gcp.core.common.ProjectRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

/** Returns policy resources applicable to a resource, from closest to farthest ancestor. */
@ApplicationScoped
public class IamResourceHierarchy {
    private final ProjectRegistry projects;

    public IamResourceHierarchy() {
        this(null);
    }

    @Inject
    public IamResourceHierarchy(ProjectRegistry projects) {
        this.projects = projects;
    }

    public List<String> policyResourcesFor(IamResource resource) {
        List<String> resources = new ArrayList<>();
        resources.add(projectPolicy(resource.policyResource()));
        String project = resource.projectResource();
        if (project != null && !project.equals(resource.policyResource())) {
            resources.add(projectPolicy(project));
        }
        if (projects != null && resource.inheritOrganizationPolicy() && project != null) {
            projects.find(project.substring("projects/".length()))
                    .filter(ProjectRecord::hasParent)
                    .filter(record -> "organization".equals(record.getParentType()))
                    .ifPresent(record -> resources.add("organizations/" + record.getParentId()));
        }
        return List.copyOf(resources);
    }

    private String projectPolicy(String resource) {
        return projects == null ? resource : projects.canonicalProjectName(resource);
    }
}
