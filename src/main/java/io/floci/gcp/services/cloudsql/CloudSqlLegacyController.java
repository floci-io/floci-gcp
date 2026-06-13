package io.floci.gcp.services.cloudsql;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;

@Path("/sql/v1beta4/projects/{project}")
@ApplicationScoped
public class CloudSqlLegacyController extends CloudSqlController {

    CloudSqlLegacyController() {
    }

    @Inject
    public CloudSqlLegacyController(CloudSqlService service) {
        super(service);
    }
}
