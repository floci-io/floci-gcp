package io.floci.gcp.services.cloudmonitoring;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.monitoring.v3.ListUptimeCheckConfigsResponse;
import com.google.monitoring.v3.ListUptimeCheckIpsResponse;
import com.google.monitoring.v3.UptimeCheckConfig;
import com.google.monitoring.v3.UptimeCheckIp;
import com.google.monitoring.v3.UptimeCheckRegion;
import com.google.protobuf.Duration;
import com.google.protobuf.FieldMask;
import com.google.protobuf.util.FieldMaskUtil;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.common.ProjectRegistry;
import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.lifecycle.GrpcServerManager;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * UptimeCheckService configuration. Checks live in a global store keyed by full resource name so
 * the prober can read them without a request context.
 */
@ApplicationScoped
public class UptimeCheckService {
    private static final Pattern PARENT = Pattern.compile("projects/[^/\\s]+");
    private static final Pattern NAME = Pattern.compile("projects/[^/\\s]+/uptimeCheckConfigs/[^/\\s]+");
    private static final Set<Long> PERIODS = Set.of(60L, 300L, 600L, 900L);
    private static final Set<String> RESOURCE_TYPES = Set.of("uptime_url", "gce_instance", "gae_app",
            "aws_ec2_instance", "aws_elb_load_balancer", "k8s_service", "servicedirectory_service", "cloud_run_revision");
    private static final Set<String> MUTABLE = Set.of("display_name", "http_check", "tcp_check", "timeout",
            "content_matchers", "selected_regions", "user_labels");
    /** Emulated checker locations; the emulator has no regional checkers, so these are placeholders. */
    private static final List<UptimeCheckIp> CHECKER_IPS = List.of(
            UptimeCheckIp.newBuilder().setRegion(UptimeCheckRegion.USA).setLocation("Iowa").setIpAddress("127.0.0.1").build(),
            UptimeCheckIp.newBuilder().setRegion(UptimeCheckRegion.EUROPE).setLocation("Belgium").setIpAddress("127.0.0.1").build(),
            UptimeCheckIp.newBuilder().setRegion(UptimeCheckRegion.ASIA_PACIFIC).setLocation("Singapore").setIpAddress("127.0.0.1").build(),
            UptimeCheckIp.newBuilder().setRegion(UptimeCheckRegion.SOUTH_AMERICA).setLocation("Sao Paulo").setIpAddress("127.0.0.1").build());

    private final StorageBackend<String, String> checks;
    private final ProjectRegistry projects;
    private final GrpcServerManager grpcServerManager;
    private final SecureRandom random = new SecureRandom();
    private final Object lock = new Object();

    @Inject
    public UptimeCheckService(StorageFactory storageFactory, ProjectRegistry projects, GrpcServerManager grpcServerManager) {
        this(storageFactory.createGlobal("monitoring", "monitoring-uptime-checks.json",
                new TypeReference<Map<String, String>>() {}), projects, grpcServerManager);
    }

    UptimeCheckService(StorageBackend<String, String> checks, ProjectRegistry projects, GrpcServerManager grpcServerManager) {
        this.checks = checks;
        this.projects = projects;
        this.grpcServerManager = grpcServerManager;
    }

    void onStart(@Observes StartupEvent ev) {
        grpcServerManager.bind(new UptimeCheckController(this));
    }

    public UptimeCheckConfig create(String parent, UptimeCheckConfig requested) {
        String project = validateParent(parent);
        synchronized (lock) {
            String name;
            do {
                name = project + "/uptimeCheckConfigs/" + slug(requested.getDisplayName())
                        + Long.toHexString(random.nextLong() >>> 4);
            } while (checks.get(name).isPresent());
            UptimeCheckConfig created = normalize(requested.toBuilder().setName(name)).build();
            validate(created);
            checks.put(name, ProtoJson.print(created));
            return created;
        }
    }

    public UptimeCheckConfig get(String name) {
        String key = validateName(name);
        synchronized (lock) {
            return require(key);
        }
    }

    public ListUptimeCheckConfigsResponse list(String parent, int pageSize, String pageToken) {
        String project = validateParent(parent);
        synchronized (lock) {
            List<UptimeCheckConfig> all = checks.keys().stream()
                    .filter(key -> key.startsWith(project + "/uptimeCheckConfigs/")).sorted().map(this::require).toList();
            PageToken.Page<UptimeCheckConfig> page = PageToken.paginate(all,
                    pageSize > 0 ? Math.min(pageSize, all.size()) : all.size(), pageToken);
            ListUptimeCheckConfigsResponse.Builder response = ListUptimeCheckConfigsResponse.newBuilder()
                    .addAllUptimeCheckConfigs(page.items()).setTotalSize(all.size());
            if (page.nextPageToken() != null) {
                response.setNextPageToken(page.nextPageToken());
            }
            return response.build();
        }
    }

    /**
     * Without a mask the configuration is replaced; with one, only display_name, http_check,
     * tcp_check, timeout, content_matchers, selected_regions (and user_labels) may change.
     */
    public UptimeCheckConfig update(UptimeCheckConfig requested, FieldMask updateMask) {
        String key = validateName(requested.getName());
        synchronized (lock) {
            UptimeCheckConfig current = require(key);
            UptimeCheckConfig.Builder updated;
            if (updateMask.getPathsCount() == 0) {
                updated = requested.toBuilder();
                if (!requested.getMonitoredResource().equals(current.getMonitoredResource())
                        || !requested.getResourceGroup().equals(current.getResourceGroup())) {
                    throw GcpException.invalidArgument("The monitored resource of an uptime check cannot be changed");
                }
            } else {
                updated = current.toBuilder();
                for (String path : updateMask.getPathsList()) {
                    if (!MUTABLE.contains(path.split("\\.", 2)[0])) {
                        throw GcpException.invalidArgument("Field " + path + " of an uptime check cannot be updated");
                    }
                }
                FieldMaskUtil.merge(updateMask, requested, updated, new FieldMaskUtil.MergeOptions()
                        .setReplaceRepeatedFields(true).setReplaceMessageFields(true).setReplacePrimitiveFields(true));
            }
            UptimeCheckConfig result = normalize(updated.setName(key)).build();
            validate(result);
            checks.put(key, ProtoJson.print(result));
            return result;
        }
    }

    public void delete(String name) {
        String key = validateName(name);
        synchronized (lock) {
            require(key);
            checks.delete(key);
        }
    }

    public ListUptimeCheckIpsResponse listIps(int pageSize, String pageToken) {
        PageToken.Page<UptimeCheckIp> page = PageToken.paginate(CHECKER_IPS,
                pageSize > 0 ? Math.min(pageSize, CHECKER_IPS.size()) : CHECKER_IPS.size(), pageToken);
        ListUptimeCheckIpsResponse.Builder response = ListUptimeCheckIpsResponse.newBuilder().addAllUptimeCheckIps(page.items());
        if (page.nextPageToken() != null) {
            response.setNextPageToken(page.nextPageToken());
        }
        return response.build();
    }

    /** Every check of every project, for the prober. */
    List<UptimeCheckConfig> listAll() {
        synchronized (lock) {
            return checks.keys().stream().sorted().map(this::require).toList();
        }
    }

    // ── Validation ───────────────────────────────────────────────────────

    private static UptimeCheckConfig.Builder normalize(UptimeCheckConfig.Builder check) {
        if (!check.hasPeriod()) {
            check.setPeriod(Duration.newBuilder().setSeconds(60));
        }
        if (check.hasHttpCheck()) {
            UptimeCheckConfig.HttpCheck.Builder http = check.getHttpCheck().toBuilder();
            if (http.getRequestMethod() == UptimeCheckConfig.HttpCheck.RequestMethod.METHOD_UNSPECIFIED) {
                http.setRequestMethod(UptimeCheckConfig.HttpCheck.RequestMethod.GET);
            }
            if (http.getPath().isEmpty()) {
                http.setPath("/");
            } else if (!http.getPath().startsWith("/")) {
                http.setPath("/" + http.getPath());
            }
            if (http.getPort() == 0) {
                http.setPort(http.getUseSsl() ? 443 : 80);
            }
            check.setHttpCheck(http);
        }
        return check;
    }

    private static void validate(UptimeCheckConfig check) {
        if (check.getDisplayName().isBlank()) {
            throw GcpException.invalidArgument("An uptime check requires a display name");
        }
        if (!check.hasMonitoredResource() && !check.hasResourceGroup() && !check.hasSyntheticMonitor()) {
            throw GcpException.invalidArgument("An uptime check requires a monitored resource, resource group or synthetic monitor");
        }
        if (check.hasMonitoredResource() && !RESOURCE_TYPES.contains(check.getMonitoredResource().getType())) {
            throw GcpException.invalidArgument("Unsupported monitored resource type for an uptime check: "
                    + check.getMonitoredResource().getType());
        }
        if (check.hasMonitoredResource() && check.getMonitoredResource().getType().equals("uptime_url")
                && check.getMonitoredResource().getLabelsOrDefault("host", "").isBlank()) {
            throw GcpException.invalidArgument("An uptime_url resource requires a host label");
        }
        if (!check.hasSyntheticMonitor() && !check.hasHttpCheck() && !check.hasTcpCheck()) {
            throw GcpException.invalidArgument("An uptime check requires an HTTP or TCP check");
        }
        if (check.hasTcpCheck() && (check.getTcpCheck().getPort() <= 0 || check.getTcpCheck().getPort() > 65535)) {
            throw GcpException.invalidArgument("A TCP check requires a port");
        }
        if (!PERIODS.contains(check.getPeriod().getSeconds()) || check.getPeriod().getNanos() != 0) {
            throw GcpException.invalidArgument("The period must be 60s, 300s, 600s or 900s");
        }
        long timeout = check.getTimeout().getSeconds();
        if (!check.hasTimeout() || timeout < 1 || timeout > 60 || (timeout == 60 && check.getTimeout().getNanos() > 0)) {
            throw GcpException.invalidArgument("The timeout is required and must be between 1 and 60 seconds");
        }
    }

    private static String slug(String displayName) {
        String slug = displayName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40);
        }
        return slug.isEmpty() ? "" : slug + "-";
    }

    private UptimeCheckConfig require(String key) {
        return checks.get(key).map(json -> ProtoJson.merge(json, UptimeCheckConfig.newBuilder()).build())
                .orElseThrow(() -> GcpException.notFound("Uptime check not found: " + key));
    }

    private String validateParent(String parent) {
        if (parent == null || !PARENT.matcher(parent).matches()) {
            throw GcpException.invalidArgument("Invalid project name: " + parent);
        }
        return projects == null ? parent : projects.canonicalProjectName(parent);
    }

    private String validateName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw GcpException.invalidArgument("Invalid uptime check name: " + name);
        }
        return projects == null ? name : projects.canonicalResourceName(name);
    }
}
