package io.floci.gcp.services.iam;

import com.google.storage.v2.Bucket;
import com.google.storage.v2.CreateBucketRequest;
import com.google.storage.v2.StorageGrpc;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.restassured.specification.RequestSpecification;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;

@QuarkusTest
@TestProfile(IamBucketPolicyBootstrapRestIntegrationTest.EnforceAuthorizationProfile.class)
class IamBucketPolicyBootstrapRestIntegrationTest {

    private static final String BOOTSTRAP = "bootstrap@test-project.iam.gserviceaccount.com";
    private static final String CREATOR = "creator@test-project.iam.gserviceaccount.com";

    @Inject
    CredentialTokenService tokenService;

    @TestHTTPResource
    URI endpoint;

    @Test
    void grantsBucketCreatorStorageAdminWhenTheCallerHasAResolvableIdentity() {
        String authorization = bearer(CREATOR);
        String bucket = createBucket(authorization);

        given().header("Authorization", authorization)
                .when().get("/storage/v1/b/" + bucket).then().statusCode(200);
        given().when().get("/storage/v1/b/" + bucket).then().statusCode(403);
    }

    @Test
    void configuredBootstrapAdminCanManageBucketsCreatedWithoutAnIdentity() {
        String bucket = createBucket(null);
        String authorization = bearer(BOOTSTRAP);

        given().header("Authorization", authorization)
                .when().get("/storage/v1/b/" + bucket).then().statusCode(200);
        given().when().get("/storage/v1/b/" + bucket).then().statusCode(403);
    }

    @Test
    void configuredBootstrapAdminCanManageBucketsCreatedOverGrpc() throws Exception {
        String bucket = "iam-bootstrap-grpc-" + UUID.randomUUID().toString().substring(0, 8);
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext()
                .build();
        try {
            StorageGrpc.newBlockingStub(channel).createBucket(CreateBucketRequest.newBuilder()
                    .setParent("projects/_")
                    .setBucketId(bucket)
                    .setBucket(Bucket.newBuilder().setProject("projects/test-project"))
                    .build());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }

        given().header("Authorization", bearer(BOOTSTRAP))
                .when().get("/storage/v1/b/" + bucket).then().statusCode(200);
        given().when().get("/storage/v1/b/" + bucket).then().statusCode(403);
    }

    private String createBucket(String authorization) {
        String bucket = "iam-bootstrap-" + UUID.randomUUID().toString().substring(0, 8);
        RequestSpecification request = given().contentType("application/json").body(Map.of("name", bucket));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        request.when().post("/storage/v1/b?project=test-project").then().statusCode(200);
        return bucket;
    }

    private String bearer(String serviceAccount) {
        return "Bearer " + tokenService.mintImpersonatedToken(serviceAccount, Instant.now().plusSeconds(600))
                .getTokenValue();
    }

    public static class EnforceAuthorizationProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-gcp.services.iam.authorization-mode", "enforce",
                    "floci-gcp.services.iam.bootstrap-admin-member", "serviceAccount:" + BOOTSTRAP);
        }
    }
}
