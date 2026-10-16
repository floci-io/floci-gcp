package io.floci.gcp.services.cloudbilling;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ProjectRecord;
import io.floci.gcp.core.common.ProjectRegistry;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Cloud Billing v1 stub: project billing links stored on the {@link ProjectRegistry}, and a
 * billing-account {@code testIamPermissions} that grants every requested permission.
 */
@ApplicationScoped
public class CloudBillingService {

    private static final Logger LOG = Logger.getLogger(CloudBillingService.class);

    private static final Pattern BILLING_ACCOUNT_NAME = Pattern.compile("billingAccounts/[^/]+");

    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final ProjectRegistry projects;

    @Inject
    public CloudBillingService(ServiceRegistry serviceRegistry, EmulatorConfig config, ProjectRegistry projects) {
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.projects = projects;
    }

    CloudBillingService(ProjectRegistry projects) {
        this.serviceRegistry = null;
        this.config = null;
        this.projects = projects;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("cloudbilling")
                .enabled(config.services().cloudbilling().enabled())
                .protocol(ServiceProtocol.REST)
                .resourceClasses(CloudBillingController.class, CloudBillingAccountsController.class)
                .api("cloudbilling.googleapis.com", "Cloud Billing API")
                .build());
    }

    public Map<String, Object> getBillingInfo(String project) {
        return billingInfo(projects.require(project));
    }

    public Map<String, Object> updateBillingInfo(String project, Map<String, Object> body) {
        Object raw = body != null ? body.get("billingAccountName") : null;
        String billingAccountName = raw instanceof String s ? s : "";
        if (!billingAccountName.isEmpty() && !BILLING_ACCOUNT_NAME.matcher(billingAccountName).matches()) {
            throw GcpException.invalidArgument("Billing account name '" + billingAccountName
                    + "' is invalid. It must have the form billingAccounts/{billing_account_id}.");
        }
        ProjectRecord record = projects.update(project,
                existing -> existing.setBillingAccountName(billingAccountName));
        LOG.infof("update billing info project=%s billingAccount=%s", record.getProjectId(), billingAccountName);
        return billingInfo(record);
    }

    public Map<String, Object> testIamPermissions(List<String> permissions) {
        return Map.of("permissions", permissions != null ? permissions : List.of());
    }

    private static Map<String, Object> billingInfo(ProjectRecord project) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "projects/" + project.getProjectId() + "/billingInfo");
        info.put("projectId", project.getProjectId());
        info.put("billingAccountName", project.getBillingAccountName());
        info.put("billingEnabled", !project.getBillingAccountName().isEmpty());
        return info;
    }
}
