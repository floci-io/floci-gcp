package io.floci.gcp.services.cloudsql;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;

@Path("/v1beta4/projects/{project}")
@ApplicationScoped
public class CloudSqlV1Beta4Controller extends CloudSqlController {

    CloudSqlV1Beta4Controller() {
    }

    @Inject
    public CloudSqlV1Beta4Controller(CloudSqlService service) {
        super(service);
    }
}
