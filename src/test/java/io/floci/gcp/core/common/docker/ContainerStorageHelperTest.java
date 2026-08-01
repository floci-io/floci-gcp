package io.floci.gcp.core.common.docker;

import io.floci.gcp.config.EmulatorConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContainerStorageHelperTest {

    @Test
    void namesCarryTheGcpPrefixWithoutNamespace() {
        assertEquals("floci-gcp-cloudsql-p-i", ContainerStorageHelper.dockerName(config(""), "cloudsql-p-i"));
        assertEquals("floci-gcp-cloudsql-p-i", ContainerStorageHelper.resourceName(config(""), "cloudsql", null, "p-i"));
        assertEquals("floci-gcp-kafka-abc123", ContainerStorageHelper.resourceName(config(""), "kafka", "abc123", "c1"));
    }

    @Test
    void nullConfigYieldsDefaultModeNames() {
        assertEquals("floci-gcp-cloudsql-abc123", ContainerStorageHelper.resourceName(null, "cloudsql", "abc123", "p-i"));
        assertEquals("floci-gcp-cloudsql-p-i", ContainerStorageHelper.resourceName(null, "cloudsql", null, "p-i"));
    }

    @Test
    void namespaceLandsBetweenCloudAndServiceTokens() {
        assertEquals("floci-gcp-run-one-kafka-abc123",
                ContainerStorageHelper.resourceName(config(" run/one "), "kafka", "abc123", "c1"));
        assertEquals("floci-gcp-run-one-gke-p1-c1",
                ContainerStorageHelper.dockerName(config("run-one"), "gke-p1-c1"));
    }

    @Test
    void alreadyPrefixedNamesAreNormalized() {
        assertEquals("floci-gcp-kafka-x", ContainerStorageHelper.dockerName(config(""), "floci-gcp-kafka-x"));
        assertEquals("floci-gcp-kafka-x", ContainerStorageHelper.dockerName(config(""), "floci-kafka-x"));
        assertEquals("floci-gcp-gke-c1", ContainerStorageHelper.dockerName(config(""), "floci-gke-c1"));
        assertEquals("floci-gcp-run-one-kafka-x", ContainerStorageHelper.dockerName(config("run-one"), "floci-gcp-kafka-x"));
        assertEquals("floci-gcp-run-one-cloudrun-svc", ContainerStorageHelper.dockerName(config("run-one"), "floci-cloudrun-svc"));
    }

    @Test
    void defaultLabelsIdentifyThisEmulator() {
        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-gcp"),
                ContainerStorageHelper.defaultLabels(config("")));
        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-gcp", "floci_namespace", "run-one"),
                ContainerStorageHelper.defaultLabels(config(" run/one ")));
        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-gcp"),
                ContainerStorageHelper.defaultLabels(null));
    }

    @Test
    void unsafeNamespaceSegmentsAreIgnored() {
        assertEquals("floci-gcp-kafka-x", ContainerStorageHelper.dockerName(config(".."), "kafka-x"));
        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-gcp"),
                ContainerStorageHelper.defaultLabels(config("..")));
    }

    private static EmulatorConfig config(String namespace) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);
        when(config.docker()).thenReturn(docker);
        when(docker.resourceNamespace()).thenReturn(namespace.isBlank() ? Optional.empty() : Optional.of(namespace));
        return config;
    }
}
