package io.floci.gcp.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.rpc.PermissionDeniedException;
import com.google.api.services.iam.v1.Iam;
import com.google.api.services.iam.v1.model.Binding;
import com.google.api.services.iam.v1.model.CreateServiceAccountKeyRequest;
import com.google.api.services.iam.v1.model.CreateServiceAccountRequest;
import com.google.api.services.iam.v1.model.Expr;
import com.google.api.services.iam.v1.model.PatchServiceAccountRequest;
import com.google.api.services.iam.v1.model.Policy;
import com.google.api.services.iam.v1.model.ServiceAccount;
import com.google.api.services.iam.v1.model.ServiceAccountKey;
import com.google.api.services.iam.v1.model.SetIamPolicyRequest;
import com.google.api.services.iam.v1.model.SignBlobRequest;
import com.google.api.services.iam.v1.model.TestIamPermissionsRequest;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.iam.credentials.v1.GenerateAccessTokenResponse;
import com.google.cloud.iam.credentials.v1.IamCredentialsClient;
import com.google.cloud.iam.credentials.v1.IamCredentialsSettings;
import com.google.iam.v1.IAMPolicyGrpc;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IamServiceAccountIamEnforcementTest {

    private static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    private final boolean enforce = Boolean.parseBoolean(System.getenv("FLOCI_GCP_IAM_TEST_ENFORCEMENT"));
    private IamCredentialsClient setup;
    private IamCredentialsClient caller;
    private String project;
    private String callerEmail;
    private String targetEmail;
    private String token;
    private Iam admin;
    private Iam management;

    @BeforeEach
    void setUp() throws Exception {
        project = TestFixtures.uniqueName("iam-service-account");
        callerEmail = "caller@" + project + ".iam.gserviceaccount.com";
        targetEmail = "target@" + project + ".iam.gserviceaccount.com";
        createTargetAccount();
        setup = TestFixtures.iamCredentialsClient();
        GenerateAccessTokenResponse callerToken = setup.generateAccessToken(
                "projects/-/serviceAccounts/" + callerEmail, List.of(), List.of(CLOUD_PLATFORM_SCOPE),
                Duration.newBuilder().setSeconds(3600).build());
        token = callerToken.getAccessToken();
        admin = iamClient(null);
        management = iamClient(token);
        GoogleCredentials credentials = GoogleCredentials.create(
                new AccessToken(callerToken.getAccessToken(), Date.from(Instant.now().plusSeconds(300))));
        IamCredentialsSettings settings = IamCredentialsSettings.newHttpJsonBuilder()
                .setEndpoint(TestFixtures.endpoint())
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                .build();
        caller = IamCredentialsClient.create(settings);
    }

    @AfterEach
    void close() {
        if (caller != null) {
            caller.close();
        }
        if (setup != null) {
            setup.close();
        }
    }

    @Test
    void generateAccessTokenRequiresTokenCreatorBindingInEnforceMode() throws Exception {
        String target = "projects/-/serviceAccounts/" + targetEmail;
        if (enforce) {
            assertThatThrownBy(() -> caller.generateAccessToken(target, List.of(), List.of(CLOUD_PLATFORM_SCOPE),
                    Duration.newBuilder().setSeconds(3600).build()))
                    .isInstanceOf(PermissionDeniedException.class);
        } else {
            assertThat(caller.generateAccessToken(target, List.of(), List.of(CLOUD_PLATFORM_SCOPE),
                    Duration.newBuilder().setSeconds(3600).build()).getAccessToken())
                    .startsWith("floci-gcp-impersonated-");
        }

        admin.projects().serviceAccounts().create("projects/" + project,
                new CreateServiceAccountRequest().setAccountId("sibling")).execute();
        setProjectPolicy("roles/iam.serviceAccountTokenCreator",
                "resource.name == 'projects/" + project + "/serviceAccounts/" + targetEmail + "'");

        assertThat(caller.generateAccessToken(target, List.of(), List.of(CLOUD_PLATFORM_SCOPE),
                Duration.newBuilder().setSeconds(3600).build()).getAccessToken())
                .startsWith("floci-gcp-impersonated-");
        if (enforce) {
            assertThatThrownBy(() -> caller.generateAccessToken(
                    "projects/-/serviceAccounts/sibling@" + project + ".iam.gserviceaccount.com", List.of(),
                    List.of(CLOUD_PLATFORM_SCOPE), Duration.newBuilder().setSeconds(3600).build()))
                    .isInstanceOf(PermissionDeniedException.class);
        }
    }

    @Test
    void officialCredentialsClientEnforcesSigningPermissions() throws Exception {
        String target = "projects/-/serviceAccounts/" + targetEmail;
        if (enforce) {
            assertThatThrownBy(() -> caller.signBlob(target, List.of(), ByteString.copyFromUtf8("hello")))
                    .isInstanceOf(PermissionDeniedException.class);
            assertThatThrownBy(() -> caller.signJwt(target, List.of(), "{}"))
                    .isInstanceOf(PermissionDeniedException.class);
        }
        setProjectPolicy("roles/iam.serviceAccountTokenCreator", null);
        assertThat(caller.signBlob(target, List.of(), ByteString.copyFromUtf8("hello")).getSignedBlob().size())
                .isEqualTo(256);
        assertThat(caller.signJwt(target, List.of(), "{}").getSignedJwt().split("\\.")).hasSize(3);
    }

    @Test
    void officialIamRestClientEnforcesManagementPolicyAndKeyPermissions() throws Exception {
        String parent = "projects/" + project;
        String target = parent + "/serviceAccounts/" + targetEmail;
        String sibling = parent + "/serviceAccounts/sibling@" + project + ".iam.gserviceaccount.com";
        CreateServiceAccountRequest request = new CreateServiceAccountRequest().setAccountId("created");
        deniedRest(() -> management.projects().serviceAccounts().create(parent, request).execute());
        if (enforce) {
            assertThat(admin.projects().serviceAccounts().list(parent).execute().getAccounts())
                    .extracting(ServiceAccount::getEmail).doesNotContain("created@" + project + ".iam.gserviceaccount.com");
        }
        setProjectPolicy("roles/iam.serviceAccountAdmin", null);
        if (!enforce) {
            admin.projects().serviceAccounts().delete(parent + "/serviceAccounts/created@" + project
                    + ".iam.gserviceaccount.com").execute();
        }
        ServiceAccount created = management.projects().serviceAccounts().create(parent, request).execute();
        assertThat(created.getEmail()).startsWith("created@");
        assertThat(management.projects().serviceAccounts().list(parent).execute().getAccounts())
                .extracting(ServiceAccount::getEmail).contains(targetEmail);
        assertThat(management.projects().serviceAccounts().get(target).execute().getEmail()).isEqualTo(targetEmail);
        ServiceAccount updated = management.projects().serviceAccounts().update(target,
                new ServiceAccount().setDisplayName("updated via SDK")).execute();
        assertThat(updated.getDisplayName()).isEqualTo("updated via SDK");
        assertThat(management.projects().serviceAccounts().patch(target, new PatchServiceAccountRequest()
                .setServiceAccount(new ServiceAccount().setDescription("patched via SDK"))
                .setUpdateMask("description")).execute().getDescription()).isEqualTo("patched via SDK");
        admin.projects().serviceAccounts().create(parent, new CreateServiceAccountRequest().setAccountId("sibling")).execute();
        setProjectPolicy("roles/iam.serviceAccountAdmin", "resource.name == '" + target + "'");
        deniedRest(() -> management.projects().serviceAccounts().get(sibling).execute());
        deniedRest(() -> management.projects().serviceAccounts().delete(sibling).execute());
        if (enforce) {
            assertThat(admin.projects().serviceAccounts().get(sibling).execute().getEmail()).startsWith("sibling@");
        }
        admin.projects().serviceAccounts().create("projects/other-" + project,
                new CreateServiceAccountRequest().setAccountId("target")).execute();
        deniedRest(() -> management.projects().serviceAccounts().get(
                "projects/other-" + project + "/serviceAccounts/target@other-" + project + ".iam.gserviceaccount.com").execute());
        List<String> permissions = List.of("iam.serviceAccounts.update", "iam.serviceAccounts.signBlob");
        assertThat(management.projects().serviceAccounts().testIamPermissions(target,
                new TestIamPermissionsRequest().setPermissions(permissions)).execute().getPermissions())
                .containsExactlyElementsOf(enforce ? List.of(permissions.getFirst()) : permissions);
        deniedRest(() -> management.projects().serviceAccounts().keys().create(target,
                new CreateServiceAccountKeyRequest()).execute());
        if (enforce) {
            assertThat(admin.projects().serviceAccounts().keys().list(target).execute().getKeys()).isNullOrEmpty();
        }
        Policy keyGrant = policy("roles/iam.serviceAccountKeyAdmin", "resource.name.startsWith('" + target + "')");
        admin.projects().serviceAccounts().setIamPolicy(target, new SetIamPolicyRequest().setPolicy(keyGrant)).execute();
        String key = management.projects().serviceAccounts().keys().create(target,
                new CreateServiceAccountKeyRequest()).execute().getName();
        assertThat(management.projects().serviceAccounts().keys().get(key).execute().getName()).isEqualTo(key);
        assertThat(management.projects().serviceAccounts().keys().list(target).execute().getKeys())
                .extracting(ServiceAccountKey::getName).contains(key);
        management.projects().serviceAccounts().keys().delete(key).execute();
        Policy policy = management.projects().serviceAccounts().getIamPolicy(target).execute();
        assertThat(policy.getBindings()).extracting(Binding::getRole).contains("roles/iam.serviceAccountKeyAdmin");
        management.projects().serviceAccounts().setIamPolicy(target, new SetIamPolicyRequest().setPolicy(policy)).execute();
        deniedRest(() -> management.projects().serviceAccounts().signBlob(target,
                new SignBlobRequest().setBytesToSign("aGVsbG8=")).execute());
        admin.projects().serviceAccounts().setIamPolicy(target, new SetIamPolicyRequest()
                .setPolicy(policy("roles/iam.serviceAccountTokenCreator", null))).execute();
        assertThat(management.projects().serviceAccounts().signBlob(target,
                new SignBlobRequest().setBytesToSign("aGVsbG8=")).execute().getSignature()).isNotBlank();
        management.projects().serviceAccounts().delete(target).execute();
    }

    @Test
    void generatedIamGrpcClientEnforcesConditionalServiceAccountPolicies() throws Exception {
        String resource = "projects/" + project + "/serviceAccounts/" + targetEmail;
        String sibling = "projects/" + project + "/serviceAccounts/sibling@" + project + ".iam.gserviceaccount.com";
        admin.projects().serviceAccounts().create("projects/" + project,
                new CreateServiceAccountRequest().setAccountId("sibling")).execute();
        setProjectPolicy("roles/iam.serviceAccountAdmin", "resource.name == '" + resource + "'");
        URI endpoint = URI.create(TestFixtures.endpoint());
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        ManagedChannel channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext().intercept(MetadataUtils.newAttachHeadersInterceptor(headers)).build();
        try {
            IAMPolicyGrpc.IAMPolicyBlockingStub grpc = IAMPolicyGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(5, TimeUnit.SECONDS);
            // The protobuf policy types collide with the REST SDK policy types imported above.
            com.google.iam.v1.GetIamPolicyRequest get = com.google.iam.v1.GetIamPolicyRequest.newBuilder()
                    .setResource(resource).build();
            assertThat(grpc.getIamPolicy(get).getBindingsCount()).isZero();
            com.google.iam.v1.Policy escalation = com.google.iam.v1.Policy.newBuilder()
                    .addBindings(com.google.iam.v1.Binding.newBuilder().setRole("roles/iam.serviceAccountTokenCreator")
                            .addMembers("serviceAccount:" + callerEmail)).build();
            Runnable siblingWrite = () -> grpc.setIamPolicy(com.google.iam.v1.SetIamPolicyRequest.newBuilder()
                    .setResource(sibling).setPolicy(escalation).build());
            if (enforce) {
                assertThatThrownBy(siblingWrite::run).isInstanceOfSatisfying(StatusRuntimeException.class,
                        error -> assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
                assertThat(admin.projects().serviceAccounts().getIamPolicy(sibling).execute().getBindings()).isNullOrEmpty();
                assertThatThrownBy(() -> grpc.getIamPolicy(get.toBuilder().setResource(sibling).build()))
                        .isInstanceOfSatisfying(StatusRuntimeException.class,
                                error -> assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
            } else {
                siblingWrite.run();
                assertThat(grpc.getIamPolicy(get.toBuilder().setResource(sibling).build()).getBindingsCount()).isEqualTo(1);
            }
            List<String> permissions = List.of("iam.serviceAccounts.update", "iam.serviceAccounts.signBlob");
            assertThat(grpc.testIamPermissions(com.google.iam.v1.TestIamPermissionsRequest.newBuilder()
                    .setResource(resource).addAllPermissions(permissions).build()).getPermissionsList())
                    .containsExactlyElementsOf(enforce ? List.of(permissions.getFirst()) : permissions);
            assertThat(grpc.setIamPolicy(com.google.iam.v1.SetIamPolicyRequest.newBuilder()
                    .setResource(resource).setPolicy(escalation).build()).getBindingsCount()).isEqualTo(1);
            assertThat(admin.projects().serviceAccounts().getIamPolicy(resource).execute().getBindings())
                    .extracting(Binding::getRole).containsExactly("roles/iam.serviceAccountTokenCreator");
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void deletedAccountPermissionTestsReturnEmptyOverRestAndGrpc() throws Exception {
        String target = "projects/" + project + "/serviceAccounts/" + targetEmail;
        String uniqueId = admin.projects().serviceAccounts().get(target).execute().getUniqueId();
        setProjectPolicy("roles/iam.serviceAccountAdmin", null);
        admin.projects().serviceAccounts().delete(target).execute();
        URI endpoint = URI.create(TestFixtures.endpoint());
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        ManagedChannel channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext().intercept(MetadataUtils.newAttachHeadersInterceptor(headers)).build();
        try {
            IAMPolicyGrpc.IAMPolicyBlockingStub grpc = IAMPolicyGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(5, TimeUnit.SECONDS);
            for (String identifier : List.of(targetEmail, "target", uniqueId)) {
                String resource = "projects/" + project + "/serviceAccounts/" + identifier;
                List<String> permissions = List.of("iam.serviceAccounts.get", "iam.serviceAccounts.update");
                assertThat(management.projects().serviceAccounts().testIamPermissions(resource,
                        new TestIamPermissionsRequest().setPermissions(permissions)).execute().getPermissions())
                        .isNullOrEmpty();
                // The protobuf request type collides with the REST SDK request type imported above.
                assertThat(grpc.testIamPermissions(com.google.iam.v1.TestIamPermissionsRequest.newBuilder()
                        .setResource(resource).addAllPermissions(permissions).build()).getPermissionsList()).isEmpty();
            }
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void replacementAccountStartsWithoutThePreviousAccountsDirectPolicy() throws Exception {
        String target = "projects/" + project + "/serviceAccounts/" + targetEmail;
        admin.projects().serviceAccounts().setIamPolicy(target, new SetIamPolicyRequest()
                .setPolicy(policy("roles/iam.serviceAccountTokenCreator", null))).execute();
        String previousId = admin.projects().serviceAccounts().get(target).execute().getUniqueId();
        assertThat(caller.generateAccessToken("projects/-/serviceAccounts/" + targetEmail,
                List.of(), List.of(CLOUD_PLATFORM_SCOPE), Duration.newBuilder().setSeconds(3600).build())
                .getAccessToken()).startsWith("floci-gcp-impersonated-");
        admin.projects().serviceAccounts().delete(target).execute();
        ServiceAccount replacement = admin.projects().serviceAccounts().create("projects/" + project,
                new CreateServiceAccountRequest().setAccountId("target")).execute();
        assertThat(replacement.getUniqueId()).isNotEqualTo(previousId);
        assertThat(admin.projects().serviceAccounts().getIamPolicy(target).execute().getBindings()).isNullOrEmpty();
        if (enforce) {
            assertThatThrownBy(() -> caller.generateAccessToken("projects/-/serviceAccounts/" + targetEmail,
                    List.of(), List.of(CLOUD_PLATFORM_SCOPE), Duration.newBuilder().setSeconds(3600).build()))
                    .isInstanceOf(PermissionDeniedException.class);
        } else {
            assertThat(caller.generateAccessToken("projects/-/serviceAccounts/" + targetEmail,
                    List.of(), List.of(CLOUD_PLATFORM_SCOPE), Duration.newBuilder().setSeconds(3600).build())
                    .getAccessToken()).startsWith("floci-gcp-impersonated-");
        }
    }

    private Iam iamClient(String bearer) {
        return new Iam.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance(), request -> {
            if (bearer != null) {
                request.getHeaders().setAuthorization("Bearer " + bearer);
            }
        }).setRootUrl(TestFixtures.endpoint() + "/").setApplicationName("floci-iam-enforcement-test").build();
    }

    private Policy policy(String role, String expression) {
        Binding binding = new Binding().setRole(role).setMembers(List.of("serviceAccount:" + callerEmail));
        if (expression != null) {
            binding.setCondition(new Expr().setTitle("test scope").setExpression(expression));
        }
        return new Policy().setVersion(expression == null ? 1 : 3).setBindings(List.of(binding));
    }

    private void setProjectPolicy(String role, String expression) throws Exception {
        String json = new ObjectMapper().writeValueAsString(
                Map.of("policy", policy(role, expression)));
        HttpRequest request = HttpRequest.newBuilder(URI.create(TestFixtures.endpoint()
                        + "/v1/projects/" + project + ":setIamPolicy"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        }
    }

    private void deniedRest(IoCall call) throws Exception {
        if (enforce) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(GoogleJsonResponseException.class, error -> {
                assertThat(error.getStatusCode()).isEqualTo(403);
                assertThat(error.getDetails().get("status")).isEqualTo("PERMISSION_DENIED");
            });
        } else {
            call.run();
        }
    }

    @FunctionalInterface
    private interface IoCall {
        void run() throws IOException;
    }

    private void createTargetAccount() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(TestFixtures.endpoint()
                        + "/v1/projects/" + project + "/serviceAccounts"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"accountId\":\"target\"}"))
                .build();
        HttpResponse<String> response;
        try (HttpClient http = HttpClient.newHttpClient()) {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        }
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }
}
