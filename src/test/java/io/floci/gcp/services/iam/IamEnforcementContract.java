package io.floci.gcp.services.iam;

import com.google.cloud.secretmanager.v1.GetSecretRequest;
import com.google.cloud.secretmanager.v1.SecretManagerServiceGrpc;
import com.google.iam.v1.Binding;
import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.IAMPolicyGrpc;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import com.google.iam.v1.TestIamPermissionsRequest;
import com.google.pubsub.v1.GetTopicRequest;
import com.google.pubsub.v1.PublisherGrpc;
import com.google.type.Expr;
import io.floci.gcp.services.credentials.CredentialAccessBoundaryRule;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.credentials.StoredCredentialToken;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

/** Identical wire requests in both modes prove that the feature flag gates real denials. */
abstract class IamEnforcementContract {
    @Inject
    CredentialTokenService tokens;
    @TestHTTPResource
    URI endpoint;

    String project;
    String member;
    String credential;
    ManagedChannel channel;
    Channel authenticated;

    abstract boolean enforced();

    @BeforeEach
    void setup() {
        project = "projects/iam-" + UUID.randomUUID().toString().substring(0, 8);
        String email = "reader@" + project.substring("projects/".length()) + ".iam.gserviceaccount.com";
        member = "serviceAccount:" + email;
        credential = "Bearer " + tokens.mintImpersonatedToken(email, Instant.now().plusSeconds(300)).getTokenValue();
        channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
        authenticated = withCredential(credential);
    }

    @AfterEach
    void closeChannel() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    enum Denial { OTHER_PERMISSION, SIBLING, CONDITION }

    static Stream<Arguments> denialCases() {
        return Stream.of(Denial.values()).flatMap(denial -> Stream.of(false, true)
                .map(grpc -> Arguments.of(denial, grpc)));
    }

    @ParameterizedTest
    @MethodSource("denialCases")
    void eachNegativeHasAnIndependentTransportAssertion(Denial denial, boolean grpc) {
        grant(project, "roles/browser", denial == Denial.CONDITION ? "resource.name == 'excluded'" : null);
        String target = denial == Denial.SIBLING ? project + "-sibling" : project;
        if (grpc) {
            grpcDenied(() -> {
                if (denial == Denial.OTHER_PERMISSION) {
                    grpcSetPolicy(target);
                } else {
                    grpcRead(authenticated, target);
                }
            });
        } else {
            Response response = denial == Denial.OTHER_PERMISSION
                    ? request().header("Authorization", credential).contentType("application/json")
                            .body(Map.of("policy", policy("roles/owner", null))).post("/v1/" + target + ":setIamPolicy")
                    : request().header("Authorization", credential).get("/v1/" + target);
            restDenied(response);
        }
    }

    @Test
    void browserCanReadMetadataAndPolicyAndPermissionsAreEvaluatedOnBothTransports() {
        grant(project, "roles/browser", null);
        request().header("Authorization", credential).get("/v1/" + project).then().statusCode(200);
        request().header("Authorization", credential).contentType("application/json").body(Map.of())
                .post("/v1/" + project + ":getIamPolicy").then().statusCode(200)
                .body("bindings[0].role", equalTo("roles/browser"));
        assertEquals("roles/browser", grpcRead(authenticated, project).getBindings(0).getRole());
        List<String> permissions = List.of("resourcemanager.projects.get", "resourcemanager.projects.setIamPolicy");
        List<String> expected = enforced() ? List.of(permissions.getFirst()) : permissions;
        request().header("Authorization", credential).contentType("application/json")
                .body(Map.of("permissions", permissions)).post("/v1/" + project + ":testIamPermissions")
                .then().statusCode(200).body("permissions", equalTo(expected));
        assertEquals(expected, IAMPolicyGrpc.newBlockingStub(authenticated).withDeadlineAfter(5, TimeUnit.SECONDS)
                .testIamPermissions(TestIamPermissionsRequest.newBuilder().setResource(project)
                        .addAllPermissions(permissions).build()).getPermissionsList());
    }

    @Test
    void policyAdminCanMutateThroughBothTransportsAndRestBodySurvivesTheFilter() {
        grant(project, "roles/resourcemanager.projectIamAdmin", null);
        request().header("Authorization", credential).contentType("application/json")
                .body(Map.of("policy", policy("roles/owner", null)))
                .post("/v1/" + project + ":setIamPolicy").then().statusCode(200)
                .body("bindings[0].role", equalTo("roles/owner"));
        grpcSetPolicy(project);
        assertEquals("roles/browser", grpcRead(authenticated, project).getBindings(0).getRole());
    }

    @Test
    void conditionsUseProjectAttributesOverBothTransports() {
        grant(project, "roles/browser", "resource.name == '" + project
                + "' && resource.service == 'cloudresourcemanager.googleapis.com'");
        request().header("Authorization", credential).get("/v1/" + project).then().statusCode(200);
        grpcRead(authenticated, project);
    }

    @Test
    void protobufConditionWithoutDescriptionCanBeEvaluated() {
        IAMPolicyGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
                .setIamPolicy(SetIamPolicyRequest.newBuilder().setResource(project)
                        .setPolicy(Policy.newBuilder().setVersion(3).addBindings(Binding.newBuilder()
                                .setRole("roles/browser").addMembers(member)
                                .setCondition(Expr.newBuilder().setTitle("scope")
                                        .setExpression("resource.name == '" + project + "'")))).build());
        grpcRead(authenticated, project);
        request().header("Authorization", credential).get("/v1/" + project).then().statusCode(200);
    }

    @Test
    void deniedMutationLeavesPolicyUnchanged() {
        grant(project, "roles/browser", null);
        grpcDenied(() -> grpcSetPolicy(project + "-ungranted"));
        assertEquals(enforced() ? 0 : 1, grpcRead(channel, project + "-ungranted").getBindingsCount());
    }

    @Test
    void unknownRoleIsDiagnosableOverBothTransports() {
        grant(project, "roles/custom.unimplemented", null);
        Response response = request().header("Authorization", credential).get("/v1/" + project);
        if (enforced()) {
            response.then().statusCode(400).body("error.status", equalTo("FAILED_PRECONDITION"))
                    .body("error.message", containsString("roles/custom.unimplemented"));
            StatusRuntimeException error = assertThrows(StatusRuntimeException.class, () -> grpcRead(authenticated, project));
            assertEquals(Status.Code.FAILED_PRECONDITION, error.getStatus().getCode());
            assertTrue(error.getMessage().contains("roles/custom.unimplemented"));
        } else {
            response.then().statusCode(200);
            grpcRead(authenticated, project);
        }
    }

    @Test
    void unsupportedConditionsAreDiagnosable() {
        grant(project, "roles/browser", "resource.name.matches('.*')");
        Response response = request().header("Authorization", credential).get("/v1/" + project);
        if (enforced()) {
            response.then().statusCode(400).body("error.message", containsString("Unsupported IAM condition"));
            assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class,
                    () -> grpcRead(authenticated, project)).getStatus().getCode());
        } else {
            response.then().statusCode(200);
            grpcRead(authenticated, project);
        }
    }

    @Test
    void anonymousAndExternalCredentialsKeepBypass() {
        request().get("/v1/" + project).then().statusCode(200);
        request().header("Authorization", "Bearer external-token").get("/v1/" + project).then().statusCode(200);
        grpcRead(channel, project);
        grpcRead(withCredential("Bearer external-token"), project);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unimplemented/resource", "topics/deferred", "secrets/deferred"})
    void sharedIamMixinRejectsUnmappedResourceKindsLoudly(String suffix) {
        String unknown = project + "/" + suffix;
        if (enforced()) {
            StatusRuntimeException exception = assertThrows(StatusRuntimeException.class, () -> grpcRead(authenticated, unknown));
            assertEquals(Status.Code.FAILED_PRECONDITION, exception.getStatus().getCode());
            assertTrue(exception.getMessage().contains(unknown));
        } else if (suffix.startsWith("unimplemented")) {
            grpcRead(authenticated, unknown);
        } else {
            // Existing resource resolvers reject absent topics and secrets in disabled mode.
            assertEquals(Status.Code.NOT_FOUND, assertThrows(StatusRuntimeException.class,
                    () -> grpcRead(authenticated, unknown)).getStatus().getCode());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unknownOrExpiredFlociTokenIsUnauthenticatedOverBothTransports(boolean expired) {
        StoredCredentialToken token = tokens.mintImpersonatedToken("expired@p.iam.gserviceaccount.com",
                Instant.now().plusSeconds(300));
        token.setExpireTime(Instant.now().minusSeconds(1));
        String invalid = expired ? "Bearer " + token.getTokenValue() : "Bearer floci-gcp-impersonated-missing";
        Response response = request().header("Authorization", invalid).get("/v1/" + project);
        response.then().statusCode(enforced() ? 401 : 200);
        if (enforced()) {
            assertEquals(Status.Code.UNAUTHENTICATED, assertThrows(StatusRuntimeException.class,
                    () -> grpcRead(withCredential(invalid), project)).getStatus().getCode());
            response.then().body("error.status", equalTo("UNAUTHENTICATED"));
        } else {
            grpcRead(withCredential(invalid), project);
        }
    }

    @Test
    void downscopedTokensCannotExpandTheirBoundaryIntoProjectIam() {
        grant(project, "roles/owner", null);
        CredentialAccessBoundaryRule rule = new CredentialAccessBoundaryRule("bucket", "", List.of("inRole:roles/storage.objectViewer"));
        for (String source : List.of(credential.substring("Bearer ".length()), "external-token")) {
            String token = "Bearer " + tokens.mintDownscopedToken(source, List.of(rule)).token().getTokenValue();
            restDenied(request().header("Authorization", token).get("/v1/" + project));
            grpcDenied(() -> grpcRead(withCredential(token), project));
        }
    }

    @Test
    void deferredServicesRemainPermissiveWithRecognizedCredentials() {
        String topic = project + "/topics/deferred";
        request().contentType("application/json").body(Map.of()).put("/v1/" + topic).then().statusCode(200);
        request().header("Authorization", credential).get("/v1/" + topic).then().statusCode(200);
        assertEquals(topic, PublisherGrpc.newBlockingStub(authenticated).withDeadlineAfter(5, TimeUnit.SECONDS)
                .getTopic(GetTopicRequest.newBuilder().setTopic(topic).build()).getName());
        String secret = project + "/secrets/deferred";
        request().contentType("application/json").queryParam("secretId", "deferred")
                .body(Map.of("replication", Map.of("automatic", Map.of())))
                .post("/v1/" + project + "/secrets").then().statusCode(200);
        request().header("Authorization", credential).get("/v1/" + secret).then().statusCode(200);
        assertEquals(secret, SecretManagerServiceGrpc.newBlockingStub(authenticated).withDeadlineAfter(5, TimeUnit.SECONDS)
                .getSecret(GetSecretRequest.newBuilder().setName(secret).build()).getName());
    }

    private Channel withCredential(String token) {
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), token);
        return ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private Policy grpcRead(Channel transport, String resource) {
        return IAMPolicyGrpc.newBlockingStub(transport).withDeadlineAfter(5, TimeUnit.SECONDS)
                .getIamPolicy(GetIamPolicyRequest.newBuilder().setResource(resource).build());
    }

    private void grpcSetPolicy(String resource) {
        IAMPolicyGrpc.newBlockingStub(authenticated).withDeadlineAfter(5, TimeUnit.SECONDS)
                .setIamPolicy(SetIamPolicyRequest.newBuilder().setResource(resource)
                        .setPolicy(Policy.newBuilder().addBindings(Binding.newBuilder()
                                .setRole("roles/browser").addMembers(member))).build());
    }

    private static RequestSpecification request() {
        return given().urlEncodingEnabled(false);
    }

    private void grant(String resource, String role, String condition) {
        request().contentType("application/json").body(Map.of("policy", policy(role, condition)))
                .post("/v1/" + resource + ":setIamPolicy").then().statusCode(200);
    }

    private Map<String, Object> policy(String role, String condition) {
        Map<String, Object> binding = condition == null ? Map.of("role", role, "members", List.of(member))
                : Map.of("role", role, "members", List.of(member), "condition", Map.of("title", "scope", "expression", condition));
        return Map.of("version", condition == null ? 1 : 3, "bindings", List.of(binding));
    }

    private void restDenied(Response response) {
        response.then().statusCode(enforced() ? 403 : 200);
        if (enforced()) {
            response.then().body("error.status", equalTo("PERMISSION_DENIED"));
        }
    }

    private void grpcDenied(Runnable operation) {
        if (enforced()) {
            StatusRuntimeException exception = assertThrows(StatusRuntimeException.class, operation::run);
            assertEquals(Status.Code.PERMISSION_DENIED, exception.getStatus().getCode(), exception.getMessage());
        } else {
            operation.run();
        }
    }
}
