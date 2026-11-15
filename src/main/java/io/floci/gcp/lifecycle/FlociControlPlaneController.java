package io.floci.gcp.lifecycle;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.Resettable;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.StorageFactory;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The Floci control plane under the canonical {@code /_floci} prefix shared by every Floci emulator
 * (floci-loft decision 0022). The endpoints are {@link EmulatorInfoController}'s; only the prefix
 * differs. The cloud-specific {@code /_floci-gcp/bigquery} and {@code /_floci-gcp/gke} routes are not
 * part of the contract and are not served here.
 */
@Path("/_floci")
@Produces(MediaType.APPLICATION_JSON)
public class FlociControlPlaneController extends EmulatorInfoController {

    @Inject
    public FlociControlPlaneController(ServiceRegistry serviceRegistry,
                                       InitLifecycleState initLifecycleState,
                                       EmulatorConfig config,
                                       StorageFactory storageFactory,
                                       Instance<Resettable> resettables) {
        super(serviceRegistry, initLifecycleState, config, storageFactory, resettables);
    }
}
