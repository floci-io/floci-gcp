package io.floci.gcp.core.common.docker;

import io.floci.gcp.config.EmulatorConfig;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Duration;

@ApplicationScoped
public class DockerClientProducer {

    private static final Logger LOG = Logger.getLogger(DockerClientProducer.class);

    private final EmulatorConfig config;
    private final Object lock = new Object();
    private volatile DockerClient dockerClient;

    @Inject
    public DockerClientProducer(EmulatorConfig config) {
        this.config = config;
    }

    static String normalizeDockerHost(String dockerHost) {
        if (dockerHost == null || dockerHost.isEmpty()) {
            return dockerHost;
        }
        String lower = dockerHost.toLowerCase();
        if (lower.startsWith("tcp://") || lower.startsWith("unix://") || lower.startsWith("npipe://")) {
            return dockerHost;
        }
        String normalized = "tcp://" + dockerHost;
        LOG.infov("Docker host ''{0}'' has no URI scheme; normalizing to ''{1}''", dockerHost, normalized);
        return normalized;
    }

    static String resolveEffectiveDockerHost(String configuredHost, String dockerHostEnv) {
        String normalizedEnvHost = normalizeDockerHost(dockerHostEnv);
        if ("unix:///var/run/docker.sock".equals(configuredHost)
                && normalizedEnvHost != null && !normalizedEnvHost.isBlank()) {
            return normalizedEnvHost;
        }
        return normalizeDockerHost(configuredHost);
    }

    private static DefaultDockerClientConfig.Builder createDockerConfigBuilder() {
        try {
            return DefaultDockerClientConfig.createDefaultConfigBuilder();
        } catch (IllegalArgumentException e) {
            LOG.warnv("Could not initialize Docker config from environment "
                    + "(DOCKER_HOST env var may be missing a URI scheme): {0}. "
                    + "Using configured host.", e.getMessage());
            return new DefaultDockerClientConfig.Builder();
        }
    }

    @Produces
    @ApplicationScoped
    public DockerClient dockerClient() {
        return client();
    }

    public DockerClient client() {
        DockerClient existing = dockerClient;
        if (existing != null) {
            return existing;
        }
        synchronized (lock) {
            if (dockerClient == null) {
                dockerClient = createDockerClient();
            }
            return dockerClient;
        }
    }

    public Duration apiTimeout() {
        return config.docker().apiTimeout();
    }

    public void reset(String reason) {
        reset(reason, true);
    }

    private void reset(String reason, boolean warn) {
        DockerClient stale;
        synchronized (lock) {
            stale = dockerClient;
            dockerClient = null;
        }
        if (stale == null) {
            return;
        }
        if (warn) {
            LOG.warnv("Resetting DockerClient: {0}", reason);
        } else {
            LOG.debugv("Closing DockerClient: {0}", reason);
        }
        try {
            stale.close();
        } catch (IOException e) {
            LOG.debugv("Error closing stale DockerClient: {0}", e.getMessage());
        }
    }

    @PreDestroy
    void close() {
        reset("floci-gcp shutdown", false);
    }

    private DockerClient createDockerClient() {
        String dockerHost = resolveEffectiveDockerHost(
                config.docker().dockerHost(), System.getenv("DOCKER_HOST"));
        LOG.infov("Creating DockerClient for host: {0}", dockerHost);

        DefaultDockerClientConfig.Builder builder = createDockerConfigBuilder();
        builder.withDockerHost(dockerHost);
        config.docker().dockerConfigPath().ifPresent(path -> {
            LOG.infov("Using Docker config path: {0}", path);
            builder.withDockerConfig(path);
        });
        DefaultDockerClientConfig clientConfig = builder.build();

        ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(clientConfig.getDockerHost())
                .maxConnections(100)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(config.docker().apiTimeout())
                .build();

        return DockerClientImpl.getInstance(clientConfig, httpClient);
    }
}
