package io.floci.gcp.services.cloudsql;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;

@Path("/v1beta4")
@ApplicationScoped
public class CloudSqlV1Beta4GlobalController extends CloudSqlGlobalController {

    CloudSqlV1Beta4GlobalController() {
    }

    @Inject
    public CloudSqlV1Beta4GlobalController(CloudSqlService service) {
        super(service);
    }
}
