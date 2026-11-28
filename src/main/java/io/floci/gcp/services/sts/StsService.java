package io.floci.gcp.services.sts;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.services.credentials.CredentialAccessBoundaryParser;
import io.floci.gcp.services.credentials.CredentialAccessBoundaryRule;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.credentials.StoredCredentialToken;
import io.floci.gcp.services.iam.IamWorkloadIdentityPoolService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class StsService {

    static final String TOKEN_EXCHANGE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange";
    static final String ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";
    static final String JWT_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:jwt";
    static final String ID_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:id_token";
    static final String AWS4_REQUEST_TOKEN_TYPE = "urn:ietf:params:aws:token-type:aws4_request";
    static final String BEARER_TOKEN_TYPE = "Bearer";

    private static final Map<String, String> DEFAULT_AWS_ATTRIBUTE_MAPPING = Map.of(
            "google.subject", "assertion.arn",
            "attribute.aws_role",
            "assertion.arn.contains('assumed-role') "
                    + "? assertion.arn.extract('{account_arn}assumed-role/') "
                    + "+ 'assumed-role/' "
                    + "+ assertion.arn.extract('assumed-role/{role_name}/') "
                    + ": assertion.arn");

    private static final Pattern PROVIDER_AUDIENCE = Pattern.compile(
            "^(?:https:)?//iam\\.googleapis\\.com/projects/([^/]+)/locations/global/"
                    + "workloadIdentityPools/([^/]+)/providers/([^/]+)$");

    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final CredentialAccessBoundaryParser cabParser;
    private final CredentialTokenService tokenService;
    private final IamWorkloadIdentityPoolService workloadIdentityPools;
    private final OidcSubjectTokenVerifier oidcVerifier;
    private final AwsSubjectTokenVerifier awsVerifier;
    private final WorkloadIdentityCelEvaluator celEvaluator;

    @Inject
    public StsService(ServiceRegistry serviceRegistry, EmulatorConfig config,
            CredentialAccessBoundaryParser cabParser, CredentialTokenService tokenService,
            IamWorkloadIdentityPoolService workloadIdentityPools,
            OidcSubjectTokenVerifier oidcVerifier, AwsSubjectTokenVerifier awsVerifier,
            WorkloadIdentityCelEvaluator celEvaluator) {
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.cabParser = cabParser;
        this.tokenService = tokenService;
        this.workloadIdentityPools = workloadIdentityPools;
        this.oidcVerifier = oidcVerifier;
        this.awsVerifier = awsVerifier;
        this.celEvaluator = celEvaluator;
    }

    StsService(CredentialAccessBoundaryParser cabParser, CredentialTokenService tokenService) {
        this(null, null, cabParser, tokenService, null, null, null, null);
    }

    StsService(CredentialAccessBoundaryParser cabParser, CredentialTokenService tokenService,
            IamWorkloadIdentityPoolService workloadIdentityPools,
            OidcSubjectTokenVerifier oidcVerifier, AwsSubjectTokenVerifier awsVerifier,
            WorkloadIdentityCelEvaluator celEvaluator) {
        this(null, null, cabParser, tokenService, workloadIdentityPools,
                oidcVerifier, awsVerifier, celEvaluator);
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("sts")
                .enabled(config.services().sts().enabled())
                .storageKey("credential-tokens")
                .protocol(ServiceProtocol.REST)
                .resourceClasses(StsController.class)
                .api("sts.googleapis.com", "Security Token Service API")
                .build());
    }

    public Map<String, Object> exchangeToken(String grantType, String subjectTokenType,
            String subjectToken, String requestedTokenType, String options) {
        return exchangeToken(grantType, subjectTokenType, subjectToken, requestedTokenType, options, null, null);
    }

    public Map<String, Object> exchangeToken(String grantType, String subjectTokenType,
            String subjectToken, String requestedTokenType, String options,
            String audience, String scope) {
        requireEquals("grant_type", grantType, TOKEN_EXCHANGE_GRANT_TYPE);
        requireEquals("requested_token_type", requestedTokenType, ACCESS_TOKEN_TYPE);
        if (subjectToken == null || subjectToken.isBlank()) {
            throw invalidRequest("subject_token is required");
        }
        if (subjectTokenType == null || subjectTokenType.isBlank()) {
            throw invalidRequest("subject_token_type is required");
        }

        return switch (subjectTokenType) {
            case ACCESS_TOKEN_TYPE -> exchangeDownscopedToken(subjectToken, options);
            case JWT_TOKEN_TYPE, ID_TOKEN_TYPE, AWS4_REQUEST_TOKEN_TYPE -> exchangeExternalToken(
                    subjectTokenType, subjectToken, audience, scope, options);
            default -> throw invalidRequest("Unsupported subject_token_type: " + subjectTokenType);
        };
    }

    private Map<String, Object> exchangeDownscopedToken(String subjectToken, String options) {
        if (options == null || options.isBlank()) {
            throw invalidRequest("options is required");
        }

        CredentialTokenService.MintedDownscopedToken mintedToken;
        try {
            List<CredentialAccessBoundaryRule> rules = cabParser.parse(options);
            mintedToken = tokenService.mintDownscopedToken(subjectToken, rules);
        } catch (GcpException e) {
            throw invalidGrant(e.getMessage());
        }

        StoredCredentialToken token = mintedToken.token();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("access_token", token.getTokenValue());
        response.put("issued_token_type", ACCESS_TOKEN_TYPE);
        response.put("token_type", BEARER_TOKEN_TYPE);
        response.put("expires_in", mintedToken.expiresInSeconds());
        return response;
    }

    private Map<String, Object> exchangeExternalToken(String subjectTokenType, String subjectToken,
            String audience, String scope, String options) {
        if (audience == null || audience.isBlank()) {
            throw invalidRequest("audience is required");
        }
        Matcher audienceMatcher = PROVIDER_AUDIENCE.matcher(audience.trim());
        if (!audienceMatcher.matches()) {
            throw invalidTarget("Invalid workload identity pool provider audience.");
        }
        if (workloadIdentityPools == null || oidcVerifier == null || awsVerifier == null || celEvaluator == null) {
            throw invalidGrant("Workload identity federation is not available.");
        }

        String project = audienceMatcher.group(1);
        String poolId = audienceMatcher.group(2);
        String providerId = audienceMatcher.group(3);
        IamWorkloadIdentityPoolService.ActiveProvider active = workloadIdentityPools
                .findActiveProvider(project, poolId, providerId)
                .orElseThrow(() -> invalidTarget(
                        "The target Workload Identity Pool or Provider was not found or is disabled."));

        Map<String, Object> assertion;
        Map<String, String> attributeMapping = WorkloadIdentityCelEvaluator.readAttributeMapping(active.provider());
        if (AWS4_REQUEST_TOKEN_TYPE.equals(subjectTokenType)) {
            assertion = awsVerifier.verify(subjectToken, active.provider(), active.providerName(), audience.trim());
            if (attributeMapping.isEmpty()) {
                attributeMapping = DEFAULT_AWS_ATTRIBUTE_MAPPING;
            }
        } else {
            assertion = oidcVerifier.verify(subjectToken, active.provider(), active.providerName());
        }

        String attributeCondition = active.provider().path("attributeCondition").asText(null);
        WorkloadIdentityCelEvaluator.MappedAttributes mapped =
                celEvaluator.evaluate(assertion, attributeMapping, attributeCondition);

        String poolPath = "iam.googleapis.com/" + active.poolName();
        String principal = "principal://" + poolPath + "/subject/" + mapped.subject();
        List<String> principalSets = new ArrayList<>();
        principalSets.add("principalSet://" + poolPath + "/*");
        for (Map.Entry<String, String> entry : mapped.attributes().entrySet()) {
            principalSets.add("principalSet://" + poolPath + "/attribute." + entry.getKey() + "/" + entry.getValue());
        }
        for (String group : mapped.groups()) {
            principalSets.add("principalSet://" + poolPath + "/group/" + group);
        }

        List<CredentialAccessBoundaryRule> gcsRules = List.of();
        if (options != null && !options.isBlank()) {
            try {
                gcsRules = cabParser.parse(options);
            } catch (GcpException e) {
                throw invalidGrant(e.getMessage());
            }
        }

        CredentialTokenService.MintedFederatedToken minted = tokenService.mintFederatedToken(
                principal, principalSets, gcsRules, CredentialTokenService.DEFAULT_LIFETIME_SECONDS);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("access_token", minted.token().getTokenValue());
        response.put("issued_token_type", ACCESS_TOKEN_TYPE);
        response.put("token_type", BEARER_TOKEN_TYPE);
        response.put("expires_in", minted.expiresInSeconds());
        if (scope != null && !scope.isBlank()) {
            response.put("scope", scope);
        }
        return response;
    }

    private static void requireEquals(String field, String actual, String expected) {
        if (!expected.equals(actual)) {
            throw invalidRequest(field + " must be " + expected);
        }
    }

    private static StsException invalidRequest(String message) {
        return new StsException("invalid_request", message);
    }

    private static StsException invalidTarget(String message) {
        return new StsException("invalid_target", message);
    }

    private static StsException invalidGrant(String message) {
        return new StsException("invalid_grant", message);
    }
}
