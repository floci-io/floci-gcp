package io.floci.gcp.services.serviceusage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.api.serviceusage.v1.BatchEnableServicesRequest;
import com.google.api.serviceusage.v1.BatchEnableServicesResponse;
import com.google.api.serviceusage.v1.BatchGetServicesResponse;
import com.google.api.serviceusage.v1.DisableServiceResponse;
import com.google.api.serviceusage.v1.EnableServiceResponse;
import com.google.api.serviceusage.v1.ListServicesResponse;
import com.google.api.serviceusage.v1.OperationMetadata;
import com.google.api.serviceusage.v1.Service;
import com.google.api.serviceusage.v1.ServiceConfig;
import com.google.api.serviceusage.v1.State;
import com.google.longrunning.Operation;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.common.ProjectNumbers;
import io.floci.gcp.core.common.ProjectRecord;
import io.floci.gcp.core.common.ProjectRegistry;
import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.common.ServiceStateProvider;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.operations.LongRunningOperationsService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@ApplicationScoped
public class ServiceUsageService implements ServiceStateProvider {

    private static final Logger LOG = Logger.getLogger(ServiceUsageService.class);

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_BATCH_ENABLE = 20;
    private static final int MAX_BATCH_GET = 30;

    private final StorageBackend<String, String> stateStore;
    private final LongRunningOperationsService operations;
    private final ServiceRegistry serviceRegistry;
    private final ProjectRegistry projectRegistry;
    private final EmulatorConfig config;
    private final Set<String> defaultEnabled;

    @Inject
    public ServiceUsageService(StorageFactory storageFactory,
                               LongRunningOperationsService operations,
                               ServiceRegistry serviceRegistry,
                               ProjectRegistry projectRegistry,
                               EmulatorConfig config) {
        this.stateStore = storageFactory.createGlobal("serviceusage", "serviceusage.json",
                new TypeReference<Map<String, String>>() {});
        this.operations = operations;
        this.serviceRegistry = serviceRegistry;
        this.projectRegistry = projectRegistry;
        this.config = config;
        EmulatorConfig.ServiceUsageServiceConfig serviceUsage = config.services().serviceusage();
        this.defaultEnabled = serviceUsage.enforcementEnabled() ? Set.copyOf(serviceUsage.defaultEnabled()) : Set.of();
    }

    ServiceUsageService(StorageBackend<String, String> stateStore,
                        LongRunningOperationsService operations,
                        EmulatorConfig config) {
        this(stateStore, operations, config, Set.of(), null);
    }

    ServiceUsageService(StorageBackend<String, String> stateStore,
                        LongRunningOperationsService operations,
                        EmulatorConfig config,
                        Set<String> defaultEnabled,
                        ProjectRegistry projectRegistry) {
        this.stateStore = stateStore;
        this.operations = operations;
        this.serviceRegistry = null;
        this.projectRegistry = projectRegistry;
        this.config = config;
        this.defaultEnabled = Set.copyOf(defaultEnabled);
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("serviceusage")
                .enabled(config.services().serviceusage().enabled())
                .storageKey("serviceusage")
                .protocol(ServiceProtocol.REST)
                .resourceClasses(ServiceUsageController.class, ServiceUsageOperationsController.class)
                .api("serviceusage.googleapis.com", "Service Usage API")
                .build());
    }

    @Override
    public boolean isServiceEnabled(String project, String apiName) {
        return readState(project, apiName) == State.ENABLED;
    }

    public Operation enable(String project, String serviceId) {
        requireServiceId(serviceId);
        Service service = writeState(project, serviceId, State.ENABLED);
        LOG.infof("enable service name=%s", service.getName());
        return done(EnableServiceResponse.newBuilder().setService(service).build(), service.getName());
    }

    public Operation disable(String project, String serviceId) {
        requireServiceId(serviceId);
        String name = serviceName(project, serviceId);
        if (readState(project, serviceId) != State.ENABLED) {
            throw GcpException.failedPrecondition(
                    "Service " + serviceId + " is not enabled for consumer projects/" + project + ".");
        }
        Service service = writeState(project, serviceId, State.DISABLED);
        LOG.infof("disable service name=%s", name);
        return done(DisableServiceResponse.newBuilder().setService(service).build(), name);
    }

    public Service get(String project, String serviceId) {
        requireServiceId(serviceId);
        return buildService(project, serviceId, readState(project, serviceId));
    }

    public ListServicesResponse list(String project, int pageSize, String pageToken, String filter) {
        State wanted = parseFilter(filter);
        if (pageSize > MAX_PAGE_SIZE) {
            throw GcpException.invalidArgument("Requested page size cannot exceed " + MAX_PAGE_SIZE + ".");
        }
        int effectivePageSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : pageSize;
        List<String> prefixes = stateKeys(project, "");
        List<Service> services = Stream.concat(
                        stateStore.keys().stream().flatMap(k -> prefixes.stream()
                                .filter(k::startsWith).map(prefix -> k.substring(prefix.length()))),
                        defaultEnabled.stream())
                .distinct()
                .map(serviceId -> buildService(project, serviceId, readState(project, serviceId)))
                .filter(s -> wanted == null || s.getState() == wanted)
                .sorted(Comparator.comparing(Service::getName))
                .toList();
        PageToken.Page<Service> page = PageToken.paginate(services, effectivePageSize, pageToken);
        ListServicesResponse.Builder response = ListServicesResponse.newBuilder()
                .addAllServices(page.items());
        if (page.nextPageToken() != null) {
            response.setNextPageToken(page.nextPageToken());
        }
        return response.build();
    }

    public Operation batchEnable(String project, String body) {
        BatchEnableServicesRequest request = ProtoJson.merge(body, BatchEnableServicesRequest.newBuilder()).build();
        List<String> serviceIds = request.getServiceIdsList();
        if (serviceIds.isEmpty()) {
            throw GcpException.invalidArgument("service_ids must not be empty.");
        }
        if (serviceIds.size() > MAX_BATCH_ENABLE) {
            throw GcpException.invalidArgument(
                    "A single request can enable a maximum of " + MAX_BATCH_ENABLE + " services at a time.");
        }
        serviceIds.forEach(ServiceUsageService::requireServiceId);
        List<Service> services = new ArrayList<>();
        for (String serviceId : serviceIds) {
            services.add(writeState(project, serviceId, State.ENABLED));
        }
        LOG.infof("batch enable services parent=projects/%s count=%d", project, services.size());
        BatchEnableServicesResponse response = BatchEnableServicesResponse.newBuilder()
                .addAllServices(services)
                .build();
        return done(response, services.stream().map(Service::getName).toArray(String[]::new));
    }

    public BatchGetServicesResponse batchGet(String project, List<String> names) {
        if (names == null || names.isEmpty()) {
            throw GcpException.invalidArgument("names must not be empty.");
        }
        if (names.size() > MAX_BATCH_GET) {
            throw GcpException.invalidArgument(
                    "A single request can get a maximum of " + MAX_BATCH_GET + " services at a time.");
        }
        String prefix = "projects/" + project + "/services/";
        BatchGetServicesResponse.Builder response = BatchGetServicesResponse.newBuilder();
        for (String name : names) {
            if (!name.startsWith(prefix)) {
                throw GcpException.invalidArgument(
                        "Service name " + name + " does not match parent projects/" + project + ".");
            }
            String serviceId = name.substring(prefix.length());
            response.addServices(buildService(project, serviceId, readState(project, serviceId)));
        }
        return response.build();
    }

    private Operation done(com.google.protobuf.Message response, String... resourceNames) {
        OperationMetadata metadata = OperationMetadata.newBuilder()
                .addAllResourceNames(List.of(resourceNames))
                .build();
        return operations.done("", response, metadata);
    }

    private Service writeState(String project, String serviceId, State state) {
        stateStore.put(stateKeys(project, serviceId).getFirst(), state.name());
        return buildService(project, serviceId, state);
    }

    private State readState(String project, String serviceId) {
        return stateKeys(project, serviceId).stream()
                .flatMap(key -> stateStore.get(key).stream())
                .findFirst()
                .map(State::valueOf)
                .orElseGet(() -> defaultEnabled.contains(serviceId) ? State.ENABLED : State.DISABLED);
    }

    /**
     * Where a project's state lives, newest first. State is written under the project ID, so a
     * registered project's number reads and writes the same record; the number key is still read
     * because releases before the project registry stored state under the project as given.
     */
    private List<String> stateKeys(String project, String serviceId) {
        if (projectRegistry == null) {
            return List.of(serviceName(project, serviceId));
        }
        String projectId = ProjectRegistry.isNumber(project)
                ? projectRegistry.find(project).map(ProjectRecord::getProjectId).orElse(project)
                : project;
        if (ProjectRegistry.isNumber(projectId)) {
            return List.of(serviceName(projectId, serviceId));
        }
        return List.of(serviceName(projectId, serviceId), serviceName(ProjectNumbers.of(projectId), serviceId));
    }

    private Service buildService(String project, String serviceId, State state) {
        return Service.newBuilder()
                .setName(serviceName(project, serviceId))
                .setParent("projects/" + project)
                .setConfig(ServiceConfig.newBuilder().setName(serviceId))
                .setState(state)
                .build();
    }

    private static State parseFilter(String filter) {
        if (filter == null || filter.isBlank()) {
            return null;
        }
        return switch (filter) {
            case "state:ENABLED" -> State.ENABLED;
            case "state:DISABLED" -> State.DISABLED;
            default -> throw GcpException.invalidArgument(
                    "Invalid filter " + filter + ". The allowed filter strings are state:ENABLED and state:DISABLED.");
        };
    }

    private static void requireServiceId(String serviceId) {
        if (serviceId == null || serviceId.isBlank()) {
            throw GcpException.invalidArgument("Service name is required.");
        }
    }

    private static String serviceName(String project, String serviceId) {
        return "projects/" + project + "/services/" + serviceId;
    }
}
