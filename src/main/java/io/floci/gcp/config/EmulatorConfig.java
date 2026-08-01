package io.floci.gcp.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "floci-gcp")
public interface EmulatorConfig {

    @WithDefault("4588")
    int port();

    @WithDefault("http://localhost:4588")
    String baseUrl();

    Optional<String> hostname();

    @WithDefault("floci-local")
    String defaultProjectId();

    @WithDefault("512")
    int maxRequestSize();

    default String effectiveBaseUrl() {
        return hostname()
                .map(h -> baseUrl().replaceFirst("://[^:/]+(:\\d+)?", "://" + h + "$1"))
                .orElse(baseUrl());
    }

    DnsConfig dns();

    StorageConfig storage();

    ServicesConfig services();

    DockerConfig docker();

    InitHooksConfig initHooks();

    interface DnsConfig {
        Optional<List<String>> extraSuffixes();

        /**
         * When {@code true} (default), the configured {@link #containerFallbackServers()} are
         * appended after floci-gcp's embedded DNS to every spawned container's
         * {@code HostConfig.Dns}. This gives Cloud Run/GKE/sidecar containers a real secondary
         * resolver so public hostnames still resolve if floci-gcp's embedded forwarder cannot
         * answer — mirroring the {@code docker run --dns <flociIp> --dns 8.8.8.8} workaround.
         *
         * <p>Disable (via {@code FLOCI_GCP_DNS_CONTAINER_FALLBACK_ENABLED=false}) in offline or
         * locked-down networks where the public resolvers are unreachable/blocked.
         */
        @WithDefault("true")
        boolean containerFallbackEnabled();

        /**
         * Ordered list of public DNS resolvers injected into spawned containers as secondary
         * resolvers when {@link #containerFallbackEnabled()} is set.
         *
         * <p>Via environment variable (comma-separated):
         * <pre>
         * FLOCI_GCP_DNS_CONTAINER_FALLBACK_SERVERS=1.1.1.1,1.0.0.1
         * </pre>
         */
        @WithDefault("8.8.8.8,8.8.4.4")
        List<String> containerFallbackServers();
    }

    interface StorageConfig {

        /** Supported modes: memory, persistent, hybrid, wal */
        @WithDefault("memory")
        String mode();

        @WithDefault("./data")
        String persistentPath();

        /** The path on the host machine where data is stored. Useful for Docker-in-Docker. */
        @WithDefault("./data")
        String hostPersistentPath();

        @WithDefault("false")
        boolean pruneVolumesOnDelete();

        WalConfig wal();
    }

    interface WalConfig {
        @WithDefault("30000")
        long compactionIntervalMs();
    }

    interface ServicesConfig {

        /** Shared Docker network for sidecar containers. */
        Optional<String> dockerNetwork();

        GcsServiceConfig gcs();

        PubSubServiceConfig pubsub();

        FirestoreServiceConfig firestore();

        DatastoreServiceConfig datastore();

        IamServiceConfig iam();

        IamCredentialsServiceConfig iamcredentials();

        SecretManagerServiceConfig secretmanager();

        LoggingServiceConfig logging();

        CloudKmsServiceConfig kms();

        KafkaServiceConfig kafka();

        CloudSqlServiceConfig cloudsql();

        CloudTasksServiceConfig cloudtasks();

        CloudRunServiceConfig cloudrun();

        CloudFunctionsServiceConfig cloudfunctions();

        MonitoringServiceConfig monitoring();

        SchedulerServiceConfig scheduler();

        EventarcServiceConfig eventarc();

        GkeServiceConfig gke();

        ServiceUsageServiceConfig serviceusage();

        ResourceManagerServiceConfig resourcemanager();

        FirebaseAuthServiceConfig firebaseauth();

        BigQueryServiceConfig bigquery();
    }

    interface ServiceUsageServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface FirebaseAuthServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface BigQueryServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface ResourceManagerServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface GkeServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("false")
        boolean mock();

        @WithDefault("rancher/k3s:latest")
        String defaultImage();

        @WithDefault("6550")
        int apiServerBasePort();

        @WithDefault("6599")
        int apiServerMaxPort();

        @WithDefault("false")
        boolean keepRunningOnShutdown();

        @WithDefault("host")
        String endpointMode();

        Optional<String> dockerNetwork();
    }

    interface GcsServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface PubSubServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface FirestoreServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface DatastoreServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface IamServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface IamCredentialsServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface SecretManagerServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface LoggingServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface CloudKmsServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface KafkaServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("false")
        boolean mock();

        @WithDefault("redpandadata/redpanda:latest")
        String defaultImage();

        Optional<String> dockerNetwork();
    }

    interface CloudSqlServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("false")
        boolean mock();

        @WithDefault("postgres:15.18-alpine")
        String postgres15Image();

        @WithDefault("postgres:16.14-alpine")
        String postgres16Image();

        @WithDefault("postgres:17.10-alpine")
        String postgres17Image();

        @WithDefault("postgres:18.4-alpine")
        String postgres18Image();

        @WithDefault("90")
        int startupTimeoutSeconds();
    }

    interface CloudTasksServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface CloudRunServiceConfig {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("false")
        boolean mock();

        ExecutionConfig execution();

        interface ExecutionConfig {
            @WithDefault("8080")
            int defaultPort();

            @WithDefault("240s")
            Duration startupTimeout();

            @WithDefault("300s")
            Duration requestTimeout();

            @WithDefault("300s")
            Duration operationTimeout();

            @WithDefault("15s")
            Duration cleanupTimeout();

            Optional<String> urlHostSuffix();
        }
    }

    interface CloudFunctionsServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface MonitoringServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface EventarcServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface SchedulerServiceConfig {
        @WithDefault("true")
        boolean enabled();

        /** When false, the background dispatcher does not fire due jobs (RunJob still works). */
        @WithDefault("true")
        boolean invocationEnabled();

        /** Interval between scheduler dispatcher ticks. */
        @WithDefault("10")
        long tickIntervalSeconds();
    }

    interface DockerConfig {

        @WithDefault("10m")
        String logMaxSize();

        @WithDefault("3")
        String logMaxFile();

        @WithDefault("unix:///var/run/docker.sock")
        String dockerHost();

        @WithDefault("30s")
        Duration apiTimeout();

        Optional<String> dockerConfigPath();

        /**
         * Optional namespace inserted into Floci-managed sidecar container and volume names.
         * Useful when multiple Floci processes share one Docker daemon.
         */
        Optional<String> resourceNamespace();

        /**
         * Optional registry/repository base for every Docker image floci-gcp launches.
         * When set, images such as {@code postgres:16.14-alpine} and
         * {@code redpandadata/redpanda:latest} resolve under this base before the
         * container is created.
         */
        Optional<String> imageRegistryBase();

        /**
         * Explicit credentials for private Docker registries.
         * Each entry maps a registry hostname to a username/password pair.
         * Use when mounting the host Docker config is impractical.
         */
        @WithDefault("")
        List<RegistryCredential> registryCredentials();

        interface RegistryCredential {
            /** Registry hostname (e.g. myregistry.example.com). */
            String server();
            String username();
            String password();
        }
    }

    interface InitHooksConfig {

        @WithDefault("/bin/sh")
        String shellExecutable();

        @WithDefault("2")
        long shutdownGracePeriodSeconds();

        @WithDefault("30")
        long timeoutSeconds();
    }
}
