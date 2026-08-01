package io.floci.gcp.core.common.docker;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.dns.EmbeddedDnsServer;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.LogConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.VolumeOptions;
import com.github.dockerjava.api.model.Volume;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class ContainerBuilder {

    private final EmulatorConfig config;
    private final DockerHostResolver dockerHostResolver;
    private final EmbeddedDnsServer embeddedDnsServer;
    private final CurrentContainerNetworkResolver currentContainerNetworkResolver;

    @Inject
    public ContainerBuilder(EmulatorConfig config, DockerHostResolver dockerHostResolver,
                            EmbeddedDnsServer embeddedDnsServer,
                            CurrentContainerNetworkResolver currentContainerNetworkResolver) {
        this.config = config;
        this.dockerHostResolver = dockerHostResolver;
        this.embeddedDnsServer = embeddedDnsServer;
        this.currentContainerNetworkResolver = currentContainerNetworkResolver;
    }

    public ContainerBuilder(EmulatorConfig config, DockerHostResolver dockerHostResolver,
                            EmbeddedDnsServer embeddedDnsServer) {
        this(config, dockerHostResolver, embeddedDnsServer, null);
    }

    public Builder newContainer(String image) {
        return new Builder(resolveImage(image), config, dockerHostResolver, embeddedDnsServer,
                currentContainerNetworkResolver);
    }

    private String resolveImage(String image) {
        return resolveImage(image, configuredImageRegistryBase(config));
    }

    /**
     * Resolves an image reference under the configured registry base
     * ({@code floci-gcp.docker.image-registry-base}), so every image floci-gcp launches can be
     * redirected to a mirror or private registry. Already-prefixed references pass through.
     */
    static String resolveImage(String image, Optional<String> imageRegistryBase) {
        if (image == null || image.isBlank()) {
            return image;
        }
        return normalizeImageRegistryBase(imageRegistryBase)
                .filter(base -> !image.startsWith(base + "/"))
                .map(base -> base + "/" + image)
                .orElse(image);
    }

    private static Optional<String> configuredImageRegistryBase(EmulatorConfig config) {
        if (config == null || config.docker() == null) {
            return Optional.empty();
        }
        return config.docker().imageRegistryBase();
    }

    private static Optional<String> normalizeImageRegistryBase(Optional<String> imageRegistryBase) {
        return imageRegistryBase
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> {
                    while (value.endsWith("/")) {
                        value = value.substring(0, value.length() - 1);
                    }
                    return value;
                });
    }

    public static class Builder {
        private final String image;
        private final EmulatorConfig config;
        private final DockerHostResolver dockerHostResolver;
        private final EmbeddedDnsServer embeddedDnsServer;
        private final CurrentContainerNetworkResolver currentContainerNetworkResolver;

        private String name;
        private final List<String> env = new ArrayList<>();
        private List<String> cmd;
        private List<String> entrypoint;
        private String workingDir;
        private Long memoryBytes;
        private final Map<Integer, Integer> portBindings = new HashMap<>();
        private final List<Integer> exposedPorts = new ArrayList<>();
        private String networkMode;
        private final List<Mount> mounts = new ArrayList<>();
        private final List<Bind> binds = new ArrayList<>();
        private final List<String> extraHosts = new ArrayList<>();
        private final Map<String, String> labels = new HashMap<>();
        private LogConfig logConfig;
        private boolean privileged;
        private String cgroupnsMode;
        private String user;
        private final List<String> groupAdd = new ArrayList<>();
        private final List<String> dnsServers = new ArrayList<>();

        Builder(String image, EmulatorConfig config, DockerHostResolver dockerHostResolver,
                EmbeddedDnsServer embeddedDnsServer,
                CurrentContainerNetworkResolver currentContainerNetworkResolver) {
            this.image = image;
            this.config = config;
            this.dockerHostResolver = dockerHostResolver;
            this.embeddedDnsServer = embeddedDnsServer;
            this.currentContainerNetworkResolver = currentContainerNetworkResolver;
        }

        public Builder withName(String name) {
            this.name = name;
            return this;
        }

        public Builder withEnv(String key, String value) {
            this.env.add(key + "=" + value);
            return this;
        }

        public Builder withEnv(List<String> env) {
            this.env.addAll(env);
            return this;
        }

        public Builder withCmd(List<String> cmd) {
            this.cmd = cmd;
            return this;
        }

        public Builder withCmd(String cmd) {
            this.cmd = List.of(cmd);
            return this;
        }

        public Builder withEntrypoint(List<String> entrypoint) {
            this.entrypoint = entrypoint;
            return this;
        }

        public Builder withWorkingDir(String workingDir) {
            this.workingDir = workingDir;
            return this;
        }

        public Builder withMemoryMb(int memoryMb) {
            this.memoryBytes = (long) memoryMb * 1024 * 1024;
            return this;
        }

        public Builder withMemoryBytes(long memoryBytes) {
            this.memoryBytes = memoryBytes;
            return this;
        }

        public Builder withPortBinding(int containerPort, int hostPort) {
            this.portBindings.put(containerPort, hostPort);
            this.exposedPorts.add(containerPort);
            return this;
        }

        public Builder withDynamicPort(int containerPort) {
            return withPortBinding(containerPort, 0);
        }

        public Builder withExposedPort(int port) {
            this.exposedPorts.add(port);
            return this;
        }

        public Builder withNetworkMode(String networkMode) {
            this.networkMode = networkMode;
            return this;
        }

        public Builder withDockerNetwork(Optional<String> serviceNetwork) {
            Optional<String> configuredNetwork = serviceNetwork
                    .or(() -> config.services().dockerNetwork())
                    .filter(n -> !n.isBlank())
                    .or(() -> currentContainerNetworkResolver != null
                            ? currentContainerNetworkResolver.resolveNetworkName()
                            : Optional.empty());
            configuredNetwork.ifPresent(n -> this.networkMode = n);
            return this;
        }

        public Builder withBind(String hostPath, String containerPath) {
            this.binds.add(new Bind(hostPath, new Volume(containerPath)));
            return this;
        }

        public Builder withReadOnlyBind(String hostPath, String containerPath) {
            this.binds.add(new Bind(hostPath, new Volume(containerPath), AccessMode.ro));
            return this;
        }

        public Builder withNamedVolume(String volumeName, String containerPath) {
            return withNamedVolume(volumeName, containerPath, false);
        }

        public Builder withNamedVolume(String volumeName, String containerPath, boolean readOnly) {
            Mount mount = new Mount()
                    .withType(MountType.VOLUME)
                    .withSource(volumeName)
                    .withTarget(containerPath)
                    .withReadOnly(readOnly);
            // Read-only volumes are pre-populated (e.g. Cloud Run GCS snapshots), so don't
            // seed them from the image. Read-write data volumes (Kafka, Cloud SQL) instead
            // need the image's initialized directory and ownership, so they must be seeded:
            // a non-root image cannot write to an empty root-owned volume and crashes on
            // startup otherwise.
            if (readOnly) {
                mount.withVolumeOptions(new VolumeOptions().withNoCopy(true));
            }
            this.mounts.add(mount);
            return this;
        }

        public Builder withMount(Mount mount) {
            this.mounts.add(mount);
            return this;
        }

        public Builder withHostDockerInternalOnLinux() {
            if (dockerHostResolver.isLinuxHost()) {
                this.extraHosts.add("host.docker.internal:host-gateway");
            }
            return this;
        }

        public Builder withExtraHost(String hostname, String ip) {
            this.extraHosts.add(hostname + ":" + ip);
            return this;
        }

        public Builder withLabel(String key, String value) {
            this.labels.put(key, value);
            return this;
        }

        public Builder withLabels(Map<String, String> labels) {
            this.labels.putAll(labels);
            return this;
        }

        public Builder withLogRotation() {
            return withLogRotation(config.docker().logMaxSize(), config.docker().logMaxFile());
        }

        public Builder withLogRotation(String maxSize, String maxFile) {
            this.logConfig = new LogConfig(
                    LogConfig.LoggingType.JSON_FILE,
                    Map.of("max-size", maxSize, "max-file", maxFile));
            return this;
        }

        public Builder withLogConfig(LogConfig logConfig) {
            this.logConfig = logConfig;
            return this;
        }

        public Builder withPrivileged(boolean privileged) {
            this.privileged = privileged;
            return this;
        }

        /** Sets the Docker cgroup namespace mode (for example, {@code "host"}). */
        public Builder withCgroupnsMode(String cgroupnsMode) {
            this.cgroupnsMode = cgroupnsMode;
            return this;
        }

        /**
         * Sets the user the container process runs as, formatted {@code "uid[:gid]"}
         * (Docker's top-level container user). Null or blank leaves the image {@code USER}
         * in effect.
         */
        public Builder withUser(String user) {
            this.user = user;
            return this;
        }

        /**
         * Adds a supplementary group id to the container process (Docker {@code --group-add}),
         * so it can access group-owned resources without changing its primary uid/gid. Blank
         * ids are ignored.
         */
        public Builder withGroupAdd(String groupId) {
            if (groupId != null && !groupId.isBlank()) {
                this.groupAdd.add(groupId);
            }
            return this;
        }

        /**
         * Injects floci-gcp's embedded DNS server into the container so emulator hostnames
         * resolve to floci-gcp's Docker network IP. No-op when the embedded DNS server is
         * not running.
         *
         * <p>When {@code floci-gcp.dns.container-fallback-enabled} is set, the configured public
         * fallback resolvers are appended after floci-gcp's IP so the container's own resolver
         * can fall through for public hostnames if the embedded forwarder cannot answer.
         */
        public Builder withEmbeddedDns() {
            embeddedDnsServer.getServerIp().ifPresent(flociIp -> {
                dnsServers.add(flociIp);
                if (config.dns().containerFallbackEnabled()) {
                    for (String fallback : config.dns().containerFallbackServers()) {
                        if (fallback != null && !fallback.isBlank() && !dnsServers.contains(fallback.trim())) {
                            dnsServers.add(fallback.trim());
                        }
                    }
                }
            });
            return this;
        }

        public ContainerSpec build() {
            return new ContainerSpec(
                    image,
                    name,
                    List.copyOf(env),
                    cmd != null ? List.copyOf(cmd) : null,
                    entrypoint != null ? List.copyOf(entrypoint) : null,
                    memoryBytes,
                    Map.copyOf(portBindings),
                    List.copyOf(exposedPorts),
                    networkMode,
                    List.copyOf(mounts),
                    List.copyOf(binds),
                    List.copyOf(extraHosts),
                    Map.copyOf(labels),
                    logConfig,
                    privileged,
                    cgroupnsMode,
                    List.copyOf(dnsServers),
                    workingDir,
                    user,
                    List.copyOf(groupAdd)
            );
        }
    }
}
