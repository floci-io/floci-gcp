package io.floci.gcp.test;

import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.auth.http.HttpTransportFactory;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.CredentialAccessBoundary;
import com.google.auth.oauth2.DownscopedCredentials;
import com.google.auth.oauth2.GoogleCredentials;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StsTokenExchangeTest {

	@Test
	void downscopedCredentialsExchangeTokenWithCustomStsTransport() throws Exception {
		GoogleCredentials sourceCredentials = GoogleCredentials.create(new AccessToken(
				"source-token",
				Date.from(Instant.now().plusSeconds(3600))));
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

		DownscopedCredentials credentials = DownscopedCredentials.newBuilder()
				.setSourceCredential(sourceCredentials)
				.setCredentialAccessBoundary(cab)
				.setHttpTransportFactory(stsTransportFactory())
				.build();

		AccessToken accessToken = credentials.refreshAccessToken();

		assertThat(accessToken.getTokenValue()).startsWith("floci-gcp-downscoped-");
		assertThat(accessToken.getExpirationTime()).isAfter(Date.from(Instant.now()));
	}

	private static HttpTransportFactory stsTransportFactory() {
		return () -> new NetHttpTransport.Builder()
				.setConnectionFactory(url -> {
					if ("sts.googleapis.com".equals(url.getHost())) {
						URL rewritten = new URL(TestFixtures.endpoint() + url.getFile());
						return (HttpURLConnection) rewritten.openConnection();
					}
					return (HttpURLConnection) url.openConnection();
				})
				.build();
	}
}
