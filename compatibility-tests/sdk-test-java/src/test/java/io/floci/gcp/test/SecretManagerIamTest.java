package io.floci.gcp.test;

import com.google.api.gax.rpc.NotFoundException;
import com.google.cloud.secretmanager.v1.CreateSecretRequest;
import com.google.cloud.secretmanager.v1.DeleteSecretRequest;
import com.google.cloud.secretmanager.v1.ProjectName;
import com.google.cloud.secretmanager.v1.Replication;
import com.google.cloud.secretmanager.v1.Secret;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretName;
import com.google.iam.v1.Binding;
import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import com.google.iam.v1.TestIamPermissionsRequest;
import com.google.iam.v1.TestIamPermissionsResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecretManagerIamTest {

    private static final String PROJECT_ID = TestFixtures.projectId();
    private static final String SECRET_ID = TestFixtures.uniqueName("iam-probe-secret");
    private static final String ROLE = "roles/secretmanager.secretAccessor";
    private static final String MEMBER = "user:probe@example.com";

    private static SecretManagerServiceClient client;

    @BeforeAll
    static void setUp() throws IOException {
        client = TestFixtures.secretManagerClient();
    }

    @AfterAll
    static void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @Order(1)
    void createSecret() {
        Secret secret = Secret.newBuilder()
                .setReplication(Replication.newBuilder()
                        .setAutomatic(Replication.Automatic.newBuilder().build())
                        .build())
                .build();

        Secret created = client.createSecret(CreateSecretRequest.newBuilder()
                .setParent(ProjectName.of(PROJECT_ID).toString())
                .setSecretId(SECRET_ID)
                .setSecret(secret)
                .build());

        assertThat(created.getName()).endsWith("/secrets/" + SECRET_ID);
    }

    @Test
    @Order(2)
    void setIamPolicy() {
        Policy policy = client.setIamPolicy(SetIamPolicyRequest.newBuilder()
                .setResource(resource())
                .setPolicy(Policy.newBuilder()
                        .addBindings(Binding.newBuilder().setRole(ROLE).addMembers(MEMBER))
                        .build())
                .build());

        assertThat(policy.getBindingsList()).hasSize(1);
        assertThat(policy.getBindings(0).getRole()).isEqualTo(ROLE);
        assertThat(policy.getBindings(0).getMembersList()).containsExactly(MEMBER);
        assertThat(policy.getEtag()).isNotEmpty();
    }

    @Test
    @Order(3)
    void getIamPolicy() {
        Policy policy = client.getIamPolicy(GetIamPolicyRequest.newBuilder()
                .setResource(resource())
                .build());

        assertThat(policy.getBindingsList()).hasSize(1);
        assertThat(policy.getBindings(0).getMembersList()).containsExactly(MEMBER);
    }

    /** The emulator has no caller identity, so an existing resource grants every requested permission. */
    @Test
    @Order(4)
    void testIamPermissionsGrantsAllRequestedPermissions() {
        TestIamPermissionsResponse response = client.testIamPermissions(
                TestIamPermissionsRequest.newBuilder()
                        .setResource(resource())
                        .addPermissions("secretmanager.versions.access")
                        .addPermissions("secretmanager.secrets.delete")
                        .build());

        assertThat(response.getPermissionsList())
                .containsExactly("secretmanager.versions.access", "secretmanager.secrets.delete");
    }

    @Test
    @Order(5)
    void testIamPermissionsOnUnknownSecretReturnsEmptySet() {
        TestIamPermissionsResponse response = client.testIamPermissions(
                TestIamPermissionsRequest.newBuilder()
                        .setResource(absentResource())
                        .addPermissions("secretmanager.versions.access")
                        .build());

        assertThat(response.getPermissionsList()).isEmpty();
    }

    @Test
    @Order(6)
    void getIamPolicyOnUnknownSecretIsNotFound() {
        GetIamPolicyRequest request = GetIamPolicyRequest.newBuilder()
                .setResource(absentResource())
                .build();

        assertThatThrownBy(() -> client.getIamPolicy(request))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @Order(7)
    void deletingSecretRemovesItsPolicy() {
        client.deleteSecret(DeleteSecretRequest.newBuilder().setName(resource()).build());

        GetIamPolicyRequest request = GetIamPolicyRequest.newBuilder()
                .setResource(resource())
                .build();

        assertThatThrownBy(() -> client.getIamPolicy(request))
                .isInstanceOf(NotFoundException.class);
    }

    private static String absentResource() {
        return SecretName.of(PROJECT_ID, SECRET_ID + "-absent").toString();
    }

    private static String resource() {
        return SecretName.of(PROJECT_ID, SECRET_ID).toString();
    }
}
