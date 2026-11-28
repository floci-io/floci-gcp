package io.floci.gcp.test;

import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.auth.http.HttpTransportFactory;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.AwsCredentials;
import com.google.auth.oauth2.AwsSecurityCredentials;
import com.google.auth.oauth2.AwsSecurityCredentialsSupplier;
import com.google.auth.oauth2.CredentialAccessBoundary;
import com.google.auth.oauth2.DownscopedCredentials;
import com.google.auth.oauth2.ExternalAccountSupplierContext;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.IdentityPoolCredentials;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StsTokenExchangeTest {

	@Test
	void downscopedCredentialsExchangeTokenWithCustomStsTransport() throws Exception {
		CredentialAccessBoundary cab = CredentialAccessBoundary.newBuilder()
				.addRule(CredentialAccessBoundary.AccessBoundaryRule.newBuilder()
						.setAvailableResource("//storage.googleapis.com/projects/_/buckets/compat-bucket")
						.setAvailablePermissions(List.of("inRole:roles/storage.legacyObjectReader"))
						.setAvailabilityCondition(
								CredentialAccessBoundary.AccessBoundaryRule.AvailabilityCondition.newBuilder()
										.setExpression("resource.name.startsWith("
											+ "'projects/_/buckets/compat-bucket/objects/allowed/')")
									.build())
							.build())
					.build();
		assertCanExchange(cab);
	}

	@Test
	void downscopedCredentialsExchangeBucketWideRuleWithoutCondition() throws Exception {
		CredentialAccessBoundary cab = CredentialAccessBoundary.newBuilder()
				.addRule(CredentialAccessBoundary.AccessBoundaryRule.newBuilder()
						.setAvailableResource("//storage.googleapis.com/projects/_/buckets/compat-bucket")
						.setAvailablePermissions(List.of("inRole:roles/storage.objectViewer"))
						.build())
				.build();
		assertCanExchange(cab);
	}

	@Test
	void downscopedCredentialsExchangeServiceAccountSourceToken() throws Exception {
		var sourceCredentials = TestFixtures.serviceAccountCredentials();
		CredentialAccessBoundary cab = CredentialAccessBoundary.newBuilder()
				.addRule(CredentialAccessBoundary.AccessBoundaryRule.newBuilder()
						.setAvailableResource("//storage.googleapis.com/projects/_/buckets/compat-bucket")
						.setAvailablePermissions(List.of("inRole:roles/storage.objectViewer"))
						.build())
				.build();
		DownscopedCredentials credentials = DownscopedCredentials.newBuilder()
				.setSourceCredential(sourceCredentials)
				.setCredentialAccessBoundary(cab)
				.setHttpTransportFactory(stsTransportFactory())
				.build();

		AccessToken accessToken = credentials.refreshAccessToken();

		assertThat(accessToken.getTokenValue()).startsWith("floci-gcp-downscoped-");
		assertThat(accessToken.getExpirationTime()).isAfter(Date.from(Instant.now()));
	}

	private static void assertCanExchange(CredentialAccessBoundary cab) throws Exception {
		GoogleCredentials sourceCredentials = GoogleCredentials.create(new AccessToken(
				"source-token",
				Date.from(Instant.now().plusSeconds(3600))));

		DownscopedCredentials credentials = DownscopedCredentials.newBuilder()
				.setSourceCredential(sourceCredentials)
				.setCredentialAccessBoundary(cab)
				.setHttpTransportFactory(stsTransportFactory())
				.build();

		AccessToken accessToken = credentials.refreshAccessToken();

		assertThat(accessToken.getTokenValue()).startsWith("floci-gcp-downscoped-");
		assertThat(accessToken.getExpirationTime()).isAfter(Date.from(Instant.now()));
	}

	@Test
	void identityPoolCredentialsExchangeOidcJwtWithJwks() throws Exception {
		String project = TestFixtures.projectId();
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String poolId = "compat-oidc-" + suffix;
		String providerId = "oidc-prov";
		String issuer = "https://issuer.compat.example";
		String jwtAudience = "https://iam.googleapis.com/compat-oidc";
		String subject = "compat-user-" + suffix;

		KeyPair keyPair = generateRsaKeyPair();
		String jwksJson = buildJwksJson((RSAPublicKey) keyPair.getPublic(), "compat-k1");
		String jwt = signRs256Jwt(keyPair, "compat-k1", issuer, subject, jwtAudience);

		createWorkloadIdentityPool(project, poolId);
		String providerJson = "{"
				+ "\"displayName\":\"compat oidc\","
				+ "\"attributeMapping\":{\"google.subject\":\"assertion.sub\"},"
				+ "\"oidc\":{"
				+ "\"issuerUri\":\"" + issuer + "\","
				+ "\"allowedAudiences\":[\"" + jwtAudience + "\"],"
				+ "\"jwksJson\":\"" + escapeJson(jwksJson) + "\""
				+ "}}";
		createWorkloadIdentityProvider(project, poolId, providerId, providerJson);

		String audience = "//iam.googleapis.com/projects/" + project
				+ "/locations/global/workloadIdentityPools/" + poolId
				+ "/providers/" + providerId;

		IdentityPoolCredentials credentials = IdentityPoolCredentials.newBuilder()
				.setAudience(audience)
				.setSubjectTokenType("urn:ietf:params:oauth:token-type:jwt")
				.setTokenUrl("https://sts.googleapis.com/v1/token")
				.setSubjectTokenSupplier(context -> jwt)
				.setHttpTransportFactory(stsTransportFactory())
				.build();

		AccessToken accessToken = credentials.refreshAccessToken();

		assertThat(accessToken.getTokenValue()).startsWith("floci-gcp-federated-");
		assertThat(accessToken.getExpirationTime()).isAfter(Date.from(Instant.now()));
	}

	@Test
	void awsCredentialsExchangeSignedGetCallerIdentityRequest() throws Exception {
		String project = TestFixtures.projectId();
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String poolId = "compat-aws-" + suffix;
		String providerId = "aws-prov";

		createWorkloadIdentityPool(project, poolId);
		String providerJson = "{"
				+ "\"displayName\":\"compat aws\","
				+ "\"attributeMapping\":{\"google.subject\":\"assertion.arn\",\"attribute.aws_account\":\"assertion.account\"},"
				+ "\"aws\":{\"accountId\":\"123456789012\"}"
				+ "}";
		createWorkloadIdentityProvider(project, poolId, providerId, providerJson);

		String audience = "//iam.googleapis.com/projects/" + project
				+ "/locations/global/workloadIdentityPools/" + poolId
				+ "/providers/" + providerId;

		AwsSecurityCredentialsSupplier supplier = new AwsSecurityCredentialsSupplier() {
			@Override
			public AwsSecurityCredentials getCredentials(ExternalAccountSupplierContext context) {
				return new AwsSecurityCredentials(
						"AKIAIOSFODNN7EXAMPLE",
						"wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
						null);
			}

			@Override
			public String getRegion(ExternalAccountSupplierContext context) {
				return "us-east-1";
			}
		};

		AwsCredentials credentials = AwsCredentials.newBuilder()
				.setAudience(audience)
				.setSubjectTokenType("urn:ietf:params:aws:token-type:aws4_request")
				.setTokenUrl("https://sts.googleapis.com/v1/token")
				.setAwsSecurityCredentialsSupplier(supplier)
				.setHttpTransportFactory(stsTransportFactory())
				.build();

		AccessToken accessToken = credentials.refreshAccessToken();

		assertThat(accessToken.getTokenValue()).startsWith("floci-gcp-federated-");
		assertThat(accessToken.getExpirationTime()).isAfter(Date.from(Instant.now()));
	}

	@Test
	void identityPoolCredentialsWithServiceAccountImpersonation() throws Exception {
		String project = TestFixtures.projectId();
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String poolId = "compat-imp-" + suffix;
		String providerId = "oidc-prov";
		String issuer = "https://issuer.compat.example";
		String jwtAudience = "https://iam.googleapis.com/compat-imp";
		String subject = "compat-imp-user-" + suffix;

		KeyPair keyPair = generateRsaKeyPair();
		String jwksJson = buildJwksJson((RSAPublicKey) keyPair.getPublic(), "compat-k2");
		String jwt = signRs256Jwt(keyPair, "compat-k2", issuer, subject, jwtAudience);

		createWorkloadIdentityPool(project, poolId);
		String providerJson = "{"
				+ "\"displayName\":\"compat oidc impersonation\","
				+ "\"attributeMapping\":{\"google.subject\":\"assertion.sub\"},"
				+ "\"oidc\":{"
				+ "\"issuerUri\":\"" + issuer + "\","
				+ "\"allowedAudiences\":[\"" + jwtAudience + "\"],"
				+ "\"jwksJson\":\"" + escapeJson(jwksJson) + "\""
				+ "}}";
		createWorkloadIdentityProvider(project, poolId, providerId, providerJson);

		String accountId = "compat-wi-" + suffix;
		String email = accountId + "@" + project + ".iam.gserviceaccount.com";
		postJson(TestFixtures.endpoint() + "/v1/projects/" + project + "/serviceAccounts",
				"{\"accountId\":\"" + accountId + "\"}");

		String audience = "//iam.googleapis.com/projects/" + project
				+ "/locations/global/workloadIdentityPools/" + poolId
				+ "/providers/" + providerId;
		String impersonationUrl = "https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/"
				+ email + ":generateAccessToken";

		IdentityPoolCredentials credentials = IdentityPoolCredentials.newBuilder()
				.setAudience(audience)
				.setSubjectTokenType("urn:ietf:params:oauth:token-type:jwt")
				.setTokenUrl("https://sts.googleapis.com/v1/token")
				.setServiceAccountImpersonationUrl(impersonationUrl)
				.setSubjectTokenSupplier(context -> jwt)
				.setHttpTransportFactory(stsTransportFactory())
				.build();

		AccessToken accessToken = credentials.refreshAccessToken();

		assertThat(accessToken.getTokenValue()).isNotBlank();
		assertThat(accessToken.getTokenValue()).doesNotStartWith("floci-gcp-federated-");
		assertThat(accessToken.getExpirationTime()).isAfter(Date.from(Instant.now()));
	}

	private static void createWorkloadIdentityPool(String project, String poolId) throws Exception {
		postJson(TestFixtures.endpoint() + "/v1/projects/" + project
				+ "/locations/global/workloadIdentityPools?workloadIdentityPoolId=" + poolId,
				"{\"displayName\":\"" + poolId + "\"}");
	}

	private static void createWorkloadIdentityProvider(
			String project, String poolId, String providerId, String body) throws Exception {
		postJson(TestFixtures.endpoint() + "/v1/projects/" + project
				+ "/locations/global/workloadIdentityPools/" + poolId
				+ "/providers?workloadIdentityPoolProviderId=" + providerId,
				body);
	}

	private static void postJson(String url, String body) throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode())
				.withFailMessage("POST %s failed (%d): %s", url, response.statusCode(), response.body())
				.isBetween(200, 299);
	}

	private static KeyPair generateRsaKeyPair() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		return generator.generateKeyPair();
	}

	private static String buildJwksJson(RSAPublicKey key, String kid) {
		String n = base64UrlUnsigned(key.getModulus());
		String e = base64UrlUnsigned(key.getPublicExponent());
		return "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"" + kid
				+ "\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\"" + n + "\",\"e\":\"" + e + "\"}]}";
	}

	private static String signRs256Jwt(
			KeyPair keyPair, String kid, String issuer, String subject, String audience) throws Exception {
		long now = Instant.now().getEpochSecond();
		String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + kid + "\"}";
		String payload = "{\"iss\":\"" + issuer + "\",\"sub\":\"" + subject
				+ "\",\"aud\":\"" + audience + "\",\"iat\":" + now + ",\"exp\":" + (now + 3600) + "}";
		Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
		String signingInput = encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8))
				+ "." + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
		Signature signature = Signature.getInstance("SHA256withRSA");
		signature.initSign(keyPair.getPrivate());
		signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
		return signingInput + "." + encoder.encodeToString(signature.sign());
	}

	private static String base64UrlUnsigned(BigInteger value) {
		byte[] bytes = value.toByteArray();
		if (bytes.length > 1 && bytes[0] == 0) {
			byte[] trimmed = new byte[bytes.length - 1];
			System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
			bytes = trimmed;
		}
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String escapeJson(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static HttpTransportFactory stsTransportFactory() {
		return () -> new NetHttpTransport.Builder()
				.setConnectionFactory(url -> {
					if ("sts.googleapis.com".equals(url.getHost())
							|| "iamcredentials.googleapis.com".equals(url.getHost())) {
						URL rewritten = new URL(TestFixtures.endpoint() + url.getFile());
						return (HttpURLConnection) rewritten.openConnection();
					}
					return (HttpURLConnection) url.openConnection();
				})
				.build();
	}
}
