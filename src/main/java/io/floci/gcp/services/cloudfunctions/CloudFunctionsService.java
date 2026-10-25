package io.floci.gcp.services.cloudfunctions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.api.FieldBehavior;
import com.google.api.FieldBehaviorProto;
import com.google.cloud.functions.v2.Environment;
import com.google.cloud.functions.v2.Function;
import com.google.cloud.functions.v2.GenerateUploadUrlResponse;
import com.google.cloud.functions.v2.ListFunctionsResponse;
import com.google.cloud.functions.v2.OperationMetadata;
import com.google.cloud.functions.v2.OperationType;
import com.google.cloud.functions.v2.ServiceConfig;
import com.google.cloud.functions.v2.StorageSource;
import com.google.iam.v1.Policy;
import com.google.iam.v1.TestIamPermissionsResponse;
import com.google.longrunning.Operation;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Empty;
import com.google.protobuf.FieldMask;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.FieldMaskUtil;
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
import io.floci.gcp.services.iam.IamPolicyCodec;
import io.floci.gcp.services.iam.IamService;
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
    private final IamService iamService;
    // Guards each read-modify-write of a function record against a concurrent create, update or delete.
    private final Object mutationLock = new Object();

    @Inject
    public CloudFunctionsService(StorageFactory storageFactory,
                                 LongRunningOperationsService operations,
                                 GcsService gcsService,
                                 ServiceRegistry serviceRegistry,
                                 EmulatorConfig config,
                                 LocationCatalog locations,
                                 IamService iamService) {
        this.functionStore = storageFactory.create("cloudfunctions-functions", "cloudfunctions-functions.json",
                new TypeReference<Map<String, String>>() {});
        this.operations = operations;
        this.gcsService = gcsService;
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.locations = locations;
        this.iamService = iamService;
    }

    CloudFunctionsService(StorageBackend<String, String> functionStore,
                          LongRunningOperationsService operations,
                          GcsService gcsService,
                          IamService iamService) {
        this(functionStore, operations, gcsService, null, iamService);
    }

    CloudFunctionsService(StorageBackend<String, String> functionStore,
                          LongRunningOperationsService operations,
                          GcsService gcsService,
                          ServiceRegistry serviceRegistry,
                          IamService iamService) {
        this.functionStore = functionStore;
        this.operations = operations;
        this.gcsService = gcsService;
        this.serviceRegistry = serviceRegistry;
        this.config = null;
        this.locations = LocationCatalog.lenient();
        this.iamService = iamService;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("cloudfunctions")
                .enabled(config.services().cloudfunctions().enabled())
                .storageKey("cloudfunctions")
                .protocol(ServiceProtocol.REST)
                .resourceClasses(CloudFunctionsController.class)
                .api("cloudfunctions.googleapis.com", "Cloud Functions API")
                .build());
        iamService.registerPolicyResourceResolver("projects/*/locations/*/functions/*", this::getFunction);
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
        Timestamp now = timestampNow();
        Function function = populateFunction(project, location, id, requested, name, now);
        synchronized (mutationLock) {
            if (functionStore.get(name).isPresent()) {
                throw GcpException.alreadyExists("Cloud Function already exists: " + name);
            }
            if (!validateOnly) {
                functionStore.put(name, ProtoJson.print(function));
            }
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

    public Operation updateFunction(String name, String body, String updateMask) {
        Function requested = ProtoJson.merge(body, Function.newBuilder()).build();
        FieldMask mask = parseUpdateMask(updateMask);
        Timestamp now = timestampNow();
        Function updated;
        synchronized (mutationLock) {
            updated = applyUpdate(getFunction(name), requested, mask, now);
            functionStore.put(name, ProtoJson.print(updated));
        }
        LOG.infof("update Cloud Function name=%s updateMask=%s", name, updateMask);
        return done(parentFromName(name), updated,
                operationMetadata(name, "update", OperationType.UPDATE_FUNCTION, now), false);
    }

    public Policy getIamPolicy(String resource) {
        getFunction(resource);
        return IamPolicyCodec.toProtoPolicy(iamService.getPolicy(resource));
    }

    public Policy setIamPolicy(String resource, Policy policy) {
        getFunction(resource);
        return IamPolicyCodec.toProtoPolicy(iamService.setPolicy(resource, IamPolicyCodec.toStoredPolicy(policy)));
    }

    public TestIamPermissionsResponse testIamPermissions(String resource, List<String> permissions) {
        TestIamPermissionsResponse.Builder response = TestIamPermissionsResponse.newBuilder();
        if (functionStore.get(resource).isPresent()) {
            response.addAllPermissions(iamService.testPermissions(resource, permissions));
        }
        return response.build();
    }

    public Operation deleteFunction(String name, boolean validateOnly) {
        if (validateOnly) {
            getFunction(name);
        } else {
            synchronized (mutationLock) {
                getFunction(name);
                iamService.deleteResourceAndPolicy(name, () -> functionStore.delete(name));
            }
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

        Function.Builder builder = requested.toBuilder();
        pinOutputOnly(builder, Function.getDefaultInstance());
        builder.setName(name)
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

    /**
     * Applies an update: with no mask every settable field is replaced, otherwise only the masked
     * paths are. Output-only fields always keep their stored values, and a change to a settable
     * field of the build or service config rolls the function onto a new revision, as a redeploy does.
     */
    private static Function applyUpdate(Function existing, Function requested, FieldMask mask, Timestamp now) {
        Function.Builder builder;
        if (mask.getPathsCount() == 0) {
            builder = requested.toBuilder();
        } else {
            builder = existing.toBuilder();
            FieldMaskUtil.merge(mask, requested, builder, new FieldMaskUtil.MergeOptions()
                    .setReplaceMessageFields(true)
                    .setReplaceRepeatedFields(true)
                    .setReplacePrimitiveFields(true));
        }
        if (builder.getEnvironment() != Environment.ENVIRONMENT_UNSPECIFIED
                && builder.getEnvironment() != existing.getEnvironment()) {
            throw GcpException.invalidArgument("A function's environment cannot be changed.");
        }

        boolean redeploy = mask.getPathsCount() == 0 || mask.getPathsList().stream()
                .anyMatch(path -> (path.startsWith("build_config") || path.startsWith("service_config"))
                        && !outputOnlyPath(path));
        pinOutputOnly(builder, existing);
        String revision = existing.getServiceConfig().getRevision();
        builder.getServiceConfigBuilder()
                .setService(existing.getServiceConfig().getService())
                .setUri(existing.getServiceConfig().getUri())
                .setRevision(redeploy ? nextRevision(revision) : revision);
        return builder
                .setName(existing.getName())
                .setEnvironment(existing.getEnvironment())
                .setUpdateTime(now)
                .build();
    }

    /**
     * Gives every OUTPUT_ONLY field of {@code target}, in it and in the singular messages it holds,
     * the value it has in {@code stored}, so a request cannot set them. A singular message the
     * request omits comes back holding only its stored output-only values. Read from the proto's
     * field_behavior annotations, so fields added to the API later are covered too.
     */
    private static void pinOutputOnly(Message.Builder target, Message stored) {
        for (Descriptors.FieldDescriptor field : target.getDescriptorForType().getFields()) {
            if (outputOnly(field)) {
                if (field.isRepeated() ? stored.getRepeatedFieldCount(field) > 0 : stored.hasField(field)) {
                    target.setField(field, stored.getField(field));
                } else {
                    target.clearField(field);
                }
            } else if (field.getJavaType() == Descriptors.FieldDescriptor.JavaType.MESSAGE && !field.isRepeated()) {
                if (target.hasField(field)) {
                    pinOutputOnly(target.getFieldBuilder(field), (Message) stored.getField(field));
                } else if (stored.hasField(field) && !oneofTaken(target, field)) {
                    Message.Builder child = target.newBuilderForField(field);
                    pinOutputOnly(child, (Message) stored.getField(field));
                    Message restored = child.build();
                    if (!restored.equals(restored.getDefaultInstanceForType())) {
                        target.setField(field, restored);
                    }
                }
            }
        }
    }

    private static boolean oneofTaken(Message.Builder target, Descriptors.FieldDescriptor field) {
        Descriptors.OneofDescriptor oneof = field.getRealContainingOneof();
        return oneof != null && target.hasOneof(oneof);
    }

    // True when the mask path is, or lies under, an OUTPUT_ONLY field (paths are validated before this).
    private static boolean outputOnlyPath(String path) {
        Descriptors.Descriptor type = Function.getDescriptor();
        for (String segment : path.split("\\.")) {
            Descriptors.FieldDescriptor field = type.findFieldByName(segment);
            if (field == null) {
                return false;
            }
            if (outputOnly(field)) {
                return true;
            }
            if (field.getJavaType() != Descriptors.FieldDescriptor.JavaType.MESSAGE) {
                return false;
            }
            type = field.getMessageType();
        }
        return false;
    }

    private static boolean outputOnly(Descriptors.FieldDescriptor field) {
        return field.getOptions().getExtension(FieldBehaviorProto.fieldBehavior)
                .contains(FieldBehavior.OUTPUT_ONLY);
    }

    private static FieldMask parseUpdateMask(String updateMask) {
        if (updateMask == null || updateMask.isBlank()) {
            return FieldMask.getDefaultInstance();
        }
        FieldMask mask = FieldMaskUtil.fromJsonString(updateMask.replace(" ", ""));
        if (!FieldMaskUtil.isValid(Function.class, mask)) {
            throw GcpException.invalidArgument("Invalid update_mask: " + updateMask);
        }
        return mask;
    }

    // Revisions are named <function>-NNNNN; an update that redeploys takes the next number.
    private static String nextRevision(String revision) {
        int dash = revision.lastIndexOf('-');
        if (dash < 0) {
            return revision;
        }
        try {
            int next = Integer.parseInt(revision.substring(dash + 1)) + 1;
            return revision.substring(0, dash + 1) + String.format("%05d", next);
        } catch (NumberFormatException e) {
            return revision;
        }
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
