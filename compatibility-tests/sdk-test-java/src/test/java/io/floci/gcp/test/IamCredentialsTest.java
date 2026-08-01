package io.floci.gcp.test;

import com.google.cloud.iam.credentials.v1.GenerateAccessTokenResponse;
import com.google.cloud.iam.credentials.v1.IamCredentialsClient;
import com.google.protobuf.Duration;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IamCredentialsTest {

    @Test
    void generateAccessTokenWithHttpJsonClient() throws Exception {
        String email = "compat@" + TestFixtures.projectId() + ".iam.gserviceaccount.com";

        try (IamCredentialsClient client = TestFixtures.iamCredentialsClient()) {
            GenerateAccessTokenResponse response = client.generateAccessToken(
                    "projects/-/serviceAccounts/" + email,
                    List.of(),
                    List.of("https://www.googleapis.com/auth/cloud-platform"),
                    Duration.newBuilder().setSeconds(3600).build());

            assertThat(response.getAccessToken()).startsWith("floci-gcp-impersonated-");
            assertThat(response.getExpireTime().getSeconds()).isGreaterThan(Instant.now().getEpochSecond());
        }
    }
}
