package io.floci.gcp.services.cloudrun;

import com.google.cloud.run.v2.Container;
import com.google.cloud.run.v2.ResourceRequirements;
import io.floci.gcp.core.common.GcpException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CloudRunResourceLimitsTest {

    @ParameterizedTest
    @CsvSource({
            "256Mi, 268435456",
            "2Gi, 2147483648",
            "1G, 1000000000",
            "512k, 512000",
            "1.5Gi, 1610612736",
            "1048576, 1048576",
            "2048Mi, 2147483648",
            "1Ti, 1099511627776",
            "0.5k, 500",
            "1.0001Ki, 1025",
    })
    void memoryQuantityParsesToBytes(String quantity, long expectedBytes) {
        assertEquals(expectedBytes, limits("memory", quantity).memoryBytes());
    }

    @ParameterizedTest
    @CsvSource({
            "1, 1000000000",
            "2000m, 2000000000",
            "0.5, 500000000",
            "80m, 80000000",
            "1.5m, 1500000",
            "8, 8000000000",
    })
    void cpuQuantityParsesToNanoCpus(String quantity, long expectedNanoCpus) {
        assertEquals(expectedNanoCpus, limits("cpu", quantity).nanoCpus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "-1", "0", "0Mi", "1Xi", "", "1m", "1.", ".5", "1 Gi", "99999999999Ti"})
    void invalidMemoryIsRejectedWithInvalidArgument(String quantity) {
        GcpException ex = assertThrows(GcpException.class, () -> limits("memory", quantity));

        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
        assertEquals("Invalid value for resources.limits.memory: " + quantity, ex.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "-1", "0", "0m", "1Xi", "", "1Gi", "1k"})
    void invalidCpuIsRejectedWithInvalidArgument(String quantity) {
        GcpException ex = assertThrows(GcpException.class, () -> limits("cpu", quantity));

        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
        assertEquals("Invalid value for resources.limits.cpu: " + quantity, ex.getMessage());
    }

    @Test
    void absentKeysLeaveTheLimitUnset() {
        Container memoryOnly = Container.newBuilder()
                .setResources(ResourceRequirements.newBuilder().putLimits("memory", "512Mi"))
                .build();

        CloudRunResourceLimits limits = CloudRunResourceLimits.of(memoryOnly);

        assertEquals(536870912L, limits.memoryBytes());
        assertNull(limits.nanoCpus());
        assertEquals(new CloudRunResourceLimits(null, null), CloudRunResourceLimits.of(Container.getDefaultInstance()));
    }

    private static CloudRunResourceLimits limits(String key, String quantity) {
        return CloudRunResourceLimits.of(Container.newBuilder()
                .setResources(ResourceRequirements.newBuilder().putLimits(key, quantity))
                .build());
    }
}
