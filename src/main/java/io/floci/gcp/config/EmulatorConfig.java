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

            @WithDefault("floci-cloudrun")
            String containerNamePrefix();

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
