package io.floci.gcp.services.iamcredentials;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ProtoJsonBytes;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.credentials.StoredCredentialToken;
import io.floci.gcp.services.iam.IamService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class IamCredentialsService {

    static final String DEVSTORAGE_READ_WRITE_SCOPE = "https://www.googleapis.com/auth/devstorage.read_write";
    static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    static final String TOKEN_PREFIX = CredentialTokenService.IMPERSONATED_TOKEN_PREFIX;
    static final long DEFAULT_LIFETIME_SECONDS = 3600;
    static final long MAX_LIFETIME_SECONDS = 3600;
    /** signJwt rejects an {@code exp} claim further out than this. */
    static final long MAX_JWT_LIFETIME_SECONDS = 12 * 3600;
    /** iamcredentials names a service account only through the {@code -} project wildcard. */
    private static final String WILDCARD_PROJECT = "-";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SUPPORTED_SCOPES = Set.of(DEVSTORAGE_READ_WRITE_SCOPE, CLOUD_PLATFORM_SCOPE);

    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final CredentialTokenService tokenService;
    private final IamService iamService;
    private final Clock clock;

    @Inject
    public IamCredentialsService(ServiceRegistry serviceRegistry, EmulatorConfig config,
            CredentialTokenService tokenService, IamService iamService) {
        this(serviceRegistry, config, tokenService, iamService, Clock.systemUTC());
    }

    IamCredentialsService(CredentialTokenService tokenService, Clock clock) {
        this(null, null, tokenService, null, clock);
    }

    IamCredentialsService(CredentialTokenService tokenService, IamService iamService, Clock clock) {
        this(null, null, tokenService, iamService, clock);
    }

    private IamCredentialsService(ServiceRegistry serviceRegistry, EmulatorConfig config,
            CredentialTokenService tokenService, IamService iamService, Clock clock) {
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.tokenService = tokenService;
        this.iamService = iamService;
        this.clock = clock;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("iamcredentials")
                .enabled(config.services().iamcredentials().enabled())
                .storageKey("iamcredentials")
                .protocol(ServiceProtocol.REST)
                .resourceClasses(IamCredentialsController.class)
                .api("iamcredentials.googleapis.com", "IAM Service Account Credentials API")
                .build());
    }

    public GeneratedAccessToken generateAccessToken(String serviceAccount, List<?> scopes, Object lifetime) {
        if (serviceAccount == null || serviceAccount.isBlank()) {
            throw GcpException.invalidArgument("service account is required");
        }
        validateScopes(scopes);

        long lifetimeSeconds = parseLifetimeSeconds(lifetime);
        Instant expireTime = clock.instant().plusSeconds(lifetimeSeconds);
        String principal = iamService != null && serviceAccount.chars().allMatch(Character::isDigit)
                ? iamService.getServiceAccount("-", serviceAccount).getEmail() : serviceAccount;
        StoredCredentialToken token = tokenService.mintImpersonatedToken(principal, expireTime);
        return new GeneratedAccessToken(token.getTokenValue(), expireTime);
    }

    /** Signs the base64 {@code payload} with the service account's Google-managed key. */
    public Map<String, String> signBlob(String serviceAccount, String payload) {
        if (payload == null || payload.isEmpty()) {
            throw GcpException.invalidArgument("payload is required");
        }
        IamService.SignedBytes signed = iamService.sign(WILDCARD_PROJECT, serviceAccount,
                ProtoJsonBytes.decode(payload, "payload"));
        Map<String, String> response = new LinkedHashMap<>();
        response.put("keyId", signed.keyId());
        response.put("signedBlob", Base64.getEncoder().encodeToString(signed.signature()));
        return response;
    }

    /**
     * Signs the JSON claims in {@code payload} as an RS256 JWT whose header names the signing key in
     * {@code kid}. An {@code exp} claim must be an integer timestamp that is not in the past and no
     * more than 12 hours ahead.
     */
    public Map<String, String> signJwt(String serviceAccount, String payload) {
        if (payload == null || payload.isEmpty()) {
            throw GcpException.invalidArgument("payload is required");
        }
        JsonNode claims;
        try {
            claims = MAPPER.readTree(payload);
        } catch (Exception e) {
            throw GcpException.invalidArgument("payload must be a JSON object of JWT claims");
        }
        if (claims == null || !claims.isObject()) {
            throw GcpException.invalidArgument("payload must be a JSON object of JWT claims");
        }
        validateExpiry(claims.get("exp"));

        // The header is built inside sign so its kid names the key that signs it.
        String[] signingInput = new String[1];
        IamService.SignedBytes signed = iamService.sign(WILDCARD_PROJECT, serviceAccount, keyId -> {
            signingInput[0] = jwtSigningInput(keyId, payload);
            return signingInput[0].getBytes(StandardCharsets.US_ASCII);
        });

        Map<String, String> response = new LinkedHashMap<>();
        response.put("keyId", signed.keyId());
        response.put("signedJwt", signingInput[0] + "." + base64Url(signed.signature()));
        return response;
    }

    private static String jwtSigningInput(String keyId, String payload) {
        Map<String, String> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", keyId);
        try {
            return base64Url(MAPPER.writeValueAsBytes(header)) + "." + base64Url(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw GcpException.internal("JWT header serialization failed: " + e.getMessage());
        }
    }

    private void validateExpiry(JsonNode exp) {
        if (exp == null) {
            return;
        }
        if (!exp.canConvertToLong() || !exp.isIntegralNumber()) {
            throw GcpException.invalidArgument("exp must be an integer timestamp");
        }
        long now = clock.instant().getEpochSecond();
        if (exp.asLong() < now) {
            throw GcpException.invalidArgument("exp must not be in the past");
        }
        if (exp.asLong() > now + MAX_JWT_LIFETIME_SECONDS) {
            throw GcpException.invalidArgument("exp must be no more than 12 hours in the future");
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
