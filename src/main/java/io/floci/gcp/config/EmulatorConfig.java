package io.floci.gcp.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

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

        KafkaServiceConfig kafka();

        CloudSqlServiceConfig cloudsql();

        CloudTasksServiceConfig cloudtasks();

        CloudRunServiceConfig cloudrun();

        CloudFunctionsServiceConfig cloudfunctions();
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
    }

    interface CloudTasksServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface CloudRunServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface CloudFunctionsServiceConfig {
        @WithDefault("true")
        boolean enabled();
    }

    interface DockerConfig {

        @WithDefault("10m")
        String logMaxSize();

        @WithDefault("3")
        String logMaxFile();

        @WithDefault("unix:///var/run/docker.sock")
        String dockerHost();

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
