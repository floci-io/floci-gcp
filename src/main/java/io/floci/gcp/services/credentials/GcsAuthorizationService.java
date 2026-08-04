package io.floci.gcp.services.credentials;

import io.floci.gcp.core.common.GcpException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@ApplicationScoped
public class GcsAuthorizationService {

	private static final String BEARER_PREFIX = "Bearer ";

	private final CredentialTokenService tokenService;

	@Inject
	public GcsAuthorizationService(CredentialTokenService tokenService) {
		this.tokenService = tokenService;
	}

	public void requireObjectRead(String authorization, String bucket, String objectName) {
		lookupDownscopedToken(authorization)
				.ifPresent(token -> requireMatchingRule(token, bucket, objectName,
						List.of(CredentialAccessBoundaryParser.LEGACY_OBJECT_READER,
								CredentialAccessBoundaryParser.OBJECT_VIEWER)));
	}

	public void requireObjectWrite(String authorization, String bucket, String objectName) {
		lookupDownscopedToken(authorization)
				.ifPresent(token -> requireMatchingRule(token, bucket, objectName,
						List.of(CredentialAccessBoundaryParser.LEGACY_BUCKET_WRITER)));
	}

	public void requireObjectDelete(String authorization, String bucket, String objectName) {
		requireObjectWrite(authorization, bucket, objectName);
	}

	public void requireObjectList(String authorization, String bucket, String prefix) {
		lookupDownscopedToken(authorization).ifPresent(token -> {
			if (prefix == null || prefix.isBlank()) {
				throw permissionDenied();
			}
			requireMatchingPrefix(token, bucket, prefix,
					List.of(CredentialAccessBoundaryParser.OBJECT_VIEWER,
							CredentialAccessBoundaryParser.LEGACY_BUCKET_WRITER));
		});
	}

	public void requireSourceReadAndDestinationWrite(String authorization, String srcBucket, String srcObject,
			String dstBucket, String dstObject) {
		requireObjectRead(authorization, srcBucket, srcObject);
		requireObjectWrite(authorization, dstBucket, dstObject);
	}

	public void rejectDownscopedToken(String authorization) {
		lookupDownscopedToken(authorization).ifPresent(token -> {
			throw permissionDenied();
		});
	}

	public boolean isBypassed(String authorization) {
		return bearerToken(authorization)
				.map(token -> !token.startsWith(CredentialTokenService.FLOCI_TOKEN_PREFIX))
				.orElse(true);
	}

	private Optional<StoredCredentialToken> lookupDownscopedToken(String authorization) {
		Optional<String> bearerToken = bearerToken(authorization);
		if (bearerToken.isEmpty()) {
			return Optional.empty();
		}
		Optional<StoredCredentialToken> token = tokenService.lookupBearerToken(bearerToken.get());
		if (token.isEmpty() || token.get().getTokenKind() != StoredCredentialToken.TokenKind.DOWNSCOPED) {
			return Optional.empty();
		}
		return token;
	}

	private static Optional<String> bearerToken(String authorization) {
		if (authorization == null || authorization.isBlank()
				|| !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
			return Optional.empty();
		}
		String token = authorization.substring(BEARER_PREFIX.length()).trim();
		return token.isEmpty() ? Optional.empty() : Optional.of(token);
	}

	private static void requireMatchingRule(StoredCredentialToken token, String bucket, String objectName,
			List<String> acceptedPermissions) {
		if (token.getGcsRules().stream()
				.anyMatch(rule -> matchesObject(rule, bucket, objectName)
						&& hasAnyPermission(rule, acceptedPermissions))) {
			return;
		}
		throw permissionDenied();
	}

	private static void requireMatchingPrefix(StoredCredentialToken token, String bucket, String prefix,
			List<String> acceptedPermissions) {
		String normalizedPrefix = normalizePrefix(prefix);
		if (token.getGcsRules().stream()
				.anyMatch(rule -> bucket.equals(rule.getBucket())
						&& normalizedPrefix.startsWith(rule.getObjectPrefix())
						&& hasAnyPermission(rule, acceptedPermissions))) {
			return;
		}
		throw permissionDenied();
	}

	private static boolean matchesObject(CredentialAccessBoundaryRule rule, String bucket, String objectName) {
		return bucket.equals(rule.getBucket())
				&& objectName != null
				&& objectName.startsWith(rule.getObjectPrefix());
	}

	private static boolean hasAnyPermission(CredentialAccessBoundaryRule rule, List<String> acceptedPermissions) {
		return rule.getAvailablePermissions().stream()
				.map(permission -> permission.toLowerCase(Locale.ROOT))
				.anyMatch(permission -> acceptedPermissions.stream()
						.map(p -> p.toLowerCase(Locale.ROOT))
						.anyMatch(permission::equals));
	}

	private static String normalizePrefix(String prefix) {
		return prefix.endsWith("/") ? prefix : prefix + "/";
	}

	private static GcpException permissionDenied() {
		return GcpException.permissionDenied("Downscoped token does not allow this GCS operation");
	}
}
