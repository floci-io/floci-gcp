package io.floci.gcp.services.iam.authorization;

import io.floci.gcp.core.common.GcpException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Immutable registry. Adding a service requires no service switch in either transport adapter. */
@ApplicationScoped
public class IamAuthorizationRegistry {
    private final List<IamAuthorizationAdapter> adapters;
    private final Map<Class<?>, IamAuthorizationAdapter> rest;
    private final Map<String, IamAuthorizationAdapter> grpc;

    @Inject
    public IamAuthorizationRegistry(Instance<IamAuthorizationAdapter> adapters) {
        this(adapters.stream().toList());
    }

    public IamAuthorizationRegistry(List<IamAuthorizationAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
        Map<Class<?>, IamAuthorizationAdapter> rest = new LinkedHashMap<>();
        Map<String, IamAuthorizationAdapter> grpc = new LinkedHashMap<>();
        for (IamAuthorizationAdapter adapter : adapters) {
            adapter.restControllers().forEach(key -> register(rest, key, adapter));
            adapter.grpcServices().forEach(key -> register(grpc, key, adapter));
        }
        this.rest = Map.copyOf(rest);
        this.grpc = Map.copyOf(grpc);
    }

    private static <K> void register(Map<K, IamAuthorizationAdapter> map, K key, IamAuthorizationAdapter adapter) {
        if (map.putIfAbsent(key, adapter) != null) {
            throw new IllegalStateException("Duplicate IAM adapter for " + key);
        }
    }

    public Optional<IamAuthorizationAdapter> rest(Class<?> controller) {
        return Optional.ofNullable(rest.get(controller));
    }

    public Optional<IamAuthorizationAdapter> grpc(String service) {
        return Optional.ofNullable(grpc.get(service));
    }

    public Optional<IamAuthorizationAdapter> resource(String name) {
        List<IamAuthorizationAdapter> matches = adapters.stream()
                .filter(adapter -> adapter.resource(name).isPresent()).toList();
        if (matches.size() > 1) {
            throw GcpException.failedPrecondition("Ambiguous IAM resource mapping: " + name);
        }
        return matches.stream().findFirst();
    }

    public List<IamAuthorizationAdapter> adapters() {
        return adapters;
    }
}
