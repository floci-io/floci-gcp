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
import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
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

@ApplicationScoped
public class ServiceUsageService {

    private static final Logger LOG = Logger.getLogger(ServiceUsageService.class);

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_BATCH_ENABLE = 20;
    private static final int MAX_BATCH_GET = 30;

    private final StorageBackend<String, String> stateStore;
    private final LongRunningOperationsService operations;
    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;

    @Inject
    public ServiceUsageService(StorageFactory storageFactory,
                               LongRunningOperationsService operations,
                               ServiceRegistry serviceRegistry,
                               EmulatorConfig config) {
        this.stateStore = storageFactory.createGlobal("serviceusage", "serviceusage.json",
                new TypeReference<Map<String, String>>() {});
        this.operations = operations;
        this.serviceRegistry = serviceRegistry;
        this.config = config;
    }

    ServiceUsageService(StorageBackend<String, String> stateStore,
                        LongRunningOperationsService operations,
                        EmulatorConfig config) {
        this.stateStore = stateStore;
        this.operations = operations;
        this.serviceRegistry = null;
        this.config = config;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("serviceusage")
                .enabled(config.services().serviceusage().enabled())
                .storageKey("serviceusage")
                .protocol(ServiceProtocol.REST)
                .resourceClasses(ServiceUsageController.class, ServiceUsageOperationsController.class)
                .build());
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
        if (readState(name) != State.ENABLED) {
            throw GcpException.failedPrecondition(
                    "Service " + serviceId + " is not enabled for consumer projects/" + project + ".");
        }
        Service service = writeState(project, serviceId, State.DISABLED);
        LOG.infof("disable service name=%s", name);
        return done(DisableServiceResponse.newBuilder().setService(service).build(), name);
    }

    public Service get(String project, String serviceId) {
        requireServiceId(serviceId);
        String name = serviceName(project, serviceId);
        return buildService(project, serviceId, readState(name));
    }

    public ListServicesResponse list(String project, int pageSize, String pageToken, String filter) {
        State wanted = parseFilter(filter);
        if (pageSize > MAX_PAGE_SIZE) {
            throw GcpException.invalidArgument("Requested page size cannot exceed " + MAX_PAGE_SIZE + ".");
        }
        int effectivePageSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : pageSize;
        String prefix = "projects/" + project + "/services/";
        List<Service> services = stateStore.keys().stream()
                .filter(k -> k.startsWith(prefix))
                .map(name -> buildService(project, name.substring(prefix.length()), readState(name)))
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
            response.addServices(buildService(project, name.substring(prefix.length()), readState(name)));
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
        stateStore.put(serviceName(project, serviceId), state.name());
        return buildService(project, serviceId, state);
    }

    private State readState(String name) {
        return stateStore.get(name)
                .map(State::valueOf)
                .orElse(State.DISABLED);
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
