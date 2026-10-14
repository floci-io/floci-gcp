package io.floci.gcp.test;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.rpc.NotFoundException;
import com.google.api.gax.rpc.PermissionDeniedException;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.run.v2.Container;
import com.google.cloud.run.v2.CreateServiceRequest;
import com.google.cloud.run.v2.DeleteRevisionRequest;
import com.google.cloud.run.v2.DeleteServiceRequest;
import com.google.cloud.run.v2.GetRevisionRequest;
import com.google.cloud.run.v2.GetServiceRequest;
import com.google.cloud.run.v2.ListRevisionsRequest;
import com.google.cloud.run.v2.ListServicesRequest;
import com.google.cloud.run.v2.RevisionsClient;
import com.google.cloud.run.v2.RevisionsSettings;
import com.google.cloud.run.v2.RevisionTemplate;
import com.google.cloud.run.v2.Service;
import com.google.cloud.run.v2.ServicesClient;
import com.google.cloud.run.v2.ServicesSettings;
import com.google.cloud.run.v2.UpdateServiceRequest;
import com.google.gson.JsonParser;
import com.google.iam.v1.Binding;
import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.IAMPolicyGrpc;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import com.google.iam.v1.TestIamPermissionsRequest;
import com.google.protobuf.FieldMask;
import com.google.type.Expr;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.Test;

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

class CloudRunIamEnforcementTest {
    private static final String LOCATION = "us-central1";
    private final boolean enforce = Boolean.parseBoolean(System.getenv("FLOCI_GCP_IAM_TEST_ENFORCEMENT"));

    @Test
    void scopedServiceGrantControlsManagementPolicyAndGrpcMixin() throws Exception {
        String project = TestFixtures.uniqueName("run-iam");
        String parent = "projects/" + project + "/locations/" + LOCATION;
        String allowed = parent + "/services/connector-a";
        String sibling = parent + "/services/unrelated";
        String otherProjectParent = "projects/" + project + "-other/locations/" + LOCATION;
        String otherProjectService = otherProjectParent + "/services/connector-a";
        String email = "reconciler@" + project + ".iam.gserviceaccount.com";
        String token = token(email);
        try (ServicesClient setup = client(null); ServicesClient caller = client(token);
                RevisionsClient revisions = revisionClient(token)) {
            create(setup, parent, "connector-a");
            create(setup, parent, "unrelated");
            create(setup, otherProjectParent, "connector-a");
            String allowedRevision = setup.getService(GetServiceRequest.newBuilder().setName(allowed).build())
                    .getLatestReadyRevision();
            String siblingRevision = setup.getService(GetServiceRequest.newBuilder().setName(sibling).build())
                    .getLatestReadyRevision();
            setup.setIamPolicy(SetIamPolicyRequest.newBuilder().setResource(allowed)
                    .setPolicy(Policy.newBuilder().setVersion(3).addBindings(Binding.newBuilder()
                            .setRole("roles/run.admin").addMembers("serviceAccount:" + email)
                            .setCondition(Expr.newBuilder().setTitle("connector")
                                    .setExpression("resource.name == '" + allowed
                                            + "' || resource.name == '" + allowedRevision + "'")))).build());

            assertThat(caller.getService(GetServiceRequest.newBuilder().setName(allowed).build()).getName())
                    .isEqualTo(allowed);
            assertThat(revisions.getRevision(GetRevisionRequest.newBuilder().setName(allowedRevision).build())
                    .getName()).isEqualTo(allowedRevision);
            assertThat(revisions.listRevisions(ListRevisionsRequest.newBuilder().setParent(allowed).build())
                    .iterateAll()).anyMatch(revision -> revision.getName().equals(allowedRevision));
            assertThat(caller.getIamPolicy(GetIamPolicyRequest.newBuilder().setResource(allowed).build())
                    .getBindingsCount()).isEqualTo(1);
            Policy current = caller.getIamPolicy(GetIamPolicyRequest.newBuilder().setResource(allowed).build());
            assertThat(caller.setIamPolicy(SetIamPolicyRequest.newBuilder().setResource(allowed)
                    .setPolicy(current).build()).getBindingsCount()).isEqualTo(1);
            assertThat(caller.updateServiceAsync(UpdateServiceRequest.newBuilder()
                    .setService(Service.newBuilder().setName(allowed).putLabels("managed", "yes"))
                    .setUpdateMask(FieldMask.newBuilder().addPaths("labels")).build())
                    .get(30, TimeUnit.SECONDS).getLabelsOrThrow("managed")).isEqualTo("yes");
            List<String> requested = List.of("run.services.get", "run.services.setIamPolicy");
            assertThat(caller.testIamPermissions(TestIamPermissionsRequest.newBuilder()
                    .setResource(allowed).addAllPermissions(requested).build()).getPermissionsList())
                    .containsExactlyElementsOf(requested);
            if (enforce) {
                assertThatThrownBy(() -> caller.getService(GetServiceRequest.newBuilder().setName(sibling).build()))
                        .isInstanceOf(PermissionDeniedException.class);
                assertThatThrownBy(() -> caller.getService(GetServiceRequest.newBuilder()
                        .setName(otherProjectService).build())).isInstanceOf(PermissionDeniedException.class);
                assertThatThrownBy(() -> revisions.getRevision(GetRevisionRequest.newBuilder()
                        .setName(siblingRevision).build())).isInstanceOf(PermissionDeniedException.class);
                assertThatThrownBy(() -> revisions.listRevisions(ListRevisionsRequest.newBuilder()
                        .setParent(sibling).build()).iterateAll().iterator().hasNext())
                        .isInstanceOf(PermissionDeniedException.class);
                assertThatThrownBy(() -> caller.setIamPolicy(SetIamPolicyRequest.newBuilder()
                        .setResource(sibling).setPolicy(Policy.getDefaultInstance()).build()))
                        .isInstanceOf(PermissionDeniedException.class);
                assertThatThrownBy(() -> caller.updateServiceAsync(UpdateServiceRequest.newBuilder()
                        .setService(Service.newBuilder().setName(sibling).putLabels("managed", "bad"))
                        .setUpdateMask(FieldMask.newBuilder().addPaths("labels")).build()).get(30, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(PermissionDeniedException.class);
                assertThatThrownBy(() -> caller.deleteServiceAsync(DeleteServiceRequest.newBuilder()
                        .setName(sibling).setValidateOnly(true).build()).get(30, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(PermissionDeniedException.class);
                assertThat(caller.testIamPermissions(TestIamPermissionsRequest.newBuilder()
                        .setResource(sibling).addAllPermissions(requested).build()).getPermissionsList()).isEmpty();
            } else {
                assertThat(caller.getService(GetServiceRequest.newBuilder().setName(sibling).build()).getName())
                        .isEqualTo(sibling);
                assertThat(caller.getService(GetServiceRequest.newBuilder()
                        .setName(otherProjectService).build()).getName()).isEqualTo(otherProjectService);
                assertThat(revisions.getRevision(GetRevisionRequest.newBuilder()
                        .setName(siblingRevision).build()).getName()).isEqualTo(siblingRevision);
                assertThat(revisions.listRevisions(ListRevisionsRequest.newBuilder().setParent(sibling).build())
                        .iterateAll()).anyMatch(revision -> revision.getName().equals(siblingRevision));
                assertThat(caller.setIamPolicy(SetIamPolicyRequest.newBuilder()
                        .setResource(sibling).setPolicy(Policy.getDefaultInstance()).build())
                        .getBindingsCount()).isZero();
                assertThat(caller.updateServiceAsync(UpdateServiceRequest.newBuilder()
                        .setService(Service.newBuilder().setName(sibling).putLabels("managed", "bad"))
                        .setUpdateMask(FieldMask.newBuilder().addPaths("labels")).build())
                        .get(30, TimeUnit.SECONDS).getLabelsOrThrow("managed")).isEqualTo("bad");
                caller.deleteServiceAsync(DeleteServiceRequest.newBuilder()
                        .setName(sibling).setValidateOnly(true).build()).get(30, TimeUnit.SECONDS);
                assertThat(caller.testIamPermissions(TestIamPermissionsRequest.newBuilder()
                        .setResource(sibling).addAllPermissions(requested).build()).getPermissionsList())
                        .containsExactlyElementsOf(requested);
            }
            assertThat(setup.getIamPolicy(GetIamPolicyRequest.newBuilder().setResource(sibling).build())
                    .getBindingsCount()).isZero();
            assertThat(setup.getService(GetServiceRequest.newBuilder().setName(sibling).build()).getName())
                    .isEqualTo(sibling);
            if (enforce) {
                assertThat(setup.getService(GetServiceRequest.newBuilder().setName(sibling).build()).getLabelsMap())
                        .doesNotContainKey("managed");
            }
            grpcPolicyChecks(token, allowed, sibling);
            assertThat(setup.getIamPolicy(GetIamPolicyRequest.newBuilder().setResource(sibling).build())
                    .getBindingsCount()).isZero();

            setup.setIamPolicy(SetIamPolicyRequest.newBuilder().setResource(allowed)
                    .setPolicy(Policy.newBuilder().addBindings(Binding.newBuilder()
                            .setRole("roles/run.viewer").addMembers("serviceAccount:" + email))).build());
            assertThat(caller.getService(GetServiceRequest.newBuilder().setName(allowed).build()).getName())
                    .isEqualTo(allowed);
            if (enforce) {
                assertThatThrownBy(() -> caller.updateServiceAsync(UpdateServiceRequest.newBuilder()
                        .setService(Service.newBuilder().setName(allowed).putLabels("managed", "bad"))
                        .setUpdateMask(FieldMask.newBuilder().addPaths("labels")).build()).get(30, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(PermissionDeniedException.class);
                assertThat(setup.getService(GetServiceRequest.newBuilder().setName(allowed).build())
                        .getLabelsOrThrow("managed")).isEqualTo("yes");
            }
        }
    }

    @Test
    void conditionalCreateUsesLocationParent() throws Exception {
        String project = TestFixtures.uniqueName("run-create");
        String parent = "projects/" + project + "/locations/" + LOCATION;
        String email = "creator@" + project + ".iam.gserviceaccount.com";
        String token = token(email);
        try (ServicesClient setup = client(null); ServicesClient caller = client(token);
                RevisionsClient revisions = revisionClient(token)) {
            projectGrant(project, email, "resource.name == '" + parent + "/services/connector-a'");
            if (enforce) {
                assertThatThrownBy(() -> create(caller, parent, "connector-a"))
                        .hasCauseInstanceOf(PermissionDeniedException.class);
                assertThatThrownBy(() -> setup.getService(GetServiceRequest.newBuilder()
                        .setName(parent + "/services/connector-a").build()))
                        .isInstanceOf(NotFoundException.class);
            } else {
                create(caller, parent, "connector-a");
            }
            projectGrant(project, email, "resource.name == '" + parent + "'");
            create(caller, parent, "connector-b");
            assertThat(caller.listServices(ListServicesRequest.newBuilder().setParent(parent).build())
                    .iterateAll()).anyMatch(service -> service.getName().endsWith("connector-b"));
            assertThat(revisions.listRevisions(ListRevisionsRequest.newBuilder()
                    .setParent(parent + "/services/-").build()).iterateAll())
                    .anyMatch(revision -> revision.getName().startsWith(parent + "/services/connector-b/revisions/"));
            assertThat(setup.getService(GetServiceRequest.newBuilder()
                    .setName(parent + "/services/connector-b").build()).getName())
                    .endsWith("connector-b");
        }
    }

    @Test
    void deleteChecksProtectRevisionsAndServices() throws Exception {
        String project = TestFixtures.uniqueName("run-delete");
        String parent = "projects/" + project + "/locations/" + LOCATION;
        String allowed = parent + "/services/connector-a";
        String sibling = parent + "/services/unrelated";
        String email = "deployer@" + project + ".iam.gserviceaccount.com";
        String token = token(email);
        try (ServicesClient setup = client(null); ServicesClient caller = client(token);
                RevisionsClient setupRevisions = revisionClient(null);
                RevisionsClient revisions = revisionClient(token)) {
            create(setup, parent, "connector-a");
            create(setup, parent, "unrelated");
            String oldRevision = setup.getService(GetServiceRequest.newBuilder().setName(allowed).build())
                    .getLatestReadyRevision();
            String siblingRevision = setup.getService(GetServiceRequest.newBuilder().setName(sibling).build())
                    .getLatestReadyRevision();
            setup.setIamPolicy(SetIamPolicyRequest.newBuilder().setResource(allowed)
                    .setPolicy(Policy.newBuilder().addBindings(Binding.newBuilder()
                            .setRole("roles/run.admin").addMembers("serviceAccount:" + email))).build());

            if (enforce) {
                assertThatThrownBy(() -> revisions.deleteRevisionAsync(DeleteRevisionRequest.newBuilder()
                        .setName(siblingRevision).build()).get(30, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(PermissionDeniedException.class);
                assertThatThrownBy(() -> caller.deleteServiceAsync(DeleteServiceRequest.newBuilder()
                        .setName(sibling).build()).get(30, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(PermissionDeniedException.class);
            }
            assertThat(setupRevisions.getRevision(GetRevisionRequest.newBuilder().setName(siblingRevision).build())
                    .getName()).isEqualTo(siblingRevision);
            assertThat(setup.getService(GetServiceRequest.newBuilder().setName(sibling).build()).getName())
                    .isEqualTo(sibling);

            Service updated = caller.updateServiceAsync(UpdateServiceRequest.newBuilder()
                    .setService(Service.newBuilder().setName(allowed).setTemplate(RevisionTemplate.newBuilder()
                            .addContainers(Container.newBuilder().setImage("nginx:1.27"))))
                    .setUpdateMask(FieldMask.newBuilder().addPaths("template")).build())
                    .get(30, TimeUnit.SECONDS);
            assertThat(updated.getLatestReadyRevision()).isNotEqualTo(oldRevision);
            revisions.deleteRevisionAsync(DeleteRevisionRequest.newBuilder().setName(oldRevision).build())
                    .get(30, TimeUnit.SECONDS);
            assertThatThrownBy(() -> setupRevisions.getRevision(GetRevisionRequest.newBuilder()
                    .setName(oldRevision).build())).isInstanceOf(NotFoundException.class);

            caller.deleteServiceAsync(DeleteServiceRequest.newBuilder().setName(allowed).build())
                    .get(30, TimeUnit.SECONDS);
            assertThatThrownBy(() -> setup.getService(GetServiceRequest.newBuilder().setName(allowed).build()))
                    .isInstanceOf(NotFoundException.class);
            assertThat(setup.getService(GetServiceRequest.newBuilder().setName(sibling).build()).getName())
                    .isEqualTo(sibling);
        }
    }

    private void grpcPolicyChecks(String token, String allowed, String sibling) throws Exception {
        URI endpoint = URI.create(TestFixtures.endpoint());
        ManagedChannel transport = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext().build();
        try {
            Metadata headers = new Metadata();
            headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
            Channel authenticated = ClientInterceptors.intercept(transport,
                    MetadataUtils.newAttachHeadersInterceptor(headers));
            IAMPolicyGrpc.IAMPolicyBlockingStub caller = IAMPolicyGrpc.newBlockingStub(authenticated)
                    .withDeadlineAfter(5, TimeUnit.SECONDS);
            Policy current = caller.getIamPolicy(GetIamPolicyRequest.newBuilder()
                    .setResource(allowed).build());
            assertThat(current.getBindingsCount()).isEqualTo(1);
            assertThat(caller.setIamPolicy(SetIamPolicyRequest.newBuilder()
                    .setResource(allowed).setPolicy(current).build()).getBindingsCount()).isEqualTo(1);
            List<String> requested = List.of("run.services.get", "run.services.setIamPolicy");
            assertThat(caller.testIamPermissions(TestIamPermissionsRequest.newBuilder()
                    .setResource(allowed).addAllPermissions(requested).build()).getPermissionsList())
                    .containsExactlyElementsOf(requested);
            if (enforce) {
                assertThatThrownBy(() -> caller.getIamPolicy(GetIamPolicyRequest.newBuilder()
                        .setResource(sibling).build())).isInstanceOfSatisfying(StatusRuntimeException.class,
                        error -> assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
                assertThatThrownBy(() -> caller.setIamPolicy(SetIamPolicyRequest.newBuilder()
                        .setResource(sibling).setPolicy(current).build()))
                        .isInstanceOfSatisfying(StatusRuntimeException.class,
                                error -> assertThat(error.getStatus().getCode())
                                        .isEqualTo(Status.Code.PERMISSION_DENIED));
                assertThat(caller.testIamPermissions(TestIamPermissionsRequest.newBuilder()
                        .setResource(sibling).addAllPermissions(requested).build()).getPermissionsList()).isEmpty();
            } else {
                assertThat(caller.getIamPolicy(GetIamPolicyRequest.newBuilder()
                        .setResource(sibling).build()).getBindingsCount()).isZero();
                assertThat(caller.setIamPolicy(SetIamPolicyRequest.newBuilder()
                        .setResource(sibling).setPolicy(Policy.getDefaultInstance()).build())
                        .getBindingsCount()).isZero();
                assertThat(caller.testIamPermissions(TestIamPermissionsRequest.newBuilder()
                        .setResource(sibling).addAllPermissions(requested).build()).getPermissionsList())
                        .containsExactlyElementsOf(requested);
            }
        } finally {
            transport.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void projectGrant(String project, String email, String expression) throws Exception {
        String body = "{\"policy\":{\"version\":3,\"bindings\":[{\"role\":\"roles/run.admin\","
                + "\"members\":[\"serviceAccount:" + email + "\"],\"condition\":{\"title\":\"scope\","
                + "\"expression\":\"" + expression + "\"}}]}}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(TestFixtures.endpoint()
                        + "/v1/projects/" + project + ":setIamPolicy"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        }
    }

    private static String token(String email) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(TestFixtures.endpoint()
                        + "/v1/projects/-/serviceAccounts/" + email + ":generateAccessToken"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"scope\":[\"https://www.googleapis.com/auth/cloud-platform\"]}"))
                .build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            return JsonParser.parseString(response.body()).getAsJsonObject().get("accessToken").getAsString();
        }
    }

    private static ServicesClient client(String token) throws Exception {
        ServicesSettings.Builder settings = ServicesSettings.newHttpJsonBuilder().setEndpoint(TestFixtures.endpoint());
        if (token == null) {
            settings.setCredentialsProvider(NoCredentialsProvider.create());
        } else {
            settings.setCredentialsProvider(FixedCredentialsProvider.create(GoogleCredentials.create(
                    new AccessToken(token, Date.from(Instant.now().plusSeconds(300))))));
        }
        return ServicesClient.create(settings.build());
    }

    private static RevisionsClient revisionClient(String token) throws Exception {
        RevisionsSettings.Builder settings = RevisionsSettings.newHttpJsonBuilder()
                .setEndpoint(TestFixtures.endpoint());
        if (token == null) {
            settings.setCredentialsProvider(NoCredentialsProvider.create());
        } else {
            settings.setCredentialsProvider(FixedCredentialsProvider.create(GoogleCredentials.create(
                    new AccessToken(token, Date.from(Instant.now().plusSeconds(300))))));
        }
        return RevisionsClient.create(settings.build());
    }

    private static void create(ServicesClient client, String parent, String id) throws Exception {
        client.createServiceAsync(CreateServiceRequest.newBuilder().setParent(parent).setServiceId(id)
                .setService(Service.newBuilder().setTemplate(RevisionTemplate.newBuilder()
                        .addContainers(Container.newBuilder().setImage("nginx:latest")))).build())
                .get(30, TimeUnit.SECONDS);
    }
}
