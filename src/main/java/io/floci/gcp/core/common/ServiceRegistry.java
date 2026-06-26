package io.floci.gcp.core.common;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class ServiceRegistry {

    private static final Logger LOG = Logger.getLogger(ServiceRegistry.class);

    private final List<ServiceDescriptor> descriptors = new ArrayList<>();
    private final Map<Class<?>, ServiceDescriptor> byResourceClass = new LinkedHashMap<>();

    public void register(ServiceDescriptor descriptor) {
        descriptors.add(descriptor);
        for (Class<?> rc : descriptor.resourceClasses()) {
            byResourceClass.put(rc, descriptor);
        }
    }

    public Optional<ServiceDescriptor> byResourceClass(Class<?> clazz) {
        return Optional.ofNullable(byResourceClass.get(clazz));
    }

    /**
     * Returns enabled services that declare a routing {@code pathPrefix} (and usually a
     * {@code hostToken}). Used by the single-port {@code ServiceRoutingFilter} to map
     * canonical/host-based REST requests onto a prefixed controller.
     */
    public List<ServiceDescriptor> getRoutableServices() {
        return descriptors.stream()
                .filter(ServiceDescriptor::enabled)
                .filter(d -> d.pathPrefix() != null && !d.pathPrefix().isBlank())
                .toList();
    }

    public boolean isEnabled(String name) {
        return descriptors.stream()
                .filter(d -> d.name().equals(name))
                .map(ServiceDescriptor::enabled)
                .findFirst()
                .orElse(true);
    }

    public List<String> getEnabledServices() {
        return descriptors.stream()
                .filter(ServiceDescriptor::enabled)
                .map(ServiceDescriptor::name)
                .toList();
    }

    public Map<String, String> getServices() {
        Map<String, String> result = new LinkedHashMap<>();
        for (ServiceDescriptor d : descriptors) {
            result.put(d.name(), d.enabled() ? "running" : "available");
        }
        return result;
    }

    public void logEnabledServices() {
        LOG.infov("Enabled services: {0}", getEnabledServices());
    }
}
