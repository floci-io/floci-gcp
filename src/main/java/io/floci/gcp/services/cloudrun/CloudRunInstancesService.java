package io.floci.gcp.services.cloudrun;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.api.LaunchStage;
import com.google.cloud.run.v2.Container;
import com.google.cloud.run.v2.ContainerPort;
import com.google.cloud.run.v2.ContainerStatus;
import com.google.cloud.run.v2.IngressTraffic;
import com.google.cloud.run.v2.Instance;
import com.google.cloud.run.v2.ListInstancesResponse;
import com.google.cloud.run.v2.ResourceRequirements;
import com.google.iam.v1.Policy;
import com.google.iam.v1.TestIamPermissionsResponse;
import com.google.longrunning.Operation;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.FieldMaskUtil;
import com.google.rpc.Code;
import com.google.rpc.Status;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.ContainerTeardown;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.GcpResourceNames;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.common.ProtoJson;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.cloudrun.model.CloudRunRuntimeInstance;
import io.floci.gcp.services.iam.IamPolicyCodec;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.operations.LongRunningOperationsService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/**
 * Cloud Run v2 Instances control plane.
 *
 * <p>Invariant: for one instance name, create, patch, start, stop and delete are serialized. Each request
 * checks its precondition and writes its accepted state while holding the instance's lock, and every
 * container side effect runs on the instance's single work queue in request order. A queued transition
 * publishes its terminal condition only while it is still the latest lifecycle transition for the
 * instance (its ticket matches); a superseded transition only merges the runtime facts it observed
 * (URL, container statuses, container conditions). A transition writes only into the instance it was
 * queued for (same {@code uid}): nothing is published for an instance that has been deleted in the
 * meantime, even when an instance with the same name has been created since.
 */
@ApplicationScoped
public class CloudRunInstancesService implements ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(CloudRunInstancesService.class);
    private static final Duration DELETED_RETENTION = Duration.ofDays(30);
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(5);
    private static final String DEFAULT_CPU = "2000m";
    private static final String DEFAULT_MEMORY = "2048Mi";
    private static final String DEFAULT_PORT_NAME = "http1";
    private static final int DEFAULT_CONTAINER_PORT = 8080;
    private static final Set<String> UPDATABLE_FIELDS = Set.of(
            "description", "labels", "annotations", "client", "client_version", "launch_stage",
            "binary_authorization", "vpc_access", "service_account", "containers", "volumes",
            "encryption_key", "encryption_key_revocation_action", "encryption_key_shutdown_duration",
            "node_selector", "gpu_zonal_redundancy_disabled", "ingress", "invoker_iam_disabled", "iap_enabled");

    private final StorageBackend<String, String> instanceStore;
    private final LongRunningOperationsService operations;
    private final IamService iamService;
    private final Predicate<String> serviceExists;
    private final CloudRunInstancesRuntime runtime;
    private final CloudRunUrlService urlService;
    private final boolean mock;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, InstanceSlot> slots = new ConcurrentHashMap<>();
    private final ExecutorService workExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Inject
    public CloudRunInstancesService(StorageFactory storageFactory,
                                    LongRunningOperationsService operations,
                                    IamService iamService,
                                    CloudRunService cloudRunService,
                                    CloudRunInstancesRuntime runtime,
                                    CloudRunUrlService urlService,
                                    EmulatorConfig config) {
        this(storageFactory.createGlobal("cloudrun-instances", "cloudrun-instances.json",
                        new TypeReference<Map<String, String>>() {}),
                operations, iamService, cloudRunService::serviceExists, runtime, urlService,
                config.services().cloudrun().mock());
    }

    CloudRunInstancesService(StorageBackend<String, String> instanceStore,
                             LongRunningOperationsService operations,
                             IamService iamService,
                             Predicate<String> serviceExists,
                             CloudRunInstancesRuntime runtime,
                             CloudRunUrlService urlService,
                             boolean mock) {
        this.instanceStore = instanceStore;
        this.operations = operations;
        this.iamService = iamService;
        this.serviceExists = serviceExists;
        this.runtime = runtime;
        this.urlService = urlService;
        this.mock = mock;
    }

    /**
     * Stops the per-instance work queues, then removes every instance container and writes its GCS
     * volumes back. Runs in the shutdown phase before the final storage flush, and again from
     * {@code @PreDestroy} as a no-op fallback.
     */
    @Override
    public void stopManagedContainers() {
        workExecutor.shutdownNow();
        try {
            if (!workExecutor.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.warnf("Cloud Run instance work did not stop within %s", SHUTDOWN_WAIT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!mock && runtime != null) {
            runtime.stopAll();
        }
    }

    @PreDestroy
    void shutdown() {
        stopManagedContainers();
    }

    public Operation createInstance(String project, String location, String instanceId, String body,
                                    boolean validateOnly) {
        Instance requested = ProtoJson.merge(body, Instance.newBuilder()).build();
        String id = instanceId == null || instanceId.isBlank() ? generateUniqueId(project, location) : instanceId;
        String name = parent(project, location) + "/instances/" + id;
        InstanceSlot slot = slot(name);
        slot.lock.lock();
        try {
            return createLocked(slot, project, location, id, requested, validateOnly);
        } finally {
            slot.lock.unlock();
        }
    }

    public Instance getInstance(String name) {
        return find(name).orElseThrow(() -> notFound(name));
    }

    public boolean instanceExists(String name) {
        return instanceStore.get(name).isPresent();
    }

    public ListInstancesResponse listInstances(String project, String location, int pageSize, String pageToken) {
        String prefix = parent(project, location) + "/instances/";
        List<Instance> instances = instanceStore.scan(key -> key.startsWith(prefix)).stream()
                .map(CloudRunInstancesService::parse)
                .sorted(Comparator.comparing(Instance::getName))
                .toList();
        PageToken.Page<Instance> page = PageToken.paginate(instances, pageSize, pageToken);
        ListInstancesResponse.Builder response = ListInstancesResponse.newBuilder().addAllInstances(page.items());
        if (page.nextPageToken() != null) {
            response.setNextPageToken(page.nextPageToken());
        }
        return response.build();
    }

    public Operation updateInstance(String name, String body, String updateMask, boolean validateOnly,
                                    boolean allowMissing) {
        Instance requested = ProtoJson.merge(body, Instance.newBuilder()).build();
        InstanceSlot slot = slot(name);
        slot.lock.lock();
        try {
            Optional<Instance> found = find(name);
            if (found.isEmpty()) {
                if (!allowMissing) {
                    throw notFound(name);
                }
                return createLocked(slot, nameProject(name), nameLocation(name), GcpResourceNames.lastSegment(name),
                        requested, validateOnly);
            }
            Instance existing = found.get();
            Timestamp now = timestampNow();
            Instance.Builder builder = existing.toBuilder();
            UpdatePaths paths = updatePaths(requested, updateMask);
            for (String field : paths.replacedFields()) {
                copyField(requested, builder, Instance.getDescriptor().findFieldByName(field));
            }
            if (!paths.mergedPaths().isEmpty()) {
                FieldMaskUtil.merge(FieldMaskUtil.fromStringList(paths.mergedPaths()), requested, builder,
                        new FieldMaskUtil.MergeOptions()
                                .setReplaceMessageFields(true)
                                .setReplaceRepeatedFields(true));
            }
            applyDefaults(builder);
            builder.setGeneration(existing.getGeneration() + 1)
                    .setUpdateTime(now)
                    .setEtag(UUID.randomUUID().toString());
            boolean runtimeChanged = !builder.getContainersList().equals(existing.getContainersList())
                    || !builder.getVolumesList().equals(existing.getVolumesList());
            if (!mock && runtimeChanged) {
                CloudRunRuntimeService.validateSupported(builder.getContainersList(), builder.getVolumesList());
            }
            boolean restart = !mock && runtimeChanged && CloudRunInstanceStates.desiredRunning(existing);
            if (restart) {
                builder.setTerminalCondition(CloudRunInstanceStates.starting(now)).setReconciling(true);
            } else {
                builder.setObservedGeneration(builder.getGeneration());
            }
            Instance updated = builder.build();
            LOG.infof("update Cloud Run instance name=%s updateMask=%s validateOnly=%s restart=%s",
                    name, updateMask, validateOnly, restart);
            if (validateOnly) {
                return operations.doneTransient(parentFromName(name), updated, updated);
            }
            store(updated);
            if (!restart) {
                return operations.done(parentFromName(name), updated, updated);
            }
            return enqueueStart(slot, updated);
        } finally {
            slot.lock.unlock();
        }
    }

    public Operation deleteInstance(String name, boolean validateOnly) {
        InstanceSlot slot = slot(name);
        slot.lock.lock();
        try {
            Instance existing = getInstance(name);
            Timestamp now = timestampNow();
            Instance deleted = existing.toBuilder()
                    .setGeneration(existing.getGeneration() + 1)
                    .setObservedGeneration(existing.getGeneration() + 1)
                    .setUpdateTime(now)
                    .setDeleteTime(now)
                    .setExpireTime(plus(now, DELETED_RETENTION))
                    .setTerminalCondition(CloudRunInstanceStates.deleted(now))
                    .setReconciling(false)
                    .build();
            LOG.infof("delete Cloud Run instance name=%s validateOnly=%s", name, validateOnly);
            if (validateOnly) {
                return operations.doneTransient(parentFromName(name), deleted, deleted);
            }
            iamService.deleteResourceAndPolicy(name, () -> instanceStore.delete(name));
            if (mock) {
                return operations.done(parentFromName(name), deleted, deleted);
            }
            Operation operation = operations.pending(parentFromName(name), deleted);
            CloudRunOperationGuard guard = new CloudRunOperationGuard(operations, operation.getName());
            slot.ticket++;
            enqueue(slot, () -> {
                try {
                    runtime.stop(deleted);
                } catch (Exception e) {
                    LOG.warnf(e, "Cloud Run instance container cleanup failed instance=%s", name);
                }
                guard.complete(deleted, deleted);
            });
            return operation;
        } finally {
            slot.lock.unlock();
        }
    }

    public Operation startInstance(String name, boolean validateOnly) {
        InstanceSlot slot = slot(name);
        slot.lock.lock();
        try {
            Instance existing = getInstance(name);
            if (CloudRunInstanceStates.desiredRunning(existing)) {
                throw GcpException.failedPrecondition("Instance '" + GcpResourceNames.lastSegment(name)
                        + "' cannot be started because it is already running.");
            }
            Timestamp now = timestampNow();
            Instance.Builder builder = existing.toBuilder()
                    .setGeneration(existing.getGeneration() + 1)
                    .setUpdateTime(now)
                    .setEtag(UUID.randomUUID().toString());
            LOG.infof("start Cloud Run instance name=%s validateOnly=%s", name, validateOnly);
            if (mock) {
                Instance running = mockRunning(builder, now);
                if (validateOnly) {
                    return operations.doneTransient(parentFromName(name), running, running);
                }
                store(running);
                return operations.done(parentFromName(name), running, running);
            }
            Instance starting = builder
                    .setTerminalCondition(CloudRunInstanceStates.starting(now))
                    .setReconciling(true)
                    .build();
            if (validateOnly) {
                return operations.doneTransient(parentFromName(name), starting, starting);
            }
            store(starting);
            return enqueueStart(slot, starting);
        } finally {
            slot.lock.unlock();
        }
    }

    public Operation stopInstance(String name, boolean validateOnly) {
        InstanceSlot slot = slot(name);
        slot.lock.lock();
        try {
            Instance existing = getInstance(name);
            if (!CloudRunInstanceStates.desiredRunning(existing)) {
                throw GcpException.failedPrecondition("Instance '" + GcpResourceNames.lastSegment(name)
                        + "' cannot be stopped because it is not running.");
            }
            Timestamp now = timestampNow();
            Instance.Builder builder = existing.toBuilder()
                    .setGeneration(existing.getGeneration() + 1)
                    .setUpdateTime(now)
                    .setEtag(UUID.randomUUID().toString());
            LOG.infof("stop Cloud Run instance name=%s validateOnly=%s", name, validateOnly);
            if (mock) {
                Instance stopped = builder
                        .setObservedGeneration(builder.getGeneration())
                        .setTerminalCondition(CloudRunInstanceStates.stopped(now))
                        .setReconciling(false)
                        .build();
                if (validateOnly) {
                    return operations.doneTransient(parentFromName(name), stopped, stopped);
                }
                store(stopped);
                return operations.done(parentFromName(name), stopped, stopped);
            }
            Instance stopping = builder
                    .setTerminalCondition(CloudRunInstanceStates.stopping(now))
                    .setReconciling(true)
                    .build();
            if (validateOnly) {
                return operations.doneTransient(parentFromName(name), stopping, stopping);
            }
            store(stopping);
            Operation operation = operations.pending(parentFromName(name), stopping);
            CloudRunOperationGuard guard = new CloudRunOperationGuard(operations, operation.getName());
            long ticket = ++slot.ticket;
            enqueue(slot, () -> {
                try {
                    runtime.stop(stopping);
                } catch (Exception e) {
                    LOG.warnf(e, "Cloud Run instance container stop failed instance=%s", name);
                }
                publishStopped(slot, ticket, guard, stopping);
            });
            return operation;
        } finally {
            slot.lock.unlock();
        }
    }

    public Policy getIamPolicy(String resource) {
        return IamPolicyCodec.toProtoPolicy(iamService.getPolicy(resource));
    }

    public Policy setIamPolicy(String resource, Policy policy) {
        return IamPolicyCodec.toProtoPolicy(iamService.setPolicy(resource, IamPolicyCodec.toStoredPolicy(policy)));
    }

    public TestIamPermissionsResponse testIamPermissions(String resource, List<String> permissions) {
        return TestIamPermissionsResponse.newBuilder()
                .addAllPermissions(iamService.testPermissions(resource, permissions))
                .build();
    }

    public Optional<CloudRunRuntimeInstance> readyRuntime(String name) {
        if (mock || runtime == null || find(name).isEmpty()) {
            return Optional.empty();
        }
        return runtime.ready(name);
    }

    public Optional<CloudRunService.InvocationRoute> resolveInvocationHost(String host) {
        if (urlService == null) {
            return Optional.empty();
        }
        Optional<CloudRunUrlService.ParsedHost> parsed = urlService.parseHost(host);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        CloudRunUrlService.ParsedHost candidate = parsed.get();
        String suffix = "/locations/" + candidate.location() + "/instances/" + candidate.serviceId();
        return instanceStore.keys().stream()
                .filter(name -> name.endsWith(suffix))
                .filter(name -> urlService.matchesProjectToken(nameProject(name), candidate.projectToken()))
                .filter(name -> find(name).map(instance -> urlMatchesHost(instance, candidate)).orElse(false))
                .findFirst()
                .map(name -> new CloudRunService.InvocationRoute(nameProject(name), candidate.location(),
                        candidate.serviceId()));
    }

    private Operation createLocked(InstanceSlot slot, String project, String location, String id,
                                   Instance requested, boolean validateOnly) {
        String parent = parent(project, location);
        String name = parent + "/instances/" + id;
        if (instanceStore.get(name).isPresent() || serviceExists.test(parent + "/services/" + id)) {
            throw GcpException.alreadyExists("Resource '" + id + "' already exists.");
        }
        Timestamp now = timestampNow();
        Instance.Builder builder = Instance.newBuilder();
        for (String field : updatePaths(requested, null).replacedFields()) {
            copyField(requested, builder, Instance.getDescriptor().findFieldByName(field));
        }
        applyDefaults(builder);
        builder.setName(name)
                .setUid(UUID.randomUUID().toString())
                .setGeneration(1)
                .setCreateTime(now)
                .setUpdateTime(now)
                .setEtag(UUID.randomUUID().toString());
        if (!mock) {
            CloudRunRuntimeService.validateSupported(builder.getContainersList(), builder.getVolumesList());
        }
        LOG.infof("create Cloud Run instance name=%s validateOnly=%s", name, validateOnly);
        if (mock) {
            Instance running = mockRunning(builder, now);
            if (validateOnly) {
                return operations.doneTransient(parent, running, running);
            }
            store(running);
            return operations.done(parent, running, running);
        }
        Instance starting = builder
                .setTerminalCondition(CloudRunInstanceStates.starting(now))
                .setReconciling(true)
                .build();
        if (validateOnly) {
            return operations.doneTransient(parent, starting, starting);
        }
        store(starting);
        return enqueueStart(slot, starting);
    }

    private Operation enqueueStart(InstanceSlot slot, Instance starting) {
        Operation operation = operations.pending(parentFromName(starting.getName()), starting);
        CloudRunOperationGuard guard = new CloudRunOperationGuard(operations, operation.getName());
        long ticket = ++slot.ticket;
        Instant requestedAt = Instant.now();
        String url = url(starting.getName());
        enqueue(slot, () -> {
            try {
                CloudRunInstancesRuntime.Started started = runtime.start(nameProject(starting.getName()),
                        nameLocation(starting.getName()), starting, url);
                publishStarted(slot, ticket, guard, starting, started, Duration.between(requestedAt, Instant.now()));
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                LOG.warnf(e, "Cloud Run instance start failed instance=%s", starting.getName());
                publishFailed(slot, ticket, guard, starting, message);
            }
        });
        return operation;
    }

    private void publishStarted(InstanceSlot slot, long ticket, CloudRunOperationGuard guard, Instance starting,
                                CloudRunInstancesRuntime.Started started, Duration startup) {
        slot.lock.lock();
        try {
            Timestamp now = timestampNow();
            Optional<Instance> current = findSameInstance(starting);
            Instance.Builder builder = current.orElse(starting).toBuilder()
                    .clearUrls()
                    .addUrls(url(starting.getName()))
                    .clearContainerStatuses()
                    .addAllContainerStatuses(containerStatuses(starting.getContainersList(), started.imageDigest()))
                    .clearConditions()
                    .addAllConditions(CloudRunInstanceStates.readyConditions(now, started.imageImport()));
            if (current.isPresent() && slot.ticket == ticket) {
                builder.setTerminalCondition(CloudRunInstanceStates.running(now, startup))
                        .setObservedGeneration(builder.getGeneration())
                        .setReconciling(false);
            }
            Instance result = builder.build();
            if (current.isPresent()) {
                store(result);
            }
            guard.complete(result, result);
        } finally {
            slot.lock.unlock();
        }
    }

    private void publishFailed(InstanceSlot slot, long ticket, CloudRunOperationGuard guard, Instance starting,
                               String message) {
        slot.lock.lock();
        try {
            Optional<Instance> current = findSameInstance(starting);
            Instance result = current.orElse(starting);
            if (current.isPresent() && slot.ticket == ticket) {
                Timestamp now = timestampNow();
                result = result.toBuilder()
                        .setTerminalCondition(CloudRunInstanceStates.failed(now, message))
                        .clearConditions()
                        .addAllConditions(CloudRunInstanceStates.failedConditions(now, message))
                        .clearContainerStatuses()
                        .addAllContainerStatuses(containerStatuses(result.getContainersList(), ""))
                        .setObservedGeneration(result.getGeneration())
                        .setReconciling(false)
                        .build();
                store(result);
            }
            guard.fail(Status.newBuilder().setCode(Code.INTERNAL_VALUE).setMessage(message).build(), result);
        } finally {
            slot.lock.unlock();
        }
    }

    private void publishStopped(InstanceSlot slot, long ticket, CloudRunOperationGuard guard, Instance stopping) {
        slot.lock.lock();
        try {
            Optional<Instance> current = findSameInstance(stopping);
            Instance result = current.orElse(stopping);
            if (current.isPresent() && slot.ticket == ticket) {
                result = result.toBuilder()
                        .setTerminalCondition(CloudRunInstanceStates.stopped(timestampNow()))
                        .setObservedGeneration(result.getGeneration())
                        .setReconciling(false)
                        .build();
                store(result);
            }
            guard.complete(result, result);
        } finally {
            slot.lock.unlock();
        }
    }

    /**
     * The stored instance a background transition may write into: the one it was queued for, identified
     * by {@code uid}. Empty once that instance has been deleted, even when an instance with the same name
     * has been created since. Callers hold the instance lock.
     */
    private Optional<Instance> findSameInstance(Instance transition) {
        return find(transition.getName()).filter(stored -> stored.getUid().equals(transition.getUid()));
    }

    private void enqueue(InstanceSlot slot, Runnable work) {
        slot.tail = slot.tail
                .handle((ignored, error) -> null)
                .thenRunAsync(work, workExecutor);
    }

    private Instance mockRunning(Instance.Builder builder, Timestamp now) {
        return builder
                .setObservedGeneration(builder.getGeneration())
                .setTerminalCondition(CloudRunInstanceStates.running(now, Duration.ZERO))
                .clearConditions()
                .addAllConditions(CloudRunInstanceStates.readyConditions(now, Duration.ZERO))
                .clearContainerStatuses()
                .addAllContainerStatuses(containerStatuses(builder.getContainersList(), ""))
                .clearUrls()
                .addUrls(url(builder.getName()))
                .setReconciling(false)
                .build();
    }

    static void applyDefaults(Instance.Builder builder) {
        List<Container> containers = new ArrayList<>();
        for (Container container : builder.getContainersList()) {
            Container.Builder completed = container.toBuilder();
            ResourceRequirements.Builder resources = completed.getResourcesBuilder();
            if (!resources.containsLimits("cpu")) {
                resources.putLimits("cpu", DEFAULT_CPU);
            }
            if (!resources.containsLimits("memory")) {
                resources.putLimits("memory", DEFAULT_MEMORY);
            }
            if (completed.getPortsCount() == 0) {
                completed.addPorts(ContainerPort.newBuilder()
                        .setName(DEFAULT_PORT_NAME)
                        .setContainerPort(DEFAULT_CONTAINER_PORT)
                        .build());
            }
            containers.add(completed.build());
        }
        builder.clearContainers().addAllContainers(containers);
        if (builder.getIngress() == IngressTraffic.INGRESS_TRAFFIC_UNSPECIFIED) {
            builder.setIngress(IngressTraffic.INGRESS_TRAFFIC_ALL);
        }
        if (builder.getLaunchStage() == LaunchStage.LAUNCH_STAGE_UNSPECIFIED) {
            builder.setLaunchStage(LaunchStage.GA);
        }
    }

    /**
     * What a PATCH writes. {@code replacedFields} are top-level Instance fields replaced as a whole: with an
     * update mask, the masked user-settable top-level fields (output-only paths are ignored); without one,
     * every user-settable field populated in the body. {@code mergedPaths} are nested snake_case mask paths
     * inside a user-settable singular message field, applied with field mask merge semantics so sibling
     * fields are kept. Map and repeated fields can only be masked as a whole.
     */
    static UpdatePaths updatePaths(Instance requested, String updateMask) {
        if (updateMask == null || updateMask.isBlank()) {
            List<String> fields = UPDATABLE_FIELDS.stream()
                    .filter(field -> {
                        FieldDescriptor descriptor = Instance.getDescriptor().findFieldByName(field);
                        return descriptor.isRepeated() ? requested.getRepeatedFieldCount(descriptor) > 0
                                : requested.hasField(descriptor);
                    })
                    .sorted()
                    .toList();
            return new UpdatePaths(fields, List.of());
        }
        Set<String> fields = new LinkedHashSet<>();
        Set<String> nested = new LinkedHashSet<>();
        for (String path : Arrays.stream(updateMask.split(",")).map(String::trim).filter(p -> !p.isBlank()).toList()) {
            List<String> segments = Arrays.stream(path.split("\\.", -1))
                    .map(CloudRunInstancesService::snakeCase)
                    .toList();
            String field = segments.getFirst();
            String normalized = String.join(".", segments);
            if (Instance.getDescriptor().findFieldByName(field) == null
                    || !FieldMaskUtil.isValid(Instance.class, normalized)) {
                throw GcpException.invalidArgument("Invalid update mask path: " + path);
            }
            if (!UPDATABLE_FIELDS.contains(field)) {
                continue;
            }
            if (segments.size() == 1) {
                fields.add(field);
            } else {
                nested.add(normalized);
            }
        }
        List<String> merged = nested.stream()
                .filter(path -> !fields.contains(path.substring(0, path.indexOf('.'))))
                .toList();
        return new UpdatePaths(List.copyOf(fields), merged);
    }

    record UpdatePaths(List<String> replacedFields, List<String> mergedPaths) {
    }

    private static void copyField(Instance source, Instance.Builder target, FieldDescriptor descriptor) {
        target.clearField(descriptor);
        if (descriptor.isRepeated()) {
            for (int i = 0; i < source.getRepeatedFieldCount(descriptor); i++) {
                target.addRepeatedField(descriptor, source.getRepeatedField(descriptor, i));
            }
        } else if (source.hasField(descriptor)) {
            target.setField(descriptor, source.getField(descriptor));
        }
    }

    private static List<ContainerStatus> containerStatuses(List<Container> containers, String imageDigest) {
        List<ContainerStatus> statuses = new ArrayList<>();
        for (Container container : containers) {
            statuses.add(ContainerStatus.newBuilder()
                    .setName(container.getName())
                    .setImageDigest(imageDigest)
                    .build());
        }
        return statuses;
    }

    private String generateUniqueId(String project, String location) {
        String parent = parent(project, location);
        while (true) {
            String id = CloudRunInstanceStates.generateId(random);
            if (instanceStore.get(parent + "/instances/" + id).isEmpty()
                    && !serviceExists.test(parent + "/services/" + id)) {
                return id;
            }
        }
    }

    private InstanceSlot slot(String name) {
        return slots.computeIfAbsent(name, key -> new InstanceSlot());
    }

    private Optional<Instance> find(String name) {
        return instanceStore.get(name).map(CloudRunInstancesService::parse);
    }

    private void store(Instance instance) {
        instanceStore.put(instance.getName(), ProtoJson.print(instance));
    }

    private String url(String name) {
        return urlService.invocationUri(nameProject(name), nameLocation(name), GcpResourceNames.lastSegment(name));
    }

    private boolean urlMatchesHost(Instance instance, CloudRunUrlService.ParsedHost candidate) {
        return instance.getUrlsList().stream()
                .anyMatch(url -> urlService.parseHost(URI.create(url).getRawAuthority())
                        .map(candidate::equals)
                        .orElse(false));
    }

    private static GcpException notFound(String name) {
        return GcpException.notFound("Resource '" + GcpResourceNames.lastSegment(name)
                + "' of kind 'INSTANCE' in region '" + nameLocation(name)
                + "' in project '" + nameProject(name) + "' does not exist.");
    }

    private static Instance parse(String json) {
        return ProtoJson.merge(json, Instance.newBuilder()).build();
    }

    private static String snakeCase(String path) {
        StringBuilder normalized = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (Character.isUpperCase(c)) {
                normalized.append('_').append(Character.toLowerCase(c));
            } else {
                normalized.append(c);
            }
        }
        return normalized.toString().toLowerCase(Locale.ROOT);
    }

    private static Timestamp timestampNow() {
        Instant now = Instant.now();
        return Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()).build();
    }

    private static Timestamp plus(Timestamp timestamp, Duration duration) {
        return timestamp.toBuilder().setSeconds(timestamp.getSeconds() + duration.getSeconds()).build();
    }

    private static String parent(String project, String location) {
        return "projects/" + project + "/locations/" + location;
    }

    private static String parentFromName(String name) {
        int instances = name.indexOf("/instances/");
        return instances < 0 ? name : name.substring(0, instances);
    }

    private static String nameProject(String name) {
        String[] parts = name.split("/");
        return parts.length > 1 ? parts[1] : "";
    }

    private static String nameLocation(String name) {
        String[] parts = name.split("/");
        return parts.length > 3 ? parts[3] : "";
    }

    /**
     * Per-instance serialization state. {@code lock} guards every read-check-write of the stored instance,
     * {@code ticket} and {@code tail}; {@code tail} is the instance's container work queue.
     */
    private static final class InstanceSlot {
        private final ReentrantLock lock = new ReentrantLock();
        private long ticket;
        private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    }
}
