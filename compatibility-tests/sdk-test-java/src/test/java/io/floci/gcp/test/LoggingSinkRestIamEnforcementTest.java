package io.floci.gcp.test;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.cloudresourcemanager.CloudResourceManager;
import com.google.api.services.cloudresourcemanager.model.Project;
import com.google.api.services.cloudresourcemanager.model.ResourceId;
import com.google.api.services.logging.v2.Logging;
import com.google.api.services.logging.v2.model.LogSink;
import com.google.cloud.iam.credentials.v1.IamCredentialsClient;
import com.google.iam.v1.Binding;
import com.google.iam.v1.IAMPolicyGrpc;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import com.google.iam.v1.TestIamPermissionsRequest;
import com.google.protobuf.Duration;
import com.google.type.Expr;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoggingSinkRestIamEnforcementTest {
    private final boolean enforce = Boolean.parseBoolean(System.getenv("FLOCI_GCP_IAM_TEST_ENFORCEMENT"));

    @Test
    void projectSinkMethods() throws Exception {
        exercise(false);
    }

    @Test
    void organizationSinkMethods() throws Exception {
        exercise(true);
    }

    @Test
    void domainScopedProjectSinkMethods() throws Exception {
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
        try {
            Logging admin = client(null);
            Logging caller = client(token(email));
            IAMPolicyGrpc.IAMPolicyBlockingStub policies = IAMPolicyGrpc.newBlockingStub(channel);
            create(admin, false, parent, "allowed");
            policies.setIamPolicy(policy(parent, email, "roles/editor", null));
            assertThat(get(caller, false, allowed).getName()).isEqualTo("allowed");
            assertThat(list(caller, false, parent)).extracting(LogSink::getName).contains("allowed");
            if (enforce) {
                denied(() -> create(caller, false, parent, "editor-denied"));
                denied(() -> update(caller, false, allowed));
                denied(() -> patch(caller, false, allowed));
                denied(() -> delete(caller, false, allowed));
            }
            policies.setIamPolicy(policy(parent, email, "roles/owner", null));
            create(caller, false, parent, "owner");
            assertThat(update(caller, false, parent + "/sinks/owner").getDescription()).isEqualTo("updated");
            assertThat(patch(caller, false, parent + "/sinks/owner").getDescription()).isEqualTo("patched");
            delete(caller, false, parent + "/sinks/owner");
            policies.setIamPolicy(policy(parent, email, "roles/viewer", null));
            assertThat(get(caller, false, allowed).getName()).isEqualTo("allowed");
            assertThat(list(caller, false, parent)).extracting(LogSink::getName).contains("allowed");
            if (enforce) {
                denied(() -> update(caller, false, allowed));
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
        String sinkName = "projects/" + id + "/sinks/allowed";
        String siblingName = "projects/" + id + "/sinks/sibling";
        String email = "reconciler@" + id + ".iam.gserviceaccount.com";
        CloudResourceManager resourceManager = new CloudResourceManager.Builder(new NetHttpTransport(),
                GsonFactory.getDefaultInstance(), ignored -> {}).setRootUrl(TestFixtures.endpoint() + "/")
                .setApplicationName("logging-sink-iam").build();
        resourceManager.projects().create(new Project().setProjectId(id)
                .setParent(new ResourceId().setType("organization").setId(organizationId))).execute();
        Logging admin = client(null);
        Logging caller = client(token(email));
        create(admin, false, "projects/" + id, "allowed");
        create(admin, false, "projects/" + id, "sibling");
        URI endpoint = URI.create(TestFixtures.endpoint());
        ManagedChannel channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext().build();
        try {
            if (enforce) {
                denied(() -> get(caller, false, sinkName));
            }
            IAMPolicyGrpc.newBlockingStub(channel).setIamPolicy(policy(organization, email,
                    "roles/logging.configWriter", "resource.name == '" + sinkName + "'"));
            assertThat(get(caller, false, sinkName).getName()).isEqualTo("allowed");
            if (enforce) {
                denied(() -> get(caller, false, siblingName));
                Metadata headers = new Metadata();
                headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                        "Bearer " + token(email));
                IAMPolicyGrpc.IAMPolicyBlockingStub restricted = IAMPolicyGrpc.newBlockingStub(channel)
                        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
                assertThat(restricted.testIamPermissions(TestIamPermissionsRequest.newBuilder()
                        .setResource(sinkName).addPermissions("logging.sinks.get").build()).getPermissionsList())
                        .containsExactly("logging.sinks.get");
                assertThat(restricted.testIamPermissions(TestIamPermissionsRequest.newBuilder()
                        .setResource(siblingName).addPermissions("logging.sinks.get").build()).getPermissionsList())
                        .isEmpty();
            }
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void exercise(boolean organization) throws Exception {
        exercise(organization, TestFixtures.uniqueName("logging-rest-iam"));
    }

    private void exercise(boolean organization, String id) throws Exception {
        String parent = (organization ? "organizations/" : "projects/") + id;
        String outside = parent + "-other";
        String allowed = parent + "/sinks/allowed";
        String sibling = parent + "/sinks/sibling";
        String outsideSink = outside + "/sinks/outside";
        String credentialProject = id.replace('.', '-').replace(':', '-');
        String email = "reconciler@" + credentialProject + ".iam.gserviceaccount.com";
        URI endpoint = URI.create(TestFixtures.endpoint());
        ManagedChannel channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext().build();
        try {
            Logging admin = client(null);
            Logging caller = client(token(email));
            Logging unbound = client(token("unbound@" + credentialProject + ".iam.gserviceaccount.com"));
            String condition = "resource.service == 'logging.googleapis.com'";
            IAMPolicyGrpc.IAMPolicyBlockingStub policies = IAMPolicyGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(10, TimeUnit.SECONDS);
            Metadata headers = new Metadata();
            headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                    "Bearer " + token(email));
            IAMPolicyGrpc.IAMPolicyBlockingStub restricted = IAMPolicyGrpc.newBlockingStub(channel)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
            policies.setIamPolicy(policy(parent, email, "roles/logging.configWriter",
                    "resource.name == '" + allowed + "'"));
            create(admin, organization, parent, "sibling");
            create(admin, organization, outside, "outside");
            if (enforce) {
                denied(() -> create(caller, organization, parent, "condition-denied"));
                denied(() -> list(caller, organization, parent));
                denied(() -> get(caller, organization, sibling));
                assertThat(list(admin, organization, parent)).extracting(LogSink::getName)
                        .doesNotContain("condition-denied");
            }
            create(caller, organization, parent, "allowed");
            if (enforce) {
                assertThat(restricted.testIamPermissions(TestIamPermissionsRequest.newBuilder()
                        .setResource(allowed).addPermissions("logging.sinks.get").build()).getPermissionsList())
                        .containsExactly("logging.sinks.get");
                assertThat(restricted.testIamPermissions(TestIamPermissionsRequest.newBuilder()
                        .setResource(sibling).addPermissions("logging.sinks.get").build()).getPermissionsList())
                        .isEmpty();
            }
            assertThat(get(caller, organization, allowed).getName()).isEqualTo("allowed");
            assertThat(update(caller, organization, allowed).getDescription()).isEqualTo("updated");
            assertThat(patch(caller, organization, allowed).getDescription()).isEqualTo("patched");
            delete(caller, organization, allowed);
            create(admin, organization, parent, "allowed");
            policies.setIamPolicy(policy(parent, email, "roles/logging.configWriter",
                    "resource.name == '" + parent + "'"));
            assertThat(list(caller, organization, parent)).extracting(LogSink::getName).contains("allowed");
            if (enforce) {
                denied(() -> create(caller, organization, parent, "parent-denied"));
                denied(() -> get(caller, organization, allowed));
            }
            policies.setIamPolicy(policy(parent, email, "roles/logging.configWriter", condition));
            if (enforce) {
                denied(() -> create(unbound, organization, parent, "unbound"));
                denied(() -> list(unbound, organization, parent));
                denied(() -> create(caller, organization, outside, "outside"));
                denied(() -> list(caller, organization, outside));
                assertThatThrownBy(() -> caller.folders().sinks().create("folders/" + id,
                        sink("unsupported")).execute()).isInstanceOf(GoogleJsonResponseException.class)
                        .satisfies(error -> assertThat(((GoogleJsonResponseException) error).getStatusCode())
                                .isEqualTo(400));
                assertThatThrownBy(() -> caller.billingAccounts().sinks().create("billingAccounts/" + id,
                        sink("unsupported")).execute()).isInstanceOf(GoogleJsonResponseException.class)
                        .satisfies(error -> assertThat(((GoogleJsonResponseException) error).getStatusCode())
                                .isEqualTo(400));
                assertThat(list(admin, organization, parent)).extracting(LogSink::getName)
                        .doesNotContain("unbound");
            }
            assertThat(get(caller, organization, allowed).getName()).isEqualTo("allowed");
            assertThat(list(caller, organization, parent)).extracting(LogSink::getName).contains("allowed");
            if (enforce) {
                Policy policyBeforeDenials = policies.getIamPolicy(com.google.iam.v1.GetIamPolicyRequest.newBuilder()
                        .setResource(parent).build());
                LogSink before = get(admin, organization, outsideSink);
                denied(() -> get(unbound, organization, allowed));
                denied(() -> get(caller, organization, outsideSink));
                denied(() -> update(caller, organization, outsideSink));
                denied(() -> patch(caller, organization, outsideSink));
                denied(() -> delete(caller, organization, outsideSink));
                denied(() -> update(unbound, organization, allowed));
                denied(() -> delete(unbound, organization, allowed));
                assertThat(get(admin, organization, outsideSink)).isEqualTo(before);
                assertThat(get(admin, organization, allowed).getDescription()).isNull();
                assertThat(policies.getIamPolicy(com.google.iam.v1.GetIamPolicyRequest.newBuilder()
                        .setResource(parent).build())).isEqualTo(policyBeforeDenials);
                assertThat(restricted.testIamPermissions(TestIamPermissionsRequest.newBuilder().setResource(allowed)
                        .addAllPermissions(List.of("logging.sinks.get", "logging.sinks.update", "logging.sinks.delete",
                                "logging.sinks.unknown")).build()).getPermissionsList())
                        .containsExactlyInAnyOrder("logging.sinks.get", "logging.sinks.update", "logging.sinks.delete");
                assertThat(restricted.testIamPermissions(TestIamPermissionsRequest.newBuilder().setResource(sibling)
                        .addPermissions("logging.sinks.get").build()).getPermissionsList())
                        .containsExactly("logging.sinks.get");
                assertThat(restricted.testIamPermissions(TestIamPermissionsRequest.newBuilder().setResource(outsideSink)
                        .addPermissions("logging.sinks.get").build()).getPermissionsList()).isEmpty();
                if (organization || id.contains(":")) {
                    assertThatThrownBy(() -> restricted.getIamPolicy(com.google.iam.v1.GetIamPolicyRequest
                            .newBuilder().setResource(parent).build())).isInstanceOf(StatusRuntimeException.class)
                            .satisfies(error -> assertThat(((StatusRuntimeException) error).getStatus().getCode())
                                    .isEqualTo(Status.Code.FAILED_PRECONDITION));
                }
                assertThatThrownBy(() -> restricted.setIamPolicy(policy(parent, email,
                        "roles/logging.admin", null))).isInstanceOf(StatusRuntimeException.class)
                        .satisfies(error -> assertThat(((StatusRuntimeException) error).getStatus().getCode())
                                .isEqualTo(organization || id.contains(":") ? Status.Code.FAILED_PRECONDITION
                                        : Status.Code.PERMISSION_DENIED));
                assertThat(policies.getIamPolicy(com.google.iam.v1.GetIamPolicyRequest.newBuilder()
                        .setResource(parent).build())).isEqualTo(policyBeforeDenials);
                policies.setIamPolicy(policy(parent, email, "roles/logging.viewer", null));
                assertThat(get(caller, organization, allowed).getName()).isEqualTo("allowed");
                denied(() -> update(caller, organization, allowed));
                denied(() -> patch(caller, organization, allowed));
                denied(() -> delete(caller, organization, allowed));
                policies.setIamPolicy(policy(parent, email, "roles/logging.privateLogViewer", null));
                assertThat(get(caller, organization, allowed).getName()).isEqualTo("allowed");
                assertThat(list(caller, organization, parent)).extracting(LogSink::getName).contains("allowed");
                denied(() -> create(caller, organization, parent, "private-viewer-denied"));
                denied(() -> update(caller, organization, allowed));
                denied(() -> patch(caller, organization, allowed));
                denied(() -> delete(caller, organization, allowed));
                assertThat(restricted.testIamPermissions(TestIamPermissionsRequest.newBuilder()
                        .setResource(allowed).addAllPermissions(List.of("logging.sinks.get",
                                "logging.sinks.update")).build()).getPermissionsList())
                        .containsExactly("logging.sinks.get");
                policies.setIamPolicy(policy(parent, email, "roles/logging.configWriter", condition));
            }
            assertThat(get(caller, organization, sibling).getName()).isEqualTo("sibling");
            assertThat(update(caller, organization, allowed).getDescription()).isEqualTo("updated");
            assertThat(patch(caller, organization, allowed).getDescription()).isEqualTo("patched");
            delete(caller, organization, allowed);
            delete(caller, organization, sibling);
            delete(admin, organization, outsideSink);
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static Logging client(String credential) {
        return new Logging.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance(), request -> {
            if (credential != null) {
                request.getHeaders().setAuthorization("Bearer " + credential);
            }
        }).setRootUrl(TestFixtures.endpoint() + "/").setApplicationName("logging-sink-iam").build();
    }

    private static LogSink sink(String id) {
        return new LogSink().setName(id).setDestination("pubsub.googleapis.com/projects/destination/topics/sink");
    }

    private static LogSink create(Logging client, boolean organization, String parent, String id) throws Exception {
        return organization ? client.organizations().sinks().create(parent, sink(id))
                .setUniqueWriterIdentity(true).execute()
                : client.projects().sinks().create(parent, sink(id)).setUniqueWriterIdentity(true).execute();
    }

    private static LogSink get(Logging client, boolean organization, String name) throws Exception {
        return organization ? client.organizations().sinks().get(name).execute()
                : client.projects().sinks().get(name).execute();
    }

    private static List<LogSink> list(Logging client, boolean organization, String parent) throws Exception {
        List<LogSink> sinks = organization ? client.organizations().sinks().list(parent).execute().getSinks()
                : client.projects().sinks().list(parent).execute().getSinks();
        return sinks == null ? List.of() : sinks;
    }

    private static LogSink update(Logging client, boolean organization, String name) throws Exception {
        LogSink value = new LogSink().setDescription("updated");
        return organization ? client.organizations().sinks().update(name, value).setUpdateMask("description")
                .setUniqueWriterIdentity(true).execute()
                : client.projects().sinks().update(name, value).setUpdateMask("description")
                        .setUniqueWriterIdentity(true).execute();
    }

    private static LogSink patch(Logging client, boolean organization, String name) throws Exception {
        LogSink value = new LogSink().setDescription("patched");
        return organization ? client.organizations().sinks().patch(name, value).setUpdateMask("description")
                .setUniqueWriterIdentity(true).execute()
                : client.projects().sinks().patch(name, value).setUpdateMask("description")
                        .setUniqueWriterIdentity(true).execute();
    }

    private static void delete(Logging client, boolean organization, String name) throws Exception {
        if (organization) {
            client.organizations().sinks().delete(name).execute();
        } else {
            client.projects().sinks().delete(name).execute();
        }
    }

    private static void denied(CheckedOperation operation) {
        assertThatThrownBy(operation::run).isInstanceOf(GoogleJsonResponseException.class)
                .satisfies(error -> {
                    GoogleJsonResponseException response = (GoogleJsonResponseException) error;
                    assertThat(response.getStatusCode()).isEqualTo(403);
                    assertThat(response.getContent()).contains("PERMISSION_DENIED");
                    assertThat(response.getContent()).contains("IAM_PERMISSION_DENIED");
                    assertThat(response.getContent()).contains("iam.googleapis.com");
                });
    }

    private static SetIamPolicyRequest policy(String parent, String email, String role, String expression) {
        Binding.Builder binding = Binding.newBuilder().setRole(role).addMembers("serviceAccount:" + email);
        if (expression != null) {
            binding.setCondition(Expr.newBuilder().setTitle("sink scope").setExpression(expression));
        }
        return SetIamPolicyRequest.newBuilder().setResource(parent)
                .setPolicy(Policy.newBuilder().setVersion(expression == null ? 1 : 3).addBindings(binding)).build();
    }

    private static String token(String email) throws Exception {
        try (IamCredentialsClient credentials = TestFixtures.iamCredentialsClient()) {
            return credentials.generateAccessToken("projects/-/serviceAccounts/" + email, List.of(),
                    List.of("https://www.googleapis.com/auth/cloud-platform"),
                    Duration.newBuilder().setSeconds(300).build()).getAccessToken();
        }
    }

    @FunctionalInterface
    private interface CheckedOperation {
        void run() throws Exception;
    }
}
