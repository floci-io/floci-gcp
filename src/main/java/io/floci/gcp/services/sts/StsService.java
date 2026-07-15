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
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class StsService {

	static final String TOKEN_EXCHANGE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange";
	static final String ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";
	static final String BEARER_TOKEN_TYPE = "Bearer";

	private final ServiceRegistry serviceRegistry;
	private final EmulatorConfig config;
	private final CredentialAccessBoundaryParser cabParser;
	private final CredentialTokenService tokenService;

	@Inject
	public StsService(ServiceRegistry serviceRegistry, EmulatorConfig config,
			CredentialAccessBoundaryParser cabParser, CredentialTokenService tokenService) {
		this.serviceRegistry = serviceRegistry;
		this.config = config;
		this.cabParser = cabParser;
		this.tokenService = tokenService;
	}

	StsService(CredentialAccessBoundaryParser cabParser, CredentialTokenService tokenService) {
		this(null, null, cabParser, tokenService);
	}

	void onStart(@Observes StartupEvent ev) {
		serviceRegistry.register(ServiceDescriptor.builder("sts")
				.enabled(config.services().sts().enabled())
				.storageKey("credential-tokens")
				.protocol(ServiceProtocol.REST)
				.resourceClasses(StsController.class)
				.build());
	}

	public Map<String, Object> exchangeToken(String grantType, String subjectTokenType,
			String subjectToken, String requestedTokenType, String options) {
		requireEquals("grant_type", grantType, TOKEN_EXCHANGE_GRANT_TYPE);
		requireEquals("subject_token_type", subjectTokenType, ACCESS_TOKEN_TYPE);
		requireEquals("requested_token_type", requestedTokenType, ACCESS_TOKEN_TYPE);
		if (subjectToken == null || subjectToken.isBlank()) {
			throw invalidRequest("subject_token is required");
		}
		if (options == null || options.isBlank()) {
			throw invalidRequest("options is required");
		}

		List<CredentialAccessBoundaryRule> rules;
		try {
			rules = cabParser.parse(options);
		} catch (GcpException e) {
			throw invalidGrant(e.getMessage());
		}

		StoredCredentialToken token = tokenService.mintDownscopedToken(subjectToken, rules);
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("access_token", token.getTokenValue());
		response.put("issued_token_type", ACCESS_TOKEN_TYPE);
		response.put("token_type", BEARER_TOKEN_TYPE);
		response.put("expires_in", CredentialTokenService.DEFAULT_LIFETIME_SECONDS);
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

	private static StsException invalidGrant(String message) {
		return new StsException("invalid_grant", message);
	}
}
