package io.floci.gcp.test;

import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.auth.http.HttpTransportFactory;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.CredentialAccessBoundary;
import com.google.auth.oauth2.DownscopedCredentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.OAuth2Credentials;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.StorageRoles;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.cloud.Identity;
import com.google.cloud.Policy;
import com.google.cloud.iam.credentials.v1.GenerateAccessTokenResponse;
import com.google.cloud.iam.credentials.v1.IamCredentialsClient;
import com.google.protobuf.Duration;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GcsDownscopedTokenTest {

	private static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";
	private static final String AUTHORIZED_SERVICE_ACCOUNT = "downscoped-reader@test-project.iam.gserviceaccount.com";
	private static final String UNAUTHORIZED_SERVICE_ACCOUNT = "downscoped-other@test-project.iam.gserviceaccount.com";

	@Test
	void storageClientEnforcesDownscopedTokenPrefix() throws Exception {
		String bucket = TestFixtures.uniqueName("downscoped-bucket");
		try (Storage setup = TestFixtures.storageClient()) {
			setup.create(BucketInfo.of(bucket));
		}

		AccessToken accessToken = downscopedAccessToken(bucket);
		try (Storage scoped = TestFixtures.storageClient(OAuth2Credentials.create(accessToken))) {
			BlobId allowed = BlobId.of(bucket, "allowed/file.txt");
			scoped.create(BlobInfo.newBuilder(allowed).build(), "allowed".getBytes(StandardCharsets.UTF_8));

			assertThat(new String(scoped.readAllBytes(allowed), StandardCharsets.UTF_8)).isEqualTo("allowed");
			assertThat(scoped.list(bucket, Storage.BlobListOption.prefix("allowed/")).iterateAll())
					.extracting(blob -> blob.getName())
					.contains("allowed/file.txt");

				assertThatThrownBy(() -> scoped.create(
						BlobInfo.newBuilder(BlobId.of(bucket, "allowed_sibling/file.txt")).build(),
						"denied".getBytes(StandardCharsets.UTF_8)))
						.isInstanceOfSatisfying(StorageException.class,
								exception -> assertThat(exception.getCode()).isEqualTo(403));
				assertThatThrownBy(() -> scoped.readAllBytes(BlobId.of(bucket, "allowed_sibling/file.txt")))
						.isInstanceOfSatisfying(StorageException.class,
								exception -> assertThat(exception.getCode()).isEqualTo(403));
				assertThatThrownBy(() -> scoped.list(bucket, Storage.BlobListOption.prefix("allowed_sibling/"))
						.iterateAll().iterator().hasNext())
						.isInstanceOfSatisfying(StorageException.class,
								exception -> assertThat(exception.getCode()).isEqualTo(403));

			assertThat(scoped.delete(allowed)).isTrue();
		}
	}

	@Test
	void storageClientAllowsWholeBucketRuleWithoutListPrefix() throws Exception {
		String bucket = TestFixtures.uniqueName("downscoped-whole-bucket");
		BlobId first = BlobId.of(bucket, "first.txt");
		BlobId second = BlobId.of(bucket, "nested/second.txt");
		try (Storage setup = TestFixtures.storageClient()) {
			setup.create(BucketInfo.of(bucket));
			setup.create(BlobInfo.newBuilder(first).build(), "first".getBytes(StandardCharsets.UTF_8));
			setup.create(BlobInfo.newBuilder(second).build(), "second".getBytes(StandardCharsets.UTF_8));
		}

		CredentialAccessBoundary cab = CredentialAccessBoundary.newBuilder()
				.addRule(CredentialAccessBoundary.AccessBoundaryRule.newBuilder()
						.setAvailableResource("//storage.googleapis.com/projects/_/buckets/" + bucket)
						.setAvailablePermissions(List.of("inRole:roles/storage.objectViewer"))
						.build())
				.build();
		try (Storage scoped = TestFixtures.storageClient(
				OAuth2Credentials.create(exchangeAccessToken(cab)))) {
			assertThat(scoped.list(bucket).iterateAll())
					.extracting(blob -> blob.getName())
					.containsExactlyInAnyOrder("first.txt", "nested/second.txt");
			assertThat(new String(scoped.readAllBytes(second), StandardCharsets.UTF_8)).isEqualTo("second");
		}
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "FLOCI_GCP_IAM_ENFORCEMENT_COMPAT", matches = "true")
	void storageClientEnforcesIamPolicyForDownscopedImpersonatedToken() throws Exception {
		String bucket = TestFixtures.uniqueName("downscoped-iam");
		BlobId allowed = BlobId.of(bucket, "allowed/report.csv");
		BlobId outside = BlobId.of(bucket, "outside/report.csv");
		try (Storage setup = TestFixtures.storageClient()) {
			setup.create(BucketInfo.of(bucket));
			setup.create(BlobInfo.newBuilder(allowed).build(), "allowed".getBytes(StandardCharsets.UTF_8));
			setup.create(BlobInfo.newBuilder(outside).build(), "outside".getBytes(StandardCharsets.UTF_8));
			setup.setIamPolicy(bucket, Policy.newBuilder()
					.addIdentity(StorageRoles.objectViewer(), Identity.serviceAccount(AUTHORIZED_SERVICE_ACCOUNT))
					.build());
		}

		CredentialAccessBoundary cab = prefixReadAndListCab(bucket, "allowed/");
		try (Storage scoped = TestFixtures.storageClient(
				OAuth2Credentials.create(exchangeImpersonatedAccessToken(AUTHORIZED_SERVICE_ACCOUNT, cab)))) {
			assertThat(new String(scoped.readAllBytes(allowed), StandardCharsets.UTF_8)).isEqualTo("allowed");
			assertThat(scoped.list(bucket, Storage.BlobListOption.prefix("allowed/")).iterateAll())
					.extracting(blob -> blob.getName())
					.contains("allowed/report.csv");
			assertThatThrownBy(() -> scoped.readAllBytes(outside))
					.isInstanceOfSatisfying(StorageException.class,
							exception -> assertThat(exception.getCode()).isEqualTo(403));
		}

		try (Storage scoped = TestFixtures.storageClient(
				OAuth2Credentials.create(exchangeImpersonatedAccessToken(UNAUTHORIZED_SERVICE_ACCOUNT, cab)))) {
			assertThatThrownBy(() -> scoped.readAllBytes(allowed))
					.isInstanceOfSatisfying(StorageException.class,
							exception -> assertThat(exception.getCode()).isEqualTo(403));
		}
	}

	private static AccessToken downscopedAccessToken(String bucket) throws Exception {
		return exchangeAccessToken(prefixReadListAndWriteCab(bucket, "allowed/"));
	}

	private static CredentialAccessBoundary prefixReadListAndWriteCab(String bucket, String prefix) {
		return CredentialAccessBoundary.newBuilder()
				.addRule(CredentialAccessBoundary.AccessBoundaryRule.newBuilder()
						.setAvailableResource("//storage.googleapis.com/projects/_/buckets/" + bucket)
						.setAvailablePermissions(List.of(
								"inRole:roles/storage.legacyObjectReader",
								"inRole:roles/storage.objectViewer",
								"inRole:roles/storage.legacyBucketWriter"))
						.setAvailabilityCondition(
								CredentialAccessBoundary.AccessBoundaryRule.AvailabilityCondition.newBuilder()
										.setExpression(prefixExpression(bucket, prefix))
										.build())
						.build())
				.build();
	}

	private static CredentialAccessBoundary prefixReadAndListCab(String bucket, String prefix) {
		return CredentialAccessBoundary.newBuilder()
				.addRule(CredentialAccessBoundary.AccessBoundaryRule.newBuilder()
						.setAvailableResource("//storage.googleapis.com/projects/_/buckets/" + bucket)
						.setAvailablePermissions(List.of(
								"inRole:roles/storage.legacyObjectReader",
								"inRole:roles/storage.objectViewer"))
						.setAvailabilityCondition(
								CredentialAccessBoundary.AccessBoundaryRule.AvailabilityCondition.newBuilder()
										.setExpression(prefixExpression(bucket, prefix))
										.build())
						.build())
				.build();
	}

	private static String prefixExpression(String bucket, String prefix) {
		return "resource.name.startsWith("
				+ "'projects/_/buckets/" + bucket + "/objects/" + prefix + "')"
				+ " || api.getAttribute("
				+ "'storage.googleapis.com/objectListPrefix', '').startsWith("
				+ "'" + prefix + "')";
	}

	private static AccessToken exchangeImpersonatedAccessToken(
			String serviceAccount, CredentialAccessBoundary cab) throws Exception {
		GenerateAccessTokenResponse sourceToken;
		try (IamCredentialsClient client = TestFixtures.iamCredentialsClient()) {
			sourceToken = client.generateAccessToken(
					"projects/-/serviceAccounts/" + serviceAccount,
					List.of(), List.of(CLOUD_PLATFORM_SCOPE), Duration.newBuilder().setSeconds(600).build());
		}
		GoogleCredentials sourceCredentials = GoogleCredentials.create(new AccessToken(
				sourceToken.getAccessToken(), Date.from(Instant.ofEpochSecond(
					sourceToken.getExpireTime().getSeconds(), sourceToken.getExpireTime().getNanos()))));
		DownscopedCredentials credentials = DownscopedCredentials.newBuilder()
				.setSourceCredential(sourceCredentials)
				.setCredentialAccessBoundary(cab)
				.setHttpTransportFactory(stsTransportFactory())
				.build();
		return credentials.refreshAccessToken();
	}

	private static AccessToken exchangeAccessToken(CredentialAccessBoundary cab) throws Exception {
		GoogleCredentials sourceCredentials = GoogleCredentials.create(new AccessToken(
				"source-token",
				Date.from(Instant.now().plusSeconds(3600))));
		DownscopedCredentials credentials = DownscopedCredentials.newBuilder()
				.setSourceCredential(sourceCredentials)
				.setCredentialAccessBoundary(cab)
				.setHttpTransportFactory(stsTransportFactory())
				.build();
		return credentials.refreshAccessToken();
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
