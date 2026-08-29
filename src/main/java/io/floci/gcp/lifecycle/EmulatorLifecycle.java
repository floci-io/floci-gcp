package io.floci.gcp.lifecycle;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.ContainerTeardown;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.PersistentPathValidator;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.core.tls.TlsConfigSource;
import io.floci.gcp.lifecycle.inithook.InitializationHook;
import io.floci.gcp.lifecycle.inithook.InitializationHooksRunner;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.ShutdownDelayInitiatedEvent;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.http.HttpServerStart;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.Optional;

@ApplicationScoped
public class EmulatorLifecycle {

    private static final Logger LOG = Logger.getLogger(EmulatorLifecycle.class);

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "")
    Optional<String> appVersion = Optional.empty();

    private final StorageFactory storageFactory;
    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final InitializationHooksRunner hooksRunner;
    private final InitLifecycleState initLifecycleState;
    private final PersistentPathValidator persistentPathValidator;
    private final Instance<ContainerTeardown> containerTeardowns;

    @Inject
    public EmulatorLifecycle(StorageFactory storageFactory, ServiceRegistry serviceRegistry,
                             EmulatorConfig config, InitializationHooksRunner hooksRunner,
                             InitLifecycleState initLifecycleState,
                             PersistentPathValidator persistentPathValidator,
                             Instance<ContainerTeardown> containerTeardowns) {
        this.storageFactory = storageFactory;
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.hooksRunner = hooksRunner;
        this.initLifecycleState = initLifecycleState;
        this.persistentPathValidator = persistentPathValidator;
        this.containerTeardowns = containerTeardowns;
    }

    void onStart(@Observes StartupEvent ignored) {
        LOG.infof("=== floci-gcp %s Starting ===", appVersion.orElse(""));
        LOG.infof("Endpoint:  http://0.0.0.0:%d", config.port());
        LOG.infof("Project:   %s", config.defaultProjectId());
        LOG.infov("Storage:   {0}  Path: {1}", config.storage().mode(), config.storage().persistentPath());

        try {
            hooksRunner.run(InitializationHook.BOOT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Boot hook interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Boot hook failed", e);
        }
        initLifecycleState.markBootCompleted();

        persistentPathValidator.validateAtBoot();

        storageFactory.loadAll();

        boolean hasStart = hooksRunner.hasHooks(InitializationHook.START);
        boolean hasReady = hooksRunner.hasHooks(InitializationHook.READY);
        if (!hasStart && !hasReady) {
            initLifecycleState.markStartCompleted();
            initLifecycleState.markReadyCompleted();
            LOG.info("=== floci-gcp Ready ===");
        }
    }

    void onHttpStart(@ObservesAsync HttpServerStart event) {
        if (event.options().getPort() != primaryHttpPort()) {
            return;
        }
        serviceRegistry.logEnabledServices();
        boolean hasStart = hooksRunner.hasHooks(InitializationHook.START);
        boolean hasReady = hooksRunner.hasHooks(InitializationHook.READY);
        if (!hasStart && !hasReady) {
            return;
        }
        try {
            if (hasStart) {
                hooksRunner.run(InitializationHook.START);
            }
            initLifecycleState.markStartCompleted();
            if (hasReady) {
                hooksRunner.run(InitializationHook.READY);
            }
            initLifecycleState.markReadyCompleted();
            LOG.info("=== floci-gcp Ready ===");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Startup hook interrupted — shutting down", e);
        } catch (Exception e) {
            LOG.error("Startup hook failed — shutting down", e);
            Quarkus.asyncExit();
        }
    }

    /**
     * The port Quarkus actually binds for the plaintext listener.
     *
     * <p>With TLS enabled the public {@link EmulatorConfig#port()} belongs to the TLS proxy and
     * Quarkus moves to internal loopback ports, so matching the public port here would never fire
     * and the START/READY hooks would silently never run. Matching the internal HTTP port keeps
     * exactly one listener triggering the hooks, as before.
     */
    int primaryHttpPort() {
        return config.tls().enabled() ? TlsConfigSource.HTTP_INTERNAL_PORT : config.port();
    }

    void onPreShutdown(@Observes ShutdownDelayInitiatedEvent ignored) {
        LOG.info("=== floci-gcp Shutting Down ===");
        initLifecycleState.markShutdownStarted();
        try {
            hooksRunner.run(InitializationHook.STOP);
        } catch (InterruptedException e) {
            LOG.error("Shutdown hook interrupted", e);
        } catch (IOException e) {
            LOG.error("Shutdown hook failed", e);
        } catch (RuntimeException e) {
            LOG.error("Shutdown hook script failed", e);
        }
    }

    void onStop(@Observes ShutdownEvent ignored) {
        // Flush persisted state to disk FIRST, before any slow container teardown below.
        // Stopping Docker sidecars can block long enough to exhaust the SIGTERM grace window
        // and trigger SIGKILL; if the flush ran last it would be skipped and in-memory (hybrid)
        // data would be lost on an otherwise-graceful shutdown. shutdownAll() still runs at the
        // end to stop the flush schedulers and capture any shutdown-time writes.
        storageFactory.flushAll();
        // Centralized teardown for process-bound containers (Cloud Run instances, Cloud
        // Functions workers, in-flight build/job containers). Runs before shutdownAll() so any
        // state written while stopping is captured by the final flush.
        for (ContainerTeardown teardown : containerTeardowns) {
            try {
                teardown.stopManagedContainers();
            } catch (Exception e) {
                LOG.warnv("Container teardown failed for {0}: {1}",
                        teardown.getClass().getSimpleName(), e.getMessage());
            }
        }
        storageFactory.shutdownAll();
        LOG.info("=== floci-gcp Stopped ===");
    }
}
