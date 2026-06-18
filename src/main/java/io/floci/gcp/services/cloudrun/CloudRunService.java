package io.floci.gcp.services.cloudrun;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.cloud.run.v2.Condition;
import com.google.cloud.run.v2.ListRevisionsResponse;
import com.google.cloud.run.v2.ListServicesResponse;
import com.google.cloud.run.v2.Revision;
import com.google.cloud.run.v2.TrafficTarget;
import com.google.cloud.run.v2.TrafficTargetAllocationType;
import com.google.cloud.run.v2.TrafficTargetStatus;
import com.google.iam.v1.Binding;
import com.google.iam.v1.Policy;
import com.google.iam.v1.TestIamPermissionsResponse;
import com.google.longrunning.Operation;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.google.rpc.Code;
import com.google.rpc.Status;
import com.google.type.Expr;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.GcpResourceNames;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.cloudrun.model.CloudRunRuntimeInstance;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.floci.gcp.services.operations.LongRunningOperationsService;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
public class CloudRunService {

    private static final Logger LOG = Logger.getLogger(CloudRunService.class);

    private final StorageBackend<String, String> serviceStore;
    private final StorageBackend<String, String> revisionStore;
    private final LongRunningOperationsService operations;
    private final IamService iamService;
    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final CloudRunRuntimeService runtimeService;
    private final CloudRunUrlService urlService;
    private final ExecutorService operationExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            runnable -> {
                Thread thread = new Thread(runnable, "cloudrun-operation");
                thread.setDaemon(true);
                return thread;
            });
    private final ScheduledExecutorService operationTimeouts = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "cloudrun-operation-timeout");
                thread.setDaemon(true);
                return thread;
            });
    private final ExecutorService cleanupExecutor = Executors.newFixedThreadPool(
            Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors())),
            runnable -> {
                Thread thread = new Thread(runnable, "cloudrun-cleanup");
                thread.setDaemon(true);
                return thread;
            });

    @Inject
    public CloudRunService(StorageFactory storageFactory,
                           LongRunningOperationsService operations,
                           IamService iamService,
                           ServiceRegistry serviceRegistry,
                           EmulatorConfig config,
                           CloudRunRuntimeService runtimeService,
                           CloudRunUrlService urlService) {
        this.serviceStore = storageFactory.createGlobal("cloudrun-services", "cloudrun-services.json",
                new TypeReference<Map<String, String>>() {});
        this.revisionStore = storageFactory.createGlobal("cloudrun-revisions", "cloudrun-revisions.json",
                new TypeReference<Map<String, String>>() {});
        this.operations = operations;
        this.iamService = iamService;
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.runtimeService = runtimeService;
        this.urlService = urlService;
    }

    CloudRunService(StorageBackend<String, String> serviceStore,
                    StorageBackend<String, String> revisionStore,
                    LongRunningOperationsService operations,
                    IamService iamService) {
        this(serviceStore, revisionStore, operations, iamService, null, null, null);
    }

    CloudRunService(StorageBackend<String, String> serviceStore,
                    StorageBackend<String, String> revisionStore,
                    LongRunningOperationsService operations,
                    IamService iamService,
                    EmulatorConfig config,
                    CloudRunRuntimeService runtimeService) {
        this(serviceStore, revisionStore, operations, iamService, config, runtimeService,
                config == null ? null : new CloudRunUrlService(config));
    }

    CloudRunService(StorageBackend<String, String> serviceStore,
                    StorageBackend<String, String> revisionStore,
                    LongRunningOperationsService operations,
                    IamService iamService,
                    EmulatorConfig config,
                    CloudRunRuntimeService runtimeService,
                    CloudRunUrlService urlService) {
        this.serviceStore = serviceStore;
        this.revisionStore = revisionStore;
        this.operations = operations;
        this.iamService = iamService;
        this.serviceRegistry = null;
        this.config = config;
        this.runtimeService = runtimeService;
        this.urlService = urlService;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("cloudrun")
                .enabled(config.services().cloudrun().enabled())
                .storageKey("cloudrun")
                .protocol(ServiceProtocol.REST)
                .resourceClasses(CloudRunController.class, CloudRunInvocationController.class,
                        CloudRunUrlRoutingFilter.class)
                .build());
    }

    @PreDestroy
    void shutdownOperationExecutor() {
        operationTimeouts.shutdownNow();
        operationExecutor.shutdownNow();
        cleanupExecutor.shutdownNow();
    }

    public Operation createService(String project, String location, String serviceId,
                                   String body, boolean validateOnly) {
        String parent = parent(project, location);
        com.google.cloud.run.v2.Service requested = ProtoJson
                .merge(body, com.google.cloud.run.v2.Service.newBuilder())
                .build();
        String id = firstPresent(serviceId, GcpResourceNames.lastSegment(requested.getName()));
        if (id == null || id.isBlank()) {
            throw GcpException.invalidArgument("serviceId query parameter is required");
        }
        String name = parent + "/services/" + id;
        if (serviceStore.get(name).isPresent()) {
            throw GcpException.alreadyExists("Cloud Run service already exists: " + name);
        }

        Timestamp now = timestampNow();
        String revisionName = name + "/revisions/" + id + "-00001";
        String uri = executionEnabled()
                ? invocationUri(project, location, id)
                : "https://" + id + "-" + stableSuffix(project, location) + ".a.run.app";

        com.google.cloud.run.v2.Service service = populateService(requested, name, revisionName, uri, now);
        Revision revision = populateRevision(requested, name, revisionName, now);
        boolean execute = executionEnabled();

        if (execute) {
            CloudRunRuntimeService.validateSupported(revision);
        }

        if (!validateOnly && execute) {
            service = runtimeStarting(service, now);
            revision = runtimeStarting(revision, now);
        }

        if (!validateOnly) {
            serviceStore.put(name, ProtoJson.print(service));
            revisionStore.put(revisionName, ProtoJson.print(revision));
        }

        LOG.infof("create Cloud Run service name=%s validateOnly=%s", name, validateOnly);
        if (validateOnly || !execute) {
            return done(parent, service, service, validateOnly);
        }

        Operation operation = operations.pending(parent, service);
        OperationGuard guard = new OperationGuard(operation.getName());
        com.google.cloud.run.v2.Service storedService = service;
        Revision storedRevision = revision;
        submitOperation(guard,
                () -> startRuntime(project, location, guard, storedService, storedRevision),
                () -> failRuntimeStart(guard, storedService, storedRevision,
                        "Cloud Run operation timed out", Code.DEADLINE_EXCEEDED_VALUE));
        return operation;
    }

    public com.google.cloud.run.v2.Service getService(String name) {
        return serviceStore.get(name)
                .map(json -> ProtoJson.merge(json, com.google.cloud.run.v2.Service.newBuilder()).build())
                .orElseThrow(() -> GcpException.notFound("Cloud Run service not found: " + name));
    }

    public ListServicesResponse listServices(String project, String location, int pageSize, String pageToken) {
        String prefix = parent(project, location) + "/services/";
        List<com.google.cloud.run.v2.Service> services = serviceStore.scan(k -> k.startsWith(prefix)).stream()
                .map(json -> ProtoJson.merge(json, com.google.cloud.run.v2.Service.newBuilder()).build())
                .sorted(Comparator.comparing(com.google.cloud.run.v2.Service::getName))
                .toList();
        PageToken.Page<com.google.cloud.run.v2.Service> page = PageToken.paginate(services, pageSize, pageToken);
        ListServicesResponse.Builder response = ListServicesResponse.newBuilder()
                .addAllServices(page.items());
        if (page.nextPageToken() != null) {
            response.setNextPageToken(page.nextPageToken());
        }
        return response.build();
    }

    public Operation deleteService(String name, boolean validateOnly) {
        com.google.cloud.run.v2.Service existing = getService(name);
        Timestamp now = timestampNow();
        com.google.cloud.run.v2.Service deleted = existing.toBuilder()
                .setDeleteTime(now)
                .setUpdateTime(now)
                .build();
        LOG.infof("delete Cloud Run service name=%s validateOnly=%s", name, validateOnly);
        if (validateOnly || !executionEnabled()) {
            if (!validateOnly) {
                deleteMetadata(name);
            }
            return done(parentFromName(name), deleted, deleted, validateOnly);
        }

        Operation operation = operations.pending(parentFromName(name), deleted);
        OperationGuard guard = new OperationGuard(operation.getName());
        submitOperation(guard,
                () -> deleteRuntime(guard, name, deleted),
                () -> guard.fail(Status.newBuilder()
                        .setCode(Code.DEADLINE_EXCEEDED_VALUE)
                        .setMessage("Cloud Run operation timed out")
                        .build(), deleted));
        return operation;
    }

    public Operation updateService(String name, String body, String updateMask, boolean validateOnly) {
        com.google.cloud.run.v2.Service existing = getService(name);
        com.google.cloud.run.v2.Service requested = ProtoJson
                .merge(body, com.google.cloud.run.v2.Service.newBuilder())
                .build();
        List<String> mask = updateMaskPaths(updateMask);
        boolean templateChanged = templateChanged(mask, existing, requested);
        Timestamp now = timestampNow();
        String revisionName = templateChanged ? nextRevisionName(name, existing) : existing.getLatestCreatedRevision();
        com.google.cloud.run.v2.Service updated = applyServiceUpdate(existing, requested, mask, revisionName, now);
        Revision revision = null;

        if (templateChanged) {
            revision = populateRevision(updated, name, revisionName, now);
            if (executionEnabled()) {
                CloudRunRuntimeService.validateSupported(revision);
                updated = runtimeUpdating(updated, existing.getLatestReadyRevision(), now);
                revision = runtimeStarting(revision, now);
            } else {
                updated = updated.toBuilder()
                        .setLatestReadyRevision(revisionName)
                        .build();
            }
        }

        LOG.infof("update Cloud Run service name=%s updateMask=%s validateOnly=%s",
                name, updateMask, validateOnly);
        if (validateOnly) {
            return done(parentFromName(name), updated, updated, true);
        }

        serviceStore.put(name, ProtoJson.print(updated));
        if (templateChanged) {
            revisionStore.put(revisionName, ProtoJson.print(revision));
        }

        if (!templateChanged || !executionEnabled()) {
            return done(parentFromName(name), updated, updated, false);
        }

        Operation operation = operations.pending(parentFromName(name), updated);
        OperationGuard guard = new OperationGuard(operation.getName());
        Revision storedRevision = revision;
        com.google.cloud.run.v2.Service storedService = updated;
        submitOperation(guard,
                () -> startRuntime(nameProject(name), nameLocation(name), guard, storedService, storedRevision),
                () -> failRuntimeStart(guard, storedService, storedRevision,
                        "Cloud Run operation timed out", Code.DEADLINE_EXCEEDED_VALUE));
        return operation;
    }

    public Optional<io.floci.gcp.services.cloudrun.model.CloudRunRuntimeInstance> readyRuntime(String serviceName) {
        com.google.cloud.run.v2.Service service = getService(serviceName);
        if (service.getLatestReadyRevision().isBlank()) {
            return Optional.empty();
        }
        return runtimeService.getReady(service.getLatestReadyRevision());
    }

    public Optional<InvocationRoute> resolveInvocationHost(String host) {
        if (urlService == null) {
            return Optional.empty();
        }
        Optional<CloudRunUrlService.ParsedHost> parsed = urlService.parseHost(host);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }

        CloudRunUrlService.ParsedHost candidate = parsed.get();
        String suffix = "/locations/" + candidate.location() + "/services/" + candidate.serviceId();
        return serviceStore.keys().stream()
                .filter(name -> name.endsWith(suffix))
                .filter(name -> urlService.matchesProjectToken(nameProject(name), candidate.projectToken()))
                .filter(name -> serviceStore.get(name)
                        .map(json -> storedUriMatchesHost(json, candidate))
                        .orElse(false))
                .findFirst()
                .map(name -> new InvocationRoute(nameProject(name), candidate.location(), candidate.serviceId()));
    }

    public boolean isGeneratedInvocationHost(String host) {
        return urlService != null && urlService.parseHost(host).isPresent();
    }

    public Revision getRevision(String name) {
        return revisionStore.get(name)
                .map(json -> ProtoJson.merge(json, Revision.newBuilder()).build())
                .orElseThrow(() -> GcpException.notFound("Cloud Run revision not found: " + name));
    }

    public ListRevisionsResponse listRevisions(String serviceName, int pageSize, String pageToken) {
        getService(serviceName);
        String prefix = serviceName + "/revisions/";
        List<Revision> revisions = revisionStore.scan(k -> k.startsWith(prefix)).stream()
                .map(json -> ProtoJson.merge(json, Revision.newBuilder()).build())
                .sorted(Comparator.comparing(Revision::getName))
                .toList();
        PageToken.Page<Revision> page = PageToken.paginate(revisions, pageSize, pageToken);
        ListRevisionsResponse.Builder response = ListRevisionsResponse.newBuilder()
                .addAllRevisions(page.items());
        if (page.nextPageToken() != null) {
            response.setNextPageToken(page.nextPageToken());
        }
        return response.build();
    }

    public Policy getIamPolicy(String resource) {
        return toProtoPolicy(iamService.getPolicy(resource));
    }

    public Policy setIamPolicy(String resource, Policy policy) {
        return toProtoPolicy(iamService.setPolicy(resource, toStoredPolicy(policy)));
    }

    public TestIamPermissionsResponse testIamPermissions(List<String> permissions) {
        return TestIamPermissionsResponse.newBuilder()
                .addAllPermissions(iamService.testPermissions(permissions))
                .build();
    }

    private static com.google.cloud.run.v2.Service populateService(
            com.google.cloud.run.v2.Service requested, String name, String revisionName, String uri, Timestamp now) {
        com.google.cloud.run.v2.Service.Builder builder = requested.toBuilder()
                .setName(name)
                .setUid(UUID.randomUUID().toString())
                .setGeneration(1)
                .setObservedGeneration(1)
                .setCreateTime(now)
                .setUpdateTime(now)
                .setLatestReadyRevision(revisionName)
                .setLatestCreatedRevision(revisionName)
                .setTerminalCondition(readyCondition(now))
                .addConditions(readyCondition(now))
                .setUri(uri)
                .addUrls(uri)
                .setReconciling(false)
                .setEtag(UUID.randomUUID().toString());
        if (builder.getTrafficCount() == 0) {
            builder.addTraffic(TrafficTarget.newBuilder()
                    .setType(TrafficTargetAllocationType.TRAFFIC_TARGET_ALLOCATION_TYPE_LATEST)
                    .setPercent(100)
                    .build());
        }
        builder.clearTrafficStatuses();
        for (TrafficTarget target : builder.getTrafficList()) {
            builder.addTrafficStatuses(TrafficTargetStatus.newBuilder()
                    .setType(target.getType())
                    .setRevision(target.getRevision().isBlank() ? revisionName : target.getRevision())
                    .setPercent(target.getPercent())
                    .setTag(target.getTag())
                    .setUri(uri)
                    .build());
        }
        return builder.build();
    }

    private static Revision populateRevision(com.google.cloud.run.v2.Service requested,
                                             String serviceName, String revisionName, Timestamp now) {
        Revision.Builder builder = Revision.newBuilder()
                .setName(revisionName)
                .setService(serviceName)
                .setUid(UUID.randomUUID().toString())
                .setGeneration(1)
                .setObservedGeneration(1)
                .setCreateTime(now)
                .setUpdateTime(now)
                .addConditions(readyCondition(now))
                .setReconciling(false)
                .setEtag(UUID.randomUUID().toString());
        builder.putAllLabels(requested.getLabelsMap());
        builder.putAllAnnotations(requested.getAnnotationsMap());
        if (requested.hasTemplate()) {
            builder.addAllContainers(requested.getTemplate().getContainersList());
            builder.addAllVolumes(requested.getTemplate().getVolumesList());
            builder.setServiceAccount(requested.getTemplate().getServiceAccount());
            builder.setMaxInstanceRequestConcurrency(requested.getTemplate().getMaxInstanceRequestConcurrency());
            if (requested.getTemplate().hasTimeout()) {
                builder.setTimeout(requested.getTemplate().getTimeout());
            }
        }
        return builder.build();
    }

    private void startRuntime(String project, String location, OperationGuard guard,
                              com.google.cloud.run.v2.Service service, Revision revision) {
        try {
            runtimeService.initialize();
            CloudRunRuntimeInstance instance = runtimeService.start(project, location, service, revision);
            if (guard.isTerminal()) {
                runtimeService.stopInstances(List.of(instance));
                return;
            }
            List<CloudRunRuntimeInstance> oldInstances =
                    runtimeService.serviceInstancesExcept(service.getName(), revision.getName());
            Timestamp now = timestampNow();
            com.google.cloud.run.v2.Service ready = service.toBuilder()
                    .setLatestReadyRevision(revision.getName())
                    .setUpdateTime(now)
                    .setTerminalCondition(readyCondition(now))
                    .clearConditions()
                    .addConditions(readyCondition(now))
                    .setReconciling(false)
                    .build();
            Revision readyRevision = revision.toBuilder()
                    .setUpdateTime(now)
                    .clearConditions()
                    .addConditions(readyCondition(now))
                    .setReconciling(false)
                    .build();
            serviceStore.put(service.getName(), ProtoJson.print(ready));
            revisionStore.put(revision.getName(), ProtoJson.print(readyRevision));
            guard.complete(ready, ready);
            runCleanupWithTimeout("old revision cleanup service=" + service.getName()
                    + " revision=" + revision.getName(),
                    () -> runtimeService.stopInstances(oldInstances));
        } catch (Exception e) {
            if (guard.isTerminal()) {
                return;
            }
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            LOG.warnf(e, "Cloud Run runtime start failed service=%s revision=%s", service.getName(), revision.getName());
            failRuntimeStart(guard, service, revision, message, Code.INTERNAL_VALUE);
        }
    }

    private void deleteRuntime(OperationGuard guard, String name, com.google.cloud.run.v2.Service deleted) {
        List<CloudRunRuntimeInstance> instances = runtimeService.serviceInstances(name);
        try {
            deleteMetadata(name);
            guard.complete(deleted, deleted);
        } catch (Exception e) {
            if (guard.isTerminal()) {
                return;
            }
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            LOG.warnf(e, "Cloud Run service delete failed service=%s", name);
            guard.fail(Status.newBuilder()
                    .setCode(Code.INTERNAL_VALUE)
                    .setMessage(message)
                    .build(), deleted);
            return;
        }

        runCleanupWithTimeout("runtime cleanup service=" + name, () -> runtimeService.stopInstances(instances));
    }

    private void submitOperation(OperationGuard guard, Runnable work, Runnable onTimeout) {
        Future<?> future = operationExecutor.submit(work);
        Duration timeout = operationTimeout();
        operationTimeouts.schedule(() -> {
            if (guard.isTerminal()) {
                return;
            }
            LOG.warnf("Cloud Run operation timed out operation=%s timeout=%s", guard.operationName(), timeout);
            try {
                onTimeout.run();
            } finally {
                future.cancel(true);
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void failRuntimeStart(OperationGuard guard,
                                  com.google.cloud.run.v2.Service service,
                                  Revision revision,
                                  String message,
                                  int code) {
        runtimeService.markFailed(revision.getName(), message);
        Timestamp now = timestampNow();
        com.google.cloud.run.v2.Service failed = service.toBuilder()
                .setUpdateTime(now)
                .setTerminalCondition(failedCondition(now, message))
                .clearConditions()
                .addConditions(failedCondition(now, message))
                .setReconciling(false)
                .build();
        Revision failedRevision = revision.toBuilder()
                .setUpdateTime(now)
                .clearConditions()
                .addConditions(failedCondition(now, message))
                .setReconciling(false)
                .build();
        serviceStore.put(service.getName(), ProtoJson.print(failed));
        revisionStore.put(revision.getName(), ProtoJson.print(failedRevision));
        guard.fail(Status.newBuilder()
                .setCode(code)
                .setMessage(message)
                .build(), failed);
    }

    private void runCleanupWithTimeout(String description, Runnable cleanup) {
        Future<?> future = cleanupExecutor.submit(cleanup);
        try {
            future.get(cleanupTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            LOG.warnf("Cloud Run cleanup timed out: %s timeout=%s", description, cleanupTimeout());
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.warnf(e, "Cloud Run cleanup failed: %s", description);
        }
    }

    private void deleteMetadata(String name) {
        serviceStore.delete(name);
        String revisionPrefix = name + "/revisions/";
        revisionStore.keys().stream()
                .filter(k -> k.startsWith(revisionPrefix))
                .forEach(revisionStore::delete);
    }

    private static Condition readyCondition(Timestamp now) {
        return Condition.newBuilder()
                .setType("Ready")
                .setState(Condition.State.CONDITION_SUCCEEDED)
                .setLastTransitionTime(now)
                .build();
    }

    private static Condition pendingCondition(Timestamp now) {
        return Condition.newBuilder()
                .setType("Ready")
                .setState(Condition.State.CONDITION_PENDING)
                .setMessage("Runtime starting")
                .setLastTransitionTime(now)
                .build();
    }

    private static Condition failedCondition(Timestamp now, String message) {
        return Condition.newBuilder()
                .setType("Ready")
                .setState(Condition.State.CONDITION_FAILED)
                .setMessage(message)
                .setLastTransitionTime(now)
                .build();
    }

    private static com.google.cloud.run.v2.Service runtimeStarting(
            com.google.cloud.run.v2.Service service, Timestamp now) {
        return service.toBuilder()
                .setUpdateTime(now)
                .clearLatestReadyRevision()
                .setTerminalCondition(pendingCondition(now))
                .clearConditions()
                .addConditions(pendingCondition(now))
                .setReconciling(true)
                .build();
    }

    private static com.google.cloud.run.v2.Service runtimeUpdating(
            com.google.cloud.run.v2.Service service, String latestReadyRevision, Timestamp now) {
        com.google.cloud.run.v2.Service.Builder builder = service.toBuilder()
                .setUpdateTime(now)
                .setTerminalCondition(pendingCondition(now))
                .clearConditions()
                .addConditions(pendingCondition(now))
                .setReconciling(true);
        if (!latestReadyRevision.isBlank()) {
            builder.setLatestReadyRevision(latestReadyRevision);
        }
        return builder.build();
    }

    private static Revision runtimeStarting(Revision revision, Timestamp now) {
        return revision.toBuilder()
                .setUpdateTime(now)
                .clearConditions()
                .addConditions(pendingCondition(now))
                .setReconciling(true)
                .build();
    }

    private Operation done(String parent, com.google.cloud.run.v2.Service response,
                           com.google.cloud.run.v2.Service metadata, boolean transientOnly) {
        return transientOnly
                ? operations.doneTransient(parent, response, metadata)
                : operations.done(parent, response, metadata);
    }

    private static StoredPolicy toStoredPolicy(Policy policy) {
        StoredPolicy stored = new StoredPolicy();
        stored.setVersion(policy.getVersion());
        if (!policy.getEtag().isEmpty()) {
            stored.setEtag(policy.getEtag().toStringUtf8());
        }
        stored.setBindings(policy.getBindingsList().stream()
                .map(CloudRunService::bindingToMap)
                .toList());
        return stored;
    }

    private static Policy toProtoPolicy(StoredPolicy stored) {
        Policy.Builder builder = Policy.newBuilder()
                .setVersion(stored.getVersion());
        if (stored.getEtag() != null) {
            builder.setEtag(ByteString.copyFromUtf8(stored.getEtag()));
        }
        if (stored.getBindings() != null) {
            for (Map<String, Object> binding : stored.getBindings()) {
                builder.addBindings(mapToBinding(binding));
            }
        }
        return builder.build();
    }

    private static Map<String, Object> bindingToMap(Binding binding) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", binding.getRole());
        map.put("members", List.copyOf(binding.getMembersList()));
        if (binding.hasCondition()) {
            Map<String, Object> condition = new LinkedHashMap<>();
            condition.put("expression", binding.getCondition().getExpression());
            condition.put("title", binding.getCondition().getTitle());
            condition.put("description", binding.getCondition().getDescription());
            condition.put("location", binding.getCondition().getLocation());
            map.put("condition", condition);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Binding mapToBinding(Map<String, Object> map) {
        Binding.Builder builder = Binding.newBuilder()
                .setRole((String) map.getOrDefault("role", ""));
        Object members = map.get("members");
        if (members instanceof List<?> list) {
            for (Object member : list) {
                builder.addMembers(String.valueOf(member));
            }
        }
        Object condition = map.get("condition");
        if (condition instanceof Map<?, ?> raw) {
            Map<String, Object> c = (Map<String, Object>) raw;
            builder.setCondition(Expr.newBuilder()
                    .setExpression((String) c.getOrDefault("expression", ""))
                    .setTitle((String) c.getOrDefault("title", ""))
                    .setDescription((String) c.getOrDefault("description", ""))
                    .setLocation((String) c.getOrDefault("location", ""))
                    .build());
        }
        return builder.build();
    }

    private static String firstPresent(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }

    private static Timestamp timestampNow() {
        Instant now = Instant.now();
        return Timestamp.newBuilder()
                .setSeconds(now.getEpochSecond())
                .setNanos(now.getNano())
                .build();
    }

    private static String parent(String project, String location) {
        return "projects/" + project + "/locations/" + location;
    }

    private static String parentFromName(String name) {
        int services = name.indexOf("/services/");
        return services < 0 ? name : name.substring(0, services);
    }

    private static String stableSuffix(String project, String location) {
        return Integer.toHexString((project + ":" + location).hashCode()).replace("-", "0");
    }

    private boolean executionEnabled() {
        return config != null
                && runtimeService != null
                && config.services().cloudrun().execution().enabled()
                && !config.services().cloudrun().execution().mock();
    }

    private Duration operationTimeout() {
        if (config == null) {
            return Duration.ofSeconds(300);
        }
        return config.services().cloudrun().execution().operationTimeout();
    }

    private Duration cleanupTimeout() {
        if (config == null) {
            return Duration.ofSeconds(15);
        }
        return config.services().cloudrun().execution().cleanupTimeout();
    }

    private String invocationUri(String project, String location, String serviceId) {
        return urlService.invocationUri(project, location, serviceId);
    }

    private boolean storedUriMatchesHost(String json, CloudRunUrlService.ParsedHost candidate) {
        com.google.cloud.run.v2.Service service = ProtoJson
                .merge(json, com.google.cloud.run.v2.Service.newBuilder())
                .build();
        return urlService.parseHost(URI.create(service.getUri()).getRawAuthority())
                .map(candidate::equals)
                .orElse(false);
    }

    private static com.google.cloud.run.v2.Service applyServiceUpdate(
            com.google.cloud.run.v2.Service existing,
            com.google.cloud.run.v2.Service requested,
            List<String> updateMask,
            String revisionName,
            Timestamp now) {
        boolean replaceAll = updateMask.isEmpty();
        com.google.cloud.run.v2.Service.Builder builder = existing.toBuilder()
                .setGeneration(existing.getGeneration() + 1)
                .setObservedGeneration(existing.getGeneration() + 1)
                .setUpdateTime(now)
                .setEtag(UUID.randomUUID().toString())
                .setTerminalCondition(readyCondition(now))
                .clearConditions()
                .addConditions(readyCondition(now))
                .setReconciling(false);

        if (replaceAll || masked(updateMask, "description")) {
            builder.setDescription(requested.getDescription());
        }
        if (replaceAll || masked(updateMask, "labels")) {
            builder.clearLabels();
            builder.putAllLabels(requested.getLabelsMap());
        }
        if (replaceAll || masked(updateMask, "annotations")) {
            builder.clearAnnotations();
            builder.putAllAnnotations(requested.getAnnotationsMap());
        }
        if (replaceAll || masked(updateMask, "ingress")) {
            builder.setIngress(requested.getIngress());
        }
        if (replaceAll || masked(updateMask, "binary_authorization")) {
            builder.setBinaryAuthorization(requested.getBinaryAuthorization());
        }
        if (replaceAll || masked(updateMask, "traffic")) {
            builder.clearTraffic();
            builder.addAllTraffic(requested.getTrafficList());
        }
        if (replaceAll || masked(updateMask, "template")) {
            builder.setTemplate(requested.getTemplate());
            builder.setLatestCreatedRevision(revisionName);
        }
        if (replaceAll || masked(updateMask, "client")) {
            builder.setClient(requested.getClient());
        }
        if (replaceAll || masked(updateMask, "client_version")) {
            builder.setClientVersion(requested.getClientVersion());
        }
        if (replaceAll || masked(updateMask, "launch_stage")) {
            builder.setLaunchStage(requested.getLaunchStage());
        }
        if (replaceAll || masked(updateMask, "invoker_iam_disabled")) {
            builder.setInvokerIamDisabled(requested.getInvokerIamDisabled());
        }
        if (replaceAll || masked(updateMask, "default_uri_disabled")) {
            builder.setDefaultUriDisabled(requested.getDefaultUriDisabled());
        }

        builder.clearTrafficStatuses();
        String uri = builder.getUri();
        for (TrafficTarget target : builder.getTrafficList()) {
            builder.addTrafficStatuses(TrafficTargetStatus.newBuilder()
                    .setType(target.getType())
                    .setRevision(target.getRevision().isBlank() ? revisionName : target.getRevision())
                    .setPercent(target.getPercent())
                    .setTag(target.getTag())
                    .setUri(uri)
                    .build());
        }
        return builder.build();
    }

    private static List<String> updateMaskPaths(String updateMask) {
        if (updateMask == null || updateMask.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(updateMask.split(","))
                .map(String::trim)
                .filter(path -> !path.isBlank())
                .map(CloudRunService::normalizeMaskPath)
                .toList();
    }

    private static boolean templateChanged(List<String> updateMask,
                                           com.google.cloud.run.v2.Service existing,
                                           com.google.cloud.run.v2.Service requested) {
        if (updateMask.isEmpty()) {
            return requested.hasTemplate() && !requested.getTemplate().equals(existing.getTemplate());
        }
        return masked(updateMask, "template");
    }

    private static boolean masked(List<String> updateMask, String path) {
        String normalized = normalizeMaskPath(path);
        return updateMask.stream()
                .anyMatch(mask -> mask.equals(normalized) || mask.startsWith(normalized + "."));
    }

    private static String normalizeMaskPath(String path) {
        StringBuilder normalized = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (Character.isUpperCase(c)) {
                normalized.append('_').append(Character.toLowerCase(c));
            } else {
                normalized.append(c);
            }
        }
        return normalized.toString();
    }

    private static String nextRevisionName(String serviceName, com.google.cloud.run.v2.Service existing) {
        String current = existing.getLatestCreatedRevision();
        String prefix = serviceName + "/revisions/" + GcpResourceNames.lastSegment(serviceName) + "-";
        int next = 1;
        if (current.startsWith(prefix)) {
            String suffix = current.substring(prefix.length());
            try {
                next = Integer.parseInt(suffix) + 1;
            } catch (NumberFormatException ignored) {
                next = 1;
            }
        }
        return prefix + String.format("%05d", next);
    }

    private static String nameProject(String serviceName) {
        String[] parts = serviceName.split("/");
        return parts.length > 1 ? parts[1] : "";
    }

    private static String nameLocation(String serviceName) {
        String[] parts = serviceName.split("/");
        return parts.length > 3 ? parts[3] : "";
    }

    public record InvocationRoute(String project, String location, String serviceId) {}

    private final class OperationGuard {
        private final String operationName;
        private final AtomicBoolean terminal = new AtomicBoolean(false);

        private OperationGuard(String operationName) {
            this.operationName = operationName;
        }

        private String operationName() {
            return operationName;
        }

        private boolean isTerminal() {
            return terminal.get();
        }

        private void complete(com.google.protobuf.Message response, com.google.protobuf.Message metadata) {
            if (terminal.compareAndSet(false, true)) {
                operations.complete(operationName, response, metadata);
            }
        }

        private void fail(Status error, com.google.protobuf.Message metadata) {
            if (terminal.compareAndSet(false, true)) {
                operations.fail(operationName, error, metadata);
            }
        }
    }
}
