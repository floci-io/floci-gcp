package io.floci.gcp.services.iam;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** Returns policy resources applicable to a resource, from closest to farthest ancestor. */
@ApplicationScoped
public class IamResourceHierarchy {

    public List<String> policyResourcesFor(IamResource resource) {
        if (resource.projectResource() == null || resource.projectResource().equals(resource.policyResource())) {
            return List.of(resource.policyResource());
        }
        return List.of(resource.policyResource(), resource.projectResource());
    }
}
