package io.floci.gcp.core.common;

import java.util.Set;

public record ServiceDescriptor(
        String name,
        boolean enabled,
        String storageKey,
        String storageMode,
        long storageFlushIntervalMs,
        ServiceProtocol protocol,
        Set<Class<?>> resourceClasses,
        String hostToken,
        String pathPrefix
) {

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static class Builder {
        private final String name;
        private boolean enabled = true;
        private String storageKey;
        private String storageMode;
        private long storageFlushIntervalMs = 5000;
        private ServiceProtocol protocol = ServiceProtocol.REST;
        private Set<Class<?>> resourceClasses = Set.of();
        private String hostToken;
        private String pathPrefix;

        private Builder(String name) {
            this.name = name;
        }

        public Builder enabled(boolean enabled) { this.enabled = enabled; return this; }
        public Builder storageKey(String storageKey) { this.storageKey = storageKey; return this; }
        public Builder storageMode(String storageMode) { this.storageMode = storageMode; return this; }
        public Builder flushInterval(long ms) { this.storageFlushIntervalMs = ms; return this; }
        public Builder protocol(ServiceProtocol protocol) { this.protocol = protocol; return this; }
        public Builder resourceClasses(Class<?>... classes) { this.resourceClasses = Set.of(classes); return this; }
        public Builder hostToken(String hostToken) { this.hostToken = hostToken; return this; }
        public Builder pathPrefix(String pathPrefix) { this.pathPrefix = pathPrefix; return this; }

        public ServiceDescriptor build() {
            return new ServiceDescriptor(name, enabled, storageKey, storageMode,
                    storageFlushIntervalMs, protocol, resourceClasses, hostToken, pathPrefix);
        }
    }
}
