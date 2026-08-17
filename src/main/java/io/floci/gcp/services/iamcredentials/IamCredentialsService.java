package io.floci.gcp.services.iamcredentials;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.credentials.StoredCredentialToken;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class IamCredentialsService {

    static final String DEVSTORAGE_READ_WRITE_SCOPE = "https://www.googleapis.com/auth/devstorage.read_write";
    static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    static final String TOKEN_PREFIX = CredentialTokenService.IMPERSONATED_TOKEN_PREFIX;
    static final long DEFAULT_LIFETIME_SECONDS = 3600;
    static final long MAX_LIFETIME_SECONDS = 3600;
    private static final Set<String> SUPPORTED_SCOPES = Set.of(DEVSTORAGE_READ_WRITE_SCOPE, CLOUD_PLATFORM_SCOPE);

    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final CredentialTokenService tokenService;
    private final Clock clock;

    @Inject
    public IamCredentialsService(ServiceRegistry serviceRegistry, EmulatorConfig config,
            CredentialTokenService tokenService) {
        this(serviceRegistry, config, tokenService, Clock.systemUTC());
    }

    IamCredentialsService(CredentialTokenService tokenService, Clock clock) {
        this(null, null, tokenService, clock);
    }

    private IamCredentialsService(ServiceRegistry serviceRegistry, EmulatorConfig config,
            CredentialTokenService tokenService, Clock clock) {
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.tokenService = tokenService;
        this.clock = clock;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("iamcredentials")
                .enabled(config.services().iamcredentials().enabled())
                .storageKey("iamcredentials")
                .protocol(ServiceProtocol.REST)
                .resourceClasses(IamCredentialsController.class)
                .build());
    }

    public GeneratedAccessToken generateAccessToken(String serviceAccount, List<?> scopes, Object lifetime) {
        if (serviceAccount == null || serviceAccount.isBlank()) {
            throw GcpException.invalidArgument("service account is required");
        }
        validateScopes(scopes);

        long lifetimeSeconds = parseLifetimeSeconds(lifetime);
        Instant expireTime = clock.instant().plusSeconds(lifetimeSeconds);
        StoredCredentialToken token = tokenService.mintImpersonatedToken(serviceAccount, expireTime);
        return new GeneratedAccessToken(token.getTokenValue(), expireTime);
    }

    private static void validateScopes(List<?> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            throw GcpException.invalidArgument("scope is required");
        }
        boolean supported = scopes.stream()
                .anyMatch(SUPPORTED_SCOPES::contains);
        if (!supported) {
            throw GcpException.invalidArgument("unsupported scope");
        }
    }

    private static long parseLifetimeSeconds(Object lifetime) {
        if (lifetime == null) {
            return DEFAULT_LIFETIME_SECONDS;
        }
        if (!(lifetime instanceof String value)) {
            throw GcpException.invalidArgument("lifetime must be a duration string");
        }
        if (!value.endsWith("s") || value.length() == 1) {
            throw GcpException.invalidArgument("lifetime must be a duration string ending in seconds");
        }

        long requestedSeconds;
        try {
            requestedSeconds = Long.parseLong(value.substring(0, value.length() - 1));
        } catch (NumberFormatException e) {
            throw GcpException.invalidArgument("lifetime must be a duration string ending in seconds");
        }

        if (requestedSeconds <= 0) {
            throw GcpException.invalidArgument("lifetime must be positive");
        }
        return Math.min(requestedSeconds, MAX_LIFETIME_SECONDS);
    }

    public record GeneratedAccessToken(String accessToken, Instant expireTime) {}
}
