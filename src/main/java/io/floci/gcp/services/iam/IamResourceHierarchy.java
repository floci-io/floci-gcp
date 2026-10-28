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

    @Inject
    public IamResourceHierarchy(ProjectRegistry projects) {
        this.projects = projects;
    }

    public IamResourceHierarchy() {
        this.projects = null;
    }

    public List<String> policyResourcesFor(IamResource resource) {
        List<String> resources = new ArrayList<>();
        resources.add(resource.policyResource());
        String project = resource.projectResource();
        if (project != null && !project.equals(resource.policyResource())) {
            resources.add(project);
        }
        if (projects != null && "logging.googleapis.com".equals(resource.service()) && project != null) {
            projects.find(project.substring("projects/".length()))
                    .filter(ProjectRecord::hasParent)
                    .filter(record -> "organization".equals(record.getParentType()))
                    .ifPresent(record -> resources.add("organizations/" + record.getParentId()));
        }
        return List.copyOf(resources);
    }
}
