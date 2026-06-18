package io.floci.gcp.core.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ServiceRegistryTest {

    @Test
    void getEnabledServices_listsRegisteredEnabledDescriptorsInRegistrationOrder() {
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(descriptor("gcs", true));
        registry.register(descriptor("pubsub", false));
        registry.register(descriptor("kms", true));

        assertEquals(List.of("gcs", "kms"), registry.getEnabledServices());
    }

    @Test
    void getEnabledServices_excludesDisabledService() {
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(descriptor("gcs", true));
        registry.register(descriptor("kms", false));

        assertFalse(registry.getEnabledServices().contains("kms"));
        assertEquals(List.of("gcs"), registry.getEnabledServices());
    }

    @Test
    void getEnabledServices_isEmptyBeforeAnyRegistration() {
        assertEquals(List.of(), new ServiceRegistry().getEnabledServices());
    }

    private static ServiceDescriptor descriptor(String name, boolean enabled) {
        return ServiceDescriptor.builder(name).enabled(enabled).build();
    }
}