package io.floci.gcp.services.cloudbilling;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Minimal Cloud Billing v1 surface: project billing association and a synthetic set of
 * open billing accounts. Required by IaC tooling: the Terraform Google provider reads
 * {@code projects.getBillingInfo} for every {@code google_project} and
 * {@code data.google_project}.
 *
 * <p>Any well-formed billing account ID is accepted and reported as an open account,
 * because the emulator has no account directory. A project with no association reports
 * {@code billingEnabled=false}.
 */
@ApplicationScoped
public class CloudBillingService {

    static final String DEFAULT_ACCOUNT = "billingAccounts/000000-000000-000000";
    private static final Pattern ACCOUNT_NAME = Pattern.compile("billingAccounts/[A-Za-z0-9]{6}-[A-Za-z0-9]{6}-[A-Za-z0-9]{6}");
    private static final String NO_ACCOUNT = "";

    private final StorageBackend<String, String> associations;
    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;

    @Inject
    public CloudBillingService(StorageFactory storageFactory, ServiceRegistry serviceRegistry, EmulatorConfig config) {
        this.associations = storageFactory.createGlobal("cloudbilling", "cloudbilling.json",
                new TypeReference<Map<String, String>>() {});
        this.serviceRegistry = serviceRegistry;
        this.config = config;
    }

    CloudBillingService(StorageBackend<String, String> associations, EmulatorConfig config) {
        this.associations = associations;
        this.serviceRegistry = null;
        this.config = config;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("cloudbilling")
                .enabled(config.services().cloudbilling().enabled())
                .storageKey("cloudbilling")
                .protocol(ServiceProtocol.REST)
                .resourceClasses(CloudBillingController.class, CloudBillingAccountsController.class)
                .build());
    }

    public Map<String, Object> getBillingInfo(String project) {
        requireProject(project);
        return billingInfo(project, associations.get(project).orElse(NO_ACCOUNT));
    }

    public Map<String, Object> updateBillingInfo(String project, Map<String, Object> body) {
        requireProject(project);
        Object requested = body == null ? null : body.get("billingAccountName");
        String account = requested == null ? NO_ACCOUNT : requested.toString();
        if (!account.isEmpty() && !ACCOUNT_NAME.matcher(account).matches()) {
            throw GcpException.invalidArgument("Invalid billing account name: " + account
                    + ". Expected billingAccounts/XXXXXX-XXXXXX-XXXXXX.");
        }
        associations.put(project, account);
        return billingInfo(project, account);
    }

    public Map<String, Object> getBillingAccount(String accountId) {
        String name = "billingAccounts/" + accountId;
        if (!ACCOUNT_NAME.matcher(name).matches()) {
            throw GcpException.invalidArgument("Invalid billing account name: " + name);
        }
        return billingAccount(name);
    }

    public Map<String, Object> listBillingAccounts(int pageSize, String pageToken) {
        TreeSet<String> names = new TreeSet<>();
        names.add(DEFAULT_ACCOUNT);
        for (String value : associations.scan(key -> true)) {
            if (!value.isEmpty()) {
                names.add(value);
            }
        }
        List<Map<String, Object>> accounts = new ArrayList<>();
        for (String name : names) {
            accounts.add(billingAccount(name));
        }
        PageToken.Page<Map<String, Object>> page = PageToken.paginate(accounts, pageSize, pageToken);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("billingAccounts", page.items());
        if (page.nextPageToken() != null) {
            response.put("nextPageToken", page.nextPageToken());
        }
        return response;
    }

    public Map<String, Object> listProjects(String accountId, int pageSize, String pageToken) {
        String name = "billingAccounts/" + accountId;
        if (!ACCOUNT_NAME.matcher(name).matches()) {
            throw GcpException.invalidArgument("Invalid billing account name: " + name);
        }
        List<String> projects = new ArrayList<>();
        for (String project : new TreeSet<>(associations.keys())) {
            if (name.equals(associations.get(project).orElse(NO_ACCOUNT))) {
                projects.add(project);
            }
        }
        List<Map<String, Object>> infos = new ArrayList<>();
        for (String project : projects) {
            infos.add(billingInfo(project, name));
        }
        PageToken.Page<Map<String, Object>> page = PageToken.paginate(infos, pageSize, pageToken);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("projectBillingInfo", page.items());
        if (page.nextPageToken() != null) {
            response.put("nextPageToken", page.nextPageToken());
        }
        return response;
    }

    private static Map<String, Object> billingInfo(String project, String account) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "projects/" + project + "/billingInfo");
        info.put("projectId", project);
        if (!account.isEmpty()) {
            info.put("billingAccountName", account);
        }
        info.put("billingEnabled", !account.isEmpty());
        return info;
    }

    private static Map<String, Object> billingAccount(String name) {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("name", name);
        account.put("open", true);
        account.put("displayName", name.equals(DEFAULT_ACCOUNT) ? "Floci Billing Account" : name.substring("billingAccounts/".length()));
        return account;
    }

    private static void requireProject(String project) {
        if (project == null || project.isBlank()) {
            throw GcpException.invalidArgument("Project ID is required.");
        }
    }
}
