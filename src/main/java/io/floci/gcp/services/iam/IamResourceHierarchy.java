package io.floci.gcp.services.iam;

import io.floci.gcp.core.common.ProjectRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
        if (resource.projectResource() == null || resource.projectResource().equals(resource.policyResource())) {
            return List.of(projectPolicy(resource.policyResource()));
        }
        return List.of(resource.policyResource(), projectPolicy(resource.projectResource()));
    }

    private String projectPolicy(String resource) {
        return projects == null ? resource : projects.canonicalProjectName(resource);
    }
}
