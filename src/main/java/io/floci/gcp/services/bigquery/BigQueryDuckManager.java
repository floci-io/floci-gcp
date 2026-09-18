package io.floci.gcp.services.bigquery;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.ContainerTeardown;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.docker.ContainerBuilder;
import io.floci.gcp.core.common.docker.ContainerDetector;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.gcp.core.common.docker.ContainerSpec;
import io.floci.gcp.core.common.docker.ContainerStorageHelper;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Optional;

/**
 * Lazily starts the floci-duck sidecar that executes BigQuery SQL. The first query pulls
 * the image and starts the container; later calls reuse its URL. When
 * {@code floci-gcp.services.bigquery.duck.url} is set, that endpoint is used as-is and no
 * container is managed. The container is stopped through {@link ContainerTeardown}, while the
 * Docker client is still usable.
 */
@ApplicationScoped
public class BigQueryDuckManager implements ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(BigQueryDuckManager.class);
    private static final String CONTAINER_BASE_NAME = "bigquery-duck";
    private static final int DUCK_PORT = 3000;
    private static final long HEALTH_TIMEOUT_MS = 30_000;
    private static final long HEALTH_POLL_INTERVAL_MS = 500;

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final EmulatorConfig config;

    private volatile String resolvedUrl;
    private volatile String containerId;

    @Inject
    public BigQueryDuckManager(ContainerBuilder containerBuilder,
                               ContainerLifecycleManager lifecycleManager,
                               ContainerDetector containerDetector,
                               EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.config = config;
    }

    /** Returns the floci-duck base URL, starting the container on first use. */
    public synchronized String ensureReady() {
        if (resolvedUrl != null) {
            return resolvedUrl;
        }
        Optional<String> configured = config.services().bigquery().duck().url();
        if (configured.isPresent() && !configured.get().isBlank()) {
            resolvedUrl = stripTrailingSlash(configured.get());
            LOG.infov("Using pre-configured floci-duck URL for BigQuery: {0}", resolvedUrl);
            return resolvedUrl;
        }
        startContainer();
        return resolvedUrl;
    }

    private void startContainer() {
        String image = config.services().bigquery().duck().defaultImage();
        String containerName = ContainerStorageHelper.dockerName(config, CONTAINER_BASE_NAME);
        LOG.infov("Starting BigQuery SQL engine container {0} using image {1}", containerName, image);
        lifecycleManager.removeIfExists(containerName);

        ContainerBuilder.Builder builder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withDockerNetwork(config.services().dockerNetwork())
                .withHostDockerInternalOnLinux()
                .withLogRotation();
        if (containerDetector.isRunningInContainer()) {
            builder.withExposedPort(DUCK_PORT);
        } else {
            builder.withDynamicPort(DUCK_PORT);
        }
        ContainerSpec spec = builder.build();

        ContainerInfo info = lifecycleManager.createAndStart(spec);
        containerId = info.containerId();
        String url = "http://" + info.getEndpoint(DUCK_PORT);
        try {
            awaitHealthy(url);
        } catch (RuntimeException e) {
            lifecycleManager.stopAndRemove(containerId, null);
            containerId = null;
            throw e;
        }
        resolvedUrl = url;
        LOG.infov("BigQuery SQL engine is ready at {0}", resolvedUrl);
    }

    private static void awaitHealthy(String baseUrl) {
        long deadline = System.currentTimeMillis() + HEALTH_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (isHealthy(baseUrl)) {
                return;
            }
            try {
                Thread.sleep(HEALTH_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw GcpException.unavailable("Interrupted while starting the BigQuery SQL engine");
            }
        }
        throw GcpException.unavailable("The BigQuery SQL engine (floci-duck) did not become healthy within "
                + HEALTH_TIMEOUT_MS + " ms");
    }

    private static boolean isHealthy(String baseUrl) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl + "/health").toURL().openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Override
    public synchronized void stopManagedContainers() {
        if (containerId == null) {
            return;
        }
        LOG.info("Stopping BigQuery SQL engine container");
        lifecycleManager.stopAndRemove(containerId, null);
        containerId = null;
        resolvedUrl = null;
    }

    @PreDestroy
    void shutdown() {
        stopManagedContainers();
    }
}
