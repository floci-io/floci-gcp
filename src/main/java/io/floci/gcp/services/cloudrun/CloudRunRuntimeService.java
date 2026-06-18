package io.floci.gcp.services.cloudrun;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.cloud.run.v2.EnvVar;
import com.google.cloud.run.v2.Revision;
import com.google.cloud.run.v2.Volume;
import com.google.cloud.run.v2.VolumeMount;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.docker.ContainerBuilder;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerSpec;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.cloudrun.model.CloudRunRuntimeInstance;
import io.floci.gcp.services.cloudrun.model.CloudRunRuntimeVolumeMount;
import io.floci.gcp.services.gcs.GcsService;
import io.floci.gcp.services.gcs.model.GcsObjectMeta;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@ApplicationScoped
public class CloudRunRuntimeService {

    private static final Logger LOG = Logger.getLogger(CloudRunRuntimeService.class);
    private static final String GCS_VOLUME_HELPER_IMAGE = "alpine:3.20";
    private static final String GCS_VOLUME_HELPER_MOUNT = "/floci-gcs-volume";
    private static final int TAR_BLOCK_SIZE = 512;

    private final StorageBackend<String, CloudRunRuntimeInstance> runtimeStore;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final EmulatorConfig config;
    private final GcsService gcsService;

    @Inject
    public CloudRunRuntimeService(StorageFactory storageFactory,
                                  ContainerBuilder containerBuilder,
                                  ContainerLifecycleManager lifecycleManager,
                                  EmulatorConfig config,
                                  GcsService gcsService) {
        this(storageFactory.createGlobal("cloudrun-runtime-instances", "cloudrun-runtime-instances.json",
                        new TypeReference<Map<String, CloudRunRuntimeInstance>>() {}),
                containerBuilder, lifecycleManager, config, gcsService);
    }

    CloudRunRuntimeService(StorageBackend<String, CloudRunRuntimeInstance> runtimeStore,
                           ContainerBuilder containerBuilder,
                           ContainerLifecycleManager lifecycleManager,
                           EmulatorConfig config) {
        this(runtimeStore, containerBuilder, lifecycleManager, config, null);
    }

    CloudRunRuntimeService(StorageBackend<String, CloudRunRuntimeInstance> runtimeStore,
                           ContainerBuilder containerBuilder,
                           ContainerLifecycleManager lifecycleManager,
                           EmulatorConfig config,
                           GcsService gcsService) {
        this.runtimeStore = runtimeStore;
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.config = config;
        this.gcsService = gcsService;
    }

    public void initialize() {
        // Forces CDI proxy initialization on the request thread before background runtime work starts.
        containerBuilder.newContainer("scratch");
        lifecycleManager.getDockerClient().versionCmd();
    }

    public CloudRunRuntimeInstance start(String project, String location,
                                         com.google.cloud.run.v2.Service service,
                                         Revision revision) {
        validateSupported(revision);
        if (config.services().cloudrun().execution().mock()) {
            throw GcpException.unimplemented("Cloud Run execution mock mode does not start runtime containers");
        }

        com.google.cloud.run.v2.Container container = revision.getContainers(0);
        int containerPort = ingressPort(container);
        String containerName = containerName(service.getName(), revision);
        List<CloudRunRuntimeVolumeMount> gcsVolumeMounts = prepareGcsVolumeMounts(revision, container);

        ContainerSpec spec = buildSpec(project, location, service, revision, container, containerPort, containerName,
                gcsVolumeMounts);
        String containerId = null;
        try {
            ContainerLifecycleManager.ContainerInfo info = lifecycleManager.createAndStart(spec);
            containerId = info.containerId();
            ContainerLifecycleManager.EndpointInfo endpoint = info.getEndpoint(containerPort);
            if (endpoint == null) {
                throw GcpException.internal("Cloud Run runtime did not expose port " + containerPort);
            }

            CloudRunRuntimeInstance starting = instance(project, location, service, revision, container,
                    info.containerId(), containerPort, spec.networkMode(), endpoint, "STARTING", null,
                    gcsVolumeMounts);
            runtimeStore.put(revision.getName(), starting);
            waitForReady(endpoint, config.services().cloudrun().execution().startupTimeout());
            CloudRunRuntimeInstance ready = starting.withStatus("READY", null);
            runtimeStore.put(revision.getName(), ready);
            LOG.infof("Cloud Run runtime ready service=%s revision=%s endpoint=%s",
                    service.getName(), revision.getName(), endpoint);
            return ready;
        } catch (RuntimeException e) {
            if (containerId != null) {
                lifecycleManager.forceRemove(containerId, null);
            }
            deleteMaterializedGcsVolumes(gcsVolumeMounts);
            throw e;
        }
    }

    public void stopService(String serviceName) {
        stopInstances(serviceInstances(serviceName));
    }

    List<CloudRunRuntimeInstance> serviceInstances(String serviceName) {
        String prefix = serviceName + "/revisions/";
        return List.copyOf(runtimeStore.keys()).stream()
                .filter(key -> key.startsWith(prefix))
                .map(runtimeStore::get)
                .flatMap(Optional::stream)
                .toList();
    }

    List<CloudRunRuntimeInstance> serviceInstancesExcept(String serviceName, String keepRevisionName) {
        return serviceInstances(serviceName).stream()
                .filter(instance -> !instance.revisionName().equals(keepRevisionName))
                .toList();
    }

    void stopInstances(List<CloudRunRuntimeInstance> instances) {
        for (CloudRunRuntimeInstance instance : List.copyOf(instances)) {
            stopInstance(instance);
        }
    }

    public void stopOtherRevisions(String serviceName, String keepRevisionName) {
        stopInstances(serviceInstancesExcept(serviceName, keepRevisionName));
    }

    public Optional<CloudRunRuntimeInstance> getReady(String revisionName) {
        Optional<CloudRunRuntimeInstance> stored = runtimeStore.get(revisionName);
        if (stored.isEmpty() || !stored.get().ready()) {
            return Optional.empty();
        }

        CloudRunRuntimeInstance instance = stored.get();
        if (instance.containerId() == null || instance.containerId().isBlank()
                || !lifecycleManager.isContainerRunning(instance.containerId())) {
            runtimeStore.delete(revisionName);
            return Optional.empty();
        }

        try {
            ContainerLifecycleManager.EndpointInfo endpoint = lifecycleManager.resolveEndpoint(
                    instance.containerId(), instance.ingressContainerPort(), instance.dockerNetwork());
            CloudRunRuntimeInstance refreshed = instance.withEndpoint(endpoint.host(), endpoint.port());
            if (!refreshed.equals(instance)) {
                runtimeStore.put(revisionName, refreshed);
            }
            return Optional.of(refreshed);
        } catch (RuntimeException e) {
            LOG.debugf(e, "Cloud Run runtime endpoint lookup failed revision=%s", revisionName);
            return Optional.empty();
        }
    }

    void markFailed(String revisionName, String message) {
        runtimeStore.get(revisionName)
                .map(instance -> instance.withStatus("FAILED", message))
                .ifPresent(instance -> runtimeStore.put(revisionName, instance));
    }

    ContainerSpec buildSpec(String project, String location,
                            com.google.cloud.run.v2.Service service,
                            Revision revision,
                            com.google.cloud.run.v2.Container container,
                            int containerPort,
                            String containerName) {
        return buildSpec(project, location, service, revision, container, containerPort, containerName, List.of());
    }

    ContainerSpec buildSpec(String project, String location,
                            com.google.cloud.run.v2.Service service,
                            Revision revision,
                            com.google.cloud.run.v2.Container container,
                            int containerPort,
                            String containerName,
                            List<CloudRunRuntimeVolumeMount> gcsVolumeMounts) {
        Map<String, String> env = new LinkedHashMap<>();
        for (EnvVar envVar : container.getEnvList()) {
            env.put(envVar.getName(), envVar.getValue());
        }
        env.put("PORT", Integer.toString(containerPort));
        env.put("K_SERVICE", lastSegment(service.getName()));
        env.put("K_REVISION", lastSegment(revision.getName()));
        env.put("K_CONFIGURATION", lastSegment(service.getName()));

        ContainerBuilder.Builder builder = containerBuilder.newContainer(container.getImage())
                .withName(containerName)
                .withDynamicPort(containerPort)
                .withDockerNetwork(Optional.empty())
                .withHostDockerInternalOnLinux()
                .withLogRotation()
                .withLabels(Map.of(
                        "floci-gcp", "true",
                        "floci-gcp.service", "cloudrun",
                        "floci-gcp.project", project,
                        "floci-gcp.location", location,
                        "floci-gcp.cloudrun.service", service.getName(),
                        "floci-gcp.cloudrun.revision", revision.getName()));

        builder.withEnv(env.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .toList());
        if (!container.getCommandList().isEmpty()) {
            builder.withEntrypoint(container.getCommandList());
        }
        if (!container.getArgsList().isEmpty()) {
            builder.withCmd(container.getArgsList());
        }
        if (!container.getWorkingDir().isBlank()) {
            builder.withWorkingDir(container.getWorkingDir());
        }
        for (CloudRunRuntimeVolumeMount mount : gcsVolumeMounts) {
            if (mount.volumeName() != null && !mount.volumeName().isBlank()) {
                builder.withNamedVolume(mount.volumeName(), mount.mountPath(), mount.readOnly());
            } else if (mount.readOnly()) {
                builder.withReadOnlyBind(mount.hostPath(), mount.mountPath());
            } else {
                builder.withBind(mount.hostPath(), mount.mountPath());
            }
        }
        return builder.build();
    }

    private void stopInstance(CloudRunRuntimeInstance instance) {
        try {
            try {
                if (instance.containerId() != null && !instance.containerId().isBlank()) {
                    lifecycleManager.forceRemove(instance.containerId(), null);
                }
            } catch (Exception e) {
                LOG.warnf(e, "Cloud Run runtime container cleanup failed revision=%s container=%s",
                        instance.revisionName(), instance.containerId());
            }
        } finally {
            cleanupGcsVolumeMounts(instance.gcsVolumeMounts());
            runtimeStore.get(instance.revisionName())
                    .filter(current -> sameRuntime(current, instance))
                    .ifPresent(current -> runtimeStore.delete(instance.revisionName()));
        }
    }

    private static boolean sameRuntime(CloudRunRuntimeInstance current, CloudRunRuntimeInstance expected) {
        return Objects.equals(current.containerId(), expected.containerId())
                && current.createTimeMillis() == expected.createTimeMillis();
    }

    static void validateSupported(Revision revision) {
        if (revision.getContainersCount() != 1) {
            throw GcpException.invalidArgument("Cloud Run execution supports exactly one container");
        }
        Map<String, Volume> volumes = new HashMap<>();
        for (Volume volume : revision.getVolumesList()) {
            if (volume.getName().isBlank()) {
                throw GcpException.invalidArgument("Cloud Run execution volume name is required");
            }
            if (!volume.hasGcs()) {
                throw GcpException.invalidArgument("Cloud Run execution supports only GCS volumes");
            }
            if (volume.getGcs().getBucket().isBlank()) {
                throw GcpException.invalidArgument("Cloud Run execution GCS volume bucket is required");
            }
            if (volume.getGcs().getMountOptionsCount() > 0) {
                throw GcpException.invalidArgument("Cloud Run execution does not support GCS volume mountOptions");
            }
            volumes.put(volume.getName(), volume);
        }
        com.google.cloud.run.v2.Container container = revision.getContainers(0);
        if (container.getImage().isBlank()) {
            throw GcpException.invalidArgument("Cloud Run execution requires a container image");
        }
        if (container.getPortsCount() > 1) {
            throw GcpException.invalidArgument("Cloud Run execution supports at most one container port");
        }
        for (EnvVar envVar : container.getEnvList()) {
            if (envVar.hasValueSource()) {
                throw GcpException.invalidArgument("Cloud Run execution does not support env valueSource");
            }
        }
        for (VolumeMount volumeMount : container.getVolumeMountsList()) {
            if (volumeMount.getName().isBlank()) {
                throw GcpException.invalidArgument("Cloud Run execution volume mount name is required");
            }
            if (!volumes.containsKey(volumeMount.getName())) {
                throw GcpException.invalidArgument("Cloud Run execution volume mount references unknown volume: "
                        + volumeMount.getName());
            }
            if (volumeMount.getMountPath().isBlank() || !volumeMount.getMountPath().startsWith("/")) {
                throw GcpException.invalidArgument("Cloud Run execution volume mount path must be absolute");
            }
        }
    }

    private int ingressPort(com.google.cloud.run.v2.Container container) {
        if (container.getPortsCount() == 0 || container.getPorts(0).getContainerPort() == 0) {
            return config.services().cloudrun().execution().defaultPort();
        }
        return container.getPorts(0).getContainerPort();
    }

    private CloudRunRuntimeInstance instance(String project, String location,
                                             com.google.cloud.run.v2.Service service,
                                             Revision revision,
                                             com.google.cloud.run.v2.Container container,
                                             String containerId,
                                             int containerPort,
                                             String dockerNetwork,
                                             ContainerLifecycleManager.EndpointInfo endpoint,
                                             String status,
                                             String lastError,
                                             List<CloudRunRuntimeVolumeMount> gcsVolumeMounts) {
        long now = System.currentTimeMillis();
        long requestTimeoutMillis = requestTimeout(revision).toMillis();
        return new CloudRunRuntimeInstance(project, location, service.getName(), revision.getName(),
                container.getImage(), containerId, containerPort, dockerNetwork, endpoint.host(), endpoint.port(),
                service.getUri(), status, now, now, lastError, requestTimeoutMillis, gcsVolumeMounts);
    }

    List<CloudRunRuntimeVolumeMount> prepareGcsVolumeMounts(Revision revision,
                                                            com.google.cloud.run.v2.Container container) {
        if (container.getVolumeMountsCount() == 0) {
            return List.of();
        }
        if (gcsService == null) {
            throw GcpException.unavailable("Cloud Run execution GCS volumes require Cloud Storage service");
        }

        Map<String, Volume> volumes = new HashMap<>();
        for (Volume volume : revision.getVolumesList()) {
            volumes.put(volume.getName(), volume);
        }

        Map<String, MaterializedGcsVolume> materialized = new HashMap<>();
        List<CloudRunRuntimeVolumeMount> mounts = new ArrayList<>();
        try {
            for (VolumeMount volumeMount : container.getVolumeMountsList()) {
                Volume volume = volumes.get(volumeMount.getName());
                String objectPrefix = normalizeVolumeSubPath(volumeMount.getSubPath());
                String key = volume.getName() + "\0" + objectPrefix;
                MaterializedGcsVolume source = materialized.get(key);
                if (source == null) {
                    source = materializeGcsVolume(volume.getGcs().getBucket(), objectPrefix,
                            volume.getGcs().getReadOnly(), revision.getName(), volume.getName());
                    materialized.put(key, source);
                }
                mounts.add(new CloudRunRuntimeVolumeMount(source.bucket(), source.objectPrefix(), source.volumeName(),
                        null, null, volumeMount.getMountPath(), source.readOnly()));
            }
            return List.copyOf(mounts);
        } catch (RuntimeException e) {
            deleteMaterializedGcsVolumes(mounts);
            materialized.values().forEach(source -> lifecycleManager.removeVolume(source.volumeName()));
            throw e;
        }
    }

    private MaterializedGcsVolume materializeGcsVolume(String bucket, String objectPrefix, boolean readOnly,
                                                       String revisionName, String volumeName) {
        String dockerVolumeName = "floci-gcp-cloudrun-gcs-" + sanitize(lastSegment(revisionName))
                + "-" + sanitize(volumeName) + "-" + UUID.randomUUID();
        try {
            gcsService.getBucket(bucket);
            lifecycleManager.ensureVolume(dockerVolumeName);
            copyGcsSnapshotToVolume(bucket, objectPrefix, dockerVolumeName);
            return new MaterializedGcsVolume(bucket, objectPrefix, dockerVolumeName, readOnly);
        } catch (RuntimeException e) {
            lifecycleManager.removeVolume(dockerVolumeName);
            throw e;
        }
    }

    private void copyGcsSnapshotToVolume(String bucket, String objectPrefix, String volumeName) {
        byte[] tar = gcsVolumeTar(bucket, objectPrefix);
        withGcsVolumeHelper(volumeName, helperId -> lifecycleManager.runDockerApi(
                "copy GCS snapshot to Docker volume " + volumeName, () -> {
                    lifecycleManager.getDockerClient()
                            .copyArchiveToContainerCmd(helperId)
                            .withRemotePath(GCS_VOLUME_HELPER_MOUNT)
                            .withTarInputStream(new ByteArrayInputStream(tar))
                            .exec();
                    return null;
                }));
    }

    private void cleanupGcsVolumeMounts(List<CloudRunRuntimeVolumeMount> mounts) {
        if (mounts == null || mounts.isEmpty()) {
            return;
        }
        Set<String> syncedRoots = new HashSet<>();
        for (CloudRunRuntimeVolumeMount mount : mounts) {
            String syncKey = mount.volumeName() != null && !mount.volumeName().isBlank()
                    ? mount.volumeName()
                    : mount.rootPath();
            if (!mount.readOnly() && syncKey != null && syncedRoots.add(syncKey)) {
                syncWritableGcsVolume(mount);
            }
        }
        deleteMaterializedGcsVolumes(mounts);
    }

    private void syncWritableGcsVolume(CloudRunRuntimeVolumeMount mount) {
        if (mount.volumeName() != null && !mount.volumeName().isBlank()) {
            syncWritableNamedGcsVolume(mount);
            return;
        }
        syncWritableHostGcsVolume(mount);
    }

    private void syncWritableNamedGcsVolume(CloudRunRuntimeVolumeMount mount) {
        if (gcsService == null) {
            return;
        }
        try {
            Map<String, byte[]> files = copyVolumeFiles(mount.volumeName());
            Set<String> diskObjects = new HashSet<>();
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                String objectName = prefixedObjectName(mount.objectPrefix(), file.getKey());
                diskObjects.add(objectName);
                gcsService.putObject(mount.bucket(), objectName, "application/octet-stream",
                        file.getValue(), config.effectiveBaseUrl());
            }
            for (GcsObjectMeta object : gcsService.listObjects(mount.bucket())) {
                if (isInObjectPrefix(object.getName(), mount.objectPrefix())
                        && !diskObjects.contains(object.getName())) {
                    gcsService.deleteObject(mount.bucket(), object.getName());
                }
            }
        } catch (Exception e) {
            LOG.warnf(e, "Cloud Run GCS volume sync failed bucket=%s volume=%s",
                    mount.bucket(), mount.volumeName());
        }
    }

    private void syncWritableHostGcsVolume(CloudRunRuntimeVolumeMount mount) {
        if (gcsService == null || mount.rootPath() == null || mount.rootPath().isBlank()) {
            return;
        }
        Path root = Path.of(mount.rootPath());
        if (!Files.isDirectory(root)) {
            return;
        }
        try {
            Set<String> diskObjects = new HashSet<>();
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                    String objectName = prefixedObjectName(mount.objectPrefix(),
                            root.relativize(path).toString().replace('\\', '/'));
                    diskObjects.add(objectName);
                    String contentType = Files.probeContentType(path);
                    gcsService.putObject(mount.bucket(), objectName,
                            contentType != null ? contentType : "application/octet-stream",
                            Files.readAllBytes(path), config.effectiveBaseUrl());
                }
            }
            for (GcsObjectMeta object : gcsService.listObjects(mount.bucket())) {
                if (isInObjectPrefix(object.getName(), mount.objectPrefix())
                        && !diskObjects.contains(object.getName())) {
                    gcsService.deleteObject(mount.bucket(), object.getName());
                }
            }
        } catch (Exception e) {
            LOG.warnf(e, "Cloud Run GCS volume sync failed bucket=%s root=%s", mount.bucket(), mount.rootPath());
        }
    }

    private void deleteMaterializedGcsVolumes(List<CloudRunRuntimeVolumeMount> mounts) {
        Set<String> deletedRoots = new HashSet<>();
        for (CloudRunRuntimeVolumeMount mount : mounts) {
            if (mount.volumeName() != null && !mount.volumeName().isBlank()) {
                if (deletedRoots.add(mount.volumeName())) {
                    lifecycleManager.removeVolume(mount.volumeName());
                }
            } else if (mount.rootPath() != null && deletedRoots.add(mount.rootPath())) {
                deleteDirectory(Path.of(mount.rootPath()));
            }
        }
    }

    private void deleteDirectory(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    LOG.debugf(e, "Could not delete Cloud Run GCS volume path=%s", path);
                }
            });
        } catch (IOException e) {
            LOG.debugf(e, "Could not delete Cloud Run GCS volume root=%s", root);
        }
    }

    private byte[] gcsVolumeTar(String bucket, String objectPrefix) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (GcsObjectMeta object : gcsService.listObjects(bucket)) {
                Optional<String> entryName = strippedObjectName(object.getName(), objectPrefix);
                if (entryName.isEmpty() || entryName.get().isBlank()) {
                    continue;
                }
                if (object.getName().endsWith("/")) {
                    writeTarEntry(out, entryName.get(), new byte[0], true);
                } else {
                    writeTarEntry(out, entryName.get(), gcsService.getObjectData(bucket, object.getName()), false);
                }
            }
            out.write(new byte[TAR_BLOCK_SIZE * 2]);
            return out.toByteArray();
        } catch (IOException e) {
            throw GcpException.internal("Cloud Run execution could not prepare GCS volume archive: "
                    + e.getMessage());
        }
    }

    private Map<String, byte[]> copyVolumeFiles(String volumeName) {
        Map<String, byte[]> files = new HashMap<>();
        withGcsVolumeHelper(volumeName, helperId -> files.putAll(lifecycleManager.runDockerApi(
                "copy Docker volume files from " + volumeName, () -> {
                    try (InputStream in = lifecycleManager.getDockerClient()
                            .copyArchiveFromContainerCmd(helperId, GCS_VOLUME_HELPER_MOUNT + "/.")
                            .exec()) {
                        return readTarFiles(in);
                    }
                })));
        return files;
    }

    private void withGcsVolumeHelper(String volumeName, VolumeHelperAction action) {
        String helperName = "floci-cloudrun-gcs-volume-" + UUID.randomUUID();
        String helperId = null;
        try {
            ContainerSpec helper = containerBuilder.newContainer(GCS_VOLUME_HELPER_IMAGE)
                    .withName(helperName)
                    .withNamedVolume(volumeName, GCS_VOLUME_HELPER_MOUNT)
                    .withLogRotation()
                    .build();
            helperId = lifecycleManager.create(helper);
            action.accept(helperId);
        } catch (IOException e) {
            throw GcpException.internal("Cloud Run execution could not copy GCS volume archive: "
                    + e.getMessage());
        } finally {
            if (helperId != null) {
                removeGcsVolumeHelper(helperId);
            } else {
                lifecycleManager.removeIfExists(helperName);
            }
        }
    }

    private void removeGcsVolumeHelper(String helperId) {
        try {
            lifecycleManager.forceRemove(helperId, null);
        } catch (Exception e) {
            LOG.debugf(e, "Could not remove Cloud Run GCS volume helper container=%s", helperId);
        }
    }

    private static void writeTarEntry(ByteArrayOutputStream out, String entryName, byte[] data, boolean directory)
            throws IOException {
        String normalized = normalizeArchiveEntryName(entryName, directory);
        if (normalized.isBlank()) {
            return;
        }
        byte[] header = new byte[TAR_BLOCK_SIZE];
        TarName tarName = tarName(normalized);
        writeUtf8(header, 0, 100, tarName.name());
        writeOctal(header, 100, 8, directory ? 0755 : 0644);
        writeOctal(header, 108, 8, 0);
        writeOctal(header, 116, 8, 0);
        writeOctal(header, 124, 12, directory ? 0 : data.length);
        writeOctal(header, 136, 12, System.currentTimeMillis() / 1000);
        Arrays.fill(header, 148, 156, (byte) ' ');
        header[156] = (byte) (directory ? '5' : '0');
        writeUtf8(header, 257, 6, "ustar");
        writeUtf8(header, 263, 2, "00");
        writeUtf8(header, 345, 155, tarName.prefix());

        long checksum = 0;
        for (byte b : header) {
            checksum += b & 0xff;
        }
        writeChecksum(header, checksum);

        out.write(header);
        if (!directory && data.length > 0) {
            out.write(data);
            int padding = TAR_BLOCK_SIZE - (data.length % TAR_BLOCK_SIZE);
            if (padding < TAR_BLOCK_SIZE) {
                out.write(new byte[padding]);
            }
        }
    }

    private static Map<String, byte[]> readTarFiles(InputStream in) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        while (true) {
            byte[] header = readBlock(in);
            if (header == null || isZeroBlock(header)) {
                break;
            }
            String name = readString(header, 0, 100);
            String prefix = readString(header, 345, 155);
            if (!prefix.isBlank()) {
                name = prefix + "/" + name;
            }
            long size = parseOctal(header, 124, 12);
            byte type = header[156];
            byte[] data = in.readNBytes(Math.toIntExact(size));
            skipFully(in, padding(size));
            if ((type == 0 || type == '0') && size >= 0) {
                String normalized = normalizeArchiveEntryName(name, false);
                if (!normalized.isBlank()) {
                    files.put(normalized, data);
                }
            }
        }
        return files;
    }

    private static byte[] readBlock(InputStream in) throws IOException {
        byte[] block = in.readNBytes(TAR_BLOCK_SIZE);
        if (block.length == 0) {
            return null;
        }
        if (block.length != TAR_BLOCK_SIZE) {
            throw new IOException("short tar header");
        }
        return block;
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static int padding(long size) {
        int remainder = (int) (size % TAR_BLOCK_SIZE);
        return remainder == 0 ? 0 : TAR_BLOCK_SIZE - remainder;
    }

    private static void skipFully(InputStream in, int bytes) throws IOException {
        int remaining = bytes;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new IOException("short tar padding");
                }
                skipped = 1;
            }
            remaining -= (int) skipped;
        }
    }

    private static void writeUtf8(byte[] target, int offset, int length, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, target, offset, Math.min(length, bytes.length));
    }

    private static void writeOctal(byte[] target, int offset, int length, long value) {
        String octal = Long.toOctalString(value);
        int start = offset + length - octal.length() - 1;
        if (start < offset) {
            throw GcpException.invalidArgument("Cloud Run GCS volume archive value is too large");
        }
        Arrays.fill(target, offset, offset + length, (byte) '0');
        byte[] bytes = octal.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, start, bytes.length);
        target[offset + length - 1] = 0;
    }

    private static void writeChecksum(byte[] target, long checksum) {
        String octal = Long.toOctalString(checksum);
        if (octal.length() > 6) {
            throw GcpException.invalidArgument("Cloud Run GCS volume archive checksum is too large");
        }
        Arrays.fill(target, 148, 156, (byte) '0');
        byte[] bytes = octal.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, 154 - bytes.length, bytes.length);
        target[154] = 0;
        target[155] = (byte) ' ';
    }

    private static long parseOctal(byte[] source, int offset, int length) {
        long value = 0;
        for (int i = offset; i < offset + length; i++) {
            byte b = source[i];
            if (b == 0 || b == ' ') {
                continue;
            }
            if (b < '0' || b > '7') {
                break;
            }
            value = value * 8 + (b - '0');
        }
        return value;
    }

    private static String readString(byte[] source, int offset, int length) {
        int end = offset;
        int limit = offset + length;
        while (end < limit && source[end] != 0) {
            end++;
        }
        return new String(source, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static TarName tarName(String entryName) {
        byte[] nameBytes = entryName.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length <= 100) {
            return new TarName(entryName, "");
        }
        for (int slash = entryName.lastIndexOf('/'); slash > 0; slash = entryName.lastIndexOf('/', slash - 1)) {
            String prefix = entryName.substring(0, slash);
            String name = entryName.substring(slash + 1);
            if (prefix.getBytes(StandardCharsets.UTF_8).length <= 155
                    && name.getBytes(StandardCharsets.UTF_8).length <= 100) {
                return new TarName(name, prefix);
            }
        }
        throw GcpException.invalidArgument("Cloud Run GCS volume object name is too long: " + entryName);
    }

    private static Optional<String> strippedObjectName(String objectName, String objectPrefix) {
        validateArchivePath(objectName, "object name");
        if (objectPrefix == null || objectPrefix.isBlank()) {
            return Optional.of(objectName);
        }
        String prefix = objectPrefix.endsWith("/") ? objectPrefix : objectPrefix + "/";
        if (!objectName.startsWith(prefix)) {
            return Optional.empty();
        }
        return Optional.of(objectName.substring(prefix.length()));
    }

    private static boolean isInObjectPrefix(String objectName, String objectPrefix) {
        return strippedObjectName(objectName, objectPrefix).isPresent();
    }

    private static String prefixedObjectName(String objectPrefix, String entryName) {
        String normalizedEntry = normalizeArchiveEntryName(entryName, false);
        if (objectPrefix == null || objectPrefix.isBlank()) {
            return normalizedEntry;
        }
        return objectPrefix + "/" + normalizedEntry;
    }

    private static String normalizeVolumeSubPath(String subPath) {
        if (subPath == null || subPath.isBlank() || ".".equals(subPath)) {
            return "";
        }
        validateArchivePath(subPath, "subPath");
        String normalized = Path.of(subPath).normalize().toString().replace('\\', '/');
        return ".".equals(normalized) ? "" : normalized;
    }

    private static String normalizeArchiveEntryName(String entryName, boolean directory) {
        String normalized = entryName == null ? "" : entryName;
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.isBlank() || ".".equals(normalized) || "./".equals(normalized)) {
            return "";
        }
        validateArchivePath(normalized, "archive entry");
        normalized = Path.of(normalized).normalize().toString().replace('\\', '/');
        if (directory && !normalized.endsWith("/")) {
            normalized += "/";
        }
        return normalized;
    }

    private static void validateArchivePath(String value, String description) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
            throw GcpException.invalidArgument("Cloud Run GCS volume " + description + " is invalid: " + value);
        }
        Path path = Path.of(value).normalize();
        if (path.isAbsolute() || path.startsWith("..")) {
            throw GcpException.invalidArgument("Cloud Run GCS volume " + description + " escapes bucket root: "
                    + value);
        }
    }

    private record MaterializedGcsVolume(String bucket, String objectPrefix, String volumeName, boolean readOnly) {}

    private record TarName(String name, String prefix) {}

    @FunctionalInterface
    private interface VolumeHelperAction {
        void accept(String containerId) throws IOException;
    }

    private Duration requestTimeout(Revision revision) {
        if (revision.hasTimeout() && (revision.getTimeout().getSeconds() > 0 || revision.getTimeout().getNanos() > 0)) {
            return Duration.ofSeconds(revision.getTimeout().getSeconds(), revision.getTimeout().getNanos());
        }
        return config.services().cloudrun().execution().requestTimeout();
    }

    private void waitForReady(ContainerLifecycleManager.EndpointInfo endpoint, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), 500);
                return;
            } catch (Exception e) {
                last = new RuntimeException(e);
                try {
                    Thread.sleep(250);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw GcpException.unavailable("Cloud Run runtime startup interrupted");
                }
            }
        }
        String detail = endpoint.toString();
        if (last != null && last.getCause() != null && last.getCause().getMessage() != null) {
            detail = last.getCause().getMessage();
        }
        throw GcpException.unavailable("Cloud Run runtime did not become ready before timeout: " + detail);
    }

    private String containerName(String serviceName, Revision revision) {
        String name = config.services().cloudrun().execution().containerNamePrefix()
                + "-" + sanitize(lastSegment(serviceName))
                + "-" + sanitize(lastSegment(revision.getName()));
        if (revision.getUid().isBlank()) {
            return name;
        }
        return name + "-" + sanitize(revision.getUid());
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_.-]", "-");
    }

    private static String lastSegment(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }
}
