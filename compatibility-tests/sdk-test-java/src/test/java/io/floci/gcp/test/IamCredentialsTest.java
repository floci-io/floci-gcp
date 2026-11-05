package io.floci.gcp.test;

import com.google.auth.oauth2.IdToken;
import com.google.cloud.iam.credentials.v1.GenerateAccessTokenResponse;
import com.google.cloud.iam.credentials.v1.IamCredentialsClient;
import com.google.cloud.iam.credentials.v1.SignBlobResponse;
import com.google.cloud.iam.credentials.v1.SignJwtResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
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

    @Test
    void signBlobAndSignJwtWithHttpJsonClient() throws Exception {
        String email = createServiceAccount("compat-signer");
        String name = "projects/-/serviceAccounts/" + email;

        try (IamCredentialsClient client = TestFixtures.iamCredentialsClient()) {
            SignBlobResponse blob = client.signBlob(name, List.of(), ByteString.copyFromUtf8("hello"));
            assertThat(blob.getKeyId()).matches("[0-9a-f]{40}");
            assertThat(blob.getSignedBlob().size()).isEqualTo(256);

            SignJwtResponse jwt = client.signJwt(name, List.of(), "{\"sub\":\"compat\"}");
            assertThat(jwt.getKeyId()).isEqualTo(blob.getKeyId());
            String[] parts = jwt.getSignedJwt().split("\\.");
            assertThat(parts).hasSize(3);
            assertThat(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8))
                    .contains("\"alg\":\"RS256\"").contains("\"kid\":\"" + blob.getKeyId() + "\"");
        }
    }

    @Test
    void generateIdTokenWithHttpJsonClient() throws Exception {
        String email = createServiceAccount("compat-id-token");
        String name = "projects/-/serviceAccounts/" + email;

        try (IamCredentialsClient client = TestFixtures.iamCredentialsClient()) {
            String withoutEmail = client.generateIdToken(name, List.of(), "https://foo.bar", false).getToken();
            assertThat(IdToken.create(withoutEmail).getExpirationTime()).isInTheFuture();
            assertThat(new String(Base64.getUrlDecoder().decode(withoutEmail.split("\\.")[1]), StandardCharsets.UTF_8))
                    .contains("\"aud\":\"https://foo.bar\"").doesNotContain(email);

            String withEmail = client.generateIdToken(name, List.of(), "https://foo.bar", true).getToken();
            assertThat(new String(Base64.getUrlDecoder().decode(withEmail.split("\\.")[1]), StandardCharsets.UTF_8))
                    .contains("\"email\":\"" + email + "\"").contains("\"email_verified\":true");
        }
    }

    private static String createServiceAccount(String accountId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(TestFixtures.endpoint()
                        + "/v1/projects/" + TestFixtures.projectId() + "/serviceAccounts"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"accountId\":\"" + accountId + "\"}"))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        // 409 means an earlier run (the suite restarts against persisted state) already created it.
        assertThat(response.statusCode()).isIn(200, 409);
        return accountId + "@" + TestFixtures.projectId() + ".iam.gserviceaccount.com";
    }
}
