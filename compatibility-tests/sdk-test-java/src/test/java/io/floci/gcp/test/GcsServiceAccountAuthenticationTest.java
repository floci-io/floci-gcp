package io.floci.gcp.test;

import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GcsServiceAccountAuthenticationTest {

    @Test
    void serviceAccountCredentialsAuthenticateStorageRequests() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        ServiceAccountCredentials credentials = ServiceAccountCredentials.newBuilder()
                .setClientId("123456789")
                .setClientEmail("storage-test@test-project.iam.gserviceaccount.com")
                .setPrivateKey(keyPair.getPrivate())
                .setPrivateKeyId("test-key")
                .setScopes(List.of("https://www.googleapis.com/auth/cloud-platform"))
                .setTokenServerUri(URI.create(TestFixtures.endpoint() + "/token"))
                .build();

        String bucketName = TestFixtures.uniqueName("service-account-auth");
        try (Storage storage = StorageOptions.newBuilder()
                .setHost(TestFixtures.endpoint())
                .setProjectId(TestFixtures.projectId())
                .setCredentials(credentials)
                .build()
                .getService()) {
            storage.create(BucketInfo.of(bucketName));

            assertThat(storage.get(bucketName)).isNotNull();
            assertThat(credentials.getAccessToken().getTokenValue()).isNotBlank();

            assertThat(storage.delete(bucketName)).isTrue();
        }
    }
}
