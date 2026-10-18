package io.floci.gcp.test;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.api.gax.rpc.PermissionDeniedException;
import com.google.api.services.cloudresourcemanager.CloudResourceManager;
import com.google.api.services.cloudresourcemanager.model.Binding;
import com.google.api.services.cloudresourcemanager.model.Expr;
import com.google.api.services.cloudresourcemanager.model.GetIamPolicyRequest;
import com.google.api.services.cloudresourcemanager.model.Policy;
import com.google.api.services.cloudresourcemanager.model.SetIamPolicyRequest;
import com.google.api.services.cloudresourcemanager.model.TestIamPermissionsRequest;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.secretmanager.v1.AccessSecretVersionRequest;
import com.google.cloud.secretmanager.v1.AddSecretVersionRequest;
import com.google.cloud.secretmanager.v1.CreateSecretRequest;
import com.google.cloud.secretmanager.v1.GetSecretRequest;
import com.google.cloud.secretmanager.v1.Replication;
import com.google.cloud.secretmanager.v1.Secret;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretManagerServiceSettings;
import com.google.cloud.secretmanager.v1.SecretPayload;
import com.google.iam.v1.IAMPolicyGrpc;
import com.google.protobuf.ByteString;
import com.google.gson.JsonParser;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs against disabled mode by default; set FLOCI_GCP_IAM_TEST_ENFORCEMENT=true for an enforcing server. */
class IamEnforcementTest {
    private final boolean enforce = Boolean.parseBoolean(System.getenv("FLOCI_GCP_IAM_TEST_ENFORCEMENT"));
    private String project;
    private String member;
    private String token;
    private CloudResourceManager setup;
    private CloudResourceManager reader;

    @BeforeEach
    void setup() throws Exception {
        project = TestFixtures.uniqueName("iam-sdk");
        String email = "reader@" + project + ".iam.gserviceaccount.com";
        member = "serviceAccount:" + email;
        HttpRequest request = HttpRequest.newBuilder(URI.create(TestFixtures.endpoint()
                        + "/v1/projects/-/serviceAccounts/" + email + ":generateAccessToken"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"scope\":[\"https://www.googleapis.com/auth/cloud-platform\"]}"))
                .build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            token = JsonParser.parseString(response.body()).getAsJsonObject().get("accessToken").getAsString();
        }
        setup = client(null);
        reader = client(token);
    }

    @Test
    void browserGrantAllowsReadingButDeniesPolicyMutationAndSiblingProject() throws Exception {
        setup.projects().setIamPolicy(project, grant(null)).execute();
        assertThat(reader.projects().get(project).execute().getProjectId()).isEqualTo(project);
        assertThat(reader.projects().getIamPolicy(project, new GetIamPolicyRequest()).execute().getBindings())
                .extracting(Binding::getRole).containsExactly("roles/browser");
        denied(() -> reader.projects().setIamPolicy(project, grant(null)).execute());
        denied(() -> reader.projects().get(project + "-sibling").execute());
        List<String> permissions = List.of("resourcemanager.projects.get", "resourcemanager.projects.setIamPolicy");
        assertThat(reader.projects().testIamPermissions(project,
                new TestIamPermissionsRequest().setPermissions(permissions)).execute().getPermissions())
                .containsExactlyElementsOf(enforce ? List.of(permissions.getFirst()) : permissions);
    }

    @Test
    void projectConditionExcludesThenAllowsTheCaller() throws Exception {
        setup.projects().setIamPolicy(project, grant("resource.name == 'projects/excluded'")).execute();
        denied(() -> reader.projects().get(project).execute());
        setup.projects().setIamPolicy(project, grant("resource.name == 'projects/" + project + "'")).execute();
        assertThat(reader.projects().get(project).execute().getProjectId()).isEqualTo(project);
    }

    @Test
    void grpcProjectPoliciesAllowReadsButDenyMutationAndSiblingAccess() throws Exception {
        setup.projects().setIamPolicy(project, grant(null)).execute();
        URI endpoint = URI.create(TestFixtures.endpoint());
        ManagedChannel transport = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext().build();
        try {
            Metadata headers = new Metadata();
            headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
            Channel authenticated = ClientInterceptors.intercept(transport, MetadataUtils.newAttachHeadersInterceptor(headers));
            IAMPolicyGrpc.IAMPolicyBlockingStub caller = IAMPolicyGrpc.newBlockingStub(authenticated)
                    .withDeadlineAfter(5, TimeUnit.SECONDS);
            String resource = "projects/" + project;
            // These protobuf request names collide with the REST SDK request types imported above.
            com.google.iam.v1.GetIamPolicyRequest read = com.google.iam.v1.GetIamPolicyRequest.newBuilder()
                    .setResource(resource).build();
            assertThat(caller.getIamPolicy(read).getBindings(0).getRole()).isEqualTo("roles/browser");
            com.google.iam.v1.SetIamPolicyRequest write = com.google.iam.v1.SetIamPolicyRequest.newBuilder()
                    .setResource(resource).setPolicy(caller.getIamPolicy(read)).build();
            grpcDenied(() -> caller.setIamPolicy(write));
            grpcDenied(() -> caller.getIamPolicy(read.toBuilder().setResource(resource + "-sibling").build()));
            List<String> permissions = List.of("resourcemanager.projects.get", "resourcemanager.projects.setIamPolicy");
            assertThat(caller.testIamPermissions(com.google.iam.v1.TestIamPermissionsRequest.newBuilder()
                    .setResource(resource).addAllPermissions(permissions).build()).getPermissionsList())
                    .containsExactlyElementsOf(enforce ? List.of(permissions.getFirst()) : permissions);
        } finally {
            transport.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void projectPolicyRejectsPublicMembersInEnforceMode() throws Exception {
        for (String publicMember : List.of("allUsers", "allAuthenticatedUsers")) {
            SetIamPolicyRequest policy = grant(null);
            policy.getPolicy().getBindings().getFirst().setMembers(List.of(publicMember));
            if (enforce) {
                assertThatThrownBy(() -> setup.projects().setIamPolicy(project, policy).execute())
                        .isInstanceOfSatisfying(GoogleJsonResponseException.class, error -> {
                            assertThat(error.getStatusCode()).isEqualTo(400);
                            assertThat(error.getDetails().get("status")).isEqualTo("INVALID_ARGUMENT");
                        });
            } else {
                setup.projects().setIamPolicy(project, policy).execute();
            }
        }
    }

    @Test
    void secretManagerSdkEnforcesPayloadAccessOverRestAndGrpc() throws Exception {
        String parent = "projects/" + project;
        URI endpoint = URI.create(TestFixtures.endpoint());
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        ManagedChannel transport = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext().intercept(MetadataUtils.newAttachHeadersInterceptor(headers)).build();
        SecretManagerServiceSettings grpcSettings = SecretManagerServiceSettings.newBuilder()
                .setTransportChannelProvider(FixedTransportChannelProvider.create(GrpcTransportChannel.create(transport)))
                .setCredentialsProvider(NoCredentialsProvider.create()).build();
        GoogleCredentials credentials = GoogleCredentials.create(new AccessToken(token,
                Date.from(Instant.now().plusSeconds(300))));
        SecretManagerServiceSettings restSettings = SecretManagerServiceSettings.newHttpJsonBuilder()
                .setEndpoint(TestFixtures.endpoint())
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build();
        try (SecretManagerServiceClient setupSecrets = TestFixtures.secretManagerClient();
                SecretManagerServiceClient grpc = SecretManagerServiceClient.create(grpcSettings);
                SecretManagerServiceClient rest = SecretManagerServiceClient.create(restSettings)) {
            Secret secret = Secret.newBuilder().setReplication(Replication.newBuilder()
                    .setAutomatic(Replication.Automatic.getDefaultInstance())).build();
            String allowed = setupSecrets.createSecret(CreateSecretRequest.newBuilder()
                    .setParent(parent).setSecretId("allowed").setSecret(secret).build()).getName();
            String sibling = setupSecrets.createSecret(CreateSecretRequest.newBuilder()
                    .setParent(parent).setSecretId("sibling").setSecret(secret).build()).getName();
            String allowedVersion = setupSecrets.addSecretVersion(AddSecretVersionRequest.newBuilder()
                    .setParent(allowed).setPayload(SecretPayload.newBuilder()
                            .setData(ByteString.copyFromUtf8("payload"))).build()).getName();
            String siblingVersion = setupSecrets.addSecretVersion(AddSecretVersionRequest.newBuilder()
                    .setParent(sibling).setPayload(SecretPayload.newBuilder()
                            .setData(ByteString.copyFromUtf8("private"))).build()).getName();
            // The protobuf IAM policy types collide with the REST SDK types imported above.
            setupSecrets.setIamPolicy(com.google.iam.v1.SetIamPolicyRequest.newBuilder().setResource(allowed)
                    .setPolicy(com.google.iam.v1.Policy.newBuilder().addBindings(com.google.iam.v1.Binding.newBuilder()
                            .setRole("roles/secretmanager.secretAccessor").addMembers(member))).build());

            assertThat(rest.accessSecretVersion(AccessSecretVersionRequest.newBuilder()
                    .setName(allowedVersion).build()).getPayload().getData().toStringUtf8()).isEqualTo("payload");
            assertThat(grpc.accessSecretVersion(AccessSecretVersionRequest.newBuilder()
                    .setName(allowedVersion).build()).getPayload().getData().toStringUtf8()).isEqualTo("payload");
            if (enforce) {
                assertThatThrownBy(() -> rest.accessSecretVersion(AccessSecretVersionRequest.newBuilder()
                        .setName(siblingVersion).build())).isInstanceOf(PermissionDeniedException.class);
                assertThatThrownBy(() -> grpc.accessSecretVersion(AccessSecretVersionRequest.newBuilder()
                        .setName(siblingVersion).build())).isInstanceOf(PermissionDeniedException.class);
            } else {
                rest.accessSecretVersion(AccessSecretVersionRequest.newBuilder().setName(siblingVersion).build());
                grpc.accessSecretVersion(AccessSecretVersionRequest.newBuilder().setName(siblingVersion).build());
            }

            com.google.iam.v1.Policy replacement = com.google.iam.v1.Policy.newBuilder()
                    .addBindings(com.google.iam.v1.Binding.newBuilder()
                            .setRole("roles/secretmanager.admin").addMembers(member)).build();
            com.google.iam.v1.SetIamPolicyRequest write = com.google.iam.v1.SetIamPolicyRequest.newBuilder()
                    .setResource(allowed).setPolicy(replacement).build();
            if (enforce) {
                assertThatThrownBy(() -> rest.setIamPolicy(write)).isInstanceOf(PermissionDeniedException.class);
                assertThatThrownBy(() -> grpc.setIamPolicy(write)).isInstanceOf(PermissionDeniedException.class);
                assertThat(setupSecrets.getIamPolicy(com.google.iam.v1.GetIamPolicyRequest.newBuilder()
                        .setResource(allowed).build()).getBindings(0).getRole())
                        .isEqualTo("roles/secretmanager.secretAccessor");
            } else {
                rest.setIamPolicy(write);
                grpc.setIamPolicy(write);
            }

            List<String> requested = List.of("secretmanager.versions.access", "secretmanager.secrets.setIamPolicy");
            List<String> expected = enforce ? List.of(requested.getFirst()) : requested;
            com.google.iam.v1.TestIamPermissionsRequest query = com.google.iam.v1.TestIamPermissionsRequest
                    .newBuilder().setResource(allowed).addAllPermissions(requested).build();
            assertThat(rest.testIamPermissions(query).getPermissionsList()).containsExactlyElementsOf(expected);
            assertThat(grpc.testIamPermissions(query).getPermissionsList()).containsExactlyElementsOf(expected);

            setup.projects().setIamPolicy(project, new SetIamPolicyRequest().setPolicy(new Policy()
                    .setVersion(3).setBindings(List.of(new Binding().setRole("roles/secretmanager.admin")
                            .setMembers(List.of(member)).setCondition(new Expr().setTitle("create")
                                    .setExpression("resource.name == '" + parent + "'")))))).execute();
            assertThat(rest.createSecret(CreateSecretRequest.newBuilder().setParent(parent)
                    .setSecretId("rest-created").setSecret(secret).build()).getName())
                    .isEqualTo(parent + "/secrets/rest-created");
            assertThat(grpc.createSecret(CreateSecretRequest.newBuilder().setParent(parent)
                    .setSecretId("grpc-created").setSecret(secret).build()).getName())
                    .isEqualTo(parent + "/secrets/grpc-created");

            setup.projects().setIamPolicy(project, new SetIamPolicyRequest().setPolicy(new Policy()
                    .setVersion(3).setBindings(List.of(new Binding().setRole("roles/secretmanager.admin")
                            .setMembers(List.of(member)).setCondition(new Expr().setTitle("create")
                                    .setExpression("resource.name == 'projects/excluded'")))))).execute();
            CreateSecretRequest restDeniedCreate = CreateSecretRequest.newBuilder().setParent(parent)
                    .setSecretId("rest-denied").setSecret(secret).build();
            CreateSecretRequest grpcDeniedCreate = CreateSecretRequest.newBuilder().setParent(parent)
                    .setSecretId("grpc-denied").setSecret(secret).build();
            if (enforce) {
                assertThatThrownBy(() -> rest.createSecret(restDeniedCreate))
                        .isInstanceOf(PermissionDeniedException.class);
                assertThatThrownBy(() -> grpc.createSecret(grpcDeniedCreate))
                        .isInstanceOf(PermissionDeniedException.class);
                assertThatThrownBy(() -> setupSecrets.getSecret(GetSecretRequest.newBuilder()
                        .setName(parent + "/secrets/rest-denied").build())).hasMessageContaining("NOT_FOUND");
                assertThatThrownBy(() -> setupSecrets.getSecret(GetSecretRequest.newBuilder()
                        .setName(parent + "/secrets/grpc-denied").build())).hasMessageContaining("NOT_FOUND");
            } else {
                rest.createSecret(restDeniedCreate);
                grpc.createSecret(grpcDeniedCreate);
            }
        } finally {
            transport.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void grpcDenied(Runnable operation) {
        if (enforce) {
            assertThatThrownBy(operation::run).isInstanceOfSatisfying(StatusRuntimeException.class,
                    error -> assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
        } else {
            operation.run();
        }
    }

    private CloudResourceManager client(String token) {
        return new CloudResourceManager.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance(),
                request -> {
                    if (token != null) {
                        request.getHeaders().setAuthorization("Bearer " + token);
                    }
                }).setRootUrl(TestFixtures.endpoint() + "/").setApplicationName("floci-iam-compat").build();
    }

    private SetIamPolicyRequest grant(String expression) {
        Binding binding = new Binding().setRole("roles/browser").setMembers(List.of(member));
        if (expression != null) {
            binding.setCondition(new Expr().setTitle("scope").setExpression(expression));
        }
        return new SetIamPolicyRequest().setPolicy(new Policy().setVersion(expression == null ? 1 : 3)
                .setBindings(List.of(binding)));
    }

    private void denied(IoOperation operation) throws IOException {
        if (enforce) {
            assertThatThrownBy(operation::run).isInstanceOfSatisfying(GoogleJsonResponseException.class,
                    error -> {
                        assertThat(error.getStatusCode()).isEqualTo(403);
                        assertThat(error.getDetails().get("status")).isEqualTo("PERMISSION_DENIED");
                    });
        } else {
            operation.run();
        }
    }

    @FunctionalInterface
    private interface IoOperation {
        void run() throws IOException;
    }
}
