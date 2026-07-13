package io.floci.gcp.core.common;

import io.floci.gcp.config.EmulatorConfig;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenValidationGrpcInterceptorTest {

    @Mock
    EmulatorConfig config;
    @Mock
    EmulatorConfig.AuthConfig authConfig;
    @Mock
    ServerCall<String, String> call;
    @Mock
    ServerCallHandler<String, String> next;

    private TokenRegistry tokenRegistry;
    private TokenValidationGrpcInterceptor interceptor;

    @BeforeEach
    void setUp() {
        when(config.auth()).thenReturn(authConfig);
        when(authConfig.validateTokens()).thenReturn(true);
        when(authConfig.rootAccessToken()).thenReturn(Optional.empty());
        when(authConfig.rootServiceAccount()).thenReturn(Optional.empty());
        tokenRegistry = new TokenRegistry();
        interceptor = new TokenValidationGrpcInterceptor(config, tokenRegistry);
    }

    @Test
    void missingBearerClosesCallAsUnauthenticated() {
        Metadata metadata = new Metadata();

        interceptor.interceptCall(call, metadata, next);

        ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
        verify(call).close(statusCaptor.capture(), any(Metadata.class));
        assertEquals(Status.Code.UNAUTHENTICATED, statusCaptor.getValue().getCode());
        verify(next, never()).startCall(any(), any());
    }

    @Test
    void validateReturnsEmptyWithoutBearer() {
        assertTrue(interceptor.validate(new Metadata()).isEmpty());
    }

    @Test
    void validateAcceptsRegisteredBearerToken() {
        tokenRegistry.register("player-token", "player@example.com");
        Metadata metadata = new Metadata();
        metadata.put(TokenValidationGrpcInterceptor.AUTHORIZATION_KEY, "Bearer player-token");

        Optional<TokenValidationGrpcInterceptor.ValidatedPrincipal> result =
                interceptor.validate(metadata);

        assertTrue(result.isPresent());
        assertEquals("player@example.com", result.get().email());
        assertEquals(false, result.get().operatorRoot());
    }

    @Test
    void validateTokensDisabledPassesThrough() {
        when(authConfig.validateTokens()).thenReturn(false);
        Metadata metadata = new Metadata();

        interceptor.interceptCall(call, metadata, next);

        verify(next).startCall(call, metadata);
        verify(call, never()).close(any(), any());
    }
}
