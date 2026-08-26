package io.floci.gcp.services.iam;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** Returns policy resources applicable to a resource, from closest to farthest ancestor. */
@ApplicationScoped
public class IamResourceHierarchy {

    public List<String> policyResourcesFor(IamResource resource) {
        return List.of(resource.policyResource());
    }
}
