package io.floci.gcp.test;

import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.InstantiatingGrpcChannelProvider;
import com.google.api.gax.rpc.FailedPreconditionException;
import com.google.api.gax.rpc.PermissionDeniedException;
import com.google.api.services.cloudresourcemanager.CloudResourceManager;
import com.google.api.services.cloudresourcemanager.model.Project;
import com.google.api.services.cloudresourcemanager.model.ResourceId;
import com.google.cloud.iam.credentials.v1.IamCredentialsClient;
import com.google.cloud.logging.v2.ConfigClient;
import com.google.cloud.logging.v2.ConfigSettings;
import com.google.iam.v1.Binding;
import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.IAMPolicyGrpc;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import com.google.iam.v1.TestIamPermissionsRequest;
import com.google.logging.v2.CreateSinkRequest;
import com.google.logging.v2.GetSinkRequest;
import com.google.logging.v2.ListSinksRequest;
import com.google.logging.v2.LogSink;
import com.google.logging.v2.UpdateSinkRequest;
import com.google.protobuf.Duration;
import com.google.protobuf.FieldMask;
import com.google.type.Expr;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoggingSinkIamEnforcementTest {
    private final boolean enforce = Boolean.parseBoolean(System.getenv("FLOCI_GCP_IAM_TEST_ENFORCEMENT"));

    @Test
    void projectSinkPermissionsAndConditions() throws Exception {
        exercise(false);
    }

    @Test
    void organizationSinkPermissionsAndConditions() throws Exception {
        exercise(true);
    }

    @Test
    void domainScopedProjectSinkPermissionsAndConditions() throws Exception {
        exercise(false, "example.com:" + TestFixtures.uniqueName("logging-domain"));
    }

    @Test
    void projectBasicRolesFollowPublishedSinkPermissions() throws Exception {
        String id = TestFixtures.uniqueName("logging-basic");
        String parent = "projects/" + id;
        String allowed = parent + "/sinks/allowed";
        String email = "reconciler@" + id + ".iam.gserviceaccount.com";
        URI endpoint = URI.create(TestFixtures.endpoint());
        ManagedChannel channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext().build();
        try (ConfigClient admin = client(null); ConfigClient caller = client(token(email))) {
            IAMPolicyGrpc.IAMPolicyBlockingStub policies = IAMPolicyGrpc.newBlockingStub(channel);
            admin.createSink(create(parent, "allowed"));
            policies.setIamPolicy(policy(parent, email, "roles/editor", null));
            assertThat(caller.getSink(GetSinkRequest.newBuilder().setSinkName(allowed).build()).getName())
                    .isEqualTo("allowed");
            assertThat(caller.listSinks(ListSinksRequest.newBuilder().setParent(parent).build()).iterateAll())
                    .anyMatch(sink -> sink.getName().equals("allowed"));
            if (enforce) {
                assertThatThrownBy(() -> caller.createSink(create(parent, "editor-denied")))
                        .isInstanceOf(PermissionDeniedException.class);
                assertThatThrownBy(() -> caller.updateSink(update(allowed)))
                        .isInstanceOf(PermissionDeniedException.class);
                assertThatThrownBy(() -> caller.deleteSink(allowed))
                        .isInstanceOf(PermissionDeniedException.class);
            }
            policies.setIamPolicy(policy(parent, email, "roles/owner", null));
            caller.createSink(create(parent, "owner"));
            assertThat(caller.updateSink(update(parent + "/sinks/owner")).getDescription()).isEqualTo("updated");
            caller.deleteSink(parent + "/sinks/owner");
            policies.setIamPolicy(policy(parent, email, "roles/viewer", null));
            assertThat(caller.getSink(GetSinkRequest.newBuilder().setSinkName(allowed).build()).getName())
                    .isEqualTo("allowed");
            if (enforce) {
                assertThatThrownBy(() -> caller.updateSink(update(allowed)))
                        .isInstanceOf(PermissionDeniedException.class);
            }
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void projectSinkInheritsRecordedOrganizationPolicy() throws Exception {
        String id = TestFixtures.uniqueName("logging-inherit");
        String organizationId = Long.toString(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000_000L) + 1);
        String organization = "organizations/" + organizationId;
        String parent = "projects/" + id;
        String allowed = parent + "/sinks/allowed";
        String sibling = parent + "/sinks/sibling";
        String email = "reconciler@" + id + ".iam.gserviceaccount.com";
        CloudResourceManager resourceManager = new CloudResourceManager.Builder(new NetHttpTransport(),
                GsonFactory.getDefaultInstance(), ignored -> {}).setRootUrl(TestFixtures.endpoint() + "/")
                .setApplicationName("logging-sink-iam").build();
        resourceManager.projects().create(new Project().setProjectId(id)
                .setParent(new ResourceId().setType("organization").setId(organizationId))).execute();
        URI endpoint = URI.create(TestFixtures.endpoint());
        ManagedChannel channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext().build();
        try (ConfigClient admin = client(null); ConfigClient caller = client(token(email))) {
            admin.createSink(create(parent, "allowed"));
            admin.createSink(create(parent, "sibling"));
            if (enforce) {
                assertThatThrownBy(() -> caller.getSink(GetSinkRequest.newBuilder().setSinkName(allowed).build()))
                        .isInstanceOf(PermissionDeniedException.class);
            }
            IAMPolicyGrpc.newBlockingStub(channel).setIamPolicy(policy(organization, email,
                    "roles/logging.configWriter", "resource.name == '" + allowed + "'"));
            assertThat(caller.getSink(GetSinkRequest.newBuilder().setSinkName(allowed).build()).getName())
                    .isEqualTo("allowed");
            if (enforce) {
                assertThatThrownBy(() -> caller.getSink(GetSinkRequest.newBuilder().setSinkName(sibling).build()))
                        .isInstanceOf(PermissionDeniedException.class);
                Metadata headers = new Metadata();
                headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                        "Bearer " + token(email));
                IAMPolicyGrpc.IAMPolicyBlockingStub restricted = IAMPolicyGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
                assertThat(restricted.testIamPermissions(TestIamPermissionsRequest.newBuilder()
                        .setResource(allowed).addPermissions("logging.sinks.get").build()).getPermissionsList())
                        .containsExactly("logging.sinks.get");
                assertThat(restricted.testIamPermissions(TestIamPermissionsRequest.newBuilder()
                        .setResource(sibling).addPermissions("logging.sinks.get").build()).getPermissionsList())
                        .isEmpty();
            }
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void exercise(boolean organization) throws Exception {
        exercise(organization, TestFixtures.uniqueName("logging-sink-iam"));
    }

    private void exercise(boolean organization, String id) throws Exception {
        String parent = organization ? "organizations/" + id : "projects/" + id;
        String outside = organization ? "organizations/" + id + "-other" : "projects/" + id + "-other";
        String allowed = parent + "/sinks/allowed";
        String sibling = parent + "/sinks/sibling";
        String outsideSink = outside + "/sinks/outside";
        String credentialProject = id.replace('.', '-').replace(':', '-');
        String email = "reconciler@" + credentialProject + ".iam.gserviceaccount.com";
        String unbound = "unbound@" + credentialProject + ".iam.gserviceaccount.com";
        URI endpoint = URI.create(TestFixtures.endpoint());
        ManagedChannel channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
        try (ConfigClient admin = client(null);
                ConfigClient caller = client(token(email));
                ConfigClient stranger = client(token(unbound))) {
            IAMPolicyGrpc.IAMPolicyBlockingStub policies = IAMPolicyGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(10, TimeUnit.SECONDS);
            Metadata metadata = new Metadata();
            metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                    "Bearer " + token(email));
            IAMPolicyGrpc.IAMPolicyBlockingStub restricted = IAMPolicyGrpc.newBlockingStub(channel)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
            String expression = "resource.service == 'logging.googleapis.com'";
            policies.setIamPolicy(policy(parent, email, "roles/logging.configWriter",
                    "resource.name == '" + allowed + "'"));
            admin.createSink(create(parent, "sibling"));
            admin.createSink(create(outside, "outside"));
            if (enforce) {
                assertThatThrownBy(() -> caller.createSink(create(parent, "condition-denied")))
                        .isInstanceOf(PermissionDeniedException.class);
                assertThatThrownBy(() -> caller.listSinks(ListSinksRequest.newBuilder().setParent(parent).build())
                        .iterateAll().iterator().hasNext()).isInstanceOf(PermissionDeniedException.class);
                assertThatThrownBy(() -> caller.getSink(GetSinkRequest.newBuilder().setSinkName(sibling).build()))
                        .isInstanceOf(PermissionDeniedException.class);
                assertThat(admin.listSinks(ListSinksRequest.newBuilder().setParent(parent).build()).iterateAll())
                        .noneMatch(sink -> sink.getName().equals("condition-denied"));
            }
            caller.createSink(create(parent, "allowed"));
            if (enforce) {
                assertThat(restricted.testIamPermissions(TestIamPermissionsRequest.newBuilder()
                        .setResource(allowed).addPermissions("logging.sinks.get").build()).getPermissionsList())
                        .containsExactly("logging.sinks.get");
                assertThat(restricted.testIamPermissions(TestIamPermissionsRequest.newBuilder()
                        .setResource(sibling).addPermissions("logging.sinks.get").build()).getPermissionsList())
                        .isEmpty();
            }
            assertThat(caller.getSink(GetSinkRequest.newBuilder().setSinkName(allowed).build()).getName())
                    .isEqualTo("allowed");
            assertThat(caller.updateSink(update(allowed)).getDescription()).isEqualTo("updated");
            caller.deleteSink(allowed);
            admin.createSink(create(parent, "allowed"));
            policies.setIamPolicy(policy(parent, email, "roles/logging.configWriter",
                    "resource.name == '" + parent + "'"));
            assertThat(caller.listSinks(ListSinksRequest.newBuilder().setParent(parent).build()).iterateAll())
                    .anyMatch(sink -> sink.getName().equals("allowed"));
            if (enforce) {
                assertThatThrownBy(() -> caller.createSink(create(parent, "parent-denied")))
                        .isInstanceOf(PermissionDeniedException.class);
                assertThatThrownBy(() -> caller.getSink(GetSinkRequest.newBuilder().setSinkName(allowed).build()))
                        .isInstanceOf(PermissionDeniedException.class);
            }
            policies.setIamPolicy(policy(parent, email, "roles/logging.configWriter", expression));
            if (enforce) {
                assertThatThrownBy(() -> stranger.createSink(create(parent, "unbound")))
                        .isInstanceOf(PermissionDeniedException.class)
                        .satisfies(error -> {
                            com.google.rpc.Status status = StatusProto.fromThrowable(error);
                            assertThat(status).isNotNull();
                            assertThat(status.getCode()).isEqualTo(Status.Code.PERMISSION_DENIED.value());
                            assertThat(status.getDetailsCount()).isEqualTo(1);
                            assertThat(status.getDetails(0).getTypeUrl())
                                    .isEqualTo("type.googleapis.com/google.rpc.ErrorInfo");
                        });
                assertThatThrownBy(() -> stranger.listSinks(ListSinksRequest.newBuilder().setParent(parent).build())
                        .iterateAll().iterator().hasNext()).isInstanceOf(PermissionDeniedException.class);
                assertThatThrownBy(() -> caller.createSink(create(outside, "outside")))
                        .isInstanceOf(PermissionDeniedException.class);
                assertThatThrownBy(() -> caller.listSinks(ListSinksRequest.newBuilder().setParent(outside).build())
                        .iterateAll().iterator().hasNext()).isInstanceOf(PermissionDeniedException.class);
                assertThatThrownBy(() -> caller.createSink(create("folders/" + id, "unsupported")))
                        .isInstanceOf(FailedPreconditionException.class);
                assertThatThrownBy(() -> caller.createSink(create("billingAccounts/" + id, "unsupported")))
                        .isInstanceOf(FailedPreconditionException.class);
                assertThat(admin.listSinks(ListSinksRequest.newBuilder().setParent(parent).build()).iterateAll())
                        .noneMatch(sink -> sink.getName().equals("unbound"));
            }
            assertThat(caller.getSink(GetSinkRequest.newBuilder().setSinkName(allowed).build()).getName())
                    .isEqualTo("allowed");
            assertThat(caller.listSinks(ListSinksRequest.newBuilder().setParent(parent).build()).iterateAll())
                    .anyMatch(sink -> sink.getName().equals("allowed"));
            if (enforce) {
                Policy policyBeforeDenials = policies.getIamPolicy(GetIamPolicyRequest.newBuilder()
                        .setResource(parent).build());
                assertThatThrownBy(() -> policies.getIamPolicy(GetIamPolicyRequest.newBuilder()
                        .setResource(allowed).build())).isInstanceOf(StatusRuntimeException.class)
                        .satisfies(error -> assertThat(((StatusRuntimeException) error).getStatus().getCode())
                                .isEqualTo(Status.Code.FAILED_PRECONDITION));
                assertThatThrownBy(() -> policies.setIamPolicy(policy(allowed, email,
                        "roles/logging.admin", null))).isInstanceOf(StatusRuntimeException.class)
                        .satisfies(error -> assertThat(((StatusRuntimeException) error).getStatus().getCode())
                                .isEqualTo(Status.Code.FAILED_PRECONDITION));
                LogSink before = admin.getSink(GetSinkRequest.newBuilder().setSinkName(outsideSink).build());
                assertThatThrownBy(() -> caller.getSink(GetSinkRequest.newBuilder().setSinkName(outsideSink).build()))
                        .isInstanceOf(PermissionDeniedException.class);
                assertThatThrownBy(() -> caller.updateSink(update(outsideSink)))
                        .isInstanceOf(PermissionDeniedException.class);
                assertThatThrownBy(() -> caller.deleteSink(outsideSink))
                        .isInstanceOf(PermissionDeniedException.class);
                assertThatThrownBy(() -> stranger.getSink(GetSinkRequest.newBuilder().setSinkName(allowed).build()))
                        .isInstanceOf(PermissionDeniedException.class);
                assertThatThrownBy(() -> stranger.updateSink(update(allowed)))
                        .isInstanceOf(PermissionDeniedException.class);
                assertThatThrownBy(() -> stranger.deleteSink(allowed))
                        .isInstanceOf(PermissionDeniedException.class);
                assertThat(admin.getSink(GetSinkRequest.newBuilder().setSinkName(outsideSink).build())).isEqualTo(before);
                assertThat(admin.getSink(GetSinkRequest.newBuilder().setSinkName(allowed).build()).getDescription())
                        .isEmpty();
                assertThat(policies.getIamPolicy(GetIamPolicyRequest.newBuilder()
                        .setResource(parent).build())).isEqualTo(policyBeforeDenials);
                assertThat(restricted.testIamPermissions(TestIamPermissionsRequest.newBuilder()
                        .setResource(allowed).addAllPermissions(List.of("logging.sinks.get", "logging.sinks.update",
                                "logging.sinks.delete", "logging.sinks.unknown")).build()).getPermissionsList())
                        .containsExactlyInAnyOrder("logging.sinks.get", "logging.sinks.update", "logging.sinks.delete");
                assertThat(restricted.testIamPermissions(TestIamPermissionsRequest.newBuilder()
                        .setResource(sibling).addPermissions("logging.sinks.get").build()).getPermissionsList())
                        .containsExactly("logging.sinks.get");
                assertThat(restricted.testIamPermissions(TestIamPermissionsRequest.newBuilder()
                        .setResource(outsideSink).addPermissions("logging.sinks.get").build()).getPermissionsList())
                        .isEmpty();
                if (organization || id.contains(":")) {
                    assertThatThrownBy(() -> restricted.getIamPolicy(GetIamPolicyRequest
                            .newBuilder().setResource(parent).build())).isInstanceOf(StatusRuntimeException.class)
                            .satisfies(error -> assertThat(((StatusRuntimeException) error).getStatus().getCode())
                                    .isEqualTo(Status.Code.FAILED_PRECONDITION));
                }
                assertThatThrownBy(() -> restricted.setIamPolicy(policy(parent, email,
                        "roles/logging.admin", null))).isInstanceOf(StatusRuntimeException.class)
                        .satisfies(error -> assertThat(((StatusRuntimeException) error).getStatus().getCode())
                                .isEqualTo(organization || id.contains(":") ? Status.Code.FAILED_PRECONDITION
                                        : Status.Code.PERMISSION_DENIED));
                assertThat(policies.getIamPolicy(GetIamPolicyRequest.newBuilder()
                        .setResource(parent).build())).isEqualTo(policyBeforeDenials);
                policies.setIamPolicy(policy(parent, email, "roles/logging.viewer", null));
                assertThat(caller.getSink(GetSinkRequest.newBuilder().setSinkName(allowed).build()).getName())
                        .isEqualTo("allowed");
                assertThatThrownBy(() -> caller.updateSink(update(allowed)))
                        .isInstanceOf(PermissionDeniedException.class);
                assertThatThrownBy(() -> caller.deleteSink(allowed))
                        .isInstanceOf(PermissionDeniedException.class);
                policies.setIamPolicy(policy(parent, email, "roles/logging.privateLogViewer", null));
                assertThat(caller.getSink(GetSinkRequest.newBuilder().setSinkName(allowed).build()).getName())
                        .isEqualTo("allowed");
                assertThat(caller.listSinks(ListSinksRequest.newBuilder().setParent(parent).build()).iterateAll())
                        .anyMatch(sink -> sink.getName().equals("allowed"));
                assertThatThrownBy(() -> caller.createSink(create(parent, "private-viewer-denied")))
                        .isInstanceOf(PermissionDeniedException.class);
                assertThatThrownBy(() -> caller.updateSink(update(allowed)))
                        .isInstanceOf(PermissionDeniedException.class);
                assertThat(restricted.testIamPermissions(TestIamPermissionsRequest.newBuilder()
                        .setResource(allowed).addAllPermissions(List.of("logging.sinks.get",
                                "logging.sinks.update")).build()).getPermissionsList())
                        .containsExactly("logging.sinks.get");
                policies.setIamPolicy(policy(parent, email, "roles/logging.configWriter", expression));
            }
            assertThat(caller.getSink(GetSinkRequest.newBuilder().setSinkName(sibling).build()).getName())
                    .isEqualTo("sibling");
            assertThat(caller.updateSink(update(allowed)).getDescription()).isEqualTo("updated");
            caller.deleteSink(allowed);
            caller.deleteSink(sibling);
            admin.deleteSink(outsideSink);
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static CreateSinkRequest create(String parent, String id) {
        return CreateSinkRequest.newBuilder().setParent(parent).setUniqueWriterIdentity(true)
                .setSink(LogSink.newBuilder().setName(id)
                        .setDestination("pubsub.googleapis.com/projects/destination/topics/sink")).build();
    }

    private static UpdateSinkRequest update(String name) {
        return UpdateSinkRequest.newBuilder().setSinkName(name)
                .setSink(LogSink.newBuilder().setDescription("updated"))
                .setUpdateMask(FieldMask.newBuilder().addPaths("description")).setUniqueWriterIdentity(true).build();
    }

    private static SetIamPolicyRequest policy(String parent, String email, String role, String expression) {
        Binding.Builder binding = Binding.newBuilder().setRole(role).addMembers("serviceAccount:" + email);
        if (expression != null) {
            binding.setCondition(Expr.newBuilder().setTitle("sink scope").setExpression(expression));
        }
        return SetIamPolicyRequest.newBuilder().setResource(parent)
                .setPolicy(Policy.newBuilder().setVersion(expression == null ? 1 : 3).addBindings(binding)).build();
    }

    private static ConfigClient client(String credential) throws Exception {
        URI endpoint = URI.create(TestFixtures.endpoint());
        Metadata metadata = new Metadata();
        if (credential != null) {
            metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + credential);
        }
        ConfigSettings.Builder settings = ConfigSettings.newBuilder().setTransportChannelProvider(
                InstantiatingGrpcChannelProvider.newBuilder()
                        .setEndpoint(endpoint.getHost() + ":" + endpoint.getPort())
                        .setChannelConfigurator(builder -> builder.usePlaintext()
                                .intercept(MetadataUtils.newAttachHeadersInterceptor(metadata))).build());
        settings.setCredentialsProvider(NoCredentialsProvider.create());
        return ConfigClient.create(settings.build());
    }

    private static String token(String email) throws Exception {
        try (IamCredentialsClient credentials = TestFixtures.iamCredentialsClient()) {
            return credentials.generateAccessToken("projects/-/serviceAccounts/" + email, List.of(),
                    List.of("https://www.googleapis.com/auth/cloud-platform"),
                    Duration.newBuilder().setSeconds(300).build()).getAccessToken();
        }
    }
}
