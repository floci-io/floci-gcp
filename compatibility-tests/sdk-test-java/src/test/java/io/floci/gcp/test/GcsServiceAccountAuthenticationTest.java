package io.floci.gcp.test;

import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GcsServiceAccountAuthenticationTest {

    @Test
    void serviceAccountCredentialsAuthenticateStorageRequests() throws Exception {
        var credentials = TestFixtures.serviceAccountCredentials();

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
