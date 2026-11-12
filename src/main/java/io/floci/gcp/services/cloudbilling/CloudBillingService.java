package io.floci.gcp.services.cloudbilling;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Cloud Billing v1 stub: project billing links stored on the {@link ProjectRegistry}, billing
 * accounts derived from those links plus one default open account, and a billing-account
 * {@code testIamPermissions} that grants every requested permission.
 */
@ApplicationScoped
public class CloudBillingService {

    private static final Logger LOG = Logger.getLogger(CloudBillingService.class);

    private static final Pattern BILLING_ACCOUNT_NAME = Pattern.compile("billingAccounts/[^/]+");

    static final String DEFAULT_BILLING_ACCOUNT = "billingAccounts/000000-000000-000000";

    private static final String DEFAULT_BILLING_ACCOUNT_DISPLAY_NAME = "My Billing Account";

    private static final Pattern BILLING_ACCOUNT_ID = Pattern.compile("[0-9A-Z]{6}-[0-9A-Z]{6}-[0-9A-Z]{6}");

    private static final String DEFAULT_CURRENCY_CODE = "USD";

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

    public Map<String, Object> getBillingAccount(String account) {
        return billingAccount(requireBillingAccountName(account));
    }

    public Map<String, Object> listBillingAccounts(int pageSize, String pageToken) {
        TreeSet<String> names = new TreeSet<>();
        names.add(DEFAULT_BILLING_ACCOUNT);
        for (ProjectRecord project : projects.list()) {
            if (!project.getBillingAccountName().isEmpty()) {
                names.add(project.getBillingAccountName());
            }
        }
        PageToken.Page<String> page = PageToken.paginate(new ArrayList<>(names), pageSize, pageToken);
        List<Map<String, Object>> accounts = new ArrayList<>();
        for (String name : page.items()) {
            accounts.add(billingAccount(name));
        }
        return pageResponse("billingAccounts", accounts, page.nextPageToken());
    }

    public Map<String, Object> listProjectBillingInfo(String account, int pageSize, String pageToken) {
        String name = requireBillingAccountName(account);
        List<ProjectRecord> linked = new ArrayList<>();
        for (ProjectRecord project : projects.list()) {
            if (name.equals(project.getBillingAccountName())) {
                linked.add(project);
            }
        }
        linked.sort(Comparator.comparing(ProjectRecord::getProjectId));
        PageToken.Page<ProjectRecord> page = PageToken.paginate(linked, pageSize, pageToken);
        List<Map<String, Object>> infos = new ArrayList<>();
        for (ProjectRecord project : page.items()) {
            infos.add(billingInfo(project));
        }
        return pageResponse("projectBillingInfo", infos, page.nextPageToken());
    }

    public Map<String, Object> testIamPermissions(List<String> permissions) {
        return Map.of("permissions", permissions != null ? permissions : List.of());
    }

    private static Map<String, Object> billingAccount(String name) {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("name", name);
        account.put("open", true);
        account.put("displayName", DEFAULT_BILLING_ACCOUNT.equals(name)
                ? DEFAULT_BILLING_ACCOUNT_DISPLAY_NAME
                : "Billing Account " + name.substring("billingAccounts/".length()));
        account.put("masterBillingAccount", "");
        account.put("parent", "");
        account.put("currencyCode", DEFAULT_CURRENCY_CODE);
        return account;
    }

    private static String requireBillingAccountName(String account) {
        if (!BILLING_ACCOUNT_ID.matcher(account).matches()) {
            throw GcpException.invalidArgument("Request contains an invalid argument.");
        }
        return "billingAccounts/" + account;
    }

    private static Map<String, Object> pageResponse(String field, List<Map<String, Object>> items,
                                                    String nextPageToken) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put(field, items);
        response.put("nextPageToken", nextPageToken != null ? nextPageToken : "");
        return response;
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
