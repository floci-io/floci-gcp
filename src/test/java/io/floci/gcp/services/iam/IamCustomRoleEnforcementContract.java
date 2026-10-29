package io.floci.gcp.services.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.iam.v1.Binding;
import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.IAMPolicyGrpc;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import com.google.iam.v1.TestIamPermissionsRequest;
import com.google.pubsub.v1.DeleteTopicRequest;
import com.google.pubsub.v1.GetTopicRequest;
import com.google.pubsub.v1.PublisherGrpc;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.credentials.StoredCredentialToken;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.floci.gcp.services.iam.model.StoredRole;
import io.floci.gcp.services.pubsub.PubSubService;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Shared expectations; denials apply only when {@link #enforced()} and succeed otherwise. */
abstract class IamCustomRoleEnforcementContract {

    @Inject
    IamService policies;
    @Inject
    IamRoleService roles;
    @Inject
    PubSubService pubsub;
    @Inject
    CredentialTokenService tokens;
    @Inject
    StorageFactory storageFactory;
    @TestHTTPResource
    URI endpoint;

    String projectId;
    String project;
    String member;
    String credential;
    ManagedChannel channel;
    Channel caller;

    abstract boolean enforced();

    @BeforeEach
    void setUp() {
        projectId = "cr-" + UUID.randomUUID().toString().substring(0, 8);
        project = "projects/" + projectId;
        String email = "caller@" + projectId + ".iam.gserviceaccount.com";
        member = "serviceAccount:" + email;
        credential = "Bearer " + tokens.mintImpersonatedToken(email, Instant.now().plusSeconds(300)).getTokenValue();
        channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), credential);
        caller = ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    @AfterEach
    void closeChannel() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void roleManagementRequiresIamRolesPermissionsAndDenialsLeaveRolesUnchanged() {
        String rolesPath = "/v1/" + project + "/roles";
        Map<String, Object> create = Map.of("roleId", "reader",
                "role", Map.of("title", "Reader", "includedPermissions", List.of("pubsub.topics.get")));

        expectDenied(given().header("Authorization", credential).contentType("application/json").body(create)
                .post(rolesPath).then(), 200);
        if (enforced()) {
            given().get(rolesPath + "/reader").then().statusCode(404);
            expectDenied(given().header("Authorization", credential).get(rolesPath).then(), 200);
        }

        grant(project, "roles/iam.roleViewer", null);
        expectDenied(given().header("Authorization", credential).contentType("application/json")
                .body(Map.of("roleId", "viewercreated")).post(rolesPath).then(), 200);
        if (enforced()) {
            given().get(rolesPath + "/viewercreated").then().statusCode(404);
        }
        given().contentType("application/json").body(create).post(rolesPath).then().statusCode(enforced() ? 200 : 409);
        String etag = given().header("Authorization", credential).get(rolesPath + "/reader")
                .then().statusCode(200).body("includedPermissions", equalTo(List.of("pubsub.topics.get")))
                .extract().path("etag");
        given().header("Authorization", credential).get(rolesPath)
                .then().statusCode(200).body("roles[0].name", equalTo(project + "/roles/reader"));
        expectDenied(given().header("Authorization", credential).contentType("application/json")
                .body(Map.of("includedPermissions", List.of("pubsub.topics.delete")))
                .queryParam("updateMask", "includedPermissions").patch(rolesPath + "/reader").then(), 200);
        expectDenied(given().header("Authorization", credential).delete(rolesPath + "/reader").then(), 200);
        if (enforced()) {
            StoredRole unchanged = roles.get(project + "/roles/reader");
            assertEquals(etag, unchanged.getEtag());
            assertEquals(List.of("pubsub.topics.get"), unchanged.getIncludedPermissions());
            assertNull(unchanged.getDeleted());
        }
        if (enforced()) {
            roles.delete(project + "/roles/reader", null);
            given().urlEncodingEnabled(false).header("Authorization", credential).contentType("application/json")
                    .body(Map.of()).post(rolesPath + "/reader:undelete")
                    .then().statusCode(403).body("error.status", equalTo("PERMISSION_DENIED"));
            assertEquals(Boolean.TRUE, roles.get(project + "/roles/reader").getDeleted());
            roles.undelete(project + "/roles/reader", null);
        }

        grant(project, "roles/iam.roleAdmin", null);
        given().header("Authorization", credential).contentType("application/json")
                .body(Map.of("roleId", "writer", "role", Map.of("includedPermissions", List.of("pubsub.topics.delete"))))
                .post(rolesPath).then().statusCode(200).body("name", equalTo(project + "/roles/writer"));
        given().header("Authorization", credential).contentType("application/json")
                .body(Map.of("title", "Writer")).queryParam("updateMask", "title")
                .patch(rolesPath + "/writer").then().statusCode(200).body("title", equalTo("Writer"));
        given().header("Authorization", credential).delete(rolesPath + "/writer")
                .then().statusCode(200).body("deleted", equalTo(true));
        given().urlEncodingEnabled(false).header("Authorization", credential).contentType("application/json")
                .body(Map.of()).post(rolesPath + "/writer:undelete").then().statusCode(200);
    }

    @Test
    void malformedAndExpiredFlociTokensCannotManageRoles() {
        roles.create(project, "reader", Map.of("title", "Reader"));
        StoredCredentialToken expired = tokens.mintImpersonatedToken(member.substring("serviceAccount:".length()),
                Instant.now().plusSeconds(300));
        expired.setExpireTime(Instant.now().minusSeconds(1));
        storageFactory.createGlobal("credential-tokens", "credential-tokens.json",
                new TypeReference<Map<String, StoredCredentialToken>>() {}).put(expired.getTokenValue(), expired);
        List<String> credentials = List.of("Bearer floci-gcp-impersonated-unknown", "Bearer " + expired.getTokenValue());
        for (int i = 0; i < credentials.size(); i++) {
            String authorization = credentials.get(i);
            ValidatableResponse create = given().header("Authorization", authorization)
                    .contentType("application/json").body(Map.of("roleId", "created" + i))
                    .post("/v1/" + project + "/roles").then();
            ValidatableResponse delete = given().header("Authorization", authorization)
                    .delete("/v1/" + project + "/roles/reader").then();
            if (enforced()) {
                create.statusCode(401).body("error.status", equalTo("UNAUTHENTICATED"));
                delete.statusCode(401).body("error.status", equalTo("UNAUTHENTICATED"));
                assertNull(roles.get(project + "/roles/reader").getDeleted());
            } else {
                create.statusCode(200);
                delete.statusCode(200);
                roles.undelete(project + "/roles/reader", null);
            }
        }
    }

    @Test
    void roleAdminInOneProjectCannotManageRolesInAnother() {
        String other = project + "-other";
        String otherRoles = "/v1/" + other + "/roles";
        roles.create(other, "reader", Map.of("title", "Reader"));
        String etag = roles.get(other + "/roles/reader").getEtag();
        grant(project, "roles/iam.roleAdmin", null);

        expectDenied(given().header("Authorization", credential).contentType("application/json")
                .body(Map.of("roleId", "created")).post(otherRoles).then(), 200);
        expectDenied(given().header("Authorization", credential).get(otherRoles).then(), 200);
        expectDenied(given().header("Authorization", credential).get(otherRoles + "/reader").then(), 200);
        expectDenied(given().header("Authorization", credential).contentType("application/json")
                .body(Map.of("title", "Escalated")).queryParam("updateMask", "title")
                .patch(otherRoles + "/reader").then(), 200);
        if (enforced()) {
            given().get(otherRoles + "/created").then().statusCode(404);
            assertEquals(etag, roles.get(other + "/roles/reader").getEtag());
        }
        expectDenied(given().header("Authorization", credential).delete(otherRoles + "/reader").then(), 200);
        if (enforced()) {
            assertNull(roles.get(other + "/roles/reader").getDeleted());
            roles.delete(other + "/roles/reader", null);
        }
        expectDenied(given().urlEncodingEnabled(false).header("Authorization", credential)
                .contentType("application/json").body(Map.of()).post(otherRoles + "/reader:undelete").then(), 200);
        if (enforced()) {
            assertEquals(Boolean.TRUE, roles.get(other + "/roles/reader").getDeleted());
        }
    }

    @Test
    void roleManagementConditionsPermitOnlyTheNamedRole() {
        roles.create(project, "allowed", Map.of());
        roles.create(project, "sibling", Map.of());
        grant(project, "roles/iam.roleAdmin", "resource.name == '" + project + "/roles/allowed'");

        given().header("Authorization", credential).get("/v1/" + project + "/roles/allowed").then().statusCode(200);
        given().header("Authorization", credential).delete("/v1/" + project + "/roles/allowed").then().statusCode(200);
        expectDenied(given().header("Authorization", credential).get("/v1/" + project + "/roles/sibling").then(), 200);
        expectDenied(given().header("Authorization", credential).delete("/v1/" + project + "/roles/sibling").then(), 200);
        expectDenied(given().header("Authorization", credential).contentType("application/json")
                .body(Map.of("roleId", "created")).post("/v1/" + project + "/roles").then(), 200);
        if (enforced()) {
            assertNull(roles.get(project + "/roles/sibling").getDeleted());
        }
    }

    @Test
    void customRoleBindingGrantsExactlyItsIncludedPermissionsOverRestAndGrpc() {
        String topic = project + "/topics/source";
        pubsub.createTopic(topic);
        createRoleViaApi(project, "topicreader", List.of("pubsub.topics.get"));
        grant(project, project + "/roles/topicreader", null);

        given().header("Authorization", credential).get("/v1/" + topic).then().statusCode(200);
        PublisherGrpc.PublisherBlockingStub publisher = PublisherGrpc.newBlockingStub(caller)
                .withDeadlineAfter(5, TimeUnit.SECONDS);
        assertEquals(topic, publisher.getTopic(GetTopicRequest.newBuilder().setTopic(topic).build()).getName());

        List<String> requested = List.of("pubsub.topics.get", "pubsub.topics.delete");
        List<String> expected = enforced() ? List.of("pubsub.topics.get") : requested;
        given().urlEncodingEnabled(false).header("Authorization", credential).contentType("application/json")
                .body(Map.of("permissions", requested)).post("/v1/" + topic + ":testIamPermissions")
                .then().statusCode(200).body("permissions", equalTo(expected));
        assertEquals(expected, IAMPolicyGrpc.newBlockingStub(caller).withDeadlineAfter(5, TimeUnit.SECONDS)
                .testIamPermissions(TestIamPermissionsRequest.newBuilder().setResource(topic)
                        .addAllPermissions(requested).build()).getPermissionsList());

        expectDenied(given().header("Authorization", credential).delete("/v1/" + topic).then(), 200);
        if (enforced()) {
            expectGrpcDenied(() -> publisher.deleteTopic(DeleteTopicRequest.newBuilder().setTopic(topic).build()));
            assertEquals(topic, pubsub.getTopic(topic).getName());
        }
    }

    @Test
    void customRoleGrantsThroughTheSchedulerAdapter() {
        String parent = project + "/locations/us-central1";
        String job = parent + "/jobs/nightly";
        given().contentType("application/json")
                .body(Map.of("name", job, "schedule", "0 3 * * *",
                        "httpTarget", Map.of("uri", "https://example.com/run", "httpMethod", "POST")))
                .post("/v1/" + parent + "/jobs").then().statusCode(200);
        createRoleViaApi(project, "jobreader", List.of("cloudscheduler.jobs.get"));
        grant(project, project + "/roles/jobreader", null);

        given().header("Authorization", credential).get("/v1/" + job).then().statusCode(200)
                .body("name", equalTo(job));
        expectDenied(given().header("Authorization", credential).delete("/v1/" + job).then(), 200);
        if (enforced()) {
            given().get("/v1/" + job).then().statusCode(200);
        }
    }

    @Test
    void customRoleAuthorizesIamServiceAccountOperationsItDeclares() {
        String email = "target@" + projectId + ".iam.gserviceaccount.com";
        policies.createServiceAccount(projectId, "target", "original", "");
        createRoleViaApi(project, "accountreader", List.of("iam.serviceAccounts.get"));
        grant(project, project + "/roles/accountreader", null);
        String account = "/v1/" + project + "/serviceAccounts/" + email;

        given().header("Authorization", credential).get(account).then().statusCode(200);
        expectDenied(given().header("Authorization", credential).contentType("application/json")
                .body(Map.of("displayName", "changed")).put(account).then(), 200);
        expectDenied(given().header("Authorization", credential).urlEncodingEnabled(false)
                .contentType("application/json").body(Map.of("scope", List.of("https://www.googleapis.com/auth/cloud-platform")))
                .post("/v1/projects/-/serviceAccounts/" + email + ":generateAccessToken").then(), 200);
        if (enforced()) {
            assertEquals("original", policies.getServiceAccount(projectId, "target").getDisplayName());
        }
    }

    @Test
    void roleEditsDisablingDeletionAndUndeletionApplyToExistingBindingsAndCredentials() {
        String topic = project + "/topics/source";
        pubsub.createTopic(topic);
        String role = "/v1/" + project + "/roles/topicreader";
        createRoleViaApi(project, "topicreader", List.of("pubsub.topics.get"));
        grant(project, project + "/roles/topicreader", null);
        Supplier<ValidatableResponse> read = () -> given().header("Authorization", credential)
                .get("/v1/" + topic).then();

        read.get().statusCode(200);
        patch(role, Map.of("includedPermissions", List.of("pubsub.topics.list")), "includedPermissions");
        expectDenied(read.get(), 200);
        patch(role, Map.of("includedPermissions", List.of("pubsub.topics.get")), "includedPermissions");
        read.get().statusCode(200);

        patch(role, Map.of("stage", "DISABLED"), "stage");
        expectDenied(read.get(), 200);
        patch(role, Map.of("stage", "GA"), "stage");
        read.get().statusCode(200);

        given().delete(role).then().statusCode(200).body("deleted", equalTo(true));
        expectDenied(read.get(), 200);
        given().urlEncodingEnabled(false).contentType("application/json").body(Map.of())
                .post(role + ":undelete").then().statusCode(200);
        read.get().statusCode(200);
    }

    @Test
    void projectRoleBoundOutsideItsProjectGrantsNothing() {
        String otherProject = project + "-other";
        String otherTopic = otherProject + "/topics/source";
        pubsub.createTopic(otherTopic);
        createRoleViaApi(project, "topicreader", List.of("pubsub.topics.get"));
        storeGrant(otherProject, project + "/roles/topicreader");

        expectDenied(given().header("Authorization", credential).get("/v1/" + otherTopic).then(), 200);
        if (enforced()) {
            expectGrpcDenied(() -> PublisherGrpc.newBlockingStub(caller).withDeadlineAfter(5, TimeUnit.SECONDS)
                    .getTopic(GetTopicRequest.newBuilder().setTopic(otherTopic).build()));
        }
        given().urlEncodingEnabled(false).header("Authorization", credential).contentType("application/json")
                .body(Map.of("permissions", List.of("pubsub.topics.get")))
                .post("/v1/" + otherTopic + ":testIamPermissions")
                .then().statusCode(200).body("permissions", equalTo(enforced() ? List.of() : List.of("pubsub.topics.get")));
    }

    @Test
    void organizationRoleGrantsOnlyInProjectsRecordedUnderItsOrganization() {
        String organization = "organizations/" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);
        createRoleViaApi(organization, "topicreader", List.of("pubsub.topics.get"));
        String orgRole = organization + "/roles/topicreader";
        Map<String, Map<String, String>> parents = Map.of(
                "in", Map.of("type", "organization", "id", organization.substring("organizations/".length())),
                "other", Map.of("type", "organization", "id", "1"),
                "folder", Map.of("type", "folder", "id", organization.substring("organizations/".length())));
        for (String suffix : List.of("in", "other", "folder", "none")) {
            String id = projectId + "-" + suffix;
            Map<String, Object> body = parents.containsKey(suffix)
                    ? Map.of("projectId", id, "parent", parents.get(suffix)) : Map.of("projectId", id);
            given().contentType("application/json").body(body).post("/v1/projects").then().statusCode(200);
            pubsub.createTopic("projects/" + id + "/topics/source");
            storeGrant("projects/" + id, orgRole);
        }

        given().header("Authorization", credential).get("/v1/projects/" + projectId + "-in/topics/source")
                .then().statusCode(200);
        for (String suffix : List.of("other", "folder", "none")) {
            expectDenied(given().header("Authorization", credential)
                    .get("/v1/projects/" + projectId + "-" + suffix + "/topics/source").then(), 200);
        }
    }

    @Test
    void organizationRoleMutationsAndListAreDeniedWithoutAnOrganizationGrant() {
        String organization = "organizations/" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);
        String name = organization + "/roles/reader";
        roles.create(organization, "reader", Map.of("title", "Reader"));
        String etag = roles.get(name).getEtag();

        expectDenied(given().header("Authorization", credential).get("/v1/" + organization + "/roles").then(), 200);
        expectDenied(given().header("Authorization", credential).contentType("application/json")
                .body(Map.of("title", "Escalated")).queryParam("updateMask", "title").patch("/v1/" + name).then(), 200);
        if (enforced()) {
            assertEquals(etag, roles.get(name).getEtag());
        }
        expectDenied(given().header("Authorization", credential).delete("/v1/" + name).then(), 200);
        if (enforced()) {
            assertNull(roles.get(name).getDeleted());
            roles.delete(name, null);
        }
        expectDenied(given().urlEncodingEnabled(false).header("Authorization", credential)
                .contentType("application/json").body(Map.of()).post("/v1/" + name + ":undelete").then(), 200);
        if (enforced()) {
            assertEquals(Boolean.TRUE, roles.get(name).getDeleted());
        }
    }

    @Test
    void roleAdminCanWidenACustomRoleItHoldsAsInGcp() {
        String topic = project + "/topics/source";
        pubsub.createTopic(topic);
        createRoleViaApi(project, "topicreader", List.of("pubsub.topics.get"));
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of("role", "roles/iam.roleAdmin", "members", List.of(member)),
                Map.of("role", project + "/roles/topicreader", "members", List.of(member))));
        policies.setPolicy(project, policy);

        expectDenied(given().header("Authorization", credential).delete("/v1/" + topic).then(), 200);
        if (!enforced()) {
            pubsub.createTopic(topic);
        }
        given().header("Authorization", credential).contentType("application/json")
                .body(Map.of("includedPermissions", List.of("pubsub.topics.get", "pubsub.topics.delete")))
                .queryParam("updateMask", "includedPermissions")
                .patch("/v1/" + project + "/roles/topicreader").then().statusCode(200);
        given().header("Authorization", credential).delete("/v1/" + topic).then().statusCode(200);
    }

    @Test
    void wronglyTypedRequestFieldsAreRejectedWithGcpMessages() {
        createRoleViaApi(project, "etagrole", List.of("pubsub.topics.get"));
        roles.delete(project + "/roles/etagrole", null);
        given().urlEncodingEnabled(false).contentType("application/json").body(Map.of("etag", 123))
                .post("/v1/" + project + "/roles/etagrole:undelete")
                .then().statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"))
                .body("error.message", equalTo("Invalid value at 'etag' (TYPE_BYTES), 123"));
        assertEquals(Boolean.TRUE, roles.get(project + "/roles/etagrole").getDeleted());
        given().contentType("application/json").body(Map.of("roleId", 5, "role", Map.of()))
                .post("/v1/" + project + "/roles")
                .then().statusCode(400).body("error.message", equalTo("Invalid value at 'role_id' (TYPE_STRING), 5"));
        given().contentType("application/json").body(Map.of("roleId", "typed", "role", "oops"))
                .post("/v1/" + project + "/roles")
                .then().statusCode(400).body("error.message",
                        equalTo("Invalid value at 'role' (type.googleapis.com/google.iam.admin.v1.Role), \"oops\""));
    }

    @Test
    void organizationRoleManagementEvaluatesTheStoredOrganizationPolicy() {
        String organization = "organizations/" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);
        Map<String, Object> create = Map.of("roleId", "reader", "role", Map.of("title", "Reader"));
        expectDenied(given().header("Authorization", credential).contentType("application/json").body(create)
                .post("/v1/" + organization + "/roles").then(), 200);
        if (enforced()) {
            given().get("/v1/" + organization + "/roles/reader").then().statusCode(404);
        }

        // The mixin accepts organization policy writes only from callers that bypass evaluation.
        Policy policy = Policy.newBuilder().addBindings(Binding.newBuilder()
                .setRole("roles/iam.organizationRoleAdmin").addMembers(member)).build();
        IAMPolicyGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS).setIamPolicy(
                SetIamPolicyRequest.newBuilder().setResource(organization).setPolicy(policy).build());
        if (enforced()) {
            given().header("Authorization", credential).contentType("application/json").body(create)
                    .post("/v1/" + organization + "/roles").then().statusCode(200);
        }
        given().header("Authorization", credential).get("/v1/" + organization + "/roles/reader")
                .then().statusCode(200).body("name", equalTo(organization + "/roles/reader"));
        expectGrpcStatusWhenEnforced(Status.Code.FAILED_PRECONDITION, () -> IAMPolicyGrpc.newBlockingStub(caller)
                .withDeadlineAfter(5, TimeUnit.SECONDS).getIamPolicy(GetIamPolicyRequest
                        .newBuilder().setResource(organization).build()));
    }

    @Test
    void unsupportedConditionOnAnApplicableCustomRoleIsRejected() {
        String topic = project + "/topics/source";
        pubsub.createTopic(topic);
        createRoleViaApi(project, "topicreader", List.of("pubsub.topics.get"));
        grant(project, project + "/roles/topicreader", "resource.name.matches('.*')");
        ValidatableResponse response = given().header("Authorization", credential).get("/v1/" + topic).then();
        if (enforced()) {
            response.statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));
        } else {
            response.statusCode(200);
        }
    }

    @Test
    void supportedConditionOnACustomRoleBindingLimitsItsGrantOverRestAndGrpc() {
        String allowed = project + "/topics/allowed";
        String excluded = project + "/topics/excluded";
        pubsub.createTopic(allowed);
        pubsub.createTopic(excluded);
        createRoleViaApi(project, "topicreader", List.of("pubsub.topics.get"));
        grant(project, project + "/roles/topicreader", "resource.name == '" + allowed + "'");
        PublisherGrpc.PublisherBlockingStub publisher = PublisherGrpc.newBlockingStub(caller)
                .withDeadlineAfter(5, TimeUnit.SECONDS);

        given().header("Authorization", credential).get("/v1/" + allowed).then().statusCode(200);
        assertEquals(allowed, publisher.getTopic(GetTopicRequest.newBuilder().setTopic(allowed).build()).getName());
        expectDenied(given().header("Authorization", credential).get("/v1/" + excluded).then(), 200);
        if (enforced()) {
            expectGrpcDenied(() -> publisher.getTopic(GetTopicRequest.newBuilder().setTopic(excluded).build()));
        }
    }

    @Test
    void undeleteAcceptsAnEmptyFormEncodedBody() {
        String organization = "organizations/" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000L);
        for (String parent : List.of(project, organization)) {
            createRoleViaApi(parent, "formrole", List.of("pubsub.topics.get"));
            given().delete("/v1/" + parent + "/roles/formrole").then().statusCode(200);
            given().urlEncodingEnabled(false).contentType("application/x-www-form-urlencoded").body("")
                    .post("/v1/" + parent + "/roles/formrole:undelete").then().statusCode(200);
            assertNull(roles.get(parent + "/roles/formrole").getDeleted());
        }
    }

    @Test
    void inactiveCustomRolesWithUnsupportedConditionsDoNotBlockAKnownGrant() {
        String topic = project + "/topics/source";
        pubsub.createTopic(topic);
        createRoleViaApi(project, "disabledrole", List.of("pubsub.topics.get"));
        createRoleViaApi(project, "deletedrole", List.of("pubsub.topics.get"));
        roles.update(project + "/roles/disabledrole", Map.of("stage", "DISABLED"), "stage");
        roles.delete(project + "/roles/deletedrole", null);
        createRoleViaApi(project + "-other", "foreignrole", List.of("pubsub.topics.get"));
        Map<String, Object> unsupported = Map.of("title", "scope", "expression", "resource.name.matches('.*')");
        StoredPolicy policy = new StoredPolicy();
        policy.setVersion(3);
        policy.setBindings(List.of(
                Map.of("role", project + "/roles/disabledrole", "members", List.of(member), "condition", unsupported),
                Map.of("role", project + "/roles/deletedrole", "members", List.of(member), "condition", unsupported),
                Map.of("role", project + "-other/roles/foreignrole", "members", List.of(member),
                        "condition", unsupported),
                Map.of("role", "roles/pubsub.viewer", "members", List.of(member))));
        storePolicy(project, policy);

        given().header("Authorization", credential).get("/v1/" + topic).then().statusCode(200);

        roles.update(project + "/roles/disabledrole", Map.of("stage", "GA"), "stage");
        ValidatableResponse reenabled = given().header("Authorization", credential).get("/v1/" + topic).then();
        if (enforced()) {
            reenabled.statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));
        } else {
            reenabled.statusCode(200);
        }
    }

    @Test
    void customRolesApplyToBucketsThroughTheOwningProjectButNotBucketPolicies() {
        String bucket = "cr-bucket-" + UUID.randomUUID().toString().substring(0, 8);
        given().contentType("application/json").body(Map.of("name", bucket))
                .post("/storage/v1/b?project=" + projectId).then().statusCode(200);
        createRoleViaApi(project, "bucketreader", List.of("storage.buckets.get"));
        String role = project + "/roles/bucketreader";

        grant(project, role, null);
        given().header("Authorization", credential).get("/storage/v1/b/" + bucket).then().statusCode(200);

        policies.setPolicy(project, new StoredPolicy());
        grant("buckets/" + bucket, role, null);
        expectDenied(given().header("Authorization", credential).get("/storage/v1/b/" + bucket).then(), 200);
    }

    @Test
    void projectSetIamPolicyRejectsMissingAndForeignCustomRoles() {
        String path = "/v1/" + project + ":setIamPolicy";
        createRoleViaApi(project + "-other", "foreign", List.of("pubsub.topics.get"));
        createRoleViaApi(project, "reader", List.of("pubsub.topics.get"));

        expectRejected(setIamPolicy(path, project + "/roles/missing", member),
                "Role (" + project + "/roles/missing) does not exist in resource's hierarchy.");
        expectRejected(setIamPolicy(path, "organizations/4242/roles/missing", member),
                "Role (organizations/4242/roles/missing) does not exist in resource's hierarchy.");
        expectRejected(setIamPolicy(path, project + "-other/roles/foreign", member),
                "Role (" + project + "-other/roles/foreign) does not exist in the resource's hierarchy.");
        setIamPolicy(path, project + "/roles/reader", member).statusCode(200);
        assertEquals(List.of(project + "/roles/reader"),
                policies.getPolicy(project).getBindings().stream().map(binding -> binding.get("role")).toList());
    }

    @Test
    void deletedCustomRoleKeepsExistingGrantsButRejectsNewOnes() {
        String path = "/v1/" + project + ":setIamPolicy";
        String role = project + "/roles/reader";
        String other = "serviceAccount:other@" + projectId + ".iam.gserviceaccount.com";
        createRoleViaApi(project, "reader", List.of("pubsub.topics.get"));
        setIamPolicy(path, role, member).statusCode(200);
        roles.delete(role, null);

        setIamPolicy(path, role, member).statusCode(200);
        expectRejected(setIamPolicy(path, role, member, other),
                "Cannot add new grants to deleted role (" + role + ").");
        roles.undelete(role, null);
        setIamPolicy(path, role, member, other).statusCode(200);
    }

    @Test
    void organizationRoleBindingsFollowTheRecordedProjectParent() {
        String organizationId = String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L));
        String orgRole = "organizations/" + organizationId + "/roles/topicreader";
        createRoleViaApi("organizations/" + organizationId, "topicreader", List.of("pubsub.topics.get"));
        Map<String, Map<String, String>> parents = Map.of(
                "in", Map.of("type", "organization", "id", organizationId),
                "other", Map.of("type", "organization", "id", "1"),
                "folder", Map.of("type", "folder", "id", organizationId));
        for (String suffix : List.of("in", "other", "folder", "none")) {
            String id = projectId + "-" + suffix;
            Map<String, Object> body = parents.containsKey(suffix)
                    ? Map.of("projectId", id, "parent", parents.get(suffix)) : Map.of("projectId", id);
            given().contentType("application/json").body(body).post("/v1/projects").then().statusCode(200);
        }

        setIamPolicy("/v1/projects/" + projectId + "-in:setIamPolicy", orgRole, member).statusCode(200);
        setIamPolicy("/v1/projects/" + projectId + "-folder:setIamPolicy", orgRole, member).statusCode(200);
        for (String suffix : List.of("other", "none")) {
            expectRejected(setIamPolicy("/v1/projects/" + projectId + "-" + suffix + ":setIamPolicy", orgRole, member),
                    "Role (" + orgRole + ") does not exist in the resource's hierarchy.");
        }
    }

    @Test
    void resourceSetIamPolicyRejectsForeignCustomRolesOverRestAndGrpc() {
        String topic = project + "/topics/source";
        pubsub.createTopic(topic);
        policies.createServiceAccount(projectId, "target", "target", "");
        String account = project + "/serviceAccounts/target@" + projectId + ".iam.gserviceaccount.com";
        String foreign = project + "-other/roles/foreign";
        createRoleViaApi(project + "-other", "foreign", List.of("pubsub.topics.get"));
        String message = "Role (" + foreign + ") does not exist in the resource's hierarchy.";

        expectRejected(setIamPolicy("/v1/" + topic + ":setIamPolicy", foreign, member), message);
        expectRejected(setIamPolicy("/v1/" + account + ":setIamPolicy", foreign, member), message);
        SetIamPolicyRequest request = SetIamPolicyRequest.newBuilder().setResource(topic)
                .setPolicy(Policy.newBuilder().addBindings(Binding.newBuilder().setRole(foreign).addMembers(member)))
                .build();
        IAMPolicyGrpc.IAMPolicyBlockingStub mixin = IAMPolicyGrpc.newBlockingStub(channel)
                .withDeadlineAfter(5, TimeUnit.SECONDS);
        if (enforced()) {
            StatusRuntimeException error = assertThrows(StatusRuntimeException.class, () -> mixin.setIamPolicy(request));
            assertEquals(Status.Code.INVALID_ARGUMENT, error.getStatus().getCode());
            assertEquals(message, error.getStatus().getDescription());
        } else {
            mixin.setIamPolicy(request);
        }
    }

    /** Covers the reset path only; eager store registration at boot is not observable from a QuarkusTest. */
    @Test
    void stateResetClearsCustomRoles() {
        createRoleViaApi(project, "transient", List.of("pubsub.topics.get"));
        given().post("/_floci-gcp/state/reset").then().statusCode(200);
        given().get("/v1/" + project + "/roles/transient").then().statusCode(404);
    }

    private void createRoleViaApi(String parent, String roleId, List<String> permissions) {
        given().contentType("application/json")
                .body(Map.of("roleId", roleId, "role", Map.of("includedPermissions", permissions, "stage", "GA")))
                .post("/v1/" + parent + "/roles").then().statusCode(200);
    }

    private void patch(String role, Map<String, Object> body, String mask) {
        given().contentType("application/json").body(body).queryParam("updateMask", mask)
                .patch(role).then().statusCode(200);
    }

    private void grant(String resource, String role, String expression) {
        StoredPolicy policy = new StoredPolicy();
        if (expression == null) {
            policy.setBindings(List.of(Map.of("role", role, "members", List.of(member))));
        } else {
            policy.setVersion(3);
            policy.setBindings(List.of(Map.of("role", role, "members", List.of(member),
                    "condition", Map.of("title", "scope", "expression", expression))));
        }
        policies.setPolicy(resource, policy);
    }

    private ValidatableResponse setIamPolicy(String path, String role, String... members) {
        return given().urlEncodingEnabled(false).contentType("application/json")
                .body(Map.of("policy", Map.of("bindings", List.of(Map.of("role", role, "members", List.of(members))))))
                .post(path).then();
    }

    private void expectRejected(ValidatableResponse response, String message) {
        if (enforced()) {
            response.statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"))
                    .body("error.message", equalTo(message));
        } else {
            response.statusCode(200);
        }
    }

    /** Seeds a policy as stored before enforce mode, bypassing the write-time binding validation. */
    private void storePolicy(String resource, StoredPolicy policy) {
        storageFactory.createGlobal("iam-policies", "iam-policies.json",
                new TypeReference<Map<String, StoredPolicy>>() {}).put("policy:" + resource, policy);
    }

    private void storeGrant(String resource, String role) {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of("role", role, "members", List.of(member))));
        storePolicy(resource, policy);
    }

    private void expectDenied(ValidatableResponse response, int disabledStatus) {
        if (enforced()) {
            response.statusCode(403).body("error.status", equalTo("PERMISSION_DENIED"));
        } else {
            response.statusCode(disabledStatus);
        }
    }

    private void expectGrpcDenied(Runnable call) {
        StatusRuntimeException error = assertThrows(StatusRuntimeException.class, call::run);
        assertEquals(Status.Code.PERMISSION_DENIED, error.getStatus().getCode());
    }

    private void expectGrpcStatusWhenEnforced(Status.Code code, Runnable call) {
        if (enforced()) {
            StatusRuntimeException error = assertThrows(StatusRuntimeException.class, call::run);
            assertEquals(code, error.getStatus().getCode());
        } else {
            call.run();
        }
    }
}
