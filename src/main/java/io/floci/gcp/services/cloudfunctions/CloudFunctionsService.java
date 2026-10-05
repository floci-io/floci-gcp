package io.floci.gcp.services.cloudfunctions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.cloud.functions.v2.Environment;
import com.google.cloud.functions.v2.Function;
import com.google.cloud.functions.v2.GenerateUploadUrlResponse;
import com.google.cloud.functions.v2.ListFunctionsResponse;
import com.google.cloud.functions.v2.OperationMetadata;
import com.google.cloud.functions.v2.OperationType;
import com.google.cloud.functions.v2.ServiceConfig;
import com.google.cloud.functions.v2.StorageSource;
import com.google.longrunning.Operation;
import com.google.protobuf.Empty;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.GcpResourceNames;
import io.floci.gcp.core.common.LocationCatalog;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.gcs.GcsService;
import io.floci.gcp.services.operations.LongRunningOperationsService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class CloudFunctionsService {

    private static final Logger LOG = Logger.getLogger(CloudFunctionsService.class);

    private final StorageBackend<String, String> functionStore;
    private final LongRunningOperationsService operations;
    private final GcsService gcsService;
    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final LocationCatalog locations;

    @Inject
    public CloudFunctionsService(StorageFactory storageFactory,
                                 LongRunningOperationsService operations,
                                 GcsService gcsService,
                                 ServiceRegistry serviceRegistry,
                                 EmulatorConfig config,
                                 LocationCatalog locations) {
        this.functionStore = storageFactory.create("cloudfunctions-functions", "cloudfunctions-functions.json",
                new TypeReference<Map<String, String>>() {});
        this.operations = operations;
        this.gcsService = gcsService;
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.locations = locations;
    }

    CloudFunctionsService(StorageBackend<String, String> functionStore,
                          LongRunningOperationsService operations,
                          GcsService gcsService) {
        this.functionStore = functionStore;
        this.operations = operations;
        this.gcsService = gcsService;
        this.serviceRegistry = null;
        this.config = null;
        this.locations = LocationCatalog.lenient();
    }

    CloudFunctionsService(StorageBackend<String, String> functionStore,
                          LongRunningOperationsService operations,
                          GcsService gcsService,
                          ServiceRegistry serviceRegistry) {
        this.functionStore = functionStore;
        this.operations = operations;
        this.gcsService = gcsService;
        this.serviceRegistry = serviceRegistry;
        this.config = null;
        this.locations = LocationCatalog.lenient();
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("cloudfunctions")
                .enabled(config.services().cloudfunctions().enabled())
                .storageKey("cloudfunctions")
                .protocol(ServiceProtocol.REST)
                .resourceClasses(CloudFunctionsController.class)
                .build());
    }

    public Operation createFunction(String project, String location, String functionId, String body, boolean validateOnly) {
        locations.requireLocation(location, LocationCatalog.Kind.REGION);
        String parent = parent(project, location);
        Function requested = ProtoJson.merge(body, Function.newBuilder()).build();
        String id = firstPresent(functionId, GcpResourceNames.lastSegment(requested.getName()));
        if (id == null || id.isBlank()) {
            throw GcpException.invalidArgument("functionId query parameter is required");
        }
        String name = parent + "/functions/" + id;
        if (functionStore.get(name).isPresent()) {
            throw GcpException.alreadyExists("Cloud Function already exists: " + name);
        }

        Timestamp now = timestampNow();
        Function function = populateFunction(project, location, id, requested, name, now);
        if (!validateOnly) {
            functionStore.put(name, ProtoJson.print(function));
        }
        LOG.infof("create Cloud Function name=%s validateOnly=%s", name, validateOnly);
        return done(parent, function, operationMetadata(name, "create", OperationType.CREATE_FUNCTION, now), validateOnly);
    }

    public Function getFunction(String name) {
        return functionStore.get(name)
                .map(json -> ProtoJson.merge(json, Function.newBuilder()).build())
                .orElseThrow(() -> GcpException.notFound("Cloud Function not found: " + name));
    }

    public ListFunctionsResponse listFunctions(String project, String location, int pageSize, String pageToken) {
        locations.requireListLocation(location, LocationCatalog.Kind.REGION);
        boolean allLocations = "-".equals(location);
        String prefix = allLocations ? "projects/" + project + "/locations/" : parent(project, location) + "/functions/";
        List<Function> functions = functionStore.scan(k -> k.startsWith(prefix) && k.contains("/functions/")).stream()
                .map(json -> ProtoJson.merge(json, Function.newBuilder()).build())
                .sorted(Comparator.comparing(Function::getName))
                .toList();
        PageToken.Page<Function> page = PageToken.paginate(functions, pageSize, pageToken);
        ListFunctionsResponse.Builder response = ListFunctionsResponse.newBuilder()
                .addAllFunctions(page.items());
        if (page.nextPageToken() != null) {
            response.setNextPageToken(page.nextPageToken());
        }
        return response.build();
    }

    public Operation deleteFunction(String name, boolean validateOnly) {
        getFunction(name);
        if (!validateOnly) {
            functionStore.delete(name);
        }
        Timestamp now = timestampNow();
        LOG.infof("delete Cloud Function name=%s validateOnly=%s", name, validateOnly);
        return done(parentFromName(name), Empty.getDefaultInstance(),
                operationMetadata(name, "delete", OperationType.DELETE_FUNCTION, now), validateOnly);
    }

    public GenerateUploadUrlResponse generateUploadUrl(String project, String location, String baseUrl) {
        if (serviceRegistry != null && !serviceRegistry.isEnabled("gcs")) {
            throw GcpException.unavailable("Cloud Functions source upload requires Cloud Storage to be enabled.");
        }
        String bucket = sourceBucket(project, location);
        String object = "source-" + UUID.randomUUID() + ".zip";
        String uploadBaseUrl = trimTrailingSlash(baseUrl);
        ensureSourceBucket(bucket, project, location, baseUrl);
        String uploadUrl = uploadBaseUrl + "/" + bucket + "/" + object;
        return GenerateUploadUrlResponse.newBuilder()
                .setUploadUrl(uploadUrl)
                .setStorageSource(StorageSource.newBuilder()
                        .setBucket(bucket)
                        .setObject(object)
                        .setSourceUploadUrl(uploadUrl)
                        .build())
                .build();
    }

    private void ensureSourceBucket(String bucket, String project, String location, String baseUrl) {
        try {
            gcsService.createBucket(bucket, project, baseUrl, Map.of("location", location));
        } catch (GcpException e) {
            if (!"ALREADY_EXISTS".equals(e.getGcpStatus())) {
                throw e;
            }
        }
    }

    private static Function populateFunction(String project, String location, String id,
                                             Function requested, String name, Timestamp now) {
        String url = "https://" + location + "-" + project + ".cloudfunctions.net/" + id;
        String revision = name + "/revisions/" + id + "-00001";
        ServiceConfig.Builder serviceConfig = requested.hasServiceConfig()
                ? requested.getServiceConfig().toBuilder()
                : ServiceConfig.newBuilder();
        serviceConfig
                .setService("projects/" + project + "/locations/" + location + "/services/" + id)
                .setUri(url)
                .setRevision(revision)
                .setAllTrafficOnLatestRevision(true);

        Function.Builder builder = requested.toBuilder()
                .setName(name)
                .setState(Function.State.ACTIVE)
                .setCreateTime(now)
                .setUpdateTime(now)
                .setUrl(url)
                .setServiceConfig(serviceConfig);
        if (builder.getEnvironment() == Environment.ENVIRONMENT_UNSPECIFIED) {
            builder.setEnvironment(Environment.GEN_2);
        }
        return builder.build();
    }

    private static OperationMetadata operationMetadata(String target, String verb,
                                                       OperationType type, Timestamp now) {
        return OperationMetadata.newBuilder()
                .setCreateTime(now)
                .setEndTime(now)
                .setTarget(target)
                .setVerb(verb)
                .setStatusDetail("Done")
                .setApiVersion("v2")
                .setOperationType(type)
                .build();
    }

    private Operation done(String parent, Message response, Message metadata, boolean transientOnly) {
        return transientOnly
                ? operations.doneTransient(parent, response, metadata)
                : operations.done(parent, response, metadata);
    }

    private static Timestamp timestampNow() {
        Instant now = Instant.now();
        return Timestamp.newBuilder()
                .setSeconds(now.getEpochSecond())
                .setNanos(now.getNano())
                .build();
    }

    private static String firstPresent(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }

    private static String sourceBucket(String project, String location) {
        return sanitizeBucket("gcf-v2-sources-" + project + "-" + location);
    }

    private static String sanitizeBucket(String value) {
        String sanitized = value.toLowerCase().replaceAll("[^a-z0-9._-]", "-");
        sanitized = sanitized.replaceAll("^[^a-z0-9]+", "").replaceAll("[^a-z0-9]+$", "");
        return sanitized.isBlank() ? "gcf-v2-sources" : sanitized;
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String parent(String project, String location) {
        return "projects/" + project + "/locations/" + location;
    }

    private static String parentFromName(String name) {
        int functions = name.indexOf("/functions/");
        return functions < 0 ? name : name.substring(0, functions);
    }
}
