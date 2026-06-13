package io.floci.gcp.services.cloudsql;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;

@Path("/sql/v1beta4")
@ApplicationScoped
public class CloudSqlLegacyGlobalController extends CloudSqlGlobalController {

    CloudSqlLegacyGlobalController() {
    }

    @Inject
    public CloudSqlLegacyGlobalController(CloudSqlService service) {
        super(service);
    }
}
