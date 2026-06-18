package io.floci.gcp.lifecycle;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.StorageFactory;
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

    @Inject
    public EmulatorLifecycle(StorageFactory storageFactory, ServiceRegistry serviceRegistry,
                             EmulatorConfig config, InitializationHooksRunner hooksRunner,
                             InitLifecycleState initLifecycleState) {
        this.storageFactory = storageFactory;
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.hooksRunner = hooksRunner;
        this.initLifecycleState = initLifecycleState;
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
        if (event.options().getPort() != config.port()) {
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
        storageFactory.shutdownAll();
        LOG.info("=== floci-gcp Stopped ===");
    }
}
