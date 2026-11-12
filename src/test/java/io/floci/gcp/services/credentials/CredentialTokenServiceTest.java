package io.floci.gcp.services.credentials;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class CredentialTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");
    private final InMemoryStorage<String, StoredCredentialToken> store = new InMemoryStorage<>();
    private final CredentialTokenService service =
            new CredentialTokenService(store, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void revokingAServiceAccountsTokensRefusesEveryFormOfItsPrincipalAndNoOtherAccounts() {
        String email = "app@test-project.iam.gserviceaccount.com";
        List<StoredCredentialToken> revoked = List.of(
                service.mintImpersonatedToken(email, NOW.plusSeconds(600)),
                service.mintImpersonatedToken("projects/-/serviceAccounts/" + email, NOW.plusSeconds(600)),
                service.mintImpersonatedToken("serviceAccount:" + email, NOW.plusSeconds(600)));
        StoredCredentialToken downscoped = service.mintDownscopedToken(revoked.getFirst().getTokenValue(), rules())
                .token();
        StoredCredentialToken kept = service.mintImpersonatedToken("other@test-project.iam.gserviceaccount.com",
                NOW.plusSeconds(600));

        service.revokeServiceAccountTokens(email);

        for (StoredCredentialToken token : List.of(revoked.get(0), revoked.get(1), revoked.get(2), downscoped)) {
            CredentialAuthenticationException error = assertThrows(CredentialAuthenticationException.class,
                    () -> service.lookupBearerToken(token.getTokenValue()));
            assertEquals(CredentialAuthenticationException.Failure.ACCOUNT_STATE_INVALID, error.failure());
            assertEquals("Request had invalid authentication credentials. Expected OAuth 2 access token, login "
                    + "cookie or other valid authentication credential. "
                    + "See https://developers.google.com/identity/sign-in/web/devconsole-project.", error.getMessage());
        }
        assertTrue(service.lookupBearerToken(kept.getTokenValue()).isPresent());
    }

    // Each test pauses the minting thread just before it stores its token, starts the revocation of the
    // account, and lets the mint finish only once the revocation has either completed or is waiting for it.
    // Without a lock shared by both, the revocation scans before the token is stored and misses it.

    @Test
    void aDownscopedTokenMintedWhileItsAccountIsRevokedIsRevokedToo() throws Exception {
        PausingStore paused = new PausingStore(CredentialTokenService.DOWNSCOPED_TOKEN_PREFIX);
        CredentialTokenService tokens = new CredentialTokenService(paused, Clock.fixed(NOW, ZoneOffset.UTC));
        String email = "app@test-project.iam.gserviceaccount.com";
        String source = tokens.mintImpersonatedToken(email, NOW.plusSeconds(600)).getTokenValue();

        CompletableFuture<StoredCredentialToken> child =
                CompletableFuture.supplyAsync(() -> tokens.mintDownscopedToken(source, rules()).token());
        StoredCredentialToken minted = paused.raceWith(child, () -> tokens.revokeServiceAccountTokens(email));

        assertAccountStateInvalid(tokens, minted.getTokenValue());
    }

    @Test
    void aDownscopedTokenOfAnAlreadyRevokedAccountIsRefused() {
        String email = "app@test-project.iam.gserviceaccount.com";
        String source = service.mintImpersonatedToken(email, NOW.plusSeconds(600)).getTokenValue();
        service.revokeServiceAccountTokens(email);
        long stored = store.scan(key -> true).size();

        CredentialAuthenticationException error = assertThrows(CredentialAuthenticationException.class,
                () -> service.mintDownscopedToken(source, rules()));

        assertEquals(CredentialAuthenticationException.Failure.ACCOUNT_STATE_INVALID, error.failure());
        assertEquals(stored, store.scan(key -> true).size());
    }

    @Test
    void anImpersonatedTokenMintedWhileItsAccountIsDeletedIsRevoked() throws Exception {
        PausingStore paused = new PausingStore(CredentialTokenService.IMPERSONATED_TOKEN_PREFIX);
        CredentialTokenService tokens = new CredentialTokenService(paused, Clock.fixed(NOW, ZoneOffset.UTC));
        String email = "app@test-project.iam.gserviceaccount.com";
        AtomicBoolean accountExists = new AtomicBoolean(true);

        CompletableFuture<StoredCredentialToken> token = CompletableFuture.supplyAsync(() ->
                tokens.mintImpersonatedToken(email, NOW.plusSeconds(600), () -> requireAccount(accountExists)));
        StoredCredentialToken minted = paused.raceWith(token, () -> {
            accountExists.set(false);
            tokens.revokeServiceAccountTokens(email);
        });

        assertAccountStateInvalid(tokens, minted.getTokenValue());
    }

    @Test
    void anImpersonatedTokenOfADeletedAccountIsNotMinted() {
        AtomicBoolean accountExists = new AtomicBoolean(false);

        assertThrows(GcpException.class, () -> service.mintImpersonatedToken(
                "app@test-project.iam.gserviceaccount.com", NOW.plusSeconds(600),
                () -> requireAccount(accountExists)));
        assertTrue(store.scan(key -> true).isEmpty());
    }

    private static void requireAccount(AtomicBoolean accountExists) {
        if (!accountExists.get()) {
            throw GcpException.notFound("Service account not found");
        }
    }

    private static void assertAccountStateInvalid(CredentialTokenService tokens, String token) {
        CredentialAuthenticationException error = assertThrows(CredentialAuthenticationException.class,
                () -> tokens.lookupBearerToken(token));
        assertEquals(CredentialAuthenticationException.Failure.ACCOUNT_STATE_INVALID, error.failure());
    }

    /** Holds the first token stored with {@code prefix} until {@link #raceWith} lets it through. */
    private static final class PausingStore extends InMemoryStorage<String, StoredCredentialToken> {
        private final String prefix;
        private final AtomicBoolean armed = new AtomicBoolean(true);
        private final CountDownLatch paused = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        PausingStore(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public void put(String key, StoredCredentialToken value) {
            if (key.startsWith(prefix) && armed.compareAndSet(true, false)) {
                paused.countDown();
                try {
                    if (!released.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("The paused store was never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            super.put(key, value);
        }

        /**
         * Waits for {@code mint} to pause before storing, runs {@code competitor} on another thread until it
         * completes or blocks on a monitor, then lets the mint store its token and returns it.
         */
        StoredCredentialToken raceWith(CompletableFuture<StoredCredentialToken> mint, Runnable competitor)
                throws Exception {
            assertTrue(paused.await(10, TimeUnit.SECONDS), "the mint never reached the store");
            Thread thread = new Thread(competitor);
            thread.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (thread.isAlive() && thread.getState() != Thread.State.BLOCKED) {
                if (System.nanoTime() > deadline) {
                    fail("the competing operation neither completed nor blocked");
                }
                Thread.onSpinWait();
            }
            released.countDown();
            thread.join(TimeUnit.SECONDS.toMillis(10));
            assertTrue(!thread.isAlive(), "the competing operation did not complete");
            return mint.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void storesAndRetrievesDownscopedToken() {
        CredentialTokenService.MintedDownscopedToken minted =
                service.mintDownscopedToken("source-token", rules());
        StoredCredentialToken token = minted.token();

        Optional<StoredCredentialToken> resolved = service.lookupBearerToken(token.getTokenValue());

        assertEquals(CredentialTokenService.DEFAULT_LIFETIME_SECONDS, minted.expiresInSeconds());
        assertTrue(resolved.isPresent());
        assertEquals(StoredCredentialToken.TokenKind.DOWNSCOPED, resolved.get().getTokenKind());
        assertEquals(NOW.plusSeconds(CredentialTokenService.DEFAULT_LIFETIME_SECONDS),
                resolved.get().getExpireTime());
        assertNull(resolved.get().getSourceToken());
        assertNull(resolved.get().getPrincipal());
        assertEquals("bucket", resolved.get().getGcsRules().getFirst().getBucket());
    }

    @Test
    void inheritsStoredSourceExpiration() {
        StoredCredentialToken source = service.mintImpersonatedToken(
                "test@test-project.iam.gserviceaccount.com", NOW.plusSeconds(1200));

        CredentialTokenService.MintedDownscopedToken minted =
                service.mintDownscopedToken(source.getTokenValue(), rules());

        assertEquals(1200, minted.expiresInSeconds());
        assertEquals(source.getExpireTime(), minted.token().getExpireTime());
        assertNull(minted.token().getSourceToken());
        assertEquals(source.getPrincipal(), minted.token().getPrincipal());
    }

    @Test
    void inheritsStoredSourceExpirationBeyondDefaultLifetime() {
        StoredCredentialToken source = service.mintImpersonatedToken(
                "test@test-project.iam.gserviceaccount.com", NOW.plusSeconds(7200));

        CredentialTokenService.MintedDownscopedToken minted =
                service.mintDownscopedToken(source.getTokenValue(), rules());

        assertEquals(7200, minted.expiresInSeconds());
        assertEquals(source.getExpireTime(), minted.token().getExpireTime());
    }

    @Test
    void rejectsDownscopedTokenAsSource() {
        String sourceToken = service.mintDownscopedToken("external-token", rules()).token().getTokenValue();

        GcpException ex = assertThrows(GcpException.class,
                () -> service.mintDownscopedToken(sourceToken, rules()));

        assertEquals("INVALID_ARGUMENT", ex.getGcpStatus());
    }

    @Test
    void storesAndRetrievesImpersonatedTokenWithoutAccessRules() {
        StoredCredentialToken token = service.mintImpersonatedToken(
                "test@test-project.iam.gserviceaccount.com", NOW.plusSeconds(1200));

        Optional<StoredCredentialToken> resolved = service.lookupBearerToken(token.getTokenValue());

        assertTrue(resolved.isPresent());
        assertEquals(StoredCredentialToken.TokenKind.IMPERSONATED, resolved.get().getTokenKind());
        assertEquals(NOW.plusSeconds(1200), resolved.get().getExpireTime());
        assertEquals("test@test-project.iam.gserviceaccount.com", resolved.get().getPrincipal());
        assertTrue(resolved.get().getGcsRules().isEmpty());
    }

    @Test
    void expiredTokenIsRejectedConsistentlyOnEveryLookup() {
        StoredCredentialToken expired = new StoredCredentialToken(
                CredentialTokenService.DOWNSCOPED_TOKEN_PREFIX + "expired",
                StoredCredentialToken.TokenKind.DOWNSCOPED,
                NOW.minusSeconds(1),
                "source-token",
                null,
                rules());
        store.put(expired.getTokenValue(), expired);

        GcpException ex = assertThrows(GcpException.class,
                () -> service.lookupBearerToken(expired.getTokenValue()));

        assertEquals("UNAUTHENTICATED", ex.getGcpStatus());
        assertEquals(CredentialTokenService.EXPIRED_TOKEN_MESSAGE, ex.getMessage());
        CredentialAuthenticationException retry = assertThrows(CredentialAuthenticationException.class,
                () -> service.lookupBearerToken(expired.getTokenValue()));
        assertEquals(CredentialAuthenticationException.Failure.EXPIRED, retry.failure());
        assertEquals(ex.getMessage(), retry.getMessage());
    }

    @Test
    void unknownFlociTokenIsRejected() {
        GcpException ex = assertThrows(GcpException.class,
                () -> service.lookupBearerToken(CredentialTokenService.DOWNSCOPED_TOKEN_PREFIX + "missing"));

        assertEquals("UNAUTHENTICATED", ex.getGcpStatus());
    }

    @Test
    void unmanagedTokenReturnsEmptyLookupForLaterBypassContract() {
        assertTrue(service.lookupBearerToken("external-token").isEmpty());
        assertTrue(service.lookupBearerToken(
                CredentialTokenService.FLOCI_TOKEN_PREFIX + "oauth-token").isEmpty());
        assertTrue(service.lookupBearerToken(null).isEmpty());
    }

    private static List<CredentialAccessBoundaryRule> rules() {
        return List.of(new CredentialAccessBoundaryRule(
                "bucket",
                "data/",
                List.of(CredentialAccessBoundaryParser.LEGACY_OBJECT_READER)));
    }
}
