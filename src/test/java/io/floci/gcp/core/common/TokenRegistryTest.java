package io.floci.gcp.core.common;

import io.floci.gcp.config.EmulatorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenRegistryTest {

    private static final String ROOT_TOKEN = "root-token-test";
    private static final String ROOT_EMAIL = "operator@test-project.iam.gserviceaccount.com";

    private TokenRegistry registry;

    @Mock
    EmulatorConfig config;
    @Mock
    EmulatorConfig.AuthConfig authConfig;

    @BeforeEach
    void setUp() {
        registry = new TokenRegistry();
    }

    @Test
    void registerThenResolveReturnsEmail() {
        registry.register("tok-1", "sa@example.com");

        assertEquals(Optional.of("sa@example.com"), registry.resolve("tok-1"));
        assertTrue(registry.isRegistered("tok-1"));
    }

    @Test
    void resolveUnknownTokenIsEmpty() {
        assertEquals(Optional.empty(), registry.resolve("missing"));
        assertFalse(registry.isRegistered("missing"));
    }

    @Test
    void resolveNullTokenIsEmpty() {
        assertEquals(Optional.empty(), registry.resolve(null));
        assertFalse(registry.isRegistered(null));
    }

    @Test
    void registerOverwritesExistingEmail() {
        registry.register("tok-1", "first@example.com");
        registry.register("tok-1", "second@example.com");

        assertEquals(Optional.of("second@example.com"), registry.resolve("tok-1"));
    }

    @Test
    void clearRemovesAllTokens() {
        registry.register("tok-1", "sa@example.com");
        registry.register("tok-2", "other@example.com");

        registry.clear();

        assertFalse(registry.isRegistered("tok-1"));
        assertFalse(registry.isRegistered("tok-2"));
        assertEquals(Optional.empty(), registry.resolve("tok-1"));
    }

    @Test
    void rootConfigDoesNotPutRootTokenInRegistryButOperatorRootAuthMatches() {
        when(config.auth()).thenReturn(authConfig);
        when(authConfig.rootAccessToken()).thenReturn(Optional.of(ROOT_TOKEN));
        when(authConfig.rootServiceAccount()).thenReturn(Optional.of(ROOT_EMAIL));

        assertEquals(Optional.empty(), registry.resolve(ROOT_TOKEN));
        assertFalse(registry.isRegistered(ROOT_TOKEN));
        assertTrue(OperatorRootAuth.matches(config, ROOT_TOKEN));
        assertEquals(ROOT_EMAIL, OperatorRootAuth.rootEmail(config));
    }
}
