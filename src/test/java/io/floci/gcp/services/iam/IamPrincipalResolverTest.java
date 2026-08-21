package io.floci.gcp.services.iam;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.credentials.CredentialAccessBoundaryRule;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.credentials.StoredCredentialToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IamPrincipalResolverTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private CredentialTokenService tokenService;
    private IamPrincipalResolver resolver;

    @BeforeEach
    void setUp() {
        tokenService = new CredentialTokenService(new InMemoryStorage<>(), Clock.fixed(NOW, ZoneOffset.UTC));
        resolver = new IamPrincipalResolver(tokenService);
    }

    @Test
    void resolvesImpersonatedServiceAccountResourceNameToCanonicalMember() {
        StoredCredentialToken token = tokenService.mintImpersonatedToken(
                "projects/-/serviceAccounts/reader@example.test", NOW.plusSeconds(60));

        IamPrincipalResolver.Resolution resolution = resolver.resolve("Bearer " + token.getTokenValue());

        assertEquals("serviceAccount:reader@example.test", resolution.principal().member());
        assertFalse(resolution.downscoped());
    }

    @Test
    void treatsMissingAndOrdinaryExternalTokensAsAnonymous() {
        assertFalse(resolver.resolve(null).principal().isAuthenticated());
        assertFalse(resolver.resolve("Bearer external-token").principal().isAuthenticated());
    }

    @Test
    void marksDownscopedTokenForCabHandlingBeforeIam() {
        StoredCredentialToken token = tokenService.mintDownscopedToken("source-token", List.of(
                new CredentialAccessBoundaryRule("bucket", "", List.of(
                        "inRole:roles/storage.objectViewer")))).token();

        IamPrincipalResolver.Resolution resolution = resolver.resolve("Bearer " + token.getTokenValue());

        assertTrue(resolution.downscoped());
        assertFalse(resolution.principal().isAuthenticated());
    }

    @Test
    void preservesInvalidKnownFlociTokenAuthenticationError() {
        assertThrows(GcpException.class,
                () -> resolver.resolve("Bearer " + CredentialTokenService.IMPERSONATED_TOKEN_PREFIX + "missing"));
    }
}
