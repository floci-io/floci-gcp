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
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
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

	private static AccessToken downscopedAccessToken(String bucket) throws Exception {
		GoogleCredentials sourceCredentials = GoogleCredentials.create(new AccessToken(
				"source-token",
				Date.from(Instant.now().plusSeconds(3600))));
		CredentialAccessBoundary cab = CredentialAccessBoundary.newBuilder()
				.addRule(CredentialAccessBoundary.AccessBoundaryRule.newBuilder()
						.setAvailableResource("//storage.googleapis.com/projects/_/buckets/" + bucket)
						.setAvailablePermissions(List.of(
								"inRole:roles/storage.legacyObjectReader",
								"inRole:roles/storage.objectViewer",
								"inRole:roles/storage.legacyBucketWriter"))
						.setAvailabilityCondition(
								CredentialAccessBoundary.AccessBoundaryRule.AvailabilityCondition.newBuilder()
										.setExpression("resource.name.startsWith("
												+ "'projects/_/buckets/" + bucket + "/objects/allowed/')"
												+ " || api.getAttribute("
												+ "'storage.googleapis.com/objectListPrefix', '').startsWith("
												+ "'allowed/')")
										.build())
						.build())
				.build();

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
