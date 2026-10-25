package io.floci.gcp.services.gke;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GkeBetaFieldsTest {

    @Test
    void namesOnlyBetaFieldsWithNonDefaultValues() {
        Map<String, Object> cluster = Map.of(
                "name", "c1",
                "workloadAltsConfig", Map.of("enableAlts", true),
                "protectConfig", Map.of(),
                "tpuConfig", Map.of("enabled", false),
                "nodeConfig", Map.of("hostMaintenancePolicy", Map.of("maintenanceInterval", "PERIODIC")),
                "nodePools", List.of(Map.of("config", Map.of("ephemeralStorageConfig", Map.of("localSsdCount", 2)))));

        assertEquals(List.of("workloadAltsConfig", "nodeConfig.hostMaintenancePolicy",
                        "nodePools[0].config.ephemeralStorageConfig"),
                GkeBetaFields.setOnCluster(cluster));
    }

    @Test
    void v1BodyHasNoBetaFields() {
        assertEquals(List.of(), GkeBetaFields.setOnCluster(Map.of(
                "name", "c1", "legacyAbac", Map.of("enabled", false), "networkConfig", Map.of())));
        assertEquals(List.of(), GkeBetaFields.setOnNodePool(Map.of("name", "p1")));
        assertEquals(List.of(), GkeBetaFields.setOnCluster(null));
    }
}
