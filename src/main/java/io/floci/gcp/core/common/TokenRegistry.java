package io.floci.gcp.core.common;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class TokenRegistry {

    private final ConcurrentHashMap<String, String> tokensByValue = new ConcurrentHashMap<>();

    public void register(String token, String email) {
        tokensByValue.put(token, email);
    }

    public Optional<String> resolve(String token) {
        if (token == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(tokensByValue.get(token));
    }

    public boolean isRegistered(String token) {
        return token != null && tokensByValue.containsKey(token);
    }

    public void clear() {
        tokensByValue.clear();
    }
}
