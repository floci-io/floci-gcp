package io.floci.gcp.core.common.docker;

import io.floci.gcp.config.EmulatorConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void resourceIdentityLabelsNameTheBackedResource() {
        assertEquals(
                Map.of("io.floci", "gcp", "io.floci.service", "cloudsql", "io.floci.resource-id", "pg-main",
                        "io.floci.project", "p1", "io.floci.location", "us-central1"),
                ContainerStorageHelper.resourceIdentityLabels("cloudsql", "pg-main", "p1", "us-central1"));
    }

    @Test
    void resourceIdentityLabelsOmitBlankValues() {
        assertEquals(
                Map.of("io.floci", "gcp", "io.floci.service", "bigquery"),
                ContainerStorageHelper.resourceIdentityLabels("bigquery", null, "", " "));
        assertEquals(
                Map.of("io.floci", "gcp"),
                ContainerStorageHelper.resourceIdentityLabels(null, null, null, null));
    }

    @Test
    void legacyAliasesCarryTheNewKeysValues() {
        Map<String, String> labels = ContainerStorageHelper.withLegacyAliases(
                ContainerStorageHelper.resourceIdentityLabels("cloudrun", "svc-00001", "p1", "us-central1"));

        assertEquals("cloudrun", labels.get("floci_service"));
        assertEquals("svc-00001", labels.get("floci_resource"));
        assertEquals("p1", labels.get("floci_project"));
        assertEquals("us-central1", labels.get("floci_location"));
        assertEquals(9, labels.size());
    }

    @Test
    void legacyAliasesAreOnlyAddedForPresentKeys() {
        assertEquals(
                Map.of("io.floci", "gcp", "io.floci.service", "bigquery", "floci_service", "bigquery", "floci", "true"),
                ContainerStorageHelper.withLegacyAliases(Map.of(
                        "io.floci", "gcp", "io.floci.service", "bigquery", "floci", "true")));
    }

    @Test
    void labelValuePrefersTheNewKeyAndFallsBackToTheLegacyKey() {
        assertEquals("new", ContainerStorageHelper.labelValue(
                Map.of("io.floci.service", "new", "floci_service", "old"), "io.floci.service"));
        assertEquals("old", ContainerStorageHelper.labelValue(
                Map.of("floci_service", "old"), "io.floci.service"));
        assertNull(ContainerStorageHelper.labelValue(Map.of("floci", "true"), "io.floci.service"));
        assertNull(ContainerStorageHelper.labelValue(null, "io.floci.service"));
    }

    @Test
    void legacyLabelFilterRespellsAliasedKeysOnly() {
        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-gcp", "floci_service", "cloudrun"),
                ContainerStorageHelper.legacyLabelFilter(Map.of(
                        "floci", "true", "floci_emulator", "floci-gcp", "io.floci.service", "cloudrun")));
        assertEquals(Map.of(), ContainerStorageHelper.legacyLabelFilter(Map.of("floci", "true")));
    }

    @Test
    void conflictingAliasesReportOnlyDisagreeingPairs() {
        assertEquals(List.of(), ContainerStorageHelper.conflictingAliases(
                Map.of("io.floci.service", "cloudrun", "floci_service", "cloudrun")));
        assertEquals(List.of(), ContainerStorageHelper.conflictingAliases(Map.of("floci_resource", "x")));
        assertEquals(List.of("io.floci.resource-id"), ContainerStorageHelper.conflictingAliases(
                Map.of("io.floci.service", "cloudrun", "floci_service", "cloudrun",
                        "io.floci.resource-id", "a", "floci_resource", "b")));
    }

    @Test
    void everyAliasedKeyIsAnIoFlociKeyWithAFlociLegacyKey() {
        assertEquals(
                Map.of("io.floci.service", "floci_service", "io.floci.resource-id", "floci_resource",
                        "io.floci.project", "floci_project", "io.floci.location", "floci_location"),
                ContainerStorageHelper.LEGACY_LABEL_ALIASES);
    }

    private static EmulatorConfig config(String namespace) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);
        when(config.docker()).thenReturn(docker);
        when(docker.resourceNamespace()).thenReturn(namespace.isBlank() ? Optional.empty() : Optional.of(namespace));
        return config;
    }
}
